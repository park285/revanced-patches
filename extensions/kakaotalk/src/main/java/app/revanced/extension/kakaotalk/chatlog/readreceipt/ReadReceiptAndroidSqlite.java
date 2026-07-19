package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class ReadReceiptAndroidSqlite {

    enum Classification {
        EXACT,
        UNSUPPORTED
    }

    interface Factory {
        Connection openReadOnly(String path) throws Failure;

        Connection openReadWrite(String path) throws Failure;
    }

    interface Connection {
        Rows query(String sql, String[] arguments) throws Failure;

        void execute(String sql, Object[] arguments) throws Failure;

        void beginTransaction() throws Failure;

        void setTransactionSuccessful() throws Failure;

        void endTransaction() throws Failure;

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

    private static final String CATALOG_QUERY =
            "SELECT type, name, tbl_name, sql FROM sqlite_master";
    private static final String STREAM_QUERY =
            "SELECT installation_id, stream_epoch "
                    + "FROM read_receipt_stream_state WHERE singleton = 1";
    private static final String INSERT_INITIAL_STREAM =
            "INSERT INTO read_receipt_stream_state "
                    + "(singleton, installation_id, stream_epoch, next_sequence, "
                    + "last_acked_sequence, highest_issued_sequence, "
                    + "confirmed_dropped_event_count, confirmed_dropped_batch_count, "
                    + "uncertain_outcome_event_count, uncertain_outcome_batch_count, "
                    + "capture_drop_event_count, capture_drop_batch_count, stream_status, "
                    + "poison_category, created_at_ms, updated_at_ms) "
                    + "VALUES (1, ?, ?, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'active', NULL, ?, ?)";
    private static final ReadReceiptSchemaCatalog.SchemaObject ANDROID_METADATA =
            new ReadReceiptSchemaCatalog.SchemaObject(
                    "table",
                    "android_metadata",
                    "android_metadata",
                    "CREATE TABLE android_metadata (locale TEXT)"
            );

    private final Factory factory;

    ReadReceiptAndroidSqlite() {
        this(new AndroidFactory());
    }

    ReadReceiptAndroidSqlite(Factory factory) {
        this.factory = factory;
    }

    Classification classifyReadOnly(String path, byte[] expectedInstallationId) {
        if (path == null || expectedInstallationId == null || expectedInstallationId.length != 16) {
            return Classification.UNSUPPORTED;
        }
        Connection connection = null;
        Classification classification = Classification.UNSUPPORTED;
        try {
            connection = factory.openReadOnly(path);
            connection.execute("PRAGMA foreign_keys=ON", null);
            if (scalarLong(connection, "PRAGMA foreign_keys") == 1
                    && scalarLong(connection, "PRAGMA page_size")
                    == ReadReceiptSchemaV2.PAGE_SIZE_BYTES
                    && scalarLong(connection, "PRAGMA auto_vacuum")
                    == ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) {
                long applicationId = scalarLong(connection, "PRAGMA application_id");
                long userVersion = scalarLong(connection, "PRAGMA user_version");
                List<ReadReceiptSchemaCatalog.SchemaObject> objects = readCatalog(connection);
                if (ReadReceiptSchemaV2.catalog().classify(applicationId, userVersion, objects)
                        == ReadReceiptSchemaCatalog.Classification.SUPPORTED
                        && hasMatchingSingleton(connection, expectedInstallationId)) {
                    classification = Classification.EXACT;
                }
            }
        } catch (Failure | RuntimeException ignored) {
            classification = Classification.UNSUPPORTED;
        }
        if (!closeStrict(connection)) {
            classification = Classification.UNSUPPORTED;
        }
        return classification;
    }

    boolean createFresh(
            String path,
            byte[] installationId,
            byte[] streamEpoch,
            long nowMillis
    ) {
        if (path == null
                || installationId == null || installationId.length != 16
                || streamEpoch == null || streamEpoch.length != 16
                || nowMillis < 0) {
            return false;
        }
        Connection connection = null;
        boolean transactionStarted = false;
        boolean created = false;
        try {
            connection = factory.openReadWrite(path);
            if (scalarLong(connection, "PRAGMA wal_autocheckpoint=0") != 0) return false;
            String journalMode = scalarString(connection, "PRAGMA journal_mode=DELETE");
            if (!"delete".equalsIgnoreCase(journalMode)) return false;
            connection.execute("PRAGMA page_size=" + ReadReceiptSchemaV2.PAGE_SIZE_BYTES, null);
            if (scalarLong(connection, "PRAGMA page_size")
                    != ReadReceiptSchemaV2.PAGE_SIZE_BYTES) return false;
            connection.execute("PRAGMA auto_vacuum=INCREMENTAL", null);
            if (scalarLong(connection, "PRAGMA auto_vacuum")
                    != ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) return false;
            connection.execute("PRAGMA foreign_keys=ON", null);
            if (scalarLong(connection, "PRAGMA foreign_keys") != 1) return false;

            connection.beginTransaction();
            transactionStarted = true;
            for (String statement : ReadReceiptSchemaV2.createStatements()) {
                connection.execute(statement, null);
            }
            connection.execute("PRAGMA application_id=" + ReadReceiptSchemaV2.APPLICATION_ID, null);
            connection.execute("PRAGMA user_version=" + ReadReceiptSchemaV2.USER_VERSION, null);
            connection.execute(
                    INSERT_INITIAL_STREAM,
                    new Object[]{installationId.clone(), streamEpoch.clone(), nowMillis, nowMillis}
            );
            connection.setTransactionSuccessful();
            transactionStarted = false;
            connection.endTransaction();
            created = true;
        } catch (Failure | RuntimeException ignored) {
            created = false;
        } finally {
            if (connection != null && transactionStarted) {
                try {
                    connection.endTransaction();
                } catch (Failure | RuntimeException ignored) {
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Failure | RuntimeException ignored) {
                    created = false;
                }
            }
        }
        return created;
    }

    private static boolean hasMatchingSingleton(Connection connection, byte[] installationId)
            throws Failure {
        Rows rows = connection.query(STREAM_QUERY, null);
        try {
            if (!rows.next()) return false;
            byte[] actualInstallation = rows.blobValue(0);
            byte[] epoch = rows.blobValue(1);
            boolean valid = Arrays.equals(installationId, actualInstallation)
                    && epoch != null && epoch.length == 16;
            return valid && !rows.next();
        } finally {
            rows.close();
        }
    }

    private static List<ReadReceiptSchemaCatalog.SchemaObject> readCatalog(Connection connection)
            throws Failure {
        Rows rows = connection.query(CATALOG_QUERY, null);
        try {
            List<ReadReceiptSchemaCatalog.SchemaObject> objects = new ArrayList<>();
            boolean androidMetadataSeen = false;
            while (rows.next()) {
                ReadReceiptSchemaCatalog.SchemaObject object =
                        new ReadReceiptSchemaCatalog.SchemaObject(
                                rows.stringValue(0),
                                rows.stringValue(1),
                                rows.stringValue(2),
                                rows.isNull(3) ? null : rows.stringValue(3)
                        );
                if (ANDROID_METADATA.equals(object)) {
                    if (androidMetadataSeen) throw new Failure();
                    androidMetadataSeen = true;
                } else {
                    objects.add(object);
                }
            }
            return objects;
        } finally {
            rows.close();
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Failure {
        Rows rows = connection.query(sql, null);
        try {
            if (!rows.next()) throw new Failure();
            long value = rows.longValue(0);
            if (rows.next()) throw new Failure();
            return value;
        } finally {
            rows.close();
        }
    }

    private static String scalarString(Connection connection, String sql) throws Failure {
        Rows rows = connection.query(sql, null);
        try {
            if (!rows.next()) throw new Failure();
            String value = rows.stringValue(0);
            if (rows.next()) throw new Failure();
            return value;
        } finally {
            rows.close();
        }
    }

    private static boolean closeStrict(Connection connection) {
        if (connection == null) return true;
        try {
            connection.close();
            return true;
        } catch (Failure | RuntimeException ignored) {
            return false;
        }
    }

    static byte[] uuidBytes(UUID value) {
        byte[] bytes = new byte[16];
        long high = value.getMostSignificantBits();
        long low = value.getLeastSignificantBits();
        for (int index = 0; index < 8; index++) {
            bytes[index] = (byte) (high >>> (56 - index * 8));
            bytes[index + 8] = (byte) (low >>> (56 - index * 8));
        }
        return bytes;
    }

    private static final class AndroidFactory implements Factory {
        @Override
        public Connection openReadOnly(String path) throws Failure {
            return open(path, readOnlyFlags());
        }

        @Override
        public Connection openReadWrite(String path) throws Failure {
            return open(path, readWriteFlags());
        }

        private static Connection open(String path, int flags) throws Failure {
            try {
                return new AndroidConnection(SQLiteDatabase.openDatabase(path, null, flags));
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }
    }

    static int readOnlyFlags() {
        return SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS;
    }

    static int readWriteFlags() {
        return SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS;
    }

    private static final class AndroidConnection implements Connection {
        private final SQLiteDatabase database;

        private AndroidConnection(SQLiteDatabase database) {
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
                if (arguments == null) database.execSQL(sql);
                else database.execSQL(sql, arguments);
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }

        @Override
        public void beginTransaction() throws Failure {
            invoke(database::beginTransaction);
        }

        @Override
        public void setTransactionSuccessful() throws Failure {
            invoke(database::setTransactionSuccessful);
        }

        @Override
        public void endTransaction() throws Failure {
            invoke(database::endTransaction);
        }

        @Override
        public void close() throws Failure {
            invoke(database::close);
        }

        private static void invoke(Action action) throws Failure {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                throw new Failure();
            }
        }
    }

    private static final class AndroidRows implements Rows {
        private final Cursor cursor;

        private AndroidRows(Cursor cursor) {
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

    private interface Action {
        void run();
    }
}
