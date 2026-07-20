package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.List;
import java.util.UUID;

interface Store {
    default Snapshot readSnapshot() {
        throw new UnsupportedOperationException("bounded store view required");
    }

    default Snapshot readControl() {
        return readSnapshot();
    }

    default ReconcileResult reconcileAppend(BatchInput input) {
        return ReadReceiptDurableStream.reconcileAppendSnapshot(input, readSnapshot());
    }

    default Snapshot readAckEvidence(LeaseRecord lease) {
        ReadReceiptDurableModelSupport.required(lease);
        return readSnapshot();
    }

    Transaction beginImmediate();
}

interface Transaction extends AutoCloseable {
    Snapshot readSnapshot();

    default Snapshot readControl() {
        return readSnapshot();
    }

    default Snapshot readLeaseWindow() {
        return readSnapshot();
    }

    default BatchSelection selectOldestCompleteUnleasedBatch() {
        return ReadReceiptDurableStream.selectOldestCompleteUnleasedBatch(readSnapshot());
    }

    default LossSelection selectOldestUnleasedLossRun() {
        return ReadReceiptDurableStream.oldestUnleasedLossRun(readSnapshot());
    }

    default List<BatchRecord> selectDeletableBatches(Snapshot snapshot,
                                                     LeaseProjection projection) {
        return ReadReceiptDurableStream.selectDeletableBatches(snapshot, projection);
    }

    void insertBatch(BatchRecord batch);

    void insertEvent(EventRecord event);

    void insertLoss(LossRecord loss);

    void updateState(StateRecord state);

    void insertLease(LeaseRecord lease);

    int deleteEventsExact(List<EventRecord> expected);

    int deleteLossesExact(List<LossRecord> expected);

    int deleteUnreferencedBatchesExact(List<BatchRecord> candidates);

    int deleteLeaseExact(LeaseRecord expected);

    int replaceEventsWithLossExact(BatchRecord batch, List<EventRecord> events,
                                   LossRecord loss);

    default int replaceBatchSelectionWithLossExact(BatchSelection selection,
                                                   LossRecord loss) {
        return replaceEventsWithLossExact(selection.batch, selection.events, loss);
    }

    default int replaceLossSelectionWithAggregateExact(LossSelection selection,
                                                       LossRecord aggregate) {
        ReadReceiptDurableModelSupport.required(selection);
        ReadReceiptDurableModelSupport.required(aggregate);
        int removed = deleteLossesExact(selection.losses);
        if (removed != selection.losses.size()) throw new StorageException();
        int removedBatches = deleteUnreferencedBatchesExact(selection.batches);
        if (removedBatches != selection.batches.size()) throw new StorageException();
        insertLoss(aggregate);
        return removed;
    }

    void poison(String category);

    void commit();

    @Override
    void close();
}

interface IdSource {
    UUID next();
}

interface Clock {
    long nowMs();
}

interface RetryScheduler {
    void scheduleGuaranteed(long delayMs, Runnable work);
}

interface RetryWork {
    boolean runOnce();

    default boolean recoveryRequiredOnStart() {
        return false;
    }
}

interface RuntimeFailureSupervisor {
    void restartRequired(RuntimeException failure);
}

interface CounterJournal {
    CounterTarget load();

    void storeGuaranteed(CounterTarget target);

    void clearExact(CounterTarget expected);
}

interface PendingBatchJournal {
    PendingBatchRecord load();

    void publishPending(BatchInput input);

    void markCommittingExact(BatchInput expected);

    void clearCommittingExact(BatchInput expected);

    void markTerminalExact(BatchInput expected, PendingBatchState state,
                           CounterTarget counterTarget);

    void clearTerminalExact(PendingBatchRecord expected);
}
