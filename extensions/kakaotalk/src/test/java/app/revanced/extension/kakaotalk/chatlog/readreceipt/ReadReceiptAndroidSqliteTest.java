package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class ReadReceiptAndroidSqliteTest {

    private static final byte[] INSTALLATION = bytes(3, 16);
    private static final byte[] EPOCH = bytes(31, 16);

    @Test
    public void exactReadOnlyCatalogAndInstallationAreAcceptedWithoutMutation() {
        FakeConnection connection = FakeConnection.exact(INSTALLATION, EPOCH);
        ReadReceiptAndroidSqlite sqlite = new ReadReceiptAndroidSqlite(
                new FakeFactory(connection));

        assertEquals(ReadReceiptAndroidSqlite.Classification.EXACT,
                sqlite.classifyReadOnly("opaque", INSTALLATION));
        assertEquals(1, connection.closeCount);
        assertEquals(1, connection.executed.size());
        assertEquals("PRAGMA foreign_keys=ON", connection.executed.get(0).sql);
        assertEquals(0, connection.beginCount);
    }

    @Test
    public void exactAndroidMetadataIsOptionalButMalformedOrDuplicatedMetadataIsRejected() {
        ReadReceiptSchemaCatalog.SchemaObject exact = object(
                "table", "android_metadata", "android_metadata",
                "CREATE TABLE android_metadata (locale TEXT)"
        );
        FakeConnection restart = FakeConnection.exact(INSTALLATION, EPOCH);
        restart.additionalCatalogObjects.add(exact);
        assertEquals(ReadReceiptAndroidSqlite.Classification.EXACT, classify(restart));

        List<ReadReceiptSchemaCatalog.SchemaObject> malformed = Arrays.asList(
                object("view", "android_metadata", "android_metadata", exact.sql),
                object("table", "Android_Metadata", "android_metadata", exact.sql),
                object("table", "android_metadata", "other", exact.sql),
                object("table", "android_metadata", "android_metadata", exact.sql + " "),
                object("table", "android_metadata", "android_metadata", null)
        );
        for (ReadReceiptSchemaCatalog.SchemaObject candidate : malformed) {
            FakeConnection connection = FakeConnection.exact(INSTALLATION, EPOCH);
            connection.additionalCatalogObjects.add(candidate);
            assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED, classify(connection));
        }

        FakeConnection duplicated = FakeConnection.exact(INSTALLATION, EPOCH);
        duplicated.additionalCatalogObjects.add(exact);
        duplicated.additionalCatalogObjects.add(exact);
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED, classify(duplicated));
    }

    @Test
    public void readOnlyMismatchUnknownAndPartialAlwaysCloseAndNeverMutate() {
        FakeConnection mismatch = FakeConnection.exact(bytes(70, 16), EPOCH);
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED,
                classify(mismatch));
        assertEquals(1, mismatch.closeCount);
        assertOnlyForeignKeysEnable(mismatch);

        FakeConnection partial = FakeConnection.exact(INSTALLATION, EPOCH);
        partial.omitLastCatalogObject = true;
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED,
                classify(partial));
        assertEquals(1, partial.closeCount);
        assertOnlyForeignKeysEnable(partial);

        FakeConnection failure = FakeConnection.exact(INSTALLATION, EPOCH);
        failure.failQuery = true;
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED,
                classify(failure));
        assertEquals(1, failure.closeCount);
        assertOnlyForeignKeysEnable(failure);

        FakeConnection unexpectedInternal = FakeConnection.exact(INSTALLATION, EPOCH);
        unexpectedInternal.additionalCatalogObjects.add(object(
                "table", "sqlite_sequence", "sqlite_sequence",
                "CREATE TABLE sqlite_sequence(name,seq)"
        ));
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED,
                classify(unexpectedInternal));

        FakeConnection pageSize = FakeConnection.exact(INSTALLATION, EPOCH);
        pageSize.pageSize = 8192;
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED,
                classify(pageSize));
        FakeConnection autoVacuum = FakeConnection.exact(INSTALLATION, EPOCH);
        autoVacuum.autoVacuum = 0;
        assertEquals(ReadReceiptAndroidSqlite.Classification.UNSUPPORTED,
                classify(autoVacuum));
    }

    @Test
    public void freshCreationUsesRollbackFkAndOneExactTransaction() {
        FakeConnection connection = FakeConnection.fresh();
        ReadReceiptAndroidSqlite sqlite = new ReadReceiptAndroidSqlite(
                new FakeFactory(connection));

        assertTrue(sqlite.createFresh("opaque", INSTALLATION, EPOCH, 1234));

        assertEquals(1, connection.beginCount);
        assertEquals(1, connection.successCount);
        assertEquals(1, connection.endCount);
        assertEquals(1, connection.closeCount);
        assertTrue(connection.containsSql("PRAGMA page_size=4096"));
        assertTrue(connection.containsSql("PRAGMA auto_vacuum=INCREMENTAL"));
        assertTrue(connection.containsSql("PRAGMA foreign_keys=ON"));
        for (String ddl : ReadReceiptSchemaV2.createStatements()) {
            assertTrue(connection.containsSql(ddl));
        }
        assertTrue(connection.containsSql(
                "PRAGMA application_id=" + ReadReceiptSchemaV2.APPLICATION_ID));
        assertTrue(connection.containsSql(
                "PRAGMA user_version=" + ReadReceiptSchemaV2.USER_VERSION));
        Execution insert = connection.executed.get(connection.executed.size() - 1);
        assertTrue(insert.sql.startsWith("INSERT INTO read_receipt_stream_state"));
        assertArrayEquals(INSTALLATION, (byte[]) insert.arguments[0]);
        assertArrayEquals(EPOCH, (byte[]) insert.arguments[1]);
        assertEquals(1234L, insert.arguments[2]);
        assertEquals(1234L, insert.arguments[3]);
    }

    @Test
    public void creationFailsClosedOnJournalFkTransactionEndOrCloseFailure() {
        FakeConnection journal = FakeConnection.fresh();
        journal.journal = "wal";
        assertFalse(create(journal));
        assertEquals(0, journal.beginCount);
        assertEquals(1, journal.closeCount);

        FakeConnection wal = FakeConnection.fresh();
        wal.walAutocheckpoint = 1;
        assertFalse(create(wal));
        assertEquals(0, wal.beginCount);

        FakeConnection foreignKeys = FakeConnection.fresh();
        foreignKeys.foreignKeys = 0;
        assertFalse(create(foreignKeys));
        assertEquals(0, foreignKeys.beginCount);

        FakeConnection pageSize = FakeConnection.fresh();
        pageSize.pageSize = 8192;
        assertFalse(create(pageSize));
        assertEquals(0, pageSize.beginCount);

        FakeConnection autoVacuum = FakeConnection.fresh();
        autoVacuum.autoVacuum = 0;
        assertFalse(create(autoVacuum));
        assertEquals(0, autoVacuum.beginCount);

        FakeConnection end = FakeConnection.fresh();
        end.failEnd = true;
        assertFalse(create(end));
        assertEquals(1, end.endCount);

        FakeConnection close = FakeConnection.fresh();
        close.failClose = true;
        assertFalse(create(close));
        assertEquals(1, close.closeCount);
    }

    @Test
    public void uuidEncodingIsCanonicalNetworkOrder() {
        assertEquals(SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ReadReceiptAndroidSqlite.readOnlyFlags());
        assertEquals(SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ReadReceiptAndroidSqlite.readWriteFlags());
        assertArrayEquals(new byte[]{
                        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                        (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb,
                        (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff},
                ReadReceiptAndroidSqlite.uuidBytes(
                        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")));
    }

    private static ReadReceiptAndroidSqlite.Classification classify(FakeConnection connection) {
        return new ReadReceiptAndroidSqlite(new FakeFactory(connection))
                .classifyReadOnly("opaque", INSTALLATION);
    }

    private static boolean create(FakeConnection connection) {
        return new ReadReceiptAndroidSqlite(new FakeFactory(connection))
                .createFresh("opaque", INSTALLATION, EPOCH, 1234);
    }

    private static void assertOnlyForeignKeysEnable(FakeConnection connection) {
        assertEquals(1, connection.executed.size());
        assertEquals("PRAGMA foreign_keys=ON", connection.executed.get(0).sql);
    }

    private static byte[] bytes(int seed, int size) {
        byte[] value = new byte[size];
        for (int index = 0; index < size; index++) value[index] = (byte) (seed + index);
        return value;
    }

    private static ReadReceiptSchemaCatalog.SchemaObject object(
            String type, String name, String tableName, String sql
    ) {
        return new ReadReceiptSchemaCatalog.SchemaObject(type, name, tableName, sql);
    }

    private static final class FakeFactory implements ReadReceiptAndroidSqlite.Factory {
        private final FakeConnection connection;

        private FakeFactory(FakeConnection connection) {
            this.connection = connection;
        }

        @Override
        public ReadReceiptAndroidSqlite.Connection openReadOnly(String path) {
            return connection;
        }

        @Override
        public ReadReceiptAndroidSqlite.Connection openReadWrite(String path) {
            return connection;
        }
    }

    private static final class FakeConnection implements ReadReceiptAndroidSqlite.Connection {
        private final byte[] installation;
        private final byte[] epoch;
        private final List<Execution> executed = new ArrayList<>();
        private String journal = "delete";
        private long walAutocheckpoint;
        private long foreignKeys = 1;
        private long pageSize = ReadReceiptSchemaV2.PAGE_SIZE_BYTES;
        private long autoVacuum = ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL;
        private boolean omitLastCatalogObject;
        private final List<ReadReceiptSchemaCatalog.SchemaObject> additionalCatalogObjects =
                new ArrayList<>();
        private boolean failQuery;
        private boolean failEnd;
        private boolean failClose;
        private int beginCount;
        private int successCount;
        private int endCount;
        private int closeCount;

        private FakeConnection(byte[] installation, byte[] epoch) {
            this.installation = installation;
            this.epoch = epoch;
        }

        private static FakeConnection exact(byte[] installation, byte[] epoch) {
            return new FakeConnection(installation, epoch);
        }

        private static FakeConnection fresh() {
            return new FakeConnection(null, null);
        }

        @Override
        public ReadReceiptAndroidSqlite.Rows query(String sql, String[] arguments)
                throws ReadReceiptAndroidSqlite.Failure {
            if (failQuery) throw new ReadReceiptAndroidSqlite.Failure();
            if (sql.equals("PRAGMA application_id")) {
                return rows(new Object[]{ReadReceiptSchemaV2.APPLICATION_ID});
            }
            if (sql.equals("PRAGMA user_version")) {
                return rows(new Object[]{ReadReceiptSchemaV2.USER_VERSION});
            }
            if (sql.equals("PRAGMA journal_mode=DELETE")) return rows(new Object[]{journal});
            if (sql.equals("PRAGMA wal_autocheckpoint=0")) {
                return rows(new Object[]{walAutocheckpoint});
            }
            if (sql.equals("PRAGMA foreign_keys")) return rows(new Object[]{foreignKeys});
            if (sql.equals("PRAGMA page_size")) return rows(new Object[]{pageSize});
            if (sql.equals("PRAGMA auto_vacuum")) return rows(new Object[]{autoVacuum});
            if (sql.contains("FROM sqlite_master")) {
                List<Object[]> values = new ArrayList<>();
                List<ReadReceiptSchemaCatalog.SchemaObject> expected =
                        ReadReceiptSchemaV2.expectedObjects();
                int limit = omitLastCatalogObject ? expected.size() - 1 : expected.size();
                for (int index = 0; index < limit; index++) {
                    ReadReceiptSchemaCatalog.SchemaObject object = expected.get(index);
                    values.add(new Object[]{object.type, object.name, object.tableName, object.sql});
                }
                for (ReadReceiptSchemaCatalog.SchemaObject object : additionalCatalogObjects) {
                    values.add(new Object[]{object.type, object.name, object.tableName, object.sql});
                }
                return rows(values.toArray(new Object[0][]));
            }
            if (sql.contains("FROM read_receipt_stream_state")) {
                return rows(new Object[]{installation, epoch, 1L, 0L, 0L,
                        0L, 0L, 0L, 0L, 0L, 0L, "active", null});
            }
            throw new AssertionError(sql);
        }

        @Override
        public void execute(String sql, Object[] arguments) {
            executed.add(new Execution(sql, arguments));
        }

        @Override
        public void beginTransaction() {
            beginCount++;
        }

        @Override
        public void setTransactionSuccessful() {
            successCount++;
        }

        @Override
        public void endTransaction() throws ReadReceiptAndroidSqlite.Failure {
            endCount++;
            if (failEnd) throw new ReadReceiptAndroidSqlite.Failure();
        }

        @Override
        public void close() throws ReadReceiptAndroidSqlite.Failure {
            closeCount++;
            if (failClose) throw new ReadReceiptAndroidSqlite.Failure();
        }

        private boolean containsSql(String sql) {
            for (Execution item : executed) if (item.sql.equals(sql)) return true;
            return false;
        }
    }

    private static ReadReceiptAndroidSqlite.Rows rows(Object[]... values) {
        return new FakeRows(Arrays.asList(values));
    }

    private static final class FakeRows implements ReadReceiptAndroidSqlite.Rows {
        private final List<Object[]> values;
        private int index = -1;

        private FakeRows(List<Object[]> values) {
            this.values = values;
        }

        @Override
        public boolean next() {
            index++;
            return index < values.size();
        }

        @Override
        public long longValue(int column) {
            return ((Number) values.get(index)[column]).longValue();
        }

        @Override
        public String stringValue(int column) {
            return (String) values.get(index)[column];
        }

        @Override
        public byte[] blobValue(int column) {
            return (byte[]) values.get(index)[column];
        }

        @Override
        public boolean isNull(int column) {
            return values.get(index)[column] == null;
        }

        @Override
        public void close() {
        }
    }

    private static final class Execution {
        private final String sql;
        private final Object[] arguments;

        private Execution(String sql, Object[] arguments) {
            this.sql = sql;
            this.arguments = arguments;
        }
    }
}
