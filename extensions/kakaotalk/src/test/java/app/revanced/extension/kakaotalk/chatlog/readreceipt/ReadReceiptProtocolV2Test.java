package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptProtocolV2.canonicalUuid;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptProtocolV2.fromHex;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptProtocolV2.toHex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

public final class ReadReceiptProtocolV2Test {
    private static final String RESOURCE = "/read-receipt-v2/read_receipt_ingress_v2_vectors.properties";
    private static final String FIXTURE_SHA256 = "d8ad5b1778e6e110d704b2c5448c89e480aa4e9fc376de65fe3825666c3a6ab3";
    private static final byte[] FIXTURE_BYTES = resourceBytes();
    private static final Fixture F = Fixture.parse(FIXTURE_BYTES);
    private static final UUID INSTALLATION = canonicalUuid("11111111-1111-8111-8111-111111111111");
    private static final UUID EPOCH = canonicalUuid("22222222-2222-4222-8222-222222222222");
    private static final ReadReceiptProtocolV2.Counters ZERO = counters(0, 0, 0, 0, 0, 0);

    @Test
    public void fixtureIsCanonicalAndParserIsStrict() {
        assertEquals(FIXTURE_SHA256, toHex(ReadReceiptProtocolV2.sha256(FIXTURE_BYTES)));
        assertEquals("2", F.text("format.version"));
        assertThrows(IllegalArgumentException.class,
                () -> Fixture.parse(append("unknown.key=value\n")));
        assertThrows(IllegalArgumentException.class,
                () -> Fixture.parse(append("format.version=2\n")));
        assertThrows(IllegalArgumentException.class,
                () -> Fixture.parse("bad-line\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> Fixture.parse(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class,
                () -> Fixture.parse(withoutLine("format.version=")));
        assertThrows(IllegalArgumentException.class, () -> F.number("vector.token_hex"));
        assertThrows(IllegalArgumentException.class, () -> fromHex("AA"));
        assertThrows(IllegalArgumentException.class,
                () -> canonicalUuid("CB995B76-8039-82B0-BFF6-0EE6FF342F62"));
    }

    @Test
    public void canonicalCryptoVectorsMatchIndependently() {
        byte[] token = F.hex("vector.token_hex");
        assertEquals(F.text("vector.installation_uuid"),
                ReadReceiptProtocolV2.installationId(token).toString());
        assertEquals(8, ReadReceiptProtocolV2.installationId(token).version());
        assertEquals(2, ReadReceiptProtocolV2.installationId(token).variant());
        assertEquals(F.text("vector.socket_name"), ReadReceiptProtocolV2.socketName(token));
        assertEquals(F.text("vector.socket_name_hmac_hex"),
                ReadReceiptProtocolV2.socketName(token).substring(22));

        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                uuid("44444444-4444-4444-8444-444444444444"), 1, 2, 2, 1000, 2000,
                uuid("55555555-5555-4555-8555-555555555555"),
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        assertHex("vector.metadata_preimage_hex", ReadReceiptProtocolV2.metadataPreimage(metadata));
        assertHex("vector.metadata_sha256_hex", ReadReceiptProtocolV2.metadataDigest(metadata));

        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                uuid("99999999-9999-4999-8999-999999999999"), 3, 5, 3, 1, null,
                "precommit_memory_compaction", null, null,
                uuid("55555555-5555-4555-8555-555555555555"),
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), 3000);
        assertHex("vector.loss_preimage_hex", ReadReceiptProtocolV2.lossPreimage(loss));
        assertHex("vector.loss_sha256_hex", ReadReceiptProtocolV2.lossDigest(loss));

        ReadReceiptProtocolV2.Lease lease = new ReadReceiptProtocolV2.Lease(INSTALLATION, EPOCH,
                uuid("33333333-3333-4333-8333-333333333333"), 1, 2, 0, ZERO,
                Collections.singletonList(metadata), Arrays.asList(
                new ReadReceiptProtocolV2.Event(1, uuid("66666666-6666-6666-6666-666666666601"),
                        metadata.batchId, 10, 21, 31),
                new ReadReceiptProtocolV2.Event(2, uuid("66666666-6666-6666-6666-666666666602"),
                        metadata.batchId, 10, 22, 32)), Collections.emptyList());
        assertHex("vector.lease_preimage_hex", ReadReceiptProtocolV2.leasePreimage(lease));
        assertHex("vector.lease_sha256_hex", ReadReceiptProtocolV2.leaseDigest(lease));
        ReadReceiptProtocolV2.Lease lossLease = frame("mixed3_7");
        assertHex("vector.loss_lease_preimage_hex", ReadReceiptProtocolV2.leasePreimage(lossLease));
        assertHex("vector.loss_lease_sha256_hex", ReadReceiptProtocolV2.leaseDigest(lossLease));

        ReadReceiptProtocolV2.State state = new ReadReceiptProtocolV2.State(INSTALLATION, EPOCH,
                0, 2, null, ZERO, "active");
        assertHex("vector.state_preimage_hex", ReadReceiptProtocolV2.statePreimage(state));
        assertHex("vector.state_sha256_hex", ReadReceiptProtocolV2.stateDigest(state));
        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(lease.leaseId,
                1, 2, ReadReceiptProtocolV2.leaseDigest(lease), "active");
        ReadReceiptProtocolV2.State activeState = new ReadReceiptProtocolV2.State(INSTALLATION, EPOCH,
                0, 2, active, ZERO, "active");
        assertHex("vector.state_active_preimage_hex", ReadReceiptProtocolV2.statePreimage(activeState));
        assertHex("vector.state_active_sha256_hex", ReadReceiptProtocolV2.stateDigest(activeState));

