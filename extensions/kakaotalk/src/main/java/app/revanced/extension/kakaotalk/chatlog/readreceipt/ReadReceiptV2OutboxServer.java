package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReadReceiptV2OutboxServer implements ReadReceiptStartupValidation.Activation {
    private static final int ROOT_UID = 0;
    private static final int DEFAULT_ACCEPT_TIMEOUT_MS = 1_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_WRITE_TIMEOUT_MS = 5_000;
    // API 28 공개 OsConstants에 없으므로 Android Linux UAPI의 고정값을 사용합니다.
    private static final int LINUX_MSG_DONTWAIT = 0x40;
    private static final AtomicBoolean OWNER = new AtomicBoolean();
    private static final MonotonicClock SYSTEM_MONOTONIC_CLOCK =
            () -> System.nanoTime() / 1_000_000L;

    private final byte[] token;
    private final StreamPort stream;
    private final ListenerFactory listenerFactory;
    private final NonceSource nonceSource;
    private final ThreadStarter threadStarter;
    private final MonotonicClock monotonicClock;
    private final int acceptTimeoutMs;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;
    private volatile boolean running;
    private volatile Listener listener;
    private volatile Connection activeConnection;
    private volatile Worker worker;
    private boolean ownsServer;
    private boolean closed;

    public static ReadReceiptV2OutboxServer android(byte[] token, StreamPort stream) {
        SecureRandom random = new SecureRandom();
        return new ReadReceiptV2OutboxServer(token, stream, new AndroidListenerFactory(),
                random::nextBytes, runnable -> {
                    Thread thread = new Thread(runnable, "read-receipt-v2-outbox");
                    thread.setDaemon(true);
                    thread.start();
                    return timeoutMs -> {
                        if (Thread.currentThread() == thread) return false;
                        try {
                            thread.join(timeoutMs);
                            return !thread.isAlive();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    };
                }, SYSTEM_MONOTONIC_CLOCK, DEFAULT_ACCEPT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS, DEFAULT_WRITE_TIMEOUT_MS);
    }

    ReadReceiptV2OutboxServer(byte[] token, StreamPort stream, ListenerFactory listenerFactory,
                              NonceSource nonceSource, ThreadStarter threadStarter,
                              int acceptTimeoutMs, int readTimeoutMs, int writeTimeoutMs) {
        this(token, stream, listenerFactory, nonceSource, threadStarter, SYSTEM_MONOTONIC_CLOCK,
                acceptTimeoutMs, readTimeoutMs, writeTimeoutMs);
    }

    ReadReceiptV2OutboxServer(byte[] token, StreamPort stream, ListenerFactory listenerFactory,
                              NonceSource nonceSource, ThreadStarter threadStarter,
                              MonotonicClock monotonicClock, int acceptTimeoutMs,
                              int readTimeoutMs, int writeTimeoutMs) {
        this.token = copyToken(token);
        this.stream = required(stream);
        this.listenerFactory = required(listenerFactory);
        this.nonceSource = required(nonceSource);
        this.threadStarter = required(threadStarter);
        this.monotonicClock = required(monotonicClock);
        this.acceptTimeoutMs = positiveTimeout(acceptTimeoutMs);
        this.readTimeoutMs = positiveTimeout(readTimeoutMs);
        this.writeTimeoutMs = positiveTimeout(writeTimeoutMs);
    }

    @Override
    public synchronized void start() {
        if (closed || running || !OWNER.compareAndSet(false, true)) throw activationFailure();
        ownsServer = true;
        Listener opened = null;
        try {
            opened = listenerFactory.bind(deriveSocketName(token));
            listener = required(opened);
            running = true;
            stream.requestRetry();
            worker = required(threadStarter.start(this::runLoop));
        } catch (RuntimeException | IOException exception) {
            running = false;
            listener = null;
            closeQuietly(opened);
            releaseOwner();
            throw activationFailure();
        }
    }

    @Override
    public void close() {
        Listener active;
        Connection connection;
        Worker activeWorker;
        synchronized (this) {
            running = false;
            closed = true;
            active = listener;
            listener = null;
            connection = activeConnection;
            activeWorker = worker;
        }
        closeQuietly(active);
        closeQuietly(connection);
        boolean stopped = activeWorker == null
                || activeWorker.awaitStopped((long) acceptTimeoutMs + readTimeoutMs + writeTimeoutMs);
        if (stopped) {
            synchronized (this) {
                if (worker == activeWorker) worker = null;
            }
            releaseOwner();
        }
        Arrays.fill(token, (byte) 0);
    }

    static String deriveSocketName(byte[] token) {
        return ReadReceiptProtocolV2.socketName(token);
    }

    static byte[] readFrame(FrameIo io, boolean streamFrame, int timeoutMs,
                            MonotonicClock clock) throws IOException {
        return readFrameUntil(io, streamFrame, deadline(clock, timeoutMs), clock);
    }

    static void writeFrame(FrameIo io, byte[] payload, boolean streamFrame, int timeoutMs,
                           MonotonicClock clock) throws IOException {
        writeFrameUntil(io, payload, streamFrame, deadline(clock, timeoutMs), clock);
    }

    static void handleConnection(Connection connection, byte[] token, StreamPort stream,
                                 NonceSource nonceSource, int readTimeoutMs,
                                 int writeTimeoutMs) throws IOException {
        handleConnection(connection, token, stream, nonceSource, readTimeoutMs, writeTimeoutMs,
                SYSTEM_MONOTONIC_CLOCK);
    }

    static void handleConnection(Connection connection, byte[] token, StreamPort stream,
                                 NonceSource nonceSource, int readTimeoutMs,
                                 int writeTimeoutMs, MonotonicClock clock) throws IOException {
        required(connection);
        required(clock);
        Session session = null;
        boolean authorized = false;
        try {
            if (connection.peerUid() != ROOT_UID) return;
            authorized = true;
            positiveTimeout(readTimeoutMs);
            positiveTimeout(writeTimeoutMs);
            session = new Session(token, stream, nonceSource);
            long handshakeDeadline = deadline(clock, readTimeoutMs);
            while (!session.closed()) {
                byte[] inbound;
                try {
                    long frameDeadline = "authenticated".equals(session.phase())
                            ? deadline(clock, readTimeoutMs) : handshakeDeadline;
                    inbound = readFrameUntil(connection, false, frameDeadline, clock);
                } catch (IOException | ProtocolException exception) {
                    break;
                }
                List<OutboundFrame> responses;
                try {
                    responses = session.onFrame(inbound);
                } catch (ProtocolException exception) {
                    break;
                }
                boolean handshaking = !"authenticated".equals(session.phase());
                for (OutboundFrame response : responses) {
                    long frameDeadline = deadline(clock, writeTimeoutMs);
                    if (handshaking) frameDeadline = Math.min(frameDeadline, handshakeDeadline);
                    writeFrameUntil(connection, response.payload, response.stream,
                            frameDeadline, clock);
                }
            }
        } finally {
            if (session != null) session.destroy();
            closeQuietly(connection);
            if (authorized) stream.requestRetry();
        }
    }

    private void runLoop() {
        try {
            while (running) {
                Listener active = listener;
                if (active == null) return;
                Connection connection = null;
                try {
                    connection = active.accept(acceptTimeoutMs);
                    if (connection == null) {
                        stream.requestRetry();
                        continue;
                    }
                    activeConnection = connection;
                    handleConnection(connection, token, stream, nonceSource,
                            readTimeoutMs, writeTimeoutMs, monotonicClock);
                } catch (IOException | RuntimeException exception) {
                    closeQuietly(connection);
                    if (running) stream.requestRetry();
                } finally {
                    activeConnection = null;
                }
            }
        } finally {
            releaseOwner();
        }
    }

    private synchronized void releaseOwner() {
        if (!ownsServer) return;
        ownsServer = false;
        OWNER.set(false);
    }

    private static byte[] readFrameUntil(FrameIo io, boolean streamFrame, long deadlineMs,
                                         MonotonicClock clock) throws IOException {
        byte[] prefix = new byte[4];
        readFully(io, prefix, deadlineMs, clock);
        long length = ReadReceiptProtocolV2.decodeFrameLength(prefix);
        if (!ReadReceiptProtocolV2.frameAllowed(length, streamFrame)) throw rejected();
        byte[] payload = new byte[(int) length];
        readFully(io, payload, deadlineMs, clock);
        return payload;
    }

    private static void writeFrameUntil(FrameIo io, byte[] payload, boolean streamFrame,
                                        long deadlineMs, MonotonicClock clock)
            throws IOException {
        if (payload == null || !ReadReceiptProtocolV2.frameAllowed(payload.length, streamFrame)) {
            throw rejected();
        }
        writeFully(io, ReadReceiptProtocolV2.encodeFrameLength(payload.length),
                deadlineMs, clock);
        writeFully(io, payload, deadlineMs, clock);
    }

    private static void readFully(FrameIo io, byte[] target, long deadlineMs,
                                  MonotonicClock clock) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = io.read(target, offset, target.length - offset,
                    remainingMs(deadlineMs, clock));
            if (count <= 0 || count > target.length - offset) {
                throw new IOException("frame unavailable");
            }
            offset += count;
            requireWithinDeadline(deadlineMs, clock);
        }
    }

    private static void writeFully(FrameIo io, byte[] source, long deadlineMs,
                                   MonotonicClock clock) throws IOException {
        int offset = 0;
        while (offset < source.length) {
            int count = io.write(source, offset, source.length - offset,
                    remainingMs(deadlineMs, clock));
            if (count < 0 || count > source.length - offset) {
                throw new IOException("frame unavailable");
            }
            if (count > 0) offset += count;
            requireWithinDeadline(deadlineMs, clock);
        }
    }

    private static long deadline(MonotonicClock clock, int timeoutMs) {
        long now = required(clock).nowMs();
        int timeout = positiveTimeout(timeoutMs);
        return now > Long.MAX_VALUE - timeout ? Long.MAX_VALUE : now + timeout;
    }

    private static int remainingMs(long deadlineMs, MonotonicClock clock) throws IOException {
        long now = required(clock).nowMs();
        if (now >= deadlineMs) throw frameDeadlineExceeded();
        long remaining = deadlineMs - now;
        if (remaining < 0) remaining = Long.MAX_VALUE;
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static void requireWithinDeadline(long deadlineMs, MonotonicClock clock)
            throws IOException {
        if (required(clock).nowMs() > deadlineMs) throw frameDeadlineExceeded();
    }

    private static IOException frameDeadlineExceeded() {
        return new IOException("frame deadline exceeded");
    }

    private static byte[] copyToken(byte[] value) {
        if (value == null || value.length != 32) throw activationFailure();
        return value.clone();
    }

    private static int positiveTimeout(int value) {
        if (value <= 0) throw activationFailure();
        return value;
    }

    private static <T> T required(T value) {
        if (value == null) throw activationFailure();
        return value;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static IllegalStateException activationFailure() {
        return new IllegalStateException("outbox activation failed");
    }

    private static ProtocolException rejected() {
        return new ProtocolException();
    }

    public interface StreamPort {
        ReadReceiptProtocolV2.State currentState();

        LeaseFrame claimOrReplayLease();

        StateUpdate stateUpdate();

        AckOutcome acceptAck(Ack ack);

        void onNack(String category, boolean retryable);

        void requestRetry();
    }

    interface ListenerFactory {
        Listener bind(String endpoint) throws IOException;
    }

    interface Listener extends AutoCloseable {
        Connection accept(int timeoutMs) throws IOException;

        @Override
        void close() throws IOException;
    }

    interface FrameIo {
        int read(byte[] target, int offset, int length, int timeoutMs) throws IOException;

        int write(byte[] source, int offset, int length, int timeoutMs) throws IOException;
    }

    interface Connection extends FrameIo, AutoCloseable {
        int peerUid() throws IOException;

        @Override
        void close() throws IOException;
    }

    interface NonceSource {
        void nextBytes(byte[] output);
    }

    interface MonotonicClock {
        long nowMs();
    }

    interface ThreadStarter {
        Worker start(Runnable runnable);
    }

    interface Worker {
        boolean awaitStopped(long timeoutMs);
    }

    static final class OutboundFrame {
        final byte[] payload;
        final boolean stream;

        OutboundFrame(byte[] payload, boolean stream) {
            this.payload = payload.clone();
            this.stream = stream;
        }
    }

    static final class Session {
        private final byte[] token;
        private final StreamPort stream;
        private final NonceSource nonceSource;
        private String phase = "await_hello";
        private byte[] clientNonce;
        private byte[] serverNonce;
        private byte[] stateDigest;
        private ReadReceiptProtocolV2.State senderState;
        private boolean closed;

        Session(byte[] token, StreamPort stream, NonceSource nonceSource) {
            this.token = copyToken(token);
            this.stream = required(stream);
            this.nonceSource = required(nonceSource);
        }

        String phase() {
            return phase;
        }

        boolean closed() {
            return closed;
        }

        List<OutboundFrame> onFrame(byte[] json) {
            if (closed) throw rejected();
            Map<String, Object> frame = object(json);
            String type = string(frame, "type");
            if (!ReadReceiptProtocolV2.phaseAllowed("sender", phase, type)) {
                return closeRejected();
            }
            if ("await_hello".equals(phase)) return onHello(frame);
            if ("await_auth".equals(phase)) return onAuth(frame);
            if ("ack".equals(type)) return onAck(frame);
            return onNack(frame);
        }

        private List<OutboundFrame> onHello(Map<String, Object> frame) {
            exactKeys(frame, "type", "protocol_version", "client_nonce");
            protocolVersion(frame);
            clientNonce = bytes32(frame, "client_nonce");
            serverNonce = new byte[32];
            nonceSource.nextBytes(serverNonce);
            senderState = requiredState(stream.currentState());
            stateDigest = ReadReceiptProtocolV2.stateDigest(senderState);
            byte[] proof = ReadReceiptProtocolV2.serverProof(token, clientNonce, serverNonce,
                    senderState.installationId, stateDigest);
            Map<String, Object> challenge = map();
            challenge.put("type", "challenge");
            challenge.put("protocol_version", 2L);
            challenge.put("server_nonce", hex(serverNonce));
            challenge.put("installation_id", senderState.installationId.toString());
            challenge.put("state_digest", hex(stateDigest));
            challenge.put("state", state(senderState));
            challenge.put("server_proof", hex(proof));
            Arrays.fill(proof, (byte) 0);
            phase = "await_auth";
            return Collections.singletonList(control(challenge));
        }

        private List<OutboundFrame> onAuth(Map<String, Object> frame) {
            exactKeys(frame, "type", "protocol_version", "known_stream_epoch",
                    "last_committed_sequence", "stream_status", "client_proof");
            protocolVersion(frame);
            UUID knownEpoch = nullableUuid(frame, "known_stream_epoch");
            long committed = nonNegative(integer(frame, "last_committed_sequence"));
            String status = string(frame, "stream_status");
            if (!"new".equals(status) && !"active".equals(status)
                    && !"poisoned".equals(status)) {
                return closeRejected();
            }
            byte[] supplied = bytes32(frame, "client_proof");
            byte[] expected = ReadReceiptProtocolV2.clientProof(token, clientNonce, serverNonce,
                    senderState.installationId, senderState.streamEpoch, stateDigest, knownEpoch,
                    committed, status);
            boolean proofMatches = constantTimeEquals(expected, supplied);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(supplied, (byte) 0);
            Arrays.fill(token, (byte) 0);
            if (!proofMatches) return closeRejected();
            ReadReceiptProtocolV2.State fresh = requiredState(stream.currentState());
            if (!constantTimeEquals(stateDigest, ReadReceiptProtocolV2.stateDigest(fresh))) {
                stream.requestRetry();
                return closeRejected();
            }
            destroy();
            String category = handshakeFailure(senderState, knownEpoch, committed, status);
            if (category != null) {
                stream.onNack(category, false);
                closed = true;
                return Collections.singletonList(control(nack(category, false)));
            }
            phase = "authenticated";
            List<OutboundFrame> result = new ArrayList<>();
            Map<String, Object> authenticated = map();
            authenticated.put("type", "authenticated");
            authenticated.put("protocol_version", 2L);
            result.add(control(authenticated));
            OutboundFrame next = nextOutbound();
            if (next != null) result.add(next);
            return result;
        }

        private List<OutboundFrame> onAck(Map<String, Object> frame) {
            exactKeys(frame, "type", "installation_id", "stream_epoch", "lease_id",
                    "through_sequence", "lease_digest");
            UUID installationId = uuid(frame, "installation_id");
            UUID streamEpoch = uuid(frame, "stream_epoch");
            UUID leaseId = uuid(frame, "lease_id");
            long through = nonNegative(integer(frame, "through_sequence"));
            byte[] digest = bytes32(frame, "lease_digest");
            ReadReceiptProtocolV2.State current = requiredState(stream.currentState());
            String classification = ReadReceiptProtocolV2.classifyAck(current.activeLease,
                    installationId, current.installationId, streamEpoch, current.streamEpoch,
                    leaseId, through, digest);
            if (!"accepted".equals(classification)) {
                stream.onNack(nackCategory(classification), false);
                return closeRejected();
            }
            AckOutcome outcome = stream.acceptAck(
                    new Ack(installationId, streamEpoch, leaseId,
                            through, digest));
            if (outcome != AckOutcome.ACCEPTED) {
                if (outcome == AckOutcome.STORAGE_UNAVAILABLE) {
                    stream.requestRetry();
                } else {
                    stream.onNack(ackCategory(outcome), false);
                }
                return closeRejected();
            }
            OutboundFrame next = nextOutbound();
            return next == null ? Collections.emptyList() : Collections.singletonList(next);
        }

        private List<OutboundFrame> onNack(Map<String, Object> frame) {
            exactKeys(frame, "type", "category", "retryable");
            String category = string(frame, "category");
            boolean retryable = bool(frame, "retryable");
            if (!ReadReceiptProtocolV2.validNack(category, retryable)) return closeRejected();
            stream.onNack(category, retryable);
            if (retryable) stream.requestRetry();
            closed = true;
            return Collections.emptyList();
        }

        private OutboundFrame nextOutbound() {
            LeaseFrame lease = stream.claimOrReplayLease();
            if (lease != null) {
                byte[] reproduced = ReadReceiptProtocolV2.leaseDigest(lease.lease);
                ReadReceiptProtocolV2.State current = requiredState(stream.currentState());
                String classification = ReadReceiptProtocolV2.classifyAck(current.activeLease,
                        lease.lease.installationId, current.installationId,
                        lease.lease.streamEpoch, current.streamEpoch, lease.lease.leaseId,
                        lease.lease.lastSequence, lease.digest);
                if (!constantTimeEquals(reproduced, lease.digest)
                        || !"accepted".equals(classification)) {
                    String category = "accepted".equals(classification)
                            ? "digest_mismatch" : nackCategory(classification);
                    stream.onNack(category, false);
                    return closeRejected();
                }
                return stream(lease(lease));
            }
            StateUpdate update = stream.stateUpdate();
            closed = true;
            if (update == null) return null;
            if (update.state.activeLease != null) throw rejected();
            if (!"active".equals(update.state.streamStatus)
                    || !constantTimeEquals(update.digest,
                    ReadReceiptProtocolV2.stateDigest(update.state))) {
                stream.onNack("digest_mismatch", false);
                return closeRejected();
            }
            return stream(stateUpdate(update));
        }

        private <T> T closeRejected() {
            closed = true;
            destroy();
            throw rejected();
        }

        void destroy() {
            Arrays.fill(token, (byte) 0);
            clear(clientNonce);
            clear(serverNonce);
            clear(stateDigest);
        }

        private static void clear(byte[] value) {
            if (value != null) Arrays.fill(value, (byte) 0);
        }
    }

    static boolean constantTimeEquals(byte[] left, byte[] right) {
        return left != null && right != null && left.length == 32 && right.length == 32
                && MessageDigest.isEqual(left, right);
    }

    private static String handshakeFailure(ReadReceiptProtocolV2.State sender, UUID knownEpoch,
                                           long cursor, String peerStatus) {
        if ("poisoned".equals(sender.streamStatus) || "poisoned".equals(peerStatus)
                || sender.activeLease != null && "poisoned".equals(sender.activeLease.state)) {
            return "stream_poisoned";
        }
        if ("new".equals(peerStatus)) {
            return knownEpoch == null && cursor == 0 && sender.lastAckedSequence == 0
                    ? null : "stream_rollback";
        }
        if (knownEpoch == null) return "stream_rollback";
        if (!sender.streamEpoch.equals(knownEpoch)) return "unknown_epoch";
        if (cursor > sender.highestIssuedSequence) return "stream_rollback";
        if (cursor == sender.lastAckedSequence) return null;
        if (sender.activeLease != null && "active".equals(sender.activeLease.state)
                && cursor == sender.activeLease.lastSequence
                && cursor > sender.lastAckedSequence) return null;
        return "stream_rollback";
    }

    private static String nackCategory(String classification) {
        String prefix = "nack:";
        String suffix = ":false";
        if (!classification.startsWith(prefix) || !classification.endsWith(suffix)) {
            throw rejected();
        }
        return classification.substring(prefix.length(), classification.length() - suffix.length());
    }

    private static String ackCategory(AckOutcome outcome) {
        if (outcome == AckOutcome.LEASE_MISMATCH) {
            return "lease_mismatch";
        }
        if (outcome == AckOutcome.RANGE_MISMATCH) {
            return "range_mismatch";
        }
        if (outcome == AckOutcome.DIGEST_MISMATCH) {
            return "digest_mismatch";
        }
        throw rejected();
    }

    private static Map<String, Object> lease(LeaseFrame frame) {
        ReadReceiptProtocolV2.Lease lease = frame.lease;
        Map<String, Object> result = map();
        result.put("type", "stream_batch");
        result.put("protocol_version", 2L);
        result.put("installation_id", lease.installationId.toString());
        result.put("stream_epoch", lease.streamEpoch.toString());
        result.put("lease_id", lease.leaseId.toString());
        result.put("first_sequence", lease.firstSequence);
        result.put("last_sequence", lease.lastSequence);
        result.put("previous_sequence", lease.previousSequence);
        result.put("counters", counters(lease.counters));
        List<Object> batches = new ArrayList<>();
        for (ReadReceiptProtocolV2.Metadata metadata : lease.batches) {
            batches.add(metadata(metadata));
        }
        result.put("batches", batches);
        List<Object> events = new ArrayList<>();
        for (ReadReceiptProtocolV2.Event event : lease.events) events.add(event(event));
        result.put("events", events);
        List<Object> losses = new ArrayList<>();
        for (ReadReceiptProtocolV2.Loss loss : lease.losses) losses.add(loss(loss));
        result.put("loss_segments", losses);
        result.put("lease_digest", hex(frame.digest));
        return result;
    }

    private static Map<String, Object> stateUpdate(StateUpdate update) {
        Map<String, Object> result = state(update.state);
        LinkedHashMap<String, Object> frame = map();
        frame.put("type", "state_update");
        frame.put("protocol_version", 2L);
        frame.putAll(result);
        frame.put("state_digest", hex(update.digest));
        return frame;
    }

    private static Map<String, Object> state(ReadReceiptProtocolV2.State state) {
        Map<String, Object> result = map();
        result.put("installation_id", state.installationId.toString());
        result.put("stream_epoch", state.streamEpoch.toString());
        result.put("last_acked_sequence", state.lastAckedSequence);
        result.put("highest_issued_sequence", state.highestIssuedSequence);
        result.put("active_lease", activeLease(state.activeLease));
        result.put("counters", counters(state.counters));
        result.put("stream_status", state.streamStatus);
        return result;
    }

    private static Map<String, Object> activeLease(ReadReceiptProtocolV2.ActiveLease lease) {
        if (lease == null) return null;
        Map<String, Object> result = map();
        result.put("lease_id", lease.leaseId.toString());
        result.put("first_sequence", lease.firstSequence);
        result.put("last_sequence", lease.lastSequence);
        result.put("lease_digest", hex(lease.leaseDigest));
        result.put("state", lease.state);
        return result;
    }

    private static Map<String, Object> counters(ReadReceiptProtocolV2.Counters counters) {
        Map<String, Object> result = map();
        result.put("confirmed_dropped_event_count", counters.confirmedDroppedEventCount);
        result.put("confirmed_dropped_batch_count", counters.confirmedDroppedBatchCount);
        result.put("uncertain_outcome_event_count", counters.uncertainOutcomeEventCount);
        result.put("uncertain_outcome_batch_count", counters.uncertainOutcomeBatchCount);
        result.put("capture_drop_event_count", counters.captureDropEventCount);
        result.put("capture_drop_batch_count", counters.captureDropBatchCount);
        return result;
    }

    private static Map<String, Object> metadata(ReadReceiptProtocolV2.Metadata metadata) {
        Map<String, Object> result = map();
        result.put("batch_id", metadata.batchId.toString());
        result.put("first_sequence", metadata.firstSequence);
        result.put("last_sequence", metadata.lastSequence);
        result.put("event_count", metadata.eventCount);
        result.put("persisted_at_ms", metadata.persistedAtMs);
        result.put("persisted_elapsed_ms", metadata.persistedElapsedMs);
        result.put("capture_session_id", metadata.captureSessionId.toString());
        result.put("capture_boot_id", nullableUuid(metadata.captureBootId));
        result.put("source_epoch_token", nullableUuid(metadata.sourceEpochToken));
        result.put("metadata_digest", hex(ReadReceiptProtocolV2.metadataDigest(metadata)));
        return result;
    }

    private static Map<String, Object> event(ReadReceiptProtocolV2.Event event) {
        Map<String, Object> result = map();
        result.put("sequence", event.sequence);
        result.put("event_id", event.eventId.toString());
        result.put("batch_id", event.batchId.toString());
        result.put("chat_id", event.chatId);
        result.put("user_id", event.userId);
        result.put("watermark", event.watermark);
        return result;
    }

    private static Map<String, Object> loss(ReadReceiptProtocolV2.Loss loss) {
        Map<String, Object> result = map();
        result.put("loss_receipt_id", loss.lossReceiptId.toString());
        result.put("first_sequence", loss.firstSequence);
        result.put("last_sequence", loss.lastSequence);
        result.put("dropped_event_count", loss.droppedEventCount);
        result.put("dropped_batch_count", loss.droppedBatchCount);
        result.put("batch_id", nullableUuid(loss.batchId));
        result.put("reason", loss.reason);
        result.put("persisted_at_ms", loss.persistedAtMs);
        result.put("persisted_elapsed_ms", loss.persistedElapsedMs);
        result.put("capture_session_id", nullableUuid(loss.captureSessionId));
        result.put("capture_boot_id", nullableUuid(loss.captureBootId));
        result.put("source_epoch_token", nullableUuid(loss.sourceEpochToken));
        result.put("created_at_ms", loss.createdAtMs);
        result.put("content_digest", hex(ReadReceiptProtocolV2.lossDigest(loss)));
        return result;
    }

    private static Map<String, Object> nack(String category, boolean retryable) {
        Map<String, Object> result = map();
        result.put("type", "nack");
        result.put("category", category);
        result.put("retryable", retryable);
        return result;
    }

    private static OutboundFrame control(Map<String, Object> value) {
        return outbound(value, false);
    }

    private static OutboundFrame stream(Map<String, Object> value) {
        return outbound(value, true);
    }

    private static OutboundFrame outbound(Map<String, Object> value, boolean stream) {
        byte[] payload = ReadReceiptV2OutboxServerJson.encode(value);
        if (!ReadReceiptProtocolV2.frameAllowed(payload.length, stream)) throw rejected();
        return new OutboundFrame(payload, stream);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(byte[] json) {
        try {
            Object value = ReadReceiptV2OutboxServerJson.parse(json);
            if (!(value instanceof Map)) throw rejected();
            return (Map<String, Object>) value;
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private static void exactKeys(Map<String, Object> object, String... keys) {
        Set<String> expected = new HashSet<>(Arrays.asList(keys));
        if (object.size() != expected.size() || !object.keySet().equals(expected)) {
            throw rejected();
        }
    }

    private static void protocolVersion(Map<String, Object> object) {
        if (integer(object, "protocol_version") != 2) throw rejected();
    }

    private static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String)) throw rejected();
        return (String) value;
    }

    private static long integer(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Long)) throw rejected();
        return (Long) value;
    }

    private static boolean bool(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw rejected();
        return (Boolean) value;
    }

    private static UUID uuid(Map<String, Object> object, String key) {
        try {
            return ReadReceiptProtocolV2.canonicalUuid(string(object, key));
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private static UUID nullableUuid(Map<String, Object> object, String key) {
        if (!object.containsKey(key)) throw rejected();
        Object value = object.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw rejected();
        try {
            return ReadReceiptProtocolV2.canonicalUuid((String) value);
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static byte[] bytes32(Map<String, Object> object, String key) {
        try {
            byte[] value = ReadReceiptProtocolV2.fromHex(string(object, key));
            if (value.length != 32) throw rejected();
            return value;
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private static long nonNegative(long value) {
        if (value < 0) throw rejected();
        return value;
    }

    private static ReadReceiptProtocolV2.State requiredState(ReadReceiptProtocolV2.State state) {
        if (state == null) throw rejected();
        return state;
    }

    private static String hex(byte[] value) {
        return ReadReceiptProtocolV2.toHex(value);
    }

    private static LinkedHashMap<String, Object> map() {
        return new LinkedHashMap<>();
    }

    static final class ProtocolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProtocolException() {
            super("protocol rejected");
        }
    }

    private static final class AndroidListenerFactory implements ListenerFactory {
        @Override
        public Listener bind(String endpoint) throws IOException {
            Object socket = null;
            try {
                Class<?> type = Class.forName("android.net.LocalServerSocket");
                Constructor<?> constructor = type.getConstructor(String.class);
                socket = constructor.newInstance(endpoint);
                return new AndroidListener(socket);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                closeReflected(socket);
                throw socketFailure();
            } catch (IOException exception) {
                closeReflected(socket);
                throw exception;
            }
        }
    }

    private static final class AndroidListener implements Listener {
        private final Object socket;
        private final Method accept;
        private final Method close;
        private final Method fileDescriptor;

        AndroidListener(Object socket) throws IOException {
            this.socket = socket;
            try {
                Class<?> type = socket.getClass();
                accept = type.getMethod("accept");
                close = type.getMethod("close");
                fileDescriptor = type.getMethod("getFileDescriptor");
            } catch (ReflectiveOperationException exception) {
                throw socketFailure();
            }
        }

        @Override
        public Connection accept(int timeoutMs) throws IOException {
            if (!poll((FileDescriptor) invoke(fileDescriptor, socket), "POLLIN", timeoutMs)) {
                return null;
            }
            Object accepted = invoke(accept, socket);
            try {
                return new AndroidConnection(accepted);
            } catch (IOException exception) {
                closeReflected(accepted);
                throw exception;
            }
        }

        @Override
        public void close() throws IOException {
            invoke(close, socket);
        }
    }

    private static final class AndroidConnection implements Connection {
        private final Object socket;
        private final Method close;
        private final Method credentials;
        private final Method input;
        private final Method setReadTimeout;
        private final Method fileDescriptor;

        AndroidConnection(Object socket) throws IOException {
            this.socket = socket;
            try {
                Class<?> type = socket.getClass();
                close = type.getMethod("close");
                credentials = type.getMethod("getPeerCredentials");
                input = type.getMethod("getInputStream");
                setReadTimeout = type.getMethod("setSoTimeout", int.class);
                fileDescriptor = type.getMethod("getFileDescriptor");
            } catch (ReflectiveOperationException exception) {
                throw socketFailure();
            }
        }

        @Override
        public int peerUid() throws IOException {
            Object peer = invoke(credentials, socket);
            try {
                return (Integer) peer.getClass().getMethod("getUid").invoke(peer);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throw socketFailure();
            }
        }

        @Override
        public int read(byte[] target, int offset, int length, int timeoutMs)
                throws IOException {
            invoke(setReadTimeout, socket, positiveTimeout(timeoutMs));
            return ((InputStream) invoke(input, socket)).read(target, offset, length);
        }

        @Override
        public int write(byte[] source, int offset, int length, int timeoutMs)
                throws IOException {
            FileDescriptor descriptor = (FileDescriptor) invoke(fileDescriptor, socket);
            if (!poll(descriptor, "POLLOUT", positiveTimeout(timeoutMs))) {
                throw frameDeadlineExceeded();
            }
            return sendNonBlocking(descriptor, source, offset, length);
        }

        @Override
        public void close() throws IOException {
            invoke(close, socket);
        }
    }

    private static boolean poll(FileDescriptor descriptor, String event, int timeoutMs)
            throws IOException {
        try {
            Class<?> pollType = Class.forName("android.system.StructPollfd");
            Object poll = pollType.getConstructor().newInstance();
            Field descriptorField = pollType.getField("fd");
            Field eventsField = pollType.getField("events");
            Field returnedEventsField = pollType.getField("revents");
            descriptorField.set(poll, descriptor);
            int requested = osConstant(event);
            eventsField.setShort(poll, (short) requested);
            Object array = Array.newInstance(pollType, 1);
            Array.set(array, 0, poll);
            Class<?> os = Class.forName("android.system.Os");
            Method method = os.getMethod("poll", array.getClass(), int.class);
            int ready = (Integer) method.invoke(null, array, timeoutMs);
            return ready > 0 && (returnedEventsField.getShort(poll) & requested) != 0;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw socketFailure();
        }
    }

    private static int sendNonBlocking(FileDescriptor descriptor, byte[] source,
                                       int offset, int length) throws IOException {
        try {
            Class<?> os = Class.forName("android.system.Os");
            Method method = os.getMethod("sendto", FileDescriptor.class, byte[].class,
                    int.class, int.class, int.class, InetAddress.class, int.class);
            return (Integer) method.invoke(null, descriptor, source, offset, length,
                    LINUX_MSG_DONTWAIT, null, 0);
        } catch (InvocationTargetException exception) {
            if (isAgain(exception.getCause())) return 0;
            throw socketFailure();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw socketFailure();
        }
    }

    private static int errno(Throwable exception) {
        if (exception == null
                || !"android.system.ErrnoException".equals(exception.getClass().getName())) {
            return Integer.MIN_VALUE;
        }
        try {
            return exception.getClass().getField("errno").getInt(exception);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean isAgain(Throwable exception) throws IOException {
        try {
            return errno(exception) == osConstant("EAGAIN");
        } catch (ReflectiveOperationException reflectionFailure) {
            throw socketFailure();
        }
    }

    private static int osConstant(String name) throws ReflectiveOperationException {
        Class<?> constants = Class.forName("android.system.OsConstants");
        return constants.getField(name).getInt(null);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws IOException {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            throw socketFailure();
        }
    }

    private static void closeReflected(Object socket) {
        if (socket == null) return;
        try {
            socket.getClass().getMethod("close").invoke(socket);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static IOException socketFailure() {
        return new IOException("socket operation failed");
    }
}
