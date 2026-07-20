package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class ReadReceiptCaptureCoordinator {
    interface PersistenceSink {
        boolean accept(CapturedBatch batch);
    }

    interface PersistenceClock {
        long currentTimeMillis();

        long elapsedRealtimeMillis();
    }

    interface SourceEpochReader {
        UUID readOnce();
    }

    interface Diagnostics {
        void captureDrop(int eventCount, int batchCount);
    }

    static final class MemberWatermark {
        final long chatId;
        final long userId;
        final long watermark;

        MemberWatermark(long chatId, long userId, long watermark) {
            this.chatId = chatId;
            this.userId = userId;
            this.watermark = watermark;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MemberWatermark)) return false;
            MemberWatermark that = (MemberWatermark) other;
            return chatId == that.chatId && userId == that.userId && watermark == that.watermark;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(chatId);
            result = 31 * result + Long.hashCode(userId);
            return 31 * result + Long.hashCode(watermark);
        }
    }

    static final class CapturedBatch {
        final List<MemberWatermark> members;
        final long persistedAtMs;
        final long persistedElapsedMs;
        final UUID captureSessionId;
        final UUID captureBootId;
        final UUID sourceEpochToken;

        private CapturedBatch(
                List<MemberWatermark> members,
                long persistedAtMs,
                long persistedElapsedMs,
                UUID captureSessionId,
                UUID captureBootId,
                UUID sourceEpochToken
        ) {
            this.members = members;
            this.persistedAtMs = persistedAtMs;
            this.persistedElapsedMs = persistedElapsedMs;
            this.captureSessionId = captureSessionId;
            this.captureBootId = captureBootId;
            this.sourceEpochToken = sourceEpochToken;
        }
    }

    private final PersistenceSink sink;
    private final PersistenceClock clock;
    private final SourceEpochReader sourceEpochReader;
    private final Diagnostics diagnostics;
    private final UUID captureSessionId;
    private final UUID captureBootId;

    ReadReceiptCaptureCoordinator(
            PersistenceSink sink,
            PersistenceClock clock,
            SourceEpochReader sourceEpochReader,
            Diagnostics diagnostics,
            UUID captureSessionId,
            UUID captureBootId
    ) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceEpochReader = Objects.requireNonNull(sourceEpochReader, "sourceEpochReader");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.captureSessionId = Objects.requireNonNull(captureSessionId, "captureSessionId");
        this.captureBootId = captureBootId;
    }

    boolean capture(long chatId, long userId, long watermark) {
        try {
            long persistedAtMs = clock.currentTimeMillis();
            long persistedElapsedMs = clock.elapsedRealtimeMillis();
            if (persistedAtMs < 0L || persistedElapsedMs < 0L) return dropped();

            UUID sourceEpochToken = sourceEpochReader.readOnce();
            CapturedBatch batch = new CapturedBatch(
                    Collections.singletonList(new MemberWatermark(chatId, userId, watermark)),
                    persistedAtMs,
                    persistedElapsedMs,
                    captureSessionId,
                    captureBootId,
                    sourceEpochToken
            );
            if (sink.accept(batch)) return true;
        } catch (Throwable ignored) {
        }
        return dropped();
    }

    private boolean dropped() {
        try {
            diagnostics.captureDrop(1, 1);
        } catch (Throwable ignored) {
        }
        return false;
    }
}
