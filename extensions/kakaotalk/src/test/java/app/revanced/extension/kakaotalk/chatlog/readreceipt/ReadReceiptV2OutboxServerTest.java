package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReadReceiptV2OutboxServerTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);
    private static final UUID LEASE = uuid(3);
    private static final byte[] TOKEN = filled(7);
    private static final byte[] CLIENT_NONCE = filled(11);
    private static final byte[] SERVER_NONCE = filled(13);

    @Test
    public void endpointIsDeterministicTokenDerivedAndDoesNotContainRawToken() {
        String first = ReadReceiptV2OutboxServer.deriveSocketName(TOKEN);
        String second = ReadReceiptV2OutboxServer.deriveSocketName(TOKEN.clone());
        byte[] other = TOKEN.clone();
        other[0] ^= 1;

        assertTrue("endpoint must be deterministic", first.equals(second));
        assertFalse("different token must not share endpoint",
                first.equals(ReadReceiptV2OutboxServer.deriveSocketName(other)));
        assertTrue("endpoint prefix missing", first.startsWith("iris.read-receipts.v2."));
        assertEquals("endpoint length mismatch", 86, first.length());
        String vector = "iris.read-receipts.v2."
                + "78a9d796b9684d8e987fab248be1d78f77b9506d8bf4b0f726c0290e22a0953a";
        assertTrue("cross-language endpoint vector mismatch", MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                vector.getBytes(StandardCharsets.US_ASCII)));
        assertFalse("abstract endpoint included a display marker", first.startsWith("@"));
        assertFalse("raw token leaked into endpoint",
                first.contains(ReadReceiptProtocolV2.toHex(TOKEN)));
    }

    @Test
    public void framePrefixIsRejectedBeforePayloadAllocationOrRead() throws Exception {
        byte[] oversized = ReadReceiptProtocolV2.encodeFrameLength(
                ReadReceiptProtocolV2.CONTROL_FRAME_MAX + 1L);
        PrefixOnlyFrameIo input = new PrefixOnlyFrameIo(oversized);
        FakeMonotonicClock clock = new FakeMonotonicClock();

        expectProtocolFailure(() -> ReadReceiptV2OutboxServer.readFrame(
                input, false, 1_000, clock));

        assertFalse("payload was read after oversized prefix", input.payloadRead);
        expectProtocolFailure(() -> ReadReceiptV2OutboxServer.readFrame(
                new ByteArrayFrameIo(new byte[]{0, 0, 0, 0}), false, 1_000, clock));
        expectProtocolFailure(() -> ReadReceiptV2OutboxServer.readFrame(
                new ByteArrayFrameIo(new byte[]{0, 4, 0, 1}), true, 1_000, clock));
    }

    @Test
    public void frameRoundTripUsesUnsignedBigEndianBounds() throws Exception {
        byte[] control = "{}".getBytes(StandardCharsets.UTF_8);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ByteArrayFrameIo output = new ByteArrayFrameIo(new byte[0]);

        ReadReceiptV2OutboxServer.writeFrame(output, control, false, 1_000, clock);
        byte[] decoded = ReadReceiptV2OutboxServer.readFrame(
                new ByteArrayFrameIo(output.output.toByteArray()), false, 1_000, clock);

        assertTrue("framed payload changed", MessageDigest.isEqual(control, decoded));
    }

    @Test
    public void androidAdapterDoesNotReferencePostApi28TimeoutSymbols() throws Exception {
        String constants = classConstants("")
                + classConstants("$AndroidListenerFactory")
                + classConstants("$AndroidListener")
                + classConstants("$AndroidConnection");

        assertFalse("API 29 StructTimeval reference", constants.contains("StructTimeval"));
        assertFalse("API 29 setsockoptTimeval reference",
                constants.contains("setsockoptTimeval"));
        assertFalse("API 29 SO_SNDTIMEO path", constants.contains("SO_SNDTIMEO"));
        assertFalse("API 30 fcntlInt path", constants.contains("fcntlInt"));
        assertFalse("blocking LocalSocket output path", constants.contains("getOutputStream"));
        assertTrue("API 21 poll path missing", constants.contains("StructPollfd"));
        assertTrue("API 21 nonblocking send path missing", constants.contains("sendto"));
        assertTrue("API 1 remaining read-timeout path missing",
                constants.contains("setSoTimeout"));
    }

    private static String classConstants(String suffix) throws IOException {
        String resource = "/" + ReadReceiptV2OutboxServer.class.getName().replace('.', '/')
                + suffix + ".class";
        try (InputStream input = ReadReceiptV2OutboxServer.class.getResourceAsStream(resource)) {
            assertNotNull("server bytecode unavailable: " + suffix, input);
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    public void frameReadUsesOneAbsoluteDeadlineAcrossPartialReads() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TrickleFrameIo io = new TrickleFrameIo(clock,
                new byte[]{0, 0, 0, 1, '{'}, 400);

        expectIoFailure(() -> ReadReceiptV2OutboxServer.readFrame(
                io, false, 1_000, clock));

        assertEquals(3, io.readCalls);
        assertEquals(Arrays.asList(1_000, 600, 200), io.readTimeouts);
    }

    @Test
    public void frameWriteUsesOneAbsoluteDeadlineAcrossPartialWrites() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TrickleFrameIo io = new TrickleFrameIo(clock, new byte[0], 400);

        expectIoFailure(() -> ReadReceiptV2OutboxServer.writeFrame(
                io, utf8("{}"), false, 1_000, clock));

        assertEquals(3, io.writeCalls);
        assertEquals(Arrays.asList(1_000, 600, 200), io.writeTimeouts);
    }

    @Test
    public void strictJsonRejectsDuplicateUnknownNonIntegerAndInvalidUtf8BeforeMutation() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session duplicateSession = session(stream);

        expectProtocolFailure(() -> duplicateSession.onFrame(utf8("{\"type\":\"hello\","
                + "\"type\":\"hello\",\"protocol_version\":2,\"client_nonce\":\""
                + hex(CLIENT_NONCE) + "\"}")));
        assertEquals(0, stream.claimCalls);

        ReadReceiptV2OutboxServer.Session unknownSession = session(stream);
        expectProtocolFailure(() -> unknownSession.onFrame(utf8("{\"type\":\"hello\","
                + "\"protocol_version\":2,\"client_nonce\":\"" + hex(CLIENT_NONCE)
                + "\",\"unknown\":0}")));
        assertEquals(0, stream.claimCalls);

        ReadReceiptV2OutboxServer.Session numericSession = session(stream);
        expectProtocolFailure(() -> numericSession.onFrame(utf8("{\"type\":\"hello\","
                + "\"protocol_version\":2.0,\"client_nonce\":\""
                + hex(CLIENT_NONCE) + "\"}")));
        assertEquals(0, stream.claimCalls);

        ReadReceiptV2OutboxServer.Session utf8Session = session(stream);
        expectProtocolFailure(() -> utf8Session.onFrame(new byte[]{(byte) 0xc3, 0x28}));
        assertEquals(0, stream.claimCalls);
    }

    @Test
    public void handshakeUsesDirectionSeparatedProofsAndStrictPhases() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session outOfOrder = session(stream);
        expectProtocolFailure(() -> outOfOrder.onFrame(utf8("{\"type\":\"ack\"}")));
        assertEquals(0, stream.claimCalls);

        ReadReceiptV2OutboxServer.Session session = session(stream);

        List<ReadReceiptV2OutboxServer.OutboundFrame> challenge = session.onFrame(hello());
        assertEquals(1, challenge.size());
        Map<String, Object> challengeJson = object(challenge.get(0).payload);
        assertEquals("challenge", challengeJson.get("type"));
        assertEquals("await_auth", session.phase());

        byte[] stateDigest = hexBytes((String) challengeJson.get("state_digest"));
        byte[] serverProof = hexBytes((String) challengeJson.get("server_proof"));
        byte[] expectedServer = ReadReceiptProtocolV2.serverProof(TOKEN, CLIENT_NONCE,
                SERVER_NONCE, INSTALLATION, stateDigest);
        assertTrue("server proof mismatch", MessageDigest.isEqual(expectedServer, serverProof));

        String wrongDirection = auth(null, 0, "new", serverProof);
        expectProtocolFailure(() -> session.onFrame(utf8(wrongDirection)));
        assertEquals(0, stream.claimCalls);
    }

    @Test
    public void authenticatedSessionSendsFrozenLeaseAndAcceptsOnlyExactFullAck() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session session = session(stream);
        List<ReadReceiptV2OutboxServer.OutboundFrame> outbound = authenticate(session,
                null, 0, "new");

        assertEquals(2, outbound.size());
        assertEquals("authenticated", type(outbound.get(0)));
        assertEquals("stream_batch", type(outbound.get(1)));
        assertTrue("stream frame was misclassified", outbound.get(1).stream);
        assertEquals(1, stream.claimCalls);

        byte[] digest = stream.frame.digest;
        String ack = "{\"type\":\"ack\",\"installation_id\":\"" + INSTALLATION
                + "\",\"stream_epoch\":\"" + EPOCH + "\",\"lease_id\":\"" + LEASE
                + "\",\"through_sequence\":1,\"lease_digest\":\"" + hex(digest) + "\"}";
        List<ReadReceiptV2OutboxServer.OutboundFrame> afterAck = session.onFrame(utf8(ack));

        assertEquals(1, stream.ackCalls);
        assertEquals(1, stream.lastAck.throughSequence);
        assertTrue("ACK digest changed", MessageDigest.isEqual(digest, stream.lastAck.digest));
        assertEquals(1, afterAck.size());
        assertEquals("state_update", type(afterAck.get(0)));
    }

    @Test
    public void authenticatedSessionFramesFrozen256EventLeaseWithinHostBound() throws Exception {
        FakeStream stream = streamWithEventLease(256);
        LeaseFrame frozen = stream.frame;
        byte[] frozenDigest = frozen.digest.clone();
        byte[] stateDigest = ReadReceiptProtocolV2.stateDigest(stream.state);
        byte[] proof = ReadReceiptProtocolV2.clientProof(TOKEN, CLIENT_NONCE, SERVER_NONCE,
                INSTALLATION, EPOCH, stateDigest, null, 0, "new");
        byte[] helloFrame = framed(hello());
        byte[] authFrame = framed(utf8(auth(null, 0, "new", proof)));
        byte[] inbound = new byte[helloFrame.length + authFrame.length];
        System.arraycopy(helloFrame, 0, inbound, 0, helloFrame.length);
        System.arraycopy(authFrame, 0, inbound, helloFrame.length, authFrame.length);
        FakeConnection connection = new FakeConnection(0, inbound);

        long started = System.nanoTime();
        ReadReceiptV2OutboxServer.handleConnection(connection, TOKEN, stream,
                output -> System.arraycopy(SERVER_NONCE, 0, output, 0, output.length),
                5_000, 5_000);
        long elapsedNanos = System.nanoTime() - started;

        byte[] wire = connection.output.toByteArray();
        ByteArrayFrameIo reader = new ByteArrayFrameIo(wire);
        byte[] challenge = ReadReceiptV2OutboxServer.readFrame(
                reader, false, 5_000, new FakeMonotonicClock());
        byte[] authenticated = ReadReceiptV2OutboxServer.readFrame(
                reader, false, 5_000, new FakeMonotonicClock());
        byte[] streamPayload = ReadReceiptV2OutboxServer.readFrame(
                reader, true, 5_000, new FakeMonotonicClock());

        int authenticatedOffset = 4 + challenge.length;
        int streamOffset = authenticatedOffset + 4 + authenticated.length;
        assertFramePrefix(wire, 0, challenge.length);
        assertFramePrefix(wire, authenticatedOffset, authenticated.length);
        assertFramePrefix(wire, streamOffset, streamPayload.length);
        assertEquals("wire contains trailing or missing bytes",
                streamOffset + 4 + streamPayload.length, wire.length);
        assertTrue("frame reader did not consume the exact wire output", reader.inputConsumed());
        assertEquals("challenge", object(challenge).get("type"));
        assertEquals("authenticated", object(authenticated).get("type"));
        assertTrue("256-event payload did not require the stream-frame bound",
                streamPayload.length > ReadReceiptProtocolV2.CONTROL_FRAME_MAX);
        assertTrue("256-event payload exceeded the stream-frame bound",
                streamPayload.length <= ReadReceiptProtocolV2.STREAM_FRAME_MAX);

        Map<String, Object> batch = object(streamPayload);
        assertEquals(13, batch.size());
        assertEquals("stream_batch", batch.get("type"));
        assertEquals(2L, batch.get("protocol_version"));
        assertEquals(INSTALLATION.toString(), batch.get("installation_id"));
        assertEquals(EPOCH.toString(), batch.get("stream_epoch"));
        assertEquals(LEASE.toString(), batch.get("lease_id"));
        assertEquals(1L, batch.get("first_sequence"));
        assertEquals(256L, batch.get("last_sequence"));
        assertEquals(0L, batch.get("previous_sequence"));
        assertEquals(hex(frozenDigest), batch.get("lease_digest"));
        Map<String, Object> counters = jsonObject(batch.get("counters"));
        assertEquals(6, counters.size());
        assertEquals(0L, counters.get("confirmed_dropped_event_count"));
        assertEquals(0L, counters.get("confirmed_dropped_batch_count"));
        assertEquals(0L, counters.get("uncertain_outcome_event_count"));
        assertEquals(0L, counters.get("uncertain_outcome_batch_count"));
        assertEquals(0L, counters.get("capture_drop_event_count"));
        assertEquals(0L, counters.get("capture_drop_batch_count"));
        assertTrue("loss segments were added to an event-only lease",
                jsonList(batch, "loss_segments").isEmpty());

        List<Object> batches = jsonList(batch, "batches");
        assertEquals(1, batches.size());
        Map<String, Object> metadata = jsonObject(batches.get(0));
        assertEquals(10, metadata.size());
        assertEquals(uuid(4).toString(), metadata.get("batch_id"));
        assertEquals(1L, metadata.get("first_sequence"));
        assertEquals(256L, metadata.get("last_sequence"));
        assertEquals(256L, metadata.get("event_count"));
        assertEquals(10L, metadata.get("persisted_at_ms"));
        assertEquals(11L, metadata.get("persisted_elapsed_ms"));
        assertEquals(uuid(5).toString(), metadata.get("capture_session_id"));
        assertNull(metadata.get("capture_boot_id"));
        assertNull(metadata.get("source_epoch_token"));
        assertEquals(hex(ReadReceiptProtocolV2.metadataDigest(frozen.lease.batches.get(0))),
                metadata.get("metadata_digest"));

        List<Object> events = jsonList(batch, "events");
        assertEquals(256, events.size());
        for (int index = 0; index < events.size(); index++) {
            long sequence = index + 1L;
            Map<String, Object> event = jsonObject(events.get(index));
            assertEquals(6, event.size());
            assertEquals(sequence, event.get("sequence"));
            assertEquals(uuid(10_000L + sequence).toString(), event.get("event_id"));
            assertEquals(uuid(4).toString(), event.get("batch_id"));
            assertEquals(1_000_000L + sequence, event.get("chat_id"));
            assertEquals(2_000_000L + sequence, event.get("user_id"));
            assertEquals(3_000_000L + sequence, event.get("watermark"));
        }

        assertEquals(1, stream.claimCalls);
        assertEquals(0, stream.ackCalls);
        assertTrue("authenticated connection remained open", connection.closed);
        assertTrue("transfer replaced the frozen lease", stream.frame == frozen);
        assertTrue("transfer changed the frozen lease digest",
                MessageDigest.isEqual(frozenDigest, stream.frame.digest));
        assertTrue("256-event host-local transfer exceeded the 5-second functional bound; "
                        + "this check is not Android p99 evidence",
                elapsedNanos < TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    public void durableAckStorageFailureClosesAndRequestsRetryWithoutPoisoning() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session session = session(stream);
        authenticate(session, null, 0, "new");
        stream.forcedAckOutcome = AckOutcome.STORAGE_UNAVAILABLE;
        String ack = "{\"type\":\"ack\",\"installation_id\":\"" + INSTALLATION
                + "\",\"stream_epoch\":\"" + EPOCH + "\",\"lease_id\":\"" + LEASE
                + "\",\"through_sequence\":1,\"lease_digest\":\""
                + hex(stream.frame.digest) + "\"}";

        expectProtocolFailure(() -> session.onFrame(utf8(ack)));

        assertEquals(1, stream.ackCalls);
        assertEquals(1, stream.retryCalls);
        assertEquals(0, stream.nackCalls);
        assertNotNull(stream.frame);
    }

    @Test
    public void nonExactAckIsRejectedBeforeAckMutationAndInvalidNackIsIgnored() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session ackSession = session(stream);
        authenticate(ackSession, null, 0, "new");
        String partial = "{\"type\":\"ack\",\"installation_id\":\"" + INSTALLATION
                + "\",\"stream_epoch\":\"" + EPOCH + "\",\"lease_id\":\"" + LEASE
                + "\",\"through_sequence\":0,\"lease_digest\":\""
                + hex(stream.frame.digest) + "\"}";

        expectProtocolFailure(() -> ackSession.onFrame(utf8(partial)));
        assertEquals(0, stream.ackCalls);
        assertEquals(1, stream.nackCalls);
        assertEquals("range_mismatch", stream.lastNackCategory);

        FakeStream invalidNackStream = streamWithLease();
        ReadReceiptV2OutboxServer.Session nackSession = session(invalidNackStream);
        authenticate(nackSession, null, 0, "new");
        expectProtocolFailure(() -> nackSession.onFrame(utf8("{\"type\":\"nack\","
                + "\"category\":\"sequence_hole\",\"retryable\":true}")));
        assertEquals(0, invalidNackStream.nackCalls);
    }

    @Test
    public void poisonedHandshakeReturnsNonRetryableNackBeforeLeaseClaim() {
        FakeStream stream = streamWithLease();
        ReadReceiptProtocolV2.State active = stream.state;
        stream.state = new ReadReceiptProtocolV2.State(active.installationId,
                active.streamEpoch, active.lastAckedSequence, active.highestIssuedSequence,
                active.activeLease, active.counters, "poisoned");
        ReadReceiptV2OutboxServer.Session session = session(stream);

        List<ReadReceiptV2OutboxServer.OutboundFrame> result = authenticate(session,
                null, 0, "new");

        assertEquals(1, result.size());
        Map<String, Object> nack = object(result.get(0).payload);
        assertEquals("nack", nack.get("type"));
        assertEquals("stream_poisoned", nack.get("category"));
        assertEquals(Boolean.FALSE, nack.get("retryable"));
        assertEquals(0, stream.claimCalls);
        assertEquals(1, stream.nackCalls);
    }

    @Test
    public void stateChangeAfterChallengeRejectsAuthAndRequestsRetry() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session session = session(stream);
        Map<String, Object> challenge = object(session.onFrame(hello()).get(0).payload);
        byte[] challengedDigest = hexBytes((String) challenge.get("state_digest"));
        byte[] proof = ReadReceiptProtocolV2.clientProof(TOKEN, CLIENT_NONCE, SERVER_NONCE,
                INSTALLATION, EPOCH, challengedDigest, null, 0, "new");
        ReadReceiptProtocolV2.State active = stream.state;
        stream.state = new ReadReceiptProtocolV2.State(active.installationId,
                active.streamEpoch, active.lastAckedSequence, active.highestIssuedSequence,
                active.activeLease, new ReadReceiptProtocolV2.Counters(1, 0, 0, 0, 0, 0),
                active.streamStatus);

        expectProtocolFailure(() -> session.onFrame(utf8(auth(null, 0, "new", proof))));

        assertEquals(0, stream.claimCalls);
        assertEquals(1, stream.retryCalls);
    }

    @Test
    public void handshakeAcceptsOnlyExactAckLossReplayBoundary() {
        FakeStream replayStream = streamWithLease();
        List<ReadReceiptV2OutboxServer.OutboundFrame> replay = authenticate(
                session(replayStream), EPOCH, 1, "active");

        assertEquals(2, replay.size());
        assertEquals("stream_batch", type(replay.get(1)));
        assertEquals(0, replayStream.nackCalls);

        FakeStream aheadStream = streamWithLease();
        List<ReadReceiptV2OutboxServer.OutboundFrame> rejected = authenticate(
                session(aheadStream), EPOCH, 2, "active");

        assertEquals(1, rejected.size());
        Map<String, Object> nack = object(rejected.get(0).payload);
        assertEquals("stream_rollback", nack.get("category"));
        assertEquals(1, aheadStream.nackCalls);
        assertEquals(0, aheadStream.claimCalls);
    }

    @Test
    public void inconsistentDurableLeaseIsPoisonedBeforeFrameEmission() {
        FakeStream stream = streamWithLease();
        byte[] wrongDigest = stream.frame.digest.clone();
        wrongDigest[0] ^= 1;
        stream.frame = new LeaseFrame(stream.frame.lease, wrongDigest);
        ReadReceiptV2OutboxServer.Session session = session(stream);

        expectProtocolFailure(() -> authenticate(session, null, 0, "new"));

        assertEquals(1, stream.claimCalls);
        assertEquals(1, stream.nackCalls);
        assertEquals("digest_mismatch", stream.lastNackCategory);
    }

    @Test
    public void disconnectAndAckLossReplayIdenticalDurableStreamFrame() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session first = session(stream);
        byte[] firstFrame = authenticate(first, null, 0, "new").get(1).payload;

        ReadReceiptV2OutboxServer.Session reconnected = session(stream);
        byte[] replay = authenticate(reconnected, null, 0, "new").get(1).payload;

        assertTrue("reconnect changed durable stream frame",
                MessageDigest.isEqual(firstFrame, replay));
        assertEquals(2, stream.claimCalls);
        assertEquals(0, stream.ackCalls);
    }

    @Test
    public void stateUpdateIsSentOnlyWhenLeaseIsAbsentAndDoesNotAckCursor() {
        FakeStream stream = streamWithoutLease();
        ReadReceiptV2OutboxServer.Session session = session(stream);

        List<ReadReceiptV2OutboxServer.OutboundFrame> outbound = authenticate(session,
                null, 0, "new");

        assertEquals(2, outbound.size());
        assertEquals("state_update", type(outbound.get(1)));
        assertTrue("state update was misclassified as control", outbound.get(1).stream);
        Map<String, Object> json = object(outbound.get(1).payload);
        assertNull(json.get("active_lease"));
        assertEquals(0L, json.get("last_acked_sequence"));
        assertEquals(0, stream.ackCalls);
        assertTrue("lease-free update left an idle session open", session.closed());
    }

    @Test
    public void nonRootPeerIsClosedBeforeTimeoutsOrReads() throws Exception {
        FakeStream stream = streamWithLease();
        FakeConnection connection = new FakeConnection(2000);

        ReadReceiptV2OutboxServer.handleConnection(connection, TOKEN, stream,
                output -> Arrays.fill(output, (byte) 1), 100, 200);

        assertTrue("non-root peer remained open", connection.closed);
        assertTrue("non-root peer received read I/O", connection.readTimeouts.isEmpty());
        assertTrue("non-root peer received write I/O", connection.writeTimeouts.isEmpty());
        assertEquals(0, connection.reads);
        assertEquals(0, stream.claimCalls);
    }

    @Test
    public void peerCredentialFailureStillClosesBeforeTimeoutsOrReads() {
        FakeStream stream = streamWithLease();
        FakeConnection connection = new FakeConnection(0);
        connection.failPeerUid = true;

        try {
            ReadReceiptV2OutboxServer.handleConnection(connection, TOKEN, stream,
                    output -> Arrays.fill(output, (byte) 1), 100, 200);
            fail("peer credential failure was accepted");
        } catch (IOException expected) {
            assertEquals("peer credentials unavailable", expected.getMessage());
        }

        assertTrue("credential failure left the connection open", connection.closed);
        assertTrue("credential failure received read I/O", connection.readTimeouts.isEmpty());
        assertTrue("credential failure received write I/O", connection.writeTimeouts.isEmpty());
        assertEquals(0, connection.reads);
        assertEquals(0, stream.retryCalls);
    }

    @Test
    public void rootPeerUsesAbsoluteReadDeadlineAndDisconnectRequestsRetry() throws Exception {
        FakeStream stream = streamWithLease();
        FakeConnection connection = new FakeConnection(0);

        ReadReceiptV2OutboxServer.handleConnection(connection, TOKEN, stream,
                output -> Arrays.fill(output, (byte) 1), 123, 456);

        assertTrue("root connection was not closed", connection.closed);
        assertEquals(Collections.singletonList(123), connection.readTimeouts);
        assertTrue("empty root connection unexpectedly wrote", connection.writeTimeouts.isEmpty());
        assertTrue("disconnect did not request autonomous retry", stream.retryCalls > 0);
    }

    @Test
    public void api28CompatibleRootConnectionCompletesHelloChallenge() throws Exception {
        FakeConnection connection = new FakeConnection(0, framed(hello()));
        FakeStream stream = streamWithLease();
        FakeMonotonicClock clock = new FakeMonotonicClock();

        ReadReceiptV2OutboxServer.handleConnection(connection, TOKEN, stream,
                output -> System.arraycopy(SERVER_NONCE, 0, output, 0, output.length),
                1_000, 456, clock);

        byte[] challenge = ReadReceiptV2OutboxServer.readFrame(
                new ByteArrayFrameIo(connection.output.toByteArray()), false, 1_000,
                new FakeMonotonicClock());
        assertEquals("challenge", object(challenge).get("type"));
        assertEquals(Arrays.asList(456, 456), connection.writeTimeouts);
        assertEquals(Arrays.asList(1_000, 1_000, 1_000), connection.readTimeouts);
        assertEquals(0, stream.claimCalls);
        assertTrue("root handshake connection remained open", connection.closed);
    }

    @Test
    public void handshakeDeadlineSpansHelloChallengeAndAuthReads() throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        byte[] stateDigest = ReadReceiptProtocolV2.stateDigest(streamWithLease().state);
        byte[] proof = ReadReceiptProtocolV2.clientProof(TOKEN, CLIENT_NONCE, SERVER_NONCE,
                INSTALLATION, EPOCH, stateDigest, null, 0, "new");
        byte[] helloFrame = framed(hello());
        byte[] authFrame = framed(utf8(auth(null, 0, "new", proof)));
        byte[] input = new byte[helloFrame.length + authFrame.length];
        System.arraycopy(helloFrame, 0, input, 0, helloFrame.length);
        System.arraycopy(authFrame, 0, input, helloFrame.length, authFrame.length);
        FakeConnection connection = new FakeConnection(0, input, clock, 180);
        FakeStream stream = streamWithLease();

        ReadReceiptV2OutboxServer.handleConnection(connection, TOKEN, stream,
                output -> System.arraycopy(SERVER_NONCE, 0, output, 0, output.length),
                1_000, 5_000, clock);

        assertEquals(Arrays.asList(1_000, 820, 280, 100), connection.readTimeouts);
        assertEquals(Arrays.asList(640, 460), connection.writeTimeouts);
        assertEquals(0, stream.claimCalls);
        assertTrue("expired handshake connection remained open", connection.closed);
    }

    @Test
    public void validNackRoutesOnlyAfterRetryabilityValidation() {
        FakeStream stream = streamWithLease();
        ReadReceiptV2OutboxServer.Session session = session(stream);
        authenticate(session, null, 0, "new");

        List<ReadReceiptV2OutboxServer.OutboundFrame> result = session.onFrame(utf8(
                "{\"type\":\"nack\",\"category\":\"schema_unavailable\","
                        + "\"retryable\":true}"));

        assertTrue("NACK emitted an unexpected response", result.isEmpty());
        assertTrue("NACK did not close the session", session.closed());
        assertEquals(1, stream.nackCalls);
        assertEquals(1, stream.retryCalls);
    }

    @Test
    public void singleOwnerAndAcceptTimeoutDriveAutonomousRetry() {
        FakeStream firstStream = streamWithoutLease();
        FakeListener firstListener = new FakeListener();
        FakeListenerFactory firstFactory = new FakeListenerFactory(firstListener);
        CapturingThreadStarter firstThread = new CapturingThreadStarter();
        ReadReceiptV2OutboxServer first = server(firstStream, firstFactory, firstThread);
        firstListener.onAccept = first::close;

        first.start();
        assertEquals(1, firstStream.retryCalls);
        assertNotNull(firstThread.runnable);

        FakeStream blockedStream = streamWithoutLease();
        ReadReceiptV2OutboxServer blocked = server(blockedStream,
                new FakeListenerFactory(new FakeListener()), new CapturingThreadStarter());
        try {
            blocked.start();
            fail("second server owner was accepted");
        } catch (IllegalStateException expected) {
            assertEquals("outbox activation failed", expected.getMessage());
        }
        blocked.close();
        ReadReceiptV2OutboxServer stillBlocked = server(streamWithoutLease(),
                new FakeListenerFactory(new FakeListener()), new CapturingThreadStarter());
        try {
            stillBlocked.start();
            fail("non-owner close released the server owner");
        } catch (IllegalStateException expected) {
            assertEquals("outbox activation failed", expected.getMessage());
        }

        firstThread.runnable.run();
        assertEquals(1_000, firstListener.lastTimeoutMs);
        assertTrue("accept timeout did not request retry", firstStream.retryCalls >= 2);

        FakeStream replacementStream = streamWithoutLease();
        ReadReceiptV2OutboxServer replacement = server(replacementStream,
                new FakeListenerFactory(new FakeListener()), new CapturingThreadStarter());
        replacement.start();
        replacement.close();
        try {
            replacement.start();
            fail("closed server restarted with cleared credentials");
        } catch (IllegalStateException expected) {
            assertEquals("outbox activation failed", expected.getMessage());
        }
    }

    @Test
    public void ownerRemainsHeldUntilWorkerActuallyStops() {
        CapturingThreadStarter worker = new CapturingThreadStarter();
        worker.stopped = false;
        ReadReceiptV2OutboxServer first = server(streamWithoutLease(),
                new FakeListenerFactory(new FakeListener()), worker);
        first.start();
        first.close();
        first.close();

        ReadReceiptV2OutboxServer blocked = server(streamWithoutLease(),
                new FakeListenerFactory(new FakeListener()), new CapturingThreadStarter());
        try {
            blocked.start();
            fail("live worker released the server owner");
        } catch (IllegalStateException expected) {
            assertEquals("outbox activation failed", expected.getMessage());
        }

        worker.runnable.run();
        ReadReceiptV2OutboxServer replacement = server(streamWithoutLease(),
                new FakeListenerFactory(new FakeListener()), new CapturingThreadStarter());
        replacement.start();
        replacement.close();
    }

    @Test
    public void proofComparisonRequiresExactThirtyTwoByteValues() {
        assertTrue("equal proof rejected",
                ReadReceiptV2OutboxServer.constantTimeEquals(filled(1), filled(1)));
        assertFalse("different proof accepted",
                ReadReceiptV2OutboxServer.constantTimeEquals(filled(1), filled(2)));
        assertFalse("short proof accepted",
                ReadReceiptV2OutboxServer.constantTimeEquals(new byte[31], new byte[31]));
    }

    private static ReadReceiptV2OutboxServer server(FakeStream stream,
                                                     FakeListenerFactory factory,
                                                     CapturingThreadStarter starter) {
        return new ReadReceiptV2OutboxServer(TOKEN, stream, factory,
                output -> Arrays.fill(output, (byte) 1), starter,
                1_000, 2_000, 3_000);
    }

    private static ReadReceiptV2OutboxServer.Session session(FakeStream stream) {
        return new ReadReceiptV2OutboxServer.Session(TOKEN, stream,
                output -> System.arraycopy(SERVER_NONCE, 0, output, 0, output.length));
    }

    private static byte[] hello() {
        return utf8("{\"type\":\"hello\",\"protocol_version\":2,\"client_nonce\":\""
                + hex(CLIENT_NONCE) + "\"}");
    }

    private static byte[] framed(byte[] payload) throws IOException {
        ByteArrayFrameIo io = new ByteArrayFrameIo(new byte[0]);
        ReadReceiptV2OutboxServer.writeFrame(io, payload, false, 1_000,
                new FakeMonotonicClock());
        return io.output.toByteArray();
    }

    private static List<ReadReceiptV2OutboxServer.OutboundFrame> authenticate(
            ReadReceiptV2OutboxServer.Session session, UUID knownEpoch, long cursor,
            String status) {
        List<ReadReceiptV2OutboxServer.OutboundFrame> challenge = session.onFrame(hello());
        Map<String, Object> json = object(challenge.get(0).payload);
        byte[] stateDigest = hexBytes((String) json.get("state_digest"));
        byte[] proof = ReadReceiptProtocolV2.clientProof(TOKEN, CLIENT_NONCE, SERVER_NONCE,
                INSTALLATION, EPOCH, stateDigest, knownEpoch, cursor, status);
        return session.onFrame(utf8(auth(knownEpoch, cursor, status, proof)));
    }

    private static String auth(UUID knownEpoch, long cursor, String status, byte[] proof) {
        return "{\"type\":\"auth\",\"protocol_version\":2,\"known_stream_epoch\":"
                + (knownEpoch == null ? "null" : "\"" + knownEpoch + "\"")
                + ",\"last_committed_sequence\":" + cursor + ",\"stream_status\":\""
                + status + "\",\"client_proof\":\"" + hex(proof) + "\"}";
    }

    private static String type(ReadReceiptV2OutboxServer.OutboundFrame frame) {
        return (String) object(frame.payload).get("type");
    }

    private static void assertFramePrefix(byte[] wire, int offset, int payloadLength) {
        assertTrue("frame prefix is outside wire output", offset >= 0 && offset + 4 <= wire.length);
        byte[] prefix = Arrays.copyOfRange(wire, offset, offset + 4);
        assertEquals("frame prefix length mismatch", (long) payloadLength,
                ReadReceiptProtocolV2.decodeFrameLength(prefix));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(byte[] value) {
        Object decoded = ReadReceiptV2OutboxServerJson.parse(value);
        assertTrue("outbound frame is not an object", decoded instanceof Map);
        return (Map<String, Object>) decoded;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObject(Object value) {
        assertTrue("JSON value is not an object", value instanceof Map);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> jsonList(Map<String, Object> object, String key) {
        Object value = object.get(key);
        assertTrue("JSON field is not an array: " + key, value instanceof List);
        return (List<Object>) value;
    }

    private static FakeStream streamWithLease() {
        ReadReceiptProtocolV2.Counters counters = new ReadReceiptProtocolV2.Counters(
                0, 0, 0, 0, 0, 0);
        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                uuid(4), 1, 1, 1, 10, 11, uuid(5), null, null);
        ReadReceiptProtocolV2.Event event = new ReadReceiptProtocolV2.Event(
                1, uuid(6), metadata.batchId, 7, 8, 9);
        ReadReceiptProtocolV2.Lease lease = new ReadReceiptProtocolV2.Lease(
                INSTALLATION, EPOCH, LEASE, 1, 1, 0, counters,
                Collections.singletonList(metadata), Collections.singletonList(event),
                Collections.emptyList());
        byte[] digest = ReadReceiptProtocolV2.leaseDigest(lease);
        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(
                LEASE, 1, 1, digest, "active");
        ReadReceiptProtocolV2.State state = new ReadReceiptProtocolV2.State(
                INSTALLATION, EPOCH, 0, 1, active, counters, "active");
        return new FakeStream(state, new LeaseFrame(lease, digest));
    }

    private static FakeStream streamWithEventLease(int eventCount) {
        ReadReceiptProtocolV2.Counters counters = new ReadReceiptProtocolV2.Counters(
                0, 0, 0, 0, 0, 0);
        UUID batchId = uuid(4);
        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                batchId, 1, eventCount, eventCount, 10, 11, uuid(5), null, null);
        List<ReadReceiptProtocolV2.Event> events = new ArrayList<>(eventCount);
        for (long sequence = 1; sequence <= eventCount; sequence++) {
            events.add(new ReadReceiptProtocolV2.Event(sequence, uuid(10_000L + sequence),
                    batchId, 1_000_000L + sequence, 2_000_000L + sequence,
                    3_000_000L + sequence));
        }
        ReadReceiptProtocolV2.Lease lease = new ReadReceiptProtocolV2.Lease(
                INSTALLATION, EPOCH, LEASE, 1, eventCount, 0, counters,
                Collections.singletonList(metadata), events, Collections.emptyList());
        byte[] digest = ReadReceiptProtocolV2.leaseDigest(lease);
        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(
                LEASE, 1, eventCount, digest, "active");
        ReadReceiptProtocolV2.State state = new ReadReceiptProtocolV2.State(
                INSTALLATION, EPOCH, 0, eventCount, active, counters, "active");
        return new FakeStream(state, new LeaseFrame(lease, digest));
    }

    private static FakeStream streamWithoutLease() {
        ReadReceiptProtocolV2.Counters counters = new ReadReceiptProtocolV2.Counters(
                0, 0, 1, 1, 0, 0);
        ReadReceiptProtocolV2.State state = new ReadReceiptProtocolV2.State(
                INSTALLATION, EPOCH, 0, 0, null, counters, "active");
        return new FakeStream(state, null);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] value) {
        return ReadReceiptProtocolV2.toHex(value);
    }

    private static byte[] hexBytes(String value) {
        return ReadReceiptProtocolV2.fromHex(value);
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    private static void expectProtocolFailure(ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("protocol input was accepted");
        } catch (ReadReceiptV2OutboxServer.ProtocolException expected) {
            assertEquals("protocol rejected", expected.getMessage());
        } catch (Exception exception) {
            throw new AssertionError("unexpected exception category");
        }
    }

    private static void expectIoFailure(ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("bounded I/O accepted an expired deadline");
        } catch (IOException expected) {
            assertEquals("frame deadline exceeded", expected.getMessage());
        } catch (Exception exception) {
            throw new AssertionError("unexpected exception category");
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class PrefixOnlyFrameIo implements ReadReceiptV2OutboxServer.FrameIo {
        private final byte[] prefix;
        private int index;
        boolean payloadRead;

        PrefixOnlyFrameIo(byte[] prefix) {
            this.prefix = prefix;
        }

        @Override
        public int read(byte[] target, int offset, int length, int timeoutMs) {
            if (index < prefix.length) {
                int count = Math.min(length, prefix.length - index);
                System.arraycopy(prefix, index, target, offset, count);
                index += count;
                return count;
            }
            payloadRead = true;
            throw new AssertionError("payload read");
        }

        @Override
        public int write(byte[] source, int offset, int length, int timeoutMs) {
            throw new AssertionError("unexpected write");
        }
    }

    private static final class ByteArrayFrameIo implements ReadReceiptV2OutboxServer.FrameIo {
        private final byte[] input;
        private int inputOffset;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ByteArrayFrameIo(byte[] input) {
            this.input = input.clone();
        }

        boolean inputConsumed() {
            return inputOffset == input.length;
        }

        @Override
        public int read(byte[] target, int offset, int length, int timeoutMs) {
            if (inputOffset == input.length) return -1;
            int count = Math.min(length, input.length - inputOffset);
            System.arraycopy(input, inputOffset, target, offset, count);
            inputOffset += count;
            return count;
        }

        @Override
        public int write(byte[] source, int offset, int length, int timeoutMs) {
            output.write(source, offset, length);
            return length;
        }
    }

    private static final class FakeMonotonicClock
            implements ReadReceiptV2OutboxServer.MonotonicClock {
        long nowMs;

        @Override
        public long nowMs() {
            return nowMs;
        }

        void advance(long elapsedMs) {
            nowMs += elapsedMs;
        }
    }

    private static final class TrickleFrameIo implements ReadReceiptV2OutboxServer.FrameIo {
        private final FakeMonotonicClock clock;
        private final byte[] input;
        private final int elapsedPerCallMs;
        final List<Integer> readTimeouts = new ArrayList<>();
        final List<Integer> writeTimeouts = new ArrayList<>();
        int inputOffset;
        int readCalls;
        int writeCalls;

        TrickleFrameIo(FakeMonotonicClock clock, byte[] input, int elapsedPerCallMs) {
            this.clock = clock;
            this.input = input;
            this.elapsedPerCallMs = elapsedPerCallMs;
        }

        @Override
        public int read(byte[] target, int offset, int length, int timeoutMs) {
            readCalls++;
            readTimeouts.add(timeoutMs);
            clock.advance(elapsedPerCallMs);
            if (inputOffset == input.length) return -1;
            target[offset] = input[inputOffset++];
            return 1;
        }

        @Override
        public int write(byte[] source, int offset, int length, int timeoutMs) {
            writeCalls++;
            writeTimeouts.add(timeoutMs);
            clock.advance(elapsedPerCallMs);
            return 1;
        }
    }

    private static final class FakeStream implements ReadReceiptV2OutboxServer.StreamPort {
        ReadReceiptProtocolV2.State state;
        LeaseFrame frame;
        Ack lastAck;
        AckOutcome forcedAckOutcome;
        String lastNackCategory;
        int claimCalls;
        int ackCalls;
        int nackCalls;
        int retryCalls;

        FakeStream(ReadReceiptProtocolV2.State state, LeaseFrame frame) {
            this.state = state;
            this.frame = frame;
        }

        @Override
        public ReadReceiptProtocolV2.State currentState() {
            return state;
        }

        @Override
        public LeaseFrame claimOrReplayLease() {
            claimCalls++;
            return frame;
        }

        @Override
        public StateUpdate stateUpdate() {
            if (frame != null) return null;
            return new StateUpdate(state,
                    ReadReceiptProtocolV2.stateDigest(state));
        }

        @Override
        public AckOutcome acceptAck(Ack ack) {
            ackCalls++;
            lastAck = ack;
            if (forcedAckOutcome != null) return forcedAckOutcome;
            if (ack.throughSequence != frame.lease.lastSequence
                    || !MessageDigest.isEqual(ack.digest, frame.digest)) {
                return AckOutcome.RANGE_MISMATCH;
            }
            frame = null;
            state = new ReadReceiptProtocolV2.State(INSTALLATION, EPOCH,
                    ack.throughSequence, ack.throughSequence, null, state.counters, "active");
            return AckOutcome.ACCEPTED;
        }

        @Override
        public void onNack(String category, boolean retryable) {
            nackCalls++;
            lastNackCategory = category;
        }

        @Override
        public void requestRetry() {
            retryCalls++;
        }
    }

    private static final class FakeConnection implements ReadReceiptV2OutboxServer.Connection {
        private final int uid;
        private final byte[] input;
        private final FakeMonotonicClock clock;
        private final int elapsedPerIoMs;
        private int inputOffset;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Integer> readTimeouts = new ArrayList<>();
        final List<Integer> writeTimeouts = new ArrayList<>();
        boolean closed;
        int reads;
        boolean failPeerUid;

        FakeConnection(int uid) {
            this(uid, new byte[0]);
        }

        FakeConnection(int uid, byte[] input) {
            this(uid, input, null, 0);
        }

        FakeConnection(int uid, byte[] input, FakeMonotonicClock clock, int elapsedPerIoMs) {
            this.uid = uid;
            this.input = input.clone();
            this.clock = clock;
            this.elapsedPerIoMs = elapsedPerIoMs;
        }

        @Override
        public int peerUid() throws IOException {
            if (failPeerUid) throw new IOException("peer credentials unavailable");
            return uid;
        }

        @Override
        public int read(byte[] target, int offset, int length, int timeoutMs) {
            reads++;
            readTimeouts.add(timeoutMs);
            if (inputOffset == input.length) return -1;
            int count = Math.min(length, input.length - inputOffset);
            System.arraycopy(input, inputOffset, target, offset, count);
            inputOffset += count;
            advanceClock();
            return count;
        }

        @Override
        public int write(byte[] source, int offset, int length, int timeoutMs) {
            writeTimeouts.add(timeoutMs);
            output.write(source, offset, length);
            advanceClock();
            return length;
        }

        private void advanceClock() {
            if (clock != null) clock.advance(elapsedPerIoMs);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeListenerFactory
            implements ReadReceiptV2OutboxServer.ListenerFactory {
        private final FakeListener listener;

        FakeListenerFactory(FakeListener listener) {
            this.listener = listener;
        }

        @Override
        public ReadReceiptV2OutboxServer.Listener bind(String endpoint) {
            assertEquals(86, endpoint.length());
            assertFalse(endpoint.startsWith("@"));
            return listener;
        }
    }

    private static final class FakeListener implements ReadReceiptV2OutboxServer.Listener {
        Runnable onAccept;
        int lastTimeoutMs;
        boolean closed;

        @Override
        public ReadReceiptV2OutboxServer.Connection accept(int timeoutMs) {
            lastTimeoutMs = timeoutMs;
            if (onAccept != null) onAccept.run();
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class CapturingThreadStarter
            implements ReadReceiptV2OutboxServer.ThreadStarter {
        Runnable runnable;
        boolean stopped = true;

        @Override
        public ReadReceiptV2OutboxServer.Worker start(Runnable runnable) {
            this.runnable = runnable;
            return timeoutMs -> stopped;
        }
    }
}
