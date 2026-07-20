package app.revanced.extension.kakaotalk.chatlog.readreceipt;

final class ReadReceiptV2CaptureRuntime {
    private ReadReceiptV2CaptureRuntime() {
    }

    interface Factory {
        boolean activate();

        void deactivate();
    }

    static Factory factory(ReadReceiptCaptureCoordinator coordinator) {
        return new OneShotFactory(coordinator);
    }

    private static final class OneShotFactory implements Factory {
        private final ReadReceiptCaptureCoordinator coordinator;
        private boolean attempted;
        private ReadReceiptCaptureFacade.CaptureRuntime active;

        private OneShotFactory(ReadReceiptCaptureCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public synchronized boolean activate() {
            if (attempted) return false;
            attempted = true;
            if (coordinator == null) return false;
            ReadReceiptCaptureFacade.CaptureRuntime candidate =
                    new ReadReceiptCaptureFacade.CaptureRuntime(coordinator);
            if (!ReadReceiptCaptureFacade.activate(candidate)) return false;
            active = candidate;
            return true;
        }

        @Override
        public synchronized void deactivate() {
            ReadReceiptCaptureFacade.CaptureRuntime current = active;
            active = null;
            if (current != null) ReadReceiptCaptureFacade.deactivate(current);
        }
    }
}
