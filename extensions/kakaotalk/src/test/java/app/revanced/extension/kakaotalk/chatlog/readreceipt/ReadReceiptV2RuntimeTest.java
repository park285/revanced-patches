package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ReadReceiptV2RuntimeTest {
    @Before
    public void resetBefore() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @After
    public void resetAfter() {
        ReadReceiptCaptureFacade.resetForTests();
    }

    @Test
    public void prepareDefensivelyPassesTheExactActivationToken() {
        byte[] token = token();
        byte[][] observed = new byte[1][];
        RecordingParts parts = new RecordingParts();

        ReadReceiptV2Runtime runtime = ReadReceiptV2Runtime.prepareForTests(token, value -> {
            observed[0] = value;
            return parts;
        });
        token[0] = 99;

        assertEquals(32, observed[0].length);
        assertEquals(0, observed[0][0]);
        assertNotSame(token, observed[0]);
        runtime.close();
    }

    @Test
    public void startOrdersAllFallibleWorkBeforeCaptureActivation() {
        RecordingParts parts = new RecordingParts();
        ReadReceiptV2Runtime runtime = ReadReceiptV2Runtime.prepareForTests(token(), value -> parts);

        runtime.start();

        assertEquals(Arrays.asList("outbox-start", "capture-activate"),
                parts.events);
        assertTrue(runtime.startedForTests());
        runtime.close();
        assertEquals(Arrays.asList("outbox-start", "capture-activate",
                "capture-deactivate", "outbox-close", "executor-close"), parts.events);
    }

    @Test
    public void failureBeforeOrAtCaptureActivationFailsClosed() {
        RecordingParts parts = new RecordingParts();
        parts.captureActivation = false;
        ReadReceiptV2Runtime runtime = ReadReceiptV2Runtime.prepareForTests(token(), value -> parts);

        boolean failed = false;
        try {
            runtime.start();
        } catch (RuntimeException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertFalse(runtime.startedForTests());
        assertEquals(Arrays.asList("outbox-start", "capture-activate",
                "capture-deactivate", "outbox-close", "executor-close"), parts.events);
    }

    @Test
    public void supervisorFailureFromAnyOwnerThreadUsesTheSameIdempotentShutdown()
            throws InterruptedException {
        RecordingParts parts = new RecordingParts();
        ReadReceiptV2Runtime runtime = ReadReceiptV2Runtime.prepareForTests(token(), value -> parts);
        runtime.start();

        Thread executorThread = new Thread(parts.failureHandler, "synthetic-executor-owner");
        Thread outboxThread = new Thread(parts.failureHandler, "synthetic-outbox-owner");
        executorThread.start();
        executorThread.join();
        outboxThread.start();
        outboxThread.join();
        runtime.close();

        assertEquals(1, Collections.frequency(parts.events, "capture-deactivate"));
        assertEquals(1, Collections.frequency(parts.events, "outbox-close"));
        assertEquals(1, Collections.frequency(parts.events, "executor-close"));
        assertFalse(runtime.healthy());
    }

    private static byte[] token() {
        byte[] token = new byte[32];
        for (int index = 0; index < token.length; index++) token[index] = (byte) index;
        return token;
    }

    private static final class RecordingParts implements ReadReceiptV2Runtime.Parts {
        private final List<String> events = new ArrayList<>();
        private boolean captureActivation = true;
        private Runnable failureHandler;

        @Override
        public void bindFailureHandler(Runnable handler) {
            failureHandler = handler;
        }

        @Override
        public void startOutbox() {
            events.add("outbox-start");
        }

        @Override
        public boolean activateCapture() {
            events.add("capture-activate");
            return captureActivation;
        }

        @Override
        public void deactivateCapture() {
            events.add("capture-deactivate");
        }

        @Override
        public void closeOutbox() {
            events.add("outbox-close");
        }

        @Override
        public void closeExecutor() {
            events.add("executor-close");
        }
    }
}
