package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReadReceiptV2CaptureRuntimeTest {
    @Before
    public void resetBefore() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @After
    public void resetAfter() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @Test
    public void factoryActivatesOnlyOnce() {
        ReadReceiptV2CaptureRuntime.Factory factory =
                ReadReceiptV2CaptureRuntime.factory(coordinator());

        assertTrue(factory.activate());
        assertFalse(factory.activate());
    }

    @Test
    public void failedGlobalActivationStillConsumesFactory() {
        ReadReceiptV2CaptureRuntime.Factory first =
                ReadReceiptV2CaptureRuntime.factory(coordinator());
        ReadReceiptV2CaptureRuntime.Factory second =
                ReadReceiptV2CaptureRuntime.factory(coordinator());

        assertTrue(first.activate());
        assertFalse(second.activate());
        ReadReceiptCaptureFacade.resetForTests();
        assertFalse(second.activate());
    }

    @Test
    public void nullCoordinatorFailsClosed() {
        assertFalse(ReadReceiptV2CaptureRuntime.factory(null).activate());
    }

    @Test
    public void deactivateRemovesOnlyFactoryOwnedRuntime() {
        ReadReceiptV2CaptureRuntime.Factory first =
                ReadReceiptV2CaptureRuntime.factory(coordinator());
        ReadReceiptV2CaptureRuntime.Factory second =
                ReadReceiptV2CaptureRuntime.factory(coordinator());

        assertTrue(first.activate());
        second.deactivate();
        assertFalse(second.activate());
        first.deactivate();
        assertTrue(ReadReceiptV2CaptureRuntime.factory(coordinator()).activate());
    }

    private static ReadReceiptCaptureCoordinator coordinator() {
        return new ReadReceiptCaptureCoordinator(
                batch -> true,
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
