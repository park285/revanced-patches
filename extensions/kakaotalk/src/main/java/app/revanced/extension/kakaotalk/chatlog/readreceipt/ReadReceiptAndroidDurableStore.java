package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ReadReceiptAndroidDurableStore implements Store {
    private static final int QUERY_LIMIT = 257;

    static final String STREAM_QUERY =
            "SELECT singleton, installation_id, stream_epoch, next_sequence, "
                    + "last_acked_sequence, highest_issued_sequence, "
                    + "confirmed_dropped_event_count, confirmed_dropped_batch_count, "
                    + "uncertain_outcome_event_count, uncertain_outcome_batch_count, "
                    + "capture_drop_event_count, capture_drop_batch_count, stream_status, "
                    + "poison_category "
                    + "FROM read_receipt_stream_state ORDER BY singleton LIMIT 2";
    static final String LEASE_QUERY =
            "SELECT singleton, stream_epoch, lease_id, first_sequence, last_sequence, "
                    + "previous_sequence, lease_digest, state, nack_category, "
                    + "confirmed_dropped_event_count, confirmed_dropped_batch_count, "
                    + "uncertain_outcome_event_count, uncertain_outcome_batch_count, "
                    + "capture_drop_event_count, capture_drop_batch_count "
                    + "FROM read_receipt_transfer_lease ORDER BY singleton LIMIT 2";
    static final String BATCH_HEAD_QUERY = batchQuery("ASC");
    static final String BATCH_TAIL_QUERY = batchQuery("DESC");
    static final String EVENT_HEAD_QUERY = eventQuery("ASC");
    static final String EVENT_TAIL_QUERY = eventQuery("DESC");
    static final String LOSS_HEAD_QUERY = lossQuery("ASC");
    static final String LOSS_TAIL_QUERY = lossQuery("DESC");
    static final String BATCH_RANGE_QUERY =
            "SELECT stream_epoch, batch_id, first_sequence, last_sequence, event_count, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, metadata_digest "
                    + "FROM read_receipt_batches WHERE stream_epoch = "
                    + "(SELECT stream_epoch FROM read_receipt_stream_state WHERE singleton = 1) "
                    + "AND last_sequence >= ? AND first_sequence <= ? "
                    + "ORDER BY first_sequence, batch_id LIMIT " + QUERY_LIMIT;
    static final String EVENT_RANGE_QUERY =
            "SELECT stream_epoch, sequence, event_id, batch_id, chat_id, user_id, watermark "
                    + "FROM read_receipt_outbox WHERE stream_epoch = "
                    + "(SELECT stream_epoch FROM read_receipt_stream_state WHERE singleton = 1) "
                    + "AND sequence >= ? "
                    + "AND sequence <= ? ORDER BY sequence LIMIT " + QUERY_LIMIT;
    static final String LOSS_RANGE_QUERY =
            "SELECT stream_epoch, loss_receipt_id, first_sequence, last_sequence, "
                    + "dropped_event_count, dropped_batch_count, batch_id, reason, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, created_at_ms, content_digest "
                    + "FROM read_receipt_loss_segments WHERE stream_epoch = "
                    + "(SELECT stream_epoch FROM read_receipt_stream_state WHERE singleton = 1) "
                    + "AND last_sequence >= ? AND first_sequence <= ? "
                    + "ORDER BY first_sequence, loss_receipt_id LIMIT " + QUERY_LIMIT;
    static final String APPEND_BATCH_QUERY =
            "SELECT stream_epoch, batch_id, first_sequence, last_sequence, event_count, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, metadata_digest "
                    + "FROM read_receipt_batches WHERE stream_epoch = X'%s' "
                    + "AND batch_id = X'%s' LIMIT 2";
    static final String APPEND_EVENT_QUERY =
            "SELECT stream_epoch, sequence, event_id, batch_id, chat_id, user_id, watermark "
                    + "FROM read_receipt_outbox WHERE stream_epoch = X'%s' "
                    + "AND batch_id = X'%s' ORDER BY sequence LIMIT %d";
    static final String APPEND_LOSS_QUERY =
            "SELECT stream_epoch, loss_receipt_id, first_sequence, last_sequence, "
                    + "dropped_event_count, dropped_batch_count, batch_id, reason, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, created_at_ms, content_digest "
                    + "FROM read_receipt_loss_segments WHERE stream_epoch = X'%s' "
                    + "AND batch_id = X'%s' LIMIT 2";
    static final String MAINTENANCE_BATCH_QUERY =
            "SELECT b.stream_epoch, b.batch_id, b.first_sequence, b.last_sequence, "
                    + "b.event_count, b.persisted_at_ms, b.persisted_elapsed_ms, "
                    + "b.capture_session_id, b.capture_boot_id, b.source_epoch_token, "
                    + "b.metadata_digest FROM read_receipt_outbox o INDEXED BY "
                    + "sqlite_autoindex_read_receipt_outbox_1 JOIN read_receipt_batches b "
                    + "ON b.stream_epoch = o.stream_epoch AND b.batch_id = o.batch_id "
                    + "WHERE o.stream_epoch = (SELECT stream_epoch FROM "
                    + "read_receipt_stream_state WHERE singleton = 1) AND o.sequence > ? "
                    + "AND (? IS NULL OR b.last_sequence < ? OR b.first_sequence > ?) "
                    + "ORDER BY o.sequence LIMIT 1";
    static final String MAINTENANCE_EVENT_SHAPE_QUERY =
            "SELECT COUNT(*), MIN(sequence), MAX(sequence) FROM (SELECT sequence "
                    + "FROM read_receipt_outbox WHERE stream_epoch = (SELECT stream_epoch FROM "
                    + "read_receipt_stream_state WHERE singleton = 1) "
                    + "AND batch_id = X'%s' AND sequence >= ? "
                    + "AND sequence <= ? ORDER BY sequence LIMIT 257)";
    static final String MAINTENANCE_LOSS_COUNT_QUERY =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM read_receipt_loss_segments "
                    + "WHERE stream_epoch = "
                    + "(SELECT stream_epoch FROM read_receipt_stream_state WHERE singleton = 1) "
                    + "AND last_sequence >= ? AND first_sequence <= ?) THEN 1 ELSE 0 END";
    static final String LOSS_COMPACTION_QUERY =
            "SELECT stream_epoch, loss_receipt_id, first_sequence, last_sequence, "
                    + "dropped_event_count, dropped_batch_count, batch_id, reason, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, created_at_ms, content_digest "
                    + "FROM read_receipt_loss_segments WHERE stream_epoch = "
                    + "(SELECT stream_epoch FROM read_receipt_stream_state WHERE singleton = 1) "
                    + "AND last_sequence > ? AND (? IS NULL OR last_sequence < ? "
                    + "OR first_sequence > ?) ORDER BY first_sequence, last_sequence LIMIT "
                    + QUERY_LIMIT;
    static final String LOSS_COMPACTION_EVENT_COUNT_QUERY =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM read_receipt_outbox WHERE stream_epoch = "
                    + "(SELECT stream_epoch FROM read_receipt_stream_state WHERE singleton = 1) "
                    + "AND sequence >= ? AND sequence <= ?) THEN 1 ELSE 0 END";
    static final String BATCH_OUTSIDE_LEASE_REFERENCE_QUERY =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM read_receipt_outbox "
                    + "WHERE stream_epoch = X'%s' AND batch_id = X'%s' "
                    + "AND (sequence < ? OR sequence > ?)) "
                    + "OR EXISTS (SELECT 1 FROM read_receipt_loss_segments "
                    + "WHERE stream_epoch = X'%s' AND batch_id = X'%s' "
                    + "AND (first_sequence < ? OR last_sequence > ?)) "
                    + "THEN 1 ELSE 0 END";

    private static final String INSERT_BATCH =
            "INSERT INTO read_receipt_batches (stream_epoch, batch_id, first_sequence, "
                    + "last_sequence, event_count, persisted_at_ms, persisted_elapsed_ms, "
                    + "capture_session_id, capture_boot_id, source_epoch_token, "
                    + "metadata_digest, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_EVENT =
            "INSERT INTO read_receipt_outbox (stream_epoch, sequence, event_id, batch_id, "
                    + "chat_id, user_id, watermark) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_LOSS =
            "INSERT INTO read_receipt_loss_segments (stream_epoch, loss_receipt_id, "
                    + "first_sequence, last_sequence, dropped_event_count, dropped_batch_count, "
                    + "batch_id, reason, persisted_at_ms, persisted_elapsed_ms, "
                    + "capture_session_id, capture_boot_id, source_epoch_token, created_at_ms, "
                    + "content_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_STATE =
            "UPDATE read_receipt_stream_state SET next_sequence = ?, last_acked_sequence = ?, "
                    + "highest_issued_sequence = ?, confirmed_dropped_event_count = ?, "
                    + "confirmed_dropped_batch_count = ?, uncertain_outcome_event_count = ?, "
                    + "uncertain_outcome_batch_count = ?, capture_drop_event_count = ?, "
                    + "capture_drop_batch_count = ?, stream_status = ?, updated_at_ms = ? "
                    + "WHERE singleton = 1 AND installation_id = ? AND stream_epoch = ?";
    private static final String INSERT_LEASE =
            "INSERT INTO read_receipt_transfer_lease (singleton, stream_epoch, lease_id, "
                    + "first_sequence, last_sequence, previous_sequence, lease_digest, state, "
                    + "nack_category, expected_cursor, confirmed_dropped_event_count, "
                    + "confirmed_dropped_batch_count, uncertain_outcome_event_count, "
                    + "uncertain_outcome_batch_count, capture_drop_event_count, "
                    + "capture_drop_batch_count, attempt_count, created_at_ms, updated_at_ms) "
                    + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
    private static final String DELETE_EVENT =
            "DELETE FROM read_receipt_outbox WHERE stream_epoch = ? AND sequence = ? "
                    + "AND event_id = ? AND batch_id = ? AND chat_id = ? AND user_id = ? "
                    + "AND watermark = ?";
    private static final String DELETE_LOSS =
            "DELETE FROM read_receipt_loss_segments WHERE stream_epoch = ? "
                    + "AND loss_receipt_id = ? AND first_sequence = ? AND last_sequence = ? "
                    + "AND content_digest = ?";
    private static final String DELETE_BATCH =
            "DELETE FROM read_receipt_batches WHERE stream_epoch = ? AND batch_id = ? "
                    + "AND metadata_digest = ? AND NOT EXISTS (SELECT 1 FROM read_receipt_outbox "
                    + "WHERE stream_epoch = ? AND batch_id = ?) AND NOT EXISTS (SELECT 1 FROM "
                    + "read_receipt_loss_segments WHERE stream_epoch = ? AND batch_id = ?)";
    private static final String DELETE_LEASE =
            "DELETE FROM read_receipt_transfer_lease WHERE singleton = 1 AND stream_epoch = ? "
                    + "AND lease_id = ? AND first_sequence = ? AND last_sequence = ? "
                    + "AND previous_sequence = ? AND lease_digest = ? AND state = ? "
                    + "AND confirmed_dropped_event_count = ? AND confirmed_dropped_batch_count = ? "
                    + "AND uncertain_outcome_event_count = ? AND uncertain_outcome_batch_count = ? "
                    + "AND capture_drop_event_count = ? AND capture_drop_batch_count = ?";
    private static final String DELETE_BATCH_SUFFIX_EVENTS =
            "DELETE FROM read_receipt_outbox WHERE stream_epoch = ? AND batch_id = ? "
                    + "AND sequence >= ? AND sequence <= ?";
    private static final String POISON_STATE =
            "UPDATE read_receipt_stream_state SET stream_status = 'poisoned', "
                    + "poison_category = ?, updated_at_ms = ? WHERE singleton = 1 "
                    + "AND stream_status = 'active' AND poison_category IS NULL";
    private static final String POISON_LEASE =
            "UPDATE read_receipt_transfer_lease SET state = 'poisoned', nack_category = ?, "
                    + "updated_at_ms = ? WHERE singleton = 1 AND state = 'active' "
                    + "AND nack_category IS NULL";

    interface Clock {
        long nowMs();
    }

    private final String databasePath;
    private final ReadReceiptAndroidStartupStore.Factory factory;
    private final Clock clock;

    ReadReceiptAndroidDurableStore(String databasePath) {
        this(databasePath, new ReadReceiptAndroidStartupStore.AndroidFactory(),
                System::currentTimeMillis);
    }

    ReadReceiptAndroidDurableStore(String databasePath,
                                   ReadReceiptAndroidStartupStore.Factory factory,
                                   Clock clock) {
        this.databasePath = databasePath;
        this.factory = factory;
        this.clock = clock;
    }

    @Override
    public Snapshot readSnapshot() {
        SqlTransaction transaction = begin(true);
        try {
            Snapshot snapshot = transaction.readSnapshot();
            transaction.commit();
            return snapshot;
        } finally {
            transaction.close();
        }
    }

    @Override
    public Snapshot readControl() {
        SqlTransaction transaction = begin(false);
        try {
            Snapshot snapshot = transaction.readControl();
            transaction.commit();
            return snapshot;
        } finally {
            transaction.close();
        }
    }

    @Override
    public ReconcileResult reconcileAppend(
            BatchInput input) {
        SqlTransaction transaction = begin(false);
        try {
            ReconcileResult result = transaction.reconcileAppend(input);
            transaction.commit();
            return result;
        } finally {
            transaction.close();
        }
    }

    @Override
    public Snapshot readAckEvidence(
            LeaseRecord lease) {
        SqlTransaction transaction = begin(false);
        try {
            Snapshot snapshot = transaction.readAckEvidence(lease);
            transaction.commit();
            return snapshot;
        } finally {
            transaction.close();
        }
    }

    @Override
    public Transaction beginImmediate() {
        return begin(false);
    }

    private SqlTransaction begin(boolean includeTail) {
        if (databasePath == null || databasePath.isEmpty() || factory == null || clock == null) {
            throw failure();
        }
        ReadReceiptAndroidStartupStore.Database database = null;
        try {
            database = factory.openReadWrite(databasePath);
            if (database == null) throw new ReadReceiptAndroidStartupStore.Failure();
            database.execute("PRAGMA foreign_keys=ON", null);
            if (scalar(database, "PRAGMA foreign_keys") != 1
                    || scalar(database, "PRAGMA wal_autocheckpoint=0") != 0
                    || scalar(database, "PRAGMA page_size")
                    != ReadReceiptSchemaV2.PAGE_SIZE_BYTES
                    || scalar(database, "PRAGMA auto_vacuum")
                    != ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) {
                throw new ReadReceiptAndroidStartupStore.Failure();
            }
            database.execute("BEGIN IMMEDIATE", null);
            return new SqlTransaction(database, clock, includeTail);
        } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
            closeQuietly(database);
            throw failure();
        }
    }

    private static final class SqlTransaction implements Transaction {
        private final ReadReceiptAndroidStartupStore.Database database;
        private final Clock clock;
        private final boolean includeTail;
        private boolean active = true;
        private boolean closed;

        SqlTransaction(ReadReceiptAndroidStartupStore.Database database, Clock clock,
                       boolean includeTail) {
            this.database = database;
            this.clock = clock;
            this.includeTail = includeTail;
        }

        @Override
        public Snapshot readSnapshot() {
            requireActive();
            try {
                return read(database, includeTail);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public Snapshot readControl() {
            requireActive();
            try {
                return control(database);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public Snapshot readLeaseWindow() {
            requireActive();
            try {
                StateRecord state = readState(database);
                LeaseRecord lease = readLease(database);
                long first = lease == null ? state.lastAckedSequence + 1 : lease.firstSequence;
                long last = lease == null ? state.highestIssuedSequence : lease.lastSequence;
                return range(database, state, lease, first, last);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        ReconcileResult reconcileAppend(
                BatchInput input) {
            requireActive();
            try {
                return reconcile(database, input);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        Snapshot readAckEvidence(
                LeaseRecord lease) {
            requireActive();
            try {
                StateRecord state = readState(database);
                LeaseRecord current = readLease(database);
                return range(database, state, current, lease.firstSequence, lease.lastSequence);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public BatchSelection selectOldestCompleteUnleasedBatch() {
            requireActive();
            try {
                return maintenanceSelection(database);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public LossSelection selectOldestUnleasedLossRun() {
            requireActive();
            try {
                return lossCompactionSelection(database);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public List<BatchRecord> selectDeletableBatches(
                Snapshot snapshot,
                LeaseProjection projection) {
            requireActive();
            try {
                List<BatchRecord> result = new ArrayList<>();
                String first = Long.toString(projection.lease.firstSequence);
                String last = Long.toString(projection.lease.lastSequence);
                String epoch = hex(bytes(projection.lease.streamEpoch));
                for (BatchRecord batch : projection.batches) {
                    if (!batch.streamEpoch.equals(projection.lease.streamEpoch)) throw failure();
                    String id = hex(bytes(batch.metadata.batchId));
                    String sql = sqlForUuids(BATCH_OUTSIDE_LEASE_REFERENCE_QUERY,
                            epoch, id, epoch, id);
                    if (scalar(database, sql, new String[]{first, last, first, last}) == 0) {
                        result.add(batch);
                    }
                }
                return result;
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public void insertBatch(BatchRecord batch) {
            requireOne(INSERT_BATCH, new Object[]{bytes(batch.streamEpoch),
                    bytes(batch.metadata.batchId), batch.metadata.firstSequence,
                    batch.metadata.lastSequence, batch.metadata.eventCount,
                    batch.metadata.persistedAtMs, batch.metadata.persistedElapsedMs,
                    bytes(batch.metadata.captureSessionId), nullableBytes(batch.metadata.captureBootId),
                    nullableBytes(batch.metadata.sourceEpochToken), batch.metadataDigest.clone(), now()});
        }

        @Override
        public void insertEvent(EventRecord record) {
            ReadReceiptProtocolV2.Event event = record.event;
            requireOne(INSERT_EVENT, new Object[]{bytes(record.streamEpoch), event.sequence,
                    bytes(event.eventId), bytes(event.batchId), event.chatId, event.userId,
                    event.watermark});
        }

        @Override
        public void insertLoss(LossRecord record) {
            ReadReceiptProtocolV2.Loss loss = record.loss;
            requireOne(INSERT_LOSS, new Object[]{bytes(record.streamEpoch),
                    bytes(loss.lossReceiptId), loss.firstSequence, loss.lastSequence,
                    loss.droppedEventCount, loss.droppedBatchCount, nullableBytes(loss.batchId),
                    loss.reason, loss.persistedAtMs, loss.persistedElapsedMs,
                    nullableBytes(loss.captureSessionId), nullableBytes(loss.captureBootId),
                    nullableBytes(loss.sourceEpochToken), loss.createdAtMs,
                    record.contentDigest.clone()});
        }

        @Override
        public void updateState(StateRecord state) {
            Counters counters = state.counters;
            requireOne(UPDATE_STATE, new Object[]{state.nextSequence, state.lastAckedSequence,
                    state.highestIssuedSequence, counters.confirmedDroppedEventCount,
                    counters.confirmedDroppedBatchCount, counters.uncertainOutcomeEventCount,
                    counters.uncertainOutcomeBatchCount, counters.captureDropEventCount,
                    counters.captureDropBatchCount, status(state.status), now(),
                    bytes(state.installationId), bytes(state.streamEpoch)});
        }

        @Override
        public void insertLease(LeaseRecord lease) {
            ReadReceiptProtocolV2.Counters counters = lease.counters;
            long now = now();
            requireOne(INSERT_LEASE, new Object[]{bytes(lease.streamEpoch), bytes(lease.leaseId),
                    lease.firstSequence, lease.lastSequence, lease.previousSequence,
                    lease.digest.clone(), status(lease.status),
                    counters.confirmedDroppedEventCount, counters.confirmedDroppedBatchCount,
                    counters.uncertainOutcomeEventCount, counters.uncertainOutcomeBatchCount,
                    counters.captureDropEventCount, counters.captureDropBatchCount, now, now});
        }

        @Override
        public int deleteEventsExact(List<EventRecord> expected) {
            requireActive();
            int removed = 0;
            for (EventRecord record : required(expected)) {
                ReadReceiptProtocolV2.Event event = record.event;
                removed = Math.addExact(removed, mutate(DELETE_EVENT,
                        new Object[]{bytes(record.streamEpoch), event.sequence,
                                bytes(event.eventId), bytes(event.batchId), event.chatId,
                                event.userId, event.watermark}));
            }
            return removed;
        }

        @Override
        public int deleteLossesExact(List<LossRecord> expected) {
            requireActive();
            int removed = 0;
            for (LossRecord record : required(expected)) {
                removed = Math.addExact(removed, mutate(DELETE_LOSS,
                        new Object[]{bytes(record.streamEpoch), bytes(record.loss.lossReceiptId),
                                record.loss.firstSequence, record.loss.lastSequence,
                                record.contentDigest.clone()}));
            }
            return removed;
        }

        @Override
        public int deleteUnreferencedBatchesExact(
                List<BatchRecord> candidates) {
            requireActive();
            int removed = 0;
            for (BatchRecord batch : required(candidates)) {
                byte[] epoch = bytes(batch.streamEpoch);
                byte[] batchId = bytes(batch.metadata.batchId);
                removed = Math.addExact(removed, mutate(DELETE_BATCH,
                        new Object[]{epoch, batchId, batch.metadataDigest.clone(), epoch, batchId,
                                epoch, batchId}));
            }
            return removed;
        }

        @Override
        public int deleteLeaseExact(LeaseRecord lease) {
            ReadReceiptProtocolV2.Counters counters = lease.counters;
            return mutate(DELETE_LEASE, new Object[]{bytes(lease.streamEpoch),
                    bytes(lease.leaseId), lease.firstSequence, lease.lastSequence,
                    lease.previousSequence, lease.digest.clone(), status(lease.status),
                    counters.confirmedDroppedEventCount, counters.confirmedDroppedBatchCount,
                    counters.uncertainOutcomeEventCount, counters.uncertainOutcomeBatchCount,
                    counters.captureDropEventCount, counters.captureDropBatchCount});
        }

        @Override
        public int replaceEventsWithLossExact(BatchRecord batch,
                                              List<EventRecord> events,
                                              LossRecord loss) {
            requireActive();
            if (!batch.streamEpoch.equals(loss.streamEpoch)
                    || !batch.metadata.batchId.equals(loss.loss.batchId)) throw failure();
            int removed = deleteEventsExact(events);
            if (removed != events.size()) throw failure();
            insertLoss(loss);
            return removed;
        }

        @Override
        public int replaceBatchSelectionWithLossExact(
                BatchSelection selection,
                LossRecord loss) {
            requireActive();
            if (!selection.batch.streamEpoch.equals(loss.streamEpoch)
                    || !selection.batch.metadata.batchId.equals(loss.loss.batchId)
                    || selection.firstSequence != loss.loss.firstSequence
                    || selection.lastSequence != loss.loss.lastSequence
                    || selection.eventCount != loss.loss.droppedEventCount
                    || selection.eventCount > Integer.MAX_VALUE) {
                throw failure();
            }
            int removed = mutateMany(DELETE_BATCH_SUFFIX_EVENTS,
                    new Object[]{bytes(selection.batch.streamEpoch),
                            bytes(selection.batch.metadata.batchId), selection.firstSequence,
                            selection.lastSequence});
            if (removed != (int) selection.eventCount) throw failure();
            insertLoss(loss);
            return removed;
        }

        @Override
        public int replaceLossSelectionWithAggregateExact(
                LossSelection selection,
                LossRecord aggregate) {
            requireActive();
            if (aggregate.loss.batchId != null
                    || aggregate.loss.firstSequence != selection.firstSequence
                    || aggregate.loss.lastSequence != selection.lastSequence
                    || aggregate.loss.droppedEventCount != selection.eventCount
                    || aggregate.loss.droppedBatchCount != selection.batchCount) {
                throw failure();
            }
            int removed = deleteLossesExact(selection.losses);
            if (removed != selection.losses.size()) throw failure();
            int removedBatches = deleteUnreferencedBatchesExact(selection.batches);
            if (removedBatches != selection.batches.size()) throw failure();
            insertLoss(aggregate);
            return removed;
        }

        @Override
        public void poison(String category) {
            requireActive();
            if (!ReadReceiptProtocolV2.validNack(category, false)) throw failure();
            LeaseRecord lease;
            try {
                lease = readLease(database);
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
            long now = now();
            if (mutate(POISON_STATE, new Object[]{category, now}) != 1) throw failure();
            int leaseRows = mutate(POISON_LEASE, new Object[]{category, now});
            if (leaseRows != (lease == null ? 0 : 1)) throw failure();
        }

        @Override
        public void commit() {
            requireActive();
            try {
                database.execute("COMMIT", null);
                active = false;
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public void close() {
            if (closed) return;
            boolean failed = false;
            if (active) {
                try {
                    database.execute("ROLLBACK", null);
                } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                    failed = true;
                }
                active = false;
            }
            try {
                database.close();
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                failed = true;
            }
            closed = true;
            if (failed) throw failure();
        }

        private void requireOne(String sql, Object[] arguments) {
            if (mutate(sql, arguments) != 1) throw failure();
        }

        private int mutate(String sql, Object[] arguments) {
            requireActive();
            try {
                int affected = database.update(sql, arguments);
                if (affected < 0 || affected > 1) throw failure();
                return affected;
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        private int mutateMany(String sql, Object[] arguments) {
            requireActive();
            try {
                int affected = database.update(sql, arguments);
                if (affected < 0) throw failure();
                return affected;
            } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException exception) {
                throw failure();
            }
        }

        private long now() {
            long value = clock.nowMs();
            if (value < 0) throw failure();
            return value;
        }

        private void requireActive() {
            if (!active || closed) throw failure();
        }
    }

    private static Snapshot control(
            ReadReceiptAndroidStartupStore.Database database)
            throws ReadReceiptAndroidStartupStore.Failure {
        return new Snapshot(readState(database), readLease(database),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private static Snapshot range(
            ReadReceiptAndroidStartupStore.Database database,
            StateRecord state,
            LeaseRecord lease,
            long first, long last) throws ReadReceiptAndroidStartupStore.Failure {
        Map<UUID, BatchRecord> batches = new LinkedHashMap<>();
        Map<Long, EventRecord> events = new LinkedHashMap<>();
        Map<UUID, LossRecord> losses = new LinkedHashMap<>();
        if (first <= last) {
            String[] bounds = new String[]{Long.toString(first), Long.toString(last)};
            readBatches(database, BATCH_RANGE_QUERY, bounds, batches);
            readEvents(database, EVENT_RANGE_QUERY, bounds, events);
            readLosses(database, LOSS_RANGE_QUERY, bounds, losses);
        }
        return snapshot(state, lease, batches, events, losses);
    }

    private static ReconcileResult reconcile(
            ReadReceiptAndroidStartupStore.Database database,
            BatchInput input)
            throws ReadReceiptAndroidStartupStore.Failure {
        StateRecord state = readState(database);
        String epoch = hex(bytes(state.streamEpoch));
        String id = hex(bytes(input.batchId));
        Map<UUID, BatchRecord> batches = new LinkedHashMap<>();
        readBatches(database, sqlForUuids(APPEND_BATCH_QUERY, epoch, id),
                (String[]) null, batches);
        if (batches.isEmpty()) return ReconcileResult.absent();
        if (batches.size() != 1) return ReconcileResult.conflict();
        BatchRecord batch = batches.values().iterator().next();
        ReadReceiptProtocolV2.Metadata metadata = batch.metadata;
        if (!batch.streamEpoch.equals(state.streamEpoch) || !batch.digestMatches()
                || metadata.eventCount != input.events.size()
                || metadata.persistedAtMs != input.persistedAtMs
                || metadata.persistedElapsedMs != input.persistedElapsedMs
                || !metadata.captureSessionId.equals(input.captureSessionId)
                || !sameNullable(metadata.captureBootId, input.captureBootId)
                || !sameNullable(metadata.sourceEpochToken, input.sourceEpochToken)
                || state.highestIssuedSequence < metadata.lastSequence
                || state.nextSequence <= metadata.lastSequence) {
            return ReconcileResult.conflict();
        }
        Map<UUID, LossRecord> losses = new LinkedHashMap<>();
        readLosses(database, sqlForUuids(APPEND_LOSS_QUERY, epoch, id),
                (String[]) null, losses);
        if (input.events.size() > ReadReceiptDurableStream.MAX_EVENT_ROWS) {
            if (losses.size() != 1 || hasAnyRow(database,
                    appendEventQuery(epoch, id, 1))) {
                return ReconcileResult.conflict();
            }
            LossRecord record = losses.values().iterator().next();
            ReadReceiptProtocolV2.Loss loss = record.loss;
            if (!record.streamEpoch.equals(state.streamEpoch) || !record.digestMatches()
                    || loss.firstSequence != metadata.firstSequence
                    || loss.lastSequence != metadata.lastSequence
                    || loss.droppedEventCount != input.events.size()
                    || loss.droppedBatchCount != 1 || !input.batchId.equals(loss.batchId)
                    || !"oversized_batch".equals(loss.reason)) {
                return ReconcileResult.conflict();
            }
            return ReconcileResult.committed(
                    new AppendResult(
                            AppendOutcome.CONFIRMED_LOSS,
                            metadata.firstSequence, metadata.lastSequence));
        }
        if (!losses.isEmpty() || !eventsMatch(
                database, input, metadata, state.streamEpoch, epoch)) {
            return ReconcileResult.conflict();
        }
        return ReconcileResult.committed(
                new AppendResult(
                        AppendOutcome.COMMITTED,
                        metadata.firstSequence, metadata.lastSequence));
    }

    private static boolean eventsMatch(ReadReceiptAndroidStartupStore.Database database,
                                       BatchInput input,
                                       ReadReceiptProtocolV2.Metadata metadata, UUID epoch,
                                       String epochHex)
            throws ReadReceiptAndroidStartupStore.Failure {
        int limit = input.events.size() >= QUERY_LIMIT
                ? QUERY_LIMIT : input.events.size() + 1;
        String sql = appendEventQuery(epochHex, hex(bytes(input.batchId)), limit);
        ReadReceiptAndroidStartupStore.Rows rows = database.query(sql, null);
        try {
            int index = 0;
            while (rows.next()) {
                if (index >= input.events.size()) return false;
                CapturedEvent expected = input.events.get(index);
                if (!uuid(rows.blobValue(0)).equals(epoch)
                        || rows.longValue(1) != metadata.firstSequence + index
                        || !uuid(rows.blobValue(2)).equals(expected.eventId)
                        || !uuid(rows.blobValue(3)).equals(input.batchId)
                        || rows.longValue(4) != expected.chatId
                        || rows.longValue(5) != expected.userId
                        || rows.longValue(6) != expected.watermark) {
                    return false;
                }
                index++;
            }
            return index == input.events.size();
        } finally {
            rows.close();
        }
    }

    private static boolean hasAnyRow(ReadReceiptAndroidStartupStore.Database database, String sql)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(sql, null);
        try {
            return rows.next();
        } finally {
            rows.close();
        }
    }

    private static BatchSelection maintenanceSelection(
            ReadReceiptAndroidStartupStore.Database database)
            throws ReadReceiptAndroidStartupStore.Failure {
        StateRecord state = readState(database);
        LeaseRecord lease = readLease(database);
        String leasePresent = lease == null ? null : "1";
        String leaseFirst = lease == null ? "0" : Long.toString(lease.firstSequence);
        String leaseLast = lease == null ? "0" : Long.toString(lease.lastSequence);
        String ack = Long.toString(state.lastAckedSequence);
        Map<UUID, BatchRecord> batches = new LinkedHashMap<>();
        readBatches(database, MAINTENANCE_BATCH_QUERY,
                new String[]{ack, leasePresent, leaseFirst, leaseLast}, batches);
        if (batches.isEmpty()) return null;
        if (batches.size() != 1) throw new ReadReceiptAndroidStartupStore.Failure();
        BatchRecord batch = batches.values().iterator().next();
        if (!batch.streamEpoch.equals(state.streamEpoch) || !batch.digestMatches()) {
            throw new ReadReceiptAndroidStartupStore.Failure();
        }
        long first = Math.max(batch.metadata.firstSequence, state.lastAckedSequence + 1);
        long last = batch.metadata.lastSequence;
        long count = last - first + 1;
        if (count <= 0 || count >= QUERY_LIMIT) {
            throw new ReadReceiptAndroidStartupStore.Failure();
        }
        if (scalar(database, MAINTENANCE_LOSS_COUNT_QUERY,
                new String[]{Long.toString(first), Long.toString(last)}) != 0) {
            throw new ReadReceiptAndroidStartupStore.Failure();
        }
        String shapeSql = sqlForUuid(MAINTENANCE_EVENT_SHAPE_QUERY,
                hex(bytes(batch.metadata.batchId)));
        ReadReceiptAndroidStartupStore.Rows rows = database.query(shapeSql,
                new String[]{Long.toString(first), Long.toString(last)});
        try {
            if (!rows.next() || rows.longValue(0) != count || rows.isNull(1) || rows.isNull(2)
                    || rows.longValue(1) != first || rows.longValue(2) != last || rows.next()) {
                throw new ReadReceiptAndroidStartupStore.Failure();
            }
        } finally {
            rows.close();
        }
        return new BatchSelection(batch, first, last, count,
                new ArrayList<>());
    }

    private static LossSelection lossCompactionSelection(
            ReadReceiptAndroidStartupStore.Database database)
            throws ReadReceiptAndroidStartupStore.Failure {
        StateRecord state = readState(database);
        LeaseRecord lease = readLease(database);
        String leasePresent = lease == null ? null : "1";
        String leaseFirst = lease == null ? "0" : Long.toString(lease.firstSequence);
        String leaseLast = lease == null ? "0" : Long.toString(lease.lastSequence);
        Map<UUID, LossRecord> losses = new LinkedHashMap<>();
        readLosses(database, LOSS_COMPACTION_QUERY,
                new String[]{Long.toString(state.lastAckedSequence), leasePresent,
                        leaseFirst, leaseLast}, losses);
        if (losses.isEmpty()) return null;
        long first = Long.MAX_VALUE;
        long last = 0;
        for (LossRecord loss : losses.values()) {
            first = Math.min(first, loss.loss.firstSequence);
            last = Math.max(last, loss.loss.lastSequence);
        }
        Map<UUID, BatchRecord> batches = new LinkedHashMap<>();
        Set<UUID> batchIds = new LinkedHashSet<>();
        for (LossRecord loss : losses.values()) {
            if (loss.loss.batchId != null) batchIds.add(loss.loss.batchId);
        }
        if (!batchIds.isEmpty()) {
            readBatches(database, lossBatchQuery(state.streamEpoch,
                    new ArrayList<>(batchIds)), (String[]) null, batches);
            if (batches.size() != batchIds.size()) {
                throw new ReadReceiptAndroidStartupStore.Failure();
            }
        }
        Snapshot candidate = new Snapshot(
                state, lease, new ArrayList<>(batches.values()), new ArrayList<>(),
                new ArrayList<>(losses.values()));
        LossSelection selection =
                ReadReceiptDurableStream.oldestUnleasedLossRun(candidate);
        if (selection == null) return null;
        if (scalar(database, LOSS_COMPACTION_EVENT_COUNT_QUERY,
                new String[]{Long.toString(selection.firstSequence),
                        Long.toString(selection.lastSequence)}) != 0) {
            throw new ReadReceiptAndroidStartupStore.Failure();
        }
        return selection;
    }

    static String lossBatchQuery(UUID streamEpoch, List<UUID> batchIds) {
        if (streamEpoch == null || batchIds == null || batchIds.isEmpty()
                || batchIds.size() > QUERY_LIMIT) throw failure();
        StringBuilder sql = new StringBuilder(
                "SELECT stream_epoch, batch_id, first_sequence, last_sequence, event_count, "
                        + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                        + "capture_boot_id, source_epoch_token, metadata_digest "
                        + "FROM read_receipt_batches WHERE stream_epoch = X'")
                .append(hex(bytes(streamEpoch))).append("' AND batch_id IN (");
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID batchId : batchIds) {
            if (batchId == null || !unique.add(batchId)) throw failure();
            if (unique.size() > 1) sql.append(", ");
            sql.append("X'").append(hex(bytes(batchId))).append("'");
        }
        return sql.append(") LIMIT ").append(QUERY_LIMIT).toString();
    }

    private static Snapshot read(
            ReadReceiptAndroidStartupStore.Database database, boolean includeTail)
            throws ReadReceiptAndroidStartupStore.Failure {
        StateRecord state = readState(database);
        LeaseRecord lease = readLease(database);
        String after = Long.toString(state.lastAckedSequence);
        Map<UUID, BatchRecord> batches = new LinkedHashMap<>();
        Map<Long, EventRecord> events = new LinkedHashMap<>();
        Map<UUID, LossRecord> losses = new LinkedHashMap<>();
        readBatches(database, BATCH_HEAD_QUERY, after, batches);
        readEvents(database, EVENT_HEAD_QUERY, after, events);
        readLosses(database, LOSS_HEAD_QUERY, after, losses);
        if (includeTail) {
            readBatches(database, BATCH_TAIL_QUERY, after, batches);
            readEvents(database, EVENT_TAIL_QUERY, after, events);
            readLosses(database, LOSS_TAIL_QUERY, after, losses);
        }
        List<BatchRecord> batchValues = new ArrayList<>(batches.values());
        List<EventRecord> eventValues = new ArrayList<>(events.values());
        List<LossRecord> lossValues = new ArrayList<>(losses.values());
        batchValues.sort(Comparator.comparingLong(value -> value.metadata.firstSequence));
        eventValues.sort(Comparator.comparingLong(value -> value.event.sequence));
        lossValues.sort(Comparator.comparingLong(value -> value.loss.firstSequence));
        return new Snapshot(state, lease,
                batchValues, eventValues, lossValues);
    }

    private static Snapshot snapshot(
            StateRecord state,
            LeaseRecord lease,
            Map<UUID, BatchRecord> batches,
            Map<Long, EventRecord> events,
            Map<UUID, LossRecord> losses) {
        List<BatchRecord> batchValues =
                new ArrayList<>(batches.values());
        List<EventRecord> eventValues =
                new ArrayList<>(events.values());
        List<LossRecord> lossValues =
                new ArrayList<>(losses.values());
        batchValues.sort(Comparator.comparingLong(value -> value.metadata.firstSequence));
        eventValues.sort(Comparator.comparingLong(value -> value.event.sequence));
        lossValues.sort(Comparator.comparingLong(value -> value.loss.firstSequence));
        return new Snapshot(state, lease,
                batchValues, eventValues, lossValues);
    }

    private static StateRecord readState(
            ReadReceiptAndroidStartupStore.Database database)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(STREAM_QUERY, null);
        try {
            if (!rows.next()) throw new ReadReceiptAndroidStartupStore.Failure();
            ReadReceiptAndroidStateProjection.Stream projection =
                    ReadReceiptAndroidStateProjection.decodeStream(rows);
            StateRecord state = new StateRecord(
                    projection.installationId, projection.streamEpoch,
                    projection.lastAckedSequence, projection.highestIssuedSequence,
                    projection.nextSequence, counters(projection.counters),
                    status(projection.status), projection.poisonCategory);
            if (rows.next()) throw new ReadReceiptAndroidStartupStore.Failure();
            return state;
        } finally {
            rows.close();
        }
    }

    private static LeaseRecord readLease(
            ReadReceiptAndroidStartupStore.Database database)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(LEASE_QUERY, null);
        try {
            if (!rows.next()) return null;
            ReadReceiptAndroidStateProjection.Lease projection =
                    ReadReceiptAndroidStateProjection.decodeLease(rows);
            LeaseRecord lease = new LeaseRecord(
                    projection.streamEpoch, projection.leaseId, projection.firstSequence,
                    projection.lastSequence, projection.previousSequence,
                    protocolCounters(projection.counters), digest(projection.digest),
                    status(projection.status), projection.poisonCategory);
            if (rows.next()) throw new ReadReceiptAndroidStartupStore.Failure();
            return lease;
        } finally {
            rows.close();
        }
    }

    private static void readBatches(ReadReceiptAndroidStartupStore.Database database, String sql,
                                    String after,
                                    Map<UUID, BatchRecord> destination)
            throws ReadReceiptAndroidStartupStore.Failure {
        readBatches(database, sql, new String[]{after}, destination);
    }

    private static void readBatches(ReadReceiptAndroidStartupStore.Database database, String sql,
                                    String[] arguments,
                                    Map<UUID, BatchRecord> destination)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(sql, arguments);
        try {
            int count = 0;
            while (rows.next()) {
                if (++count > QUERY_LIMIT) throw new ReadReceiptAndroidStartupStore.Failure();
                UUID epoch = uuid(rows.blobValue(0));
                UUID id = uuid(rows.blobValue(1));
                ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                        id, rows.longValue(2), rows.longValue(3), rows.longValue(4),
                        rows.longValue(5), rows.longValue(6), uuid(rows.blobValue(7)),
                        nullableUuid(rows, 8), nullableUuid(rows, 9));
                BatchRecord value =
                        new BatchRecord(epoch, metadata,
                                digest(rows.blobValue(10)));
                BatchRecord prior = destination.put(id, value);
                if (prior != null && !prior.same(value)) {
                    throw new ReadReceiptAndroidStartupStore.Failure();
                }
            }
        } finally {
            rows.close();
        }
    }

    private static void readEvents(ReadReceiptAndroidStartupStore.Database database, String sql,
                                   String after,
                                   Map<Long, EventRecord> destination)
            throws ReadReceiptAndroidStartupStore.Failure {
        readEvents(database, sql, new String[]{after}, destination);
    }

    private static void readEvents(ReadReceiptAndroidStartupStore.Database database, String sql,
                                   String[] arguments,
                                   Map<Long, EventRecord> destination)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(sql, arguments);
        try {
            int count = 0;
            while (rows.next()) {
                if (++count > QUERY_LIMIT) throw new ReadReceiptAndroidStartupStore.Failure();
                UUID epoch = uuid(rows.blobValue(0));
                long sequence = rows.longValue(1);
                EventRecord value =
                        new EventRecord(epoch,
                                new ReadReceiptProtocolV2.Event(sequence, uuid(rows.blobValue(2)),
                                        uuid(rows.blobValue(3)), rows.longValue(4),
                                        rows.longValue(5), rows.longValue(6)));
                EventRecord prior = destination.put(sequence, value);
                if (prior != null && !prior.same(value)) {
                    throw new ReadReceiptAndroidStartupStore.Failure();
                }
            }
        } finally {
            rows.close();
        }
    }

    private static void readLosses(ReadReceiptAndroidStartupStore.Database database, String sql,
                                   String after,
                                   Map<UUID, LossRecord> destination)
            throws ReadReceiptAndroidStartupStore.Failure {
        readLosses(database, sql, new String[]{after}, destination);
    }

    private static void readLosses(ReadReceiptAndroidStartupStore.Database database, String sql,
                                   String[] arguments,
                                   Map<UUID, LossRecord> destination)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(sql, arguments);
        try {
            int count = 0;
            while (rows.next()) {
                if (++count > QUERY_LIMIT) throw new ReadReceiptAndroidStartupStore.Failure();
                UUID epoch = uuid(rows.blobValue(0));
                UUID id = uuid(rows.blobValue(1));
                ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                        id, rows.longValue(2), rows.longValue(3), rows.longValue(4),
                        rows.longValue(5), nullableUuid(rows, 6), rows.stringValue(7),
                        nullableLong(rows, 8), nullableLong(rows, 9), nullableUuid(rows, 10),
                        nullableUuid(rows, 11), nullableUuid(rows, 12), rows.longValue(13));
                LossRecord value =
                        new LossRecord(epoch, loss,
                                digest(rows.blobValue(14)));
                LossRecord prior = destination.put(id, value);
                if (prior != null && !prior.same(value)) {
                    throw new ReadReceiptAndroidStartupStore.Failure();
                }
            }
        } finally {
            rows.close();
        }
    }

    private static Counters counters(
            ReadReceiptAndroidStateProjection.Counters counters) {
        return new Counters(counters.confirmedDroppedEventCount,
                counters.confirmedDroppedBatchCount, counters.uncertainOutcomeEventCount,
                counters.uncertainOutcomeBatchCount, counters.captureDropEventCount,
                counters.captureDropBatchCount);
    }

    private static ReadReceiptProtocolV2.Counters protocolCounters(
            ReadReceiptAndroidStateProjection.Counters counters) {
        return new ReadReceiptProtocolV2.Counters(counters.confirmedDroppedEventCount,
                counters.confirmedDroppedBatchCount, counters.uncertainOutcomeEventCount,
                counters.uncertainOutcomeBatchCount, counters.captureDropEventCount,
                counters.captureDropBatchCount);
    }

    private static Status status(String value)
            throws ReadReceiptAndroidStartupStore.Failure {
        if ("active".equals(value)) return Status.ACTIVE;
        if ("poisoned".equals(value)) return Status.POISONED;
        throw new ReadReceiptAndroidStartupStore.Failure();
    }

    private static String status(Status value) {
        if (value == Status.ACTIVE) return "active";
        if (value == Status.POISONED) return "poisoned";
        throw failure();
    }

    private static UUID nullableUuid(ReadReceiptAndroidStartupStore.Rows rows, int column)
            throws ReadReceiptAndroidStartupStore.Failure {
        return rows.isNull(column) ? null : uuid(rows.blobValue(column));
    }

    private static Long nullableLong(ReadReceiptAndroidStartupStore.Rows rows, int column)
            throws ReadReceiptAndroidStartupStore.Failure {
        return rows.isNull(column) ? null : rows.longValue(column);
    }

    private static String nullableString(ReadReceiptAndroidStartupStore.Rows rows, int column)
            throws ReadReceiptAndroidStartupStore.Failure {
        return rows.isNull(column) ? null : rows.stringValue(column);
    }

    private static UUID uuid(byte[] bytes) throws ReadReceiptAndroidStartupStore.Failure {
        if (bytes == null || bytes.length != 16) throw new ReadReceiptAndroidStartupStore.Failure();
        long high = 0;
        long low = 0;
        for (int index = 0; index < 8; index++) {
            high = high << 8 | bytes[index] & 0xffL;
            low = low << 8 | bytes[index + 8] & 0xffL;
        }
        return new UUID(high, low);
    }

    private static byte[] digest(byte[] value) throws ReadReceiptAndroidStartupStore.Failure {
        if (value == null || value.length != 32) throw new ReadReceiptAndroidStartupStore.Failure();
        return value.clone();
    }

    private static long scalar(ReadReceiptAndroidStartupStore.Database database, String sql)
            throws ReadReceiptAndroidStartupStore.Failure {
        return scalar(database, sql, null);
    }

    private static long scalar(ReadReceiptAndroidStartupStore.Database database, String sql,
                               String[] arguments)
            throws ReadReceiptAndroidStartupStore.Failure {
        ReadReceiptAndroidStartupStore.Rows rows = database.query(sql, arguments);
        try {
            if (!rows.next()) throw new ReadReceiptAndroidStartupStore.Failure();
            long value = rows.longValue(0);
            if (rows.next()) throw new ReadReceiptAndroidStartupStore.Failure();
            return value;
        } finally {
            rows.close();
        }
    }

    private static byte[] bytes(UUID value) {
        return ReadReceiptAndroidSqlite.uuidBytes(value);
    }

    private static byte[] nullableBytes(UUID value) {
        return value == null ? null : bytes(value);
    }

    private static boolean sameNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String sqlForUuid(String template, String hex) {
        requireHex(hex);
        return template.replace("%s", hex);
    }

    private static String sqlForUuids(String template, String... values) {
        Object[] arguments = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            requireHex(values[index]);
            arguments[index] = values[index];
        }
        return String.format(java.util.Locale.ROOT, template, arguments);
    }

    private static String appendEventQuery(String epoch, String batch, int limit) {
        requireHex(epoch);
        requireHex(batch);
        if (limit <= 0 || limit > QUERY_LIMIT) throw failure();
        return String.format(java.util.Locale.ROOT, APPEND_EVENT_QUERY, epoch, batch, limit);
    }

    private static void requireHex(String value) {
        if (value == null || value.length() != 32 || !value.matches("[0-9A-F]{32}")) {
            throw failure();
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            int unsigned = item & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static void closeQuietly(ReadReceiptAndroidStartupStore.Database database) {
        if (database == null) return;
        try {
            database.close();
        } catch (ReadReceiptAndroidStartupStore.Failure | RuntimeException ignored) {
        }
    }

    private static String batchQuery(String direction) {
        return "SELECT stream_epoch, batch_id, first_sequence, last_sequence, event_count, "
                + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, capture_boot_id, "
                + "source_epoch_token, metadata_digest FROM read_receipt_batches "
                + "WHERE last_sequence > ? ORDER BY first_sequence " + direction + ", batch_id "
                + direction + " LIMIT " + QUERY_LIMIT;
    }

    private static String eventQuery(String direction) {
        return "SELECT stream_epoch, sequence, event_id, batch_id, chat_id, user_id, watermark "
                + "FROM read_receipt_outbox WHERE sequence > ? ORDER BY sequence " + direction
                + " LIMIT " + QUERY_LIMIT;
    }

    private static String lossQuery(String direction) {
        return "SELECT stream_epoch, loss_receipt_id, first_sequence, last_sequence, "
                + "dropped_event_count, dropped_batch_count, batch_id, reason, persisted_at_ms, "
                + "persisted_elapsed_ms, capture_session_id, capture_boot_id, "
                + "source_epoch_token, created_at_ms, content_digest "
                + "FROM read_receipt_loss_segments WHERE last_sequence > ? "
                + "ORDER BY first_sequence " + direction + ", loss_receipt_id " + direction
                + " LIMIT " + QUERY_LIMIT;
    }

    private static <T> T required(T value) {
        if (value == null) throw failure();
        return value;
    }

    private static StorageException failure() {
        return new StorageException();
    }
}
