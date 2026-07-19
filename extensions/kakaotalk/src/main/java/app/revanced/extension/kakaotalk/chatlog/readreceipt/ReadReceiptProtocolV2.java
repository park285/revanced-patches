package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ReadReceiptProtocolV2 {
    public static final int CONTROL_FRAME_MAX = 16_384;
    public static final int STREAM_FRAME_MAX = 262_144;
    private static final String SOCKET_NAME_PREFIX = "iris.read-receipts.v2.";
    private static final byte[] SOCKET_NAME_DOMAIN =
            "read-receipt-socket-name-v2".getBytes(StandardCharsets.US_ASCII);
    private static final int LIST_MAX = 256;
    private static final Set<String> LOSS_REASONS = new HashSet<>(Arrays.asList(
            "bounded_prune", "retry_compaction", "oversized_batch",
            "precommit_memory_compaction", "operator_approved_rollback_gap"));
    private static final Set<String> NON_RETRYABLE_NACKS = new HashSet<>(Arrays.asList(
            "stream_poisoned", "sequence_hole", "range_mismatch", "lease_mismatch",
            "digest_mismatch", "conflicting_replay", "batch_conflict", "counter_regression",
            "stream_rollback", "unknown_epoch"));

    private ReadReceiptProtocolV2() {
    }

    public static final class Counters {
        public final long confirmedDroppedEventCount;
        public final long confirmedDroppedBatchCount;
        public final long uncertainOutcomeEventCount;
        public final long uncertainOutcomeBatchCount;
        public final long captureDropEventCount;
        public final long captureDropBatchCount;

        public Counters(long confirmedDroppedEventCount, long confirmedDroppedBatchCount,
                        long uncertainOutcomeEventCount, long uncertainOutcomeBatchCount,
                        long captureDropEventCount, long captureDropBatchCount) {
            this.confirmedDroppedEventCount = nonNegative(confirmedDroppedEventCount, "counter");
            this.confirmedDroppedBatchCount = nonNegative(confirmedDroppedBatchCount, "counter");
            this.uncertainOutcomeEventCount = nonNegative(uncertainOutcomeEventCount, "counter");
            this.uncertainOutcomeBatchCount = nonNegative(uncertainOutcomeBatchCount, "counter");
            this.captureDropEventCount = nonNegative(captureDropEventCount, "counter");
            this.captureDropBatchCount = nonNegative(captureDropBatchCount, "counter");
        }

        boolean atLeast(Counters other) {
            return confirmedDroppedEventCount >= other.confirmedDroppedEventCount
                    && confirmedDroppedBatchCount >= other.confirmedDroppedBatchCount
                    && uncertainOutcomeEventCount >= other.uncertainOutcomeEventCount
                    && uncertainOutcomeBatchCount >= other.uncertainOutcomeBatchCount
                    && captureDropEventCount >= other.captureDropEventCount
                    && captureDropBatchCount >= other.captureDropBatchCount;
        }

        boolean same(Counters other) {
            return confirmedDroppedEventCount == other.confirmedDroppedEventCount
                    && confirmedDroppedBatchCount == other.confirmedDroppedBatchCount
                    && uncertainOutcomeEventCount == other.uncertainOutcomeEventCount
                    && uncertainOutcomeBatchCount == other.uncertainOutcomeBatchCount
                    && captureDropEventCount == other.captureDropEventCount
                    && captureDropBatchCount == other.captureDropBatchCount;
        }
    }

    public static final class Metadata {
        public final UUID batchId;
        public final long firstSequence;
        public final long lastSequence;
        public final long eventCount;
        public final long persistedAtMs;
        public final long persistedElapsedMs;
        public final UUID captureSessionId;
        public final UUID captureBootId;
        public final UUID sourceEpochToken;

        public Metadata(UUID batchId, long firstSequence, long lastSequence, long eventCount,
                        long persistedAtMs, long persistedElapsedMs, UUID captureSessionId,
                        UUID captureBootId, UUID sourceEpochToken) {
            this.batchId = required(batchId, "batchId");
            this.firstSequence = positive(firstSequence, "firstSequence");
            this.lastSequence = rangeEnd(firstSequence, lastSequence);
            this.eventCount = nonNegative(eventCount, "eventCount");
            this.persistedAtMs = nonNegative(persistedAtMs, "persistedAtMs");
            this.persistedElapsedMs = nonNegative(persistedElapsedMs, "persistedElapsedMs");
            this.captureSessionId = required(captureSessionId, "captureSessionId");
            this.captureBootId = captureBootId;
            this.sourceEpochToken = sourceEpochToken;
            long rangeCount;
            try {
                rangeCount = Math.addExact(Math.subtractExact(lastSequence, firstSequence), 1);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("metadata range overflows", exception);
            }
            if (eventCount != rangeCount) {
                throw new IllegalArgumentException("metadata range does not match eventCount");
            }
        }
    }

    public static final class Event {
        public final long sequence;
        public final UUID eventId;
        public final UUID batchId;
        public final long chatId;
        public final long userId;
        public final long watermark;

        public Event(long sequence, UUID eventId, UUID batchId, long chatId, long userId,
                     long watermark) {
            this.sequence = positive(sequence, "sequence");
            this.eventId = required(eventId, "eventId");
            this.batchId = required(batchId, "batchId");
            this.chatId = chatId;
            this.userId = userId;
            this.watermark = watermark;
        }
    }

    public static final class Loss {
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

        public Loss(UUID lossReceiptId, long firstSequence, long lastSequence,
                    long droppedEventCount, long droppedBatchCount, UUID batchId, String reason,
                    Long persistedAtMs, Long persistedElapsedMs, UUID captureSessionId,
                    UUID captureBootId, UUID sourceEpochToken, long createdAtMs) {
            this.lossReceiptId = required(lossReceiptId, "lossReceiptId");
            this.firstSequence = positive(firstSequence, "firstSequence");
            this.lastSequence = rangeEnd(firstSequence, lastSequence);
            this.droppedEventCount = positive(droppedEventCount, "droppedEventCount");
            this.droppedBatchCount = nonNegative(droppedBatchCount, "droppedBatchCount");
            if (lastSequence - firstSequence + 1 != droppedEventCount) {
                throw new IllegalArgumentException("loss range does not match droppedEventCount");
            }
            this.batchId = batchId;
            if (!LOSS_REASONS.contains(reason)) throw new IllegalArgumentException("invalid loss reason");
            this.reason = reason;
            this.persistedAtMs = nullableNonNegative(persistedAtMs, "persistedAtMs");
            this.persistedElapsedMs = nullableNonNegative(persistedElapsedMs, "persistedElapsedMs");
            this.captureSessionId = captureSessionId;
            this.captureBootId = captureBootId;
            this.sourceEpochToken = sourceEpochToken;
            this.createdAtMs = nonNegative(createdAtMs, "createdAtMs");
        }
    }

    public static final class Lease {
        public final UUID installationId;
        public final UUID streamEpoch;
        public final UUID leaseId;
        public final long firstSequence;
        public final long lastSequence;
        public final long previousSequence;
        public final Counters counters;
        public final List<Metadata> batches;
        public final List<Event> events;
        public final List<Loss> losses;

        public Lease(UUID installationId, UUID streamEpoch, UUID leaseId, long firstSequence,
                     long lastSequence, long previousSequence, Counters counters,
                     List<Metadata> batches, List<Event> events, List<Loss> losses) {
            this.installationId = required(installationId, "installationId");
            this.streamEpoch = required(streamEpoch, "streamEpoch");
            this.leaseId = required(leaseId, "leaseId");
            this.firstSequence = positive(firstSequence, "firstSequence");
            this.lastSequence = rangeEnd(firstSequence, lastSequence);
            this.previousSequence = nonNegative(previousSequence, "previousSequence");
            if (previousSequence != firstSequence - 1) {
                throw new IllegalArgumentException("previousSequence is not adjacent");
            }
            this.counters = required(counters, "counters");
            this.batches = immutableCopy(batches, "batches");
            this.events = immutableCopy(events, "events");
            this.losses = immutableCopy(losses, "losses");
        }
    }

    public static final class IrisModel {
        private long cursor;
        private long highestIssuedSequence;
        private Counters counters = new Counters(0, 0, 0, 0, 0, 0);
        private boolean poisoned;
        private UUID expectedInstallationId;
        private UUID expectedStreamEpoch;
        private final Map<UUID, Lease> receipts = new HashMap<>();
        private final Map<UUID, byte[]> batches = new HashMap<>();
        private final Map<UUID, byte[]> events = new HashMap<>();
        private final Map<UUID, byte[]> losses = new HashMap<>();

        public IrisModel() {
        }

        public IrisModel(UUID installationId, UUID streamEpoch) {
            expectedInstallationId = required(installationId, "installationId");
            expectedStreamEpoch = required(streamEpoch, "streamEpoch");
        }

        public String ingest(Lease incoming) {
            if (poisoned) return "nack:stream_poisoned:false";
            if (expectedInstallationId != null && (!expectedInstallationId.equals(incoming.installationId)
                    || !expectedStreamEpoch.equals(incoming.streamEpoch))) return poison("unknown_epoch");
            Lease receipt = receipts.get(incoming.leaseId);
            if (receipt != null) {
                if (sameLease(incoming, receipt)) return "ack:" + receipt.lastSequence;
                return poison("conflicting_replay");
            }
            if (hasDuplicateCanonicalIdentities(incoming)) return poison("conflicting_replay");
            try {
                validateLease(incoming);
            } catch (IllegalArgumentException exception) {
                return poison("sequence_hole");
            }
            if (incoming.previousSequence != cursor) return poison("sequence_hole");
            if (!incoming.counters.atLeast(counters)) return poison("counter_regression");
            for (Metadata metadata : incoming.batches) {
                byte[] prior = batches.get(metadata.batchId);
                if (prior != null && !MessageDigest.isEqual(prior, metadataPreimage(metadata))) {
                    return poison("batch_conflict");
                }
            }
            for (Event event : incoming.events) {
                byte[] prior = events.get(event.eventId);
                if (prior != null && !MessageDigest.isEqual(prior, eventPreimage(event))) {
                    return poison("conflicting_replay");
                }
            }
            for (Loss loss : incoming.losses) {
                byte[] prior = losses.get(loss.lossReceiptId);
                if (prior != null && !MessageDigest.isEqual(prior, lossPreimage(loss))) {
                    return poison("conflicting_replay");
                }
            }
            for (Metadata metadata : incoming.batches) batches.put(metadata.batchId, metadataPreimage(metadata));
            for (Event event : incoming.events) events.put(event.eventId, eventPreimage(event));
            for (Loss loss : incoming.losses) losses.put(loss.lossReceiptId, lossPreimage(loss));
            receipts.put(incoming.leaseId, incoming);
            if (expectedInstallationId == null) {
                expectedInstallationId = incoming.installationId;
                expectedStreamEpoch = incoming.streamEpoch;
            }
            cursor = incoming.lastSequence;
            highestIssuedSequence = Math.max(highestIssuedSequence, incoming.lastSequence);
            counters = incoming.counters;
            return "ack:" + incoming.lastSequence;
        }

        public long cursor() {
            return cursor;
        }

        public long highestIssuedSequence() {
            return highestIssuedSequence;
        }

        public Counters counters() {
            return counters;
        }

        public boolean poisoned() {
            return poisoned;
        }

        private String poison(String category) {
            poisoned = true;
            return "nack:" + category + ":false";
        }
    }

    public static final class HandshakeModel {
        private State iris;
        private boolean installationKnown;
        private boolean poisoned;

        public HandshakeModel() {
        }

        public HandshakeModel(State iris, boolean installationKnown) {
            this.iris = iris;
            this.installationKnown = installationKnown;
            poisoned = iris != null && "poisoned".equals(iris.streamStatus);
        }

        public String handshake(State sender) {
            if (poisoned) return "nack:stream_poisoned:false";
            String result = classifyHandshake(sender, iris, installationKnown);
            if ("nack:stream_rollback:false".equals(result)
                    || "nack:unknown_epoch:false".equals(result)) {
                poisoned = true;
                return result;
            }
            if (iris == null && "accepted".equals(result)) {
                iris = new State(sender.installationId, sender.streamEpoch, sender.lastAckedSequence,
                        sender.highestIssuedSequence, null, sender.counters, "active");
                installationKnown = true;
            }
            return result;
        }

        public boolean poisoned() {
            return poisoned;
        }
    }

    public static final class ActiveLease {
        public final UUID leaseId;
        public final long firstSequence;
        public final long lastSequence;
        public final byte[] leaseDigest;
        public final String state;

        public ActiveLease(UUID leaseId, long firstSequence, long lastSequence, byte[] leaseDigest,
                           String state) {
            this.leaseId = required(leaseId, "leaseId");
            this.firstSequence = positive(firstSequence, "firstSequence");
            this.lastSequence = rangeEnd(firstSequence, lastSequence);
            this.leaseDigest = bytes32(leaseDigest, "leaseDigest");
            if (!"active".equals(state) && !"poisoned".equals(state)) {
                throw new IllegalArgumentException("invalid active lease state");
            }
            this.state = state;
        }
    }

    public static final class State {
        public final UUID installationId;
        public final UUID streamEpoch;
        public final long lastAckedSequence;
        public final long highestIssuedSequence;
        public final ActiveLease activeLease;
        public final Counters counters;
        public final String streamStatus;

        public State(UUID installationId, UUID streamEpoch, long lastAckedSequence,
                     long highestIssuedSequence, ActiveLease activeLease, Counters counters,
                     String streamStatus) {
            this.installationId = required(installationId, "installationId");
            this.streamEpoch = required(streamEpoch, "streamEpoch");
            this.lastAckedSequence = nonNegative(lastAckedSequence, "lastAckedSequence");
            this.highestIssuedSequence = nonNegative(highestIssuedSequence, "highestIssuedSequence");
            this.activeLease = activeLease;
            this.counters = required(counters, "counters");
            if (!"new".equals(streamStatus) && !"active".equals(streamStatus)
                    && !"poisoned".equals(streamStatus)) {
                throw new IllegalArgumentException("invalid stream status");
            }
            this.streamStatus = streamStatus;
        }
    }

    public static byte[] metadataPreimage(Metadata metadata) {
        return encode(out -> {
            string(out, "read-receipt-batch-metadata-v2");
            metadataFields(out, metadata);
        });
    }

    public static byte[] metadataDigest(Metadata metadata) {
        return sha256(metadataPreimage(metadata));
    }

    public static byte[] lossPreimage(Loss loss) {
        return encode(out -> {
            string(out, "read-receipt-loss-content-v2");
            lossFields(out, loss);
        });
    }

    public static byte[] lossDigest(Loss loss) {
        return sha256(lossPreimage(loss));
    }

    public static byte[] leasePreimage(Lease lease) {
        validateLease(lease);
        return encode(out -> {
            string(out, "read-receipt-lease-v2");
            out.writeLong(2);
            uuid(out, lease.installationId);
            uuid(out, lease.streamEpoch);
            uuid(out, lease.leaseId);
            out.writeLong(lease.firstSequence);
            out.writeLong(lease.lastSequence);
            out.writeLong(lease.previousSequence);
            counters(out, lease.counters);
            out.writeInt(lease.batches.size());
            for (Metadata metadata : lease.batches) {
                string(out, "read-receipt-batch-element-v2");
                metadataFields(out, metadata);
                bytes(out, metadataDigest(metadata));
            }
            out.writeInt(lease.events.size());
            for (Event event : lease.events) event(out, event);
            out.writeInt(lease.losses.size());
            for (Loss loss : lease.losses) {
                string(out, "read-receipt-loss-element-v2");
                lossFields(out, loss);
                bytes(out, lossDigest(loss));
            }
        });
    }

    public static byte[] leaseDigest(Lease lease) {
        return sha256(leasePreimage(lease));
    }

    public static byte[] statePreimage(State state) {
        if ("new".equals(state.streamStatus)) {
            throw new IllegalArgumentException("state digest status must be active or poisoned");
        }
        return encode(out -> {
            string(out, "read-receipt-state-v2");
            uuid(out, state.installationId);
            uuid(out, state.streamEpoch);
            out.writeLong(state.lastAckedSequence);
            out.writeLong(state.highestIssuedSequence);
            if (state.activeLease == null) {
                out.writeByte(0);
            } else {
                out.writeByte(1);
                string(out, "read-receipt-active-lease-v2");
                uuid(out, state.activeLease.leaseId);
                out.writeLong(state.activeLease.firstSequence);
                out.writeLong(state.activeLease.lastSequence);
                bytes(out, state.activeLease.leaseDigest);
                string(out, state.activeLease.state);
            }
            counters(out, state.counters);
            string(out, state.streamStatus);
        });
    }

    public static byte[] stateDigest(State state) {
        return sha256(statePreimage(state));
    }

    public static UUID installationId(byte[] token) {
        byte[] raw = Arrays.copyOf(hmac(token,
                "read-receipt-installation-id-v2".getBytes(StandardCharsets.US_ASCII)), 16);
        raw[6] = (byte) ((raw[6] & 0x0f) | 0x80);
        raw[8] = (byte) ((raw[8] & 0x3f) | 0x80);
        return uuidFromBytes(raw);
    }

    public static String socketName(byte[] token) {
        return SOCKET_NAME_PREFIX + toHex(hmac(bytes32(token, "token"), SOCKET_NAME_DOMAIN));
    }

    public static byte[] serverProofPreimage(byte[] clientNonce, byte[] serverNonce,
                                             UUID installationId, byte[] stateDigest) {
        return encode(out -> {
            string(out, "read-receipt-server-proof-v2");
            bytes(out, bytes32(clientNonce, "clientNonce"));
            bytes(out, bytes32(serverNonce, "serverNonce"));
            uuid(out, installationId);
            bytes(out, bytes32(stateDigest, "stateDigest"));
        });
    }

    public static byte[] clientProofPreimage(byte[] clientNonce, byte[] serverNonce,
                                             UUID installationId, UUID streamEpoch,
                                             byte[] stateDigest, UUID knownStreamEpoch,
                                             long lastCommittedSequence, String streamStatus) {
        nonNegative(lastCommittedSequence, "lastCommittedSequence");
        if (!"new".equals(streamStatus) && !"active".equals(streamStatus)
                && !"poisoned".equals(streamStatus)) {
            throw new IllegalArgumentException("invalid client stream status");
        }
        return encode(out -> {
            string(out, "read-receipt-client-proof-v2");
            bytes(out, bytes32(clientNonce, "clientNonce"));
            bytes(out, bytes32(serverNonce, "serverNonce"));
            uuid(out, installationId);
            uuid(out, streamEpoch);
            bytes(out, bytes32(stateDigest, "stateDigest"));
            nullableUuid(out, knownStreamEpoch);
            out.writeLong(lastCommittedSequence);
            string(out, streamStatus);
        });
    }

    public static byte[] serverProof(byte[] token, byte[] clientNonce, byte[] serverNonce,
                                     UUID installationId, byte[] stateDigest) {
        return hmac(token, serverProofPreimage(clientNonce, serverNonce, installationId, stateDigest));
    }

    public static byte[] clientProof(byte[] token, byte[] clientNonce, byte[] serverNonce,
                                     UUID installationId, UUID streamEpoch, byte[] stateDigest,
                                     UUID knownStreamEpoch, long lastCommittedSequence,
                                     String streamStatus) {
        return hmac(token, clientProofPreimage(clientNonce, serverNonce, installationId,
                streamEpoch, stateDigest, knownStreamEpoch, lastCommittedSequence, streamStatus));
    }

    public static String classifyBatch(long irisCursor, Lease incoming, Lease committed) {
        boolean same = committed != null && sameLease(incoming, committed);
        if (same) return "ack:" + committed.lastSequence;
        if (committed != null) return "nack:conflicting_replay:false";
        if (incoming.previousSequence != irisCursor) return "nack:sequence_hole:false";
        return "ack:" + incoming.lastSequence;
    }

    public static String classifyAck(ActiveLease active, UUID installationId, UUID expectedInstallation,
                                     UUID streamEpoch, UUID expectedEpoch, UUID leaseId,
                                     long throughSequence, byte[] digest) {
        if (active == null || !"active".equals(active.state)
                || !expectedInstallation.equals(installationId) || !expectedEpoch.equals(streamEpoch)
                || !active.leaseId.equals(leaseId)) return "nack:lease_mismatch:false";
        if (active.lastSequence != throughSequence) return "nack:range_mismatch:false";
        if (!MessageDigest.isEqual(active.leaseDigest, digest)) return "nack:digest_mismatch:false";
        return "accepted";
    }

    public static String classifyHandshake(State sender, State iris, boolean installationKnown) {
        if ("poisoned".equals(sender.streamStatus) || sender.activeLease != null
                && "poisoned".equals(sender.activeLease.state)
                || iris != null && ("poisoned".equals(iris.streamStatus)
                || iris.activeLease != null && "poisoned".equals(iris.activeLease.state))) {
            return "nack:stream_poisoned:false";
        }
        if (iris == null || !installationKnown) {
            return sender.lastAckedSequence == 0 ? "accepted" : "nack:stream_rollback:false";
        }
        if (!sender.streamEpoch.equals(iris.streamEpoch)) return "nack:unknown_epoch:false";
        long cursor = iris.lastAckedSequence;
        if (cursor > sender.highestIssuedSequence) return "nack:stream_rollback:false";
        if (sender.activeLease != null && cursor > sender.activeLease.firstSequence
                && cursor < sender.activeLease.lastSequence) return "nack:stream_rollback:false";
        if (cursor == sender.lastAckedSequence) return "accepted";
        if (cursor > sender.lastAckedSequence && sender.activeLease != null
                && cursor == sender.activeLease.lastSequence) return "accepted_exact_replay";
        return "nack:stream_rollback:false";
    }

    public static String classifyStateUpdate(State update, State iris) {
        if ("poisoned".equals(update.streamStatus) || "poisoned".equals(iris.streamStatus)) {
            return "nack:stream_poisoned:false";
        }
        if (update.activeLease != null) return "nack:lease_mismatch:false";
        if (!"active".equals(update.streamStatus)) return "nack:stream_rollback:false";
        if (!update.installationId.equals(iris.installationId)
                || !update.streamEpoch.equals(iris.streamEpoch)
                || update.lastAckedSequence != iris.lastAckedSequence
                || update.highestIssuedSequence < update.lastAckedSequence
                || update.highestIssuedSequence < iris.highestIssuedSequence) {
            return "nack:stream_rollback:false";
        }
        if (!update.counters.atLeast(iris.counters)) return "nack:counter_regression:false";
        return "accepted:no_ack:cursor_" + iris.lastAckedSequence;
    }

    public static boolean frameAllowed(long length, boolean streamFrame) {
        return length > 0 && length <= (streamFrame ? STREAM_FRAME_MAX : CONTROL_FRAME_MAX);
    }

    public static long decodeFrameLength(byte[] prefix) {
        if (prefix == null || prefix.length != 4) {
            throw new IllegalArgumentException("frame prefix must be four bytes");
        }
        return ((prefix[0] & 0xffL) << 24) | ((prefix[1] & 0xffL) << 16)
                | ((prefix[2] & 0xffL) << 8) | (prefix[3] & 0xffL);
    }

    public static byte[] encodeFrameLength(long length) {
        if (length < 0 || length > 0xffff_ffffL) {
            throw new IllegalArgumentException("frame length is outside u32");
        }
        return new byte[]{(byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length};
    }

    public static boolean phaseAllowed(String phase, String frameType) {
        if ("await_hello".equals(phase)) return "HELLO".equals(frameType);
        if ("await_auth".equals(phase)) return "AUTH".equals(frameType);
        return "authenticated".equals(phase)
                && ("STREAM_BATCH".equals(frameType) || "STATE_UPDATE".equals(frameType));
    }

    public static boolean phaseAllowed(String role, String phase, String frameType) {
        if ("sender".equals(role)) {
            if ("await_hello".equals(phase)) return "hello".equals(frameType);
            if ("await_auth".equals(phase)) return "auth".equals(frameType);
            return "authenticated".equals(phase)
                    && ("ack".equals(frameType) || "nack".equals(frameType));
        }
        if ("iris".equals(role)) {
            if ("await_challenge".equals(phase)) {
                return "challenge".equals(frameType) || "nack".equals(frameType);
            }
            if ("await_authenticated".equals(phase)) {
                return "authenticated".equals(frameType) || "nack".equals(frameType);
            }
            return "authenticated".equals(phase) && ("stream_batch".equals(frameType)
                    || "state_update".equals(frameType) || "nack".equals(frameType));
        }
        return false;
    }

    public static boolean validNack(String category, boolean retryable) {
        if ("schema_unavailable".equals(category) || "internal_transient".equals(category)) {
            return retryable;
        }
        return NON_RETRYABLE_NACKS.contains(category) && !retryable;
    }

    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static byte[] fromHex(String value) {
        if (value == null || (value.length() & 1) != 0 || !value.matches("[0-9a-f]*")) {
            throw new IllegalArgumentException("non-canonical hex");
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    public static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    public static UUID canonicalUuid(String value) {
        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid UUID", exception);
        }
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("non-canonical UUID");
        return uuid;
    }

    private static void validateLease(Lease lease) {
        if (lease.batches.size() > LIST_MAX || lease.events.size() > LIST_MAX
                || lease.losses.size() > LIST_MAX || lease.events.isEmpty() && lease.losses.isEmpty()) {
            throw new IllegalArgumentException("invalid lease list size");
        }
        if (hasDuplicateCanonicalIdentities(lease)) {
            throw new IllegalArgumentException("duplicate canonical identity");
        }
        Comparator<Metadata> metadataOrder = Comparator.comparingLong((Metadata item) -> item.firstSequence)
                .thenComparing(item -> uuidBytes(item.batchId), ReadReceiptProtocolV2::compareUnsigned);
        for (int i = 1; i < lease.batches.size(); i++) {
            if (metadataOrder.compare(lease.batches.get(i - 1), lease.batches.get(i)) >= 0) {
                throw new IllegalArgumentException("batch metadata is not canonically sorted");
            }
        }
        Map<UUID, Metadata> metadataById = new HashMap<>();
        for (Metadata metadata : lease.batches) {
            if (metadataById.put(metadata.batchId, metadata) != null) {
                throw new IllegalArgumentException("duplicate batch metadata");
            }
        }
        long next = lease.firstSequence;
        long previousEvent = 0;
        for (Event event : lease.events) {
            if (event.sequence <= previousEvent) throw new IllegalArgumentException("events are not sorted");
            previousEvent = event.sequence;
            Metadata metadata = metadataById.get(event.batchId);
            if (metadata == null) throw new IllegalArgumentException("missing event metadata");
            if (event.sequence < metadata.firstSequence || event.sequence > metadata.lastSequence) {
                throw new IllegalArgumentException("event sequence is outside metadata range");
            }
        }
        long previousLossEnd = 0;
        for (Loss loss : lease.losses) {
            if (loss.firstSequence <= previousLossEnd) throw new IllegalArgumentException("losses are not sorted");
            previousLossEnd = loss.lastSequence;
            if (loss.batchId != null && !metadataById.containsKey(loss.batchId)) {
                throw new IllegalArgumentException("missing loss metadata");
            }
        }
        int eventIndex = 0;
        int lossIndex = 0;
        while (eventIndex < lease.events.size() || lossIndex < lease.losses.size()) {
            Event event = eventIndex < lease.events.size() ? lease.events.get(eventIndex) : null;
            Loss loss = lossIndex < lease.losses.size() ? lease.losses.get(lossIndex) : null;
            if (event != null && (loss == null || event.sequence < loss.firstSequence)) {
                if (event.sequence != next++) throw new IllegalArgumentException("event sequence hole");
                eventIndex++;
            } else {
                if (loss.firstSequence != next) throw new IllegalArgumentException("loss sequence hole");
                next = loss.lastSequence + 1;
                lossIndex++;
            }
        }
        if (next != lease.lastSequence + 1) throw new IllegalArgumentException("lease range is not covered");
    }

    private static boolean hasDuplicateCanonicalIdentities(Lease lease) {
        Set<UUID> eventIds = new HashSet<>();
        for (Event event : lease.events) {
            if (!eventIds.add(event.eventId)) return true;
        }
        Set<UUID> lossReceiptIds = new HashSet<>();
        for (Loss loss : lease.losses) {
            if (!lossReceiptIds.add(loss.lossReceiptId)) return true;
        }
        return false;
    }

    private interface Encoder {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] encode(Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            encoder.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void metadataFields(DataOutputStream out, Metadata value) throws IOException {
        uuid(out, value.batchId);
        out.writeLong(value.firstSequence);
        out.writeLong(value.lastSequence);
        out.writeLong(value.eventCount);
        out.writeLong(value.persistedAtMs);
        out.writeLong(value.persistedElapsedMs);
        uuid(out, value.captureSessionId);
        nullableUuid(out, value.captureBootId);
        nullableUuid(out, value.sourceEpochToken);
    }

    private static void event(DataOutputStream out, Event value) throws IOException {
        string(out, "read-receipt-event-v2");
        out.writeLong(value.sequence);
        uuid(out, value.eventId);
        uuid(out, value.batchId);
        out.writeLong(value.chatId);
        out.writeLong(value.userId);
        out.writeLong(value.watermark);
    }

    private static byte[] eventPreimage(Event value) {
        return encode(out -> event(out, value));
    }

    private static boolean sameLease(Lease left, Lease right) {
        return left.installationId.equals(right.installationId)
                && left.streamEpoch.equals(right.streamEpoch)
                && left.leaseId.equals(right.leaseId)
                && left.firstSequence == right.firstSequence
                && left.lastSequence == right.lastSequence
                && left.counters.same(right.counters)
                && MessageDigest.isEqual(leaseDigest(left), leaseDigest(right));
    }

    private static void lossFields(DataOutputStream out, Loss value) throws IOException {
        uuid(out, value.lossReceiptId);
        out.writeLong(value.firstSequence);
        out.writeLong(value.lastSequence);
        out.writeLong(value.droppedEventCount);
        out.writeLong(value.droppedBatchCount);
        nullableUuid(out, value.batchId);
        string(out, value.reason);
        nullableLong(out, value.persistedAtMs);
        nullableLong(out, value.persistedElapsedMs);
        nullableUuid(out, value.captureSessionId);
        nullableUuid(out, value.captureBootId);
        nullableUuid(out, value.sourceEpochToken);
        out.writeLong(value.createdAtMs);
    }

    private static void counters(DataOutputStream out, Counters value) throws IOException {
        out.writeLong(value.confirmedDroppedEventCount);
        out.writeLong(value.confirmedDroppedBatchCount);
        out.writeLong(value.uncertainOutcomeEventCount);
        out.writeLong(value.uncertainOutcomeBatchCount);
        out.writeLong(value.captureDropEventCount);
        out.writeLong(value.captureDropBatchCount);
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void bytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static void nullableBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeByte(value == null ? 0 : 1);
        if (value != null) bytes(out, value);
    }

    private static void nullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeByte(value == null ? 0 : 1);
        if (value != null) out.writeLong(value);
    }

    private static void nullableUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeByte(value == null ? 0 : 1);
        if (value != null) uuid(out, value);
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(required(value, "uuid").getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static byte[] uuidBytes(UUID value) {
        return encode(out -> uuid(out, value));
    }

    private static UUID uuidFromBytes(byte[] value) {
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) most = (most << 8) | (value[i] & 0xffL);
        for (int i = 8; i < 16; i++) least = (least << 8) | (value[i] & 0xffL);
        return new UUID(most, least);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int i = 0; i < left.length; i++) {
            int difference = (left[i] & 0xff) - (right[i] & 0xff);
            if (difference != 0) return difference;
        }
        return 0;
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    private static Long nullableNonNegative(Long value, String name) {
        if (value != null) nonNegative(value, name);
        return value;
    }

    private static long rangeEnd(long first, long last) {
        if (last < first) throw new IllegalArgumentException("invalid range");
        return last;
    }

    private static byte[] bytes32(byte[] value, String name) {
        if (value == null || value.length != 32) throw new IllegalArgumentException(name + " must be 32 bytes");
        return value.clone();
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static <T> List<T> immutableCopy(List<T> value, String name) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(required(value, name)));
    }
}
