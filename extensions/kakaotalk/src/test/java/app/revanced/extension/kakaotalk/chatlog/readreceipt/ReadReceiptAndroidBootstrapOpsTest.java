package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public final class ReadReceiptAndroidBootstrapOpsTest {

    private static final String NO_BACKUP = "/opaque/no-backup";
    private static final String DATABASES = "/opaque/databases";
    private static final String LIVE = DATABASES + "/iris_read_receipts.db";
    private static final int UID = 12001;

    @Test
    public void existingDatabaseWithMissingOrInvalidTokenHasNoCreationOrDatabaseMutation() {
        FakeFileOps files = new FakeFileOps();
        files.nodes.put(LIVE, Node.file(UID, 0600, 7, 51, new byte[1]));
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);

        assertFalse(activate(files, sqlite, nativeCalls));

        assertEquals(0, files.createOpens);
        assertEquals(0, sqlite.readOnlyOpens);
        assertEquals(0, sqlite.readWriteOpens);
        assertEquals(0, nativeCalls.calls);

        files.nodes.put(ReadReceiptMarkers.installationTokenPath(NO_BACKUP),
                Node.file(UID, 0640, 7, 52, bytes(1, 32)));
        assertFalse(activate(files, sqlite, nativeCalls));
        assertEquals(0, files.createOpens);
        assertEquals(0, sqlite.readOnlyOpens);
        assertEquals(0, sqlite.readWriteOpens);
    }

    @Test
    public void existingExactCatalogMustMatchTokenDerivedInstallation() {
        FakeFileOps files = new FakeFileOps();
        files.nodes.put(LIVE, Node.file(UID, 0600, 7, 51, new byte[1]));
        files.nodes.put(ReadReceiptMarkers.installationTokenPath(NO_BACKUP),
                Node.file(UID, 0600, 7, 52, bytes(5, 32)));
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        sqlite.installation = bytes(99, 16);

        assertFalse(activate(files, sqlite, new FakeNativeCalls(files)));
        assertEquals(1, sqlite.readOnlyOpens);
        assertEquals(0, sqlite.readWriteOpens);
        assertEquals(1, sqlite.executions);
        assertEquals(1, sqlite.closes);
    }

    @Test
    public void absentDatabaseCreatesDurableTokenAndPublishesFreshExactDatabase() {
        FakeFileOps files = new FakeFileOps();
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);

        ReadReceiptAndroidBootstrapOps.ActivationContext activation =
                activateContext(files, sqlite, nativeCalls);

        assertNotNull(activation);
        assertArrayEquals(bytes(1, 32), activation.token());
        byte[] exposedToken = activation.token();
        exposedToken[0] ^= 0x7f;
        assertArrayEquals(bytes(1, 32), activation.token());

        assertEquals(2, files.createOpens);
        assertEquals(1, sqlite.readWriteOpens);
        assertEquals(1, sqlite.readOnlyOpens);
        assertTrue(sqlite.transactionCommitted);
        assertTrue(sqlite.executions >= ReadReceiptSchemaV2.createStatements().size() + 4);
        assertTrue(files.nodes.containsKey(LIVE));
        assertFalse(files.nodes.keySet().stream().anyMatch(
                path -> path.startsWith(NO_BACKUP + "/.iris_read_receipts.db.tmp-")
        ));
        assertEquals(1, nativeCalls.publishCalls);
        assertEquals(1, nativeCalls.pathFsyncCalls);
        assertEquals(1, nativeCalls.liveDirectoryFsyncCalls);
    }

    @Test
    public void publishedDatabaseWithExactAndroidMetadataRemainsReadyAcrossRestart() {
        FakeFileOps files = new FakeFileOps();
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);

        assertNotNull(activateContext(files, sqlite, nativeCalls));
        sqlite.includeAndroidMetadata = true;
        assertNotNull(activateContext(files, sqlite, nativeCalls));

        assertEquals(1, nativeCalls.publishCalls);
        assertEquals(2, sqlite.readOnlyOpens);
        assertEquals(1, sqlite.readWriteOpens);
    }

    @Test
    public void absentDatabaseTempIdentityAndSameDeviceAreEnforcedBeforeSqlite() {
        FakeFileOps invalid = new FakeFileOps();
        invalid.nextCreatedMode = 0640;
        FakeSqliteFactory invalidSqlite = new FakeSqliteFactory();
        assertFalse(activate(invalid, invalidSqlite, new FakeNativeCalls(invalid)));
        assertEquals(0, invalidSqlite.readWriteOpens);

        FakeFileOps crossDevice = new FakeFileOps();
        crossDevice.directoryDevice = 9;
        FakeSqliteFactory crossDeviceSqlite = new FakeSqliteFactory();
        assertFalse(activate(crossDevice, crossDeviceSqlite, new FakeNativeCalls(crossDevice)));
        assertEquals(0, crossDeviceSqlite.readWriteOpens);
        assertEquals(0, crossDevice.tempCount());
    }

    @Test
    public void competingPublisherCleanupRemovesOnlyExactLoserTemp() {
        FakeFileOps files = new FakeFileOps();
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);
        nativeCalls.competingOnPublish = true;

        assertNotNull(activateContext(files, sqlite, nativeCalls));

        assertEquals(1, nativeCalls.unlinkCalls);
        assertEquals(0, files.tempCount());
        assertTrue(files.nodes.containsKey(LIVE));
    }

    @Test
    public void startupCleanupRemovesPartialOwnedTempsInBoundedRetriesBeforeCreate() {
        FakeFileOps files = new FakeFileOps();
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);
        files.nodes.put(ReadReceiptMarkers.installationTokenPath(NO_BACKUP),
                Node.file(UID, 0600, 7, 52, bytes(1, 32)));

        for (int index = 0; index < 10; index++) {
            String name = String.format(".iris_read_receipts.db.tmp-%032x", index + 1);
            files.nodes.put(NO_BACKUP + "/" + name,
                    Node.file(UID, 0600, 7, 1000 + index,
                            index % 2 == 0 ? new byte[0] : new byte[37]));
        }
        files.nodes.put(NO_BACKUP + "/.iris_read_receipts.db.tmp-not-hex",
                Node.file(UID, 0600, 7, 2001, new byte[4096]));
        files.nodes.put(NO_BACKUP + "/unrelated.db",
                Node.file(UID, 0600, 7, 2002, new byte[4096]));

        assertFalse(activate(files, sqlite, nativeCalls));
        assertEquals(2, files.tempCount());
        assertEquals(0, files.tempCreateOpens);
        assertFalse(files.nodes.containsKey(LIVE));
        assertTrue(files.nodes.containsKey(NO_BACKUP + "/unrelated.db"));
        assertTrue(files.nodes.containsKey(
                NO_BACKUP + "/.iris_read_receipts.db.tmp-not-hex"));

        assertNotNull(activateContext(files, sqlite, nativeCalls));
        assertEquals(0, files.tempCount());
        assertEquals(1, files.tempCreateOpens);
        assertTrue(files.nodes.containsKey(LIVE));
        assertEquals(10, nativeCalls.unlinkCalls);
    }

    @Test
    public void suspiciousExactTempFailsClosedWithoutCreatingOrTouchingLiveDatabase() {
        FakeFileOps files = new FakeFileOps();
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);
        files.nodes.put(ReadReceiptMarkers.installationTokenPath(NO_BACKUP),
                Node.file(UID, 0600, 7, 52, bytes(1, 32)));
        String tempPath = NO_BACKUP
                + "/.iris_read_receipts.db.tmp-00000000000000000000000000000001";
        files.nodes.put(tempPath, Node.file(UID + 1, 0600, 7, 1000, new byte[0]));

        assertFalse(activate(files, sqlite, nativeCalls));

        assertEquals(0, files.tempCreateOpens);
        assertEquals(0, nativeCalls.unlinkCalls);
        assertTrue(files.nodes.containsKey(tempPath));
        assertFalse(files.nodes.containsKey(LIVE));
    }

    @Test
    public void pathReplacementDuringSqliteCreateCannotPublishAndClosesHeldDescriptor() {
        FakeFileOps files = new FakeFileOps();
        FakeSqliteFactory sqlite = new FakeSqliteFactory();
        sqlite.replacePathOnWritableClose = true;
        FakeNativeCalls nativeCalls = new FakeNativeCalls(files);

        assertFalse(activate(files, sqlite, nativeCalls));

        assertEquals(0, nativeCalls.publishCalls);
        assertFalse(files.nodes.containsKey(LIVE));
        assertEquals(0, files.openHandles);
    }

    private static boolean activate(
            FakeFileOps files,
            FakeSqliteFactory sqlite,
            FakeNativeCalls nativeCalls
    ) {
        return activateContext(files, sqlite, nativeCalls) != null;
    }

    private static ReadReceiptAndroidBootstrapOps.ActivationContext activateContext(
            FakeFileOps files,
            FakeSqliteFactory sqlite,
            FakeNativeCalls nativeCalls
    ) {
        sqlite.files = files;
        return ReadReceiptAndroidBootstrapOps.activate(
                files,
                new ReadReceiptAndroidSqlite(sqlite),
                new ReadReceiptNativeFs(() -> true, nativeCalls),
                new FixedRandom(),
                () -> 1234,
                NO_BACKUP,
                DATABASES,
                UID,
                directory -> files.names(directory)
        );
    }

    private static byte[] bytes(int seed, int count) {
        byte[] bytes = new byte[count];
        for (int index = 0; index < count; index++) bytes[index] = (byte) (seed + index);
        return bytes;
    }

    private static final class FixedRandom implements ReadReceiptAndroidBootstrapOps.RandomSource {
        private int seed = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (seed + index);
            seed += 31;
        }

        @Override
        public UUID nextUuid() {
            return UUID.fromString("10213243-5465-4787-98a9-bacbdcedfe0f");
        }
    }

    private static final class FakeFileOps implements ReadReceiptFileOps {
        private final Map<String, Node> nodes = new HashMap<>();
        private int nextInode = 100;
        private int nextCreatedMode = 0600;
        private long directoryDevice = 7;
        private int createOpens;
        private int tempCreateOpens;
        private int openHandles;

        private String[] names(String directory) {
            String prefix = directory + "/";
            List<String> names = new ArrayList<>();
            for (String path : nodes.keySet()) {
                if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0) {
                    names.add(path.substring(prefix.length()));
                }
            }
            return names.toArray(new String[0]);
        }

        private int tempCount() {
            int count = 0;
            for (String name : names(NO_BACKUP)) {
                if (name.matches("\\.iris_read_receipts\\.db\\.tmp-[0-9a-f]{32}")) count++;
            }
            return count;
        }

        @Override
        public Handle open(String path, OpenKind kind) throws Failure {
            if (kind == OpenKind.CREATE_EXCLUSIVE) {
                createOpens++;
                if (path.startsWith(NO_BACKUP + "/.iris_read_receipts.db.tmp-")) {
                    tempCreateOpens++;
                }
                if (nodes.containsKey(path)) throw new Failure(FailureReason.EXISTS);
                Node node = Node.file(UID, nextCreatedMode, 7, nextInode++, new byte[0]);
                nodes.put(path, node);
                openHandles++;
                return new FakeHandle(path, node, kind);
            }
            if (kind == OpenKind.DIRECTORY) {
                openHandles++;
                return new FakeHandle(path,
                        Node.directory(UID, 0700, directoryDevice, 2), kind);
            }
            Node node = nodes.get(path);
            if (node == null) throw new Failure(FailureReason.NOT_FOUND);
            openHandles++;
            return new FakeHandle(path, node, kind);
        }

        @Override
        public FileStatus fstat(Handle raw) {
            Node node = ((FakeHandle) raw).node;
            return new FileStatus(node.regular, !node.regular, node.uid, node.mode, node.bytes.length,
                    node.device, node.inode);
        }

        @Override
        public int read(Handle raw, byte[] bytes, int offset, int count) {
            FakeHandle handle = (FakeHandle) raw;
            int copied = Math.min(count, handle.node.bytes.length - handle.offset);
            if (copied <= 0) return 0;
            System.arraycopy(handle.node.bytes, handle.offset, bytes, offset, copied);
            handle.offset += copied;
            return copied;
        }

        @Override
        public int write(Handle raw, byte[] bytes, int offset, int count) {
            FakeHandle handle = (FakeHandle) raw;
            handle.node.bytes = new byte[count];
            System.arraycopy(bytes, offset, handle.node.bytes, 0, count);
            return count;
        }

        @Override
        public void fsync(Handle handle) {
        }

        @Override
        public void close(Handle handle) {
            openHandles--;
        }
    }

    private static final class FakeNativeCalls implements ReadReceiptNativeFs.NativeCalls {
        private final FakeFileOps files;
        private int calls;
        private int publishCalls;
        private int pathFsyncCalls;
        private int liveDirectoryFsyncCalls;
        private int unlinkCalls;
        private boolean competingOnPublish;

        private FakeNativeCalls(FakeFileOps files) {
            this.files = files;
        }

        @Override
        public int publish(String tempPath, String liveDirectory, String basename,
                           long expectedDevice, long expectedInode) {
            calls++;
            publishCalls++;
            String livePath = liveDirectory + "/" + basename;
            if (competingOnPublish) {
                files.nodes.put(livePath,
                        Node.file(UID, 0600, 7, 9000, new byte[4096]));
                return 1;
            }
            if (files.nodes.containsKey(livePath)) return 1;
            Node temp = files.nodes.remove(tempPath);
            if (temp == null) return 4;
            files.nodes.put(livePath, temp);
            return 0;
        }

        @Override
        public int fsyncPath(String path, long expectedDevice, long expectedInode) {
            calls++;
            pathFsyncCalls++;
            return 0;
        }

        @Override
        public int fsyncDirectory(String directory) {
            calls++;
            if (DATABASES.equals(directory)) liveDirectoryFsyncCalls++;
            return 0;
        }

        @Override
        public int unlinkIfExactInode(String directory, String basename,
                                      long expectedDevice, long expectedInode) {
            calls++;
            unlinkCalls++;
            String path = directory + "/" + basename;
            Node node = files.nodes.get(path);
            if (node == null) return 4;
            if (node.device != expectedDevice || node.inode != expectedInode) return 5;
            files.nodes.remove(path);
            return 0;
        }
    }

    private static final class FakeSqliteFactory implements ReadReceiptAndroidSqlite.Factory {
        private byte[] installation;
        private byte[] epoch = bytes(40, 16);
        private FakeFileOps files;
        private int readOnlyOpens;
        private int readWriteOpens;
        private int closes;
        private int executions;
        private boolean transactionCommitted;
        private boolean replacePathOnWritableClose;
        private boolean includeAndroidMetadata;

        @Override
        public ReadReceiptAndroidSqlite.Connection openReadOnly(String path) {
            readOnlyOpens++;
            return new Connection(this, path, false);
        }

        @Override
        public ReadReceiptAndroidSqlite.Connection openReadWrite(String path) {
            readWriteOpens++;
            return new Connection(this, path, true);
        }
    }

    private static final class Connection implements ReadReceiptAndroidSqlite.Connection {
        private final FakeSqliteFactory owner;
        private final String path;
        private final boolean writable;

        private Connection(FakeSqliteFactory owner, String path, boolean writable) {
            this.owner = owner;
            this.path = path;
            this.writable = writable;
        }

        @Override
        public ReadReceiptAndroidSqlite.Rows query(String sql, String[] arguments) {
            if (sql.equals("PRAGMA journal_mode=DELETE")) return new Rows(new Object[]{"delete"});
            if (sql.equals("PRAGMA wal_autocheckpoint=0")) return new Rows(new Object[]{0L});
            if (sql.equals("PRAGMA foreign_keys")) return new Rows(new Object[]{1L});
            if (sql.equals("PRAGMA page_size")) {
                return new Rows(new Object[]{ReadReceiptSchemaV2.PAGE_SIZE_BYTES});
            }
            if (sql.equals("PRAGMA auto_vacuum")) {
                return new Rows(new Object[]{ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL});
            }
            if (sql.equals("PRAGMA application_id")) {
                return new Rows(new Object[]{ReadReceiptSchemaV2.APPLICATION_ID});
            }
            if (sql.equals("PRAGMA user_version")) {
                return new Rows(new Object[]{ReadReceiptSchemaV2.USER_VERSION});
            }
            if (sql.contains("FROM sqlite_master")) {
                List<Object[]> rows = new ArrayList<>();
                for (ReadReceiptSchemaCatalog.SchemaObject object :
                        ReadReceiptSchemaV2.expectedObjects()) {
                    rows.add(new Object[]{object.type, object.name, object.tableName, object.sql});
                }
                if (owner.includeAndroidMetadata) {
                    rows.add(new Object[]{"table", "android_metadata", "android_metadata",
                            "CREATE TABLE android_metadata (locale TEXT)"});
                }
                return new Rows(rows.toArray(new Object[0][]));
            }
            if (sql.contains("FROM read_receipt_stream_state")) {
                return new Rows(new Object[]{owner.installation, owner.epoch, 1L, 0L, 0L,
                        0L, 0L, 0L, 0L, 0L, 0L, "active", null});
            }
            throw new AssertionError(sql);
        }

        @Override
        public void execute(String sql, Object[] arguments) {
            owner.executions++;
            if (sql.startsWith("INSERT INTO read_receipt_stream_state")) {
                owner.installation = ((byte[]) arguments[0]).clone();
                owner.epoch = ((byte[]) arguments[1]).clone();
            }
        }

        @Override
        public void beginTransaction() {
        }

        @Override
        public void setTransactionSuccessful() {
            owner.transactionCommitted = true;
        }

        @Override
        public void endTransaction() {
        }

        @Override
        public void close() {
            owner.closes++;
            if (writable && owner.files != null) {
                Node node = owner.files.nodes.get(path);
                if (owner.replacePathOnWritableClose) {
                    owner.files.nodes.put(path,
                            Node.file(UID, 0600, 7, 9001, new byte[4096]));
                } else if (node != null) {
                    node.bytes = new byte[4096];
                }
            }
        }
    }

    private static final class Rows implements ReadReceiptAndroidSqlite.Rows {
        private final Object[][] values;
        private int index = -1;

        private Rows(Object[]... values) {
            this.values = values;
        }

        @Override
        public boolean next() {
            index++;
            return index < values.length;
        }

        @Override
        public long longValue(int column) {
            return ((Number) values[index][column]).longValue();
        }

        @Override
        public String stringValue(int column) {
            return (String) values[index][column];
        }

        @Override
        public byte[] blobValue(int column) {
            return (byte[]) values[index][column];
        }

        @Override
        public boolean isNull(int column) {
            return values[index][column] == null;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeHandle implements ReadReceiptFileOps.Handle {
        private final String path;
        private final Node node;
        private final ReadReceiptFileOps.OpenKind kind;
        private int offset;

        private FakeHandle(String path, Node node, ReadReceiptFileOps.OpenKind kind) {
            this.path = path;
            this.node = node;
            this.kind = kind;
        }
    }

    private static final class Node {
        private final boolean regular;
        private final int uid;
        private final int mode;
        private final long device;
        private final long inode;
        private byte[] bytes;

        private Node(boolean regular, int uid, int mode, long device, long inode, byte[] bytes) {
            this.regular = regular;
            this.uid = uid;
            this.mode = mode;
            this.device = device;
            this.inode = inode;
            this.bytes = bytes;
        }

        private static Node file(int uid, int mode, long device, long inode, byte[] bytes) {
            return new Node(true, uid, mode, device, inode, bytes);
        }

        private static Node directory(int uid, int mode, long device, long inode) {
            return new Node(false, uid, mode, device, inode, new byte[0]);
        }
    }
}