        byte[] clientNonce = F.hex("vector.client_nonce_hex");
        byte[] serverNonce = F.hex("vector.server_nonce_hex");
        byte[] stateDigest = F.hex("vector.state_sha256_hex");
        assertHex("vector.server_proof_preimage_hex", ReadReceiptProtocolV2.serverProofPreimage(
                clientNonce, serverNonce, INSTALLATION, stateDigest));
        assertHex("vector.server_proof_hmac_hex", ReadReceiptProtocolV2.serverProof(
                token, clientNonce, serverNonce, INSTALLATION, stateDigest));
        assertHex("vector.client_proof_preimage_hex", ReadReceiptProtocolV2.clientProofPreimage(
                clientNonce, serverNonce, INSTALLATION, EPOCH, stateDigest, null, 0, "new"));
        assertHex("vector.client_proof_hmac_hex", ReadReceiptProtocolV2.clientProof(
                token, clientNonce, serverNonce, INSTALLATION, EPOCH, stateDigest, null, 0, "new"));
        assertHex("vector.client_known_preimage_hex", ReadReceiptProtocolV2.clientProofPreimage(
                clientNonce, serverNonce, INSTALLATION, EPOCH, stateDigest, EPOCH, 2, "active"));
        assertHex("vector.client_known_hmac_hex", ReadReceiptProtocolV2.clientProof(
                token, clientNonce, serverNonce, INSTALLATION, EPOCH, stateDigest, EPOCH, 2, "active"));
    }

    @Test
    public void nullableUuidBranchesEncodeRaw16() {
        ReadReceiptProtocolV2.Metadata metadata = frame("event12").batches.get(0);
        byte[] preimage = ReadReceiptProtocolV2.metadataPreimage(metadata);
        assertTrue(contains(preimage, uuidBytes(metadata.captureBootId)));
        assertTrue(contains(preimage, uuidBytes(metadata.sourceEpochToken)));
        assertFalse(contains(preimage, metadata.sourceEpochToken.toString().getBytes(StandardCharsets.US_ASCII)));

        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(
                uuid("33333333-3333-4333-8333-333333333333"), 1, 2,
                ReadReceiptProtocolV2.leaseDigest(frame("event12")), "active");
        ReadReceiptProtocolV2.State state = new ReadReceiptProtocolV2.State(INSTALLATION, EPOCH,
                0, 2, active, ZERO, "active");
        assertNotEquals(toHex(ReadReceiptProtocolV2.statePreimage(state)),
                F.text("vector.state_preimage_hex"));
        assertNotEquals(toHex(ReadReceiptProtocolV2.clientProofPreimage(
                        F.hex("vector.client_nonce_hex"), F.hex("vector.server_nonce_hex"),
                        INSTALLATION, EPOCH, ReadReceiptProtocolV2.stateDigest(state), EPOCH, 0, "active")),
                F.text("vector.client_proof_preimage_hex"));
    }

    @Test
    public void streamModelCoversNewMixedLossReplayHoleAndConflicts() {
        ReadReceiptProtocolV2.IrisModel model = new ReadReceiptProtocolV2.IrisModel();
        assertEquals(F.text("case.new_stream"), model.ingest(frame(F.text("case.new_stream.input.frame"))));
        assertEquals(F.text("case.mixed_event_loss"),
                model.ingest(frame(F.text("case.mixed_event_loss.input.frame"))));
        assertEquals(F.text("case.loss_only"), model.ingest(frame(F.text("case.loss_only.input.frame"))));

        ReadReceiptProtocolV2.IrisModel replay = new ReadReceiptProtocolV2.IrisModel();
        ReadReceiptProtocolV2.Lease first = frame(F.text("case.exact_replay.input.frame"));
        assertEquals(F.text("case.new_stream"), replay.ingest(first));
        assertEquals(F.text("case.exact_replay"), replay.ingest(first));

        ReadReceiptProtocolV2.IrisModel hole = new ReadReceiptProtocolV2.IrisModel();
        assertEquals(F.text("case.sequence_hole"),
                hole.ingest(frame(F.text("case.sequence_hole.input.frame"))));
        assertEquals(0, hole.cursor());
        assertTrue(hole.poisoned());

        ReadReceiptProtocolV2.IrisModel conflict = new ReadReceiptProtocolV2.IrisModel();
        assertEquals(F.text("case.new_stream"), conflict.ingest(frame("event12")));
        assertEquals(F.text("case.conflicting_duplicate"),
                conflict.ingest(frame(F.text("case.conflicting_duplicate.input.frame"))));
        assertEquals(F.text("case.durable_poison"), conflict.ingest(frame("event34")));
    }

    @Test
    public void oldExactReplayReturnsAckWithoutRegressingState() {
        ReadReceiptProtocolV2.IrisModel model = new ReadReceiptProtocolV2.IrisModel();
        ReadReceiptProtocolV2.Lease old = frame(F.text("case.old_replay.first.frame"));
        assertEquals("ack:2", model.ingest(old));
        assertEquals("ack:4", model.ingest(frame(F.text("case.old_replay.second.frame"))));
        long cursor = model.cursor();
        long highest = model.highestIssuedSequence();
        ReadReceiptProtocolV2.Counters counters = model.counters();
        assertEquals("ack:2", model.ingest(old));
        assertEquals(cursor, model.cursor());
        assertEquals(highest, model.highestIssuedSequence());
        assertTrue(counters == model.counters());
    }

    @Test
    public void crossFrameCanonicalIdentitiesAreImmutable() {
        ReadReceiptProtocolV2.IrisModel batch = new ReadReceiptProtocolV2.IrisModel();
        assertEquals("ack:2", batch.ingest(frame(F.text("case.batch_conflict.input.base.frame"))));
        ReadReceiptProtocolV2.Lease next = frame(F.text("case.batch_conflict.input.next.frame"));
        UUID existingBatch = F.uuid("case.batch_conflict.input.conflicting_batch_id");
        ReadReceiptProtocolV2.Metadata source = next.batches.get(0);
        ReadReceiptProtocolV2.Metadata conflictingMetadata = new ReadReceiptProtocolV2.Metadata(
                existingBatch, source.firstSequence, source.lastSequence, source.eventCount,
                source.persistedAtMs, source.persistedElapsedMs, source.captureSessionId,
                source.captureBootId, source.sourceEpochToken);
        List<ReadReceiptProtocolV2.Event> batchEvents = new ArrayList<>();
        for (ReadReceiptProtocolV2.Event event : next.events) batchEvents.add(new ReadReceiptProtocolV2.Event(
                event.sequence, event.eventId, existingBatch, event.chatId, event.userId, event.watermark));
        assertEquals("nack:batch_conflict:false", batch.ingest(copyLease(next,
                Collections.singletonList(conflictingMetadata), batchEvents, next.losses)));

        ReadReceiptProtocolV2.IrisModel eventModel = new ReadReceiptProtocolV2.IrisModel();
        eventModel.ingest(frame(F.text("case.event_id_conflict.input.base.frame")));
        next = frame(F.text("case.event_id_conflict.input.next.frame"));
        List<ReadReceiptProtocolV2.Event> duplicateEvents = new ArrayList<>(next.events);
        ReadReceiptProtocolV2.Event event = duplicateEvents.get(0);
        duplicateEvents.set(0, new ReadReceiptProtocolV2.Event(event.sequence,
                F.uuid("case.event_id_conflict.input.duplicate_event_id"), event.batchId,
                event.chatId, event.userId, event.watermark));
        assertEquals("nack:conflicting_replay:false", eventModel.ingest(copyLease(next,
                next.batches, duplicateEvents, next.losses)));

        ReadReceiptProtocolV2.IrisModel lossModel = new ReadReceiptProtocolV2.IrisModel();
        lossModel.ingest(frame(F.text("case.loss_conflict.input.base.frame")));
        ReadReceiptProtocolV2.Lease firstLoss = lossConflictLease("first",
                frame(F.text("case.loss_conflict.input.first.frame")),
                F.uuid("case.loss_conflict.input.first_receipt_id"),
                frame(F.text("case.loss_conflict.input.first.frame")).leaseId,
                F.number("case.loss_conflict.input.first.first_sequence"),
                F.number("case.loss_conflict.input.first.last_sequence"),
                F.number("case.loss_conflict.input.first.first_sequence") - 1);
        assertEquals("ack:4", lossModel.ingest(firstLoss));
        ReadReceiptProtocolV2.Lease secondLoss = lossConflictLease("second",
                frame(F.text("case.loss_conflict.input.second.base_frame")),
                F.uuid("case.loss_conflict.input.duplicate_receipt_id"),
                F.uuid("case.loss_conflict.input.second.lease_id"),
                F.number("case.loss_conflict.input.second.first_sequence"),
                F.number("case.loss_conflict.input.second.last_sequence"),
                F.number("case.loss_conflict.input.second.previous_sequence"));
        assertEquals("nack:conflicting_replay:false", lossModel.ingest(secondLoss));
    }

    @Test
    public void sameLeaseCanonicalIdentitiesAreUnique() {
        ReadReceiptProtocolV2.Lease eventLease =
                frame(F.text("case.event_id_conflict.input.base.frame"));
        List<ReadReceiptProtocolV2.Event> events = new ArrayList<>(eventLease.events);
        ReadReceiptProtocolV2.Event second = events.get(1);
        events.set(1, new ReadReceiptProtocolV2.Event(second.sequence, events.get(0).eventId,
                second.batchId, second.chatId, second.userId, second.watermark));
        ReadReceiptProtocolV2.IrisModel eventModel = new ReadReceiptProtocolV2.IrisModel();
        assertEquals("nack:conflicting_replay:false",
                eventModel.ingest(copyLease(eventLease, eventLease.batches, events, eventLease.losses)));
        assertEquals(0, eventModel.cursor());

        ReadReceiptProtocolV2.Loss source =
                frame(F.text("case.loss_only.input.frame")).losses.get(0);
        ReadReceiptProtocolV2.Loss firstLoss = new ReadReceiptProtocolV2.Loss(
                source.lossReceiptId, 1, 1, 1, 0, source.batchId, source.reason,
                source.persistedAtMs, source.persistedElapsedMs, source.captureSessionId,
                source.captureBootId, source.sourceEpochToken, source.createdAtMs);
        ReadReceiptProtocolV2.Loss secondLoss = new ReadReceiptProtocolV2.Loss(
                firstLoss.lossReceiptId, 2, 2, 1, 0, source.batchId, source.reason,
                source.persistedAtMs, source.persistedElapsedMs, source.captureSessionId,
                source.captureBootId, source.sourceEpochToken, source.createdAtMs);
        ReadReceiptProtocolV2.Lease lossLease = new ReadReceiptProtocolV2.Lease(
                INSTALLATION, EPOCH, canonicalUuid("33333333-3333-4333-8333-333333333334"),
                1, 2, 0, ZERO, Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(firstLoss, secondLoss));
        ReadReceiptProtocolV2.IrisModel lossModel = new ReadReceiptProtocolV2.IrisModel();
        assertEquals("nack:conflicting_replay:false",
                lossModel.ingest(lossLease));
        assertEquals(0, lossModel.cursor());
    }

    @Test
    public void acknowledgementsRequireFullIdentityRangeAndDigest() {
        ReadReceiptProtocolV2.Lease lease = frame(F.text("case.ack.input.frame"));
        byte[] digest = ReadReceiptProtocolV2.leaseDigest(lease);
        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(
                lease.leaseId, lease.firstSequence, lease.lastSequence, digest, "active");
        assertEquals(F.text("case.ack_exact"), ack(active, F.uuid("case.ack.input.installation_id"),
                F.uuid("case.ack.input.stream_epoch"), F.uuid("case.ack.input.lease_id"),
                F.number("case.ack.input.through_sequence"), digest));
        assertEquals(F.text("case.ack_partial"), ack(active, INSTALLATION, EPOCH, lease.leaseId,
                F.number("case.ack.partial_through"), digest));
        assertEquals("nack:lease_mismatch:false", ack(active, F.uuid("case.ack.wrong_installation"),
                EPOCH, lease.leaseId, 2, digest));
        assertEquals("nack:lease_mismatch:false", ack(active, INSTALLATION,
                F.uuid("case.ack.wrong_epoch"), lease.leaseId, 2, digest));
        assertEquals("nack:lease_mismatch:false", ack(active, INSTALLATION, EPOCH,
                F.uuid("case.ack.wrong_lease"), 2, digest));
        assertEquals("nack:range_mismatch:false", ack(active, INSTALLATION, EPOCH, lease.leaseId,
                F.number("case.ack.past_through"), digest));
        assertEquals("nack:digest_mismatch:false", ack(active, INSTALLATION, EPOCH, lease.leaseId,
                2, F.hex("case.ack.wrong_digest")));
    }

    @Test
    public void rollbackPoisonAndStateUpdateMatricesMatchFixture() {
        String[] rollbackCases = {"new", "new_sender_ahead", "normal", "ack_loss",
                "sender_state", "iris_behind", "above_highest", "unknown_epoch",
                "sender_poisoned", "receiver_poisoned", "both_poisoned", "inside_lease"};
        for (String name : rollbackCases) assertRollbackCase(name);

        ReadReceiptProtocolV2.State iris = state(0, 0, null, ZERO, "active");
        ReadReceiptProtocolV2.State update = stateFromFixture();
        assertEquals(F.text("case.state_update_normal"),
                ReadReceiptProtocolV2.classifyStateUpdate(update, iris));
        ReadReceiptProtocolV2.Counters regressed = counters(0, 0,
                F.number("case.state_update.regression.uncertain_event"), 1, 0, 0);
        ReadReceiptProtocolV2.State stored = state(0, 0, null, update.counters, "active");
        assertEquals(F.text("case.state_update_regression"), ReadReceiptProtocolV2.classifyStateUpdate(
                state(0, 0, null, regressed, "active"), stored));
        assertEquals(F.text("case.state_update_cursor_conflict"), ReadReceiptProtocolV2.classifyStateUpdate(
                state(F.number("case.state_update.cursor_conflict.last_acked_sequence"),
                        F.number("case.state_update.cursor_conflict.highest_issued_sequence"),
                        null, update.counters, "active"), iris));
        String activePrefix = "case.state_update.active_lease.";
        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(
                F.uuid(activePrefix + "lease_id"), F.number(activePrefix + "first_sequence"),
                F.number(activePrefix + "last_sequence"), F.hex(activePrefix + "digest"),
                F.text(activePrefix + "state"));
        assertEquals(F.text("case.state_update_active_lease"), ReadReceiptProtocolV2.classifyStateUpdate(
                state(0, 2, active, update.counters, "active"), iris));
    }

    @Test
    public void framePrefixesAndPhasesAreBounded() {
        String[] names = {"zero", "control_max", "control_over", "stream_max", "stream_over",
                "signed_max", "high_bit", "unsigned_max"};
        for (String name : names) {
            byte[] prefix = F.hex("prefix." + name + ".hex");
            long value = F.number("prefix." + name + ".value");
            assertEquals(value, ReadReceiptProtocolV2.decodeFrameLength(prefix));
            assertArrayEquals(prefix, ReadReceiptProtocolV2.encodeFrameLength(value));
            String kind = F.has("prefix." + name + ".control") ? "control" : "stream";
            String expected = F.text("prefix." + name + "." + kind);
            assertEquals(expected.equals("accept"),
                    ReadReceiptProtocolV2.frameAllowed(value, kind.equals("stream")));
        }
        for (int length = 0; length < 4; length++) {
            String encoded = F.text("prefix.truncated" + length + ".hex");
            byte[] prefix = encoded.equals("none") ? new byte[0] : fromHex(encoded);
            assertThrows(IllegalArgumentException.class,
                    () -> ReadReceiptProtocolV2.decodeFrameLength(prefix));
        }
        assertTrue(ReadReceiptProtocolV2.phaseAllowed("await_hello", "HELLO"));
        assertTrue(ReadReceiptProtocolV2.phaseAllowed("await_auth", "AUTH"));
        assertTrue(ReadReceiptProtocolV2.phaseAllowed("authenticated", "STREAM_BATCH"));
        assertTrue(ReadReceiptProtocolV2.phaseAllowed("authenticated", "STATE_UPDATE"));
        assertFalse(ReadReceiptProtocolV2.phaseAllowed("await_hello", "STREAM_BATCH"));
        assertPhaseFixture("sender", "await_hello");
        assertPhaseFixture("sender", "await_auth");
        assertPhaseFixture("sender", "authenticated");
        assertPhaseFixture("iris", "await_challenge");
        assertPhaseFixture("iris", "await_authenticated");
        assertPhaseFixture("iris", "authenticated");
        assertEquals("reject", F.text("case.phase_out_of_order"));
        assertTrue(ReadReceiptProtocolV2.validNack("schema_unavailable", true));
        assertFalse(ReadReceiptProtocolV2.validNack("schema_unavailable", false));
        assertTrue(ReadReceiptProtocolV2.validNack("stream_rollback", false));
        assertFalse(ReadReceiptProtocolV2.validNack("stream_rollback", true));
    }

    @Test
    public void numericAndOrderingMutationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ReadReceiptProtocolV2.Event(
                parseSigned("case.validation.negative_event_sequence"),
                uuid("66666666-6666-6666-6666-666666666601"),
                uuid("44444444-4444-4444-8444-444444444444"), 1, 1, 1));
        ReadReceiptProtocolV2.Lease source = frame(F.text("case.validation.input.event_frame"));
        assertThrows(IllegalArgumentException.class, () -> new ReadReceiptProtocolV2.Lease(
                source.installationId, source.streamEpoch, source.leaseId,
                F.number("case.validation.zero_first_sequence"), source.lastSequence,
                source.previousSequence, source.counters, source.batches, source.events, source.losses));
        ReadReceiptProtocolV2.Lease overflow = new ReadReceiptProtocolV2.Lease(
                source.installationId, source.streamEpoch, source.leaseId,
                F.number("case.validation.overflow_first_sequence"),
                F.number("case.validation.overflow_last_sequence"),
                F.number("case.validation.overflow_previous_sequence"), source.counters,
                source.batches, source.events, source.losses);
        assertThrows(IllegalArgumentException.class,
                () -> ReadReceiptProtocolV2.leasePreimage(overflow));

        ReadReceiptProtocolV2.Lease lossSource = frame(F.text("case.validation.input.loss_frame"));
        ReadReceiptProtocolV2.Loss second = new ReadReceiptProtocolV2.Loss(
                F.uuid("case.validation.loss_receipt_b"),
                F.number("case.validation.loss_first_b"), F.number("case.validation.loss_last_b"),
                2, 1, null, "bounded_prune", null, null, null, null, null, 1);
        ReadReceiptProtocolV2.Loss first = new ReadReceiptProtocolV2.Loss(
                lossSource.losses.get(0).lossReceiptId, F.number("case.validation.loss_first_a"),
                F.number("case.validation.loss_last_a"), 1, 1, null, "bounded_prune",
                null, null, null, null, null, 1);
        ReadReceiptProtocolV2.Lease reversed = copyLease(lossSource, lossSource.batches,
                lossSource.events, Arrays.asList(second, first));
        assertThrows(IllegalArgumentException.class,
                () -> ReadReceiptProtocolV2.leasePreimage(reversed));
    }

    @Test
    public void metadataRangeAndReferencedEventRangeAreExact() {
        ReadReceiptProtocolV2.Metadata source = frame("event12").batches.get(0);
        assertThrows(IllegalArgumentException.class, () -> new ReadReceiptProtocolV2.Metadata(
                source.batchId, source.firstSequence, source.lastSequence, source.eventCount + 1,
                source.persistedAtMs, source.persistedElapsedMs, source.captureSessionId,
                source.captureBootId, source.sourceEpochToken));
        ReadReceiptProtocolV2.Metadata narrow = new ReadReceiptProtocolV2.Metadata(source.batchId,
                2, 2, 1, source.persistedAtMs, source.persistedElapsedMs, source.captureSessionId,
                source.captureBootId, source.sourceEpochToken);
        ReadReceiptProtocolV2.Lease lease = frame("event12");
        assertThrows(IllegalArgumentException.class, () -> ReadReceiptProtocolV2.leasePreimage(
                copyLease(lease, Collections.singletonList(narrow), lease.events, lease.losses)));
    }

    @Test
    public void streamIdentityAndHandshakePoisonAreDurable() {
        ReadReceiptProtocolV2.IrisModel stream = new ReadReceiptProtocolV2.IrisModel();
        assertEquals("ack:2", stream.ingest(frame("event12")));
        ReadReceiptProtocolV2.Lease next = frame(F.text("case.ingest.cross_epoch.input.frame"));
        ReadReceiptProtocolV2.Lease wrongEpoch = new ReadReceiptProtocolV2.Lease(
                next.installationId, F.uuid("case.ingest.cross_epoch.input.stream_epoch"), next.leaseId,
                next.firstSequence, next.lastSequence, next.previousSequence, next.counters,
                next.batches, next.events, next.losses);
        assertEquals("nack:unknown_epoch:false", stream.ingest(wrongEpoch));
        assertEquals("nack:stream_poisoned:false", stream.ingest(next));

        ReadReceiptProtocolV2.Lease installationFrame = frame(
                F.text("case.ingest.cross_installation.input.frame"));
        ReadReceiptProtocolV2.IrisModel installation = new ReadReceiptProtocolV2.IrisModel();
        installation.ingest(installationFrame);
        ReadReceiptProtocolV2.Lease event34 = frame("event34");
        ReadReceiptProtocolV2.Lease wrongInstallation = new ReadReceiptProtocolV2.Lease(
                F.uuid("case.ingest.cross_installation.input.installation_id"), event34.streamEpoch,
                event34.leaseId, event34.firstSequence, event34.lastSequence,
                event34.previousSequence, event34.counters, event34.batches,
                event34.events, event34.losses);
        assertEquals("nack:unknown_epoch:false", installation.ingest(wrongInstallation));

        String repeat = "case.handshake.repeat_poison.";
        ReadReceiptProtocolV2.Lease knownReceipt = frame(
                F.text(repeat + "known").substring("receipt:".length()));
        ReadReceiptProtocolV2.State iris = state(knownReceipt.lastSequence,
                knownReceipt.lastSequence, null, knownReceipt.counters, "active");
        ReadReceiptProtocolV2.HandshakeModel handshake = new ReadReceiptProtocolV2.HandshakeModel(
                iris, true);
        ReadReceiptProtocolV2.State rollback = new ReadReceiptProtocolV2.State(INSTALLATION,
                F.uuid(repeat + "sender_epoch"), F.number(repeat + "sender_ack"),
                F.number(repeat + "sender_highest"), null, ZERO,
                F.text(repeat + "sender_status"));
        assertEquals("nack:" + F.text(repeat + "first_expected") + ":false",
                handshake.handshake(rollback));
        assertTrue(handshake.poisoned());
        assertEquals("nack:" + F.text(repeat + "second_expected") + ":false",
                handshake.handshake(iris));

        String unknown = "case.rollback.unknown_epoch.";
        ReadReceiptProtocolV2.HandshakeModel epochHandshake = new ReadReceiptProtocolV2.HandshakeModel(
                iris, true);
        ReadReceiptProtocolV2.State unknownEpoch = new ReadReceiptProtocolV2.State(INSTALLATION,
                F.uuid(unknown + "sender_epoch"), F.number(unknown + "sender_ack"),
                F.number(unknown + "sender_highest"), null, ZERO,
                F.text(unknown + "sender_status"));
        assertEquals("nack:" + F.text(unknown + "expected") + ":false",
                epochHandshake.handshake(unknownEpoch));
        assertEquals("nack:stream_poisoned:false", epochHandshake.handshake(iris));

        ReadReceiptProtocolV2.ActiveLease active = new ReadReceiptProtocolV2.ActiveLease(
                uuid("33333333-3333-4333-8333-333333333333"), 1, 2, new byte[32], "active");
        ReadReceiptProtocolV2.State poisonedWithLease = state(2, 2, active, ZERO,
                F.text("case.state_update.poisoned_with_lease.status"));
        assertEquals("nack:stream_poisoned:false",
                ReadReceiptProtocolV2.classifyStateUpdate(poisonedWithLease, iris));
        ReadReceiptProtocolV2.State senderPoisoned = state(2, 2, null, ZERO,
                F.text("case.state_update.sender_poisoned.status"));
        assertEquals("nack:stream_poisoned:false",
                ReadReceiptProtocolV2.classifyStateUpdate(senderPoisoned, iris));
    }

    @Test
    public void sharedInvalidLossReasonIsRejected() {
        ReadReceiptProtocolV2.Loss source = frame(F.text("case.loss_invalid_reason.input.frame"))
                .losses.get(0);
        assertThrows(IllegalArgumentException.class, () -> new ReadReceiptProtocolV2.Loss(
                source.lossReceiptId, source.firstSequence, source.lastSequence,
                source.droppedEventCount, source.droppedBatchCount, source.batchId,
                F.text("case.loss_invalid_reason.input.reason"), source.persistedAtMs,
                source.persistedElapsedMs, source.captureSessionId, source.captureBootId,
                source.sourceEpochToken, source.createdAtMs));
    }

    private static ReadReceiptProtocolV2.Lease frame(String name) {
        String p = "frame." + name + ".";
        List<ReadReceiptProtocolV2.Metadata> batches = new ArrayList<>();
        for (int i = 0; i < F.number(p + "batch_count"); i++) {
            String q = p + "batch." + i + ".";
            batches.add(new ReadReceiptProtocolV2.Metadata(F.uuid(q + "batch_id"),
                    F.number(q + "first_sequence"), F.number(q + "last_sequence"),
                    F.number(q + "event_count"), F.number(q + "persisted_at_ms"),
                    F.number(q + "persisted_elapsed_ms"), F.uuid(q + "capture_session_id"),
                    F.nullableUuid(q + "capture_boot_id"), F.nullableUuid(q + "source_epoch_token")));
        }
        List<ReadReceiptProtocolV2.Event> events = new ArrayList<>();
        for (int i = 0; i < F.number(p + "event_count"); i++) {
            String q = p + "event." + i + ".";
            events.add(new ReadReceiptProtocolV2.Event(F.number(q + "sequence"),
                    F.uuid(q + "event_id"), F.uuid(q + "batch_id"), F.number(q + "chat_id"),
                    F.number(q + "user_id"), F.number(q + "watermark")));
        }
        List<ReadReceiptProtocolV2.Loss> losses = new ArrayList<>();
        for (int i = 0; i < F.number(p + "loss_count"); i++) {
            String q = p + "loss." + i + ".";
            losses.add(new ReadReceiptProtocolV2.Loss(F.uuid(q + "loss_receipt_id"),
                    F.number(q + "first_sequence"), F.number(q + "last_sequence"),
                    F.number(q + "dropped_event_count"), F.number(q + "dropped_batch_count"),
                    F.nullableUuid(q + "batch_id"), F.text(q + "reason"),
                    F.nullableNumber(q + "persisted_at_ms"), F.nullableNumber(q + "persisted_elapsed_ms"),
                    F.nullableUuid(q + "capture_session_id"), F.nullableUuid(q + "capture_boot_id"),
                    F.nullableUuid(q + "source_epoch_token"), F.number(q + "created_at_ms")));
        }
        return new ReadReceiptProtocolV2.Lease(F.uuid(p + "installation_id"), F.uuid(p + "stream_epoch"),
                F.uuid(p + "lease_id"), F.number(p + "first_sequence"), F.number(p + "last_sequence"),
                F.number(p + "previous_sequence"), counters(p), batches, events, losses);
    }

    private static void assertPhaseFixture(String role, String phase) {
        String prefix = "case.phase." + role + "." + phase + ".";
        if (F.has(prefix + "accept")) {
            for (String frame : F.text(prefix + "accept").split(",")) {
                assertTrue(role + "/" + phase + "/" + frame,
                        ReadReceiptProtocolV2.phaseAllowed(role, phase, frame));
            }
        }
        if (F.has(prefix + "reject")) {
            for (String frame : F.text(prefix + "reject").split(",")) {
                assertFalse(role + "/" + phase + "/" + frame,
                        ReadReceiptProtocolV2.phaseAllowed(role, phase, frame));
            }
        }
    }

    private static void assertRollbackCase(String name) {
        String p = "case.rollback." + name + ".";
        String leaseName = F.text(p + "sender_lease");
        ReadReceiptProtocolV2.ActiveLease active = null;
        if (leaseName.equals("inline")) {
            active = new ReadReceiptProtocolV2.ActiveLease(F.uuid(p + "sender_lease_id"),
                    F.number(p + "sender_lease_first"), F.number(p + "sender_lease_last"),
                    F.hex(p + "sender_lease_digest"), F.text(p + "sender_lease_state"));
        } else if (!leaseName.equals("null")) {
            ReadReceiptProtocolV2.Lease lease = frame(leaseName);
            active = new ReadReceiptProtocolV2.ActiveLease(lease.leaseId, lease.firstSequence,
                    lease.lastSequence, ReadReceiptProtocolV2.leaseDigest(lease), "active");
        }
        ReadReceiptProtocolV2.State sender = new ReadReceiptProtocolV2.State(INSTALLATION,
                F.uuid(p + "sender_epoch"), F.number(p + "sender_ack"),
                F.number(p + "sender_highest"), active, ZERO, F.text(p + "sender_status"));
        String known = F.text(p + "known");
        boolean installationKnown = !known.equals("none");
        ReadReceiptProtocolV2.State iris = null;
        if (known.startsWith("receipt:") || known.startsWith("poisoned:")) {
            ReadReceiptProtocolV2.Lease receipt = frame(known.substring(known.indexOf(':') + 1));
            iris = new ReadReceiptProtocolV2.State(INSTALLATION, receipt.streamEpoch,
                    receipt.lastSequence, receipt.lastSequence, null, receipt.counters,
                    known.startsWith("poisoned:") ? "poisoned" : "active");
        } else if (known.startsWith("cursor:")) {
            long cursor = Long.parseLong(known.substring("cursor:".length()));
            iris = state(cursor, cursor, null, ZERO, "active");
        }
        String expected = F.text(p + "expected");
        if (!expected.equals("accepted")) expected = "nack:" + expected + ":false";
        String actual = ReadReceiptProtocolV2.classifyHandshake(sender, iris, installationKnown);
        if (actual.equals("accepted_exact_replay")) actual = "accepted";
        assertEquals(name, expected, actual);
    }

    private static ReadReceiptProtocolV2.Counters counters(String prefix) {
        return counters(F.number(prefix + "counters.confirmed_event"),
                F.number(prefix + "counters.confirmed_batch"),
                F.number(prefix + "counters.uncertain_event"),
                F.number(prefix + "counters.uncertain_batch"),
                F.number(prefix + "counters.capture_event"),
                F.number(prefix + "counters.capture_batch"));
    }

    private static ReadReceiptProtocolV2.Counters counters(long a, long b, long c, long d, long e, long f) {
        return new ReadReceiptProtocolV2.Counters(a, b, c, d, e, f);
    }

    private static ReadReceiptProtocolV2.State state(long cursor, long highest,
                                                     ReadReceiptProtocolV2.ActiveLease active,
                                                     ReadReceiptProtocolV2.Counters counters,
                                                     String status) {
        return new ReadReceiptProtocolV2.State(INSTALLATION, EPOCH, cursor, highest, active, counters, status);
    }

    private static ReadReceiptProtocolV2.State stateFromFixture() {
        String p = "case.state_update.input.";
        return new ReadReceiptProtocolV2.State(F.uuid(p + "installation_id"), F.uuid(p + "stream_epoch"),
                F.number(p + "last_acked_sequence"), F.number(p + "highest_issued_sequence"), null,
                counters(p), F.text(p + "status"));
    }

    private static String ack(ReadReceiptProtocolV2.ActiveLease active, UUID installation, UUID epoch,
                              UUID lease, long through, byte[] digest) {
        return ReadReceiptProtocolV2.classifyAck(active, installation, INSTALLATION, epoch, EPOCH,
                lease, through, digest);
    }

    private static ReadReceiptProtocolV2.Lease copyLease(ReadReceiptProtocolV2.Lease source,
            List<ReadReceiptProtocolV2.Metadata> batches, List<ReadReceiptProtocolV2.Event> events,
            List<ReadReceiptProtocolV2.Loss> losses) {
        return new ReadReceiptProtocolV2.Lease(source.installationId, source.streamEpoch, source.leaseId,
                source.firstSequence, source.lastSequence, source.previousSequence, source.counters,
                batches, events, losses);
    }

    private static ReadReceiptProtocolV2.Lease lossConflictLease(String name,
            ReadReceiptProtocolV2.Lease base, UUID receiptId, UUID leaseId,
            long firstSequence, long lastSequence, long previousSequence) {
        String p = "case.loss_conflict.input." + name + ".";
        ReadReceiptProtocolV2.Counters counters = counters(F.number(p + "counters.confirmed_event"),
                F.number(p + "counters.confirmed_batch"), 0, 0, 0, 0);
        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(receiptId,
                firstSequence, lastSequence, F.number(p + "dropped_event_count"),
                F.number(p + "dropped_batch_count"), F.nullableUuid(p + "batch_id"),
                F.text(p + "reason"), F.nullableNumber(p + "persisted_at_ms"),
                F.nullableNumber(p + "persisted_elapsed_ms"),
                F.nullableUuid(p + "capture_session_id"), F.nullableUuid(p + "capture_boot_id"),
                F.nullableUuid(p + "source_epoch_token"), F.number(p + "created_at_ms"));
        return new ReadReceiptProtocolV2.Lease(base.installationId, base.streamEpoch, leaseId,
                firstSequence, lastSequence, previousSequence, counters,
                Collections.emptyList(), Collections.emptyList(), Collections.singletonList(loss));
    }

    private static long parseSigned(String key) {
        try {
            return Long.parseLong(F.text(key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static void assertHex(String key, byte[] actual) {
        assertEquals(key, F.text(key), toHex(actual));
    }

    private static UUID uuid(String value) {
        return canonicalUuid(value);
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static byte[] append(String value) {
        byte[] suffix = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(FIXTURE_BYTES, FIXTURE_BYTES.length + suffix.length);
        System.arraycopy(suffix, 0, result, FIXTURE_BYTES.length, suffix.length);
        return result;
    }

    private static byte[] withoutLine(String prefix) {
        StringBuilder result = new StringBuilder();
        for (String line : decodeUtf8(FIXTURE_BYTES).split("\\n", -1)) {
            if (!line.startsWith(prefix)) result.append(line).append('\n');
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes() {
        try (InputStream input = ReadReceiptProtocolV2Test.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("missing fixture " + RESOURCE);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("fixture is not UTF-8", exception);
        }
    }

    private static final class Fixture {
        private final Map<String, String> values;

        private Fixture(Map<String, String> values) {
            this.values = values;
        }

        static Fixture parse(byte[] bytes) {
            Map<String, String> values = new LinkedHashMap<>();
            String text = decodeUtf8(bytes);
            for (String line : text.split("\\n", -1)) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.indexOf('\\') >= 0) throw new IllegalArgumentException("fixture escapes are forbidden");
                int separator = line.indexOf('=');
                if (separator <= 0 || separator != line.lastIndexOf('=')) {
                    throw new IllegalArgumentException("malformed fixture line");
                }
                String key = line.substring(0, separator);
                if (values.put(key, line.substring(separator + 1)) != null) {
                    throw new IllegalArgumentException("duplicate fixture key " + key);
                }
            }
            String manifest = values.get("format.allowed_keys");
            if (manifest == null || manifest.isEmpty()) {
                throw new IllegalArgumentException("missing exact-key manifest");
            }
            Set<String> allowedKeys = new LinkedHashSet<>(Arrays.asList(manifest.split(",", -1)));
            if (allowedKeys.contains("") || allowedKeys.size() != manifest.split(",", -1).length
                    || allowedKeys.contains("format.allowed_keys")) {
                throw new IllegalArgumentException("invalid exact-key manifest");
            }
            Set<String> observedKeys = new LinkedHashSet<>(values.keySet());
            observedKeys.remove("format.allowed_keys");
            if (!observedKeys.equals(allowedKeys)) {
                Set<String> unknown = new LinkedHashSet<>(observedKeys);
                unknown.removeAll(allowedKeys);
                if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown fixture key " + unknown);
                throw new IllegalArgumentException("missing fixture key");
            }
            return new Fixture(Collections.unmodifiableMap(values));
        }

        boolean has(String key) {
            return values.containsKey(key);
        }

        String text(String key) {
            String value = values.get(key);
            if (value == null) throw new IllegalArgumentException("missing fixture key " + key);
            return value;
        }

        long number(String key) {
            String value = text(key);
            if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("invalid integer " + key);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("integer outside i64 " + key, exception);
            }
        }

        Long nullableNumber(String key) {
            return text(key).equals("null") ? null : number(key);
        }

        byte[] hex(String key) {
            return fromHex(text(key));
        }

        UUID uuid(String key) {
            return canonicalUuid(text(key));
        }

        UUID nullableUuid(String key) {
            return text(key).equals("null") ? null : uuid(key);
        }
    }
}
