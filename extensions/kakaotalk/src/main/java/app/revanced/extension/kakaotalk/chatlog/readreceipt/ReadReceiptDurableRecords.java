package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.exactDigest;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.required;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.sameNullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class BatchRecord {
    final UUID streamEpoch;
    final ReadReceiptProtocolV2.Metadata metadata;
    final byte[] metadataDigest;

    BatchRecord(UUID streamEpoch, ReadReceiptProtocolV2.Metadata metadata,
                byte[] metadataDigest) {
        this.streamEpoch = required(streamEpoch);
        this.metadata = required(metadata);
        this.metadataDigest = required(metadataDigest).clone();
    }

    boolean digestMatches() {
        return exactDigest(metadataDigest, ReadReceiptProtocolV2.metadataDigest(metadata));
    }

    boolean same(BatchRecord other) {
        return other != null && streamEpoch.equals(other.streamEpoch)
                && metadata.batchId.equals(other.metadata.batchId)
                && exactDigest(metadataDigest, other.metadataDigest);
    }
}

final class EventRecord {
    final UUID streamEpoch;
    final ReadReceiptProtocolV2.Event event;

    EventRecord(UUID streamEpoch, ReadReceiptProtocolV2.Event event) {
        this.streamEpoch = required(streamEpoch);
        this.event = required(event);
    }

    boolean same(EventRecord other) {
        return other != null && streamEpoch.equals(other.streamEpoch)
                && event.sequence == other.event.sequence
                && event.eventId.equals(other.event.eventId)
                && event.batchId.equals(other.event.batchId)
                && event.chatId == other.event.chatId && event.userId == other.event.userId
                && event.watermark == other.event.watermark;
    }
}

final class LossRecord {
    final UUID streamEpoch;
    final ReadReceiptProtocolV2.Loss loss;
    final byte[] contentDigest;

    LossRecord(UUID streamEpoch, ReadReceiptProtocolV2.Loss loss,
               byte[] contentDigest) {
        this.streamEpoch = required(streamEpoch);
        this.loss = required(loss);
        this.contentDigest = required(contentDigest).clone();
    }

    boolean digestMatches() {
        return exactDigest(contentDigest, ReadReceiptProtocolV2.lossDigest(loss));
    }

    boolean same(LossRecord other) {
        return other != null && streamEpoch.equals(other.streamEpoch)
                && loss.lossReceiptId.equals(other.loss.lossReceiptId)
                && exactDigest(contentDigest, other.contentDigest);
    }
}

final class LeaseRecord {
    final UUID streamEpoch;
    final UUID leaseId;
    final long firstSequence;
    final long lastSequence;
    final long previousSequence;
    final ReadReceiptProtocolV2.Counters counters;
    final byte[] digest;
    final Status status;
    final String poisonCategory;

    LeaseRecord(UUID streamEpoch, UUID leaseId, long firstSequence, long lastSequence,
                long previousSequence, ReadReceiptProtocolV2.Counters counters,
                byte[] digest, Status status) {
        this(streamEpoch, leaseId, firstSequence, lastSequence, previousSequence,
                counters, digest, status, null);
    }

    LeaseRecord(UUID streamEpoch, UUID leaseId, long firstSequence, long lastSequence,
                long previousSequence, ReadReceiptProtocolV2.Counters counters,
                byte[] digest, Status status, String poisonCategory) {
        this.streamEpoch = required(streamEpoch);
        this.leaseId = required(leaseId);
        this.firstSequence = firstSequence;
        this.lastSequence = lastSequence;
        this.previousSequence = previousSequence;
        this.counters = required(counters);
        this.digest = required(digest).clone();
        this.status = required(status);
        this.poisonCategory = poisonCategory;
        if ((status == Status.ACTIVE) != (poisonCategory == null)
                || status == Status.POISONED
                && !ReadReceiptProtocolV2.validNack(poisonCategory, false)) {
            throw new IllegalArgumentException("invalid poison lease");
        }
    }

    boolean same(LeaseRecord other) {
        return other != null && streamEpoch.equals(other.streamEpoch)
                && leaseId.equals(other.leaseId) && firstSequence == other.firstSequence
                && lastSequence == other.lastSequence
                && previousSequence == other.previousSequence
                && Counters.fromProtocol(counters).same(other.counters)
                && status == other.status
                && sameNullable(poisonCategory, other.poisonCategory)
                && exactDigest(digest, other.digest);
    }

    LeaseRecord poisoned(String category) {
        return new LeaseRecord(streamEpoch, leaseId, firstSequence, lastSequence,
                previousSequence, counters, digest, Status.POISONED, required(category));
    }
}

final class Snapshot {
    StateRecord state;
    LeaseRecord lease;
    final List<BatchRecord> batches;
    final List<EventRecord> events;
    final List<LossRecord> losses;

    Snapshot(StateRecord state, LeaseRecord lease, List<BatchRecord> batches,
             List<EventRecord> events, List<LossRecord> losses) {
        this.state = state;
        this.lease = lease;
        this.batches = new ArrayList<>(required(batches));
        this.events = new ArrayList<>(required(events));
        this.losses = new ArrayList<>(required(losses));
    }

    Snapshot copy() {
        return new Snapshot(state, lease, batches, events, losses);
    }
}
