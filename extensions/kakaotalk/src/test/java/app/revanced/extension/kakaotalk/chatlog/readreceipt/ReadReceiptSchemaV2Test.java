package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class ReadReceiptSchemaV2Test {

    private static final String EXPECTED_CATALOG_DIGEST =
            "6c725b29dca1d1b1d637c1dbb2abf5771b15840e0ea78f0eaf64205387133605";
    private static final List<String> EXPECTED_STATEMENT_NAMES = Arrays.asList(
            "read_receipt_stream_state",
            "read_receipt_batches",
            "read_receipt_outbox",
            "read_receipt_loss_segments",
            "read_receipt_transfer_lease",
            "read_receipt_diagnostic_counters",
            "idx_read_receipt_batches_delivery",
            "idx_read_receipt_outbox_batch",
            "idx_read_receipt_loss_segments_batch"
    );
    private static final List<String> EXPECTED_AUTO_INDEX_NAMES = Arrays.asList(
            "sqlite_autoindex_read_receipt_stream_state_1",
            "sqlite_autoindex_read_receipt_batches_1",
            "sqlite_autoindex_read_receipt_outbox_1",
            "sqlite_autoindex_read_receipt_outbox_2",
            "sqlite_autoindex_read_receipt_loss_segments_1",
            "sqlite_autoindex_read_receipt_loss_segments_2",
            "sqlite_autoindex_read_receipt_transfer_lease_1",
            "sqlite_autoindex_read_receipt_transfer_lease_2"
    );

    @Test
    public void ddlStatementAndCatalogObjectNamesAreExact() {
        assertEquals(EXPECTED_STATEMENT_NAMES, ReadReceiptSchemaV2.statementNames());
        assertEquals(9, ReadReceiptSchemaV2.createStatements().size());
        for (String statement : ReadReceiptSchemaV2.createStatements()) {
            assertFalse(statement.contains("\n"));
            assertFalse(statement.endsWith(";"));
        }

        List<ReadReceiptSchemaCatalog.SchemaObject> objects = ReadReceiptSchemaV2.expectedObjects();
        assertEquals(17, objects.size());
        List<String> autoIndexes = new ArrayList<>();
        for (ReadReceiptSchemaCatalog.SchemaObject object : objects) {
            if (object.name.startsWith("sqlite_autoindex_")) {
                autoIndexes.add(object.name);
                assertEquals("index", object.type);
                assertNull(object.sql);
            }
        }
        assertEquals(EXPECTED_AUTO_INDEX_NAMES, autoIndexes);
        assertEquals(EXPECTED_CATALOG_DIGEST, hex(ReadReceiptSchemaCatalog.digest(
                ReadReceiptSchemaV2.APPLICATION_ID,
                ReadReceiptSchemaV2.USER_VERSION,
                objects
        )));
        assertEquals(ReadReceiptSchemaCatalog.Classification.SUPPORTED,
                ReadReceiptSchemaV2.catalog().classify(
                        ReadReceiptSchemaV2.APPLICATION_ID,
                        ReadReceiptSchemaV2.USER_VERSION,
                        objects));
    }

    @Test
    public void productionDdlMatchesHostSqlFixtureExactly() throws IOException {
        assertEquals(Arrays.asList(
                "PRAGMA foreign_keys=ON",
                "PRAGMA journal_mode=DELETE",
                "PRAGMA page_size=4096",
                "PRAGMA auto_vacuum=INCREMENTAL",
                "PRAGMA application_id=0x49525232",
                "PRAGMA user_version=2",
                "BEGIN IMMEDIATE"
        ), fixturePreamble());
        assertEquals(ReadReceiptSchemaV2.createStatements(), fixtureDdl());
        assertEquals("COMMIT", fixtureLines().get(fixtureLines().size() - 1));
    }

    @Test
    public void expectedCatalogUsesTheExactPersistedDdl() {
        Map<String, String> statements = new HashMap<>();
        List<String> names = ReadReceiptSchemaV2.statementNames();
        List<String> ddl = ReadReceiptSchemaV2.createStatements();
        for (int index = 0; index < names.size(); index++) {
            statements.put(names.get(index), ddl.get(index));
        }

        for (ReadReceiptSchemaCatalog.SchemaObject object : ReadReceiptSchemaV2.expectedObjects()) {
            if (object.name.startsWith("sqlite_autoindex_")) {
                assertNull(object.sql);
            } else {
                assertEquals(statements.get(object.name), object.sql);
            }
        }
    }

    @Test
    public void physicalConstraintsAreFrozenWithoutAHeuristicParser() {
        String state = ReadReceiptSchemaV2.CREATE_STREAM_STATE;
        assertContainsAll(state,
                "singleton INTEGER PRIMARY KEY CHECK (singleton = 1)",
                "installation_id BLOB NOT NULL CHECK (typeof(installation_id) = 'blob' AND length(installation_id) = 16)",
                "stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16)",
                "UNIQUE (stream_epoch)",
                "next_sequence > 0", "last_acked_sequence >= 0", "highest_issued_sequence >= 0",
                "last_acked_sequence <= highest_issued_sequence",
                "highest_issued_sequence < next_sequence",
                "stream_status IN ('active', 'poisoned')",
                "stream_status = 'active' AND poison_category IS NULL",
                "stream_status = 'poisoned' AND poison_category IS NOT NULL",
                "created_at_ms >= 0", "updated_at_ms >= 0");
        assertIntegerStorage(state,
                "singleton", "next_sequence", "last_acked_sequence", "highest_issued_sequence",
                "confirmed_dropped_event_count", "confirmed_dropped_batch_count",
                "uncertain_outcome_event_count", "uncertain_outcome_batch_count",
                "capture_drop_event_count", "capture_drop_batch_count",
                "created_at_ms", "updated_at_ms");
        assertSixCounters(state, "");

        String lease = ReadReceiptSchemaV2.CREATE_TRANSFER_LEASE;
        assertContainsAll(lease,
                "singleton INTEGER PRIMARY KEY CHECK (singleton = 1)",
                "stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16)",
                "UNIQUE (lease_id)", "UNIQUE (stream_epoch, first_sequence, last_sequence)",
                "first_sequence > 0 AND first_sequence <= last_sequence",
                "previous_sequence = first_sequence - 1",
                "length(lease_digest) = 32",
                "state IN ('active', 'poisoned')",
                "state = 'active' AND nack_category IS NULL AND expected_cursor IS NULL",
                "state = 'poisoned' AND nack_category IS NOT NULL",
                "expected_cursor IS NULL OR (typeof(expected_cursor) = 'integer' AND expected_cursor >= 0)",
                "FOREIGN KEY (stream_epoch) REFERENCES read_receipt_stream_state(stream_epoch) ON UPDATE RESTRICT ON DELETE RESTRICT",
                "attempt_count >= 0", "created_at_ms >= 0", "updated_at_ms >= 0");
        assertFalse(lease.contains(
                "state = 'poisoned' AND nack_category IS NOT NULL AND expected_cursor IS NOT NULL"));
        assertIntegerStorage(lease,
                "singleton", "first_sequence", "last_sequence", "previous_sequence",
                "confirmed_dropped_event_count", "confirmed_dropped_batch_count",
                "uncertain_outcome_event_count", "uncertain_outcome_batch_count",
                "capture_drop_event_count", "capture_drop_batch_count",
                "attempt_count", "created_at_ms", "updated_at_ms");
        assertSixCounters(lease, "");
        assertPoisonCategories(state);
        assertPoisonCategories(lease);

        String batches = ReadReceiptSchemaV2.CREATE_BATCHES;
        assertContainsAll(batches,
                "stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16)",
                "PRIMARY KEY (stream_epoch, batch_id)",
                "event_count = last_sequence - first_sequence + 1",
                "persisted_at_ms >= 0", "persisted_elapsed_ms >= 0",
                "length(capture_session_id) = 16", "length(capture_boot_id) = 16",
                "length(source_epoch_token) = 16", "length(metadata_digest) = 32",
                "FOREIGN KEY (stream_epoch) REFERENCES read_receipt_stream_state(stream_epoch) ON UPDATE RESTRICT ON DELETE RESTRICT");
        assertIntegerStorage(batches,
                "first_sequence", "last_sequence", "event_count", "persisted_at_ms",
                "persisted_elapsed_ms", "created_at_ms");

        String outbox = ReadReceiptSchemaV2.CREATE_OUTBOX;
        assertContainsAll(outbox,
                "stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16)",
                "batch_id BLOB NOT NULL CHECK (typeof(batch_id) = 'blob' AND length(batch_id) = 16)",
                "PRIMARY KEY (stream_epoch, sequence)", "UNIQUE (event_id)", "sequence > 0",
                "FOREIGN KEY (stream_epoch, batch_id) REFERENCES read_receipt_batches(stream_epoch, batch_id) ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE INITIALLY IMMEDIATE");
        assertFalse(outbox.contains("chat_id >= 0"));
        assertFalse(outbox.contains("user_id >= 0"));
        assertFalse(outbox.contains("watermark >= 0"));
        assertIntegerStorage(outbox, "sequence", "chat_id", "user_id", "watermark");

        String loss = ReadReceiptSchemaV2.CREATE_LOSS_SEGMENTS;
        assertContainsAll(loss,
                "stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16)",
                "batch_id BLOB CHECK (batch_id IS NULL OR (typeof(batch_id) = 'blob' AND length(batch_id) = 16))",
                "PRIMARY KEY (stream_epoch, loss_receipt_id)",
                "UNIQUE (stream_epoch, first_sequence, last_sequence)",
                "dropped_event_count = last_sequence - first_sequence + 1",
                "reason IN ('bounded_prune', 'retry_compaction', 'oversized_batch', 'precommit_memory_compaction', 'operator_approved_rollback_gap')",
                "FOREIGN KEY (stream_epoch, batch_id) REFERENCES read_receipt_batches(stream_epoch, batch_id) ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE INITIALLY IMMEDIATE");
        assertIntegerStorage(loss,
                "first_sequence", "last_sequence", "dropped_event_count", "dropped_batch_count",
                "created_at_ms");
        assertContainsAll(loss,
                "persisted_at_ms IS NULL OR (typeof(persisted_at_ms) = 'integer' AND persisted_at_ms >= 0)",
                "persisted_elapsed_ms IS NULL OR (typeof(persisted_elapsed_ms) = 'integer' AND persisted_elapsed_ms >= 0)");

        String diagnostics = ReadReceiptSchemaV2.CREATE_DIAGNOSTIC_COUNTERS;
        assertContainsAll(diagnostics,
                "category TEXT PRIMARY KEY",
                "occurrence_count INTEGER NOT NULL DEFAULT 0 CHECK (typeof(occurrence_count) = 'integer' AND occurrence_count >= 0)",
                "event_count INTEGER NOT NULL DEFAULT 0 CHECK (typeof(event_count) = 'integer' AND event_count >= 0)",
                "batch_count INTEGER NOT NULL DEFAULT 0 CHECK (typeof(batch_count) = 'integer' AND batch_count >= 0)",
                "last_observed_at_ms INTEGER CHECK (last_observed_at_ms IS NULL OR (typeof(last_observed_at_ms) = 'integer' AND last_observed_at_ms >= 0))",
                "occurrence_count = 0 AND last_observed_at_ms IS NULL",
                "occurrence_count > 0 AND last_observed_at_ms IS NOT NULL",
                "WITHOUT ROWID");
        assertIntegerStorage(diagnostics, "occurrence_count", "event_count", "batch_count");
        assertTrue(diagnostics.contains(
                "last_observed_at_ms IS NULL OR (typeof(last_observed_at_ms) = 'integer' AND last_observed_at_ms >= 0)"));
        for (String category : Arrays.asList(
                "owner_mismatch", "missing_handle", "handle_timeout", "capacity_eviction",
                "unproven_false", "unproven_throw", "sqlite_write_failure", "retry_queue_full",
                "marker_rejected", "token_rejected", "poison_transition")) {
            assertTrue(diagnostics.contains("'" + category + "'"));
        }

        String all = String.join("\n", ReadReceiptSchemaV2.createStatements());
        assertFalse(all.contains("AUTOINCREMENT"));
        assertFalse(all.contains("sqlite_sequence"));
        assertFalse(all.contains("CREATE VIEW"));
        assertFalse(all.contains("CREATE TRIGGER"));
        assertFalse(all.contains("sqlite_stat"));
    }

    private static void assertSixCounters(String sql, String prefix) {
        for (String name : Arrays.asList(
                "confirmed_dropped_event_count", "confirmed_dropped_batch_count",
                "uncertain_outcome_event_count", "uncertain_outcome_batch_count",
                "capture_drop_event_count", "capture_drop_batch_count")) {
            String column = prefix + name;
            assertTrue(sql.contains(column + " INTEGER NOT NULL CHECK (typeof(" + column
                    + ") = 'integer' AND " + column + " >= 0)"));
        }
    }

    private static void assertIntegerStorage(String sql, String... columns) {
        for (String column : columns) {
            assertTrue("missing integer storage check: " + column,
                    sql.contains("typeof(" + column + ") = 'integer'"));
        }
    }

    private static void assertPoisonCategories(String sql) {
        for (String category : Arrays.asList(
                "stream_poisoned", "sequence_hole", "range_mismatch", "lease_mismatch",
                "digest_mismatch", "conflicting_replay", "batch_conflict", "counter_regression",
                "stream_rollback", "unknown_epoch")) {
            assertTrue(sql.contains("'" + category + "'"));
        }
        assertFalse(sql.contains("'schema_unavailable'"));
        assertFalse(sql.contains("'internal_transient'"));
    }

    private static void assertContainsAll(String value, String... expectedParts) {
        for (String part : expectedParts) {
            assertTrue("missing exact DDL token: " + part, value.contains(part));
        }
    }

    private static List<String> fixturePreamble() throws IOException {
        return fixtureLines().subList(0, 7);
    }

    private static List<String> fixtureDdl() throws IOException {
        List<String> lines = fixtureLines();
        return lines.subList(7, lines.size() - 1);
    }

    private static List<String> fixtureLines() throws IOException {
        InputStream stream = ReadReceiptSchemaV2Test.class.getResourceAsStream(
                "/read-receipt-v2/read_receipt_schema_v2.sql");
        assertTrue(stream != null);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
