package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.Test;

public final class ReadReceiptStartupValidationTest {
    private static final UUID INSTALLATION = uuid("11111111-1111-8111-8111-111111111111");
    private static final UUID EPOCH = uuid("22222222-2222-4222-8222-222222222222");
    private static final UUID LEASE = uuid("33333333-3333-4333-8333-333333333333");
    private static final UUID BATCH_A = uuid("44444444-4444-4444-8444-444444444441");
    private static final UUID BATCH_B = uuid("44444444-4444-4444-8444-444444444442");
    private static final UUID SESSION = uuid("55555555-5555-4555-8555-555555555555");
    private static final UUID BOOT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SOURCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID OTHER = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final byte[] BASE_DIGEST = canonicalDigest(new SnapshotBuilder());

    @Test
    public void validSnapshotCommitsBeforeActivationAndUsesTransactionProjection() {
        SnapshotBuilder builder = valid();
        builder.stateCounters = counters(11, 12, 13, 14, 15, 16);
        builder.leaseCounters = counters(1, 2, 3, 4, 5, 6);
        builder.leaseDigest = canonicalDigest(builder);
        FakeStore store = new FakeStore(builder.build());
        SideEffect sideEffect = new SideEffect(store);

        ReadReceiptStartupValidation.Result result = validate(store, sideEffect);

        assertEquals(ReadReceiptStartupValidation.Outcome.READY, result.outcome);
        assertTrue(result.bindPermitted);
        assertEquals(1, store.commits);
        assertEquals(1, store.digestCalls);
        assertTrue(store.digestUsedCurrentSnapshot);
        assertTrue(sideEffect.sawCommittedTransaction);
        assertArrayEquals(builder.leaseCounters.toArray(),
                store.lastProjection.counters.toArray());
        assertNotNull(result.activation);
        result.close();
        assertEquals(1, sideEffect.cleanups);
    }

    @Test
    public void singletonAbsenceOrMultiplicityCannotClaimDurablePoison() {
        SnapshotBuilder missing = valid();
        missing.streamSingletonCount = 0;
        missing.includeState = false;
        assertStorageUnavailable(missing, 0, 0);

        SnapshotBuilder duplicate = valid();
        duplicate.streamSingletonCount = 2;
        assertStorageUnavailable(duplicate, 0, 0);

        SnapshotBuilder missingLease = valid();
        missingLease.leaseSingletonCount = 0;
        missingLease.includeLease = true;
        assertStorageUnavailable(missingLease, 0, 0);

        SnapshotBuilder duplicateLease = valid();
        duplicateLease.leaseSingletonCount = 2;
        assertStorageUnavailable(duplicateLease, 0, 0);
    }

