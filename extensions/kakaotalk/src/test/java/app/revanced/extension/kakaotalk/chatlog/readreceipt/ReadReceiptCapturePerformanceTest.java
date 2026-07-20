package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.After;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ReadReceiptCapturePerformanceTest {
    private static final long GATE_NANOS = 5_000_000_000L;

    @After
    public void resetAfter() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @Test
    public void disabledAndDirectCaptureCallbacksStayWithinGenerousGate() {
        long disabledStart = System.nanoTime();
        for (int index = 0; index < 1_000_000; index++) {
            ReadReceiptCaptureFacade.captureSuccessfulWatermark(1L, index, index);
        }
        assertTrue(System.nanoTime() - disabledStart < GATE_NANOS);

        AtomicInteger accepted = new AtomicInteger();
        ReadReceiptCaptureCoordinator coordinator = new ReadReceiptCaptureCoordinator(
                batch -> {
                    accepted.incrementAndGet();
                    return true;
                },
                new ReadReceiptCaptureCoordinator.PersistenceClock() {
                    @Override
                    public long currentTimeMillis() {
                        return 1L;
                    }

                    @Override
                    public long elapsedRealtimeMillis() {
                        return 2L;
                    }
                },
                () -> null,
                (events, batches) -> { },
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                null
        );
        assertTrue(ReadReceiptCaptureFacade.activate(
                new ReadReceiptCaptureFacade.CaptureRuntime(coordinator)));

        int iterations = 100_000;
        long directStart = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            ReadReceiptCaptureFacade.captureSuccessfulWatermark(1L, index, index);
        }
        assertTrue(System.nanoTime() - directStart < GATE_NANOS);
        assertEquals(iterations, accepted.get());
    }
}
