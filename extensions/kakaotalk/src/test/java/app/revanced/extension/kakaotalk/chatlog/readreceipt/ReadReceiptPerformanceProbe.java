package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;

public final class ReadReceiptPerformanceProbe {
    private static final int CAPTURE_REPETITIONS = 5;
    private static final int CAPTURE_WARMUP_EVENTS = 8;
    private static final int DISABLED_CAPTURE_WARMUP_EVENTS = 32;
    private static final int DISABLED_CAPTURE_SAMPLES = 1_000;
    private static final long HARD_SAFETY_NANOS = 5_000_000_000L;
    private static final int[] BURST_SIZES = {1, 10, 100, 256};

    private ReadReceiptPerformanceProbe() {
    }

    public static void main(String[] arguments) {
        if (arguments.length == 1 && "--self-test".equals(arguments[0])) {
            selfTest();
            return;
        }
        if (arguments.length != 2
                || !ReadReceiptPerformanceSandbox.acceptsNonce(arguments[1])) {
            result("invalid_arguments", false);
            System.exit(2);
        }

        String mode = arguments[0];
        String rootPath = ReadReceiptPerformanceSandbox.rootFor(arguments[1]);
        int uid = Process.myUid();
        boolean passed = false;
        boolean sandboxOwned = false;
        boolean cleanupPassed = false;
        try {
            ReadReceiptPerformanceSandbox sandbox =
                    ReadReceiptPerformanceSandbox.create(rootPath, uid);
            sandboxOwned = true;
            if ("startup-empty".equals(mode)) {
                requireMaintenancePreflight(sandbox);
                passed = startupEmpty(sandbox, uid);
            } else if ("startup-near-cap".equals(mode)) {
                requireMaintenancePreflight(sandbox);
                passed = startupNearCap(sandbox, uid);
            } else if ("capture-bursts".equals(mode)) {
                requireMaintenancePreflight(sandbox);
                passed = captureBursts(sandbox, uid);
            } else if ("capture-disabled".equals(mode)) {
                passed = captureDisabled();
            } else if ("transfer-drain-256".equals(mode)) {
                requireMaintenancePreflight(sandbox);
                passed = transferDrain256(sandbox);
            } else {
                result("unsupported_mode", false);
            }
        } catch (Throwable failure) {
            metric("probe_failure_kind", failureKind(failure));
            metric("probe_failure_stage", failureStage(failure));
            result("probe_execution", false);
        } finally {
            cleanupPassed = !sandboxOwned || ReadReceiptPerformanceSandbox.remove(rootPath, uid);
            metric("cleanup_passed", cleanupPassed ? 1 : 0);
        }
        passed &= cleanupPassed;
        result("probe", passed);
        if (!passed) System.exit(3);
    }

    private static boolean startupEmpty(ReadReceiptPerformanceSandbox sandbox, int uid) {
        long bootstrapStarted = System.nanoTime();
        Prepared prepared = prepareFresh(sandbox);
        long bootstrapElapsed = System.nanoTime() - bootstrapStarted;
        long started = System.nanoTime();
        ReadReceiptV2Runtime runtime = startRuntime(prepared, sandbox, uid);
        long elapsed = System.nanoTime() - started;
        long totalElapsed = Math.addExact(bootstrapElapsed, elapsed);
        close(runtime);
        metric("startup_kind", "empty");
        metric("bootstrap_elapsed_us", nanosToMicros(bootstrapElapsed));
        metric("startup_elapsed_us", nanosToMicros(elapsed));
        metric("bootstrap_startup_elapsed_us", nanosToMicros(totalElapsed));
        metric("database_rows", ReadReceiptPerformanceFixture.countEvents(
                prepared.databasePath));
        metric("database_triplet_bytes", databaseTripletBytes(prepared.databasePath));
        boolean passed = totalElapsed < HARD_SAFETY_NANOS;
        metric("hard_safety_passed", passed ? 1 : 0);
        return passed;
    }