    @Test
    public void poisonRequiresExactAffectedStreamAndLeaseRows() {
        SnapshotBuilder invalid = valid();
        invalid.events.remove(1);

        FakeStore missingStreamUpdate = new FakeStore(invalid.build());
        missingStreamUpdate.poisonStreamRows = 0;
        assertStorageUnavailable(missingStreamUpdate, new SideEffect(missingStreamUpdate));

        FakeStore missingLeaseUpdate = new FakeStore(invalid.build());
        missingLeaseUpdate.poisonLeaseRows = 0;
        assertStorageUnavailable(missingLeaseUpdate, new SideEffect(missingLeaseUpdate));

        FakeStore duplicateUpdate = new FakeStore(invalid.build());
        duplicateUpdate.poisonStreamRows = 2;
        assertStorageUnavailable(duplicateUpdate, new SideEffect(duplicateUpdate));

        FakeStore reportedWithoutMutation = new FakeStore(invalid.build());
        reportedWithoutMutation.skipPoisonMutation = true;
        assertStorageUnavailable(reportedWithoutMutation, new SideEffect(reportedWithoutMutation));

        invalid.leaseSingletonCount = 0;
        invalid.includeLease = false;
        FakeStore withoutLease = new FakeStore(invalid.build());
        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(withoutLease, new SideEffect(withoutLease)).outcome);
        assertEquals(0, withoutLease.poisonLeaseRows);
    }

    @Test
    public void alreadyPoisonedStreamIdempotentlyPoisonsActiveLeaseAndSurvivesModelReopen() {
        SnapshotBuilder builder = valid();
        builder.streamStatus = ReadReceiptStartupValidation.Status.POISONED;
        builder.streamPoisonCategory = "sequence_hole";
        FakeStore store = new FakeStore(builder.build());
        SideEffect sideEffect = new SideEffect(store);

        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(store, sideEffect).outcome);
        assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.state.status);
        assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.lease.status);
        assertEquals("sequence_hole", store.durable.lease.poisonCategory);
        assertEquals(1, store.poisonCalls);

        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(store, sideEffect).outcome);
        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(store, sideEffect).outcome);
        assertEquals(3, store.poisonCalls);
        assertEquals(3, store.commits);
        assertEquals(0, sideEffect.prepares);
    }

    @Test
    public void firstPoisonPersistsAcrossTwoModelReopensBeforeActivation() {
        SnapshotBuilder builder = valid();
        builder.events.remove(1);
        FakeStore store = new FakeStore(builder.build());
        SideEffect sideEffect = new SideEffect(store);

        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(store, sideEffect).outcome);
        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(store, sideEffect).outcome);
        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED,
                validate(store, sideEffect).outcome);

        assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.state.status);
        assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.lease.status);
        assertEquals(3, store.commits);
        assertEquals(0, sideEffect.prepares);
    }

    @Test
    public void canonicalLeaseProjectionRejectsHoleOverlapAndMissingMetadataReference() {
        SnapshotBuilder hole = valid();
        hole.events.remove(1);
        assertPoison(hole, "sequence_hole");

        SnapshotBuilder overlap = valid();
        overlap.losses.add(loss(uuid("99999999-9999-4999-8999-999999999998"),
                2, 2, 1, 0, null, "bounded_prune", 4000));
        assertPoison(overlap, "range_mismatch");

        SnapshotBuilder crossing = valid();
        crossing.leaseLast = 3;
        assertPoison(crossing, "lease_mismatch");

        SnapshotBuilder missingMetadata = valid();
        missingMetadata.batches.remove(1);
        assertPoison(missingMetadata, "batch_conflict");

    }

    @Test
    public void consecutiveLeasesCanReferenceOneBatchWhoseMetadataSpansBothRanges() {
        SnapshotBuilder first = valid();
        first.highestIssued = 2;
        first.nextSequence = 3;
        first.leaseLast = 1;
        first.batches.clear();
        first.batches.add(batch(BATCH_A, 1, 2, 2, 1000, 2000, SESSION, BOOT, SOURCE));
        first.losses.clear();
        first.leaseDigest = projectionDigest(
                first,
                first.batches,
                Arrays.asList(first.events.get(0)),
                new ArrayList<>());

        FakeStore firstStore = new FakeStore(first.build());
        assertEquals(ReadReceiptStartupValidation.Outcome.READY,
                validate(firstStore, new SideEffect(firstStore)).outcome);
        assertEquals(1, firstStore.lastProjection.batches.size());
        assertEquals(1, firstStore.lastProjection.events.size());

        SnapshotBuilder second = valid();
        second.lastAcked = 1;
        second.highestIssued = 2;
        second.nextSequence = 3;
        second.leaseFirst = 2;
        second.leaseLast = 2;
        second.leasePrevious = 1;
        second.batches.clear();
        second.batches.add(batch(BATCH_A, 1, 2, 2, 1000, 2000, SESSION, BOOT, SOURCE));
        second.events.remove(0);
        second.losses.clear();
        second.leaseDigest = canonicalDigest(second);

        FakeStore secondStore = new FakeStore(second.build());
        assertEquals(ReadReceiptStartupValidation.Outcome.READY,
                validate(secondStore, new SideEffect(secondStore)).outcome);
        assertEquals(1, secondStore.lastProjection.batches.size());
        assertEquals(1, secondStore.lastProjection.events.size());
    }

    @Test
    public void unreferencedMetadataIsNotProjectedIntoTheLease() {
        SnapshotBuilder builder = valid();
        builder.batches.add(batch(OTHER, 3, 3, 1, 9000, 9001, SESSION, BOOT, SOURCE));
        FakeStore store = new FakeStore(builder.build());

        assertEquals(ReadReceiptStartupValidation.Outcome.READY,
                validate(store, new SideEffect(store)).outcome);
        assertEquals(2, store.lastProjection.batches.size());
        assertEquals(1, store.digestCalls);
    }

    @Test
    public void canonicalProjectionEnforcesEveryListLimit() {
        SnapshotBuilder batches = valid();
        batches.highestIssued = 257;
        batches.nextSequence = 258;
        batches.leaseLast = 257;
        batches.batches.clear();
        batches.events.clear();
        batches.losses.clear();
        for (int sequence = 1; sequence <= 256; sequence++) {
            UUID batchId = uuidFor(sequence + 100);
            batches.batches.add(batch(batchId, sequence, sequence, 1,
                    sequence, sequence, SESSION, BOOT, SOURCE));
            batches.events.add(event(sequence, uuidFor(sequence + 1000), batchId, 1, 2, 3));
        }
        UUID lossBatchId = uuidFor(9998);
        batches.batches.add(batch(lossBatchId, 257, 257, 1,
                257, 257, SESSION, BOOT, SOURCE));
        batches.losses.add(loss(uuidFor(9999), 257, 257,
                1, 1, lossBatchId, "bounded_prune", 257));
        assertPoison(batches, "lease_mismatch");

        SnapshotBuilder events = sequenceEvents(257);
        assertPoison(events, "lease_mismatch");

        SnapshotBuilder losses = sequenceLosses(257);
        assertPoison(losses, "lease_mismatch");
    }

    @Test
    public void canonicalIdentityAndBatchDigestChangesUseDigestPath() {
        List<Consumer<SnapshotBuilder>> mutations = Arrays.asList(
                builder -> builder.installationId = OTHER,
                builder -> builder.leaseId = OTHER,
                builder -> builder.batches.set(0, batch(BATCH_A, 2, 2, 1,
                        1000, 2000, SESSION, BOOT, SOURCE)),
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 1, 1,
                        1000, 2000, SESSION, BOOT, SOURCE)),
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 2, 2,
                        1001, 2000, SESSION, BOOT, SOURCE)),
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 2, 2,
                        1000, 2001, SESSION, BOOT, SOURCE)),
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 2, 2,
                        1000, 2000, OTHER, BOOT, SOURCE)),
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 2, 2,
                        1000, 2000, SESSION, null, SOURCE)),
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 2, 2,
                        1000, 2000, SESSION, BOOT, null)));
        assertDigestMutations(mutations);
    }

    @Test
    public void canonicalBatchSubdigestShapeFailureSkipsLeaseDigestPath() {
        assertPreLeaseDigestMutations(Arrays.asList(
                builder -> builder.batches.set(0, batch(BATCH_A, 1, 2, 3,
                        1000, 2000, SESSION, BOOT, SOURCE))));
    }

    @Test
    public void canonicalIdentityAndBatchStructuralFailuresSkipDigestPath() {
        assertStructuralMutations(Arrays.asList(
                structural(builder -> builder.streamEpoch = OTHER, "lease_mismatch"),
                structural(builder -> builder.batches.set(0, batch(OTHER, 1, 2, 2,
                        1000, 2000, SESSION, BOOT, SOURCE)), "batch_conflict")));
    }

    @Test
    public void canonicalEventDigestChangesUseDigestPath() {
        List<Consumer<SnapshotBuilder>> mutations = Arrays.asList(
                builder -> builder.events.set(0, event(1, OTHER, BATCH_A, 10, 20, 30)),
                builder -> builder.events.set(0, event(1, eventId(1), BATCH_B, 10, 20, 30)),
                builder -> builder.events.set(0, event(1, eventId(1), BATCH_A, 11, 20, 30)),
                builder -> builder.events.set(0, event(1, eventId(1), BATCH_A, 10, 21, 30)),
                builder -> builder.events.set(0, event(1, eventId(1), BATCH_A, 10, 20, 31)));
        assertDigestMutations(mutations);
    }

    @Test
    public void canonicalEventStructuralFailuresSkipDigestPath() {
        assertStructuralMutations(Arrays.asList(structural(
                builder -> builder.events.set(
                        0, event(2, eventId(1), BATCH_A, 10, 20, 30)),
                "sequence_hole")));
    }

    @Test
    public void canonicalLossDigestChangesUseDigestPath() {
        List<Consumer<SnapshotBuilder>> mutations = Arrays.asList(
                builder -> builder.losses.set(0, loss(OTHER, 3, 4, 2, 1, BATCH_B,
                        "retry_compaction", 3000)),
                builder -> builder.losses.set(0, loss(lossId(), 3, 4, 2, 2, BATCH_B,
                        "retry_compaction", 3000)),
                builder -> builder.losses.set(0, loss(lossId(), 3, 4, 2, 1, null,
                        "retry_compaction", 3000)),
                builder -> builder.losses.set(0, loss(lossId(), 3, 4, 2, 1, BATCH_B,
                        "bounded_prune", 3000)),
                builder -> builder.losses.set(0, lossWithOptionals(null, 4000L, SESSION, BOOT, SOURCE)),
                builder -> builder.losses.set(0, lossWithOptionals(3000L, null, SESSION, BOOT, SOURCE)),
                builder -> builder.losses.set(0, lossWithOptionals(3000L, 4000L, OTHER, BOOT, SOURCE)),
                builder -> builder.losses.set(0, lossWithOptionals(3000L, 4000L, SESSION, null, SOURCE)),
                builder -> builder.losses.set(0, lossWithOptionals(3000L, 4000L, SESSION, BOOT, null)),
                builder -> builder.losses.set(0, loss(lossId(), 3, 4, 2, 1, BATCH_B,
                        "retry_compaction", 3001)));
        assertDigestMutations(mutations);
    }

    @Test
    public void canonicalLossSubdigestShapeFailureSkipsLeaseDigestPath() {
        assertPreLeaseDigestMutations(Arrays.asList(
                builder -> builder.losses.set(0, loss(
                        lossId(), 3, 4, 3, 1, BATCH_B, "retry_compaction", 3000))));
    }

    @Test
    public void canonicalLossStructuralFailuresSkipDigestPath() {
        assertStructuralMutations(Arrays.asList(
                structural(builder -> builder.losses.set(0, loss(
                        lossId(), 2, 4, 3, 1, BATCH_B, "retry_compaction", 3000)),
                        "range_mismatch"),
                structural(builder -> builder.losses.set(0, loss(
                        lossId(), 3, 3, 1, 1, BATCH_B, "retry_compaction", 3000)),
                        "sequence_hole")));
    }

    @Test
    public void storedMetadataDigestBitFlipPoisonsBeforeLeaseDigestCall() {
        SnapshotBuilder builder = valid();
        ReadReceiptStartupValidation.BatchRecord record = builder.batches.get(0);
        builder.batches.set(0, batchWithDigest(record, bitFlip(record.metadataDigest)));
        FakeStore store = new FakeStore(builder.build());

        assertPoison(store, "digest_mismatch", 0);
    }

    @Test
    public void storedLossDigestBitFlipPoisonsBeforeLeaseDigestCall() {
        SnapshotBuilder builder = valid();
        ReadReceiptStartupValidation.LossRecord record = builder.losses.get(0);
        builder.losses.set(0, lossWithDigest(record, bitFlip(record.contentDigest)));
        FakeStore store = new FakeStore(builder.build());

        assertPoison(store, "digest_mismatch", 0);
    }

    @Test
    public void counterDigestMismatchUsesFrozenValuesWhileNewerLiveCountersRemainPending() {
        for (int index = 0; index < 6; index++) {
            SnapshotBuilder mismatch = valid();
            long[] frozen = {1, 1, 1, 1, 1, 1};
            frozen[index] = 2;
            mismatch.leaseCounters = counters(frozen);
            assertPoison(mismatch, "digest_mismatch");
        }

        SnapshotBuilder replay = valid();
        replay.stateCounters = counters(101, 102, 103, 104, 105, 106);
        FakeStore store = new FakeStore(replay.build());
        assertEquals(ReadReceiptStartupValidation.Outcome.READY,
                validate(store, new SideEffect(store)).outcome);
        assertArrayEquals(new long[]{1, 1, 1, 1, 1, 1},
                store.lastProjection.counters.toArray());
        assertArrayEquals(replay.stateCounters.toArray(), store.durable.state.counters.toArray());
    }

    @Test
    public void everyFrozenCounterRegressionPoisonsBeforeDigestReproduction() {
        for (int index = 0; index < 6; index++) {
            SnapshotBuilder builder = valid();
            long[] live = {10, 10, 10, 10, 10, 10};
            long[] frozen = {1, 1, 1, 1, 1, 1};
            frozen[index] = 11;
            builder.stateCounters = counters(live);
            builder.leaseCounters = counters(frozen);
            FakeStore store = new FakeStore(builder.build());

            assertEquals("counter_regression",
                    validate(store, new SideEffect(store)).category);
            assertEquals(0, store.digestCalls);
        }
    }

    @Test
    public void foreignKeyAckedResidueBrokenReferenceAndMonotonicityPoison() {
        SnapshotBuilder foreignKey = valid();
        foreignKey.foreignKeyViolations = 1;
        assertPoison(foreignKey, "batch_conflict");

        SnapshotBuilder acked = valid();
        acked.lastAcked = 1;
        acked.events.add(event(1, OTHER, BATCH_A, 1, 1, 1));
        assertPoison(acked, "stream_rollback");

        SnapshotBuilder reference = valid();
        reference.events.set(0, event(1, eventId(1), OTHER, 10, 20, 30));
        assertPoison(reference, "batch_conflict");

        SnapshotBuilder monotonic = valid();
        monotonic.nextSequence = 4;
        monotonic.highestIssued = 4;
        assertPoison(monotonic, "sequence_hole");
    }

    @Test
    public void poisonCommitFailureNeverActivates() {
        SnapshotBuilder invalid = valid();
        invalid.events.remove(1);
        FakeStore store = new FakeStore(invalid.build());
        store.failCommit = true;
        assertStorageUnavailable(store, new SideEffect(store));
        assertEquals(ReadReceiptStartupValidation.Status.ACTIVE, store.durable.state.status);
    }

    @Test
    public void partialActivationFailureCleansUpAndReturnsBoundedNonreadyResult() {
        FakeStore store = new FakeStore(valid().build());
        SideEffect sideEffect = new SideEffect(store);
        sideEffect.failDuringActivation = true;

        ReadReceiptStartupValidation.Result result = validate(store, sideEffect);

        assertEquals(ReadReceiptStartupValidation.Outcome.ACTIVATION_UNAVAILABLE, result.outcome);
        assertEquals("activation_failure", result.category);
        assertFalse(result.bindPermitted);
        assertEquals(1, store.commits);
        assertEquals(1, sideEffect.starts);
        assertEquals(1, sideEffect.cleanups);
        assertFalse(sideEffect.partiallyActive);
    }

    @Test
    public void uncertainCountersNeverCreateSequenceCoverage() {
        SnapshotBuilder builder = valid();
        builder.stateCounters = counters(0, 0, 99, 88, 0, 0);
        builder.events.remove(1);
        assertPoison(builder, "sequence_hole");
    }

    private static void assertDigestMutations(List<Consumer<SnapshotBuilder>> mutations) {
        for (Consumer<SnapshotBuilder> mutation : mutations) {
            SnapshotBuilder builder = valid();
            mutation.accept(builder);
            assertPoison(new FakeStore(builder.build()), "digest_mismatch", 1);
        }
    }

    private static void assertPreLeaseDigestMutations(
            List<Consumer<SnapshotBuilder>> mutations
    ) {
        for (Consumer<SnapshotBuilder> mutation : mutations) {
            SnapshotBuilder builder = valid();
            mutation.accept(builder);
            assertPoison(new FakeStore(builder.build()), "digest_mismatch", 0);
        }
    }

    private static void assertStructuralMutations(List<StructuralMutation> mutations) {
        for (StructuralMutation mutation : mutations) {
            SnapshotBuilder builder = valid();
            mutation.change.accept(builder);
            assertPoison(new FakeStore(builder.build()), mutation.category, 0);
        }
    }

    private static StructuralMutation structural(
            Consumer<SnapshotBuilder> change,
            String category
    ) {
        return new StructuralMutation(change, category);
    }

    private static void assertPoison(SnapshotBuilder builder, String category) {
        FakeStore store = new FakeStore(builder.build());
        SideEffect sideEffect = new SideEffect(store);
        ReadReceiptStartupValidation.Result result = validate(store, sideEffect);
        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED, result.outcome);
        assertEquals(category, result.category);
        assertFalse(result.bindPermitted);
        assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.state.status);
        if (store.durable.lease != null) {
            assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.lease.status);
            assertEquals(category, store.durable.lease.poisonCategory);
        }
        assertEquals(1, store.commits);
        assertEquals(0, sideEffect.prepares);
    }

    private static void assertPoison(FakeStore store, String category, int digestCalls) {
        SideEffect sideEffect = new SideEffect(store);
        ReadReceiptStartupValidation.Result result = validate(store, sideEffect);
        assertEquals(ReadReceiptStartupValidation.Outcome.POISONED, result.outcome);
        assertEquals(category, result.category);
        assertEquals(digestCalls, store.digestCalls);
        assertEquals(ReadReceiptStartupValidation.Status.POISONED, store.durable.state.status);
        assertEquals(0, sideEffect.prepares);
    }

    private static void assertStorageUnavailable(SnapshotBuilder builder,
                                                 int poisonCalls, int commits) {
        FakeStore store = new FakeStore(builder.build());
        ReadReceiptStartupValidation.Result result = validate(store, new SideEffect(store));
        assertEquals(ReadReceiptStartupValidation.Outcome.STORAGE_UNAVAILABLE, result.outcome);
        assertFalse(result.bindPermitted);
        assertEquals(poisonCalls, store.poisonCalls);
        assertEquals(commits, store.commits);
    }

    private static void assertStorageUnavailable(FakeStore store, SideEffect sideEffect) {
        ReadReceiptStartupValidation.Result result = validate(store, sideEffect);
        assertEquals(ReadReceiptStartupValidation.Outcome.STORAGE_UNAVAILABLE, result.outcome);
        assertEquals("storage_failure", result.category);
        assertFalse(result.bindPermitted);
        assertEquals(0, store.commits);
        assertEquals(0, sideEffect.prepares);
    }

    private static ReadReceiptStartupValidation.Result validate(FakeStore store,
                                                                 SideEffect sideEffect) {
        return new ReadReceiptStartupValidation(store, sideEffect).validateAndStart();
    }

    private static SnapshotBuilder valid() {
        return new SnapshotBuilder();
    }

    private static SnapshotBuilder sequenceEvents(int count) {
        SnapshotBuilder builder = valid();
        builder.highestIssued = count;
        builder.nextSequence = count + 1L;
        builder.leaseLast = count;
        builder.batches.clear();
        builder.batches.add(batch(BATCH_A, 1, count, count, 1, 2, SESSION, BOOT, SOURCE));
        builder.events.clear();
        builder.losses.clear();
        for (int sequence = 1; sequence <= count; sequence++) {
            builder.events.add(event(sequence, uuidFor(sequence + 1000), BATCH_A, 1, 2, 3));
        }
        return builder;
    }

    private static SnapshotBuilder sequenceLosses(int count) {
        SnapshotBuilder builder = valid();
        builder.highestIssued = count;
        builder.nextSequence = count + 1L;
        builder.leaseLast = count;
        builder.batches.clear();
        builder.events.clear();
        builder.losses.clear();
        for (int sequence = 1; sequence <= count; sequence++) {
            builder.losses.add(loss(uuidFor(sequence + 2000), sequence, sequence,
                    1, 0, null, "bounded_prune", sequence));
        }
        return builder;
    }

    private static ReadReceiptStartupValidation.Counters counters(long... values) {
        return new ReadReceiptStartupValidation.Counters(values);
    }

    private static ReadReceiptStartupValidation.BatchRecord batch(
            UUID batchId, long first, long last, long count, long at, long elapsed,
            UUID session, UUID boot, UUID source) {
        byte[] metadataDigest;
        try {
            metadataDigest = ReadReceiptProtocolV2.metadataDigest(
                    new ReadReceiptProtocolV2.Metadata(batchId, first, last, count,
                            at, elapsed, session, boot, source));
        } catch (IllegalArgumentException exception) {
            metadataDigest = new byte[32];
        }
        return new ReadReceiptStartupValidation.BatchRecord(batchId, first, last, count,
                at, elapsed, session, boot, source, metadataDigest);
    }

    private static ReadReceiptStartupValidation.BatchRecord batchWithDigest(
            ReadReceiptStartupValidation.BatchRecord record,
            byte[] metadataDigest
    ) {
        return new ReadReceiptStartupValidation.BatchRecord(
                record.batchId, record.firstSequence, record.lastSequence, record.eventCount,
                record.persistedAtMs, record.persistedElapsedMs, record.captureSessionId,
                record.captureBootId, record.sourceEpochToken, metadataDigest);
    }

    private static ReadReceiptStartupValidation.EventRecord event(
            long sequence, UUID eventId, UUID batchId, long chatId, long userId, long watermark) {
        return new ReadReceiptStartupValidation.EventRecord(
                sequence, eventId, batchId, chatId, userId, watermark);
    }

    private static ReadReceiptStartupValidation.LossRecord loss(
            UUID receiptId, long first, long last, long eventCount, long batchCount,
            UUID batchId, String reason, long createdAt) {
        return loss(receiptId, first, last, eventCount, batchCount, batchId, reason,
                3000L, 4000L, SESSION, BOOT, SOURCE, createdAt);
    }

    private static ReadReceiptStartupValidation.LossRecord loss(
            UUID receiptId, long first, long last, long eventCount, long batchCount,
            UUID batchId, String reason, Long at, Long elapsed, UUID session,
            UUID boot, UUID source, long createdAt
    ) {
        byte[] contentDigest;
        try {
            contentDigest = ReadReceiptProtocolV2.lossDigest(new ReadReceiptProtocolV2.Loss(
                    receiptId, first, last, eventCount, batchCount, batchId, reason,
                    at, elapsed, session, boot, source, createdAt));
        } catch (IllegalArgumentException exception) {
            contentDigest = new byte[32];
        }
        return new ReadReceiptStartupValidation.LossRecord(receiptId, first, last,
                eventCount, batchCount, batchId, reason, at, elapsed,
                session, boot, source, createdAt, contentDigest);
    }

    private static ReadReceiptStartupValidation.LossRecord lossWithOptionals(
            Long at, Long elapsed, UUID session, UUID boot, UUID source) {
        return loss(lossId(), 3, 4, 2, 1, BATCH_B, "retry_compaction",
                at, elapsed, session, boot, source, 3000);
    }

    private static ReadReceiptStartupValidation.LossRecord lossWithDigest(
            ReadReceiptStartupValidation.LossRecord record,
            byte[] contentDigest
    ) {
        return new ReadReceiptStartupValidation.LossRecord(
                record.lossReceiptId, record.firstSequence, record.lastSequence,
                record.droppedEventCount, record.droppedBatchCount, record.batchId,
                record.reason, record.persistedAtMs, record.persistedElapsedMs,
                record.captureSessionId, record.captureBootId, record.sourceEpochToken,
                record.createdAtMs, contentDigest);
    }

    private static UUID eventId(int sequence) {
        return uuid(String.format("66666666-6666-4666-8666-%012d", sequence));
    }

    private static UUID lossId() {
        return uuid("99999999-9999-4999-8999-999999999999");
    }

    private static UUID uuidFor(int value) {
        return uuid(String.format("77777777-7777-4777-8777-%012d", value));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static byte[] canonicalDigest(SnapshotBuilder builder) {
        return projectionDigest(builder, builder.batches, builder.events, builder.losses);
    }

    private static byte[] projectionDigest(
            SnapshotBuilder builder,
            List<ReadReceiptStartupValidation.BatchRecord> batches,
            List<ReadReceiptStartupValidation.EventRecord> events,
            List<ReadReceiptStartupValidation.LossRecord> losses
    ) {
        ReadReceiptProtocolV2.Lease lease = new ReadReceiptProtocolV2.Lease(
                builder.installationId, builder.streamEpoch, builder.leaseId,
                builder.leaseFirst, builder.leaseLast, builder.leasePrevious,
                builder.leaseCounters.toProtocolCounters(),
                toProtocolBatches(batches), toProtocolEvents(events),
                toProtocolLosses(losses));
        return ReadReceiptProtocolV2.leaseDigest(lease);
    }

    private static byte[] bitFlip(byte[] digest) {
        byte[] changed = digest.clone();
        changed[0] ^= 1;
        return changed;
    }

    private static List<ReadReceiptProtocolV2.Metadata> toProtocolBatches(
            List<ReadReceiptStartupValidation.BatchRecord> records) {
        List<ReadReceiptProtocolV2.Metadata> values = new ArrayList<>();
        for (ReadReceiptStartupValidation.BatchRecord record : records) values.add(record.toProtocol());
        return values;
    }

    private static List<ReadReceiptProtocolV2.Event> toProtocolEvents(
            List<ReadReceiptStartupValidation.EventRecord> records) {
        List<ReadReceiptProtocolV2.Event> values = new ArrayList<>();
        for (ReadReceiptStartupValidation.EventRecord record : records) values.add(record.toProtocol());
        return values;
    }

    private static List<ReadReceiptProtocolV2.Loss> toProtocolLosses(
            List<ReadReceiptStartupValidation.LossRecord> records) {
        List<ReadReceiptProtocolV2.Loss> values = new ArrayList<>();
        for (ReadReceiptStartupValidation.LossRecord record : records) values.add(record.toProtocol());
        return values;
    }

    private static final class StructuralMutation {
        private final Consumer<SnapshotBuilder> change;
        private final String category;

        private StructuralMutation(Consumer<SnapshotBuilder> change, String category) {
            this.change = change;
            this.category = category;
        }
    }

    private static final class SnapshotBuilder {
        int foreignKeyViolations;
        int streamSingletonCount = 1;
        int leaseSingletonCount = 1;
        boolean includeState = true;
        boolean includeLease = true;
        UUID installationId = INSTALLATION;
        UUID streamEpoch = EPOCH;
        UUID leaseStreamEpoch = EPOCH;
        UUID leaseId = LEASE;
        long lastAcked;
        long highestIssued = 4;
        long nextSequence = 5;
        long leaseFirst = 1;
        long leaseLast = 4;
        long leasePrevious;
        ReadReceiptStartupValidation.Status streamStatus = ReadReceiptStartupValidation.Status.ACTIVE;
        String streamPoisonCategory;
        ReadReceiptStartupValidation.Status leaseStatus = ReadReceiptStartupValidation.Status.ACTIVE;
        String leasePoisonCategory;
        ReadReceiptStartupValidation.Counters stateCounters = counters(6, 6, 6, 6, 6, 6);
        ReadReceiptStartupValidation.Counters leaseCounters = counters(1, 1, 1, 1, 1, 1);
        byte[] leaseDigest = BASE_DIGEST;
        final List<ReadReceiptStartupValidation.BatchRecord> batches = new ArrayList<>(Arrays.asList(
                batch(BATCH_A, 1, 2, 2, 1000, 2000, SESSION, BOOT, SOURCE),
                batch(BATCH_B, 3, 4, 2, 1001, 2001, SESSION, BOOT, SOURCE)));
        final List<ReadReceiptStartupValidation.EventRecord> events = new ArrayList<>(Arrays.asList(
                event(1, eventId(1), BATCH_A, 10, 20, 30),
                event(2, eventId(2), BATCH_A, 11, 21, 31)));
        final List<ReadReceiptStartupValidation.LossRecord> losses = new ArrayList<>(Arrays.asList(
                loss(lossId(), 3, 4, 2, 1, BATCH_B, "retry_compaction", 3000)));

        ReadReceiptStartupValidation.Snapshot build() {
            ReadReceiptStartupValidation.StreamState state = includeState
                    ? new ReadReceiptStartupValidation.StreamState(installationId, streamEpoch,
                    lastAcked, highestIssued, nextSequence, stateCounters,
                    streamStatus, streamPoisonCategory) : null;
            ReadReceiptStartupValidation.ActiveLease lease = includeLease
                    ? new ReadReceiptStartupValidation.ActiveLease(leaseStreamEpoch, leaseId,
                    leaseFirst, leaseLast, leasePrevious, leaseCounters, leaseDigest,
                    leaseStatus, leasePoisonCategory) : null;
            return new ReadReceiptStartupValidation.Snapshot(foreignKeyViolations,
                    streamSingletonCount, leaseSingletonCount, state,
                    batches, events, losses, lease);
        }
    }

    private static final class SideEffect implements ReadReceiptStartupValidation.StartSideEffect {
        private final FakeStore store;
        int prepares;
        int starts;
        int cleanups;
        boolean partiallyActive;
        boolean sawCommittedTransaction;
        boolean failDuringActivation;

        SideEffect(FakeStore store) {
            this.store = store;
        }

        @Override
        public ReadReceiptStartupValidation.Activation prepare() {
            prepares++;
            sawCommittedTransaction = !store.transactionOpen && store.commits > 0;
            return new ReadReceiptStartupValidation.Activation() {
                @Override
                public void start() {
                    starts++;
                    partiallyActive = true;
                    if (failDuringActivation) throw new IllegalStateException("activation failed");
                }

                @Override
                public void close() {
                    cleanups++;
                    partiallyActive = false;
                }
            };
        }
    }

    private static final class FakeStore implements ReadReceiptStartupValidation.Store {
        ReadReceiptStartupValidation.Snapshot durable;
        boolean transactionOpen;
        boolean failCommit;
        boolean skipPoisonMutation;
        int poisonStreamRows = 1;
        int poisonLeaseRows = 1;
        int poisonCalls;
        int digestCalls;
        int commits;
        boolean digestUsedCurrentSnapshot;
        ReadReceiptStartupValidation.LeaseProjection lastProjection;

        FakeStore(ReadReceiptStartupValidation.Snapshot durable) {
            this.durable = durable;
            if (durable.leaseSingletonCount == 0) poisonLeaseRows = 0;
        }

        @Override
        public ReadReceiptStartupValidation.Transaction beginImmediate() {
            if (transactionOpen) throw new IllegalStateException("transaction already open");
            transactionOpen = true;
            return new Tx(durable);
        }

        private final class Tx implements ReadReceiptStartupValidation.Transaction {
            private ReadReceiptStartupValidation.Snapshot working;
            private boolean done;

            Tx(ReadReceiptStartupValidation.Snapshot working) {
                this.working = working;
            }

            @Override
            public ReadReceiptStartupValidation.Snapshot readSnapshot() {
                return working;
            }

            @Override
            public byte[] reproduceLeaseDigest(ReadReceiptStartupValidation.LeaseProjection projection) {
                digestCalls++;
                lastProjection = projection;
                digestUsedCurrentSnapshot = projection.belongsTo(working);
                if (!digestUsedCurrentSnapshot) throw new ReadReceiptStartupValidation.StorageException();
                return ReadReceiptProtocolV2.leaseDigest(projection.toProtocolLease());
            }

            @Override
            public ReadReceiptStartupValidation.AffectedRows poison(String category) {
                poisonCalls++;
                if (!skipPoisonMutation) working = working.poisoned(category);
                return new ReadReceiptStartupValidation.AffectedRows(
                        poisonStreamRows, poisonLeaseRows);
            }

            @Override
            public void commit() {
                if (failCommit) throw new ReadReceiptStartupValidation.StorageException();
                durable = working;
                commits++;
                done = true;
                transactionOpen = false;
            }

            @Override
            public void close() {
                if (!done) transactionOpen = false;
            }
        }
    }
}
