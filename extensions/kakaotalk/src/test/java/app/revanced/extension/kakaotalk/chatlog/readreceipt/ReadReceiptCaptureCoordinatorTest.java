package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReadReceiptCaptureCoordinatorTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOOT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SOURCE = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    public void successfulMutationIsDurablyOfferedWithExactOwnerIds() {
        List<ReadReceiptCaptureCoordinator.CapturedBatch> batches = new ArrayList<>();
        int[] drops = {0};
        ReadReceiptCaptureCoordinator coordinator = coordinator(batch -> {
            batches.add(batch);
            return true;
        }, drops);

        assertTrue(coordinator.capture(10L, 20L, 30L));

        assertEquals(1, batches.size());
        ReadReceiptCaptureCoordinator.CapturedBatch batch = batches.get(0);
        assertEquals(100L, batch.persistedAtMs);
        assertEquals(200L, batch.persistedElapsedMs);
        assertEquals(SESSION, batch.captureSessionId);
        assertEquals(BOOT, batch.captureBootId);
        assertEquals(SOURCE, batch.sourceEpochToken);
        assertEquals(1, batch.members.size());
        assertEquals(new ReadReceiptCaptureCoordinator.MemberWatermark(10L, 20L, 30L),
                batch.members.get(0));
        assertEquals(0, drops[0]);
    }

    @Test
    public void rejectedSinkAndClockFailureAreCountedAsCaptureDrops() {
        int[] drops = {0};
        ReadReceiptCaptureCoordinator rejected = coordinator(batch -> false, drops);
        assertFalse(rejected.capture(10L, 20L, 30L));

        ReadReceiptCaptureCoordinator brokenClock = new ReadReceiptCaptureCoordinator(
                batch -> true,
                new ReadReceiptCaptureCoordinator.PersistenceClock() {
                    @Override
                    public long currentTimeMillis() {
                        throw new IllegalStateException("clock");
                    }

                    @Override
                    public long elapsedRealtimeMillis() {
                        return 200L;
                    }
                },
                () -> SOURCE,
                (events, batches) -> drops[0] += events,
                SESSION,
                BOOT
        );
        assertFalse(brokenClock.capture(10L, 20L, 30L));
        assertEquals(2, drops[0]);
    }

    private static ReadReceiptCaptureCoordinator coordinator(
            ReadReceiptCaptureCoordinator.PersistenceSink sink,
            int[] drops
    ) {
        return new ReadReceiptCaptureCoordinator(
                sink,
                new ReadReceiptCaptureCoordinator.PersistenceClock() {
                    @Override
                    public long currentTimeMillis() {
                        return 100L;
                    }

                    @Override
                    public long elapsedRealtimeMillis() {
                        return 200L;
                    }
                },
                () -> SOURCE,
                (events, batches) -> drops[0] += events,
                SESSION,
                BOOT
        );
    }
}
