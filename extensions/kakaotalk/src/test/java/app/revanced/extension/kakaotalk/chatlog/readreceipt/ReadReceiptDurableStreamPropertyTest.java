package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReadReceiptDurableStreamPropertyTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);
    private static final UUID SESSION = uuid(3);
    private static final UUID BOOT = uuid(4);
    private static final UUID SOURCE = uuid(5);

    @Test
    public void generatedMaintenanceSequencesNeverPruneLeasedEvidence() {
        for (long seed = 0; seed < 40; seed++) {
            Fixture fixture = new Fixture();
            Random random = new Random(seed);
            appendBacklog(fixture, random, seed);

            LeaseFrame frozen = fixture.stream.claimOrReplayLease();
            assertNotNull("seed=" + seed, frozen);
            byte[] preimage = ReadReceiptProtocolV2.leasePreimage(frozen.lease);
            assertLeasedEvidencePresent(fixture.store.durable, frozen);

            for (int step = 0; step < 32; step++) {
                switch (random.nextInt(6)) {
                    case 0:
                        fixture.stream.maintain(new Capacity(
                                ReadReceiptDurableStream.MAX_EVENT_ROWS + 1, 0, 0));
                        break;
                    case 1:
                        fixture.stream.maintain(new Capacity(
                                0, ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));
                        break;
                    case 2:
                        fixture.stream.recordUncertainOutcome(random.nextInt(4),
                                random.nextInt(2));
                        break;
                    case 3:
                        fixture.stream.recordCaptureDrop(random.nextInt(4), random.nextInt(2));
                        break;
                    case 4:
                        rejectWrongAck(fixture, frozen, random.nextInt(3));
                        break;
                    default:
                        fixture.stream = fixture.newStream();
                        break;
                }

                LeaseFrame replay =
                        fixture.stream.claimOrReplayLease();
                assertNotNull("seed=" + seed + " step=" + step, replay);
                assertArrayEquals("seed=" + seed + " step=" + step,
                        preimage, ReadReceiptProtocolV2.leasePreimage(replay.lease));
                assertArrayEquals("seed=" + seed + " step=" + step,
                        frozen.digest, replay.digest);
                assertLeasedEvidencePresent(fixture.store.durable, frozen);
            }
        }
    }

    @Test
    public void unknownAppendOutcomesNeverIssuePositionsOrEvidenceRows() {
        List<String> rollbackBoundaries = Arrays.asList(
                "INSERT_BATCH", "INSERT_EVENT", "UPDATE_STATE", "COMMIT");
        for (long seed = 0; seed < 64; seed++) {
            Fixture fixture = new Fixture();
            String boundary = rollbackBoundaries.get((int) (seed % rollbackBoundaries.size()));
            fixture.store.failOnce(boundary);
            int eventCount = 1 + new Random(seed).nextInt(8);

            AppendResult result = fixture.stream.append(
                    batch(seed + 1, eventCount));

            if (result.outcome == AppendOutcome.UNCERTAIN) {
                assertEquals("seed=" + seed, "COMMIT", boundary);
                assertEquals("seed=" + seed, eventCount,
                        fixture.store.durable.state.counters.uncertainOutcomeEventCount);
                assertEquals("seed=" + seed, 1,
                        fixture.store.durable.state.counters.uncertainOutcomeBatchCount);
            } else {
                assertEquals("seed=" + seed,
                        AppendOutcome.STORAGE_UNAVAILABLE,
                        result.outcome);
            }
            assertNoIssuedEvidence(fixture.store.durable, "seed=" + seed);
        }
    }

    @Test
    public void generatedUncertainCountersNeverCreateRowsOrConsumePositions() {
        for (long seed = 0; seed < 40; seed++) {
            Fixture fixture = new Fixture();
            Random random = new Random(seed);
            long expectedEvents = 0;
            long expectedBatches = 0;
            for (int step = 0; step < 48; step++) {
                long events = random.nextInt(7);
                long batches = random.nextInt(3);
                expectedEvents += events;
                expectedBatches += batches;
                fixture.stream.recordUncertainOutcome(events, batches);
                assertNoIssuedEvidence(fixture.store.durable,
                        "seed=" + seed + " step=" + step);
            }
            assertEquals(expectedEvents,
                    fixture.store.durable.state.counters.uncertainOutcomeEventCount);
            assertEquals(expectedBatches,
                    fixture.store.durable.state.counters.uncertainOutcomeBatchCount);
        }
    }

    @Test
    public void replayIsByteExactAcrossCrashReconnectCounterChangeAndAckLoss() {
        for (long seed = 0; seed < 64; seed++) {
            Fixture fixture = new Fixture();
            Random random = new Random(seed);
            int batches = 1 + random.nextInt(6);
            for (int index = 0; index < batches; index++) {
                AppendResult result = fixture.stream.append(
                        batch(seed * 100 + index + 1, 1 + random.nextInt(20)));
                assertEquals(AppendOutcome.COMMITTED, result.outcome);
            }

            LeaseFrame original = fixture.stream.claimOrReplayLease();
            assertNotNull("seed=" + seed, original);
            byte[] expected = ReadReceiptProtocolV2.leasePreimage(original.lease);

            fixture.stream = fixture.newStream();
            assertExactReplay(expected, original, fixture.stream.claimOrReplayLease(), seed);
            fixture.stream.recordCaptureDrop(3 + seed, 1);
            fixture.stream.recordUncertainOutcome(5 + seed, 2);
            assertExactReplay(expected, original, fixture.stream.claimOrReplayLease(), seed);

            fixture.stream = fixture.newStream();
            assertExactReplay(expected, original, fixture.stream.claimOrReplayLease(), seed);
            assertNotNull("lost ACK must leave the lease replayable",
                    fixture.store.durable.lease);
            assertLeasedEvidencePresent(fixture.store.durable, original);
        }
    }

    @Test
    public void postCommitErrorIsReconciledAsCommittedRatherThanUnknown() {
        for (long seed = 0; seed < 32; seed++) {
            Fixture fixture = new Fixture();
            int eventCount = 1 + new Random(seed).nextInt(8);
            fixture.store.failAfterCommitOnce = true;

            AppendResult result = fixture.stream.append(
                    batch(seed + 10_000, eventCount));

            assertEquals("seed=" + seed, AppendOutcome.COMMITTED,
                    result.outcome);
            assertEquals(eventCount, fixture.store.durable.events.size());
            assertTrue(fixture.store.durable.losses.isEmpty());
            assertEquals(0,
                    fixture.store.durable.state.counters.uncertainOutcomeEventCount);
            assertEquals(eventCount, fixture.store.durable.state.highestIssuedSequence);
        }
    }

    private static void appendBacklog(Fixture fixture, Random random, long seed) {
        int total = 0;
        int index = 0;
        while (total < 320) {
            int count = 24 + random.nextInt(48);
            AppendResult result = fixture.stream.append(
                    batch(seed * 1_000 + ++index, count));
            assertEquals("seed=" + seed, AppendOutcome.COMMITTED,
                    result.outcome);
            total += count;
        }
    }

    private static void rejectWrongAck(Fixture fixture,
                                       LeaseFrame frame, int kind) {
        UUID installation = frame.lease.installationId;
        UUID epoch = frame.lease.streamEpoch;
        UUID lease = frame.lease.leaseId;
        long through = frame.lease.lastSequence;
        byte[] digest = frame.digest.clone();
        AckOutcome expected;
        if (kind == 0) {
            lease = uuid(9_000_000);
            expected = AckOutcome.LEASE_MISMATCH;
        } else if (kind == 1) {
            through--;
            expected = AckOutcome.RANGE_MISMATCH;
        } else {
            digest[0] ^= 1;
            expected = AckOutcome.DIGEST_MISMATCH;
        }
        AckResult result = fixture.stream.acceptAck(
                new Ack(
                        installation, epoch, lease, through, digest));
        assertEquals(expected, result.outcome);
    }

    private static void assertExactReplay(byte[] expected,
                                          LeaseFrame original,
                                          LeaseFrame replay, long seed) {
        assertNotNull("seed=" + seed, replay);
        assertArrayEquals("seed=" + seed, expected,
                ReadReceiptProtocolV2.leasePreimage(replay.lease));
        assertArrayEquals("seed=" + seed, original.digest, replay.digest);
    }

    private static void assertNoIssuedEvidence(Snapshot snapshot,
                                               String label) {
        assertEquals(label, 0, snapshot.state.lastAckedSequence);
        assertEquals(label, 0, snapshot.state.highestIssuedSequence);
        assertEquals(label, 1, snapshot.state.nextSequence);
        assertTrue(label, snapshot.batches.isEmpty());
        assertTrue(label, snapshot.events.isEmpty());
        assertTrue(label, snapshot.losses.isEmpty());
        assertNull(label, snapshot.lease);
    }

    private static void assertLeasedEvidencePresent(Snapshot snapshot,
                                                    LeaseFrame frame) {
        ReadReceiptProtocolV2.Lease lease = frame.lease;
        int length = Math.toIntExact(lease.lastSequence - lease.firstSequence + 1);
        int[] coverage = new int[length];
        for (ReadReceiptProtocolV2.Event expected : lease.events) {
            boolean found = false;
            for (EventRecord actual : snapshot.events) {
                if (actual.event.sequence == expected.sequence
                        && actual.event.eventId.equals(expected.eventId)
                        && actual.event.batchId.equals(expected.batchId)
                        && actual.event.chatId == expected.chatId
                        && actual.event.userId == expected.userId
                        && actual.event.watermark == expected.watermark) {
                    found = true;
                    break;
                }
            }
            assertTrue("missing leased event " + expected.sequence, found);
            coverage[Math.toIntExact(expected.sequence - lease.firstSequence)]++;
        }
        for (ReadReceiptProtocolV2.Loss expected : lease.losses) {
            boolean found = false;
            byte[] digest = ReadReceiptProtocolV2.lossDigest(expected);
            for (LossRecord actual : snapshot.losses) {
                if (actual.loss.lossReceiptId.equals(expected.lossReceiptId)
                        && Arrays.equals(actual.contentDigest, digest)) {
                    found = true;
                    break;
                }
            }
            assertTrue("missing leased loss " + expected.lossReceiptId, found);
            for (long sequence = expected.firstSequence;
                 sequence <= expected.lastSequence; sequence++) {
                coverage[Math.toIntExact(sequence - lease.firstSequence)]++;
            }
        }
        for (ReadReceiptProtocolV2.Metadata expected : lease.batches) {
            boolean found = false;
            byte[] digest = ReadReceiptProtocolV2.metadataDigest(expected);
            for (BatchRecord actual : snapshot.batches) {
                if (actual.metadata.batchId.equals(expected.batchId)
                        && Arrays.equals(actual.metadataDigest, digest)) {
                    found = true;
                    break;
                }
            }
            assertTrue("missing leased batch " + expected.batchId, found);
        }
        for (int count : coverage) assertEquals("lease coverage changed", 1, count);
    }

    private static BatchInput batch(long seed, int count) {
        List<CapturedEvent> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long member = Math.addExact(Math.multiplyExact(seed, 1_000L), index);
            events.add(new CapturedEvent(
                    uuid(2_000_000L + member), seed, member, seed + index));
        }
        return new BatchInput(uuid(1_000_000L + seed),
                10_000L + seed, 20_000L + seed, SESSION, BOOT, SOURCE, events);
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    private static final class Fixture {
        final ModelStore store = new ModelStore();
        final Scheduler scheduler = new Scheduler();
        final FakeCounterJournal counterJournal = new FakeCounterJournal();
        long nextId = 20_000_000;
        long now = 50_000;
        ReadReceiptDurableStream stream = newStream();

        ReadReceiptDurableStream newStream() {
            return new ReadReceiptDurableStream(store, () -> uuid(nextId++), () -> now++,
                    scheduler, () -> true, counterJournal, failure -> { });
        }
    }

    private static final class FakeCounterJournal implements CounterJournal {
        CounterTarget target;

        @Override
        public CounterTarget load() {
            return target;
        }

        @Override
        public void storeGuaranteed(CounterTarget value) {
            target = value;
        }

        @Override
        public void clearExact(CounterTarget expected) {
            if (target != null && target.same(expected)) target = null;
        }
    }

    private static final class Scheduler implements RetryScheduler {
        final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void scheduleGuaranteed(long delayMs, Runnable work) {
            queued.addLast(work);
        }
    }

    private static final class ModelStore implements Store {
        Snapshot durable = initialSnapshot();
        String failOnce;
        boolean failAfterCommitOnce;

        void failOnce(String statement) {
            failOnce = statement;
        }

        @Override
        public Snapshot readSnapshot() {
            return durable.copy();
        }

        @Override
        public Snapshot readControl() {
            return durable.copy();
        }

        @Override
        public Snapshot readAckEvidence(
                LeaseRecord lease) {
            return durable.copy();
        }

        @Override
        public ReconcileResult reconcileAppend(
                BatchInput input) {
            return ReadReceiptDurableStream.reconcileAppendSnapshot(input, durable.copy());
        }

        @Override
        public Transaction beginImmediate() {
            return new ModelTransaction(this, durable.copy());
        }

        void hit(String statement) {
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

    private static final class ModelTransaction implements Transaction {
        private final ModelStore owner;
        private Snapshot working;
        private boolean committed;

        ModelTransaction(ModelStore owner, Snapshot working) {
            this.owner = owner;
            this.working = working;
        }

        @Override
        public Snapshot readSnapshot() {
            return working.copy();
        }

        @Override
        public Snapshot readControl() {
            return working.copy();
        }

        @Override
        public Snapshot readLeaseWindow() {
            return working.copy();
        }

        @Override
        public void insertBatch(BatchRecord batch) {
            owner.hit("INSERT_BATCH");
            for (BatchRecord prior : working.batches) {
                if (prior.metadata.batchId.equals(batch.metadata.batchId)) throw failure();
            }
            working.batches.add(batch);
        }

        @Override
        public void insertEvent(EventRecord event) {
            owner.hit("INSERT_EVENT");
            for (EventRecord prior : working.events) {
                if (prior.event.sequence == event.event.sequence
                        || prior.event.eventId.equals(event.event.eventId)) throw failure();
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
            if (working.lease != null) throw failure();
            working.lease = lease;
        }

        @Override
        public int deleteEventsExact(List<EventRecord> expected) {
            owner.hit("DELETE_EVENTS");
            int removed = 0;
            for (EventRecord item : expected) {
                for (int index = 0; index < working.events.size(); index++) {
                    if (working.events.get(index).same(item)) {
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
            if (removed == events.size()) working.losses.add(loss);
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
                throw failure();
            }
        }

        @Override
        public void close() {
            if (!committed) working = null;
        }

        private static StorageException failure() {
            return new StorageException();
        }
    }
}
