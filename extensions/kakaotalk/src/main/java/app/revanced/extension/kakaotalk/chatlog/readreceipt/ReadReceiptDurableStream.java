package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.exactDigest;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.requireActive;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.required;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.sameNullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ReadReceiptDurableStream {
    static final long MAX_EVENT_ROWS = 250_000L;
    static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_LEASE_RECORDS = 256;

    private final Store store;
    private final IdSource idSource;
    private final Clock clock;
    private final ReadReceiptDurabilityRecoveryCoordinator recovery;

    ReadReceiptDurableStream(Store store, IdSource idSource, Clock clock,
                             RetryScheduler retryScheduler, RetryWork retryWork,
                             CounterJournal counterJournal) {
        this(store, idSource, clock, retryScheduler, retryWork, counterJournal,
                null, failure -> { });
    }

    ReadReceiptDurableStream(Store store, IdSource idSource, Clock clock,
                             RetryScheduler retryScheduler, RetryWork retryWork,
                             CounterJournal counterJournal,
                             RuntimeFailureSupervisor runtimeFailureSupervisor) {
        this(store, idSource, clock, retryScheduler, retryWork, counterJournal,
                null, runtimeFailureSupervisor);
    }

    ReadReceiptDurableStream(Store store, IdSource idSource, Clock clock,
                             RetryScheduler retryScheduler, RetryWork retryWork,
                             CounterJournal counterJournal, PendingBatchJournal pendingBatchJournal,
                             RuntimeFailureSupervisor runtimeFailureSupervisor) {
        this.store = required(store);
        this.idSource = required(idSource);
        this.clock = required(clock);
        this.recovery = new ReadReceiptDurabilityRecoveryCoordinator(
                this, this.store, retryScheduler, retryWork, counterJournal,
                pendingBatchJournal, runtimeFailureSupervisor, this::appendAttempt);
        recovery.startIfRequired();
    }

    synchronized AppendResult append(BatchInput input) {
        return recovery.append(required(input));
    }

    private AppendResult appendAttempt(BatchInput input, PendingCommitBarrier commitBarrier,
                                       boolean countUncertain) {
        required(input);
        AppendResult result;
        boolean commitAttempted = false;
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            requireActive(snapshot);
            StateRecord state = snapshot.state;
            long first = state.nextSequence;
            long last = Math.addExact(first, input.events.size() - 1L);
            long next = Math.addExact(last, 1L);
            ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                    input.batchId, first, last, input.events.size(), input.persistedAtMs,
                    input.persistedElapsedMs, input.captureSessionId, input.captureBootId,
                    input.sourceEpochToken);
            BatchRecord batch = new BatchRecord(state.streamEpoch, metadata,
                    ReadReceiptProtocolV2.metadataDigest(metadata));
            transaction.insertBatch(batch);

            Counters counters = state.counters;
            if (input.events.size() > MAX_EVENT_ROWS) {
                ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                        idSource.next(), first, last, input.events.size(), 1, input.batchId,
                        "oversized_batch", input.persistedAtMs, input.persistedElapsedMs,
                        input.captureSessionId, input.captureBootId, input.sourceEpochToken,
                        clock.nowMs());
                transaction.insertLoss(new LossRecord(state.streamEpoch, loss,
                        ReadReceiptProtocolV2.lossDigest(loss)));
                counters = counters.addConfirmed(input.events.size(), 1);
            } else {
                long sequence = first;
                for (CapturedEvent captured : input.events) {
                    transaction.insertEvent(new EventRecord(state.streamEpoch,
                            new ReadReceiptProtocolV2.Event(
                                    sequence++, captured.eventId, input.batchId, captured.chatId,
                                    captured.userId, captured.watermark)));
                }
            }
            transaction.updateState(state.withIssued(next, last, counters));
            commitBarrier.beforeCommit();
            commitAttempted = true;
            transaction.commit();
            result = new AppendResult(input.events.size() > MAX_EVENT_ROWS
                    ? AppendOutcome.CONFIRMED_LOSS : AppendOutcome.COMMITTED, first, last);
        } catch (RuntimeException exception) {
            if (exception instanceof PendingJournalContractException) throw exception;
            ReconcileResult reconciliation;
            try {
                reconciliation = store.reconcileAppend(input);
            } catch (RuntimeException readFailure) {
                requestRetry();
                return new AppendResult(AppendOutcome.STORAGE_UNAVAILABLE, 0, 0);
            }
            if (reconciliation.result != null) {
                requestRetry();
                return reconciliation.result;
            }
            if (!reconciliation.absenceProven) {
                requestRetry();
                return new AppendResult(AppendOutcome.STORAGE_UNAVAILABLE, 0, 0);
            }
            if (!commitAttempted) {
                requestRetry();
                return new AppendResult(AppendOutcome.STORAGE_UNAVAILABLE, 0, 0);
            }
            if (countUncertain) recordUncertainOutcome(input.events.size(), 1);
            requestRetry();
            return new AppendResult(AppendOutcome.UNCERTAIN, 0, 0);
        }
        requestRetry();
        return result;
    }

    synchronized LeaseFrame claimOrReplayLease() {
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readLeaseWindow();
            requireActive(snapshot);
            if (snapshot.lease != null) {
                LeaseFrame replay = reconstruct(snapshot, snapshot.lease);
                transaction.commit();
                return replay;
            }
            if (snapshot.state.lastAckedSequence == snapshot.state.highestIssuedSequence) {
                transaction.commit();
                return null;
            }

            long last = selectLeaseEnd(snapshot);
            UUID leaseId = idSource.next();
            LeaseProjection projection = project(snapshot, leaseId,
                    snapshot.state.lastAckedSequence + 1, last, snapshot.state.counters);
            byte[] digest = ReadReceiptProtocolV2.leaseDigest(projection.lease);
            LeaseRecord lease = new LeaseRecord(snapshot.state.streamEpoch, leaseId,
                    projection.lease.firstSequence, projection.lease.lastSequence,
                    projection.lease.previousSequence, projection.lease.counters, digest,
                    Status.ACTIVE);
            transaction.insertLease(lease);
            transaction.commit();
            return new LeaseFrame(projection.lease, digest);
        } catch (RuntimeException exception) {
            requestRetry();
            return null;
        }
    }

    synchronized AckResult acceptAck(Ack ack) {
        required(ack);
        AckResult result;
        LeaseRecord validatedLease = null;
        LeaseProjection validatedProjection = null;
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readLeaseWindow();
            requireActive(snapshot);
            LeaseRecord lease = snapshot.lease;
            if (lease == null || lease.status != Status.ACTIVE
                    || !snapshot.state.installationId.equals(ack.installationId)
                    || !snapshot.state.streamEpoch.equals(ack.streamEpoch)
                    || !lease.leaseId.equals(ack.leaseId)) {
                return AckResult.of(AckOutcome.LEASE_MISMATCH);
            }
            if (lease.lastSequence != ack.throughSequence) {
                return AckResult.of(AckOutcome.RANGE_MISMATCH);
            }
            if (!exactDigest(lease.digest, ack.digest)) {
                return AckResult.of(AckOutcome.DIGEST_MISMATCH);
            }
            requireLeaseShape(snapshot, lease);

            LeaseProjection projection = project(snapshot, lease.leaseId, lease.firstSequence,
                    lease.lastSequence, Counters.fromProtocol(lease.counters));
            byte[] reproduced = ReadReceiptProtocolV2.leaseDigest(projection.lease);
            if (!exactDigest(lease.digest, reproduced)) {
                requestRetry();
                return AckResult.of(AckOutcome.STORAGE_UNAVAILABLE);
            }
            validatedLease = lease;
            validatedProjection = projection;
            if (transaction.deleteEventsExact(projection.events) != projection.events.size()
                    || transaction.deleteLossesExact(projection.losses) != projection.losses.size()) {
                throw new StorageException();
            }
            List<BatchRecord> deletableBatches =
                    transaction.selectDeletableBatches(snapshot, projection);
            int removedBatches = transaction.deleteUnreferencedBatchesExact(deletableBatches);
            if (removedBatches != deletableBatches.size()) throw new StorageException();
            if (transaction.deleteLeaseExact(lease) != 1) throw new StorageException();
            transaction.updateState(snapshot.state.withAck(lease.lastSequence));
            transaction.commit();
            result = AckResult.of(AckOutcome.ACCEPTED);
        } catch (RuntimeException exception) {
            if (validatedLease != null && validatedProjection != null
                    && ackCommitIsDurable(ack, validatedLease, validatedProjection)) {
                requestRetry();
                return AckResult.of(AckOutcome.ACCEPTED);
            }
            requestRetry();
            return AckResult.of(AckOutcome.STORAGE_UNAVAILABLE);
        }
        requestRetry();
        return result;
    }

    synchronized MaintenanceResult maintain(Capacity capacity) {
        required(capacity);
        if (!overCapacity(capacity)) return MaintenanceResult.of(MaintenanceOutcome.WITHIN_CAP);
        MaintenanceResult result;
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            requireActive(snapshot);
            if (overByteCapacity(capacity)) {
                LossSelection losses = transaction.selectOldestUnleasedLossRun();
                if (losses != null) {
                    ReadReceiptProtocolV2.Loss aggregate = new ReadReceiptProtocolV2.Loss(
                            idSource.next(), losses.firstSequence, losses.lastSequence,
                            losses.eventCount, losses.batchCount, null, "bounded_prune",
                            null, null, null, null, null, clock.nowMs());
                    LossRecord replacement = new LossRecord(snapshot.state.streamEpoch,
                            aggregate, ReadReceiptProtocolV2.lossDigest(aggregate));
                    int replaced = transaction.replaceLossSelectionWithAggregateExact(
                            losses, replacement);
                    if (replaced != losses.losses.size()) throw new StorageException();
                    transaction.commit();
                    result = new MaintenanceResult(MaintenanceOutcome.COMPACTED, aggregate);
                    requestRetry();
                    return result;
                }
            }
            BatchSelection selection = transaction.selectOldestCompleteUnleasedBatch();
            if (selection == null) {
                return MaintenanceResult.of(snapshot.lease == null
                        ? MaintenanceOutcome.NO_ELIGIBLE_BATCH
                        : MaintenanceOutcome.BLOCKED_BY_LEASE);
            }
            ReadReceiptProtocolV2.Metadata metadata = selection.batch.metadata;
            ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                    idSource.next(), selection.firstSequence, selection.lastSequence,
                    selection.eventCount, 1, metadata.batchId, "bounded_prune",
                    metadata.persistedAtMs, metadata.persistedElapsedMs,
                    metadata.captureSessionId, metadata.captureBootId,
                    metadata.sourceEpochToken, clock.nowMs());
            LossRecord lossRecord = new LossRecord(snapshot.state.streamEpoch, loss,
                    ReadReceiptProtocolV2.lossDigest(loss));
            int replaced = transaction.replaceBatchSelectionWithLossExact(selection, lossRecord);
            if (replaced != selection.eventCount) throw new StorageException();
            transaction.updateState(snapshot.state.withCounters(snapshot.state.counters
                    .addConfirmed(selection.eventCount, 1)));
            transaction.commit();
            result = new MaintenanceResult(MaintenanceOutcome.CONVERTED, loss);
        } catch (RuntimeException exception) {
            requestRetry();
            return MaintenanceResult.of(MaintenanceOutcome.STORAGE_UNAVAILABLE);
        }
        requestRetry();
        return result;
    }

    synchronized void recordUncertainOutcome(long eventCount, long batchCount) {
        recovery.recordUncertainOutcome(eventCount, batchCount);
    }

    synchronized void recordCaptureDrop(long eventCount, long batchCount) {
        recovery.recordCaptureDrop(eventCount, batchCount);
    }

    synchronized StateUpdate stateUpdate() {
        try {
            Snapshot snapshot = store.readControl();
            requireActive(snapshot);
            if (snapshot.lease != null) return null;
            ReadReceiptProtocolV2.State state = new ReadReceiptProtocolV2.State(
                    snapshot.state.installationId, snapshot.state.streamEpoch,
                    snapshot.state.lastAckedSequence, snapshot.state.highestIssuedSequence,
                    null, snapshot.state.counters.toProtocol(), "active");
            return new StateUpdate(state, ReadReceiptProtocolV2.stateDigest(state));
        } catch (RuntimeException exception) {
            requestRetry();
            return null;
        }
    }

    public synchronized ReadReceiptProtocolV2.State currentState() {
        Snapshot snapshot = store.readControl();
        if (snapshot == null || snapshot.state == null) throw new StorageException();
        StateRecord state = snapshot.state;
        ReadReceiptProtocolV2.ActiveLease activeLease = null;
        if (snapshot.lease != null) {
            LeaseRecord lease = snapshot.lease;
            activeLease = new ReadReceiptProtocolV2.ActiveLease(lease.leaseId,
                    lease.firstSequence, lease.lastSequence, lease.digest,
                    lease.status == Status.ACTIVE ? "active" : "poisoned");
        }
        return new ReadReceiptProtocolV2.State(state.installationId, state.streamEpoch,
                state.lastAckedSequence, state.highestIssuedSequence, activeLease,
                state.counters.toProtocol(), state.status == Status.ACTIVE ? "active" : "poisoned");
    }

    public synchronized void onNack(String category, boolean retryable) {
        if (!ReadReceiptProtocolV2.validNack(category, retryable)) {
            throw recovery.failClosed(new ProtocolContractException());
        }
        if (retryable) {
            requestRetry();
            return;
        }
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readControl();
            if (snapshot == null || snapshot.state == null) throw new StorageException();
            if (snapshot.state.status == Status.ACTIVE) {
                transaction.poison(category);
            } else if (snapshot.state.poisonCategory == null) {
                throw new StorageException();
            }
            transaction.commit();
        } catch (RuntimeException exception) {
            try {
                Snapshot durable = store.readControl();
                if (durable.state != null && durable.state.status == Status.POISONED
                        && durable.state.poisonCategory != null) return;
            } catch (RuntimeException readFailure) {
                exception.addSuppressed(readFailure);
            }
            throw recovery.failClosed(new DurabilityContractException());
        }
    }

    void requestRetry() {
        recovery.requestRetry();
    }

    static boolean overCapacity(Capacity capacity) {
        required(capacity);
        if (capacity.eventRows > MAX_EVENT_ROWS) return true;
        return overByteCapacity(capacity);
    }

    static boolean overByteCapacity(Capacity capacity) {
        required(capacity);
        return capacity.mainBytes > MAX_TOTAL_BYTES
                || capacity.walBytes > MAX_TOTAL_BYTES - capacity.mainBytes;
    }

    static ReconcileResult reconcileAppendSnapshot(BatchInput input, Snapshot snapshot) {
        requireActive(snapshot);
        BatchRecord matched = null;
        for (BatchRecord batch : snapshot.batches) {
            if (batch.streamEpoch.equals(snapshot.state.streamEpoch)
                    && batch.metadata.batchId.equals(input.batchId)) {
                if (matched != null) throw new StorageException();
                matched = batch;
            }
        }
        if (matched == null) {
            Set<UUID> eventIds = new HashSet<>();
            for (CapturedEvent event : input.events) eventIds.add(event.eventId);
            for (EventRecord event : snapshot.events) {
                if (!event.streamEpoch.equals(snapshot.state.streamEpoch)
                        || eventIds.contains(event.event.eventId)
                        || event.event.batchId.equals(input.batchId)) {
                    return ReconcileResult.conflict();
                }
            }
            for (LossRecord loss : snapshot.losses) {
                if (!loss.streamEpoch.equals(snapshot.state.streamEpoch)
                        || input.batchId.equals(loss.loss.batchId)) {
                    return ReconcileResult.conflict();
                }
            }
            return ReconcileResult.absent();
        }
        ReadReceiptProtocolV2.Metadata metadata = matched.metadata;
        if (!matched.digestMatches() || metadata.eventCount != input.events.size()
                || metadata.persistedAtMs != input.persistedAtMs
                || metadata.persistedElapsedMs != input.persistedElapsedMs
                || !metadata.captureSessionId.equals(input.captureSessionId)
                || !sameNullable(metadata.captureBootId, input.captureBootId)
                || !sameNullable(metadata.sourceEpochToken, input.sourceEpochToken)
                || snapshot.state.highestIssuedSequence < metadata.lastSequence
                || snapshot.state.nextSequence <= metadata.lastSequence) {
            return ReconcileResult.conflict();
        }

        List<EventRecord> events = new ArrayList<>();
        List<LossRecord> losses = new ArrayList<>();
        for (EventRecord event : snapshot.events) {
            if (!event.streamEpoch.equals(snapshot.state.streamEpoch)) {
                return ReconcileResult.conflict();
            }
            if (event.event.sequence >= metadata.firstSequence
                    && event.event.sequence <= metadata.lastSequence) events.add(event);
        }
        for (LossRecord loss : snapshot.losses) {
            if (!loss.streamEpoch.equals(snapshot.state.streamEpoch)) {
                return ReconcileResult.conflict();
            }
            if (rangesIntersect(metadata.firstSequence, metadata.lastSequence,
                    loss.loss.firstSequence, loss.loss.lastSequence)) losses.add(loss);
        }
        if (input.events.size() > MAX_EVENT_ROWS) {
            if (!events.isEmpty() || losses.size() != 1) return ReconcileResult.conflict();
            ReadReceiptProtocolV2.Loss loss = losses.get(0).loss;
            if (!losses.get(0).digestMatches() || loss.firstSequence != metadata.firstSequence
                    || loss.lastSequence != metadata.lastSequence
                    || loss.droppedEventCount != input.events.size()
                    || loss.droppedBatchCount != 1 || !input.batchId.equals(loss.batchId)
                    || !"oversized_batch".equals(loss.reason)) {
                return ReconcileResult.conflict();
            }
            return ReconcileResult.committed(new AppendResult(AppendOutcome.CONFIRMED_LOSS,
                    metadata.firstSequence, metadata.lastSequence));
        }

        events.sort(Comparator.comparingLong(value -> value.event.sequence));
        if (!losses.isEmpty() || events.size() != input.events.size()) {
            return ReconcileResult.conflict();
        }
        for (int index = 0; index < events.size(); index++) {
            EventRecord stored = events.get(index);
            CapturedEvent expected = input.events.get(index);
            if (stored.event.sequence != metadata.firstSequence + index
                    || !stored.event.eventId.equals(expected.eventId)
                    || !stored.event.batchId.equals(input.batchId)
                    || stored.event.chatId != expected.chatId
                    || stored.event.userId != expected.userId
                    || stored.event.watermark != expected.watermark) {
                return ReconcileResult.conflict();
            }
        }
        return ReconcileResult.committed(new AppendResult(AppendOutcome.COMMITTED,
                metadata.firstSequence, metadata.lastSequence));
    }

    private boolean ackCommitIsDurable(Ack ack, LeaseRecord lease,
                                       LeaseProjection projection) {
        try {
            Snapshot snapshot = store.readAckEvidence(lease);
            requireActive(snapshot);
            if (!snapshot.state.installationId.equals(ack.installationId)
                    || !snapshot.state.streamEpoch.equals(ack.streamEpoch)
                    || snapshot.state.lastAckedSequence != ack.throughSequence
                    || ack.throughSequence != lease.lastSequence
                    || snapshot.lease != null) {
                return false;
            }
            for (EventRecord durable : snapshot.events) {
                if (durable.streamEpoch.equals(lease.streamEpoch)
                        && durable.event.sequence >= lease.firstSequence
                        && durable.event.sequence <= lease.lastSequence) return false;
            }
            for (LossRecord durable : snapshot.losses) {
                if (durable.streamEpoch.equals(lease.streamEpoch)
                        && rangesIntersect(lease.firstSequence, lease.lastSequence,
                        durable.loss.firstSequence, durable.loss.lastSequence)) return false;
            }
            for (EventRecord deleted : projection.events) {
                for (EventRecord durable : snapshot.events) {
                    if (durable.same(deleted)) return false;
                }
            }
            for (LossRecord deleted : projection.losses) {
                for (LossRecord durable : snapshot.losses) {
                    if (durable.same(deleted)) return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static List<BatchRecord> defaultDeletableBatches(Snapshot snapshot,
                                                              LeaseProjection projection) {
        List<BatchRecord> result = new ArrayList<>();
        for (BatchRecord batch : projection.batches) {
            boolean referencedOutsideLease = false;
            for (EventRecord event : snapshot.events) {
                if (event.streamEpoch.equals(batch.streamEpoch)
                        && event.event.batchId.equals(batch.metadata.batchId)
                        && (event.event.sequence < projection.lease.firstSequence
                        || event.event.sequence > projection.lease.lastSequence)) {
                    referencedOutsideLease = true;
                    break;
                }
            }
            if (!referencedOutsideLease) {
                for (LossRecord loss : snapshot.losses) {
                    if (loss.streamEpoch.equals(batch.streamEpoch)
                            && batch.metadata.batchId.equals(loss.loss.batchId)
                            && (loss.loss.firstSequence < projection.lease.firstSequence
                            || loss.loss.lastSequence > projection.lease.lastSequence)) {
                        referencedOutsideLease = true;
                        break;
                    }
                }
            }
            if (!referencedOutsideLease) result.add(batch);
        }
        return result;
    }

    private static long selectLeaseEnd(Snapshot snapshot) {
        List<EventRecord> events = sortedEvents(snapshot.events);
        List<LossRecord> losses = sortedLosses(snapshot.losses);
        Map<UUID, BatchRecord> batches = batchesById(snapshot.batches);
        long expected = snapshot.state.lastAckedSequence + 1;
        int eventIndex = firstEventAtOrAfter(events, expected);
        int lossIndex = firstLossAtOrAfter(losses, expected);
        int eventCount = 0;
        int lossCount = 0;
        Set<UUID> batchIds = new HashSet<>();
        long last = expected - 1;
        while (expected <= snapshot.state.highestIssuedSequence) {
            EventRecord event = eventIndex < events.size() ? events.get(eventIndex) : null;
            LossRecord loss = lossIndex < losses.size() ? losses.get(lossIndex) : null;
            boolean eventHere = event != null && event.event.sequence == expected;
            boolean lossHere = loss != null && loss.loss.firstSequence == expected;
            if (eventHere == lossHere) throw new StorageException();
            UUID batchId;
            if (eventHere) {
                if (eventCount == MAX_LEASE_RECORDS) break;
                batchId = event.event.batchId;
            } else {
                if (lossCount == MAX_LEASE_RECORDS) break;
                batchId = loss.loss.batchId;
            }
            if (batchId != null && !batchIds.contains(batchId)) {
                if (!batches.containsKey(batchId)) throw new StorageException();
                if (batchIds.size() == MAX_LEASE_RECORDS) break;
                batchIds.add(batchId);
            }
            if (eventHere) {
                last = expected;
                expected++;
                eventCount++;
                eventIndex++;
            } else {
                if (loss.loss.lastSequence < expected
                        || loss.loss.lastSequence > snapshot.state.highestIssuedSequence) {
                    throw new StorageException();
                }
                last = loss.loss.lastSequence;
                expected = Math.addExact(last, 1L);
                lossCount++;
                lossIndex++;
            }
        }
        if (last < snapshot.state.lastAckedSequence + 1) throw new StorageException();
        return last;
    }

    private static LeaseFrame reconstruct(Snapshot snapshot, LeaseRecord lease) {
        requireLeaseShape(snapshot, lease);
        LeaseProjection projection = project(snapshot, lease.leaseId, lease.firstSequence,
                lease.lastSequence, Counters.fromProtocol(lease.counters));
        byte[] digest = ReadReceiptProtocolV2.leaseDigest(projection.lease);
        if (!exactDigest(lease.digest, digest)) throw new StorageException();
        return new LeaseFrame(projection.lease, lease.digest);
    }

    private static LeaseProjection project(Snapshot snapshot, UUID leaseId, long first, long last,
                                           Counters frozenCounters) {
        List<EventRecord> events = new ArrayList<>();
        for (EventRecord event : snapshot.events) {
            if (!event.streamEpoch.equals(snapshot.state.streamEpoch)) {
                throw new StorageException();
            }
            if (event.event.sequence >= first && event.event.sequence <= last) events.add(event);
        }
        events.sort(Comparator.comparingLong(value -> value.event.sequence));
        List<LossRecord> losses = new ArrayList<>();
        for (LossRecord loss : snapshot.losses) {
            if (!loss.streamEpoch.equals(snapshot.state.streamEpoch)) {
                throw new StorageException();
            }
            boolean intersects = loss.loss.firstSequence <= last && loss.loss.lastSequence >= first;
            if (intersects && (loss.loss.firstSequence < first || loss.loss.lastSequence > last)) {
                throw new StorageException();
            }
            if (intersects) losses.add(loss);
        }
        losses.sort(Comparator.comparingLong(value -> value.loss.firstSequence));
        if (events.size() > MAX_LEASE_RECORDS || losses.size() > MAX_LEASE_RECORDS) {
            throw new StorageException();
        }

        Map<UUID, BatchRecord> batchesById = batchesById(snapshot.batches);
        Set<UUID> referenced = new HashSet<>();
        for (EventRecord event : events) referenced.add(event.event.batchId);
        for (LossRecord loss : losses) {
            if (loss.loss.batchId != null) referenced.add(loss.loss.batchId);
        }
        List<BatchRecord> batches = new ArrayList<>();
        for (UUID batchId : referenced) {
            BatchRecord batch = batchesById.get(batchId);
            if (batch == null || !batch.streamEpoch.equals(snapshot.state.streamEpoch)
                    || !batch.digestMatches()) throw new StorageException();
            batches.add(batch);
        }
        if (batches.size() > MAX_LEASE_RECORDS) throw new StorageException();
        batches.sort(Comparator.comparingLong((BatchRecord value) -> value.metadata.firstSequence)
                .thenComparing(value -> value.metadata.batchId,
                        ReadReceiptDurableStream::compareUuid));
        for (LossRecord loss : losses) {
            if (!loss.digestMatches()) throw new StorageException();
        }

        List<ReadReceiptProtocolV2.Metadata> protocolBatches = new ArrayList<>();
        for (BatchRecord batch : batches) protocolBatches.add(batch.metadata);
        List<ReadReceiptProtocolV2.Event> protocolEvents = new ArrayList<>();
        for (EventRecord event : events) protocolEvents.add(event.event);
        List<ReadReceiptProtocolV2.Loss> protocolLosses = new ArrayList<>();
        for (LossRecord loss : losses) protocolLosses.add(loss.loss);
        ReadReceiptProtocolV2.Lease lease = new ReadReceiptProtocolV2.Lease(
                snapshot.state.installationId, snapshot.state.streamEpoch, leaseId,
                first, last, first - 1, frozenCounters.toProtocol(), protocolBatches,
                protocolEvents, protocolLosses);
        return new LeaseProjection(lease, batches, events, losses);
    }

    private static BatchSelection oldestCompleteUnleasedBatch(Snapshot snapshot) {
        List<BatchRecord> batches = new ArrayList<>(snapshot.batches);
        batches.sort(Comparator.comparingLong(value -> value.metadata.firstSequence));
        List<EventRecord> events = sortedEvents(snapshot.events);
        for (BatchRecord batch : batches) {
            ReadReceiptProtocolV2.Metadata metadata = batch.metadata;
            if (!batch.streamEpoch.equals(snapshot.state.streamEpoch)
                    || !batch.digestMatches()) throw new StorageException();
            if (metadata.lastSequence <= snapshot.state.lastAckedSequence) continue;
            long remainingFirst = Math.max(metadata.firstSequence,
                    snapshot.state.lastAckedSequence + 1);
            if (snapshot.lease != null && rangesIntersect(remainingFirst,
                    metadata.lastSequence, snapshot.lease.firstSequence,
                    snapshot.lease.lastSequence)) continue;
            List<LossRecord> intersectingLosses = new ArrayList<>();
            for (LossRecord loss : snapshot.losses) {
                if (rangesIntersect(remainingFirst, metadata.lastSequence,
                        loss.loss.firstSequence, loss.loss.lastSequence)) {
                    intersectingLosses.add(loss);
                }
            }
            List<EventRecord> complete = new ArrayList<>();
            for (EventRecord event : events) {
                if (event.event.sequence >= remainingFirst
                        && event.event.sequence <= metadata.lastSequence
                        && event.event.batchId.equals(metadata.batchId)) {
                    complete.add(event);
                }
            }
            if (!intersectingLosses.isEmpty()) {
                if (complete.isEmpty() && intersectingLosses.size() == 1
                        && intersectingLosses.get(0).loss.firstSequence
                        == remainingFirst
                        && intersectingLosses.get(0).loss.lastSequence
                        == metadata.lastSequence) {
                    continue;
                }
                throw new StorageException();
            }
            long remainingCount = metadata.lastSequence - remainingFirst + 1;
            if (complete.size() != remainingCount) throw new StorageException();
            long expected = remainingFirst;
            boolean contiguous = true;
            for (EventRecord event : complete) {
                if (event.event.sequence != expected++) {
                    contiguous = false;
                    break;
                }
            }
            if (contiguous && expected == metadata.lastSequence + 1) {
                return new BatchSelection(batch, remainingFirst, metadata.lastSequence,
                        remainingCount, complete);
            }
        }
        return null;
    }

    static LossSelection oldestUnleasedLossRun(Snapshot snapshot) {
        List<LossRecord> losses = sortedLosses(snapshot.losses);
        Map<UUID, BatchRecord> batches = batchesById(snapshot.batches);
        List<LossRecord> run = new ArrayList<>();
        long expected = -1;
        for (LossRecord record : losses) {
            ReadReceiptProtocolV2.Loss loss = record.loss;
            if (!record.streamEpoch.equals(snapshot.state.streamEpoch)
                    || !record.digestMatches()) throw new StorageException();
            if (loss.lastSequence <= snapshot.state.lastAckedSequence) continue;
            if (snapshot.lease != null && rangesIntersect(loss.firstSequence, loss.lastSequence,
                    snapshot.lease.firstSequence, snapshot.lease.lastSequence)) {
                LossSelection selection = lossSelection(snapshot, batches, run);
                if (selection != null) return selection;
                run.clear();
                expected = -1;
                continue;
            }
            if (!run.isEmpty() && loss.firstSequence != expected) {
                LossSelection selection = lossSelection(snapshot, batches, run);
                if (selection != null) return selection;
                run.clear();
            }
            run.add(record);
            expected = Math.addExact(loss.lastSequence, 1L);
            if (run.size() == MAX_LEASE_RECORDS) {
                LossSelection selection = lossSelection(snapshot, batches, run);
                if (selection != null) return selection;
                run.clear();
                expected = -1;
            }
        }
        return lossSelection(snapshot, batches, run);
    }

    private static LossSelection lossSelection(Snapshot snapshot,
                                               Map<UUID, BatchRecord> batches,
                                               List<LossRecord> run) {
        if (run.isEmpty()) return null;
        boolean removesMetadata = false;
        List<BatchRecord> selectedBatches = new ArrayList<>();
        Set<UUID> selectedBatchIds = new HashSet<>();
        long eventCount = 0;
        long batchCount = 0;
        for (LossRecord record : run) {
            ReadReceiptProtocolV2.Loss loss = record.loss;
            eventCount = Math.addExact(eventCount, loss.droppedEventCount);
            batchCount = Math.addExact(batchCount, loss.droppedBatchCount);
            if (loss.batchId != null && selectedBatchIds.add(loss.batchId)) {
                BatchRecord batch = batches.get(loss.batchId);
                if (batch == null || !batch.streamEpoch.equals(snapshot.state.streamEpoch)
                        || !batch.digestMatches()
                        || loss.firstSequence < batch.metadata.firstSequence
                        || loss.lastSequence > batch.metadata.lastSequence) {
                    throw new StorageException();
                }
                selectedBatches.add(batch);
                removesMetadata = true;
            }
        }
        if (run.size() == 1 && !removesMetadata) return null;
        long first = run.get(0).loss.firstSequence;
        long last = run.get(run.size() - 1).loss.lastSequence;
        if (last - first + 1 != eventCount) throw new StorageException();
        for (EventRecord event : snapshot.events) {
            if (event.event.sequence >= first && event.event.sequence <= last) {
                throw new StorageException();
            }
        }
        return new LossSelection(run, selectedBatches, first, last, eventCount, batchCount);
    }

    private static Map<UUID, BatchRecord> batchesById(List<BatchRecord> batches) {
        Map<UUID, BatchRecord> result = new HashMap<>();
        for (BatchRecord batch : batches) {
            if (result.put(batch.metadata.batchId, batch) != null) throw new StorageException();
        }
        return result;
    }

    private static List<EventRecord> sortedEvents(List<EventRecord> source) {
        List<EventRecord> result = new ArrayList<>(source);
        result.sort(Comparator.comparingLong(value -> value.event.sequence));
        return result;
    }

    private static List<LossRecord> sortedLosses(List<LossRecord> source) {
        List<LossRecord> result = new ArrayList<>(source);
        result.sort(Comparator.comparingLong(value -> value.loss.firstSequence));
        return result;
    }

    private static int firstEventAtOrAfter(List<EventRecord> events, long sequence) {
        int index = 0;
        while (index < events.size() && events.get(index).event.sequence < sequence) index++;
        return index;
    }

    private static int firstLossAtOrAfter(List<LossRecord> losses, long sequence) {
        int index = 0;
        while (index < losses.size() && losses.get(index).loss.lastSequence < sequence) index++;
        return index;
    }

    private static void requireLeaseShape(Snapshot snapshot, LeaseRecord lease) {
        if (lease.status != Status.ACTIVE
                || !snapshot.state.streamEpoch.equals(lease.streamEpoch)
                || lease.firstSequence <= 0
                || lease.firstSequence != snapshot.state.lastAckedSequence + 1
                || lease.firstSequence > lease.lastSequence
                || lease.lastSequence > snapshot.state.highestIssuedSequence
                || lease.previousSequence != lease.firstSequence - 1
                || !snapshot.state.counters.atLeast(Counters.fromProtocol(lease.counters))) {
            throw new StorageException();
        }
    }

    private static boolean rangesIntersect(long firstA, long lastA, long firstB, long lastB) {
        return firstA <= lastB && lastA >= firstB;
    }

    private static int compareUuid(UUID left, UUID right) {
        int most = Long.compareUnsigned(left.getMostSignificantBits(),
                right.getMostSignificantBits());
        return most != 0 ? most : Long.compareUnsigned(left.getLeastSignificantBits(),
                right.getLeastSignificantBits());
    }

    static BatchSelection selectOldestCompleteUnleasedBatch(Snapshot snapshot) {
        return oldestCompleteUnleasedBatch(snapshot);
    }

    static List<BatchRecord> selectDeletableBatches(
            Snapshot snapshot, LeaseProjection projection
    ) {
        return defaultDeletableBatches(snapshot, projection);
    }

}
