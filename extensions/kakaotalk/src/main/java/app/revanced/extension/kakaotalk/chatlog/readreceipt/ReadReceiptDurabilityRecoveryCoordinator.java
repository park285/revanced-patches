package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.nonNegative;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.requireActive;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.required;

final class ReadReceiptDurabilityRecoveryCoordinator {
    private static final long RETRY_INITIAL_DELAY_MS = 250L;
    private static final long RETRY_MAX_DELAY_MS = 30_000L;

    private final Object serializationLock;
    private final Store store;
    private final RetryScheduler retryScheduler;
    private final RetryWork retryWork;
    private final CounterJournal counterJournal;
    private final PendingBatchJournal pendingBatchJournal;
    private final RuntimeFailureSupervisor runtimeFailureSupervisor;
    private final PendingAppendAttempt appendAttempt;
    private final Object retryLock = new Object();
    private boolean retryScheduled;
    private int retryAttempt;

    ReadReceiptDurabilityRecoveryCoordinator(
            Object serializationLock,
            Store store,
            RetryScheduler retryScheduler,
            RetryWork retryWork,
            CounterJournal counterJournal,
            PendingBatchJournal pendingBatchJournal,
            RuntimeFailureSupervisor runtimeFailureSupervisor,
            PendingAppendAttempt appendAttempt
    ) {
        this.serializationLock = required(serializationLock);
        this.store = required(store);
        this.retryScheduler = required(retryScheduler);
        this.retryWork = required(retryWork);
        this.counterJournal = required(counterJournal);
        this.pendingBatchJournal = pendingBatchJournal;
        this.runtimeFailureSupervisor = required(runtimeFailureSupervisor);
        this.appendAttempt = required(appendAttempt);
    }

    void startIfRequired() {
        boolean recoveryRequired;
        try {
            recoveryRequired = counterJournal.load() != null
                    || pendingBatchJournal != null && pendingBatchJournal.load() != null
                    || retryWork.recoveryRequiredOnStart();
        } catch (RuntimeException exception) {
            throw failClosed(pendingBatchJournal == null
                    ? new DurabilityContractException()
                    : new PendingJournalContractException());
        }
        if (recoveryRequired) requestRetry();
    }

    AppendResult append(BatchInput input) {
        if (pendingBatchJournal != null) return appendDurably(required(input));
        return appendAttempt.run(required(input), () -> { }, true);
    }

    void recordUncertainOutcome(long eventCount, long batchCount) {
        updateCounters(eventCount, batchCount, CounterKind.UNCERTAIN);
    }

    void recordCaptureDrop(long eventCount, long batchCount) {
        updateCounters(eventCount, batchCount, CounterKind.CAPTURE_DROP);
    }

