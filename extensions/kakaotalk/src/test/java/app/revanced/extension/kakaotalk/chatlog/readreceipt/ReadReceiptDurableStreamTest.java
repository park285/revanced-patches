package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ReadReceiptDurableStreamTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);
    private static final UUID SESSION = uuid(3);
    private static final UUID BOOT = uuid(4);
    private static final UUID SOURCE = uuid(5);

    @Test
    public void appendCommitsMetadataRowsAndSequenceStateAtomically() {
        Fixture fixture = new Fixture();

        AppendResult result = fixture.stream.append(batch(10, 3));

        assertEquals(AppendOutcome.COMMITTED, result.outcome);
        assertEquals(1, result.firstSequence);
        assertEquals(3, result.lastSequence);
        assertEquals(1, fixture.store.durable.batches.size());
        assertSequences(fixture.store.durable.events, 1, 2, 3);
        assertEquals(3, fixture.store.durable.state.highestIssuedSequence);
        assertEquals(4, fixture.store.durable.state.nextSequence);
        assertEquals(Arrays.asList("BEGIN", "INSERT_BATCH", "INSERT_EVENT", "INSERT_EVENT",
                "INSERT_EVENT", "UPDATE_STATE", "COMMIT"), fixture.store.trace);
    }

    @Test
    public void statementFaultsRemainRetryableWithStableIdentitiesAfterRestart() {
        for (String fault : Arrays.asList("INSERT_BATCH", "INSERT_EVENT", "UPDATE_STATE")) {
            Fixture fixture = new Fixture();
            fixture.store.failOnce(fault);
            BatchInput input = batch(20, 2);

            AppendResult result = fixture.stream.append(input);

            assertEquals(fault, AppendOutcome.STORAGE_UNAVAILABLE,
                    result.outcome);
            assertEquals(fault, 0, fixture.store.durable.batches.size());
            assertEquals(fault, 0, fixture.store.durable.events.size());
            assertEquals(fault, 0, fixture.store.durable.losses.size());
            assertEquals(fault, 0, fixture.store.durable.state.highestIssuedSequence);
            assertEquals(fault, 1, fixture.store.durable.state.nextSequence);
            assertEquals(fault, 0,
                    fixture.store.durable.state.counters.uncertainOutcomeEventCount);
            assertEquals(fault, 0,
                    fixture.store.durable.state.counters.uncertainOutcomeBatchCount);
            assertEquals(fault, 1, fixture.scheduler.queued.size());

            AppendResult retried = fixture.newStream().append(input);
            assertEquals(fault, AppendOutcome.COMMITTED, retried.outcome);
            assertEquals(fault, input.batchId,
                    fixture.store.durable.batches.get(0).metadata.batchId);
            assertEquals(fault, input.events.get(0).eventId,
                    fixture.store.durable.events.get(0).event.eventId);
            assertEquals(fault, input.events.get(1).eventId,
                    fixture.store.durable.events.get(1).event.eventId);
        }
    }

    @Test
    public void commitAttemptWithProvenAbsenceIsUncertainButNeverIssuesPositions() {
        Fixture fixture = new Fixture();
        fixture.store.failOnce("COMMIT");

        AppendResult result = fixture.stream.append(batch(21, 2));

        assertEquals(AppendOutcome.UNCERTAIN, result.outcome);
        assertTrue(fixture.store.durable.events.isEmpty());
        assertEquals(2, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
        assertEquals(1, fixture.store.durable.state.counters.uncertainOutcomeBatchCount);
    }

    @Test
    public void durablePendingBatchRetriesSameIdentitiesAfterRestartWithoutNewEvent() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        ReadReceiptDurableStream stream = fixture.newStream(journal);
        fixture.store.failOnce("INSERT_EVENT");
        BatchInput input = batch(22, 2);

        AppendResult queued = stream.append(input);

        assertEquals(AppendOutcome.QUEUED, queued.outcome);
        assertEquals(PendingBatchState.PENDING, journal.record.state);
        assertTrue(fixture.store.durable.events.isEmpty());
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        fixture.newStream(journal);
        assertEquals(1, fixture.scheduler.queued.size());
        fixture.scheduler.runNext();

        assertNull(journal.record);
        assertEquals(input.batchId, fixture.store.durable.batches.get(0).metadata.batchId);
        assertEquals(input.events.get(0).eventId,
                fixture.store.durable.events.get(0).event.eventId);
        assertEquals(input.events.get(1).eventId,
                fixture.store.durable.events.get(1).event.eventId);
    }

    @Test
    public void occupiedPendingSlotRejectsNewCaptureForCoordinatorOwnedCaptureDrop() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        ReadReceiptDurableStream stream = fixture.newStream(journal);
        fixture.store.failOnce("INSERT_BATCH");
        stream.append(batch(23, 1));

        AppendResult result = stream.append(batch(24, 3));

        assertEquals(AppendOutcome.QUEUE_OCCUPIED, result.outcome);
        assertEquals(0, fixture.store.durable.state.counters.captureDropEventCount);
        assertEquals(0, fixture.store.durable.state.counters.captureDropBatchCount);
        assertEquals(uuid(23), journal.record.input.batchId);
    }

    @Test
    public void terminalClearFaultNeverReissuesCommittedBatchAfterRestart() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failClears = 1;
        ReadReceiptDurableStream stream = fixture.newStream(journal);

        AppendResult result = stream.append(batch(26, 2));

        assertEquals(AppendOutcome.COMMITTED, result.outcome);
        assertEquals(PendingBatchState.TERMINAL_COMMITTED,
                journal.record.state);
        int inserts = Collections.frequency(fixture.store.trace, "INSERT_BATCH");
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        fixture.newStream(journal);
        fixture.scheduler.runNext();

        assertNull(journal.record);
        assertEquals(inserts, Collections.frequency(fixture.store.trace, "INSERT_BATCH"));
        assertSequences(fixture.store.durable.events, 1, 2);
    }

    @Test
    public void uncertainTerminalAppliesAbsoluteCounterAndNeverReissuesAfterClearFault() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failClears = 1;
        ReadReceiptDurableStream stream = fixture.newStream(journal);
        fixture.store.failOnce("COMMIT");

        AppendResult result = stream.append(batch(27, 2));

        assertEquals(AppendOutcome.UNCERTAIN, result.outcome);
        assertEquals(PendingBatchState.TERMINAL_UNCERTAIN,
                journal.record.state);
        assertEquals(2, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
        int inserts = Collections.frequency(fixture.store.trace, "INSERT_BATCH");
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        fixture.newStream(journal);
        fixture.scheduler.runNext();

        assertNull(journal.record);
        assertEquals(inserts, Collections.frequency(fixture.store.trace, "INSERT_BATCH"));
        assertEquals(2, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
    }

    @Test
    public void terminalPublishFailureLeavesCommittingEvidenceForReadOnlyRestartReconcile() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failTerminals = 1;
        ReadReceiptDurableStream stream = fixture.newStream(journal);

        try {
            stream.append(batch(28, 1));
            throw new AssertionError("expected fail-closed terminal publish");
        } catch (PendingJournalContractException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }
        assertEquals(PendingBatchState.COMMITTING,
                journal.record.state);
        int inserts = Collections.frequency(fixture.store.trace, "INSERT_BATCH");
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        fixture.newStream(journal);
        fixture.scheduler.runNext();

        assertNull(journal.record);
        assertEquals(inserts, Collections.frequency(fixture.store.trace, "INSERT_BATCH"));
    }

    @Test
    public void runtimeSinkAssignsIdsBeforePublishAndRejectsOccupiedSlot() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        ReadReceiptDurableStream stream = fixture.newStream(journal);
        ReadReceiptPendingBatchSink sink = new ReadReceiptPendingBatchSink(stream,
                () -> uuid(fixture.nextId++));
        fixture.store.failOnce("INSERT_EVENT");

        assertTrue(sink.accept(captured(30, 2)));
        BatchInput pending = journal.record.input;
        assertEquals(2, pending.events.size());
        assertNotNull(pending.batchId);
        assertNotNull(pending.events.get(0).eventId);

        assertFalse(sink.accept(captured(31, 3)));
        assertEquals(0, fixture.store.durable.state.counters.captureDropEventCount);
        assertEquals(0, fixture.store.durable.state.counters.captureDropBatchCount);
        assertEquals(pending.batchId, journal.record.input.batchId);
    }

    @Test
    public void runtimeSinkRejects257MembersBeforeIdAllocationOrJournalMutation() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        ReadReceiptDurableStream stream = fixture.newStream(journal);
        long before = fixture.nextId;
        ReadReceiptPendingBatchSink sink = new ReadReceiptPendingBatchSink(stream,
                () -> uuid(fixture.nextId++));

        assertFalse(sink.accept(captured(35, 257)));

        assertEquals(before, fixture.nextId);
        assertNull(journal.record);
        assertTrue(fixture.store.durable.events.isEmpty());
    }

    @Test
    public void pendingPublishFailureSignalsFailClosedBeforeAnySqlMutation() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failPublishes = 1;
        ReadReceiptDurableStream stream = fixture.newStream(journal);

        try {
            stream.append(batch(32, 1));
            throw new AssertionError("expected pending publish failure");
        } catch (PendingJournalContractException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }

        assertNull(journal.record);
        assertFalse(fixture.store.trace.contains("INSERT_BATCH"));
    }

    @Test
    public void malformedPendingJournalAtStartupSignalsSupervisorFailClosed() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failLoads = 1;

        try {
            fixture.newStream(journal);
            throw new AssertionError("expected startup journal failure");
        } catch (PendingJournalContractException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }

        assertTrue(fixture.scheduler.queued.isEmpty());
    }

    @Test
    public void preCommitBarrierFailureKeepsPendingAndRestartRetriesExactInput() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failCommitting = 1;
        BatchInput input = batch(33, 1);
        ReadReceiptDurableStream stream = fixture.newStream(journal);

        try {
            stream.append(input);
            throw new AssertionError("expected commit barrier failure");
        } catch (PendingJournalContractException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }
        assertEquals(PendingBatchState.PENDING, journal.record.state);
        assertTrue(fixture.store.durable.events.isEmpty());
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        fixture.newStream(journal);
        fixture.scheduler.runNext();

        assertNull(journal.record);
        assertEquals(input.events.get(0).eventId,
                fixture.store.durable.events.get(0).event.eventId);
    }

    @Test
    public void ambiguousBarrierPublishNeverReissuesAndConservativelyCountsUncertain() {
        Fixture fixture = new Fixture();
        FakePendingBatchJournal journal = new FakePendingBatchJournal();
        journal.failCommittingAfter = 1;
        ReadReceiptDurableStream stream = fixture.newStream(journal);

        try {
            stream.append(batch(34, 2));
            throw new AssertionError("expected ambiguous barrier failure");
        } catch (PendingJournalContractException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }
        assertEquals(PendingBatchState.COMMITTING,
                journal.record.state);
        int inserts = Collections.frequency(fixture.store.trace, "INSERT_BATCH");
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        fixture.newStream(journal);
        fixture.scheduler.runNext();

        assertNull(journal.record);
        assertEquals(inserts, Collections.frequency(fixture.store.trace, "INSERT_BATCH"));
        assertEquals(2, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
    }

    @Test
    public void postCommitErrorReconcilesDurableBatchWithoutFalseUncertainty() {
        Fixture fixture = new Fixture();
        fixture.store.failAfterCommitOnce = true;

        AppendResult result = fixture.stream.append(batch(25, 2));

        assertEquals(AppendOutcome.COMMITTED, result.outcome);
        assertEquals(1, result.firstSequence);
        assertEquals(2, result.lastSequence);
        assertSequences(fixture.store.durable.events, 1, 2);
        assertEquals(0, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
        assertEquals(0, fixture.store.durable.state.counters.uncertainOutcomeBatchCount);
    }

    @Test
    public void activeLeaseIsFrozenAndReplaysByteExactlyAfterCounterChangesAndRestart() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(30, 2));
        LeaseFrame first = fixture.stream.claimOrReplayLease();
        assertNotNull(first);

        fixture.stream.recordUncertainOutcome(7, 3);
        fixture.stream.recordCaptureDrop(11, 5);
        fixture.stream.append(batch(31, 1));
        ReadReceiptDurableStream restarted = fixture.newStream();
        LeaseFrame replay = restarted.claimOrReplayLease();

        assertEquals(first.lease.leaseId, replay.lease.leaseId);
        assertEquals(first.lease.firstSequence, replay.lease.firstSequence);
        assertEquals(first.lease.lastSequence, replay.lease.lastSequence);
        assertArrayEquals(first.digest, replay.digest);
        assertArrayEquals(ReadReceiptProtocolV2.leasePreimage(first.lease),
                ReadReceiptProtocolV2.leasePreimage(replay.lease));
        assertEquals(0, replay.lease.counters.uncertainOutcomeEventCount);
        assertEquals(0, replay.lease.counters.captureDropEventCount);
        assertEquals(7, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
        assertEquals(11, fixture.store.durable.state.counters.captureDropEventCount);
    }

    @Test
    public void leaseInsertAndCommitFaultsLeaveNoPartialLease() {
        for (String fault : Arrays.asList("INSERT_LEASE", "COMMIT")) {
            Fixture fixture = new Fixture();
            fixture.stream.append(batch(35, 2));
            fixture.store.failOnce(fault);

            assertNull(fault, fixture.stream.claimOrReplayLease());
            assertNull(fault, fixture.store.durable.lease);
            assertSequences(fixture.store.durable.events, 1, 2);

            LeaseFrame recovered = fixture.newStream()
                    .claimOrReplayLease();
            assertNotNull(fault, recovered);
            assertEquals(fault, 1, recovered.lease.firstSequence);
            assertEquals(fault, 2, recovered.lease.lastSequence);
        }
    }

    @Test
    public void exactAckDeletesOnlyExactLeaseEvidenceAndNeverBulkThrough() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(40, 2));
        LeaseFrame leased = fixture.stream.claimOrReplayLease();
        fixture.stream.append(batch(41, 2));

        assertRejectedAndPreserved(fixture, ack(leased, uuid(99), EPOCH,
                leased.lease.leaseId, leased.lease.lastSequence, leased.digest),
                AckOutcome.LEASE_MISMATCH);
        assertRejectedAndPreserved(fixture, ack(leased, INSTALLATION, EPOCH,
                leased.lease.leaseId, leased.lease.lastSequence - 1, leased.digest),
                AckOutcome.RANGE_MISMATCH);
        byte[] wrongDigest = leased.digest.clone();
        wrongDigest[0] ^= 1;
        assertRejectedAndPreserved(fixture, ack(leased, INSTALLATION, EPOCH,
                leased.lease.leaseId, leased.lease.lastSequence, wrongDigest),
                AckOutcome.DIGEST_MISMATCH);

        AckResult accepted = fixture.stream.acceptAck(
                ack(leased, INSTALLATION, EPOCH, leased.lease.leaseId,
                        leased.lease.lastSequence, leased.digest));

        assertEquals(AckOutcome.ACCEPTED, accepted.outcome);
        assertNull(fixture.store.durable.lease);
        assertSequences(fixture.store.durable.events, 3, 4);
        assertEquals(2, fixture.store.durable.state.lastAckedSequence);
        assertEquals(4, fixture.store.durable.state.highestIssuedSequence);
        assertEquals(5, fixture.store.durable.state.nextSequence);
        assertEquals(1, fixture.store.durable.batches.size());
    }

    @Test
    public void ackDeleteStatementOrCommitFaultRollsBackAllEvidence() {
        for (String fault : Arrays.asList("DELETE_EVENTS", "DELETE_BATCHES", "DELETE_LEASE",
                "UPDATE_STATE", "COMMIT")) {
            Fixture fixture = new Fixture();
            fixture.stream.append(batch(50, 2));
            LeaseFrame leased = fixture.stream.claimOrReplayLease();
            fixture.store.failOnce(fault);

            AckResult result = fixture.stream.acceptAck(
                    ack(leased, INSTALLATION, EPOCH, leased.lease.leaseId,
                            leased.lease.lastSequence, leased.digest));

            assertEquals(fault, AckOutcome.STORAGE_UNAVAILABLE,
                    result.outcome);
            assertNotNull(fault, fixture.store.durable.lease);
            assertSequences(fixture.store.durable.events, 1, 2);
            assertEquals(fault, 0, fixture.store.durable.state.lastAckedSequence);
            assertArrayEquals(fault, leased.digest,
                    fixture.newStream().claimOrReplayLease().digest);
        }
    }

    @Test
    public void postCommitAckErrorReconcilesAsAcceptedFromDurableState() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(53, 2));
        LeaseFrame leased = fixture.stream.claimOrReplayLease();
        fixture.store.failAfterCommitOnce = true;

        AckResult result = fixture.stream.acceptAck(
                ack(leased, INSTALLATION, EPOCH, leased.lease.leaseId,
                        leased.lease.lastSequence, leased.digest));

        assertEquals(AckOutcome.ACCEPTED, result.outcome);
        assertNull(fixture.store.durable.lease);
        assertTrue(fixture.store.durable.events.isEmpty());
        assertEquals(2, fixture.store.durable.state.lastAckedSequence);
    }

    @Test
    public void lossAckFaultPreservesLossMetadataAndLease() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(55, 2));
        fixture.stream.maintain(new Capacity(
                ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
        LeaseFrame leased = fixture.stream.claimOrReplayLease();
        fixture.store.failOnce("DELETE_LOSSES");

        AckResult result = fixture.stream.acceptAck(
                ack(leased, INSTALLATION, EPOCH, leased.lease.leaseId,
                        leased.lease.lastSequence, leased.digest));

        assertEquals(AckOutcome.STORAGE_UNAVAILABLE, result.outcome);
        assertEquals(1, fixture.store.durable.losses.size());
        assertEquals(1, fixture.store.durable.batches.size());
        assertNotNull(fixture.store.durable.lease);
        assertArrayEquals(leased.digest, fixture.newStream().claimOrReplayLease().digest);
    }

    @Test
    public void maintenanceConvertsOnlyOldestCompleteUnleasedBatchAtSamePositions() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(60, 2));
        LeaseFrame protectedLease = fixture.stream.claimOrReplayLease();
        fixture.stream.append(batch(61, 3));
        fixture.stream.append(batch(62, 2));

        MaintenanceResult result = fixture.stream.maintain(
                new Capacity(
                        ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 1, 1));

        assertEquals(MaintenanceOutcome.CONVERTED, result.outcome);
        assertEquals(3, result.loss.firstSequence);
        assertEquals(5, result.loss.lastSequence);
        assertEquals("bounded_prune", result.loss.reason);
        assertEquals(batch(61, 3).batchId, result.loss.batchId);
        assertSequences(fixture.store.durable.events, 1, 2, 6, 7);
        assertEquals(1, fixture.store.durable.losses.size());
        assertEquals(3,
                fixture.store.durable.state.counters.confirmedDroppedEventCount);
        assertEquals(1,
                fixture.store.durable.state.counters.confirmedDroppedBatchCount);
        assertEquals(protectedLease.lease.leaseId, fixture.store.durable.lease.leaseId);
        assertEquals(3, fixture.store.durable.batches.size());

        ReadReceiptDurableStream restarted = fixture.newStream();
        assertArrayEquals(protectedLease.digest, restarted.claimOrReplayLease().digest);
    }

    @Test
    public void byteCapCompactsExistingLossBeforeConvertingAnotherEvent() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(63, 1));
        fixture.stream.maintain(new Capacity(
                ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
        fixture.stream.append(batch(64, 1));
        Counters counters = fixture.store.durable.state.counters;

        MaintenanceResult result = fixture.stream.maintain(
                new Capacity(1,
                        ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));

        assertFalse(result.outcome == MaintenanceOutcome.NO_ELIGIBLE_BATCH);
        assertEquals(1, fixture.store.durable.events.size());
        assertEquals(1, fixture.store.durable.losses.size());
        assertNull(fixture.store.durable.losses.get(0).loss.batchId);
        assertEquals(1, fixture.store.durable.batches.size());
        assertEquals(counters.confirmedDroppedEventCount,
                fixture.store.durable.state.counters.confirmedDroppedEventCount);
        assertEquals(counters.confirmedDroppedBatchCount,
                fixture.store.durable.state.counters.confirmedDroppedBatchCount);
    }

    @Test
    public void allLossBacklogCompactsContiguousCoverageWithoutCounterIncrease() {
        Fixture fixture = new Fixture();
        for (int index = 0; index < 3; index++) {
            fixture.stream.append(batch(65 + index, 1));
            fixture.stream.maintain(new Capacity(
                    ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
        }
        Counters counters = fixture.store.durable.state.counters;

        MaintenanceResult result = fixture.stream.maintain(
                new Capacity(0,
                        ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));

        assertFalse(result.outcome == MaintenanceOutcome.NO_ELIGIBLE_BATCH);
        assertTrue(fixture.store.durable.events.isEmpty());
        assertTrue(fixture.store.durable.batches.isEmpty());
        assertEquals(1, fixture.store.durable.losses.size());
        ReadReceiptProtocolV2.Loss aggregate = fixture.store.durable.losses.get(0).loss;
        assertEquals(1, aggregate.firstSequence);
        assertEquals(3, aggregate.lastSequence);
        assertEquals(3, aggregate.droppedEventCount);
        assertEquals(3, aggregate.droppedBatchCount);
        assertNull(aggregate.batchId);
        assertArrayEquals(ReadReceiptProtocolV2.lossDigest(aggregate),
                fixture.store.durable.losses.get(0).contentDigest);
        assertEquals(counters.confirmedDroppedEventCount,
                fixture.store.durable.state.counters.confirmedDroppedEventCount);
        assertEquals(counters.confirmedDroppedBatchCount,
                fixture.store.durable.state.counters.confirmedDroppedBatchCount);
    }

    @Test
    public void lossCompactionSkipsFrozenLeaseAndKeepsReplayDigestExact() {
        Fixture fixture = new Fixture();
        for (int index = 0; index < 2; index++) {
            fixture.stream.append(batch(68 + index, 1));
            fixture.stream.maintain(new Capacity(
                    ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
        }
        LeaseFrame frozen = fixture.stream.claimOrReplayLease();
        for (int index = 0; index < 2; index++) {
            fixture.stream.append(batch(70 + index, 1));
            fixture.stream.maintain(new Capacity(
                    ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
        }

        MaintenanceResult result = fixture.stream.maintain(
                new Capacity(0,
                        ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));

        assertEquals(MaintenanceOutcome.COMPACTED, result.outcome);
        assertEquals(3, fixture.store.durable.losses.size());
        assertEquals(2, fixture.store.durable.batches.size());
        assertEquals(3, result.loss.firstSequence);
        assertEquals(4, result.loss.lastSequence);
        assertNull(result.loss.batchId);
        assertArrayEquals(frozen.digest, fixture.newStream().claimOrReplayLease().digest);
    }

    @Test
    public void lossCompactionFaultRollsBackCoverageMetadataAndCountersTogether() {
        for (String fault : Arrays.asList(
                "DELETE_LOSSES", "DELETE_BATCHES", "INSERT_LOSS", "COMMIT")) {
            Fixture fixture = new Fixture();
            for (int index = 0; index < 2; index++) {
                fixture.stream.append(batch(72 + index, 1));
                fixture.stream.maintain(new Capacity(
                        ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
            }
            Counters counters = fixture.store.durable.state.counters;
            fixture.store.failOnce(fault);

            MaintenanceResult result = fixture.stream.maintain(
                    new Capacity(0,
                            ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));

            assertEquals(fault, MaintenanceOutcome.STORAGE_UNAVAILABLE,
                    result.outcome);
            assertEquals(fault, 2, fixture.store.durable.losses.size());
            assertEquals(fault, 2, fixture.store.durable.batches.size());
            assertEquals(fault, counters.confirmedDroppedEventCount,
                    fixture.store.durable.state.counters.confirmedDroppedEventCount);
            assertEquals(fault, counters.confirmedDroppedBatchCount,
                    fixture.store.durable.state.counters.confirmedDroppedBatchCount);
            LeaseFrame replay = fixture.newStream().claimOrReplayLease();
            assertEquals(fault, 2, replay.lease.losses.size());
        }
    }

    @Test
    public void maintenanceNeverPrunesLeasedRowsAndReportsBlockedWithoutEligibleBatch() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(70, 2));
        LeaseFrame leased = fixture.stream.claimOrReplayLease();

        MaintenanceResult result = fixture.stream.maintain(
                new Capacity(
                        ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L,
                        ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));

        assertEquals(MaintenanceOutcome.BLOCKED_BY_LEASE,
                result.outcome);
        assertSequences(fixture.store.durable.events, 1, 2);
        assertNotNull(fixture.store.durable.lease);
        assertArrayEquals(leased.digest, fixture.stream.claimOrReplayLease().digest);
    }

    @Test
    public void maintenanceFaultRollsBackEventsLossAndConfirmedCountersTogether() {
        for (String fault : Arrays.asList("REPLACE_WITH_LOSS", "UPDATE_STATE", "COMMIT")) {
            Fixture fixture = new Fixture();
            fixture.stream.append(batch(75, 2));
            fixture.store.failOnce(fault);

            MaintenanceResult result = fixture.stream.maintain(
                    new Capacity(
                            ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));

            assertEquals(fault, MaintenanceOutcome.STORAGE_UNAVAILABLE,
                    result.outcome);
            assertSequences(fixture.store.durable.events, 1, 2);
            assertTrue(fault, fixture.store.durable.losses.isEmpty());
            assertEquals(fault, 0,
                    fixture.store.durable.state.counters.confirmedDroppedEventCount);
            assertEquals(fault, 0,
                    fixture.store.durable.state.counters.confirmedDroppedBatchCount);
        }
    }

    @Test
    public void lossOnlyLeaseUsesSamePositiveContiguousCoordinate() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(80, 2));
        MaintenanceResult maintenance = fixture.stream.maintain(
                new Capacity(
                        ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));
        assertEquals(MaintenanceOutcome.CONVERTED,
                maintenance.outcome);

        LeaseFrame frame = fixture.stream.claimOrReplayLease();

        assertTrue(frame.lease.events.isEmpty());
        assertEquals(1, frame.lease.losses.size());
        assertEquals(1, frame.lease.firstSequence);
        assertEquals(2, frame.lease.lastSequence);
        assertEquals(2, frame.lease.losses.get(0).droppedEventCount);
        assertEquals(2, frame.lease.counters.confirmedDroppedEventCount);
    }

    @Test
    public void stateUpdateExistsOnlyWithoutLeaseAndNeverMovesCursor() {
        Fixture fixture = new Fixture();
        fixture.stream.recordUncertainOutcome(4, 2);
        fixture.stream.recordCaptureDrop(3, 1);
        long cursor = fixture.store.durable.state.lastAckedSequence;

        StateUpdate update = fixture.stream.stateUpdate();

        assertNotNull(update);
        assertNull(update.state.activeLease);
        assertEquals(cursor, update.state.lastAckedSequence);
        assertEquals(4, update.state.counters.uncertainOutcomeEventCount);
        assertEquals(3, update.state.counters.captureDropEventCount);
        assertArrayEquals(ReadReceiptProtocolV2.stateDigest(update.state), update.digest);
        assertEquals(cursor, fixture.store.durable.state.lastAckedSequence);

        fixture.stream.append(batch(90, 1));
        fixture.stream.claimOrReplayLease();
        assertNull(fixture.stream.stateUpdate());
    }

    @Test
    public void currentStateUsesBoundedControlViewAndIncludesActiveLease() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(91, 2));
        LeaseFrame lease = fixture.stream.claimOrReplayLease();
        int fullReads = fixture.store.fullSnapshotReads;

        ReadReceiptProtocolV2.State state = fixture.stream.currentState();

        assertEquals(INSTALLATION, state.installationId);
        assertEquals(EPOCH, state.streamEpoch);
        assertEquals(lease.lease.leaseId, state.activeLease.leaseId);
        assertEquals("active", state.streamStatus);
        assertEquals(fullReads, fixture.store.fullSnapshotReads);
    }

    @Test
    public void retryableNackPreservesStateAndRequestsAutonomousRetry() {
        Fixture fixture = new Fixture();

        fixture.stream.onNack("schema_unavailable", true);

        assertEquals(Status.ACTIVE, fixture.store.durable.state.status);
        assertNull(fixture.store.durable.state.poisonCategory);
        assertEquals(1, fixture.scheduler.queued.size());
    }

    @Test
    public void nonRetryableNackPoisonsStateAndLeaseWithoutDeletingEvidenceAcrossRestart() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(92, 2));
        LeaseFrame lease = fixture.stream.claimOrReplayLease();

        fixture.stream.onNack("sequence_hole", false);

        assertEquals(Status.POISONED, fixture.store.durable.state.status);
        assertEquals("sequence_hole", fixture.store.durable.state.poisonCategory);
        assertEquals(Status.POISONED, fixture.store.durable.lease.status);
        assertEquals("sequence_hole", fixture.store.durable.lease.poisonCategory);
        assertSequences(fixture.store.durable.events, 1, 2);
        assertEquals(1, fixture.store.durable.batches.size());
        assertEquals(0, fixture.store.durable.state.lastAckedSequence);

        ReadReceiptProtocolV2.State restarted = fixture.newStream().currentState();
        assertEquals("poisoned", restarted.streamStatus);
        assertEquals(lease.lease.leaseId, restarted.activeLease.leaseId);
        assertEquals("poisoned", restarted.activeLease.state);
        assertEquals(lease.lease.leaseId, fixture.store.durable.lease.leaseId);
    }

    @Test
    public void poisonCommitAmbiguityReconcilesDurablePoison() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(93, 1));
        fixture.store.failAfterCommitOnce = true;

        fixture.stream.onNack("digest_mismatch", false);

        assertEquals(Status.POISONED, fixture.store.durable.state.status);
        assertEquals("digest_mismatch", fixture.store.durable.state.poisonCategory);
        assertSequences(fixture.store.durable.events, 1);
        assertEquals(0, fixture.supervisor.calls);
    }

    @Test
    public void repeatedNackPreservesFirstDurablePoisonByPrecedence() {
        Fixture fixture = new Fixture();
        fixture.stream.onNack("batch_conflict", false);

        fixture.stream.onNack("stream_rollback", false);

        assertEquals("batch_conflict", fixture.store.durable.state.poisonCategory);
        assertEquals(Status.POISONED, fixture.store.durable.state.status);
    }

    @Test
    public void malformedOrUnknownNackFailsClosedWithoutMutation() {
        for (Object[] item : Arrays.asList(
                new Object[]{"schema_unavailable", false},
                new Object[]{"sequence_hole", true},
                new Object[]{"not_a_category", false})) {
            Fixture fixture = new Fixture();
            try {
                fixture.stream.onNack((String) item[0], (Boolean) item[1]);
                throw new AssertionError("expected protocol failure");
            } catch (ProtocolContractException expected) {
                assertEquals(1, fixture.supervisor.calls);
            }
            assertEquals(Status.ACTIVE,
                    fixture.store.durable.state.status);
        }
    }

    @Test
    public void sixCountersRemainSeparateMonotonicAndDoNotIssueSequence() {
        Fixture fixture = new Fixture();

        fixture.stream.recordUncertainOutcome(2, 1);
        fixture.stream.recordUncertainOutcome(3, 4);
        fixture.stream.recordCaptureDrop(5, 6);

        Counters counters = fixture.store.durable.state.counters;
        assertEquals(0, counters.confirmedDroppedEventCount);
        assertEquals(0, counters.confirmedDroppedBatchCount);
        assertEquals(5, counters.uncertainOutcomeEventCount);
        assertEquals(5, counters.uncertainOutcomeBatchCount);
        assertEquals(5, counters.captureDropEventCount);
        assertEquals(6, counters.captureDropBatchCount);
        assertEquals(0, fixture.store.durable.state.highestIssuedSequence);
        assertEquals(1, fixture.store.durable.state.nextSequence);
    }

    @Test
    public void failedCounterWriteIsDrainedByRetryWithoutNewCapture() {
        Fixture fixture = new Fixture();
        fixture.store.failOnce("UPDATE_STATE");

        fixture.stream.recordUncertainOutcome(8, 3);
        assertEquals(0, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
        assertEquals(1, fixture.scheduler.queued.size());

        fixture.scheduler.runNext();

        assertEquals(8, fixture.store.durable.state.counters.uncertainOutcomeEventCount);
        assertEquals(3, fixture.store.durable.state.counters.uncertainOutcomeBatchCount);
        assertEquals(0, fixture.store.durable.state.highestIssuedSequence);
        assertTrue(fixture.scheduler.queued.isEmpty());
    }

    @Test
    public void failedCounterTargetSurvivesRestartAndDrainsExactlyOnce() {
        Fixture fixture = new Fixture();
        fixture.store.failOnce("UPDATE_STATE");
        fixture.stream.recordCaptureDrop(12, 5);
        assertNotNull(fixture.counterJournal.durable);
        fixture.scheduler.queued.clear();
        fixture.scheduler.delays.clear();

        ReadReceiptDurableStream restarted = fixture.newStream();
        assertNotNull(restarted);
        assertEquals(1, fixture.scheduler.queued.size());
        fixture.scheduler.runNext();

        assertEquals(12, fixture.store.durable.state.counters.captureDropEventCount);
        assertEquals(5, fixture.store.durable.state.counters.captureDropBatchCount);
        assertNull(fixture.counterJournal.durable);
        assertTrue(fixture.scheduler.queued.isEmpty());
    }

    @Test
    public void counterJournalGuaranteeFailureSignalsFailClosedInsteadOfLosingDelta() {
        Fixture fixture = new Fixture();
        fixture.counterJournal.failStores = 1;

        try {
            fixture.stream.recordCaptureDrop(12, 5);
            throw new AssertionError("expected fail-closed signal");
        } catch (DurabilityContractException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }

        assertEquals(0, fixture.store.durable.state.counters.captureDropEventCount);
        assertNull(fixture.counterJournal.durable);
        assertTrue(fixture.scheduler.queued.isEmpty());
    }

    @Test
    public void postCommitCounterErrorDoesNotDoubleCountOnRetry() {
        Fixture fixture = new Fixture();
        fixture.store.failAfterCommitOnce = true;

        fixture.stream.recordCaptureDrop(9, 4);
        fixture.scheduler.runNext();

        assertEquals(9, fixture.store.durable.state.counters.captureDropEventCount);
        assertEquals(4, fixture.store.durable.state.counters.captureDropBatchCount);
    }

    @Test
    public void capDecisionUsesStrictRowAndCombinedMainWalBoundsWithOverflowSafety() {
        assertFalse(ReadReceiptDurableStream.overCapacity(new Capacity(
                ReadReceiptDurableStream.MAX_EVENT_ROWS,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 0)));
        assertTrue(ReadReceiptDurableStream.overCapacity(new Capacity(
                ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0)));
        assertFalse(ReadReceiptDurableStream.overCapacity(new Capacity(
                0, ReadReceiptDurableStream.MAX_TOTAL_BYTES - 1, 1)));
        assertTrue(ReadReceiptDurableStream.overCapacity(new Capacity(
                0, ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1)));
        assertTrue(ReadReceiptDurableStream.overCapacity(new Capacity(
                0, Long.MAX_VALUE, Long.MAX_VALUE)));
    }

    @Test
    public void retryRunsAutonomouslyWithoutNewCaptureAndCoalescesRequests() {
        Fixture fixture = new Fixture();
        fixture.retryWork.results.add(false);
        fixture.retryWork.results.add(true);

        fixture.stream.requestRetry();
        fixture.stream.requestRetry();
        assertEquals(1, fixture.scheduler.queued.size());
        assertEquals(250, fixture.scheduler.delays.removeFirst().longValue());

        fixture.scheduler.runNext();
        assertEquals(1, fixture.retryWork.calls);
        assertEquals(1, fixture.scheduler.queued.size());
        assertEquals(500, fixture.scheduler.delays.removeFirst().longValue());

        fixture.scheduler.runNext();
        assertEquals(2, fixture.retryWork.calls);
        assertTrue(fixture.scheduler.queued.isEmpty());

        fixture.stream.requestRetry();
        assertEquals(250, fixture.scheduler.delays.removeFirst().longValue());
    }

    @Test
    public void retryMaintenanceSerializesDurableFacadesAndAllowsReentrantMaintain()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(121, 1));
        LeaseFrame lease = fixture.stream.claimOrReplayLease();
        Ack ack = ack(lease, INSTALLATION, EPOCH, lease.lease.leaseId,
                lease.lease.lastSequence, lease.digest);
        CountDownLatch maintenanceEntered = new CountDownLatch(1);
        CountDownLatch releaseMaintenance = new CountDownLatch(1);
        AtomicBoolean reentrantMaintainReturned = new AtomicBoolean();
        fixture.retryWork.onRun = () -> {
            MaintenanceResult result = fixture.stream.maintain(new Capacity(0, 0, 0));
            assertEquals(MaintenanceOutcome.WITHIN_CAP, result.outcome);
            reentrantMaintainReturned.set(true);
            maintenanceEntered.countDown();
            await(releaseMaintenance);
        };

        Thread retry = new Thread(fixture.scheduler::runNext, "read-receipt-retry");
        retry.start();
        assertTrue(maintenanceEntered.await(2, TimeUnit.SECONDS));
        assertTrue(reentrantMaintainReturned.get());

        CountDownLatch facadeStarted = new CountDownLatch(3);
        CountDownLatch facadeCompleted = new CountDownLatch(3);
        AtomicReference<Throwable> facadeFailure = new AtomicReference<>();
        Thread append = facadeThread("read-receipt-append", facadeStarted, facadeCompleted,
                facadeFailure, () -> fixture.stream.append(batch(122, 1)));
        Thread claim = facadeThread("read-receipt-claim", facadeStarted, facadeCompleted,
                facadeFailure, fixture.stream::claimOrReplayLease);
        Thread accept = facadeThread("read-receipt-ack", facadeStarted, facadeCompleted,
                facadeFailure, () -> fixture.stream.acceptAck(ack));
        append.start();
        claim.start();
        accept.start();
        assertTrue(facadeStarted.await(2, TimeUnit.SECONDS));
        assertBlocked(append);
        assertBlocked(claim);
        assertBlocked(accept);
        assertEquals(3, facadeCompleted.getCount());

        releaseMaintenance.countDown();
        retry.join(2_000L);
        append.join(2_000L);
        claim.join(2_000L);
        accept.join(2_000L);
        assertFalse(retry.isAlive());
        assertFalse(append.isAlive());
        assertFalse(claim.isAlive());
        assertFalse(accept.isAlive());
        assertEquals(0, facadeCompleted.getCount());
        assertNull(facadeFailure.get());
    }

    @Test
    public void durabilityFailureFailsClosedWithoutReschedulingMaintenance() {
        Fixture fixture = new Fixture();
        fixture.retryWork.failClosed = true;

        fixture.stream.requestRetry();
        assertThrows(DurabilityContractException.class,
                fixture.scheduler::runNext);

        assertEquals(1, fixture.retryWork.calls);
        assertEquals(1, fixture.supervisor.calls);
        assertTrue(fixture.scheduler.queued.isEmpty());
    }

    @Test
    public void oneSchedulerRejectionIsRetriedWithoutNewCapture() {
        Fixture fixture = new Fixture();
        fixture.scheduler.failSchedules = 1;

        fixture.stream.requestRetry();

        assertEquals(1, fixture.scheduler.queued.size());
        fixture.scheduler.runNext();
        assertEquals(1, fixture.retryWork.calls);
    }

    @Test(expected = RetrySchedulingException.class)
    public void persistentSchedulerContractFailureIsNeverSilentlyDropped() {
        Fixture fixture = new Fixture();
        fixture.scheduler.failSchedules = 2;

        fixture.stream.requestRetry();
    }

    @Test
    public void schedulerContractFailureSignalsRestartAndStartupRecoveryNeedsNoEvent() {
        Fixture fixture = new Fixture();
        fixture.scheduler.failSchedules = 2;

        try {
            fixture.stream.requestRetry();
            throw new AssertionError("expected scheduler failure");
        } catch (RetrySchedulingException expected) {
            assertEquals(1, fixture.supervisor.calls);
        }

        fixture.retryWork.recoveryRequiredOnStart = true;
        ReadReceiptDurableStream restarted = fixture.newStream();
        assertNotNull(restarted);
        assertEquals(1, fixture.scheduler.queued.size());
        fixture.scheduler.runNext();
        assertEquals(1, fixture.retryWork.calls);
    }

    @Test
    public void leaseSelectionBoundsEachListAt256AndCompletesQuickly() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(100, 256));

        long started = System.nanoTime();
        LeaseFrame frame = fixture.stream.claimOrReplayLease();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(256, frame.lease.events.size());
        assertEquals(1, frame.lease.batches.size());
        assertTrue("256-event projection exceeded bounded local budget", elapsedMs < 5_000);
    }

    @Test
    public void eventListSplitsAt257AndReusesImmutableBatchMetadata() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(110, 257));

        LeaseFrame first = fixture.stream.claimOrReplayLease();
        assertEquals(256, first.lease.events.size());
        assertEquals(256, first.lease.lastSequence);
        fixture.stream.acceptAck(ack(first, INSTALLATION, EPOCH, first.lease.leaseId,
                first.lease.lastSequence, first.digest));

        LeaseFrame second = fixture.stream.claimOrReplayLease();
        assertEquals(1, second.lease.events.size());
        assertEquals(257, second.lease.firstSequence);
        assertEquals(first.lease.batches.get(0).batchId, second.lease.batches.get(0).batchId);
        assertArrayEquals(ReadReceiptProtocolV2.metadataDigest(first.lease.batches.get(0)),
                ReadReceiptProtocolV2.metadataDigest(second.lease.batches.get(0)));
    }

    @Test
    public void maintenanceConvertsPartialAckBatchSuffixAtItsExactPosition() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(111, 257));
        LeaseFrame first = fixture.stream.claimOrReplayLease();
        fixture.stream.acceptAck(ack(first, INSTALLATION, EPOCH, first.lease.leaseId,
                first.lease.lastSequence, first.digest));

        MaintenanceResult result = fixture.stream.maintain(
                new Capacity(
                        ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0));

        assertEquals(MaintenanceOutcome.CONVERTED, result.outcome);
        assertEquals(257, result.loss.firstSequence);
        assertEquals(257, result.loss.lastSequence);
        assertEquals(1, result.loss.droppedEventCount);
        assertTrue(fixture.store.durable.events.isEmpty());
        assertEquals(1, fixture.store.durable.losses.size());
    }

    @Test
    public void productionOperationsUseBoundedViewsInsteadOfFullSnapshotApi() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(112, 2));
        LeaseFrame lease = fixture.stream.claimOrReplayLease();
        fixture.store.failAfterCommitOnce = true;
        fixture.stream.acceptAck(ack(lease, INSTALLATION, EPOCH, lease.lease.leaseId,
                lease.lease.lastSequence, lease.digest));
        fixture.stream.stateUpdate();

        assertEquals(0, fixture.store.fullSnapshotReads);
        assertTrue(fixture.store.controlReads > 0);
        assertTrue(fixture.store.leaseWindowReads > 0);
        assertTrue(fixture.store.ackEvidenceReads > 0);
    }

    @Test
    public void lossAndMetadataListsIndependentlySplitAt257() {
        Fixture fixture = new Fixture();
        for (int index = 0; index < 257; index++) {
            fixture.stream.append(batch(200 + index, 1));
            assertEquals(MaintenanceOutcome.CONVERTED,
                    fixture.stream.maintain(new Capacity(
                            ReadReceiptDurableStream.MAX_EVENT_ROWS + 1L, 0, 0)).outcome);
        }

        LeaseFrame first = fixture.stream.claimOrReplayLease();
        assertTrue(first.lease.events.isEmpty());
        assertEquals(256, first.lease.losses.size());
        assertEquals(256, first.lease.batches.size());
        fixture.stream.acceptAck(ack(first, INSTALLATION, EPOCH, first.lease.leaseId,
                first.lease.lastSequence, first.digest));

        LeaseFrame second = fixture.stream.claimOrReplayLease();
        assertEquals(1, second.lease.losses.size());
        assertEquals(1, second.lease.batches.size());
        assertEquals(257, second.lease.firstSequence);
    }

    @Test
    public void sequenceHoleCannotCreateLease() {
        Fixture fixture = new Fixture();
        fixture.stream.append(batch(120, 2));
        fixture.store.durable.events.remove(0);

        assertNull(fixture.stream.claimOrReplayLease());
        assertNull(fixture.store.durable.lease);
        assertEquals(1, fixture.store.durable.events.size());
    }

    private static void assertRejectedAndPreserved(Fixture fixture,
                                                     Ack ack,
                                                     AckOutcome outcome) {
        int eventCount = fixture.store.durable.events.size();
        UUID leaseId = fixture.store.durable.lease.leaseId;
        AckResult result = fixture.stream.acceptAck(ack);
        assertEquals(outcome, result.outcome);
        assertEquals(eventCount, fixture.store.durable.events.size());
        assertEquals(leaseId, fixture.store.durable.lease.leaseId);
    }

    private static Ack ack(LeaseFrame frame,
                                                     UUID installation, UUID epoch, UUID lease,
                                                     long through, byte[] digest) {
        return new Ack(installation, epoch, lease, through, digest);
    }

    private static BatchInput batch(int seed, int count) {
        List<CapturedEvent> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            events.add(new CapturedEvent(
                    uuid(seed * 1000 + index), seed, index, seed + index));
        }
        return new BatchInput(uuid(seed), 1_000L + seed,
                2_000L + seed, SESSION, BOOT, SOURCE, events);
    }

    private static ReadReceiptCaptureCoordinator.CapturedBatch captured(int seed, int count) {
        List<ReadReceiptCaptureCoordinator.MemberWatermark> members = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            members.add(new ReadReceiptCaptureCoordinator.MemberWatermark(seed, index,
                    seed + index));
        }
        try {
            java.lang.reflect.Constructor<ReadReceiptCaptureCoordinator.CapturedBatch> constructor =
                    ReadReceiptCaptureCoordinator.CapturedBatch.class.getDeclaredConstructor(
                            List.class, long.class, long.class, UUID.class, UUID.class, UUID.class);
            constructor.setAccessible(true);
            return constructor.newInstance(members, 1_000L + seed, 2_000L + seed,
                    SESSION, BOOT, SOURCE);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertSequences(List<EventRecord> events,
                                        long... expected) {
        long[] actual = new long[events.size()];
        for (int index = 0; index < events.size(); index++) {
            actual[index] = events.get(index).event.sequence;
        }
        assertArrayEquals(expected, actual);
    }

    private static Thread facadeThread(String name, CountDownLatch started,
                                       CountDownLatch completed,
                                       AtomicReference<Throwable> failure,
                                       Runnable action) {
        return new Thread(() -> {
            started.countDown();
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                completed.countDown();
            }
        }, name);
    }

    private static void assertBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    private static final class Fixture {
        final FakeStore store = new FakeStore();
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeRetryWork retryWork = new FakeRetryWork();
        final FakeCounterJournal counterJournal = new FakeCounterJournal();
        final FakeSupervisor supervisor = new FakeSupervisor();
        long nextId = 10_000;
        long now = 50_000;
        ReadReceiptDurableStream stream = newStream();

        ReadReceiptDurableStream newStream() {
            return new ReadReceiptDurableStream(store, () -> uuid(nextId++), () -> now++, scheduler,
                    retryWork, counterJournal, supervisor);
        }

        ReadReceiptDurableStream newStream(PendingBatchJournal journal) {
            return new ReadReceiptDurableStream(store, () -> uuid(nextId++), () -> now++, scheduler,
                    retryWork, counterJournal, journal, supervisor);
        }
    }

    private static final class FakePendingBatchJournal
            implements PendingBatchJournal {
        PendingBatchRecord record;
        int failPublishes;
        int failLoads;
        int failCommitting;
        int failCommittingAfter;
        int failTerminals;
        int failClears;

        @Override
        public PendingBatchRecord load() {
            if (failLoads-- > 0) throw new StorageException();
            return record;
        }

        @Override
        public void publishPending(BatchInput input) {
            if (failPublishes-- > 0) throw new StorageException();
            if (record != null) throw new StorageException();
            record = new PendingBatchRecord(
                    PendingBatchState.PENDING, input, null);
        }

        @Override
        public void markCommittingExact(BatchInput expected) {
            if (failCommitting-- > 0) throw new StorageException();
            require(expected, PendingBatchState.PENDING);
            record = new PendingBatchRecord(
                    PendingBatchState.COMMITTING, expected, null);
            if (failCommittingAfter-- > 0) {
                throw new StorageException();
            }
        }

        @Override
        public void markTerminalExact(BatchInput expected,
                                      PendingBatchState state,
                                      CounterTarget target) {
            if (failTerminals-- > 0) throw new StorageException();
            require(expected, PendingBatchState.COMMITTING);
            record = new PendingBatchRecord(state, expected, target);
        }

        @Override
        public void clearTerminalExact(PendingBatchRecord expected) {
            if (failClears-- > 0) throw new StorageException();
            if (record != null && record.same(expected) && record.terminal()) record = null;
        }

        private void require(BatchInput expected,
                             PendingBatchState state) {
            if (record == null || record.state != state || !record.sameInput(expected)) {
                throw new StorageException();
            }
        }
    }

    private static final class FakeCounterJournal
            implements CounterJournal {
        CounterTarget durable;
        int failStores;

        @Override
        public CounterTarget load() {
            return durable;
        }

        @Override
        public void storeGuaranteed(CounterTarget target) {
            if (failStores > 0) {
                failStores--;
                throw new StorageException();
            }
            durable = target;
        }

        @Override
        public void clearExact(CounterTarget expected) {
            if (durable != null && durable.same(expected)) durable = null;
        }
    }

    private static final class FakeRetryWork implements RetryWork {
        final Deque<Boolean> results = new ArrayDeque<>();
        int calls;
        boolean recoveryRequiredOnStart;
        boolean failClosed;
        Runnable onRun;

        @Override
        public boolean runOnce() {
            calls++;
            if (onRun != null) onRun.run();
            if (failClosed) throw new DurabilityContractException();
            return results.isEmpty() || results.removeFirst();
        }

        @Override
        public boolean recoveryRequiredOnStart() {
            return recoveryRequiredOnStart;
        }
    }

    private static final class FakeSupervisor
            implements RuntimeFailureSupervisor {
        int calls;

        @Override
        public void restartRequired(RuntimeException failure) {
            calls++;
        }
    }

    private static final class FakeScheduler implements RetryScheduler {
        final Deque<Runnable> queued = new ArrayDeque<>();
        final Deque<Long> delays = new ArrayDeque<>();
        int failSchedules;

        @Override
        public void scheduleGuaranteed(long delayMs, Runnable work) {
            if (failSchedules > 0) {
                failSchedules--;
                throw new StorageException();
            }
            delays.addLast(delayMs);
            queued.addLast(work);
        }

        void runNext() {
            queued.removeFirst().run();
        }
    }

    private static final class FakeStore implements Store {
        Snapshot durable = initialSnapshot();
        final List<String> trace = new ArrayList<>();
        String failOnce;
        boolean failAfterCommitOnce;
        int fullSnapshotReads;
        int controlReads;
        int ackEvidenceReads;
        int leaseWindowReads;

        void failOnce(String statement) {
            failOnce = statement;
        }

        @Override
        public Snapshot readSnapshot() {
            fullSnapshotReads++;
            return durable.copy();
        }

        @Override
        public Snapshot readControl() {
            controlReads++;
            return durable.copy();
        }

        @Override
        public Snapshot readAckEvidence(
                LeaseRecord lease) {
            ackEvidenceReads++;
            return durable.copy();
        }

        @Override
        public ReconcileResult reconcileAppend(
                BatchInput input) {
            return ReadReceiptDurableStream.reconcileAppendSnapshot(input, durable.copy());
        }

        @Override
        public Transaction beginImmediate() {
            trace.add("BEGIN");
            return new Tx(this, durable.copy());
        }

        private void hit(String statement) {
            trace.add(statement);
            if (statement.equals(failOnce)) {
                failOnce = null;
                throw new StorageException();
            }
        }

        private static Snapshot initialSnapshot() {
            StateRecord state = new StateRecord(
                    INSTALLATION, EPOCH, 0, 0, 1, Counters.zero(),
                    Status.ACTIVE);
            return new Snapshot(state, null,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    private static final class Tx implements Transaction {
        private final FakeStore owner;
        private Snapshot working;
        private boolean committed;

        Tx(FakeStore owner, Snapshot working) {
            this.owner = owner;
            this.working = working;
        }

        @Override
        public Snapshot readSnapshot() {
            owner.fullSnapshotReads++;
            return working.copy();
        }

        @Override
        public Snapshot readControl() {
            owner.controlReads++;
            return working.copy();
        }

        @Override
        public Snapshot readLeaseWindow() {
            owner.leaseWindowReads++;
            return working.copy();
        }

        @Override
        public BatchSelection selectOldestCompleteUnleasedBatch() {
            return Transaction.super
                    .selectOldestCompleteUnleasedBatch();
        }

        @Override
        public void insertBatch(BatchRecord batch) {
            owner.hit("INSERT_BATCH");
            for (BatchRecord prior : working.batches) {
                if (prior.metadata.batchId.equals(batch.metadata.batchId)) {
                    throw new StorageException();
                }
            }
            working.batches.add(batch);
        }

        @Override
        public void insertEvent(EventRecord event) {
            owner.hit("INSERT_EVENT");
            for (EventRecord prior : working.events) {
                if (prior.event.sequence == event.event.sequence
                        || prior.event.eventId.equals(event.event.eventId)) {
                    throw new StorageException();
                }
            }
            working.events.add(event);
        }

        @Override
        public void insertLoss(LossRecord loss) {
            owner.hit("INSERT_LOSS");
            working.losses.add(loss);
        }

        @Override
        public void updateState(StateRecord state) {
            owner.hit("UPDATE_STATE");
            working.state = state;
        }

        @Override
        public void insertLease(LeaseRecord lease) {
            owner.hit("INSERT_LEASE");
            if (working.lease != null) throw new StorageException();
            working.lease = lease;
        }

        @Override
        public int deleteEventsExact(List<EventRecord> expected) {
            owner.hit("DELETE_EVENTS");
            int removed = 0;
            for (EventRecord item : expected) {
                for (int index = 0; index < working.events.size(); index++) {
                    EventRecord current = working.events.get(index);
                    if (current.same(item)) {
                        working.events.remove(index);
                        removed++;
                        break;
                    }
                }
            }
            return removed;
        }

        @Override
        public int deleteLossesExact(List<LossRecord> expected) {
            owner.hit("DELETE_LOSSES");
            int removed = 0;
            for (LossRecord item : expected) {
                for (int index = 0; index < working.losses.size(); index++) {
                    if (working.losses.get(index).same(item)) {
                        working.losses.remove(index);
                        removed++;
                        break;
                    }
                }
            }
            return removed;
        }

        @Override
        public int deleteUnreferencedBatchesExact(
                List<BatchRecord> candidates) {
            owner.hit("DELETE_BATCHES");
            Set<UUID> referenced = new HashSet<>();
            for (EventRecord event : working.events) {
                referenced.add(event.event.batchId);
            }
            for (LossRecord loss : working.losses) {
                if (loss.loss.batchId != null) referenced.add(loss.loss.batchId);
            }
            int removed = 0;
            for (BatchRecord candidate : candidates) {
                if (referenced.contains(candidate.metadata.batchId)) continue;
                for (int index = 0; index < working.batches.size(); index++) {
                    if (working.batches.get(index).same(candidate)) {
                        working.batches.remove(index);
                        removed++;
                        break;
                    }
                }
            }
            return removed;
        }

        @Override
        public int deleteLeaseExact(LeaseRecord expected) {
            owner.hit("DELETE_LEASE");
            if (working.lease != null && working.lease.same(expected)) {
                working.lease = null;
                return 1;
            }
            return 0;
        }

        @Override
        public int replaceEventsWithLossExact(BatchRecord batch,
                                              List<EventRecord> events,
                                              LossRecord loss) {
            owner.hit("REPLACE_WITH_LOSS");
            int removed = 0;
            for (EventRecord expected : events) {
                for (int index = 0; index < working.events.size(); index++) {
                    if (working.events.get(index).same(expected)) {
                        working.events.remove(index);
                        removed++;
                        break;
                    }
                }
            }
            if (removed != events.size()) return removed;
            working.losses.add(loss);
            return removed;
        }

        @Override
        public void poison(String category) {
            owner.hit("POISON");
            working.state = working.state.poisoned(category);
            if (working.lease != null) working.lease = working.lease.poisoned(category);
        }

        @Override
        public void commit() {
            owner.hit("COMMIT");
            owner.durable = working.copy();
            committed = true;
            if (owner.failAfterCommitOnce) {
                owner.failAfterCommitOnce = false;
                throw new StorageException();
            }
        }

        @Override
        public void close() {
            if (!committed) working = null;
        }
    }
}
