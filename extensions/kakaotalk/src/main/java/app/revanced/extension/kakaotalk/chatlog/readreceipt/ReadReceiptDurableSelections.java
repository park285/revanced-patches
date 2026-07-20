package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.nonNegative;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.required;

import java.util.ArrayList;
import java.util.List;

final class LeaseProjection {
    final ReadReceiptProtocolV2.Lease lease;
    final List<BatchRecord> batches;
    final List<EventRecord> events;
    final List<LossRecord> losses;

    LeaseProjection(ReadReceiptProtocolV2.Lease lease, List<BatchRecord> batches,
                    List<EventRecord> events, List<LossRecord> losses) {
        this.lease = lease;
        this.batches = batches;
        this.events = events;
        this.losses = losses;
    }
}

final class BatchSelection {
    final BatchRecord batch;
    final long firstSequence;
    final long lastSequence;
    final long eventCount;
    final List<EventRecord> events;

    BatchSelection(BatchRecord batch, long firstSequence, long lastSequence,
                   long eventCount, List<EventRecord> events) {
        this.batch = batch;
        this.firstSequence = firstSequence;
        this.lastSequence = lastSequence;
        this.eventCount = eventCount;
        this.events = events;
    }
}

final class LossSelection {
    final List<LossRecord> losses;
    final List<BatchRecord> batches;
    final long firstSequence;
    final long lastSequence;
    final long eventCount;
    final long batchCount;

    LossSelection(List<LossRecord> losses, List<BatchRecord> batches,
                  long firstSequence, long lastSequence,
                  long eventCount, long batchCount) {
        this.losses = new ArrayList<>(required(losses));
        this.batches = new ArrayList<>(required(batches));
        if (firstSequence <= 0 || lastSequence < firstSequence || eventCount <= 0) {
            throw new StorageException();
        }
        this.firstSequence = firstSequence;
        this.lastSequence = lastSequence;
        this.eventCount = eventCount;
        this.batchCount = nonNegative(batchCount);
    }
}
