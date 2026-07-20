package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.nonNegative;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.required;

import java.util.UUID;

enum AppendOutcome {
    COMMITTED,
    CONFIRMED_LOSS,
    UNCERTAIN,
    STORAGE_UNAVAILABLE,
    QUEUED,
    QUEUE_OCCUPIED
}

enum AckOutcome {
    ACCEPTED,
    LEASE_MISMATCH,
    RANGE_MISMATCH,
    DIGEST_MISMATCH,
    STORAGE_UNAVAILABLE
}

enum MaintenanceOutcome {
    WITHIN_CAP,
    CONVERTED,
    COMPACTED,
    BLOCKED_BY_LEASE,
    NO_ELIGIBLE_BATCH,
    STORAGE_UNAVAILABLE
}

final class AppendResult {
    final AppendOutcome outcome;
    final long firstSequence;
    final long lastSequence;

    AppendResult(AppendOutcome outcome, long firstSequence, long lastSequence) {
        this.outcome = outcome;
        this.firstSequence = firstSequence;
        this.lastSequence = lastSequence;
    }
}

final class LeaseFrame {
    final ReadReceiptProtocolV2.Lease lease;
    final byte[] digest;

    LeaseFrame(ReadReceiptProtocolV2.Lease lease, byte[] digest) {
        this.lease = required(lease);
        this.digest = required(digest).clone();
    }
}

final class Ack {
    final UUID installationId;
    final UUID streamEpoch;
    final UUID leaseId;
    final long throughSequence;
    final byte[] digest;

    Ack(UUID installationId, UUID streamEpoch, UUID leaseId, long throughSequence,
        byte[] digest) {
        this.installationId = required(installationId);
        this.streamEpoch = required(streamEpoch);
        this.leaseId = required(leaseId);
        this.throughSequence = nonNegative(throughSequence);
        this.digest = required(digest).clone();
    }
}

final class AckResult {
    final AckOutcome outcome;

    private AckResult(AckOutcome outcome) {
        this.outcome = outcome;
    }

    static AckResult of(AckOutcome outcome) {
        return new AckResult(outcome);
    }
}

final class Capacity {
    final long eventRows;
    final long mainBytes;
    final long walBytes;

    Capacity(long eventRows, long mainBytes, long walBytes) {
        this.eventRows = nonNegative(eventRows);
        this.mainBytes = nonNegative(mainBytes);
        this.walBytes = nonNegative(walBytes);
    }
}

final class MaintenanceResult {
    final MaintenanceOutcome outcome;
    final ReadReceiptProtocolV2.Loss loss;

    MaintenanceResult(MaintenanceOutcome outcome, ReadReceiptProtocolV2.Loss loss) {
        this.outcome = outcome;
        this.loss = loss;
    }

    static MaintenanceResult of(MaintenanceOutcome outcome) {
        return new MaintenanceResult(outcome, null);
    }
}

final class StateUpdate {
    final ReadReceiptProtocolV2.State state;
    final byte[] digest;

    StateUpdate(ReadReceiptProtocolV2.State state, byte[] digest) {
        this.state = state;
        this.digest = digest.clone();
    }
}

final class ReconcileResult {
    final AppendResult result;
    final boolean absenceProven;

    private ReconcileResult(AppendResult result, boolean absenceProven) {
        this.result = result;
        this.absenceProven = absenceProven;
    }

    static ReconcileResult committed(AppendResult result) {
        return new ReconcileResult(result, false);
    }

    static ReconcileResult absent() {
        return new ReconcileResult(null, true);
    }

    static ReconcileResult conflict() {
        return new ReconcileResult(null, false);
    }
}