    void requestRetry() {
        long delay;
        synchronized (retryLock) {
            if (retryScheduled) return;
            int shift = Math.min(retryAttempt, 16);
            long candidate = RETRY_INITIAL_DELAY_MS << shift;
            delay = Math.min(candidate, RETRY_MAX_DELAY_MS);
            retryAttempt++;
            retryScheduled = true;
        }
        RuntimeException rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                retryScheduler.scheduleGuaranteed(delay, this::runRetry);
                return;
            } catch (RuntimeException exception) {
                rejected = exception;
            }
        }
        synchronized (retryLock) {
            retryScheduled = false;
        }
        if (rejected != null) throw failClosed(new RetrySchedulingException());
    }

    RuntimeException failClosed(RuntimeException failure) {
        try {
            runtimeFailureSupervisor.restartRequired(failure);
        } catch (RuntimeException supervisorFailure) {
            failure.addSuppressed(supervisorFailure);
        }
        return failure;
    }

    private void runRetry() {
        synchronized (retryLock) {
            retryScheduled = false;
        }
        boolean complete;
        synchronized (serializationLock) {
            complete = drainPendingAppend();
        }
        if (complete) {
            synchronized (serializationLock) {
                complete = drainPendingCounters();
            }
        }
        if (complete) {
            synchronized (serializationLock) {
                try {
                    complete = retryWork.runOnce();
                } catch (DurabilityContractException exception) {
                    throw failClosed(exception);
                } catch (RuntimeException exception) {
                    complete = false;
                }
            }
        }
        if (complete) {
            synchronized (retryLock) {
                retryAttempt = 0;
            }
        } else {
            requestRetry();
        }
    }

    private void updateCounters(long eventCount, long batchCount, CounterKind kind) {
        nonNegative(eventCount);
        nonNegative(batchCount);
        applyCounterDelta(eventCount, batchCount, kind);
        requestRetry();
    }

    private AppendResult appendDurably(BatchInput input) {
        PendingBatchRecord record = loadPendingBatch();
        if (record != null && record.terminal()) {
            boolean same = record.sameInput(input);
            AppendResult terminal = terminalResult(record);
            if (!drainPendingRecord(record)) {
                return same ? terminal
                        : new AppendResult(AppendOutcome.QUEUE_OCCUPIED, 0, 0);
            }
            if (same) return terminal;
        }
        record = loadPendingBatch();
        if (record == null) {
            try {
                pendingBatchJournal.publishPending(input);
            } catch (RuntimeException exception) {
                throw failClosed(new PendingJournalContractException());
            }
            record = loadPendingBatch();
        }
        if (record == null || !record.sameInput(input)) {
            return new AppendResult(AppendOutcome.QUEUE_OCCUPIED, 0, 0);
        }
        AppendResult result;
        if (record.state == PendingBatchState.PENDING) {
            result = appendAttempt.run(input, () -> markCommitting(input), false);
        } else if (record.state == PendingBatchState.COMMITTING) {
            result = reconcileCommitting(record);
        } else {
            drainPendingRecord(record);
            return terminalResult(record);
        }
        if (result.outcome == AppendOutcome.COMMITTED
                || result.outcome == AppendOutcome.CONFIRMED_LOSS) {
            finishSuccess(input);
            return result;
        }
        if (result.outcome == AppendOutcome.UNCERTAIN) {
            finishUncertain(input);
            return result;
        }
        requestRetry();
        return new AppendResult(AppendOutcome.QUEUED, 0, 0);
    }

    private boolean drainPendingAppend() {
        if (pendingBatchJournal == null) return true;
        PendingBatchRecord record = loadPendingBatch();
        if (record == null) return true;
        if (record.terminal()) return drainPendingRecord(record);
        AppendResult result = record.state == PendingBatchState.PENDING
                ? appendAttempt.run(record.input, () -> markCommitting(record.input), false)
                : reconcileCommitting(record);
        if (result.outcome == AppendOutcome.COMMITTED
                || result.outcome == AppendOutcome.CONFIRMED_LOSS) {
            finishSuccess(record.input);
            return loadPendingBatch() == null;
        }
        if (result.outcome == AppendOutcome.UNCERTAIN) {
            finishUncertain(record.input);
            return loadPendingBatch() == null;
        }
        return false;
    }

    private AppendResult reconcileCommitting(PendingBatchRecord record) {
        try {
            ReconcileResult reconciliation = store.reconcileAppend(record.input);
            if (reconciliation.result != null) return reconciliation.result;
            return reconciliation.absenceProven
                    ? new AppendResult(AppendOutcome.UNCERTAIN, 0, 0)
                    : new AppendResult(AppendOutcome.STORAGE_UNAVAILABLE, 0, 0);
        } catch (RuntimeException exception) {
            return new AppendResult(AppendOutcome.STORAGE_UNAVAILABLE, 0, 0);
        }
    }

    private void markCommitting(BatchInput input) {
        try {
            pendingBatchJournal.markCommittingExact(input);
        } catch (RuntimeException exception) {
            throw failClosed(new PendingJournalContractException());
        }
    }

    private void finishSuccess(BatchInput input) {
        try {
            pendingBatchJournal.clearCommittingExact(input);
        } catch (RuntimeException exception) {
            requestRetry();
        }
    }

    private void finishUncertain(BatchInput input) {
        CounterTarget target = counterTarget(input.events.size(), 1, CounterKind.UNCERTAIN);
        try {
            pendingBatchJournal.markTerminalExact(input,
                    PendingBatchState.TERMINAL_UNCERTAIN, target);
        } catch (RuntimeException exception) {
            throw failClosed(new PendingJournalContractException());
        }
        drainPendingRecord(loadPendingBatch());
    }

    private boolean drainPendingRecord(PendingBatchRecord record) {
        if (record == null) return true;
        if (!record.terminal()) return false;
        if (record.state == PendingBatchState.TERMINAL_UNCERTAIN
                && !applyCounterTarget(record.counterTarget)) return false;
        return clearPendingTerminal(record);
    }

    private boolean clearPendingTerminal(PendingBatchRecord record) {
        if (record == null) return true;
        try {
            pendingBatchJournal.clearTerminalExact(record);
            return pendingBatchJournal.load() == null;
        } catch (RuntimeException exception) {
            requestRetry();
            return false;
        }
    }

    private PendingBatchRecord loadPendingBatch() {
        try {
            return pendingBatchJournal.load();
        } catch (RuntimeException exception) {
            throw failClosed(new PendingJournalContractException());
        }
    }

    private CounterTarget counterTarget(long eventCount, long batchCount, CounterKind kind) {
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            requireActive(snapshot);
            CounterTarget pending = counterJournal.load();
            Counters base = snapshot.state.counters;
            if (pending != null) {
                if (!pending.matches(snapshot.state)) throw new StorageException();
                base = base.max(pending.counters);
            }
            Counters target = kind == CounterKind.UNCERTAIN
                    ? base.addUncertain(eventCount, batchCount)
                    : base.addCaptureDrop(eventCount, batchCount);
            transaction.commit();
            return new CounterTarget(snapshot.state.installationId,
                    snapshot.state.streamEpoch, target);
        }
    }

    private boolean applyCounterTarget(CounterTarget pending) {
        if (pending == null) return false;
        StateRecord target = null;
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            requireActive(snapshot);
            if (!pending.matches(snapshot.state)) throw new StorageException();
            target = snapshot.state.withCounters(snapshot.state.counters.max(pending.counters));
            transaction.updateState(target);
            transaction.commit();
        } catch (RuntimeException exception) {
            return target != null && counterCommitIsDurable(target);
        }
        return true;
    }

    private static AppendResult terminalResult(PendingBatchRecord record) {
        if (record.state == PendingBatchState.TERMINAL_COMMITTED) {
            return new AppendResult(AppendOutcome.COMMITTED, 0, 0);
        }
        if (record.state == PendingBatchState.TERMINAL_CONFIRMED_LOSS) {
            return new AppendResult(AppendOutcome.CONFIRMED_LOSS, 0, 0);
        }
        return new AppendResult(AppendOutcome.UNCERTAIN, 0, 0);
    }

    private boolean applyCounterDelta(long eventCount, long batchCount, CounterKind kind) {
        StateRecord target = null;
        CounterTarget journalTarget = null;
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            requireActive(snapshot);
            CounterTarget pending = counterJournal.load();
            Counters base = snapshot.state.counters;
            if (pending != null) {
                if (!pending.matches(snapshot.state)) throw new StorageException();
                base = base.max(pending.counters);
            }
            Counters counters = kind == CounterKind.UNCERTAIN
                    ? base.addUncertain(eventCount, batchCount)
                    : base.addCaptureDrop(eventCount, batchCount);
            target = snapshot.state.withCounters(counters);
            journalTarget = new CounterTarget(target.installationId, target.streamEpoch,
                    target.counters);
            try {
                counterJournal.storeGuaranteed(journalTarget);
            } catch (RuntimeException exception) {
                throw failClosed(new DurabilityContractException());
            }
            transaction.updateState(target);
            transaction.commit();
        } catch (RuntimeException exception) {
            if (exception instanceof DurabilityContractException) throw exception;
            if (journalTarget == null) throw failClosed(new DurabilityContractException());
            if (target == null || !counterCommitIsDurable(target)) return false;
        }
        clearCounterJournal(journalTarget);
        return true;
    }

    private boolean drainPendingCounters() {
        CounterTarget pending;
        try {
            pending = counterJournal.load();
        } catch (RuntimeException exception) {
            return false;
        }
        if (pending == null) return true;
        StateRecord target = null;
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            requireActive(snapshot);
            if (!pending.matches(snapshot.state)) throw new StorageException();
            target = snapshot.state.withCounters(snapshot.state.counters.max(pending.counters));
            transaction.updateState(target);
            transaction.commit();
        } catch (RuntimeException exception) {
            if (target == null || !counterCommitIsDurable(target)) return false;
        }
        clearCounterJournal(pending);
        return true;
    }

    private void clearCounterJournal(CounterTarget expected) {
        try {
            counterJournal.clearExact(expected);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean counterCommitIsDurable(StateRecord target) {
        try {
            Snapshot snapshot = store.readControl();
            requireActive(snapshot);
            return snapshot.state.installationId.equals(target.installationId)
                    && snapshot.state.streamEpoch.equals(target.streamEpoch)
                    && snapshot.state.lastAckedSequence == target.lastAckedSequence
                    && snapshot.state.highestIssuedSequence == target.highestIssuedSequence
                    && snapshot.state.nextSequence == target.nextSequence
                    && snapshot.state.counters.atLeast(target.counters);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private enum CounterKind {
        UNCERTAIN,
        CAPTURE_DROP
    }
}

interface PendingAppendAttempt {
    AppendResult run(BatchInput input, PendingCommitBarrier commitBarrier,
                     boolean countUncertain);
}

interface PendingCommitBarrier {
    void beforeCommit();
}
