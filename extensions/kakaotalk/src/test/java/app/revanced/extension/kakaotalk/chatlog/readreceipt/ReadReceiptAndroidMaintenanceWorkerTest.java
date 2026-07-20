package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReadReceiptAndroidMaintenanceWorkerTest {
    private static final long SQLITE_PROCESS_TIMEOUT_SECONDS = 120;
    private static final int OVERSIZED_WAL_LOSS_ROWS = 280_000;

    @Test
    public void checkpointWorkHasExplicitWalByteBudget() {
        assertTrue(ReadReceiptAndroidMaintenanceWorker.checkpointFitsRunBudget(
                ReadReceiptDurableStream.MAX_TOTAL_BYTES));
        assertFalse(ReadReceiptAndroidMaintenanceWorker.checkpointFitsRunBudget(
                ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1));
        assertFalse(ReadReceiptAndroidMaintenanceWorker.checkpointFitsRunBudget(-1));
        assertTrue(ReadReceiptAndroidMaintenanceWorker.EVENT_CAPACITY_QUERY
                .endsWith("LIMIT 250001)"));
        assertEquals("PRAGMA wal_checkpoint(PASSIVE)",
                ReadReceiptAndroidMaintenanceWorker.WAL_CHECKPOINT_PASSIVE_QUERY);
        assertEquals("PRAGMA wal_checkpoint(TRUNCATE)",
                ReadReceiptAndroidMaintenanceWorker.WAL_CHECKPOINT_TRUNCATE_QUERY);
        assertTrue(ReadReceiptAndroidMaintenanceWorker.startupRecoveryFitsHardLimit(
                ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1));
        assertTrue(ReadReceiptAndroidMaintenanceWorker.startupRecoveryFitsHardLimit(
                ReadReceiptAndroidMaintenanceWorker.MAX_STARTUP_RECOVERY_WAL_BYTES));
        assertFalse(ReadReceiptAndroidMaintenanceWorker.startupRecoveryFitsHardLimit(
                ReadReceiptAndroidMaintenanceWorker.MAX_STARTUP_RECOVERY_WAL_BYTES + 1));
    }

    @Test(expected = DurabilityContractException.class)
    public void walBeyondCheckpointBudgetSignalsFailClosed() {
        ReadReceiptAndroidMaintenanceWorker.requireCheckpointBudget(
                ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1);
    }

    @Test
    public void startupOversizedWalRecoveryPrecedesLogicalMaintenanceAndPreservesRows() {
        FakeAccess access = new FakeAccess();
        access.startupWalBytes = ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1;
        access.recoveredWalBytes = 0;
        List<String> protectedRows = new ArrayList<>(Arrays.asList(
                "active-lease", "partial-batch", "logical-row"));
        int[] maintainerCalls = new int[1];
        ReadReceiptAndroidMaintenanceWorker worker = new ReadReceiptAndroidMaintenanceWorker(
                () -> access,
                capacity -> {
                    maintainerCalls[0]++;
                    protectedRows.clear();
                    return MaintenanceResult.of(
                            MaintenanceOutcome.CONVERTED);
                });

        assertTrue(worker.recoverPhysicalWalOnStart());

        assertEquals(Arrays.asList("OPEN", "WAL", "RECOVER_WAL", "WAL", "CLOSE"),
                access.trace);
        assertEquals(0, maintainerCalls[0]);
        assertEquals(Arrays.asList("active-lease", "partial-batch", "logical-row"),
                protectedRows);
    }

    @Test
    public void startupRecoveryBusyOrErrorIsRetryableAndNeverRunsLogicalMaintenance() {
        for (String failure : Arrays.asList("RECOVER_WAL", "WAL_AFTER_RECOVERY")) {
            FakeAccess access = new FakeAccess();
            access.startupWalBytes = ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1;
            access.recoveredWalBytes = failure.equals("WAL_AFTER_RECOVERY")
                    ? ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1 : 0;
            access.fail = failure.equals("RECOVER_WAL") ? failure : null;
            FakeMaintainer maintainer = new FakeMaintainer();

            assertFalse(failure,
                    worker(access, maintainer).recoverPhysicalWalOnStart());

            assertEquals(failure, 0, maintainer.calls);
            assertEquals(failure, "CLOSE", access.trace.get(access.trace.size() - 1));
        }
    }

    @Test
    public void startupRecoveryRejectsWalBeyondExplicitHardLimitWithoutCheckpointing() {
        FakeAccess access = new FakeAccess();
        access.startupWalBytes =
                ReadReceiptAndroidMaintenanceWorker.MAX_STARTUP_RECOVERY_WAL_BYTES + 1;
        FakeMaintainer maintainer = new FakeMaintainer();

        assertFalse(worker(access, maintainer).recoverPhysicalWalOnStart());

        assertEquals(Arrays.asList("OPEN", "WAL", "CLOSE"), access.trace);
        assertEquals(0, maintainer.calls);
    }

    @Test
    public void withinCapsCheckpointsMeasuresAndClosesWithoutPrune() {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(250_000,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 0));
        FakeMaintainer maintainer = new FakeMaintainer();
        ReadReceiptAndroidMaintenanceWorker worker = worker(access, maintainer);

        assertTrue(worker.runOnce());

        assertEquals(Arrays.asList("OPEN", "CHECKPOINT", "ROWS", "MAIN", "WAL", "CLOSE"),
                access.trace);
        assertEquals(0, maintainer.calls);
        assertTrue(worker.recoveryRequiredOnStart());
    }

    @Test
    public void rowAndCombinedByteOverflowConvertThenRemeasureUntilBounded() {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(250_001, 1, 1));
        access.capacities.add(new Capacity(250_000,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));
        access.capacities.add(new Capacity(250_000,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 0));
        FakeMaintainer maintainer = new FakeMaintainer();
        maintainer.outcomes.add(MaintenanceOutcome.CONVERTED);
        maintainer.outcomes.add(MaintenanceOutcome.CONVERTED);

        assertTrue(worker(access, maintainer).runOnce());

        assertEquals(2, maintainer.calls);
        assertEquals(3, access.checkpoints);
        assertEquals(ReadReceiptDurableStream.MAX_TOTAL_BYTES,
                maintainer.capacities.get(1).mainBytes);
        assertEquals(1, maintainer.capacities.get(1).walBytes);
    }

    @Test
    public void walOnlyOverflowTruncatesAndRemeasuresBeforeLogicalCompaction() {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(0,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));
        FakeMaintainer maintainer = new FakeMaintainer();

        assertTrue(worker(access, maintainer).runOnce());

        assertEquals(0, maintainer.calls);
        assertEquals(Arrays.asList("OPEN", "CHECKPOINT", "ROWS", "MAIN", "WAL",
                "TRUNCATE_WAL", "ROWS", "MAIN", "WAL", "CLOSE"), access.trace);
    }

    @Test
    public void logicalCompactionReclaimsFreelistAndRemeasuresPhysicalBytes() {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(0,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1, 0));
        access.capacities.add(new Capacity(0,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES + 1, 0));
        access.reclaimableBytes = 4096;
        access.reclaimedCapacity = new Capacity(0,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 0);
        FakeMaintainer maintainer = new FakeMaintainer();
        maintainer.outcomes.add(MaintenanceOutcome.COMPACTED);

        assertTrue(worker(access, maintainer).runOnce());

        assertEquals(2, maintainer.calls);
        assertTrue(access.trace.contains("RECLAIMABLE"));
        assertTrue(access.trace.contains("RECLAIM_PAGES:256"));
        assertTrue(access.trace.contains("TRUNCATE_WAL"));
    }

    @Test
    public void busyWalTruncateFailsOnceWithoutLogicalMutationOrLoop() {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(0,
                ReadReceiptDurableStream.MAX_TOTAL_BYTES, 1));
        access.fail = "TRUNCATE_WAL";
        FakeMaintainer maintainer = new FakeMaintainer();

        assertFalse(worker(access, maintainer).runOnce());

        assertEquals(0, maintainer.calls);
        assertEquals(1, Collections.frequency(access.trace, "TRUNCATE_WAL"));
        assertEquals("CLOSE", access.trace.get(access.trace.size() - 1));
    }

    @Test
    public void leasedOrUnavailableConversionReturnsRetryableFalseWithoutBusyLoop() {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(250_001, 0, 0));
        FakeMaintainer maintainer = new FakeMaintainer();
        maintainer.outcomes.add(MaintenanceOutcome.BLOCKED_BY_LEASE);

        assertFalse(worker(access, maintainer).runOnce());
        assertEquals(1, maintainer.calls);
        assertEquals(1, access.checkpoints);
    }

    @Test
    public void measurementAndCheckpointFailureCloseAndRequestRetry() {
        for (String fault : Arrays.asList("CHECKPOINT", "ROWS", "MAIN", "WAL")) {
            FakeAccess access = new FakeAccess();
            access.fail = fault;
            access.capacities.add(new Capacity(0, 0, 0));

            assertFalse(fault, worker(access, new FakeMaintainer()).runOnce());
            assertTrue(fault, access.trace.contains("CLOSE"));
        }
    }

    @Test(expected = DurabilityContractException.class)
    public void durabilityFailureEscapesRetryableMaintenanceResult() {
        FakeAccess access = new FakeAccess();
        access.failClosedOnOpen = true;

        worker(access, new FakeMaintainer()).runOnce();
    }

    @Test
    public void processDomainSerializesConcurrentMaintenanceRuns() throws Exception {
        FakeAccess access = new FakeAccess();
        access.capacities.add(new Capacity(0, 0, 0));
        access.capacities.add(new Capacity(0, 0, 0));
        access.blockFirst = true;
        ReadReceiptAndroidMaintenanceWorker worker = worker(access, new FakeMaintainer());
        CountDownLatch started = new CountDownLatch(2);
        List<Boolean> results = new ArrayList<>();
        Runnable run = () -> {
            started.countDown();
            boolean result = worker.runOnce();
            synchronized (results) {
                results.add(result);
            }
        };
        Thread first = new Thread(run);
        Thread second = new Thread(run);
        first.start();
        assertTrue(access.entered.await(2, TimeUnit.SECONDS));
        second.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        access.release.countDown();
        first.join(2_000);
        second.join(2_000);

        assertEquals(1, access.maxActive);
        assertEquals(Arrays.asList(true, true), results);
    }

    @Test
    public void actualOversizedWalRecoveryPreservesLeasePartialBatchAndLogicalRows()
            throws Exception {
        Path database = Files.createTempFile("read-receipt-oversized-wal", ".db");
        Files.delete(database);
        Path output = Files.createTempFile("read-receipt-oversized-wal", ".log");
        WalFixture fixture = null;
        try {
            fixture = startOversizedWalFixture(database, output);
            long before = optionalSize(Path.of(database + "-wal"));
            assertTrue("fixture WAL must exceed normal cap: " + before,
                    before > ReadReceiptDurableStream.MAX_TOTAL_BYTES);
            assertTrue("fixture WAL must stay within hard recovery cap: " + before,
                    before <= ReadReceiptAndroidMaintenanceWorker.MAX_STARTUP_RECOVERY_WAL_BYTES);
            SqliteAccess access = new SqliteAccess(database);
            FakeMaintainer maintainer = new FakeMaintainer();

            assertTrue(new ReadReceiptAndroidMaintenanceWorker(
                    () -> access, maintainer).recoverPhysicalWalOnStart());

            assertEquals("0|0|0", access.recoveryResult);
            assertTrue(optionalSize(Path.of(database + "-wal"))
                    <= ReadReceiptDurableStream.MAX_TOTAL_BYTES);
            assertEquals(0, maintainer.calls);
            List<String> rows = nonEmptyLines(runSqlite(database,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check;"
                            + "SELECT COUNT(*), first_sequence, last_sequence, "
                            + "previous_sequence FROM read_receipt_transfer_lease;"
                            + "SELECT COUNT(*), MIN(first_sequence), MAX(last_sequence), "
                            + "MIN(event_count) FROM read_receipt_batches WHERE batch_id = X'"
                            + hex(10) + "';"
                            + "SELECT COUNT(*) FROM read_receipt_outbox WHERE batch_id = X'"
                            + hex(10) + "';"
                            + "SELECT occurrence_count, event_count, batch_count, "
                            + "last_observed_at_ms FROM read_receipt_diagnostic_counters "
                            + "WHERE category = 'capacity_eviction';"
                            + "SELECT COUNT(*) FROM read_receipt_loss_segments;"
                            + "SELECT COUNT(*) FROM read_receipt_batches;"));
            assertEquals(Arrays.asList(
                    "0", "1|1|1|0", "1|1|2|2", "1", "1|0|0|1",
                    Integer.toString(OVERSIZED_WAL_LOSS_ROWS),
                    Integer.toString(OVERSIZED_WAL_LOSS_ROWS + 1)), rows);
        } finally {
            if (fixture != null) fixture.close();
            Files.deleteIfExists(Path.of(database + "-shm"));
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(database);
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void allLossPhysicalCapConvergesThroughProductionMaintenanceLoop()
            throws Exception {
        Path database = Files.createTempFile("read-receipt-all-loss", ".db");
        try {
            createFixture(database, 0, 260_000);
            runSqlite(database, "PRAGMA wal_checkpoint(TRUNCATE);");
            long before = physicalBytes(database);
            assertTrue("fixture must reproduce cap overflow",
                    before > ReadReceiptDurableStream.MAX_TOTAL_BYTES);

            int runs = converge(database, 1, 260_000);

            long after = physicalBytes(database);
            assertTrue("bounded reclaim must converge over repeated runs", runs > 1);
            assertTrue("all-loss physical bytes=" + after,
                    after <= ReadReceiptDurableStream.MAX_TOTAL_BYTES);
            assertFixtureInvariants(database, 0, 260_000);
        } finally {
            Files.deleteIfExists(Path.of(database + "-shm"));
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(database);
        }
    }

    @Test
    public void mixedEventAndLossBacklogMakesMonotonicPhysicalProgress() throws Exception {
        Path database = Files.createTempFile("read-receipt-mixed", ".db");
        try {
            createFixture(database, 250_000, 80_000);
            runSqlite(database, "PRAGMA wal_checkpoint(TRUNCATE);");
            long before = physicalBytes(database);
            assertTrue("mixed fixture must reproduce cap overflow",
                    before > ReadReceiptDurableStream.MAX_TOTAL_BYTES);

            int runs = converge(database, 250_001, 80_000);

            long after = physicalBytes(database);
            assertTrue("bounded reclaim must converge over repeated runs", runs > 1);
            assertTrue("mixed physical bytes=" + after,
                    after <= ReadReceiptDurableStream.MAX_TOTAL_BYTES);
            assertFixtureInvariants(database, 250_000, 80_000);
        } finally {
            Files.deleteIfExists(Path.of(database + "-shm"));
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(database);
        }
    }

    private static int converge(Path database, long lossFirst, long lossCount)
            throws Exception {
        SqliteAccess access = new SqliteAccess(database);
        SqliteMaintainer maintainer = new SqliteMaintainer(database, lossFirst, lossCount);
        ReadReceiptAndroidMaintenanceWorker worker = new ReadReceiptAndroidMaintenanceWorker(
                () -> access, maintainer);
        long previous = physicalBytes(database);
        for (int run = 0; run < 16; run++) {
            boolean complete = worker.runOnce();
            long current = physicalBytes(database);
            if (complete) return run + 1;
            assertTrue("physical size did not decrease: " + previous + " -> " + current
                            + ", calls=" + maintainer.calls
                            + ", aggregateLast=" + maintainer.aggregateLast
                            + ", diagnostic=" + maintainer.diagnostic,
                    current < previous);
            previous = current;
        }
        throw new AssertionError("physical cap did not converge");
    }

    private static void createFixture(Path database, int eventCount, int lossCount)
            throws Exception {
        long total = (long) eventCount + lossCount;
        StringBuilder script = new StringBuilder("PRAGMA page_size=4096;"
                + "PRAGMA auto_vacuum=INCREMENTAL;PRAGMA journal_mode=WAL;BEGIN;");
        for (String statement : ReadReceiptSchemaV2.createStatements()) {
            script.append(statement).append(';');
        }
        script.append("INSERT INTO read_receipt_stream_state VALUES (1, X'")
                .append(hex(1)).append("', X'").append(hex(2)).append("', ")
                .append(total + 1L).append(", 0, ").append(total).append(", ")
                .append(lossCount).append(", ").append(lossCount)
                .append(", 0, 0, 0, 0, 'active', NULL, 1, 1);")
                .append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 ")
                .append("FROM n WHERE v < ").append(total).append(") ")
                .append("INSERT INTO read_receipt_batches SELECT X'").append(hex(2))
                .append("', unhex(printf('%032x', v)), v, v, 1, 1, 1, X'")
                .append(hex(3)).append("', X'").append(hex(4)).append("', X'")
                .append(hex(5)).append("', zeroblob(32), 1 FROM n;")
                .append(eventCount == 0 ? "" : "WITH RECURSIVE n(v) AS (VALUES(1) "
                        + "UNION ALL SELECT v + 1 FROM n WHERE v < " + eventCount + ") "
                        + "INSERT INTO read_receipt_outbox SELECT X'" + hex(2)
                        + "', v, unhex(printf('%032x', v + 2000000)), "
                        + "unhex(printf('%032x', v)), 1, 2, 3 FROM n;")
                .append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 ")
                .append("FROM n WHERE v < ").append(lossCount).append(") ")
                .append("INSERT INTO read_receipt_loss_segments SELECT X'").append(hex(2))
                .append("', unhex(printf('%032x', v + 3000000)), v + ")
                .append(eventCount).append(", v + ").append(eventCount)
                .append(", 1, 1, unhex(printf('%032x', v + ").append(eventCount)
                .append(")), 'bounded_prune', 1, 1, X'")
                .append(hex(3)).append("', X'").append(hex(4)).append("', X'")
                .append(hex(5)).append("', 1, zeroblob(32) FROM n;COMMIT;");
        runSqlite(database, script.toString());
    }

    private static String runSqlite(Path database, String script) throws Exception {
        String sdk = System.getenv("ANDROID_SDK_ROOT");
        if (sdk == null) sdk = System.getenv("ANDROID_HOME");
        if (sdk == null) throw new IOException("Android SDK path is unavailable");
        Path sqlite = Path.of(sdk, "platform-tools", "sqlite3");
        Process process = new ProcessBuilder(sqlite.toString(), database.toString())
                .redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream()) {
            input.write(script.getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(SQLITE_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new IOException("sqlite3 timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exit = process.exitValue();
        assertEquals(output, 0, exit);
        return output;
    }

    private static WalFixture startOversizedWalFixture(Path database, Path output)
            throws Exception {
        String sdk = System.getenv("ANDROID_SDK_ROOT");
        if (sdk == null) sdk = System.getenv("ANDROID_HOME");
        if (sdk == null) throw new IOException("Android SDK path is unavailable");
        Path sqlite = Path.of(sdk, "platform-tools", "sqlite3");
        Process process = new ProcessBuilder(sqlite.toString(), database.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        OutputStream input = process.getOutputStream();
        StringBuilder script = new StringBuilder(".bail on\nPRAGMA page_size=4096;"
                + "PRAGMA auto_vacuum=INCREMENTAL;PRAGMA journal_mode=WAL;"
                + "PRAGMA wal_autocheckpoint=0;BEGIN;");
        for (String statement : ReadReceiptSchemaV2.createStatements()) {
            script.append(statement).append(';');
        }
        long highest = OVERSIZED_WAL_LOSS_ROWS + 2L;
        script.append("INSERT INTO read_receipt_stream_state VALUES (1, X'")
                .append(hex(1)).append("', X'").append(hex(2)).append("', ")
                .append(highest + 1).append(", 0, ").append(highest)
                .append(", 0, 0, 0, 0, 0, 0, 'active', NULL, 1, 1);")
                .append("INSERT INTO read_receipt_batches VALUES (X'").append(hex(2))
                .append("', X'").append(hex(10))
                .append("', 1, 2, 2, 1, 1, X'").append(hex(3))
                .append("', NULL, NULL, zeroblob(32), 1);")
                .append("INSERT INTO read_receipt_outbox VALUES (X'").append(hex(2))
                .append("', 1, X'").append(hex(11)).append("', X'").append(hex(10))
                .append("', 1, 2, 3);")
                .append("INSERT INTO read_receipt_transfer_lease VALUES (1, X'")
                .append(hex(2)).append("', X'").append(hex(12))
                .append("', 1, 1, 0, zeroblob(32), 'active', NULL, NULL, "
                        + "0, 0, 0, 0, 0, 0, 0, 1, 1);")
                .append("INSERT INTO read_receipt_diagnostic_counters VALUES "
                        + "('capacity_eviction', 1, 0, 0, 1);")
                .append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 "
                        + "FROM n WHERE v < ").append(OVERSIZED_WAL_LOSS_ROWS).append(") ")
                .append("INSERT INTO read_receipt_batches SELECT X'").append(hex(2))
                .append("', unhex(printf('%032x', v + 1000)), v + 2, v + 2, 1, 1, 1, X'")
                .append(hex(3)).append("', NULL, NULL, zeroblob(32), 1 FROM n;")
                .append("WITH RECURSIVE n(v) AS (VALUES(1) UNION ALL SELECT v + 1 "
                        + "FROM n WHERE v < ").append(OVERSIZED_WAL_LOSS_ROWS).append(") ")
                .append("INSERT INTO read_receipt_loss_segments SELECT X'").append(hex(2))
                .append("', unhex(printf('%032x', v + 3000000)), v + 2, v + 2, 1, 1, "
                        + "unhex(printf('%032x', v + 1000)), 'bounded_prune', 1, 1, X'")
                .append(hex(3)).append("', NULL, NULL, 1, zeroblob(32) FROM n;COMMIT;"
                        + "PRAGMA wal_checkpoint(NOOP);\n.print READY\n");
        input.write(script.toString().getBytes(StandardCharsets.UTF_8));
        input.flush();
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(SQLITE_PROCESS_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String observed = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            if (observed.contains("READY")) return new WalFixture(process, input);
            if (!process.isAlive()) throw new IOException("sqlite3 fixture failed: " + observed);
            Thread.sleep(25);
        }
        process.destroyForcibly();
        process.waitFor();
        input.close();
        throw new IOException("sqlite3 fixture timed out");
    }

    private static String hex(long value) {
        return String.format("%032x", value);
    }

    private static String hex(UUID value) {
        return String.format(Locale.ROOT, "%016x%016x",
                value.getMostSignificantBits(), value.getLeastSignificantBits());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static long physicalBytes(Path database) throws IOException {
        return Files.size(database) + optionalSize(Path.of(database + "-wal"));
    }

    private static long optionalSize(Path path) throws IOException {
        return Files.exists(path) ? Files.size(path) : 0;
    }

    private static void assertFixtureInvariants(Path database, int eventCount, int lossCount)
            throws Exception {
        long lossFirst = eventCount + 1L;
        long lossLast = eventCount + (long) lossCount;
        String output = runSqlite(database,
                "SELECT COUNT(*) FROM pragma_foreign_key_check;"
                        + "SELECT COUNT(*) FROM read_receipt_outbox;"
                        + "SELECT COALESCE(SUM(dropped_event_count), 0), "
                        + "COALESCE(SUM(dropped_batch_count), 0), MIN(first_sequence), "
                        + "MAX(last_sequence) FROM read_receipt_loss_segments;"
                        + "SELECT COUNT(*) FROM (SELECT first_sequence, "
                        + "LAG(last_sequence) OVER (ORDER BY first_sequence) AS prior "
                        + "FROM read_receipt_loss_segments) WHERE prior IS NOT NULL "
                        + "AND first_sequence <> prior + 1;"
                        + "SELECT confirmed_dropped_event_count, "
                        + "confirmed_dropped_batch_count FROM read_receipt_stream_state "
                        + "WHERE singleton = 1;"
                        + "SELECT COUNT(*) FROM read_receipt_transfer_lease;"
                        + "SELECT COUNT(*) FROM read_receipt_batches b WHERE NOT EXISTS "
                        + "(SELECT 1 FROM read_receipt_outbox o WHERE o.stream_epoch = "
                        + "b.stream_epoch AND o.batch_id = b.batch_id) AND NOT EXISTS "
                        + "(SELECT 1 FROM read_receipt_loss_segments l WHERE l.stream_epoch = "
                        + "b.stream_epoch AND l.batch_id = b.batch_id);"
                        + "PRAGMA page_size;PRAGMA auto_vacuum;");
        List<String> rows = nonEmptyLines(output);
        assertEquals(output, 9, rows.size());
        assertEquals("0", rows.get(0));
        assertEquals(Integer.toString(eventCount), rows.get(1));
        assertEquals(lossCount + "|" + lossCount + "|" + lossFirst + "|" + lossLast,
                rows.get(2));
        assertEquals("0", rows.get(3));
        assertEquals(lossCount + "|" + lossCount, rows.get(4));
        assertEquals("0", rows.get(5));
        assertEquals("0", rows.get(6));
        assertEquals(Long.toString(ReadReceiptSchemaV2.PAGE_SIZE_BYTES), rows.get(7));
        assertEquals(Long.toString(ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL), rows.get(8));
    }

    private static List<String> nonEmptyLines(String output) {
        List<String> result = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String value = line.trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static String sqliteUnchecked(Path database, String script) {
        try {
            return runSqlite(database, script);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class SqliteAccess implements ReadReceiptAndroidMaintenanceWorker.Access {
        private final Path database;
        private boolean open;
        private String recoveryResult;

        SqliteAccess(Path database) {
            this.database = database;
        }

        @Override
        public void open() {
            if (open) throw new IllegalStateException();
            open = true;
            sqliteUnchecked(database, "PRAGMA busy_timeout=0; PRAGMA wal_autocheckpoint=0;");
            if (scalar("PRAGMA page_size;") != ReadReceiptSchemaV2.PAGE_SIZE_BYTES
                    || scalar("PRAGMA auto_vacuum;")
                    != ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) {
                throw new IllegalStateException();
            }
        }

        @Override
        public void checkpoint() {
            requireOpen();
            List<String> rows = nonEmptyLines(sqliteUnchecked(database,
                    ReadReceiptAndroidMaintenanceWorker.WAL_CHECKPOINT_PASSIVE_QUERY));
            if (rows.size() != 1 || !rows.get(0).startsWith("0|")) {
                throw new IllegalStateException(rows.toString());
            }
        }

        @Override
        public void recoverOversizedWal() {
            requireOpen();
            List<String> rows = nonEmptyLines(sqliteUnchecked(database,
                    ReadReceiptAndroidMaintenanceWorker.WAL_CHECKPOINT_TRUNCATE_QUERY));
            if (rows.size() != 1 || !"0|0|0".equals(rows.get(0))) {
                throw new IllegalStateException(rows.toString());
            }
            recoveryResult = rows.get(0);
        }

        @Override
        public long eventRows() {
            requireOpen();
            return scalar(ReadReceiptAndroidMaintenanceWorker.EVENT_CAPACITY_QUERY + ";");
        }

        @Override
        public long mainBytes() {
            requireOpen();
            try {
                return Files.size(database);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public long walBytes() {
            requireOpen();
            try {
                return optionalSize(Path.of(database + "-wal"));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public long reclaimableBytes() {
            requireOpen();
            long pages = scalar("PRAGMA freelist_count;");
            long pageSize = scalar("PRAGMA page_size;");
            return Math.multiplyExact(pages, pageSize);
        }

        @Override
        public void reclaimPages(int maxPages) {
            requireOpen();
            if (maxPages != 256) throw new IllegalStateException();
            sqliteUnchecked(database, "PRAGMA incremental_vacuum(" + maxPages + ");");
        }

        @Override
        public void truncateWal() {
            requireOpen();
            List<String> rows = nonEmptyLines(sqliteUnchecked(database,
                    ReadReceiptAndroidMaintenanceWorker.WAL_CHECKPOINT_TRUNCATE_QUERY));
            if (rows.size() != 1 || !"0|0|0".equals(rows.get(0))) {
                throw new IllegalStateException(rows.toString());
            }
        }

        @Override
        public void close() {
            open = false;
        }

        private long scalar(String sql) {
            List<String> rows = nonEmptyLines(sqliteUnchecked(database, sql));
            if (rows.size() != 1) throw new IllegalStateException(rows.toString());
            return Long.parseLong(rows.get(0));
        }

        private void requireOpen() {
            if (!open) throw new IllegalStateException();
        }
    }

    private static final class WalFixture implements AutoCloseable {
        private final Process process;
        private final OutputStream input;

        private WalFixture(Process process, OutputStream input) {
            this.process = process;
            this.input = input;
        }

        @Override
        public void close() throws Exception {
            input.close();
            if (!process.waitFor(SQLITE_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("sqlite3 fixture close timed out");
            }
            if (process.exitValue() != 0) throw new IOException("sqlite3 fixture close failed");
        }
    }

    private static final class SqliteMaintainer
            implements ReadReceiptAndroidMaintenanceWorker.Maintainer {
        private final Path database;
        private final long lossFirst;
        private final long finalLoss;
        private long aggregateLast;
        private long calls;
        private String diagnostic;

        SqliteMaintainer(Path database, long lossFirst, long lossCount) {
            this.database = database;
            this.lossFirst = lossFirst;
            finalLoss = Math.addExact(lossFirst, lossCount - 1);
            aggregateLast = lossFirst - 1;
        }

        @Override
        public MaintenanceResult maintain(
                Capacity capacity) {
            if (aggregateLast >= finalLoss) {
                return MaintenanceResult.of(
                        MaintenanceOutcome.NO_ELIGIBLE_BATCH);
            }
            long oldLast = aggregateLast;
            long newLast = Math.min(finalLoss,
                    Math.addExact(Math.max(oldLast, lossFirst), 255));
            long selectedLosses = oldLast < lossFirst
                    ? newLast - lossFirst + 1 : newLast - oldLast + 1;
            long selectedBatches = newLast - Math.max(oldLast, lossFirst - 1);
            calls++;
            UUID lossId = new UUID(0x7000000000000000L, calls);
            ReadReceiptProtocolV2.Loss aggregate = new ReadReceiptProtocolV2.Loss(
                    lossId, lossFirst, newLast, newLast - lossFirst + 1,
                    newLast - lossFirst + 1, null, "bounded_prune",
                    null, null, null, null, null, calls + 1);
            byte[] digest = ReadReceiptProtocolV2.lossDigest(aggregate);
            String output = sqliteUnchecked(database,
                    "PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;"
                            + "DELETE FROM read_receipt_loss_segments WHERE stream_epoch = X'"
                            + hex(2) + "' AND first_sequence >= " + lossFirst
                            + " AND last_sequence <= " + newLast + ";SELECT changes();"
                            + "DELETE FROM read_receipt_batches WHERE stream_epoch = X'"
                            + hex(2) + "' AND first_sequence > "
                            + Math.max(oldLast, lossFirst - 1) + " AND last_sequence <= "
                            + newLast + " AND NOT EXISTS (SELECT 1 FROM read_receipt_outbox o "
                            + "WHERE o.stream_epoch = read_receipt_batches.stream_epoch "
                            + "AND o.batch_id = read_receipt_batches.batch_id) AND NOT EXISTS "
                            + "(SELECT 1 FROM read_receipt_loss_segments l WHERE l.stream_epoch = "
                            + "read_receipt_batches.stream_epoch AND l.batch_id = "
                            + "read_receipt_batches.batch_id);SELECT changes();"
                            + "INSERT INTO read_receipt_loss_segments VALUES (X'" + hex(2)
                            + "', X'" + hex(lossId) + "', " + lossFirst + ", " + newLast
                            + ", " + (newLast - lossFirst + 1) + ", "
                            + (newLast - lossFirst + 1)
                            + ", NULL, 'bounded_prune', NULL, NULL, NULL, NULL, NULL, "
                            + (calls + 1) + ", X'" + hex(digest) + "');COMMIT;");
            List<String> rows = nonEmptyLines(output);
            diagnostic = rows.toString();
            if (!rows.equals(Arrays.asList(Long.toString(selectedLosses),
                    Long.toString(selectedBatches)))) {
                throw new IllegalStateException(rows.toString());
            }
            aggregateLast = newLast;
            return new MaintenanceResult(
                    MaintenanceOutcome.COMPACTED, aggregate);
        }
    }

    private static ReadReceiptAndroidMaintenanceWorker worker(FakeAccess access,
                                                               FakeMaintainer maintainer) {
        return new ReadReceiptAndroidMaintenanceWorker(() -> access, maintainer);
    }

    private static final class FakeMaintainer
            implements ReadReceiptAndroidMaintenanceWorker.Maintainer {
        final Deque<MaintenanceOutcome> outcomes = new ArrayDeque<>();
        final List<Capacity> capacities = new ArrayList<>();
        int calls;

        @Override
        public MaintenanceResult maintain(
                Capacity capacity) {
            calls++;
            capacities.add(capacity);
            MaintenanceOutcome outcome = outcomes.isEmpty()
                    ? MaintenanceOutcome.NO_ELIGIBLE_BATCH
                    : outcomes.removeFirst();
            return MaintenanceResult.of(outcome);
        }
    }

    private static final class FakeAccess implements ReadReceiptAndroidMaintenanceWorker.Access {
        final List<String> trace = new ArrayList<>();
        final Deque<Capacity> capacities = new ArrayDeque<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        String fail;
        int checkpoints;
        int active;
        int maxActive;
        boolean blockFirst;
        boolean failClosedOnOpen;
        long reclaimableBytes;
        long startupWalBytes;
        long recoveredWalBytes;
        boolean recovered;
        Capacity current;
        Capacity reclaimedCapacity;

        @Override
        public void open() {
            trace.add("OPEN");
            if (failClosedOnOpen) {
                throw new DurabilityContractException();
            }
            active++;
            maxActive = Math.max(maxActive, active);
            if (blockFirst) {
                blockFirst = false;
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException();
                }
            }
        }

        @Override
        public void checkpoint() {
            trace.add("CHECKPOINT");
            checkpoints++;
            fail("CHECKPOINT");
            current = capacities.removeFirst();
        }

        @Override
        public long eventRows() {
            trace.add("ROWS");
            fail("ROWS");
            return current.eventRows;
        }

        @Override
        public long mainBytes() {
            trace.add("MAIN");
            fail("MAIN");
            return current.mainBytes;
        }

        @Override
        public long walBytes() {
            trace.add("WAL");
            fail("WAL");
            if (current == null) return recovered ? recoveredWalBytes : startupWalBytes;
            return current.walBytes;
        }

        @Override
        public void recoverOversizedWal() {
            trace.add("RECOVER_WAL");
            fail("RECOVER_WAL");
            recovered = true;
        }

        @Override
        public long reclaimableBytes() {
            trace.add("RECLAIMABLE");
            fail("RECLAIMABLE");
            return reclaimableBytes;
        }

        @Override
        public void reclaimPages(int maxPages) {
            trace.add("RECLAIM_PAGES:" + maxPages);
            fail("RECLAIM_PAGES");
            if (reclaimedCapacity != null) current = reclaimedCapacity;
        }

        @Override
        public void truncateWal() {
            trace.add("TRUNCATE_WAL");
            fail("TRUNCATE_WAL");
            current = new Capacity(current.eventRows,
                    current.mainBytes, 0);
        }

        @Override
        public void close() {
            trace.add("CLOSE");
            active--;
        }

        private void fail(String operation) {
            if (operation.equals(fail)) throw new IllegalStateException();
        }
    }
}
