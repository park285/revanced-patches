package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ReadReceiptPendingBatchSink
        implements ReadReceiptCaptureCoordinator.PersistenceSink {
    private static final int MAX_EVENTS = 256;
    interface IdSource {
        UUID next();
    }

    private final ReadReceiptDurableStream stream;
    private final IdSource ids;

    ReadReceiptPendingBatchSink(ReadReceiptDurableStream stream, IdSource ids) {
        if (stream == null || ids == null) throw new IllegalArgumentException(
                "pending sink owner missing");
        this.stream = stream;
        this.ids = ids;
    }

    @Override
    public boolean accept(ReadReceiptCaptureCoordinator.CapturedBatch captured) {
        if (captured == null || captured.members == null || captured.members.isEmpty()
                || captured.members.size() > MAX_EVENTS) return false;
        try {
            List<CapturedEvent> events =
                    new ArrayList<>(captured.members.size());
            for (ReadReceiptCaptureCoordinator.MemberWatermark member : captured.members) {
                events.add(new CapturedEvent(ids.next(), member.chatId,
                        member.userId, member.watermark));
            }
            BatchInput input = new BatchInput(
                    ids.next(), captured.persistedAtMs, captured.persistedElapsedMs,
                    captured.captureSessionId, captured.captureBootId,
                    captured.sourceEpochToken, events);
            return accepted(stream.append(input).outcome);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean accepted(AppendOutcome outcome) {
        return outcome == AppendOutcome.COMMITTED
                || outcome == AppendOutcome.CONFIRMED_LOSS
                || outcome == AppendOutcome.UNCERTAIN
                || outcome == AppendOutcome.QUEUED;
    }
}