    private static boolean startupNearCap(ReadReceiptPerformanceSandbox sandbox, int uid) {
        Prepared prepared = prepareFresh(sandbox);
        long seedStarted = System.nanoTime();
        ReadReceiptProtocolV2.Metadata metadata = ReadReceiptPerformanceFixture.seedNearCap(
                prepared.databasePath, prepared.epoch);
        long seedElapsed = System.nanoTime() - seedStarted;
        ReadReceiptPerformanceFixture.verifyNearCap(prepared.databasePath, metadata);
        ReadReceiptPerformanceFixture.verifyNearCapLease(prepared.databasePath);
        long started = System.nanoTime();
        ReadReceiptV2Runtime runtime = startRuntime(prepared, sandbox, uid);
        long elapsed = System.nanoTime() - started;
        close(runtime);
        ReadReceiptPerformanceFixture.verifyNearCapMaintenance(prepared.databasePath);
        long rows = ReadReceiptPerformanceFixture.countEvents(prepared.databasePath);
        metric("startup_kind", "near_cap");
        metric("seed_elapsed_us", nanosToMicros(seedElapsed));
        metric("startup_elapsed_us", nanosToMicros(elapsed));
        metric("database_rows", rows);
        metric("database_triplet_bytes", databaseTripletBytes(prepared.databasePath));
        boolean passed = rows == ReadReceiptPerformanceFixture.NEAR_CAP_ROWS
                && elapsed < HARD_SAFETY_NANOS;
        metric("hard_safety_passed", passed ? 1 : 0);
        return passed;
    }

    private static boolean captureBursts(ReadReceiptPerformanceSandbox sandbox, int uid) {
        Prepared prepared = prepareFresh(sandbox);
        ReadReceiptV2Runtime runtime = startRuntime(prepared, sandbox, uid);
        int expectedEvents = 0;
        boolean passed = true;
        try {
            for (int index = 0; index < CAPTURE_WARMUP_EVENTS; index++) {
                ReadReceiptCaptureFacade.captureSuccessfulWatermark(1L, 1L, index + 1L);
            }
            expectedEvents += CAPTURE_WARMUP_EVENTS;
            for (int burstSize : BURST_SIZES) {
                long[] calls = new long[burstSize * CAPTURE_REPETITIONS];
                long[] bursts = new long[CAPTURE_REPETITIONS];
                int sample = 0;
                for (int repetition = 0; repetition < CAPTURE_REPETITIONS; repetition++) {
                    long burstStarted = System.nanoTime();
                    for (int index = 0; index < burstSize; index++) {
                        long callStarted = System.nanoTime();
                        ReadReceiptCaptureFacade.captureSuccessfulWatermark(
                                1L, 1L, expectedEvents + index + 1L);
                        calls[sample++] = System.nanoTime() - callStarted;
                    }
                    bursts[repetition] = System.nanoTime() - burstStarted;
                    expectedEvents += burstSize;
                }
                Arrays.sort(calls);
                Arrays.sort(bursts);
                metric("capture_burst_size", burstSize);
                metric("capture_samples", calls.length);
                metric("capture_call_p50_us", percentileMicros(calls, 50));
                metric("capture_call_p95_us", percentileMicros(calls, 95));
                metric("capture_call_p99_us", percentileMicros(calls, 99));
                metric("capture_call_max_us", nanosToMicros(calls[calls.length - 1]));
                metric("capture_burst_p50_us", percentileMicros(bursts, 50));
                metric("capture_burst_p95_us", percentileMicros(bursts, 95));
                metric("capture_burst_p99_us", percentileMicros(bursts, 99));
                if (calls[calls.length - 1] >= HARD_SAFETY_NANOS) passed = false;
            }
        } finally {
            close(runtime);
        }
        long rows = ReadReceiptPerformanceFixture.countEvents(prepared.databasePath);
        metric("database_rows", rows);
        metric("database_triplet_bytes", databaseTripletBytes(prepared.databasePath));
        passed &= rows == expectedEvents;
        metric("hard_safety_passed", passed ? 1 : 0);
        return passed;
    }

