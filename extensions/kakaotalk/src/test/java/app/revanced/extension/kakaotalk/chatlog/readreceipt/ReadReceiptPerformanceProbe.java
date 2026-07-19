package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

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
                passed = startupEmpty(sandbox, uid);
            } else if ("startup-near-cap".equals(mode)) {
                passed = startupNearCap(sandbox, uid);
            } else if ("capture-bursts".equals(mode)) {
                passed = captureBursts(sandbox, uid);
            } else if ("capture-disabled".equals(mode)) {
                passed = captureDisabled();
            } else if ("transfer-drain-256".equals(mode)) {
                passed = transferDrain256(sandbox);
            } else {
                result("unsupported_mode", false);
            }
        } catch (Throwable ignored) {
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
        byte[] token = new byte[32];
        byte[] epoch = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(token);
        random.nextBytes(epoch);
        byte[] installation = ReadReceiptAndroidSqlite.uuidBytes(
                ReadReceiptProtocolV2.installationId(token));
        String databasePath = sandbox.databases + "/iris_read_receipts.db";
        if (!new ReadReceiptAndroidSqlite().createFresh(
                databasePath, installation, epoch, System.currentTimeMillis())) {
            throw new IllegalStateException();
        }
        return new Prepared(token, epoch, databasePath);
    }

    private static ReadReceiptV2Runtime startRuntime(
            Prepared prepared,
            ReadReceiptPerformanceSandbox sandbox,
            int uid
    ) {
        ReadReceiptStartupValidation validation = new ReadReceiptStartupValidation(
                new ReadReceiptAndroidStartupStore(prepared.databasePath),
                () -> ReadReceiptV2Runtime.prepare(
                        prepared.token, sandbox.noBackup, sandbox.databases,
                        new AndroidReadReceiptFileOps(), uid)
        );
        ReadReceiptStartupValidation.Result result = validation.validateAndStart();
        if (result.outcome != ReadReceiptStartupValidation.Outcome.READY
                || !(result.activation instanceof ReadReceiptV2Runtime)) {
            throw new IllegalStateException();
        }
        ReadReceiptV2Runtime runtime = (ReadReceiptV2Runtime) result.activation;
        if (!runtime.healthy()) {
            close(runtime);
            throw new IllegalStateException();
        }
        return runtime;
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
