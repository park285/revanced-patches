package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReadReceiptAndroidDurableStoreFaultMatrixTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);
    private static final UUID BATCH = uuid(3);
    private static final UUID EVENT = uuid(4);
    private static final UUID SESSION = uuid(5);
    private static final UUID LEASE = uuid(6);

    private static final List<String> MUTATION_FIELDS = Arrays.asList(
            "INSERT_BATCH",
            "INSERT_EVENT",
            "INSERT_LOSS",
            "UPDATE_STATE",
            "INSERT_LEASE",
            "DELETE_EVENT",
            "DELETE_LOSS",
            "DELETE_BATCH",
            "DELETE_LEASE",
            "DELETE_BATCH_SUFFIX_EVENTS",
            "POISON_STATE",
            "POISON_LEASE");

    @Test
    public void everyProductionMutationStatementHasAnInjectedRollbackBoundary() throws Exception {
        Map<String, String> inventory = mutationInventory();
        assertEquals(new LinkedHashSet<>(MUTATION_FIELDS), inventory.keySet());

        for (Map.Entry<String, String> entry : inventory.entrySet()) {
            FaultDatabase database = new FaultDatabase();
            database.withLease = entry.getKey().startsWith("POISON_");
            Transaction transaction = store(database).beginImmediate();
            database.failSql = entry.getValue();
            try {
                exercise(entry.getKey(), transaction);
                fail("expected injected failure at " + entry.getKey());
            } catch (StorageException expected) {
                assertEquals("storage operation failed", expected.getMessage());
            } finally {
                database.failSql = null;
                transaction.close();
            }

            String trace = String.join("\n", database.trace);
            assertEquals(entry.getKey(), 1, database.faultHits);
            assertTrue(entry.getKey(), trace.contains("UPDATE:" + entry.getValue()));
            assertTrue(entry.getKey(), trace.contains("EXEC:ROLLBACK"));
            assertFalse(entry.getKey(), trace.contains("EXEC:COMMIT"));
        }
    }

    @Test
    public void beginAndCommitBoundariesAreExplicitlyFaultInjected() {
        FaultDatabase beginFailure = new FaultDatabase();
        beginFailure.failSql = "BEGIN IMMEDIATE";
        try {
            store(beginFailure).beginImmediate();
            fail("expected BEGIN IMMEDIATE failure");
        } catch (StorageException expected) {
            assertEquals(1, beginFailure.faultHits);
            assertTrue(beginFailure.trace.contains("EXEC:BEGIN IMMEDIATE"));
            assertTrue(beginFailure.trace.contains("CLOSE"));
            assertFalse(beginFailure.trace.contains("EXEC:COMMIT"));
        }

        FaultDatabase commitFailure = new FaultDatabase();
        Transaction transaction =
                store(commitFailure).beginImmediate();
        commitFailure.failSql = "COMMIT";
        try {
            transaction.commit();
            fail("expected COMMIT failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        } finally {
            commitFailure.failSql = null;
            transaction.close();
        }
        assertEquals(1, commitFailure.faultHits);
        assertTrue(commitFailure.trace.contains("EXEC:COMMIT"));
        assertTrue(commitFailure.trace.contains("EXEC:ROLLBACK"));

        FaultDatabase rollbackFailure = new FaultDatabase();
        Transaction rollingBack =
                store(rollbackFailure).beginImmediate();
        rollbackFailure.failSql = "ROLLBACK";
        try {
            rollingBack.close();
            fail("expected ROLLBACK failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        }
        assertEquals(1, rollbackFailure.faultHits);
        assertTrue(rollbackFailure.trace.contains("EXEC:ROLLBACK"));
        assertTrue(rollbackFailure.trace.contains("CLOSE"));
    }

    @Test
    public void postDeleteReferenceQueryFaultRollsBackAckMutation() {
        FaultDatabase database = new FaultDatabase();
        Transaction transaction = store(database).beginImmediate();
        ReadReceiptProtocolV2.Lease protocolLease = new ReadReceiptProtocolV2.Lease(
                INSTALLATION, EPOCH, LEASE, 1, 1, 0,
                Counters.zero().toProtocol(),
                Collections.singletonList(batch().metadata),
                Collections.singletonList(event().event), Collections.emptyList());
        LeaseProjection projection =
                new LeaseProjection(protocolLease,
                        Collections.singletonList(batch()), Collections.singletonList(event()),
                        Collections.emptyList());
        database.failSqlPrefix = "SELECT CASE WHEN EXISTS (SELECT 1 FROM read_receipt_outbox ";
        try {
            assertEquals(1, transaction.deleteEventsExact(
                    Collections.singletonList(event())));
            transaction.selectDeletableBatches(null, projection);
            fail("expected post-delete reference query failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        } finally {
            database.failSqlPrefix = null;
            transaction.close();
        }

        String trace = String.join("\n", database.trace);
        int delete = trace.indexOf("UPDATE:DELETE FROM read_receipt_outbox");
        int referenceQuery = trace.indexOf("QUERY:SELECT CASE WHEN EXISTS");
        int rollback = trace.indexOf("EXEC:ROLLBACK");
        assertTrue(delete >= 0);
        assertTrue(referenceQuery > delete);
        assertTrue(rollback > referenceQuery);
        assertEquals(1, database.faultHits);
    }

    private static Map<String, String> mutationInventory() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> expected = new LinkedHashSet<>(MUTATION_FIELDS);
        for (Field field : ReadReceiptAndroidDurableStore.class.getDeclaredFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            String sql = (String) field.get(null);
            if (sql == null || !(sql.startsWith("INSERT ") || sql.startsWith("UPDATE ")
                    || sql.startsWith("DELETE "))) continue;
            assertTrue("unmapped production mutation " + field.getName(),
                    expected.contains(field.getName()));
            result.put(field.getName(), sql);
        }
        return result;
    }

    private static void exercise(String field,
                                 Transaction transaction) {
        switch (field) {
            case "INSERT_BATCH":
                transaction.insertBatch(batch());
                return;
            case "INSERT_EVENT":
                transaction.insertEvent(event());
                return;
            case "INSERT_LOSS":
                transaction.insertLoss(loss());
                return;
            case "UPDATE_STATE":
                transaction.updateState(state());
                return;
            case "INSERT_LEASE":
                transaction.insertLease(lease());
                return;
            case "DELETE_EVENT":
                transaction.deleteEventsExact(Collections.singletonList(event()));
                return;
            case "DELETE_LOSS":
                transaction.deleteLossesExact(Collections.singletonList(loss()));
                return;
            case "DELETE_BATCH":
                transaction.deleteUnreferencedBatchesExact(Collections.singletonList(batch()));
                return;
            case "DELETE_LEASE":
                transaction.deleteLeaseExact(lease());
                return;
            case "DELETE_BATCH_SUFFIX_EVENTS":
                transaction.replaceBatchSelectionWithLossExact(selection(), loss());
                return;
            case "POISON_STATE":
            case "POISON_LEASE":
                transaction.poison("sequence_hole");
                return;
            default:
                throw new AssertionError("unhandled mutation " + field);
        }
    }

    private static ReadReceiptAndroidDurableStore store(FaultDatabase database) {
        return new ReadReceiptAndroidDurableStore("db", path -> database, () -> 7_000L);
    }

    private static StateRecord state() {
        return new StateRecord(INSTALLATION, EPOCH, 0, 1, 2,
                Counters.zero(), Status.ACTIVE);
    }

    private static BatchRecord batch() {
        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                BATCH, 1, 1, 1, 100, 200, SESSION, null, null);
        return new BatchRecord(EPOCH, metadata,
                ReadReceiptProtocolV2.metadataDigest(metadata));
    }

    private static EventRecord event() {
        return new EventRecord(EPOCH,
                new ReadReceiptProtocolV2.Event(1, EVENT, BATCH, 10, 20, 30));
    }

    private static LossRecord loss() {
        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                uuid(7), 1, 1, 1, 1, BATCH, "bounded_prune",
                100L, 200L, SESSION, null, null, 300L);
        return new LossRecord(EPOCH, loss,
                ReadReceiptProtocolV2.lossDigest(loss));
    }

    private static LeaseRecord lease() {
        ReadReceiptProtocolV2.Counters counters =
                Counters.zero().toProtocol();
        return new LeaseRecord(EPOCH, LEASE, 1, 1, 0,
                counters, new byte[32], Status.ACTIVE);
    }

    private static BatchSelection selection() {
        return new BatchSelection(batch(), 1, 1, 1,
                Collections.singletonList(event()));
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    private static byte[] bytes(UUID value) {
        return ReadReceiptAndroidSqlite.uuidBytes(value);
    }

    private static final class FaultDatabase implements ReadReceiptAndroidStartupStore.Database {
        final List<String> trace = new ArrayList<>();
        String failSql;
        String failSqlPrefix;
        int faultHits;
        boolean withLease;

        @Override
        public ReadReceiptAndroidStartupStore.Rows query(String sql, String[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("QUERY:" + sql);
            hit(sql);
            if ("PRAGMA foreign_keys".equals(sql)) return rows(row(1L));
            if ("PRAGMA wal_autocheckpoint=0".equals(sql)) return rows(row(0L));
            if ("PRAGMA page_size".equals(sql)) {
                return rows(row(ReadReceiptSchemaV2.PAGE_SIZE_BYTES));
            }
            if ("PRAGMA auto_vacuum".equals(sql)) {
                return rows(row(ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL));
            }
            if (ReadReceiptAndroidDurableStore.LEASE_QUERY.equals(sql)) {
                if (!withLease) return rows();
                return rows(row(1L, bytes(EPOCH), bytes(LEASE), 1L, 1L, 0L,
                        new byte[32], "active", null, 0L, 0L, 0L, 0L, 0L, 0L));
            }
            throw new ReadReceiptAndroidStartupStore.Failure();
        }

        @Override
        public void execute(String sql, Object[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("EXEC:" + sql);
            hit(sql);
        }

        @Override
        public int update(String sql, Object[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("UPDATE:" + sql);
            hit(sql);
            return 1;
        }

        @Override
        public void close() {
            trace.add("CLOSE");
        }

        private void hit(String sql) throws ReadReceiptAndroidStartupStore.Failure {
            if (sql.equals(failSql)
                    || failSqlPrefix != null && sql.startsWith(failSqlPrefix)) {
                faultHits++;
                throw new ReadReceiptAndroidStartupStore.Failure();
            }
        }
    }

    private static ReadReceiptAndroidStartupStore.Rows rows(Object[]... values) {
        return new FaultRows(Arrays.asList(values));
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static final class FaultRows implements ReadReceiptAndroidStartupStore.Rows {
        private final List<Object[]> rows;
        private int index = -1;

        FaultRows(List<Object[]> rows) {
            this.rows = rows;
        }

        @Override
        public boolean next() {
            index++;
            return index < rows.size();
        }

        @Override
        public long longValue(int column) {
            return ((Number) rows.get(index)[column]).longValue();
        }

        @Override
        public String stringValue(int column) {
            return (String) rows.get(index)[column];
        }

        @Override
        public byte[] blobValue(int column) {
            byte[] value = (byte[]) rows.get(index)[column];
            return value == null ? null : value.clone();
        }

        @Override
        public boolean isNull(int column) {
            return rows.get(index)[column] == null;
        }

        @Override
        public void close() {
        }
    }
}