    private static boolean captureDisabled() {
        for (int index = 0; index < DISABLED_CAPTURE_WARMUP_EVENTS; index++) {
            ReadReceiptCaptureFacade.captureSuccessfulWatermark(1L, 1L, index + 1L);
        }
        long[] calls = new long[DISABLED_CAPTURE_SAMPLES];
        for (int index = 0; index < calls.length; index++) {
            long started = System.nanoTime();
            ReadReceiptCaptureFacade.captureSuccessfulWatermark(1L, 1L, index + 1L);
            calls[index] = System.nanoTime() - started;
        }
        Arrays.sort(calls);
        metric("disabled_capture_samples", calls.length);
        metric("disabled_capture_p50_us", percentileMicros(calls, 50));
        metric("disabled_capture_p95_us", percentileMicros(calls, 95));
        metric("disabled_capture_p99_us", percentileMicros(calls, 99));
        metric("disabled_capture_max_us", nanosToMicros(calls[calls.length - 1]));
        boolean passed = calls[calls.length - 1] < HARD_SAFETY_NANOS;
        metric("hard_safety_passed", passed ? 1 : 0);
        return passed;
    }

    private static boolean transferDrain256(ReadReceiptPerformanceSandbox sandbox) {
        Prepared prepared = prepareFresh(sandbox);
        ReadReceiptPerformanceFixture.TransferResult transfer =
                ReadReceiptPerformanceFixture.transferDrain256(prepared.databasePath);
        metric("transfer_before_acked", transfer.beforeAcked);
        metric("transfer_before_highest", transfer.beforeHighest);
        metric("transfer_before_outbox", transfer.beforeOutbox);
        metric("transfer_frame_events", transfer.frameEvents);
        metric("transfer_after_acked", transfer.afterAcked);
        metric("transfer_after_highest", transfer.afterHighest);
        metric("transfer_after_outbox", transfer.afterOutbox);
        metric("transfer_elapsed_us", nanosToMicros(transfer.elapsedNanos));
        metric("database_triplet_bytes", databaseTripletBytes(prepared.databasePath));
        boolean passed = transfer.elapsedNanos < HARD_SAFETY_NANOS;
        metric("hard_safety_passed", passed ? 1 : 0);
        return passed;
    }

    private static Prepared prepareFresh(ReadReceiptPerformanceSandbox sandbox) {
        try {
            byte[] token = new byte[32];
            byte[] epoch = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(token);
            random.nextBytes(epoch);
            byte[] installation = ReadReceiptAndroidSqlite.uuidBytes(
                    ReadReceiptProtocolV2.installationId(token));
            String databasePath = sandbox.databases + "/iris_read_receipts.db";
            createEmptyDatabaseFile(databasePath, Process.myUid());
            if (!new ReadReceiptAndroidSqlite().createFresh(
                    databasePath, installation, epoch, System.currentTimeMillis())) {
                throw new ProbeFailure("create_fresh_rejected", new IllegalStateException());
            }
            return new Prepared(token, epoch, databasePath);
        } catch (ProbeFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ProbeFailure("prepare_exception", failure);
        }
    }

    private static void requireMaintenancePreflight(ReadReceiptPerformanceSandbox sandbox) {
        String path = sandbox.databases + "/iris_read_receipts.preflight.db";
        String outcome;
        try {
            byte[] token = randomBytes(32);
            byte[] epoch = randomBytes(16);
            byte[] installation = ReadReceiptAndroidSqlite.uuidBytes(
                    ReadReceiptProtocolV2.installationId(token));
            createEmptyDatabaseFile(path, Process.myUid());
            if (!new ReadReceiptAndroidSqlite().createFresh(
                    path, installation, epoch, System.currentTimeMillis())) {
                throw new ProbeFailure("maintenance_create_fresh", new IllegalStateException());
            }
            outcome = maintenancePreflight(path);
        } catch (ProbeFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ProbeFailure("maintenance_preflight", failure);
        } finally {
            if (!SQLiteDatabase.deleteDatabase(new File(path))) {
                throw new ProbeFailure("maintenance_cleanup", new IllegalStateException());
            }
        }
        metric("maintenance_preflight", outcome);
        if (!"ok".equals(outcome)) {
            throw new ProbeFailure("maintenance_" + outcome, new IllegalStateException());
        }
    }

