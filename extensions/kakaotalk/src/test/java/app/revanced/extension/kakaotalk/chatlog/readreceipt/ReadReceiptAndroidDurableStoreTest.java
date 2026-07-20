package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReadReceiptAndroidDurableStoreTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);
    private static final UUID BATCH = uuid(3);
    private static final UUID EVENT = uuid(4);
    private static final UUID SESSION = uuid(5);
    private static final UUID LEASE = uuid(6);

    @Test
    public void startupAndDurableStateQueriesKeepExactProjectionOrder() {
        assertEquals(ReadReceiptAndroidStartupStore.STREAM_QUERY,
                ReadReceiptAndroidDurableStore.STREAM_QUERY);
        assertEquals(ReadReceiptAndroidStartupStore.LEASE_QUERY,
                ReadReceiptAndroidDurableStore.LEASE_QUERY);
    }

    @Test
    public void writableOpenConfiguresAndVerifiesBeforeImmediateTransaction() {
        FakeDatabase database = new FakeDatabase();
        ReadReceiptAndroidDurableStore store = store(database);

        Transaction transaction = store.beginImmediate();
        transaction.close();

        assertEquals(Arrays.asList(
                "EXEC:PRAGMA foreign_keys=ON",
                "QUERY:PRAGMA foreign_keys",
                "QUERY:PRAGMA wal_autocheckpoint=0",
                "QUERY:PRAGMA page_size",
                "QUERY:PRAGMA auto_vacuum",
                "EXEC:BEGIN IMMEDIATE",
                "EXEC:ROLLBACK",
                "CLOSE"), database.trace);
    }

    @Test
    public void snapshotMapsCanonicalRowsWithBoundedHeadQueries() {
        FakeDatabase database = new FakeDatabase();
        database.withContent = true;
        ReadReceiptAndroidDurableStore store = store(database);
        Transaction transaction = store.beginImmediate();

        Snapshot snapshot = transaction.readSnapshot();
        transaction.commit();
        transaction.close();

        assertEquals(INSTALLATION, snapshot.state.installationId);
        assertEquals(EPOCH, snapshot.state.streamEpoch);
        assertEquals(1, snapshot.batches.size());
        assertEquals(EPOCH, snapshot.batches.get(0).streamEpoch);
        assertEquals(1, snapshot.events.size());
        assertEquals(1, snapshot.events.get(0).event.sequence);
        assertTrue(snapshot.losses.isEmpty());
        assertNull(snapshot.lease);
        assertTrue(ReadReceiptAndroidDurableStore.BATCH_HEAD_QUERY.contains("LIMIT 257"));
        assertTrue(ReadReceiptAndroidDurableStore.EVENT_HEAD_QUERY.contains("LIMIT 257"));
        assertTrue(ReadReceiptAndroidDurableStore.LOSS_HEAD_QUERY.contains("LIMIT 257"));
    }

    @Test
    public void leaseWindowUsesOnlyIndexedBoundedRangeQueries() {
        FakeDatabase database = new FakeDatabase();
        database.withContent = true;
        Transaction transaction = store(database).beginImmediate();

        Snapshot snapshot = transaction.readLeaseWindow();
        transaction.commit();
        transaction.close();

        assertEquals(1, snapshot.events.size());
        String trace = String.join("\n", database.trace);
        assertTrue(trace.contains(ReadReceiptAndroidDurableStore.BATCH_RANGE_QUERY));
        assertTrue(trace.contains(ReadReceiptAndroidDurableStore.EVENT_RANGE_QUERY));
        assertTrue(trace.contains(ReadReceiptAndroidDurableStore.LOSS_RANGE_QUERY));
        assertFalse(trace.contains(ReadReceiptAndroidDurableStore.BATCH_TAIL_QUERY));
        assertTrue(ReadReceiptAndroidDurableStore.BATCH_RANGE_QUERY.contains("LIMIT 257"));
        assertTrue(ReadReceiptAndroidDurableStore.EVENT_RANGE_QUERY.contains("LIMIT 257"));
        assertTrue(ReadReceiptAndroidDurableStore.LOSS_RANGE_QUERY.contains("LIMIT 257"));
    }

    @Test
    public void poisonUpdatesOnlyStateAndActiveLeaseWithoutEvidenceDeletion() {
        FakeDatabase database = new FakeDatabase();
        database.withLease = true;
        Transaction transaction = store(database).beginImmediate();

        transaction.poison("sequence_hole");
        transaction.commit();
        transaction.close();

        String trace = String.join("\n", database.trace);
        assertTrue(trace.contains("UPDATE read_receipt_stream_state SET stream_status = 'poisoned'"));
        assertTrue(trace.contains("UPDATE read_receipt_transfer_lease SET state = 'poisoned'"));
        assertFalse(trace.contains("DELETE FROM read_receipt_outbox"));
        assertFalse(trace.contains("DELETE FROM read_receipt_batches"));
        assertFalse(trace.contains("DELETE FROM read_receipt_loss_segments"));
    }

    @Test
    public void appendReconciliationUsesProductionBoundPlusOneWithoutFullSnapshot() {
        FakeDatabase database = new FakeDatabase();
        database.appendEventCount = 256;
        database.highestIssued = 256;
        ReadReceiptAndroidDurableStore store = store(database);
        BatchInput input = input(256);

        ReconcileResult result = store.reconcileAppend(input);

        assertEquals(AppendOutcome.COMMITTED, result.result.outcome);
        String trace = String.join("\n", database.trace);
        assertTrue(trace.contains("FROM read_receipt_outbox WHERE stream_epoch = X'"));
        assertTrue(trace.contains("AND batch_id = X'80000000000000000000000000000003'"));
        assertFalse(trace.contains(ReadReceiptAndroidDurableStore.EVENT_HEAD_QUERY));
        assertFalse(trace.contains(ReadReceiptAndroidDurableStore.EVENT_TAIL_QUERY));
        assertTrue(trace.contains("ORDER BY sequence LIMIT 257"));
    }

    @Test
    public void compositeIdentityQueriesUseIndexSearchAt250kBacklog() throws Exception {
        String epoch = hex(EPOCH);
        String batch = hex(BATCH);
        String appendBatch = String.format(Locale.ROOT,
                ReadReceiptAndroidDurableStore.APPEND_BATCH_QUERY, epoch, batch);
        String appendEvent = String.format(Locale.ROOT,
                ReadReceiptAndroidDurableStore.APPEND_EVENT_QUERY, epoch, batch, 257);
        String appendLoss = String.format(Locale.ROOT,
                ReadReceiptAndroidDurableStore.APPEND_LOSS_QUERY, epoch, batch);
        String outsideLease = String.format(Locale.ROOT,
                ReadReceiptAndroidDurableStore.BATCH_OUTSIDE_LEASE_REFERENCE_QUERY,
                epoch, batch, epoch, batch);
        String lossBatch = ReadReceiptAndroidDurableStore.lossBatchQuery(EPOCH,
                Collections.singletonList(BATCH));

        String plan = explainAtRepresentativeBacklog(
                appendBatch, appendEvent, appendLoss, outsideLease,
                ReadReceiptAndroidDurableStore.MAINTENANCE_BATCH_QUERY,
                ReadReceiptAndroidDurableStore.LOSS_COMPACTION_QUERY,
                ReadReceiptAndroidDurableStore.BATCH_RANGE_QUERY,
                ReadReceiptAndroidDurableStore.LOSS_COMPACTION_EVENT_COUNT_QUERY,
                lossBatch);

        assertTrue(plan, plan.contains("OUTBOX_COUNT=250001"));
        assertTrue(plan, plan.contains("LOSS_COUNT=250001"));
        assertTrue(plan, plan.contains("sqlite_autoindex_read_receipt_batches_1"));
        assertTrue(plan, plan.contains("idx_read_receipt_outbox_batch"));
        assertTrue(plan, plan.contains("idx_read_receipt_loss_segments_batch"));
        assertTrue(plan, plan.contains("CAPACITY_ROWS=250001"));
        assertTrue(plan, plan.contains("MAINTENANCE_BATCH=" + hex(BATCH)));
        assertTrue(plan, plan.contains("LOSS_BATCH=" + hex(BATCH)));
        assertTrue(plan, plan.contains("RANGE_TAIL=0"));
        assertTrue(plan, plan.contains("sqlite_autoindex_read_receipt_outbox_1"));
        assertFalse(plan, plan.contains("SCAN read_receipt_"));
        assertFalse(plan, plan.contains("USE TEMP B-TREE FOR ORDER BY"));
    }

    @Test
    public void lossCompactionUsesBoundedSelectionAndExactCompositeMutations() {
        FakeDatabase database = new FakeDatabase();
        database.lossCompactionCandidate = true;
        Transaction transaction = store(database).beginImmediate();

        LossSelection selection =
                transaction.selectOldestUnleasedLossRun();
        ReadReceiptProtocolV2.Loss aggregate = new ReadReceiptProtocolV2.Loss(
                uuid(30_000), 1, 1, 1, 1, null, "bounded_prune",
                null, null, null, null, null, 400L);
        int replaced = transaction.replaceLossSelectionWithAggregateExact(selection,
                new LossRecord(EPOCH, aggregate,
                        ReadReceiptProtocolV2.lossDigest(aggregate)));
        transaction.commit();
        transaction.close();

        assertEquals(1, replaced);
        assertEquals(1, selection.losses.size());
        assertEquals(1, selection.batches.size());
        assertEquals(Arrays.asList("0", null, "0", "0"),
                Arrays.asList(database.lastLossCompactionArguments));
        assertEquals(Arrays.asList("1", "1"),
                Arrays.asList(database.lastCompactionEventArguments));
        assertTrue(ReadReceiptAndroidDurableStore.LOSS_COMPACTION_QUERY.contains("LIMIT 257"));
        assertEquals(3, database.updateArguments.size());
        Object[] deleteLoss = database.updateArguments.get(0);
        assertArrayEquals(bytes(EPOCH), (byte[]) deleteLoss[0]);
        assertArrayEquals(bytes(loss().loss.lossReceiptId), (byte[]) deleteLoss[1]);
        assertEquals(1L, deleteLoss[2]);
        assertEquals(1L, deleteLoss[3]);
        assertArrayEquals(loss().contentDigest, (byte[]) deleteLoss[4]);
        Object[] deleteBatch = database.updateArguments.get(1);
        assertArrayEquals(bytes(EPOCH), (byte[]) deleteBatch[0]);
        assertArrayEquals(bytes(BATCH), (byte[]) deleteBatch[1]);
        assertArrayEquals(bytes(EPOCH), (byte[]) deleteBatch[3]);
        assertArrayEquals(bytes(BATCH), (byte[]) deleteBatch[4]);
        Object[] insertAggregate = database.updateArguments.get(2);
        assertArrayEquals(bytes(EPOCH), (byte[]) insertAggregate[0]);
        assertArrayEquals(bytes(aggregate.lossReceiptId), (byte[]) insertAggregate[1]);
        assertNull(insertAggregate[6]);
        assertArrayEquals(ReadReceiptProtocolV2.lossDigest(aggregate),
                (byte[]) insertAggregate[14]);
        assertFalse(String.join("\n", database.trace)
                .contains("UPDATE read_receipt_stream_state SET"));
    }

    @Test
    public void lossCompactionBatchLookupIgnoresUnrelatedRangeMetadata() {
        FakeDatabase database = new FakeDatabase();
        database.lossCompactionGapCandidate = true;
        database.highestIssued = 1000;
        Transaction transaction = store(database).beginImmediate();

        LossSelection selection =
                transaction.selectOldestUnleasedLossRun();
        transaction.commit();
        transaction.close();

        assertEquals(1000, selection.firstSequence);
        assertEquals(1000, selection.lastSequence);
        assertEquals(1, selection.losses.size());
        assertEquals(BATCH, selection.losses.get(0).loss.batchId);
        assertEquals(1, selection.batches.size());
        assertEquals(BATCH, selection.batches.get(0).metadata.batchId);
        String trace = String.join("\n", database.trace);
        assertTrue(trace.contains("AND batch_id IN (X'" + hex(BATCH) + "')"));
    }

    @Test
    public void outsideLeaseQueryBindsBoundsInSubqueryOrder() {
        FakeDatabase database = new FakeDatabase();
        Transaction transaction = store(database).beginImmediate();
        ReadReceiptProtocolV2.Counters counters = new ReadReceiptProtocolV2.Counters(
                0, 0, 0, 0, 0, 0);
        ReadReceiptProtocolV2.Lease protocolLease = new ReadReceiptProtocolV2.Lease(
                INSTALLATION, EPOCH, LEASE, 10, 20, 9, counters,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        LeaseProjection projection =
                new LeaseProjection(protocolLease,
                        Collections.singletonList(batch()), Collections.emptyList(),
                        Collections.emptyList());

        List<BatchRecord> selected =
                transaction.selectDeletableBatches(null, projection);
        transaction.commit();
        transaction.close();

        assertEquals(1, selected.size());
        assertEquals(Arrays.asList("10", "20", "10", "20"),
                Arrays.asList(database.lastOutsideArguments));
        assertTrue(database.lastOutsideSql.contains("stream_epoch = X'" + hex(EPOCH) + "'"));
    }

    @Test
    public void maintenanceSelectsAndReplacesOnlyPartialAckBatchSuffix() {
        FakeDatabase database = new FakeDatabase();
        database.maintenanceCandidate = true;
        database.lastAcked = 256;
        database.highestIssued = 257;
        Transaction transaction = store(database).beginImmediate();

        BatchSelection selection =
                transaction.selectOldestCompleteUnleasedBatch();
        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                uuid(20_000), 257, 257, 1, 1, BATCH, "bounded_prune",
                100L, 200L, SESSION, null, null, 300L);
        int replaced = transaction.replaceBatchSelectionWithLossExact(selection,
                new LossRecord(EPOCH, loss,
                        ReadReceiptProtocolV2.lossDigest(loss)));
        transaction.commit();
        transaction.close();

        assertEquals(257, selection.firstSequence);
        assertEquals(257, selection.lastSequence);
        assertEquals(1, selection.eventCount);
        assertTrue(selection.events.isEmpty());
        assertEquals(1, replaced);
        assertEquals(Arrays.asList("256", null, "0", "0"),
                Arrays.asList(database.lastMaintenanceArguments));
        String trace = String.join("\n", database.trace);
        assertTrue(trace.contains("batch_id = ? AND sequence >= ? AND sequence <= ?"));
        assertFalse(trace.contains("sequence <= through"));
    }

    @Test
    public void maintenanceRejectsRestoredBatchSuffixBeyondMutationBudget() {
        FakeDatabase database = new FakeDatabase();
        database.maintenanceCandidate = true;
        database.highestIssued = 257;
        Transaction transaction = store(database).beginImmediate();

        try {
            transaction.selectOldestCompleteUnleasedBatch();
            fail("expected storage failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        } finally {
            transaction.close();
        }

        assertFalse(String.join("\n", database.trace)
                .contains("SELECT COUNT(*), MIN(sequence), MAX(sequence)"));
        assertTrue(ReadReceiptAndroidDurableStore.MAINTENANCE_EVENT_SHAPE_QUERY
                .endsWith("LIMIT 257)"));
    }

    @Test
    public void exactMutationsUseCompositeIdentityAndNeverBulkThrough() {
        FakeDatabase database = new FakeDatabase();
        ReadReceiptAndroidDurableStore store = store(database);
        Transaction transaction = store.beginImmediate();
        BatchRecord batch = batch();
        EventRecord event = event();
        LeaseRecord lease = lease();

        transaction.insertBatch(batch);
        transaction.insertEvent(event);
        transaction.insertLease(lease);
        assertEquals(1, transaction.deleteEventsExact(Collections.singletonList(event)));
        assertEquals(1, transaction.deleteUnreferencedBatchesExact(
                Collections.singletonList(batch)));
        assertEquals(1, transaction.deleteLeaseExact(lease));
        transaction.commit();
        transaction.close();

        String sql = String.join("\n", database.trace);
        assertTrue(sql.contains("stream_epoch = ? AND sequence = ? AND event_id = ?"));
        assertTrue(sql.contains("stream_epoch = ? AND batch_id = ? AND metadata_digest = ?"));
        assertTrue(sql.contains("stream_epoch = ? AND lease_id = ?"));
        assertFalse(sql.contains("sequence <="));
        assertFalse(sql.contains("through"));
        assertFalse(sql.contains("wal_checkpoint"));
        assertFalse(sql.contains("LENGTH("));
    }

    @Test
    public void affectedRowMismatchFailsAndCloseRollsBack() {
        FakeDatabase database = new FakeDatabase();
        database.nextAffectedRows = 0;
        Transaction transaction = store(database).beginImmediate();

        try {
            transaction.insertEvent(event());
            fail("expected storage failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        } finally {
            transaction.close();
        }

        assertTrue(database.trace.contains("EXEC:ROLLBACK"));
    }

    @Test
    public void commitFaultKeepsRollbackActiveAndCloseFaultIsBounded() {
        FakeDatabase database = new FakeDatabase();
        database.failSql = "COMMIT";
        Transaction transaction = store(database).beginImmediate();
        try {
            transaction.commit();
            fail("expected storage failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        }
        database.failSql = null;
        transaction.close();
        assertTrue(database.trace.contains("EXEC:ROLLBACK"));

        FakeDatabase closeFailure = new FakeDatabase();
        closeFailure.failClose = true;
        Transaction closing = store(closeFailure).beginImmediate();
        try {
            closing.close();
            fail("expected storage failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        }
    }

    private static ReadReceiptAndroidDurableStore store(FakeDatabase database) {
        return new ReadReceiptAndroidDurableStore("db", path -> database, () -> 7_000L);
    }

    private static BatchRecord batch() {
        return batch(1);
    }

    private static BatchRecord batch(int count) {
        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                BATCH, 1, count, count, 100, 200, SESSION, null, null);
        return new BatchRecord(EPOCH, metadata,
                ReadReceiptProtocolV2.metadataDigest(metadata));
    }

    private static BatchRecord tailBatch() {
        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                BATCH, 1000, 1000, 1, 100, 200, SESSION, null, null);
        return new BatchRecord(EPOCH, metadata,
                ReadReceiptProtocolV2.metadataDigest(metadata));
    }

    private static BatchInput input(int count) {
        List<CapturedEvent> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            events.add(new CapturedEvent(uuid(10_000 + index),
                    10, index, 30 + index));
        }
        return new BatchInput(BATCH, 100, 200, SESSION,
                null, null, events);
    }

    private static EventRecord event() {
        return new EventRecord(EPOCH,
                new ReadReceiptProtocolV2.Event(1, EVENT, BATCH, 10, 20, 30));
    }

    private static LeaseRecord lease() {
        ReadReceiptProtocolV2.Counters counters = new ReadReceiptProtocolV2.Counters(
                0, 0, 0, 0, 0, 0);
        return new LeaseRecord(EPOCH, LEASE, 1, 1, 0,
                counters, new byte[32], Status.ACTIVE);
    }

    private static LossRecord loss() {
        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                uuid(25_000), 1, 1, 1, 1, BATCH, "bounded_prune",
                100L, 200L, SESSION, null, null, 300L);
        return new LossRecord(EPOCH, loss,
                ReadReceiptProtocolV2.lossDigest(loss));
    }

    private static LossRecord aggregateLoss() {
        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                uuid(25_001), 1, 1, 1, 1, null, "bounded_prune",
                null, null, null, null, null, 300L);
        return new LossRecord(EPOCH, loss,
                ReadReceiptProtocolV2.lossDigest(loss));
    }

    private static LossRecord tailLoss() {
        ReadReceiptProtocolV2.Loss loss = new ReadReceiptProtocolV2.Loss(
                uuid(25_002), 1000, 1000, 1, 1, BATCH, "bounded_prune",
                100L, 200L, SESSION, null, null, 301L);
        return new LossRecord(EPOCH, loss,
                ReadReceiptProtocolV2.lossDigest(loss));
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    private static String hex(UUID value) {
        StringBuilder result = new StringBuilder(32);
        for (byte item : bytes(value)) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }

    private static String explainAtRepresentativeBacklog(String... queries) throws Exception {
        String sdk = System.getenv("ANDROID_SDK_ROOT");
        if (sdk == null) sdk = System.getenv("ANDROID_HOME");
        if (sdk == null) throw new IOException("Android SDK path is unavailable");
        Path sqlite = Path.of(sdk, "platform-tools", "sqlite3");
        if (!Files.isExecutable(sqlite)) throw new IOException("sqlite3 is unavailable");
        Path database = Files.createTempFile("read-receipt-query-plan", ".db");
        try {
            StringBuilder script = new StringBuilder("PRAGMA page_size=4096;"
                    + "PRAGMA auto_vacuum=INCREMENTAL;");
            for (String statement : ReadReceiptSchemaV2.createStatements()) {
                script.append(statement).append(';').append('\n');
            }
            script.append("INSERT INTO read_receipt_stream_state VALUES (1, X'")
                    .append(hex(INSTALLATION)).append("', X'").append(hex(EPOCH))
                    .append("', 250002, 0, 250001, 0, 0, 0, 0, 0, 0, 'active', NULL, 1, 1);")
                    .append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 ")
                    .append("FROM n WHERE v < 250000) INSERT INTO read_receipt_batches ")
                    .append("SELECT X'").append(hex(EPOCH))
                    .append("', unhex(printf('%032x', v)), v, v, 1, 1, 1, X'")
                    .append(hex(SESSION))
                    .append("', NULL, NULL, zeroblob(32), 1 FROM n;");
            script.append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 ")
                    .append("FROM n WHERE v < 250000) ")
                    .append("INSERT INTO read_receipt_outbox ")
                    .append("SELECT X'").append(hex(EPOCH)).append("', v, ")
                    .append("unhex(printf('%032x', v + 1000000)), ")
                    .append("unhex(printf('%032x', v)), 1, 2, 3 FROM n;")
                    .append("INSERT INTO read_receipt_outbox VALUES (X'")
                    .append(hex(EPOCH)).append("', 250001, X'").append(hex(EVENT))
                    .append("', X'").append(hex(BATCH)).append("', 1, 2, 3);")
                    .append("INSERT INTO read_receipt_batches VALUES (X'")
                    .append(hex(EPOCH)).append("', X'").append(hex(BATCH))
                    .append("', 250001, 250001, 1, 1, 1, X'").append(hex(SESSION))
                    .append("', NULL, NULL, zeroblob(32), 1);")
                    .append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 ")
                    .append("FROM n WHERE v < 250000) ")
                    .append("INSERT INTO read_receipt_loss_segments ")
                    .append("SELECT X'").append(hex(EPOCH)).append("', ")
                    .append("unhex(printf('%032x', v + 2000000)), v, v, 1, 1, ")
                    .append("unhex(printf('%032x', v)), 'bounded_prune', 1, 1, X'")
                    .append(hex(SESSION))
                    .append("', NULL, NULL, 1, zeroblob(32) FROM n;")
                    .append("INSERT INTO read_receipt_loss_segments VALUES (X'")
                    .append(hex(EPOCH)).append("', X'").append(hex(uuid(90)))
                    .append("', 250001, 250001, 1, 1, X'").append(hex(BATCH))
                    .append("', 'bounded_prune', 1, 1, X'").append(hex(SESSION))
                    .append("', NULL, NULL, 1, zeroblob(32));")
                    .append("ANALYZE;")
                    .append("SELECT 'OUTBOX_COUNT=' || COUNT(*) FROM read_receipt_outbox;")
                    .append("SELECT 'LOSS_COUNT=' || COUNT(*) ")
                    .append("FROM read_receipt_loss_segments;")
                    .append("SELECT 'CAPACITY_ROWS=' || (")
                    .append(ReadReceiptAndroidMaintenanceWorker.EVENT_CAPACITY_QUERY)
                    .append(");DELETE FROM read_receipt_outbox WHERE sequence <= 250000;")
                    .append("SELECT 'MAINTENANCE_BATCH=' || lower(hex(batch_id)) FROM (")
                    .append(maintenanceQueryAt(250000)).append(");")
                    .append("SELECT 'LOSS_BATCH=' || lower(hex(batch_id)) FROM (")
                    .append(ReadReceiptAndroidDurableStore.lossBatchQuery(EPOCH,
                            Collections.singletonList(BATCH))).append(");")
                    .append("SELECT 'RANGE_TAIL=' || COUNT(*) FROM (")
                    .append(batchRangeQueryAt(1, 250001)).append(") WHERE batch_id = X'")
                    .append(hex(BATCH)).append("';");
            for (String query : queries) {
                script.append("EXPLAIN QUERY PLAN ").append(query).append(';');
            }
            return runSqlite(sqlite, database, script.toString());
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static String maintenanceQueryAt(long acknowledgedSequence) {
        String query = ReadReceiptAndroidDurableStore.MAINTENANCE_BATCH_QUERY;
        for (String value : Arrays.asList(Long.toString(acknowledgedSequence),
                "NULL", "0", "0")) {
            int placeholder = query.indexOf('?');
            if (placeholder < 0) throw new AssertionError(query);
            query = query.substring(0, placeholder) + value + query.substring(placeholder + 1);
        }
        if (query.indexOf('?') >= 0) throw new AssertionError(query);
        return query;
    }

    private static String batchRangeQueryAt(long firstSequence, long lastSequence) {
        String query = ReadReceiptAndroidDurableStore.BATCH_RANGE_QUERY;
        for (String value : Arrays.asList(Long.toString(firstSequence),
                Long.toString(lastSequence))) {
            int placeholder = query.indexOf('?');
            if (placeholder < 0) throw new AssertionError(query);
            query = query.substring(0, placeholder) + value + query.substring(placeholder + 1);
        }
        if (query.indexOf('?') >= 0) throw new AssertionError(query);
        return query;
    }

    private static String runSqlite(Path sqlite, Path database, String script) throws Exception {
        Process process = new ProcessBuilder(sqlite.toString(), database.toString())
                .redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream()) {
            input.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(output, 0, exitCode);
        return output;
    }

    private static final class FakeDatabase implements ReadReceiptAndroidStartupStore.Database {
        final List<String> trace = new ArrayList<>();
        boolean withContent;
        boolean withLease;
        int appendEventCount;
        long highestIssued = 1;
        long lastAcked;
        boolean maintenanceCandidate;
        boolean lossCompactionCandidate;
        boolean lossCompactionGapCandidate;
        int nextAffectedRows = 1;
        String failSql;
        boolean failClose;
        String lastOutsideSql;
        String[] lastOutsideArguments;
        String[] lastLossCompactionArguments;
        String[] lastCompactionEventArguments;
        String[] lastMaintenanceArguments;
        final List<Object[]> updateArguments = new ArrayList<>();

        @Override
        public ReadReceiptAndroidStartupStore.Rows query(String sql, String[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("QUERY:" + sql);
            if ("PRAGMA foreign_keys".equals(sql)) return rows(row(1L));
            if ("PRAGMA wal_autocheckpoint=0".equals(sql)) return rows(row(0L));
            if ("PRAGMA page_size".equals(sql)) {
                return rows(row(ReadReceiptSchemaV2.PAGE_SIZE_BYTES));
            }
            if ("PRAGMA auto_vacuum".equals(sql)) {
                return rows(row(ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL));
            }
            if (ReadReceiptAndroidDurableStore.STREAM_QUERY.equals(sql)) {
                return rows(row(1L, bytes(INSTALLATION), bytes(EPOCH), highestIssued + 1,
                        lastAcked, highestIssued,
                        0L, 0L, 0L, 0L, 0L, 0L, "active", null));
            }
            if (ReadReceiptAndroidDurableStore.LEASE_QUERY.equals(sql)) {
                if (!withLease) return rows();
                return rows(row(1L, bytes(EPOCH), bytes(LEASE), 1L, 1L, 0L,
                        new byte[32], "active", null, 0L, 0L, 0L, 0L, 0L, 0L));
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.BATCH_HEAD_QUERY)
                    || sql.equals(ReadReceiptAndroidDurableStore.BATCH_TAIL_QUERY)) {
                if (!withContent) return rows();
                ReadReceiptProtocolV2.Metadata metadata = batch().metadata;
                return rows(row(bytes(EPOCH), bytes(BATCH), 1L, 1L, 1L, 100L, 200L,
                        bytes(SESSION), null, null,
                        ReadReceiptProtocolV2.metadataDigest(metadata)));
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.EVENT_HEAD_QUERY)
                    || sql.equals(ReadReceiptAndroidDurableStore.EVENT_TAIL_QUERY)) {
                return withContent
                        ? rows(row(bytes(EPOCH), 1L, bytes(EVENT), bytes(BATCH), 10L, 20L, 30L))
                        : rows();
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.LOSS_HEAD_QUERY)
                    || sql.equals(ReadReceiptAndroidDurableStore.LOSS_TAIL_QUERY)) return rows();
            if (sql.equals(ReadReceiptAndroidDurableStore.BATCH_RANGE_QUERY)) {
                if (lossCompactionGapCandidate) {
                    List<Object[]> values = new ArrayList<>();
                    for (int index = 0; index < 257; index++) {
                        UUID id = uuid(40_000 + index);
                        long sequence = index + 2L;
                        ReadReceiptProtocolV2.Metadata metadata =
                                new ReadReceiptProtocolV2.Metadata(id, sequence, sequence,
                                        1, 100, 200, SESSION, null, null);
                        values.add(row(bytes(EPOCH), bytes(id), sequence, sequence, 1L,
                                100L, 200L, bytes(SESSION), null, null,
                                ReadReceiptProtocolV2.metadataDigest(metadata)));
                    }
                    return rows(values.toArray(new Object[0][]));
                }
                if (!withContent && !lossCompactionCandidate) return rows();
                ReadReceiptProtocolV2.Metadata metadata = batch().metadata;
                return rows(row(bytes(EPOCH), bytes(BATCH), 1L, 1L, 1L, 100L, 200L,
                        bytes(SESSION), null, null,
                        ReadReceiptProtocolV2.metadataDigest(metadata)));
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.EVENT_RANGE_QUERY)) {
                return withContent
                        ? rows(row(bytes(EPOCH), 1L, bytes(EVENT), bytes(BATCH), 10L, 20L, 30L))
                        : rows();
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.LOSS_RANGE_QUERY)) return rows();
            if (sql.equals(ReadReceiptAndroidDurableStore.LOSS_COMPACTION_QUERY)) {
                lastLossCompactionArguments = arguments.clone();
                if (lossCompactionGapCandidate) {
                    return rows(lossRow(aggregateLoss()), lossRow(tailLoss()));
                }
                if (!lossCompactionCandidate) return rows();
                LossRecord record = loss();
                ReadReceiptProtocolV2.Loss value = record.loss;
                return rows(row(bytes(EPOCH), bytes(value.lossReceiptId),
                        value.firstSequence, value.lastSequence, value.droppedEventCount,
                        value.droppedBatchCount, bytes(value.batchId), value.reason,
                        value.persistedAtMs, value.persistedElapsedMs,
                        bytes(value.captureSessionId), null, null, value.createdAtMs,
                        record.contentDigest));
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.LOSS_COMPACTION_EVENT_COUNT_QUERY)) {
                lastCompactionEventArguments = arguments.clone();
                return rows(row(0L));
            }
            if (sql.contains("FROM read_receipt_batches WHERE stream_epoch = X'")
                    && sql.contains("AND batch_id IN (")) {
                BatchRecord record = lossCompactionGapCandidate
                        ? tailBatch() : batch();
                ReadReceiptProtocolV2.Metadata metadata = record.metadata;
                return rows(row(bytes(EPOCH), bytes(BATCH), metadata.firstSequence,
                        metadata.lastSequence, metadata.eventCount, metadata.persistedAtMs,
                        metadata.persistedElapsedMs, bytes(metadata.captureSessionId), null, null,
                        record.metadataDigest));
            }
            if (sql.startsWith("SELECT CASE WHEN EXISTS")) {
                lastOutsideSql = sql;
                lastOutsideArguments = arguments.clone();
                return rows(row(0L));
            }
            if (sql.contains("FROM read_receipt_batches WHERE stream_epoch = X'")) {
                ReadReceiptProtocolV2.Metadata metadata = batch(appendEventCount).metadata;
                return rows(row(bytes(EPOCH), bytes(BATCH), 1L, (long) appendEventCount,
                        (long) appendEventCount, 100L, 200L, bytes(SESSION), null, null,
                        ReadReceiptProtocolV2.metadataDigest(metadata)));
            }
            if (sql.contains("FROM read_receipt_outbox WHERE stream_epoch = X'")) {
                List<Object[]> values = new ArrayList<>();
                for (int index = 0; index < appendEventCount; index++) {
                    values.add(row(bytes(EPOCH), (long) index + 1, bytes(uuid(10_000 + index)),
                            bytes(BATCH), 10L, (long) index, 30L + index));
                }
                return rows(values.toArray(new Object[0][]));
            }
            if (sql.contains("FROM read_receipt_loss_segments WHERE stream_epoch = X'")) {
                return rows();
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.MAINTENANCE_BATCH_QUERY)) {
                lastMaintenanceArguments = arguments.clone();
                if (!maintenanceCandidate) return rows();
                ReadReceiptProtocolV2.Metadata metadata = batch(257).metadata;
                return rows(row(bytes(EPOCH), bytes(BATCH), 1L, 257L, 257L,
                        100L, 200L, bytes(SESSION), null, null,
                        ReadReceiptProtocolV2.metadataDigest(metadata)));
            }
            if (sql.equals(ReadReceiptAndroidDurableStore.MAINTENANCE_LOSS_COUNT_QUERY)) {
                return rows(row(0L));
            }
            if (sql.startsWith("SELECT COUNT(*), MIN(sequence), MAX(sequence)")) {
                return rows(row(1L, 257L, 257L));
            }
            throw new ReadReceiptAndroidStartupStore.Failure();
        }

        @Override
        public void execute(String sql, Object[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("EXEC:" + sql);
            if (sql.equals(failSql)) throw new ReadReceiptAndroidStartupStore.Failure();
        }

        @Override
        public int update(String sql, Object[] arguments)
                throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("UPDATE:" + sql);
            updateArguments.add(arguments.clone());
            int result = nextAffectedRows;
            nextAffectedRows = 1;
            return result;
        }

        @Override
        public void close() throws ReadReceiptAndroidStartupStore.Failure {
            trace.add("CLOSE");
            if (failClose) throw new ReadReceiptAndroidStartupStore.Failure();
        }
    }

    private static Object[] lossRow(LossRecord record) {
        ReadReceiptProtocolV2.Loss value = record.loss;
        return row(bytes(EPOCH), bytes(value.lossReceiptId), value.firstSequence,
                value.lastSequence, value.droppedEventCount, value.droppedBatchCount,
                value.batchId == null ? null : bytes(value.batchId), value.reason,
                value.persistedAtMs, value.persistedElapsedMs,
                value.captureSessionId == null ? null : bytes(value.captureSessionId),
                value.captureBootId == null ? null : bytes(value.captureBootId),
                value.sourceEpochToken == null ? null : bytes(value.sourceEpochToken),
                value.createdAtMs, record.contentDigest);
    }

    private static ReadReceiptAndroidStartupStore.Rows rows(Object[]... values) {
        return new FakeRows(Arrays.asList(values));
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static byte[] bytes(UUID value) {
        return ReadReceiptAndroidSqlite.uuidBytes(value);
    }

    private static final class FakeRows implements ReadReceiptAndroidStartupStore.Rows {
        private final List<Object[]> rows;
        private int index = -1;

        FakeRows(List<Object[]> rows) {
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
