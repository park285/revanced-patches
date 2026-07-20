package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public final class ReadReceiptAndroidStartupStoreTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);
    private static final UUID LEASE = uuid(3);
    private static final UUID BATCH = uuid(4);
    private static final UUID EVENT = uuid(5);
    private static final UUID SESSION = uuid(6);
    private static final UUID BOOT = uuid(7);
    private static final UUID SOURCE = uuid(8);
    private static final UUID LOSS = uuid(9);
    private static final byte[] LEASE_DIGEST = bytes(20, 32);
    private static final byte[] METADATA_DIGEST = bytes(60, 32);
    private static final byte[] LOSS_DIGEST = bytes(100, 32);

    @Test
    public void beginsImmediateMapsFrozenLeaseAndCommitsBeforeClose() {
        FakeDatabase database = validDatabase();
        ReadReceiptAndroidStartupStore store = store(database);

        ReadReceiptStartupValidation.Transaction transaction = store.beginImmediate();
        ReadReceiptStartupValidation.Snapshot snapshot = transaction.readSnapshot();

        assertEquals(ReadReceiptAndroidStartupStore.FOREIGN_KEYS_ENABLE,
                database.executions.get(0).sql);
        assertEquals("BEGIN IMMEDIATE", database.executions.get(1).sql);
        assertEquals(0, snapshot.foreignKeyViolationCount);
        assertEquals(1, snapshot.streamSingletonCount);
        assertEquals(1, snapshot.leaseSingletonCount);
        assertEquals(INSTALLATION, snapshot.state.installationId);
        assertEquals(EPOCH, snapshot.state.streamEpoch);
        assertArrayEquals(new long[]{16, 17, 18, 19, 20, 21},
                snapshot.state.counters.toArray());
        assertEquals(LEASE, snapshot.lease.leaseId);
        assertArrayEquals(new long[]{6, 7, 8, 9, 10, 11},
                snapshot.lease.counters.toArray());
        assertArrayEquals(LEASE_DIGEST, snapshot.lease.digest);
        assertEquals(1, snapshot.batches.size());
        assertArrayEquals(METADATA_DIGEST, snapshot.batches.get(0).metadataDigest);
        assertEquals(1, snapshot.events.size());
        assertEquals(-44L, snapshot.events.get(0).chatId);
        assertEquals(1, snapshot.losses.size());
        assertEquals(Long.valueOf(900), snapshot.losses.get(0).persistedAtMs);
        assertNull(snapshot.losses.get(0).persistedElapsedMs);
        assertArrayEquals(LOSS_DIGEST, snapshot.losses.get(0).contentDigest);

        transaction.commit();
        transaction.close();
        assertEquals("COMMIT", database.executions.get(database.executions.size() - 1).sql);
        assertEquals(1, database.closeCount);
        assertFalse(database.containsSql("ROLLBACK"));
    }

    @Test
    public void writableStartupRequiresExactHeaderAndDisablesWalAutocheckpointBeforeBegin() {
        FakeDatabase foreignKeys = validDatabase();
        foreignKeys.results.put(ReadReceiptAndroidStartupStore.FOREIGN_KEYS_QUERY,
                Collections.singletonList(new Object[]{0L}));
        assertStorageFailure(() -> store(foreignKeys).beginImmediate());
        assertFalse(foreignKeys.containsSql("BEGIN IMMEDIATE"));
        assertEquals(1, foreignKeys.closeCount);

        FakeDatabase checkpoint = validDatabase();
        checkpoint.results.put(ReadReceiptAndroidStartupStore.WAL_AUTOCHECKPOINT_DISABLE,
                Collections.singletonList(new Object[]{1L}));
        assertStorageFailure(() -> store(checkpoint).beginImmediate());
        assertFalse(checkpoint.containsSql("BEGIN IMMEDIATE"));
        assertEquals(1, checkpoint.closeCount);

        for (String query : Arrays.asList(ReadReceiptAndroidStartupStore.PAGE_SIZE_QUERY,
                ReadReceiptAndroidStartupStore.AUTO_VACUUM_QUERY)) {
            FakeDatabase mismatch = validDatabase();
            mismatch.results.put(query, Collections.singletonList(new Object[]{0L}));
            assertStorageFailure(() -> store(mismatch).beginImmediate());
            assertFalse(query, mismatch.containsSql("BEGIN IMMEDIATE"));
            assertEquals(query, 1, mismatch.closeCount);
        }
    }

    @Test
    public void digestIsReproducedOnlyFromCurrentSnapshotThroughCanonicalProtocol() {
        FakeDatabase database = validDatabase();
        ReadReceiptStartupValidation.Transaction transaction = store(database).beginImmediate();
        ReadReceiptStartupValidation.Snapshot snapshot = transaction.readSnapshot();

        ReadReceiptStartupValidation.LeaseProjection projection = projection(snapshot);
        assertArrayEquals(ReadReceiptProtocolV2.leaseDigest(projection.toProtocolLease()),
                transaction.reproduceLeaseDigest(projection));

        ReadReceiptStartupValidation.Snapshot replacement = transaction.readSnapshot();
        ReadReceiptStartupValidation.LeaseProjection stale = projection(snapshot);
        try {
            transaction.reproduceLeaseDigest(stale);
            fail("stale projection accepted");
        } catch (ReadReceiptStartupValidation.StorageException exception) {
            assertEquals("storage operation failed", exception.getMessage());
            assertNull(exception.getCause());
        }
        assertFalse(snapshot == replacement);
        transaction.close();
    }

    @Test
    public void poisonReturnsExactAffectedCountsAndUsesBoundedUpdates() {
        FakeDatabase database = validDatabase();
        database.updateCounts.put(ReadReceiptAndroidStartupStore.POISON_STREAM, 1);
        database.updateCounts.put(ReadReceiptAndroidStartupStore.POISON_LEASE, 1);
        ReadReceiptStartupValidation.Transaction transaction = store(database).beginImmediate();
        transaction.readSnapshot();

        ReadReceiptStartupValidation.AffectedRows affected =
                transaction.poison("digest_mismatch");

        assertEquals(1, affected.streamRows);
        assertEquals(1, affected.leaseRows);
        assertArrayEquals(new Object[]{"digest_mismatch"},
                database.updateArguments.get(ReadReceiptAndroidStartupStore.POISON_STREAM));
        assertArrayEquals(new Object[]{"digest_mismatch"},
                database.updateArguments.get(ReadReceiptAndroidStartupStore.POISON_LEASE));
        transaction.close();
        assertTrue(database.containsSql("ROLLBACK"));
        assertEquals(1, database.closeCount);
    }

    @Test
    public void noLeasePoisonReportsZeroLeaseRows() {
        FakeDatabase database = validDatabase();
        database.results.put(ReadReceiptAndroidStartupStore.LEASE_QUERY,
                Collections.emptyList());
        database.updateCounts.put(ReadReceiptAndroidStartupStore.POISON_STREAM, 1);
        database.updateCounts.put(ReadReceiptAndroidStartupStore.POISON_LEASE, 0);
        ReadReceiptStartupValidation.Transaction transaction = store(database).beginImmediate();
        ReadReceiptStartupValidation.Snapshot snapshot = transaction.readSnapshot();

        assertEquals(0, snapshot.leaseSingletonCount);
        assertNull(snapshot.lease);
        ReadReceiptStartupValidation.AffectedRows affected = transaction.poison("sequence_hole");
        assertEquals(1, affected.streamRows);
        assertEquals(0, affected.leaseRows);
        transaction.close();
    }

    @Test
    public void everyListIsLimitedAndAnExtraRowFailsClosed() {
        for (String query : Arrays.asList(
                ReadReceiptAndroidStartupStore.BATCH_QUERY,
                ReadReceiptAndroidStartupStore.EVENT_QUERY,
                ReadReceiptAndroidStartupStore.LOSS_QUERY)) {
            FakeDatabase database = validDatabase();
            Object[] row = database.results.get(query).get(0);
            List<Object[]> tooMany = new ArrayList<>();
            for (int index = 0; index < 257; index++) tooMany.add(row);
            database.results.put(query, tooMany);
            ReadReceiptStartupValidation.Transaction transaction = store(database).beginImmediate();
            assertStorageFailure(transaction::readSnapshot);
            transaction.close();
            assertTrue(database.containsSql("ROLLBACK"));
        }
        assertTrue(ReadReceiptAndroidStartupStore.BATCH_QUERY.endsWith("LIMIT 257"));
        assertTrue(ReadReceiptAndroidStartupStore.EVENT_QUERY.endsWith("LIMIT 257"));
        assertTrue(ReadReceiptAndroidStartupStore.LOSS_QUERY.endsWith("LIMIT 257"));
    }

    @Test
    public void healthyBacklogBeyondLeaseLimitIsValidatedByStreamingCoverage() {
        FakeDatabase database = validDatabase();
        database.results.put(ReadReceiptAndroidStartupStore.STREAM_QUERY,
                Collections.singletonList(new Object[]{1L, uuidBytes(INSTALLATION),
                        uuidBytes(EPOCH), 258L, 0L, 257L, 0L, 0L, 0L, 0L, 0L, 0L,
                        "active", null}));
        database.results.put(ReadReceiptAndroidStartupStore.LEASE_QUERY,
                Collections.emptyList());
        List<Object[]> coverage = new ArrayList<>();
        for (long sequence = 1; sequence <= 257; sequence++) {
            coverage.add(new Object[]{sequence, sequence});
        }
        database.results.put(ReadReceiptAndroidStartupStore.BACKLOG_COVERAGE_QUERY, coverage);
        ReadReceiptStartupValidation.Transaction transaction = store(database).beginImmediate();

        ReadReceiptStartupValidation.Snapshot snapshot = transaction.readSnapshot();
        String failure = transaction.validateBacklog(snapshot);

        assertNull(failure);
        assertTrue(snapshot.batches.isEmpty());
        assertTrue(snapshot.events.isEmpty());
        assertTrue(snapshot.losses.isEmpty());
        assertFalse(database.queriedRows.containsKey(ReadReceiptAndroidStartupStore.EVENT_QUERY));
        assertEquals(258, database.lastRows(
                ReadReceiptAndroidStartupStore.BACKLOG_COVERAGE_QUERY).nextCalls);
        transaction.close();
    }

    @Test
    public void singletonQueriesRejectSecondRowsAndForeignKeyCheckIsBounded() {
        FakeDatabase duplicateStream = validDatabase();
        duplicateStream.results.put(ReadReceiptAndroidStartupStore.STREAM_QUERY,
                Arrays.asList(streamRow(), streamRow()));
        ReadReceiptStartupValidation.Transaction streamTx = store(duplicateStream).beginImmediate();
        ReadReceiptStartupValidation.Snapshot duplicateSnapshot = streamTx.readSnapshot();
        assertEquals(2, duplicateSnapshot.streamSingletonCount);
        assertNull(duplicateSnapshot.state);
        streamTx.close();

        FakeDatabase duplicateLease = validDatabase();
        duplicateLease.results.put(ReadReceiptAndroidStartupStore.LEASE_QUERY,
                Arrays.asList(leaseRow(), leaseRow()));
        ReadReceiptStartupValidation.Transaction leaseTx =
                store(duplicateLease).beginImmediate();
        ReadReceiptStartupValidation.Snapshot leaseSnapshot =
                leaseTx.readSnapshot();
        assertEquals(2, leaseSnapshot.leaseSingletonCount);
        assertNull(leaseSnapshot.lease);
        leaseTx.close();

        FakeDatabase foreignKey = validDatabase();
        foreignKey.results.put(ReadReceiptAndroidStartupStore.FOREIGN_KEY_QUERY,
                Arrays.asList(new Object[]{"outbox", 1L, "batches", 0L},
                        new Object[]{"loss", 2L, "stream", 0L}));
        foreignKey.results.put(ReadReceiptAndroidStartupStore.BATCH_QUERY,
                Collections.singletonList(batchRow(uuid(99))));
        ReadReceiptStartupValidation.Transaction foreignKeyTx =
                store(foreignKey).beginImmediate();
        ReadReceiptStartupValidation.Snapshot foreignKeySnapshot =
                foreignKeyTx.readSnapshot();
        assertEquals(1, foreignKeySnapshot.foreignKeyViolationCount);
        assertEquals(1, foreignKeySnapshot.batches.size());
        assertEquals(1, foreignKey.lastRows(ReadReceiptAndroidStartupStore.FOREIGN_KEY_QUERY)
                .nextCalls);
        foreignKeyTx.close();
    }

    @Test
    public void beginReadCommitRollbackAndCloseFailuresAreCauseFreeAndBounded() {
        FakeDatabase beginFailure = validDatabase();
        beginFailure.failExecuteSql = "BEGIN IMMEDIATE";
        assertStorageFailure(() -> store(beginFailure).beginImmediate());
        assertEquals(1, beginFailure.closeCount);

        FakeDatabase queryFailure = validDatabase();
        queryFailure.failQuerySql = ReadReceiptAndroidStartupStore.STREAM_QUERY;
        ReadReceiptStartupValidation.Transaction queryTx = store(queryFailure).beginImmediate();
        assertStorageFailure(queryTx::readSnapshot);
        queryTx.close();
        assertTrue(queryFailure.containsSql("ROLLBACK"));

        FakeDatabase commitFailure = validDatabase();
        commitFailure.failExecuteSql = "COMMIT";
        ReadReceiptStartupValidation.Transaction commitTx = store(commitFailure).beginImmediate();
        assertStorageFailure(commitTx::commit);
        commitFailure.failExecuteSql = null;
        commitTx.close();
        assertTrue(commitFailure.containsSql("ROLLBACK"));

        FakeDatabase rollbackFailure = validDatabase();
        ReadReceiptStartupValidation.Transaction rollbackTx = store(rollbackFailure).beginImmediate();
        rollbackFailure.failExecuteSql = "ROLLBACK";
        assertStorageFailure(rollbackTx::close);
        assertEquals(1, rollbackFailure.closeCount);

        FakeDatabase closeFailure = validDatabase();
        ReadReceiptStartupValidation.Transaction closeTx = store(closeFailure).beginImmediate();
        closeTx.commit();
        closeFailure.failClose = true;
        assertStorageFailure(closeTx::close);
    }

    @Test
    public void invalidPoisonCategoryAndPoisonBeforeSnapshotFailWithoutSqlMutation() {
        FakeDatabase database = validDatabase();
        ReadReceiptStartupValidation.Transaction transaction = store(database).beginImmediate();
        int before = database.updateArguments.size();
        assertStorageFailure(() -> transaction.poison("database path: /sensitive"));
        assertStorageFailure(() -> transaction.poison("sequence_hole"));
        assertEquals(before, database.updateArguments.size());
        transaction.close();
    }

    private static ReadReceiptAndroidStartupStore store(FakeDatabase database) {
        return new ReadReceiptAndroidStartupStore("opaque.db", path -> database);
    }

    private static FakeDatabase validDatabase() {
        FakeDatabase database = new FakeDatabase();
        database.results.put(ReadReceiptAndroidStartupStore.FOREIGN_KEY_QUERY,
                Collections.emptyList());
        database.results.put(ReadReceiptAndroidStartupStore.FOREIGN_KEYS_QUERY,
                Collections.singletonList(new Object[]{1L}));
        database.results.put(ReadReceiptAndroidStartupStore.WAL_AUTOCHECKPOINT_DISABLE,
                Collections.singletonList(new Object[]{0L}));
        database.results.put(ReadReceiptAndroidStartupStore.PAGE_SIZE_QUERY,
                Collections.singletonList(new Object[]{ReadReceiptSchemaV2.PAGE_SIZE_BYTES}));
        database.results.put(ReadReceiptAndroidStartupStore.AUTO_VACUUM_QUERY,
                Collections.singletonList(
                        new Object[]{ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL}));
        database.results.put(ReadReceiptAndroidStartupStore.STREAM_QUERY,
                Collections.singletonList(streamRow()));
        database.results.put(ReadReceiptAndroidStartupStore.LEASE_QUERY,
                Collections.singletonList(leaseRow()));
        database.results.put(ReadReceiptAndroidStartupStore.BATCH_QUERY,
                Collections.singletonList(batchRow()));
        database.results.put(ReadReceiptAndroidStartupStore.EVENT_QUERY,
                Collections.singletonList(eventRow()));
        database.results.put(ReadReceiptAndroidStartupStore.LOSS_QUERY,
                Collections.singletonList(lossRow()));
        database.results.put(ReadReceiptAndroidStartupStore.BACKLOG_COVERAGE_QUERY,
                Arrays.asList(new Object[]{1L, 1L}, new Object[]{2L, 3L}));
        return database;
    }

    private static Object[] streamRow() {
        return new Object[]{1L, uuidBytes(INSTALLATION), uuidBytes(EPOCH), 4L, 0L, 3L,
                16L, 17L, 18L, 19L, 20L, 21L, "active", null};
    }

    private static Object[] leaseRow() {
        return new Object[]{1L, uuidBytes(EPOCH), uuidBytes(LEASE), 1L, 3L, 0L,
                LEASE_DIGEST, "active", null, 6L, 7L, 8L, 9L, 10L, 11L};
    }

    private static Object[] batchRow() {
        return batchRow(EPOCH);
    }

    private static Object[] batchRow(UUID epoch) {
        return new Object[]{uuidBytes(epoch), uuidBytes(BATCH), 1L, 3L, 3L, 800L, 850L,
                uuidBytes(SESSION), uuidBytes(BOOT), uuidBytes(SOURCE), METADATA_DIGEST};
    }

    private static Object[] eventRow() {
        return new Object[]{uuidBytes(EPOCH), 1L, uuidBytes(EVENT), uuidBytes(BATCH),
                -44L, 55L, 66L};
    }

    private static Object[] lossRow() {
        return new Object[]{uuidBytes(EPOCH), uuidBytes(LOSS), 2L, 3L, 2L, 0L,
                uuidBytes(BATCH), "retry_compaction", 900L, null, uuidBytes(SESSION),
                uuidBytes(BOOT), uuidBytes(SOURCE), 950L, LOSS_DIGEST};
    }

    private static ReadReceiptStartupValidation.LeaseProjection projection(
            ReadReceiptStartupValidation.Snapshot snapshot) {
        try {
            java.lang.reflect.Constructor<ReadReceiptStartupValidation.LeaseProjection> constructor =
                    ReadReceiptStartupValidation.LeaseProjection.class.getDeclaredConstructor(
                            ReadReceiptStartupValidation.Snapshot.class, UUID.class, UUID.class,
                            UUID.class, long.class, long.class, long.class,
                            ReadReceiptStartupValidation.Counters.class, List.class, List.class,
                            List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(snapshot, INSTALLATION, EPOCH, LEASE, 1L, 3L, 0L,
                    snapshot.lease.counters, snapshot.batches, snapshot.events, snapshot.losses);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertStorageFailure(Action action) {
        try {
            action.run();
            fail("storage failure expected");
        } catch (ReadReceiptStartupValidation.StorageException exception) {
            assertEquals("storage operation failed", exception.getMessage());
            assertNull(exception.getCause());
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static byte[] uuidBytes(UUID value) {
        return ReadReceiptAndroidSqlite.uuidBytes(value);
    }

    private static byte[] bytes(int seed, int size) {
        byte[] value = new byte[size];
        for (int index = 0; index < size; index++) value[index] = (byte) (seed + index);
        return value;
    }

    private interface Action {
        void run();
    }

    private static final class FakeDatabase implements ReadReceiptAndroidStartupStore.Database {
        final Map<String, List<Object[]>> results = new HashMap<>();
        final Map<String, Integer> updateCounts = new HashMap<>();
        final Map<String, Object[]> updateArguments = new HashMap<>();
        final List<Execution> executions = new ArrayList<>();
        final Map<String, FakeRows> queriedRows = new HashMap<>();
        String failExecuteSql;
        String failQuerySql;
        boolean failClose;
        int closeCount;

        @Override
        public ReadReceiptAndroidStartupStore.Rows query(String sql, String[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            if (sql.equals(failQuerySql)) throw new ReadReceiptAndroidStartupStore.Failure();
            List<Object[]> values = results.get(sql);
            if (values == null) throw new AssertionError(sql);
            FakeRows rows = new FakeRows(values);
            queriedRows.put(sql, rows);
            return rows;
        }

        @Override
        public void execute(String sql, Object[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            executions.add(new Execution(sql, arguments));
            if (sql.equals(failExecuteSql)) throw new ReadReceiptAndroidStartupStore.Failure();
        }

        @Override
        public int update(String sql, Object[] arguments) {
            updateArguments.put(sql, arguments == null ? null : arguments.clone());
            Integer result = updateCounts.get(sql);
            return result == null ? 0 : result;
        }

        @Override
        public void close() throws ReadReceiptAndroidStartupStore.Failure {
            closeCount++;
            if (failClose) throw new ReadReceiptAndroidStartupStore.Failure();
        }

        boolean containsSql(String sql) {
            for (Execution execution : executions) {
                if (execution.sql.equals(sql)) return true;
            }
            return false;
        }

        FakeRows lastRows(String query) {
            return queriedRows.get(query);
        }
    }

    private static final class FakeRows implements ReadReceiptAndroidStartupStore.Rows {
        private final List<Object[]> values;
        private int index = -1;
        private int nextCalls;
        private boolean closed;

        FakeRows(List<Object[]> values) {
            this.values = values;
        }

        @Override
        public boolean next() {
            nextCalls++;
            index++;
            return index < values.size();
        }

        @Override
        public long longValue(int column) {
            return ((Number) value(column)).longValue();
        }

        @Override
        public String stringValue(int column) {
            return (String) value(column);
        }

        @Override
        public byte[] blobValue(int column) {
            byte[] value = (byte[]) value(column);
            return value == null ? null : value.clone();
        }

        @Override
        public boolean isNull(int column) {
            return value(column) == null;
        }

        @Override
        public void close() {
            closed = true;
        }

        private Object value(int column) {
            if (closed || index < 0 || index >= values.size()) throw new AssertionError();
            return values.get(index)[column];
        }
    }

    private static final class Execution {
        final String sql;
        final Object[] arguments;

        Execution(String sql, Object[] arguments) {
            this.sql = sql;
            this.arguments = arguments;
        }
    }
}