    private static void createEmptyDatabaseFile(String path, int uid) {
        AndroidReadReceiptFileOps fileOps = new AndroidReadReceiptFileOps();
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(path, ReadReceiptFileOps.OpenKind.CREATE_EXCLUSIVE);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            if (!status.regular || status.directory || status.uid != uid
                    || status.mode != 0600 || status.size != 0
                    || status.device < 0 || status.inode <= 0) {
                throw new IllegalStateException();
            }
            fileOps.close(handle);
            handle = null;
        } catch (ReadReceiptFileOps.Failure failure) {
            throw new IllegalStateException();
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                }
            }
        }
    }

    private static String maintenancePreflight(String path) {
        SQLiteDatabase database = null;
        String stage = "open";
        try {
            database = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            stage = "foreign_keys";
            database.setForeignKeyConstraintsEnabled(true);
            stage = "busy_timeout";
            if (sqliteScalar(database, "PRAGMA busy_timeout=0") != 0) return "busy_timeout";
            stage = "foreign_keys_read";
            if (sqliteScalar(database, "PRAGMA foreign_keys") != 1) return "foreign_keys";
            stage = "wal_autocheckpoint_read";
            if (sqliteScalar(database, "PRAGMA wal_autocheckpoint=0") != 0) {
                return "wal_autocheckpoint";
            }
            stage = "page_size_read";
            if (sqliteScalar(database, "PRAGMA page_size") != ReadReceiptSchemaV2.PAGE_SIZE_BYTES) {
                return "page_size";
            }
            stage = "auto_vacuum_read";
            if (sqliteScalar(database, "PRAGMA auto_vacuum")
                    != ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) {
                return "auto_vacuum";
            }
            stage = "reclaim_prepare";
            database.execSQL("CREATE TABLE iris_probe_reclaim(payload BLOB NOT NULL)");
            database.execSQL("INSERT INTO iris_probe_reclaim VALUES(zeroblob(32768))");
            database.execSQL("DROP TABLE iris_probe_reclaim");
            if (sqliteScalar(database, "PRAGMA freelist_count") <= 0) return "reclaim_prepare";
            database.close();
            database = null;
            stage = "reclaim";
            ReadReceiptAndroidMaintenanceWorker.AndroidAccess access =
                    new ReadReceiptAndroidMaintenanceWorker.AndroidAccess(path);
            try {
                access.open();
                long before = access.reclaimableBytes();
                access.reclaimPages(1);
                long after = access.reclaimableBytes();
                if (before <= 0 || after >= before) return "reclaim_progress";
            } finally {
                access.close();
            }
            return "ok";
        } catch (RuntimeException failure) {
            return stage;
        } finally {
            if (database != null) database.close();
        }
    }

    private static long sqliteScalar(SQLiteDatabase database, String sql) {
        Cursor cursor = database.rawQuery(sql, null);
        try {
            if (!cursor.moveToNext()) throw new IllegalStateException();
            long value = cursor.getLong(0);
            if (cursor.moveToNext()) throw new IllegalStateException();
            return value;
        } finally {
            cursor.close();
        }
    }

    private static ReadReceiptV2Runtime startRuntime(
            Prepared prepared,
            ReadReceiptPerformanceSandbox sandbox,
            int uid
    ) {
        try {
            String[] activationStage = {"runtime_prepare"};
            ReadReceiptStartupValidation validation = new ReadReceiptStartupValidation(
                    new ReadReceiptAndroidStartupStore(prepared.databasePath),
                    () -> {
                        ReadReceiptV2Runtime runtime = ReadReceiptV2Runtime.prepare(
                                prepared.token, sandbox.noBackup, sandbox.databases,
                                new AndroidReadReceiptFileOps(), uid);
                        activationStage[0] = "runtime_start";
                        return runtime;
                    }
            );
            ReadReceiptStartupValidation.Result result = validation.validateAndStart();
            if (result.outcome != ReadReceiptStartupValidation.Outcome.READY) {
                String stage = result.outcome == ReadReceiptStartupValidation.Outcome.POISONED
                        ? "runtime_poisoned"
                        : result.outcome == ReadReceiptStartupValidation.Outcome.STORAGE_UNAVAILABLE
                        ? "runtime_storage_unavailable"
                        : activationStage[0];
                throw new ProbeFailure(stage, new IllegalStateException());
            }
            if (!(result.activation instanceof ReadReceiptV2Runtime)) {
                throw new ProbeFailure("runtime_activation", new IllegalStateException());
            }
            ReadReceiptV2Runtime runtime = (ReadReceiptV2Runtime) result.activation;
            if (!runtime.healthy()) {
                close(runtime);
                throw new ProbeFailure("runtime_unhealthy", new IllegalStateException());
            }
            return runtime;
        } catch (ProbeFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ProbeFailure("runtime_exception", failure);
        }
    }

    private static long databaseTripletBytes(String databasePath) {
        return Math.addExact(fileBytes(databasePath, false),
                Math.addExact(fileBytes(databasePath + "-wal", true),
                        fileBytes(databasePath + "-shm", true)));
    }

    private static long fileBytes(String path, boolean absentAllowed) {
        try {
            StructStat status = Os.lstat(path);
            if (!OsConstants.S_ISREG(status.st_mode) || status.st_size < 0) {
                throw new IllegalStateException();
            }
            return status.st_size;
        } catch (ErrnoException exception) {
            if (absentAllowed && exception.errno == OsConstants.ENOENT) return 0L;
            throw new IllegalStateException();
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static void close(ReadReceiptV2Runtime runtime) {
        if (runtime == null) return;
        try {
            runtime.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static String failureKind(Throwable failure) {
        if (failure instanceof ProbeFailure && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof LinkageError) return "linkage";
        if (failure instanceof SecurityException) return "security";
        if (failure instanceof IllegalStateException) return "state";
        if (failure instanceof RuntimeException) return "runtime";
        return "other";
    }

    private static String failureStage(Throwable failure) {
        return failure instanceof ProbeFailure
                ? ((ProbeFailure) failure).stage
                : "dispatch";
    }

    private static final class ProbeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String stage;

        ProbeFailure(String stage, Throwable cause) {
            super(cause);
            this.stage = stage;
        }
    }

    private static long percentileMicros(long[] sortedNanos, int percentile) {
        int index = Math.max(0,
                (int) Math.ceil(percentile / 100.0d * sortedNanos.length) - 1);
        return nanosToMicros(sortedNanos[index]);
    }

    private static long nanosToMicros(long nanos) {
        return (nanos + 999L) / 1_000L;
    }

    private static void selfTest() {
        long[] values = {4_000L, 1_000L, 3_000L, 2_000L};
        Arrays.sort(values);
        boolean passed = ReadReceiptPerformanceSandbox.acceptsNonce(
                "rrv2-perf-0123456789abcdef")
                && !ReadReceiptPerformanceSandbox.acceptsNonce("../databases")
                && !ReadReceiptPerformanceSandbox.acceptsNonce(
                "rrv2-perf-0123456789abcdeg")
                && percentileMicros(values, 50) == 2L
                && percentileMicros(values, 95) == 4L
                && ReadReceiptPerformanceSandbox.rootFor(
                "rrv2-perf-0123456789abcdef").endsWith("/rrv2-perf-0123456789abcdef");
        result("self_test", passed);
        if (!passed) System.exit(4);
    }

    private static void metric(String name, long value) {
        System.out.println(name + "=" + value);
    }

    private static void metric(String name, String value) {
        System.out.println(name + "=" + value);
    }

    private static void result(String stage, boolean passed) {
        System.out.println("result=" + stage + ",passed=" + passed);
    }

    private static final class Prepared {
        final byte[] token;
        final byte[] epoch;
        final String databasePath;

        Prepared(byte[] token, byte[] epoch, String databasePath) {
            this.token = token.clone();
            this.epoch = epoch.clone();
            this.databasePath = databasePath;
        }
    }

}
