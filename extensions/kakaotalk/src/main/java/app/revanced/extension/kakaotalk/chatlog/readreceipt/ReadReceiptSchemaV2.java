package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class ReadReceiptSchemaV2 {

    static final long APPLICATION_ID = ReadReceiptSchemaCatalog.APPLICATION_ID;
    static final long USER_VERSION = ReadReceiptSchemaCatalog.USER_VERSION;
    static final long PAGE_SIZE_BYTES = 4096L;
    static final long AUTO_VACUUM_INCREMENTAL = 2L;

    static final String CREATE_STREAM_STATE = "CREATE TABLE read_receipt_stream_state (singleton INTEGER PRIMARY KEY CHECK (singleton = 1) CHECK (typeof(singleton) = 'integer'), installation_id BLOB NOT NULL CHECK (typeof(installation_id) = 'blob' AND length(installation_id) = 16), stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16), next_sequence INTEGER NOT NULL CHECK (typeof(next_sequence) = 'integer' AND next_sequence > 0), last_acked_sequence INTEGER NOT NULL CHECK (typeof(last_acked_sequence) = 'integer' AND last_acked_sequence >= 0), highest_issued_sequence INTEGER NOT NULL CHECK (typeof(highest_issued_sequence) = 'integer' AND highest_issued_sequence >= 0), confirmed_dropped_event_count INTEGER NOT NULL CHECK (typeof(confirmed_dropped_event_count) = 'integer' AND confirmed_dropped_event_count >= 0), confirmed_dropped_batch_count INTEGER NOT NULL CHECK (typeof(confirmed_dropped_batch_count) = 'integer' AND confirmed_dropped_batch_count >= 0), uncertain_outcome_event_count INTEGER NOT NULL CHECK (typeof(uncertain_outcome_event_count) = 'integer' AND uncertain_outcome_event_count >= 0), uncertain_outcome_batch_count INTEGER NOT NULL CHECK (typeof(uncertain_outcome_batch_count) = 'integer' AND uncertain_outcome_batch_count >= 0), capture_drop_event_count INTEGER NOT NULL CHECK (typeof(capture_drop_event_count) = 'integer' AND capture_drop_event_count >= 0), capture_drop_batch_count INTEGER NOT NULL CHECK (typeof(capture_drop_batch_count) = 'integer' AND capture_drop_batch_count >= 0), stream_status TEXT NOT NULL CHECK (stream_status IN ('active', 'poisoned')), poison_category TEXT CHECK (poison_category IN ('stream_poisoned', 'sequence_hole', 'range_mismatch', 'lease_mismatch', 'digest_mismatch', 'conflicting_replay', 'batch_conflict', 'counter_regression', 'stream_rollback', 'unknown_epoch')), created_at_ms INTEGER NOT NULL CHECK (typeof(created_at_ms) = 'integer' AND created_at_ms >= 0), updated_at_ms INTEGER NOT NULL CHECK (typeof(updated_at_ms) = 'integer' AND updated_at_ms >= 0), UNIQUE (stream_epoch), CHECK (last_acked_sequence <= highest_issued_sequence), CHECK (highest_issued_sequence < next_sequence), CHECK ((stream_status = 'active' AND poison_category IS NULL) OR (stream_status = 'poisoned' AND poison_category IS NOT NULL)))";
    static final String CREATE_BATCHES = "CREATE TABLE read_receipt_batches (stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16), batch_id BLOB NOT NULL CHECK (typeof(batch_id) = 'blob' AND length(batch_id) = 16), first_sequence INTEGER NOT NULL CHECK (typeof(first_sequence) = 'integer' AND first_sequence > 0), last_sequence INTEGER NOT NULL CHECK (typeof(last_sequence) = 'integer' AND last_sequence >= first_sequence), event_count INTEGER NOT NULL CHECK (typeof(event_count) = 'integer' AND event_count > 0 AND event_count = last_sequence - first_sequence + 1), persisted_at_ms INTEGER NOT NULL CHECK (typeof(persisted_at_ms) = 'integer' AND persisted_at_ms >= 0), persisted_elapsed_ms INTEGER NOT NULL CHECK (typeof(persisted_elapsed_ms) = 'integer' AND persisted_elapsed_ms >= 0), capture_session_id BLOB NOT NULL CHECK (typeof(capture_session_id) = 'blob' AND length(capture_session_id) = 16), capture_boot_id BLOB CHECK (capture_boot_id IS NULL OR (typeof(capture_boot_id) = 'blob' AND length(capture_boot_id) = 16)), source_epoch_token BLOB CHECK (source_epoch_token IS NULL OR (typeof(source_epoch_token) = 'blob' AND length(source_epoch_token) = 16)), metadata_digest BLOB NOT NULL CHECK (typeof(metadata_digest) = 'blob' AND length(metadata_digest) = 32), created_at_ms INTEGER NOT NULL CHECK (typeof(created_at_ms) = 'integer' AND created_at_ms >= 0), PRIMARY KEY (stream_epoch, batch_id), FOREIGN KEY (stream_epoch) REFERENCES read_receipt_stream_state(stream_epoch) ON UPDATE RESTRICT ON DELETE RESTRICT)";
    static final String CREATE_OUTBOX = "CREATE TABLE read_receipt_outbox (stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16), sequence INTEGER NOT NULL CHECK (typeof(sequence) = 'integer' AND sequence > 0), event_id BLOB NOT NULL CHECK (typeof(event_id) = 'blob' AND length(event_id) = 16), batch_id BLOB NOT NULL CHECK (typeof(batch_id) = 'blob' AND length(batch_id) = 16), chat_id INTEGER NOT NULL CHECK (typeof(chat_id) = 'integer'), user_id INTEGER NOT NULL CHECK (typeof(user_id) = 'integer'), watermark INTEGER NOT NULL CHECK (typeof(watermark) = 'integer'), PRIMARY KEY (stream_epoch, sequence), UNIQUE (event_id), FOREIGN KEY (stream_epoch, batch_id) REFERENCES read_receipt_batches(stream_epoch, batch_id) ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE INITIALLY IMMEDIATE)";
    static final String CREATE_LOSS_SEGMENTS = "CREATE TABLE read_receipt_loss_segments (stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16), loss_receipt_id BLOB NOT NULL CHECK (typeof(loss_receipt_id) = 'blob' AND length(loss_receipt_id) = 16), first_sequence INTEGER NOT NULL CHECK (typeof(first_sequence) = 'integer' AND first_sequence > 0), last_sequence INTEGER NOT NULL CHECK (typeof(last_sequence) = 'integer' AND last_sequence >= first_sequence), dropped_event_count INTEGER NOT NULL CHECK (typeof(dropped_event_count) = 'integer' AND dropped_event_count > 0 AND dropped_event_count = last_sequence - first_sequence + 1), dropped_batch_count INTEGER NOT NULL CHECK (typeof(dropped_batch_count) = 'integer' AND dropped_batch_count >= 0), batch_id BLOB CHECK (batch_id IS NULL OR (typeof(batch_id) = 'blob' AND length(batch_id) = 16)), reason TEXT NOT NULL CHECK (reason IN ('bounded_prune', 'retry_compaction', 'oversized_batch', 'precommit_memory_compaction', 'operator_approved_rollback_gap')), persisted_at_ms INTEGER CHECK (persisted_at_ms IS NULL OR (typeof(persisted_at_ms) = 'integer' AND persisted_at_ms >= 0)), persisted_elapsed_ms INTEGER CHECK (persisted_elapsed_ms IS NULL OR (typeof(persisted_elapsed_ms) = 'integer' AND persisted_elapsed_ms >= 0)), capture_session_id BLOB CHECK (capture_session_id IS NULL OR (typeof(capture_session_id) = 'blob' AND length(capture_session_id) = 16)), capture_boot_id BLOB CHECK (capture_boot_id IS NULL OR (typeof(capture_boot_id) = 'blob' AND length(capture_boot_id) = 16)), source_epoch_token BLOB CHECK (source_epoch_token IS NULL OR (typeof(source_epoch_token) = 'blob' AND length(source_epoch_token) = 16)), created_at_ms INTEGER NOT NULL CHECK (typeof(created_at_ms) = 'integer' AND created_at_ms >= 0), content_digest BLOB NOT NULL CHECK (typeof(content_digest) = 'blob' AND length(content_digest) = 32), PRIMARY KEY (stream_epoch, loss_receipt_id), UNIQUE (stream_epoch, first_sequence, last_sequence), FOREIGN KEY (stream_epoch) REFERENCES read_receipt_stream_state(stream_epoch) ON UPDATE RESTRICT ON DELETE RESTRICT, FOREIGN KEY (stream_epoch, batch_id) REFERENCES read_receipt_batches(stream_epoch, batch_id) ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE INITIALLY IMMEDIATE)";
    static final String CREATE_TRANSFER_LEASE = "CREATE TABLE read_receipt_transfer_lease (singleton INTEGER PRIMARY KEY CHECK (singleton = 1) CHECK (typeof(singleton) = 'integer'), stream_epoch BLOB NOT NULL CHECK (typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16), lease_id BLOB NOT NULL CHECK (typeof(lease_id) = 'blob' AND length(lease_id) = 16), first_sequence INTEGER NOT NULL CHECK (typeof(first_sequence) = 'integer'), last_sequence INTEGER NOT NULL CHECK (typeof(last_sequence) = 'integer'), previous_sequence INTEGER NOT NULL CHECK (typeof(previous_sequence) = 'integer'), lease_digest BLOB NOT NULL CHECK (typeof(lease_digest) = 'blob' AND length(lease_digest) = 32), state TEXT NOT NULL CHECK (state IN ('active', 'poisoned')), nack_category TEXT CHECK (nack_category IN ('stream_poisoned', 'sequence_hole', 'range_mismatch', 'lease_mismatch', 'digest_mismatch', 'conflicting_replay', 'batch_conflict', 'counter_regression', 'stream_rollback', 'unknown_epoch')), expected_cursor INTEGER CHECK (expected_cursor IS NULL OR (typeof(expected_cursor) = 'integer' AND expected_cursor >= 0)), confirmed_dropped_event_count INTEGER NOT NULL CHECK (typeof(confirmed_dropped_event_count) = 'integer' AND confirmed_dropped_event_count >= 0), confirmed_dropped_batch_count INTEGER NOT NULL CHECK (typeof(confirmed_dropped_batch_count) = 'integer' AND confirmed_dropped_batch_count >= 0), uncertain_outcome_event_count INTEGER NOT NULL CHECK (typeof(uncertain_outcome_event_count) = 'integer' AND uncertain_outcome_event_count >= 0), uncertain_outcome_batch_count INTEGER NOT NULL CHECK (typeof(uncertain_outcome_batch_count) = 'integer' AND uncertain_outcome_batch_count >= 0), capture_drop_event_count INTEGER NOT NULL CHECK (typeof(capture_drop_event_count) = 'integer' AND capture_drop_event_count >= 0), capture_drop_batch_count INTEGER NOT NULL CHECK (typeof(capture_drop_batch_count) = 'integer' AND capture_drop_batch_count >= 0), attempt_count INTEGER NOT NULL CHECK (typeof(attempt_count) = 'integer' AND attempt_count >= 0), created_at_ms INTEGER NOT NULL CHECK (typeof(created_at_ms) = 'integer' AND created_at_ms >= 0), updated_at_ms INTEGER NOT NULL CHECK (typeof(updated_at_ms) = 'integer' AND updated_at_ms >= 0), UNIQUE (lease_id), UNIQUE (stream_epoch, first_sequence, last_sequence), CHECK (first_sequence > 0 AND first_sequence <= last_sequence), CHECK (previous_sequence = first_sequence - 1), CHECK ((state = 'active' AND nack_category IS NULL AND expected_cursor IS NULL) OR (state = 'poisoned' AND nack_category IS NOT NULL)), FOREIGN KEY (stream_epoch) REFERENCES read_receipt_stream_state(stream_epoch) ON UPDATE RESTRICT ON DELETE RESTRICT)";
    static final String CREATE_DIAGNOSTIC_COUNTERS = "CREATE TABLE read_receipt_diagnostic_counters (category TEXT PRIMARY KEY CHECK (category IN ('owner_mismatch', 'missing_handle', 'handle_timeout', 'capacity_eviction', 'unproven_false', 'unproven_throw', 'sqlite_write_failure', 'retry_queue_full', 'marker_rejected', 'token_rejected', 'poison_transition')), occurrence_count INTEGER NOT NULL DEFAULT 0 CHECK (typeof(occurrence_count) = 'integer' AND occurrence_count >= 0), event_count INTEGER NOT NULL DEFAULT 0 CHECK (typeof(event_count) = 'integer' AND event_count >= 0), batch_count INTEGER NOT NULL DEFAULT 0 CHECK (typeof(batch_count) = 'integer' AND batch_count >= 0), last_observed_at_ms INTEGER CHECK (last_observed_at_ms IS NULL OR (typeof(last_observed_at_ms) = 'integer' AND last_observed_at_ms >= 0)), CHECK ((occurrence_count = 0 AND last_observed_at_ms IS NULL) OR (occurrence_count > 0 AND last_observed_at_ms IS NOT NULL))) WITHOUT ROWID";
    static final String CREATE_BATCH_DELIVERY_INDEX = "CREATE INDEX idx_read_receipt_batches_delivery ON read_receipt_batches(stream_epoch, first_sequence, batch_id)";
    static final String CREATE_OUTBOX_BATCH_INDEX = "CREATE INDEX idx_read_receipt_outbox_batch ON read_receipt_outbox(stream_epoch, batch_id, sequence)";
    static final String CREATE_LOSS_BATCH_INDEX = "CREATE INDEX idx_read_receipt_loss_segments_batch ON read_receipt_loss_segments(stream_epoch, batch_id, first_sequence, last_sequence)";

    private static final List<String> STATEMENT_NAMES = Collections.unmodifiableList(Arrays.asList(
            "read_receipt_stream_state",
            "read_receipt_batches",
            "read_receipt_outbox",
            "read_receipt_loss_segments",
            "read_receipt_transfer_lease",
            "read_receipt_diagnostic_counters",
            "idx_read_receipt_batches_delivery",
            "idx_read_receipt_outbox_batch",
            "idx_read_receipt_loss_segments_batch"
    ));
    private static final List<String> CREATE_STATEMENTS = Collections.unmodifiableList(Arrays.asList(
            CREATE_STREAM_STATE,
            CREATE_BATCHES,
            CREATE_OUTBOX,
            CREATE_LOSS_SEGMENTS,
            CREATE_TRANSFER_LEASE,
            CREATE_DIAGNOSTIC_COUNTERS,
            CREATE_BATCH_DELIVERY_INDEX,
            CREATE_OUTBOX_BATCH_INDEX,
            CREATE_LOSS_BATCH_INDEX
    ));
    private static final List<ReadReceiptSchemaCatalog.SchemaObject> EXPECTED_OBJECTS =
            Collections.unmodifiableList(Arrays.asList(
                    table("read_receipt_stream_state", CREATE_STREAM_STATE),
                    table("read_receipt_batches", CREATE_BATCHES),
                    table("read_receipt_outbox", CREATE_OUTBOX),
                    table("read_receipt_loss_segments", CREATE_LOSS_SEGMENTS),
                    table("read_receipt_transfer_lease", CREATE_TRANSFER_LEASE),
                    table("read_receipt_diagnostic_counters", CREATE_DIAGNOSTIC_COUNTERS),
                    index("idx_read_receipt_batches_delivery", "read_receipt_batches", CREATE_BATCH_DELIVERY_INDEX),
                    index("idx_read_receipt_outbox_batch", "read_receipt_outbox", CREATE_OUTBOX_BATCH_INDEX),
                    index("idx_read_receipt_loss_segments_batch", "read_receipt_loss_segments", CREATE_LOSS_BATCH_INDEX),
                    autoIndex("sqlite_autoindex_read_receipt_stream_state_1", "read_receipt_stream_state"),
                    autoIndex("sqlite_autoindex_read_receipt_batches_1", "read_receipt_batches"),
                    autoIndex("sqlite_autoindex_read_receipt_outbox_1", "read_receipt_outbox"),
                    autoIndex("sqlite_autoindex_read_receipt_outbox_2", "read_receipt_outbox"),
                    autoIndex("sqlite_autoindex_read_receipt_loss_segments_1", "read_receipt_loss_segments"),
                    autoIndex("sqlite_autoindex_read_receipt_loss_segments_2", "read_receipt_loss_segments"),
                    autoIndex("sqlite_autoindex_read_receipt_transfer_lease_1", "read_receipt_transfer_lease"),
                    autoIndex("sqlite_autoindex_read_receipt_transfer_lease_2", "read_receipt_transfer_lease")
            ));

    static List<String> statementNames() {
        return STATEMENT_NAMES;
    }

    static List<String> createStatements() {
        return CREATE_STATEMENTS;
    }

    static List<ReadReceiptSchemaCatalog.SchemaObject> expectedObjects() {
        return EXPECTED_OBJECTS;
    }

    static ReadReceiptSchemaCatalog catalog() {
        return new ReadReceiptSchemaCatalog(EXPECTED_OBJECTS);
    }

    private static ReadReceiptSchemaCatalog.SchemaObject table(String name, String sql) {
        return new ReadReceiptSchemaCatalog.SchemaObject("table", name, name, sql);
    }

    private static ReadReceiptSchemaCatalog.SchemaObject index(String name, String table, String sql) {
        return new ReadReceiptSchemaCatalog.SchemaObject("index", name, table, sql);
    }

    private static ReadReceiptSchemaCatalog.SchemaObject autoIndex(String name, String table) {
        return index(name, table, null);
    }

    private ReadReceiptSchemaV2() {
    }
}
