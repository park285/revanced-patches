package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.os.SystemClock;

import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ReadReceiptV2Runtime implements ReadReceiptStartupValidation.Activation {
    private static final int TOKEN_SIZE = 32;
    interface PartsFactory {
        Parts create(byte[] token);
    }

    interface Parts {
        void bindFailureHandler(Runnable handler);

        void startOutbox();

        boolean activateCapture();

        void deactivateCapture();

        void closeOutbox();

        void closeExecutor();
    }

    private final Parts parts;
    private boolean started;
    private boolean closed;

    private ReadReceiptV2Runtime(Parts parts) {
        if (parts == null) throw new IllegalArgumentException("runtime parts missing");
        this.parts = parts;
        parts.bindFailureHandler(this::failClosed);
    }

    static ReadReceiptV2Runtime prepare(
            byte[] token,
            String noBackupDirectory,
            String databaseDirectory,
            ReadReceiptFileOps fileOps,
            int uid
    ) {
        if (noBackupDirectory == null || noBackupDirectory.isEmpty()
                || databaseDirectory == null || databaseDirectory.isEmpty()
                || fileOps == null || uid < 0) {
            throw new IllegalArgumentException("runtime owner missing");
        }
        String databasePath = databaseDirectory + "/iris_read_receipts.db";
        return prepareForTests(token, exactToken -> ProductionParts.create(
                exactToken, noBackupDirectory, databasePath, fileOps, uid));
    }

    static ReadReceiptV2Runtime prepareForTests(byte[] token, PartsFactory factory) {
        if (token == null || token.length != TOKEN_SIZE || factory == null) {
            throw new IllegalArgumentException("activation token missing");
        }
        byte[] exactToken = token.clone();
        Parts created = factory.create(exactToken);
        return new ReadReceiptV2Runtime(created);
    }

    @Override
    public synchronized void start() {
        if (closed || started) throw new IllegalStateException("runtime activation failed");
        try {
            parts.startOutbox();
            if (!parts.activateCapture()) {
                throw new IllegalStateException("capture activation failed");
            }
            started = true;
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        started = false;
        closeStep(parts::deactivateCapture);
        closeStep(parts::closeOutbox);
        closeStep(parts::closeExecutor);
    }

    private static void closeStep(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException ignored) {
        }
    }

    boolean startedForTests() {
        synchronized (this) {
            return started;
        }
    }

    void failClosedForTests() {
        failClosed();
    }

    boolean healthy() {
        synchronized (this) {
            return started && !closed;
        }
    }

    private void failClosed() {
        close();
    }

    private static final class ProductionParts implements Parts {
        private final ScheduledThreadPoolExecutor executor;
        private final ReadReceiptV2OutboxServer outbox;
        private final ReadReceiptV2CaptureRuntime.Factory capture;
        private final RuntimeOwner owner;

        private ProductionParts(
                ScheduledThreadPoolExecutor executor,
                ReadReceiptV2OutboxServer outbox,
                ReadReceiptV2CaptureRuntime.Factory capture,
                RuntimeOwner owner
        ) {
            this.executor = executor;
            this.outbox = outbox;
            this.capture = capture;
            this.owner = owner;
        }

        static ProductionParts create(
                byte[] token,
                String noBackupDirectory,
                String databasePath,
                ReadReceiptFileOps fileOps,
                int uid
        ) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                    1,
                    runnable -> {
                        Thread thread = new Thread(runnable, "read-receipt-v2-owner");
                        thread.setDaemon(true);
                        return thread;
                    }
            );
            executor.setRemoveOnCancelPolicy(true);
            RuntimeOwner owner = new RuntimeOwner(executor);
            try {
                ReadReceiptAndroidDurableStore store =
                        new ReadReceiptAndroidDurableStore(databasePath);
                AtomicReference<ReadReceiptDurableStream> streamReference =
                        new AtomicReference<>();
                ReadReceiptAndroidMaintenanceWorker maintenance =
                        new ReadReceiptAndroidMaintenanceWorker(databasePath, capacity -> {
                            ReadReceiptDurableStream stream = streamReference.get();
                            return stream == null
                                    ? MaintenanceResult.of(
                                    MaintenanceOutcome.STORAGE_UNAVAILABLE)
                                    : stream.maintain(capacity);
                        });
                if (!maintenance.recoverPhysicalWalOnStart()) {
                    throw new IllegalStateException("startup storage recovery unavailable");
                }
                ReadReceiptDurableStream stream = new ReadReceiptDurableStream(
                        store,
                        UUID::randomUUID,
                        System::currentTimeMillis,
                        (delayMs, work) -> executor.schedule(
                                () -> runOwned(owner, work), delayMs, TimeUnit.MILLISECONDS),
                        maintenance,
                        new ReadReceiptAndroidCounterJournal(
                                noBackupDirectory,
                                "iris_read_receipt_v2.counter-target",
                                uid,
                                new ReadReceiptAndroidCounterJournal.AndroidOps(),
                                () -> UUID.randomUUID().toString()),
                        new ReadReceiptAndroidPendingBatchJournal(
                                noBackupDirectory,
                                "iris_read_receipt_v2.pending-batch",
                                uid,
                                new ReadReceiptAndroidCounterJournal.AndroidOps(),
                                () -> UUID.randomUUID().toString()),
                        owner::failClosed
                );
                streamReference.set(stream);

                ReadReceiptPendingBatchSink sink =
                        new ReadReceiptPendingBatchSink(stream, UUID::randomUUID);
                ReadReceiptCaptureCoordinator.Diagnostics diagnostics =
                        new StreamDiagnostics(stream);
                UUID captureSessionId = UUID.randomUUID();
                UUID captureBootId = ReadReceiptBootIdReader.read(fileOps);
                ReadReceiptCaptureCoordinator coordinator = new ReadReceiptCaptureCoordinator(
                        sink,
                        new ReadReceiptCaptureCoordinator.PersistenceClock() {
                            @Override
                            public long currentTimeMillis() {
                                return System.currentTimeMillis();
                            }

                            @Override
                            public long elapsedRealtimeMillis() {
                                return SystemClock.elapsedRealtime();
                            }
                        },
                        () -> ReadReceiptSourceEpochMarker.read(
                                fileOps, noBackupDirectory, uid),
                        diagnostics,
                        captureSessionId,
                        captureBootId
                );
                ReadReceiptV2CaptureRuntime.Factory capture =
                        ReadReceiptV2CaptureRuntime.factory(coordinator);
                ReadReceiptV2OutboxServer outbox = ReadReceiptV2OutboxServer.android(
                        token, new StreamPortAdapter(stream));
                ProductionParts parts = new ProductionParts(executor, outbox, capture, owner);
                return parts;
            } catch (RuntimeException exception) {
                executor.shutdownNow();
                throw exception;
            }
        }

        private static void runOwned(RuntimeOwner owner, Runnable work) {
            try {
                work.run();
            } catch (RuntimeException exception) {
                owner.failClosed(exception);
            }
        }

        @Override
        public void bindFailureHandler(Runnable handler) {
            owner.bind(handler);
        }

        @Override
        public void startOutbox() {
            outbox.start();
        }

        @Override
        public boolean activateCapture() {
            return capture.activate();
        }

        @Override
        public void deactivateCapture() {
            capture.deactivate();
        }

        @Override
        public void closeOutbox() {
            outbox.close();
        }

        @Override
        public void closeExecutor() {
            executor.shutdownNow();
        }
    }

    private static final class RuntimeOwner {
        private final ScheduledThreadPoolExecutor executor;
        private volatile Runnable failureHandler;

        private RuntimeOwner(ScheduledThreadPoolExecutor executor) {
            this.executor = executor;
        }

        void bind(Runnable failureHandler) {
            this.failureHandler = failureHandler;
        }

        void failClosed(RuntimeException ignored) {
            Runnable current = failureHandler;
            if (current == null) {
                executor.shutdownNow();
                return;
            }
            closeStep(current);
        }
    }

    private static final class StreamDiagnostics
            implements ReadReceiptCaptureCoordinator.Diagnostics {
        private final ReadReceiptDurableStream stream;

        private StreamDiagnostics(ReadReceiptDurableStream stream) {
            this.stream = stream;
        }

        @Override
        public void captureDrop(int eventCount, int batchCount) {
            stream.recordCaptureDrop(eventCount, batchCount);
        }
    }

    private static final class StreamPortAdapter implements ReadReceiptV2OutboxServer.StreamPort {
        private final ReadReceiptDurableStream stream;

        private StreamPortAdapter(ReadReceiptDurableStream stream) {
            this.stream = stream;
        }

        @Override
        public ReadReceiptProtocolV2.State currentState() {
            return stream.currentState();
        }

        @Override
        public LeaseFrame claimOrReplayLease() {
            return stream.claimOrReplayLease();
        }

        @Override
        public StateUpdate stateUpdate() {
            return stream.stateUpdate();
        }

        @Override
        public AckOutcome acceptAck(Ack ack) {
            return stream.acceptAck(ack).outcome;
        }

        @Override
        public void onNack(String category, boolean retryable) {
            stream.onNack(category, retryable);
        }

        @Override
        public void requestRetry() {
            stream.requestRetry();
        }
    }
}
