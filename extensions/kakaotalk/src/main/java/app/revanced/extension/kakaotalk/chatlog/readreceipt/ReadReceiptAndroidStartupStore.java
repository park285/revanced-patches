package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ReadReceiptAndroidStartupStore implements ReadReceiptStartupValidation.Store {
    private static final int LIST_LIMIT = 256;
    private static final Set<String> POISON_CATEGORIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "stream_poisoned", "sequence_hole", "range_mismatch", "lease_mismatch",
                    "digest_mismatch", "conflicting_replay", "batch_conflict",
                    "counter_regression", "stream_rollback", "unknown_epoch")));

    static final String FOREIGN_KEY_QUERY = "PRAGMA foreign_key_check";
    static final String FOREIGN_KEYS_ENABLE = "PRAGMA foreign_keys=ON";
    static final String FOREIGN_KEYS_QUERY = "PRAGMA foreign_keys";
    static final String WAL_AUTOCHECKPOINT_DISABLE = "PRAGMA wal_autocheckpoint=0";
    static final String PAGE_SIZE_QUERY = "PRAGMA page_size";
    static final String AUTO_VACUUM_QUERY = "PRAGMA auto_vacuum";
    static final String STREAM_QUERY =
            "SELECT singleton, installation_id, stream_epoch, next_sequence, "
                    + "last_acked_sequence, highest_issued_sequence, "
                    + "confirmed_dropped_event_count, confirmed_dropped_batch_count, "
                    + "uncertain_outcome_event_count, uncertain_outcome_batch_count, "
                    + "capture_drop_event_count, capture_drop_batch_count, stream_status, "
                    + "poison_category FROM read_receipt_stream_state ORDER BY singleton LIMIT 2";
    static final String LEASE_QUERY =
            "SELECT singleton, stream_epoch, lease_id, first_sequence, last_sequence, "
                    + "previous_sequence, lease_digest, state, nack_category, "
                    + "confirmed_dropped_event_count, confirmed_dropped_batch_count, "
                    + "uncertain_outcome_event_count, uncertain_outcome_batch_count, "
                    + "capture_drop_event_count, capture_drop_batch_count "
                    + "FROM read_receipt_transfer_lease ORDER BY singleton LIMIT 2";
    static final String BATCH_QUERY =
            "SELECT stream_epoch, batch_id, first_sequence, last_sequence, event_count, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, metadata_digest "
                    + "FROM read_receipt_batches WHERE last_sequence >= ? "
                    + "AND first_sequence <= ? ORDER BY first_sequence, batch_id LIMIT 257";
    static final String EVENT_QUERY =
            "SELECT stream_epoch, sequence, event_id, batch_id, chat_id, user_id, watermark "
                    + "FROM read_receipt_outbox WHERE sequence >= ? AND sequence <= ? "
                    + "ORDER BY sequence LIMIT 257";
    static final String LOSS_QUERY =
            "SELECT stream_epoch, loss_receipt_id, first_sequence, last_sequence, "
                    + "dropped_event_count, dropped_batch_count, batch_id, reason, "
                    + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                    + "capture_boot_id, source_epoch_token, created_at_ms, content_digest "
                    + "FROM read_receipt_loss_segments WHERE last_sequence >= ? "
                    + "AND first_sequence <= ? "
                    + "ORDER BY first_sequence, loss_receipt_id LIMIT 257";
    static final String BACKLOG_COVERAGE_QUERY =
            "SELECT first_sequence, last_sequence FROM ("
                    + "SELECT sequence AS first_sequence, sequence AS last_sequence "
                    + "FROM read_receipt_outbox UNION ALL "
                    + "SELECT first_sequence, last_sequence FROM read_receipt_loss_segments) "
                    + "ORDER BY first_sequence, last_sequence";
    static final String POISON_STREAM =
            "UPDATE read_receipt_stream_state SET stream_status = 'poisoned', "
                    + "poison_category = ? WHERE singleton = 1";
    static final String POISON_LEASE =
            "UPDATE read_receipt_transfer_lease SET state = 'poisoned', "
                    + "nack_category = ? WHERE singleton = 1";

    interface Factory {
        Database openReadWrite(String path) throws Failure;
    }

    interface Database {
        Rows query(String sql, String[] arguments) throws Failure;

        void execute(String sql, Object[] arguments) throws Failure;

        int update(String sql, Object[] arguments) throws Failure;

        void close() throws Failure;
    }

    interface Rows {
        boolean next() throws Failure;

        long longValue(int column) throws Failure;

        String stringValue(int column) throws Failure;

        byte[] blobValue(int column) throws Failure;

        boolean isNull(int column) throws Failure;

        void close() throws Failure;
    }

    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private final String databasePath;
    private final Factory factory;

    ReadReceiptAndroidStartupStore(String databasePath) {
        this(databasePath, new AndroidFactory());
    }

    ReadReceiptAndroidStartupStore(String databasePath, Factory factory) {
        this.databasePath = databasePath;
        this.factory = factory;
    }

    @Override
    public ReadReceiptStartupValidation.Transaction beginImmediate() {
        if (databasePath == null || databasePath.isEmpty() || factory == null) {
            throw storageFailure();
        }
        Database database = null;
        try {
            database = factory.openReadWrite(databasePath);
            if (database == null) throw new Failure();
            database.execute(FOREIGN_KEYS_ENABLE, null);
            if (scalarLong(database, FOREIGN_KEYS_QUERY) != 1
                    || scalarLong(database, WAL_AUTOCHECKPOINT_DISABLE) != 0
                    || scalarLong(database, PAGE_SIZE_QUERY)
                    != ReadReceiptSchemaV2.PAGE_SIZE_BYTES
                    || scalarLong(database, AUTO_VACUUM_QUERY)
                    != ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) {
                throw new Failure();
            }
            database.execute("BEGIN IMMEDIATE", null);
            return new Transaction(database);
        } catch (Failure | RuntimeException ignored) {
            closeAfterOpenFailure(database);
            throw storageFailure();
        }
    }

    private static final class Transaction implements ReadReceiptStartupValidation.Transaction {
        private final Database database;
        private boolean active = true;
        private boolean closed;
        private ReadReceiptStartupValidation.Snapshot currentSnapshot;

        private Transaction(Database database) {
            this.database = database;
        }

        @Override
        public ReadReceiptStartupValidation.Snapshot readSnapshot() {
            requireActive();
            try {
                int foreignKeyViolations = hasForeignKeyViolation(database) ? 1 : 0;
                Singleton<ReadReceiptStartupValidation.StreamState> stream = readStream(database);
                Singleton<ReadReceiptStartupValidation.ActiveLease> lease = readLease(database);
                List<ReadReceiptStartupValidation.BatchRecord> batches = Collections.emptyList();
                List<ReadReceiptStartupValidation.EventRecord> events = Collections.emptyList();
                List<ReadReceiptStartupValidation.LossRecord> losses = Collections.emptyList();
                if (stream.count == 1 && stream.value != null && lease.count == 1
                        && lease.value != null) {
                    String first = Long.toString(lease.value.firstSequence);
                    String last = Long.toString(lease.value.lastSequence);
                    batches = readBatches(database, first, last);
                    events = readEvents(database, first, last);
                    losses = readLosses(database, first, last);
                }
                currentSnapshot = new ReadReceiptStartupValidation.Snapshot(
                        foreignKeyViolations, stream.count, lease.count, stream.value,
                        batches, events, losses, lease.value);
                return currentSnapshot;
            } catch (Failure | RuntimeException ignored) {
                throw storageFailure();
            }
        }

        @Override
        public String validateBacklog(ReadReceiptStartupValidation.Snapshot snapshot) {
            requireActive();
            if (snapshot == null || currentSnapshot != snapshot || snapshot.state == null) {
                throw storageFailure();
            }
            try {
                return validateCoverage(database, snapshot.state.lastAckedSequence,
                        snapshot.state.highestIssuedSequence);
            } catch (Failure | RuntimeException ignored) {
                throw storageFailure();
            }
        }

        @Override
        public byte[] reproduceLeaseDigest(
                ReadReceiptStartupValidation.LeaseProjection projection) {
            requireActive();
            if (projection == null || currentSnapshot == null
                    || !projection.belongsTo(currentSnapshot)) {
                throw storageFailure();
            }
            return ReadReceiptProtocolV2.leaseDigest(projection.toProtocolLease());
        }

        @Override
        public ReadReceiptStartupValidation.AffectedRows poison(String category) {
            requireActive();
            if (currentSnapshot == null || !POISON_CATEGORIES.contains(category)) {
                throw storageFailure();
            }
            try {
                int streamRows = database.update(POISON_STREAM, new Object[]{category});
                int leaseRows = database.update(POISON_LEASE, new Object[]{category});
                return new ReadReceiptStartupValidation.AffectedRows(streamRows, leaseRows);
            } catch (Failure | RuntimeException ignored) {
                throw storageFailure();
            }
        }

        @Override
        public void commit() {
            requireActive();
            try {
                database.execute("COMMIT", null);
                active = false;
            } catch (Failure | RuntimeException ignored) {
                throw storageFailure();
            }
        }

        @Override
        public void close() {
            if (closed) return;
            boolean failed = false;
            if (active) {
                try {
                    database.execute("ROLLBACK", null);
                } catch (Failure | RuntimeException ignored) {
                    failed = true;
                }
                active = false;
            }
            try {
                database.close();
            } catch (Failure | RuntimeException ignored) {
                failed = true;
            }
            closed = true;
            if (failed) throw storageFailure();
        }

        private void requireActive() {
            if (!active || closed) throw storageFailure();
        }
    }

    private static boolean hasForeignKeyViolation(Database database) throws Failure {
        Rows rows = database.query(FOREIGN_KEY_QUERY, null);
        try {
            return rows.next();
        } finally {
            rows.close();
        }
    }

    private static long scalarLong(Database database, String sql) throws Failure {
        Rows rows = database.query(sql, null);
        try {
            if (!rows.next()) throw new Failure();
            long value = rows.longValue(0);
            if (rows.next()) throw new Failure();
            return value;
        } finally {
            rows.close();
        }
    }

    private static Singleton<ReadReceiptStartupValidation.StreamState> readStream(
            Database database) throws Failure {
        Rows rows = database.query(STREAM_QUERY, null);
        try {
            int count = 0;
            ReadReceiptStartupValidation.StreamState state = null;
            while (rows.next()) {
                count++;
                if (count == 1) state = mapStream(rows);
            }
            return count == 1 ? new Singleton<>(1, state) : new Singleton<>(count, null);
        } finally {
            rows.close();
        }
    }

    private static ReadReceiptStartupValidation.StreamState mapStream(Rows rows) throws Failure {
        ReadReceiptAndroidStateProjection.Stream projection =
                ReadReceiptAndroidStateProjection.decodeStream(rows);
        return new ReadReceiptStartupValidation.StreamState(projection.installationId,
                projection.streamEpoch, projection.lastAckedSequence,
                projection.highestIssuedSequence, projection.nextSequence,
                counters(projection.counters), status(projection.status),
                projection.poisonCategory);
    }

    private static Singleton<ReadReceiptStartupValidation.ActiveLease> readLease(
            Database database) throws Failure {
        Rows rows = database.query(LEASE_QUERY, null);
        try {
            int count = 0;
            ReadReceiptStartupValidation.ActiveLease lease = null;
            while (rows.next()) {
                count++;
                if (count == 1) lease = mapLease(rows);
            }
            return count == 1 ? new Singleton<>(1, lease) : new Singleton<>(count, null);
        } finally {
            rows.close();
        }
    }

    private static ReadReceiptStartupValidation.ActiveLease mapLease(Rows rows) throws Failure {
        ReadReceiptAndroidStateProjection.Lease projection =
                ReadReceiptAndroidStateProjection.decodeLease(rows);
        return new ReadReceiptStartupValidation.ActiveLease(
                projection.streamEpoch, projection.leaseId, projection.firstSequence,
                projection.lastSequence, projection.previousSequence,
                counters(projection.counters), projection.digest, status(projection.status),
                projection.poisonCategory);
    }

    private static List<ReadReceiptStartupValidation.BatchRecord> readBatches(
            Database database, String first, String last) throws Failure {
        Rows rows = database.query(BATCH_QUERY, new String[]{first, last});
        try {
            List<ReadReceiptStartupValidation.BatchRecord> values = new ArrayList<>();
            while (rows.next()) {
                requireRoom(values);
                uuid(rows.blobValue(0));
                values.add(new ReadReceiptStartupValidation.BatchRecord(
                        uuid(rows.blobValue(1)), rows.longValue(2), rows.longValue(3),
                        rows.longValue(4), rows.longValue(5), rows.longValue(6),
                        uuid(rows.blobValue(7)), nullableUuid(rows, 8),
                        nullableUuid(rows, 9), requiredBlob(rows, 10)));
            }
            return values;
        } finally {
            rows.close();
        }
    }

    private static List<ReadReceiptStartupValidation.EventRecord> readEvents(
            Database database, String first, String last) throws Failure {
        Rows rows = database.query(EVENT_QUERY, new String[]{first, last});
        try {
            List<ReadReceiptStartupValidation.EventRecord> values = new ArrayList<>();
            while (rows.next()) {
                requireRoom(values);
                uuid(rows.blobValue(0));
                values.add(new ReadReceiptStartupValidation.EventRecord(
                        rows.longValue(1), uuid(rows.blobValue(2)), uuid(rows.blobValue(3)),
                        rows.longValue(4), rows.longValue(5), rows.longValue(6)));
            }
            return values;
        } finally {
            rows.close();
        }
    }

    private static List<ReadReceiptStartupValidation.LossRecord> readLosses(
            Database database, String first, String last) throws Failure {
        Rows rows = database.query(LOSS_QUERY, new String[]{first, last});
        try {
            List<ReadReceiptStartupValidation.LossRecord> values = new ArrayList<>();
            while (rows.next()) {
                requireRoom(values);
                uuid(rows.blobValue(0));
                values.add(new ReadReceiptStartupValidation.LossRecord(
                        uuid(rows.blobValue(1)), rows.longValue(2), rows.longValue(3),
                        rows.longValue(4), rows.longValue(5), nullableUuid(rows, 6),
                        rows.stringValue(7), nullableLong(rows, 8), nullableLong(rows, 9),
                        nullableUuid(rows, 10), nullableUuid(rows, 11),
                        nullableUuid(rows, 12), rows.longValue(13), requiredBlob(rows, 14)));
            }
            return values;
        } finally {
            rows.close();
        }
    }

    private static void requireRoom(List<?> values) throws Failure {
        if (values.size() >= LIST_LIMIT) throw new Failure();
    }

    private static String validateCoverage(Database database, long lastAcked, long highestIssued)
            throws Failure {
        Rows rows = database.query(BACKLOG_COVERAGE_QUERY, null);
        try {
            long expected = Math.addExact(lastAcked, 1L);
            while (rows.next()) {
                long first = rows.longValue(0);
                long last = rows.longValue(1);
                if (first <= lastAcked) return "stream_rollback";
                if (first != expected || last < first || last > highestIssued) {
                    return "sequence_hole";
                }
                expected = Math.addExact(last, 1L);
            }
            return expected == Math.addExact(highestIssued, 1L) ? null : "sequence_hole";
        } catch (ArithmeticException exception) {
            return "sequence_hole";
        } finally {
            rows.close();
        }
    }

    private static ReadReceiptStartupValidation.Counters counters(
            ReadReceiptAndroidStateProjection.Counters counters) {
        return new ReadReceiptStartupValidation.Counters(
                counters.confirmedDroppedEventCount, counters.confirmedDroppedBatchCount,
                counters.uncertainOutcomeEventCount, counters.uncertainOutcomeBatchCount,
                counters.captureDropEventCount, counters.captureDropBatchCount);
    }

    private static ReadReceiptStartupValidation.Status status(String value) throws Failure {
        if ("active".equals(value)) return ReadReceiptStartupValidation.Status.ACTIVE;
        if ("poisoned".equals(value)) return ReadReceiptStartupValidation.Status.POISONED;
        throw new Failure();
    }

    private static String nullableString(Rows rows, int column) throws Failure {
        return rows.isNull(column) ? null : rows.stringValue(column);
    }

    private static Long nullableLong(Rows rows, int column) throws Failure {
        return rows.isNull(column) ? null : rows.longValue(column);
    }

    private static UUID nullableUuid(Rows rows, int column) throws Failure {
        return rows.isNull(column) ? null : uuid(rows.blobValue(column));
    }

    private static byte[] requiredBlob(Rows rows, int column) throws Failure {
        byte[] value = rows.blobValue(column);
        if (value == null) throw new Failure();
        return value.clone();
    }

    private static UUID uuid(byte[] value) throws Failure {
        if (value == null || value.length != 16) throw new Failure();
        long high = 0;
        long low = 0;
        for (int index = 0; index < 8; index++) {
            high = high << 8 | value[index] & 0xffL;
            low = low << 8 | value[index + 8] & 0xffL;
        }
        return new UUID(high, low);
    }

    private static void closeAfterOpenFailure(Database database) {
        if (database == null) return;
        try {
            database.close();
        } catch (Failure | RuntimeException ignored) {
        }
    }

    private static ReadReceiptStartupValidation.StorageException storageFailure() {
        return new ReadReceiptStartupValidation.StorageException();
    }

    private static final class Singleton<T> {
        final int count;
        final T value;

        Singleton(int count, T value) {
            this.count = count;
            this.value = value;
        }
    }

    static final class AndroidFactory implements Factory {
        @Override
        public Database openReadWrite(String path) throws Failure {
            try {
                return new AndroidDatabase(SQLiteDatabase.openDatabase(
                        path, null, SQLiteDatabase.OPEN_READWRITE));
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }
    }

    private static final class AndroidDatabase implements Database {
        private final SQLiteDatabase database;

        AndroidDatabase(SQLiteDatabase database) {
            this.database = database;
        }

        @Override
        public Rows query(String sql, String[] arguments) throws Failure {
            try {
                return new AndroidRows(database.rawQuery(sql, arguments));
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public void execute(String sql, Object[] arguments) throws Failure {
            try {
                if ("BEGIN IMMEDIATE".equals(sql)) {
                    database.beginTransactionNonExclusive();
                } else if ("COMMIT".equals(sql)) {
                    database.setTransactionSuccessful();
                    database.endTransaction();
                } else if ("ROLLBACK".equals(sql)) {
                    database.endTransaction();
                } else if (FOREIGN_KEYS_ENABLE.equals(sql)) {
                    database.setForeignKeyConstraintsEnabled(true);
                } else if (arguments == null) {
                    database.execSQL(sql);
                } else {
                    database.execSQL(sql, arguments);
                }
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public int update(String sql, Object[] arguments) throws Failure {
            SQLiteStatement statement = null;
            int affectedRows = 0;
            boolean failed = false;
            try {
                statement = database.compileStatement(sql);
                bind(statement, arguments);
                affectedRows = statement.executeUpdateDelete();
            } catch (RuntimeException ignored) {
                failed = true;
            } finally {
                if (statement != null) {
                    try {
                        statement.close();
                    } catch (RuntimeException ignored) {
                        failed = true;
                    }
                }
            }
            if (failed) throw new Failure();
            return affectedRows;
        }

        @Override
        public void close() throws Failure {
            try {
                database.close();
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        private static void bind(SQLiteStatement statement, Object[] arguments) throws Failure {
            if (arguments == null) return;
            for (int index = 0; index < arguments.length; index++) {
                Object value = arguments[index];
                int position = index + 1;
                if (value == null) statement.bindNull(position);
                else if (value instanceof byte[]) statement.bindBlob(position, (byte[]) value);
                else if (value instanceof Number) {
                    statement.bindLong(position, ((Number) value).longValue());
                } else if (value instanceof String) statement.bindString(position, (String) value);
                else throw new Failure();
            }
        }
    }

    private static final class AndroidRows implements Rows {
        private final Cursor cursor;

        AndroidRows(Cursor cursor) {
            this.cursor = cursor;
        }

        @Override
        public boolean next() throws Failure {
            try {
                return cursor.moveToNext();
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public long longValue(int column) throws Failure {
            try {
                return cursor.getLong(column);
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public String stringValue(int column) throws Failure {
            try {
                return cursor.getString(column);
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public byte[] blobValue(int column) throws Failure {
            try {
                return cursor.getBlob(column);
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public boolean isNull(int column) throws Failure {
            try {
                return cursor.isNull(column);
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public void close() throws Failure {
            try {
                cursor.close();
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }
    }
}
