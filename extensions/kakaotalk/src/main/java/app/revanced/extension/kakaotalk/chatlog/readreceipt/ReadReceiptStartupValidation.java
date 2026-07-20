package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ReadReceiptStartupValidation {
    private static final int LEASE_LIST_LIMIT = 256;
    private static final String STORAGE_FAILURE = "storage_failure";
    private static final String ACTIVATION_FAILURE = "activation_failure";
    private static final Set<String> POISON_CATEGORIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "stream_poisoned", "sequence_hole", "range_mismatch", "lease_mismatch",
                    "digest_mismatch", "conflicting_replay", "batch_conflict",
                    "counter_regression", "stream_rollback", "unknown_epoch")));

    private final Store store;
    private final StartSideEffect startSideEffect;

    public ReadReceiptStartupValidation(Store store, StartSideEffect startSideEffect) {
        this.store = required(store);
        this.startSideEffect = required(startSideEffect);
    }

    public Result validateAndStart() {
        try (Transaction transaction = store.beginImmediate()) {
            Snapshot snapshot = transaction.readSnapshot();
            requirePoisonableShape(snapshot);
            String poisonCategory = validate(transaction, snapshot);
            if (poisonCategory != null) {
                AffectedRows affectedRows = transaction.poison(poisonCategory);
                if (affectedRows == null || affectedRows.streamRows != 1
                        || affectedRows.leaseRows != snapshot.leaseSingletonCount) {
                    throw new StorageException();
                }
                requirePoisonApplied(transaction.readSnapshot(), snapshot.leaseSingletonCount,
                        poisonCategory);
                transaction.commit();
                return Result.poisoned(poisonCategory);
            }
            transaction.commit();
        } catch (RuntimeException exception) {
            return Result.storageUnavailable();
        }
        return activate();
    }

    private Result activate() {
        Activation activation = null;
        try {
            activation = required(startSideEffect.prepare());
            activation.start();
            return Result.ready(activation);
        } catch (RuntimeException exception) {
            closeQuietly(activation);
            return Result.activationUnavailable();
        }
    }

    private static void requirePoisonableShape(Snapshot snapshot) {
        if (snapshot == null || snapshot.streamSingletonCount != 1 || snapshot.state == null
                || snapshot.leaseSingletonCount < 0 || snapshot.leaseSingletonCount > 1
                || (snapshot.leaseSingletonCount == 0) != (snapshot.lease == null)) {
            throw new StorageException();
        }
    }

    private static void requirePoisonApplied(Snapshot snapshot, int expectedLeaseRows,
                                             String category) {
        String bounded = boundedCategory(category);
        if (snapshot == null || snapshot.streamSingletonCount != 1 || snapshot.state == null
                || snapshot.state.status != Status.POISONED
                || !bounded.equals(snapshot.state.poisonCategory)
                || snapshot.leaseSingletonCount != expectedLeaseRows
                || (expectedLeaseRows == 0) != (snapshot.lease == null)
                || snapshot.lease != null && (snapshot.lease.status != Status.POISONED
                || !bounded.equals(snapshot.lease.poisonCategory))) {
            throw new StorageException();
        }
    }

    private String validate(Transaction transaction, Snapshot snapshot) {
        if (snapshot.state.status == Status.POISONED) {
            return boundedCategory(snapshot.state.poisonCategory);
        }
        if (snapshot.foreignKeyViolationCount != 0) return "batch_conflict";

        StreamState state = snapshot.state;
        if (state.status != Status.ACTIVE || state.poisonCategory != null
                || state.lastAckedSequence < 0 || state.highestIssuedSequence < 0
                || state.nextSequence <= 0
                || state.lastAckedSequence > state.highestIssuedSequence
                || state.highestIssuedSequence >= state.nextSequence) {
            return "sequence_hole";
        }

        String coverageFailure = transaction.validateBacklog(snapshot);
        if (coverageFailure != null) return coverageFailure;
        if (snapshot.lease == null) return null;

        ActiveLease lease = snapshot.lease;
        if (lease.status != Status.ACTIVE || lease.poisonCategory != null
                || !state.streamEpoch.equals(lease.streamEpoch)
                || lease.firstSequence != state.lastAckedSequence + 1
                || lease.firstSequence <= 0 || lease.firstSequence > lease.lastSequence
                || lease.lastSequence > state.highestIssuedSequence
                || lease.previousSequence != lease.firstSequence - 1) {
            return "lease_mismatch";
        }
        if (!state.counters.atLeast(lease.counters)) return "counter_regression";

        ProjectionResult projectionResult = projectLease(snapshot);
        if (projectionResult.failureCategory != null) return projectionResult.failureCategory;
        byte[] reproduced;
        try {
            if (!storedRecordDigestsMatch(projectionResult.projection)) {
                return "digest_mismatch";
            }
            reproduced = transaction.reproduceLeaseDigest(projectionResult.projection);
        } catch (IllegalArgumentException exception) {
            return "digest_mismatch";
        }
        if (lease.digest.length != 32 || reproduced == null || reproduced.length != 32
                || !MessageDigest.isEqual(lease.digest, reproduced)) {
            return "digest_mismatch";
        }
        return null;
    }

    private static ProjectionResult projectLease(Snapshot snapshot) {
        ActiveLease lease = snapshot.lease;
        List<EventRecord> events = new ArrayList<>();
        for (EventRecord event : snapshot.events) {
            if (event.sequence >= lease.firstSequence && event.sequence <= lease.lastSequence) {
                events.add(event);
            }
        }
        List<LossRecord> losses = new ArrayList<>();
        for (LossRecord loss : snapshot.losses) {
            boolean intersects = loss.firstSequence <= lease.lastSequence
                    && loss.lastSequence >= lease.firstSequence;
            if (intersects && (loss.firstSequence < lease.firstSequence
                    || loss.lastSequence > lease.lastSequence)) {
                return ProjectionResult.failure("lease_mismatch");
            }
            if (intersects) losses.add(loss);
        }
        if (events.size() > LEASE_LIST_LIMIT || losses.size() > LEASE_LIST_LIMIT) {
            return ProjectionResult.failure("lease_mismatch");
        }
        String coverageFailure = validateCoverage(events, losses,
                lease.firstSequence - 1, lease.lastSequence);
        if (coverageFailure != null) return ProjectionResult.failure(coverageFailure);

        Set<UUID> referencedBatchIds = new HashSet<>();
        for (EventRecord event : events) referencedBatchIds.add(event.batchId);
        for (LossRecord loss : losses) {
            if (loss.batchId != null) referencedBatchIds.add(loss.batchId);
        }
        Map<UUID, BatchRecord> batchesById = new HashMap<>();
        for (BatchRecord batch : snapshot.batches) {
            if (batch.batchId == null || batchesById.put(batch.batchId, batch) != null) {
                return ProjectionResult.failure("batch_conflict");
            }
        }
        List<BatchRecord> batches = new ArrayList<>();
        for (UUID batchId : referencedBatchIds) {
            BatchRecord batch = batchesById.get(batchId);
            if (batch == null) return ProjectionResult.failure("batch_conflict");
            batches.add(batch);
        }
        if (batches.size() > LEASE_LIST_LIMIT) {
            return ProjectionResult.failure("lease_mismatch");
        }

        batches.sort(Comparator.comparingLong((BatchRecord value) -> value.firstSequence)
                .thenComparing(value -> value.batchId, ReadReceiptStartupValidation::compareUuid));
        events.sort(Comparator.comparingLong(value -> value.sequence));
        losses.sort(Comparator.comparingLong(value -> value.firstSequence));
        return ProjectionResult.success(new LeaseProjection(snapshot, snapshot.state.installationId,
                lease.streamEpoch, lease.leaseId, lease.firstSequence, lease.lastSequence,
                lease.previousSequence, lease.counters, batches, events, losses));
    }

    private static boolean storedRecordDigestsMatch(LeaseProjection projection) {
        for (BatchRecord batch : projection.batches) {
            byte[] reproduced = ReadReceiptProtocolV2.metadataDigest(batch.toProtocol());
            if (!exactDigest(batch.metadataDigest, reproduced)) {
                return false;
            }
        }
        for (LossRecord loss : projection.losses) {
            byte[] reproduced = ReadReceiptProtocolV2.lossDigest(loss.toProtocol());
            if (!exactDigest(loss.contentDigest, reproduced)) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactDigest(byte[] stored, byte[] reproduced) {
        boolean equal = MessageDigest.isEqual(stored, reproduced);
        return stored.length == 32 && reproduced.length == 32 && equal;
    }

    private static String validateCoverage(List<EventRecord> events, List<LossRecord> losses,
                                           long lastAcked, long highestIssued) {
        List<Interval> coverage = new ArrayList<>(events.size() + losses.size());
        for (EventRecord event : events) coverage.add(new Interval(event.sequence, event.sequence));
        for (LossRecord loss : losses) {
            if (loss.firstSequence <= 0 || loss.firstSequence > loss.lastSequence) {
                return "range_mismatch";
            }
            coverage.add(new Interval(loss.firstSequence, loss.lastSequence));
        }
        coverage.sort(Comparator.comparingLong((Interval value) -> value.first)
                .thenComparingLong(value -> value.last));

        long expected = lastAcked + 1;
        for (Interval interval : coverage) {
            if (interval.first > highestIssued || interval.last > highestIssued) {
                return "range_mismatch";
            }
            if (interval.first > expected) return "sequence_hole";
            if (interval.first < expected) return "range_mismatch";
            expected = interval.last + 1;
        }
        return expected == highestIssued + 1 ? null : "sequence_hole";
    }

    private static int compareUuid(UUID left, UUID right) {
        int most = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return most != 0 ? most
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private static String boundedCategory(String category) {
        return POISON_CATEGORIES.contains(category) ? category : "stream_poisoned";
    }

    private static void closeQuietly(Activation activation) {
        if (activation == null) return;
        try {
            activation.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalArgumentException("required value missing");
        return value;
    }

    public interface Store {
        Transaction beginImmediate();
    }

    public interface Transaction extends AutoCloseable {
        Snapshot readSnapshot();

        default String validateBacklog(Snapshot snapshot) {
            requirePoisonableShape(snapshot);
            StreamState state = snapshot.state;
            Map<UUID, BatchRecord> batches = new HashMap<>();
            for (BatchRecord batch : snapshot.batches) {
                if (batch.batchId == null || batches.put(batch.batchId, batch) != null) {
                    return "batch_conflict";
                }
            }
            for (EventRecord event : snapshot.events) {
                if (event.sequence <= state.lastAckedSequence) return "stream_rollback";
                if (event.batchId == null || !batches.containsKey(event.batchId)) {
                    return "batch_conflict";
                }
            }
            for (LossRecord loss : snapshot.losses) {
                if (loss.firstSequence <= state.lastAckedSequence) return "stream_rollback";
                if (loss.batchId != null && !batches.containsKey(loss.batchId)) {
                    return "batch_conflict";
                }
            }
            return validateCoverage(snapshot.events, snapshot.losses,
                    state.lastAckedSequence, state.highestIssuedSequence);
        }

        byte[] reproduceLeaseDigest(LeaseProjection projection);

        AffectedRows poison(String category);

        void commit();

        @Override
        void close();
    }

    public interface StartSideEffect {
        Activation prepare();
    }

    public interface Activation extends AutoCloseable {
        void start();

        @Override
        void close();
    }

    public enum Status {
        ACTIVE,
        POISONED
    }

    public enum Outcome {
        READY,
        POISONED,
        STORAGE_UNAVAILABLE,
        ACTIVATION_UNAVAILABLE
    }

    public static final class Result implements AutoCloseable {
        public final Outcome outcome;
        public final String category;
        public final boolean bindPermitted;
        public final Activation activation;

        private Result(Outcome outcome, String category, boolean bindPermitted,
                       Activation activation) {
            this.outcome = outcome;
            this.category = category;
            this.bindPermitted = bindPermitted;
            this.activation = activation;
        }

        static Result ready(Activation activation) {
            return new Result(Outcome.READY, null, true, activation);
        }

        static Result poisoned(String category) {
            return new Result(Outcome.POISONED, boundedCategory(category), false, null);
        }

        static Result storageUnavailable() {
            return new Result(Outcome.STORAGE_UNAVAILABLE, STORAGE_FAILURE, false, null);
        }

        static Result activationUnavailable() {
            return new Result(Outcome.ACTIVATION_UNAVAILABLE, ACTIVATION_FAILURE, false, null);
        }

        @Override
        public void close() {
            closeQuietly(activation);
        }
    }

    public static final class AffectedRows {
        public final int streamRows;
        public final int leaseRows;

        public AffectedRows(int streamRows, int leaseRows) {
            this.streamRows = streamRows;
            this.leaseRows = leaseRows;
        }
    }

    public static final class Counters {
        private final long[] values;

        public Counters(long... values) {
            if (values == null || values.length != 6) {
                throw new IllegalArgumentException("exactly six counters required");
            }
            this.values = values.clone();
            for (long value : this.values) {
                if (value < 0) throw new IllegalArgumentException("negative counter");
            }
        }

        boolean atLeast(Counters other) {
            for (int index = 0; index < values.length; index++) {
                if (values[index] < other.values[index]) return false;
            }
            return true;
        }

        public long[] toArray() {
            return values.clone();
        }

        public ReadReceiptProtocolV2.Counters toProtocolCounters() {
            return new ReadReceiptProtocolV2.Counters(values[0], values[1], values[2],
                    values[3], values[4], values[5]);
        }
    }

    public static final class StreamState {
        public final UUID installationId;
        public final UUID streamEpoch;
        public final long lastAckedSequence;
        public final long highestIssuedSequence;
        public final long nextSequence;
        public final Counters counters;
        public final Status status;
        public final String poisonCategory;

        public StreamState(UUID installationId, UUID streamEpoch, long lastAckedSequence,
                           long highestIssuedSequence, long nextSequence, Counters counters,
                           Status status, String poisonCategory) {
            this.installationId = required(installationId);
            this.streamEpoch = required(streamEpoch);
            this.lastAckedSequence = lastAckedSequence;
            this.highestIssuedSequence = highestIssuedSequence;
            this.nextSequence = nextSequence;
            this.counters = required(counters);
            this.status = required(status);
            this.poisonCategory = poisonCategory;
        }

        StreamState poisoned(String category) {
            return new StreamState(installationId, streamEpoch, lastAckedSequence,
                    highestIssuedSequence, nextSequence, counters,
                    Status.POISONED, boundedCategory(category));
        }
    }

    public static final class ActiveLease {
        public final UUID streamEpoch;
        public final UUID leaseId;
        public final long firstSequence;
        public final long lastSequence;
        public final long previousSequence;
        public final Counters counters;
        public final byte[] digest;
        public final Status status;
        public final String poisonCategory;

        public ActiveLease(UUID streamEpoch, UUID leaseId, long firstSequence, long lastSequence,
                           long previousSequence, Counters counters, byte[] digest, Status status,
                           String poisonCategory) {
            this.streamEpoch = required(streamEpoch);
            this.leaseId = required(leaseId);
            this.firstSequence = firstSequence;
            this.lastSequence = lastSequence;
            this.previousSequence = previousSequence;
            this.counters = required(counters);
            this.digest = required(digest).clone();
            this.status = required(status);
            this.poisonCategory = poisonCategory;
        }

        ActiveLease poisoned(String category) {
            return new ActiveLease(streamEpoch, leaseId, firstSequence, lastSequence,
                    previousSequence, counters, digest, Status.POISONED,
                    boundedCategory(category));
        }
    }

    public static final class BatchRecord {
        public final UUID batchId;
        public final long firstSequence;
        public final long lastSequence;
        public final long eventCount;
        public final long persistedAtMs;
        public final long persistedElapsedMs;
        public final UUID captureSessionId;
        public final UUID captureBootId;
        public final UUID sourceEpochToken;
        public final byte[] metadataDigest;

        public BatchRecord(UUID batchId, long firstSequence, long lastSequence, long eventCount,
                           long persistedAtMs, long persistedElapsedMs, UUID captureSessionId,
                           UUID captureBootId, UUID sourceEpochToken, byte[] metadataDigest) {
            this.batchId = batchId;
            this.firstSequence = firstSequence;
            this.lastSequence = lastSequence;
            this.eventCount = eventCount;
            this.persistedAtMs = persistedAtMs;
            this.persistedElapsedMs = persistedElapsedMs;
            this.captureSessionId = captureSessionId;
            this.captureBootId = captureBootId;
            this.sourceEpochToken = sourceEpochToken;
            this.metadataDigest = required(metadataDigest).clone();
        }

        public ReadReceiptProtocolV2.Metadata toProtocol() {
            return new ReadReceiptProtocolV2.Metadata(batchId, firstSequence, lastSequence,
                    eventCount, persistedAtMs, persistedElapsedMs, captureSessionId,
                    captureBootId, sourceEpochToken);
        }
    }

    public static final class EventRecord {
        public final long sequence;
        public final UUID eventId;
        public final UUID batchId;
        public final long chatId;
        public final long userId;
        public final long watermark;

        public EventRecord(long sequence, UUID eventId, UUID batchId, long chatId,
                           long userId, long watermark) {
            this.sequence = sequence;
            this.eventId = eventId;
            this.batchId = batchId;
            this.chatId = chatId;
            this.userId = userId;
            this.watermark = watermark;
        }

        public ReadReceiptProtocolV2.Event toProtocol() {
            return new ReadReceiptProtocolV2.Event(
                    sequence, eventId, batchId, chatId, userId, watermark);
        }
    }

    public static final class LossRecord {
        public final UUID lossReceiptId;
        public final long firstSequence;
        public final long lastSequence;
        public final long droppedEventCount;
        public final long droppedBatchCount;
        public final UUID batchId;
        public final String reason;
        public final Long persistedAtMs;
        public final Long persistedElapsedMs;
        public final UUID captureSessionId;
        public final UUID captureBootId;
        public final UUID sourceEpochToken;
        public final long createdAtMs;
        public final byte[] contentDigest;

        public LossRecord(UUID lossReceiptId, long firstSequence, long lastSequence,
                          long droppedEventCount, long droppedBatchCount, UUID batchId,
                          String reason, Long persistedAtMs, Long persistedElapsedMs,
                          UUID captureSessionId, UUID captureBootId, UUID sourceEpochToken,
                          long createdAtMs, byte[] contentDigest) {
            this.lossReceiptId = lossReceiptId;
            this.firstSequence = firstSequence;
            this.lastSequence = lastSequence;
            this.droppedEventCount = droppedEventCount;
            this.droppedBatchCount = droppedBatchCount;
            this.batchId = batchId;
            this.reason = reason;
            this.persistedAtMs = persistedAtMs;
            this.persistedElapsedMs = persistedElapsedMs;
            this.captureSessionId = captureSessionId;
            this.captureBootId = captureBootId;
            this.sourceEpochToken = sourceEpochToken;
            this.createdAtMs = createdAtMs;
            this.contentDigest = required(contentDigest).clone();
        }

        public ReadReceiptProtocolV2.Loss toProtocol() {
            return new ReadReceiptProtocolV2.Loss(lossReceiptId, firstSequence, lastSequence,
                    droppedEventCount, droppedBatchCount, batchId, reason, persistedAtMs,
                    persistedElapsedMs, captureSessionId, captureBootId, sourceEpochToken,
                    createdAtMs);
        }
    }

    public static final class LeaseProjection {
        private final Snapshot source;
        public final UUID installationId;
        public final UUID streamEpoch;
        public final UUID leaseId;
        public final long firstSequence;
        public final long lastSequence;
        public final long previousSequence;
        public final Counters counters;
        public final List<BatchRecord> batches;
        public final List<EventRecord> events;
        public final List<LossRecord> losses;

        private LeaseProjection(Snapshot source, UUID installationId, UUID streamEpoch,
                                UUID leaseId, long firstSequence, long lastSequence,
                                long previousSequence, Counters counters, List<BatchRecord> batches,
                                List<EventRecord> events, List<LossRecord> losses) {
            this.source = source;
            this.installationId = installationId;
            this.streamEpoch = streamEpoch;
            this.leaseId = leaseId;
            this.firstSequence = firstSequence;
            this.lastSequence = lastSequence;
            this.previousSequence = previousSequence;
            this.counters = counters;
            this.batches = immutableCopy(batches);
            this.events = immutableCopy(events);
            this.losses = immutableCopy(losses);
        }

        boolean belongsTo(Snapshot snapshot) {
            return source == snapshot;
        }

        public ReadReceiptProtocolV2.Lease toProtocolLease() {
            List<ReadReceiptProtocolV2.Metadata> metadata = new ArrayList<>();
            for (BatchRecord batch : batches) metadata.add(batch.toProtocol());
            List<ReadReceiptProtocolV2.Event> protocolEvents = new ArrayList<>();
            for (EventRecord event : events) protocolEvents.add(event.toProtocol());
            List<ReadReceiptProtocolV2.Loss> protocolLosses = new ArrayList<>();
            for (LossRecord loss : losses) protocolLosses.add(loss.toProtocol());
            return new ReadReceiptProtocolV2.Lease(installationId, streamEpoch, leaseId,
                    firstSequence, lastSequence, previousSequence,
                    counters.toProtocolCounters(), metadata, protocolEvents, protocolLosses);
        }
    }

    public static final class Snapshot {
        public final int foreignKeyViolationCount;
        public final int streamSingletonCount;
        public final int leaseSingletonCount;
        public final StreamState state;
        public final List<BatchRecord> batches;
        public final List<EventRecord> events;
        public final List<LossRecord> losses;
        public final ActiveLease lease;

        public Snapshot(int foreignKeyViolationCount, int streamSingletonCount,
                        int leaseSingletonCount, StreamState state, List<BatchRecord> batches,
                        List<EventRecord> events, List<LossRecord> losses, ActiveLease lease) {
            this.foreignKeyViolationCount = foreignKeyViolationCount;
            this.streamSingletonCount = streamSingletonCount;
            this.leaseSingletonCount = leaseSingletonCount;
            this.state = state;
            this.batches = immutableCopy(batches);
            this.events = immutableCopy(events);
            this.losses = immutableCopy(losses);
            this.lease = lease;
        }

        public Snapshot poisoned(String category) {
            return new Snapshot(foreignKeyViolationCount, streamSingletonCount,
                    leaseSingletonCount, state == null ? null : state.poisoned(category),
                    batches, events, losses,
                    lease == null ? null : lease.poisoned(category));
        }
    }

    public static final class StorageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StorageException() {
            super("storage operation failed");
        }
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null) throw new IllegalArgumentException("required list missing");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class ProjectionResult {
        final LeaseProjection projection;
        final String failureCategory;

        private ProjectionResult(LeaseProjection projection, String failureCategory) {
            this.projection = projection;
            this.failureCategory = failureCategory;
        }

        static ProjectionResult success(LeaseProjection projection) {
            return new ProjectionResult(projection, null);
        }

        static ProjectionResult failure(String category) {
            return new ProjectionResult(null, category);
        }
    }

    private static final class Interval {
        final long first;
        final long last;

        Interval(long first, long last) {
            this.first = first;
            this.last = last;
        }
    }
}
