package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReadReceiptCaptureFacadeTest {
    @Before
    public void resetBefore() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @After
    public void resetAfter() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @Test
    public void disabledFacadeIsNoOp() {
        ReadReceiptCaptureFacade.captureSuccessfulWatermark(10L, 20L, 30L);
    }

    @Test
    public void activeFacadeCapturesTheExactSuccessfulMutationSynchronously() {
        List<ReadReceiptCaptureCoordinator.CapturedBatch> batches = new ArrayList<>();
        ReadReceiptCaptureFacade.CaptureRuntime runtime = new ReadReceiptCaptureFacade.CaptureRuntime(
                coordinator(batch -> {
                    batches.add(batch);
                    return true;
                })
        );
        assertTrue(ReadReceiptCaptureFacade.activate(runtime));

        ReadReceiptCaptureFacade.captureSuccessfulWatermark(10L, 20L, 30L);

        assertEquals(1, batches.size());
        assertEquals(new ReadReceiptCaptureCoordinator.MemberWatermark(10L, 20L, 30L),
                batches.get(0).members.get(0));
    }

    @Test
    public void activationHasOneGlobalOwnerAndDeactivationIsOwnershipChecked() {
        ReadReceiptCaptureFacade.CaptureRuntime first =
                new ReadReceiptCaptureFacade.CaptureRuntime(coordinator(batch -> true));
        ReadReceiptCaptureFacade.CaptureRuntime second =
                new ReadReceiptCaptureFacade.CaptureRuntime(coordinator(batch -> true));

        assertTrue(ReadReceiptCaptureFacade.activate(first));
        assertFalse(ReadReceiptCaptureFacade.activate(second));
        ReadReceiptCaptureFacade.deactivate(second);
        assertFalse(ReadReceiptCaptureFacade.activate(second));
        ReadReceiptCaptureFacade.deactivate(first);
        assertTrue(ReadReceiptCaptureFacade.activate(second));
    }

    private static ReadReceiptCaptureCoordinator coordinator(
            ReadReceiptCaptureCoordinator.PersistenceSink sink
    ) {
        return new ReadReceiptCaptureCoordinator(
                sink,
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
    }
}
