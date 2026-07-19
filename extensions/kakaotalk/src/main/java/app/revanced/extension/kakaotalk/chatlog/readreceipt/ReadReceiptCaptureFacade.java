package app.revanced.extension.kakaotalk.chatlog.readreceipt;

public final class ReadReceiptCaptureFacade {
    private static volatile CaptureRuntime activeRuntime;

    private ReadReceiptCaptureFacade() {
    }

    public static void captureSuccessfulWatermark(long chatId, long userId, long watermark) {
        CaptureRuntime runtime = activeRuntime;
        if (runtime == null) return;
        try {
            runtime.capture(chatId, userId, watermark);
        } catch (Throwable ignored) {
        }
    }

    static synchronized boolean activate(CaptureRuntime runtime) {
        if (runtime == null || activeRuntime != null) return false;
        activeRuntime = runtime;
        return true;
    }

    static synchronized void deactivate(CaptureRuntime runtime) {
        if (activeRuntime == runtime) activeRuntime = null;
    }

    static synchronized void resetForTests() {
        activeRuntime = null;
    }

    static final class CaptureRuntime {
        private final ReadReceiptCaptureCoordinator coordinator;

        CaptureRuntime(ReadReceiptCaptureCoordinator coordinator) {
            if (coordinator == null) throw new IllegalArgumentException("capture owner missing");
            this.coordinator = coordinator;
        }

        boolean capture(long chatId, long userId, long watermark) {
            return coordinator.capture(chatId, userId, watermark);
        }
    }
}
