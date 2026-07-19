package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.add;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.nonNegative;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.required;
import static app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptDurableModelSupport.sameNullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

enum Status {
    ACTIVE,
    POISONED
}

enum PendingBatchState {
    PENDING,
    COMMITTING,
    TERMINAL_COMMITTED,
    TERMINAL_CONFIRMED_LOSS,
    TERMINAL_UNCERTAIN
}

final class CapturedEvent {
    final UUID eventId;
    final long chatId;
    final long userId;
    final long watermark;

    CapturedEvent(UUID eventId, long chatId, long userId, long watermark) {
        this.eventId = required(eventId);
        this.chatId = chatId;
        this.userId = userId;
        this.watermark = watermark;
    }
}

final class BatchInput {
    final UUID batchId;
    final long persistedAtMs;
    final long persistedElapsedMs;
    final UUID captureSessionId;
    final UUID captureBootId;
    final UUID sourceEpochToken;
    final List<CapturedEvent> events;

    BatchInput(UUID batchId, long persistedAtMs, long persistedElapsedMs,
               UUID captureSessionId, UUID captureBootId, UUID sourceEpochToken,
               List<CapturedEvent> events) {
        this.batchId = required(batchId);
        this.persistedAtMs = nonNegative(persistedAtMs);
        this.persistedElapsedMs = nonNegative(persistedElapsedMs);
        this.captureSessionId = required(captureSessionId);
        this.captureBootId = captureBootId;
        this.sourceEpochToken = sourceEpochToken;
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("empty batch");
        }
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        Set<UUID> eventIds = new HashSet<>();
        for (CapturedEvent event : this.events) {
            if (!eventIds.add(required(event).eventId)) {
                throw new IllegalArgumentException("duplicate event id");
            }
        }
    }
}

final class PendingBatchRecord {
    final PendingBatchState state;
    final BatchInput input;
    final CounterTarget counterTarget;

    PendingBatchRecord(PendingBatchState state, BatchInput input,
                       CounterTarget counterTarget) {
        this.state = required(state);
        this.input = required(input);
        this.counterTarget = counterTarget;
        if ((state == PendingBatchState.TERMINAL_UNCERTAIN)
                != (counterTarget != null)) {
            throw new IllegalArgumentException("invalid pending batch terminal");
        }
    }

    boolean terminal() {
        return state != PendingBatchState.PENDING
                && state != PendingBatchState.COMMITTING;
    }

    boolean sameInput(BatchInput other) {
        if (other == null || !input.batchId.equals(other.batchId)
                || input.persistedAtMs != other.persistedAtMs
                || input.persistedElapsedMs != other.persistedElapsedMs
                || !input.captureSessionId.equals(other.captureSessionId)
                || !sameNullable(input.captureBootId, other.captureBootId)
                || !sameNullable(input.sourceEpochToken, other.sourceEpochToken)
                || input.events.size() != other.events.size()) return false;
        for (int index = 0; index < input.events.size(); index++) {
            CapturedEvent left = input.events.get(index);
            CapturedEvent right = other.events.get(index);
            if (!left.eventId.equals(right.eventId) || left.chatId != right.chatId
                    || left.userId != right.userId || left.watermark != right.watermark) {
                return false;
            }
        }
        return true;
    }

    boolean same(PendingBatchRecord other) {
        return other != null && state == other.state && sameInput(other.input)
                && (counterTarget == null ? other.counterTarget == null
                : counterTarget.same(other.counterTarget));
    }
}

final class Counters {
    final long confirmedDroppedEventCount;
    final long confirmedDroppedBatchCount;
    final long uncertainOutcomeEventCount;
    final long uncertainOutcomeBatchCount;
    final long captureDropEventCount;
    final long captureDropBatchCount;

    Counters(long confirmedDroppedEventCount, long confirmedDroppedBatchCount,
             long uncertainOutcomeEventCount, long uncertainOutcomeBatchCount,
             long captureDropEventCount, long captureDropBatchCount) {
        this.confirmedDroppedEventCount = nonNegative(confirmedDroppedEventCount);
        this.confirmedDroppedBatchCount = nonNegative(confirmedDroppedBatchCount);
        this.uncertainOutcomeEventCount = nonNegative(uncertainOutcomeEventCount);
        this.uncertainOutcomeBatchCount = nonNegative(uncertainOutcomeBatchCount);
        this.captureDropEventCount = nonNegative(captureDropEventCount);
        this.captureDropBatchCount = nonNegative(captureDropBatchCount);
    }

    static Counters zero() {
        return new Counters(0, 0, 0, 0, 0, 0);
    }

    static Counters fromProtocol(ReadReceiptProtocolV2.Counters counters) {
        return new Counters(counters.confirmedDroppedEventCount,
                counters.confirmedDroppedBatchCount, counters.uncertainOutcomeEventCount,
                counters.uncertainOutcomeBatchCount, counters.captureDropEventCount,
                counters.captureDropBatchCount);
    }

    Counters addConfirmed(long events, long batches) {
        return new Counters(add(confirmedDroppedEventCount, events),
                add(confirmedDroppedBatchCount, batches), uncertainOutcomeEventCount,
                uncertainOutcomeBatchCount, captureDropEventCount, captureDropBatchCount);
    }

    Counters addUncertain(long events, long batches) {
        return new Counters(confirmedDroppedEventCount, confirmedDroppedBatchCount,
                add(uncertainOutcomeEventCount, events),
                add(uncertainOutcomeBatchCount, batches), captureDropEventCount,
                captureDropBatchCount);
    }

    Counters addCaptureDrop(long events, long batches) {
        return new Counters(confirmedDroppedEventCount, confirmedDroppedBatchCount,
                uncertainOutcomeEventCount, uncertainOutcomeBatchCount,
                add(captureDropEventCount, events), add(captureDropBatchCount, batches));
    }

    Counters max(Counters other) {
        return new Counters(Math.max(confirmedDroppedEventCount,
                other.confirmedDroppedEventCount), Math.max(confirmedDroppedBatchCount,
                other.confirmedDroppedBatchCount), Math.max(uncertainOutcomeEventCount,
                other.uncertainOutcomeEventCount), Math.max(uncertainOutcomeBatchCount,
                other.uncertainOutcomeBatchCount), Math.max(captureDropEventCount,
                other.captureDropEventCount), Math.max(captureDropBatchCount,
                other.captureDropBatchCount));
    }

    ReadReceiptProtocolV2.Counters toProtocol() {
        return new ReadReceiptProtocolV2.Counters(confirmedDroppedEventCount,
                confirmedDroppedBatchCount, uncertainOutcomeEventCount,
                uncertainOutcomeBatchCount, captureDropEventCount, captureDropBatchCount);
    }

    boolean atLeast(Counters other) {
        return confirmedDroppedEventCount >= other.confirmedDroppedEventCount
                && confirmedDroppedBatchCount >= other.confirmedDroppedBatchCount
                && uncertainOutcomeEventCount >= other.uncertainOutcomeEventCount
                && uncertainOutcomeBatchCount >= other.uncertainOutcomeBatchCount
                && captureDropEventCount >= other.captureDropEventCount
                && captureDropBatchCount >= other.captureDropBatchCount;
    }

    boolean same(ReadReceiptProtocolV2.Counters other) {
        return confirmedDroppedEventCount == other.confirmedDroppedEventCount
                && confirmedDroppedBatchCount == other.confirmedDroppedBatchCount
                && uncertainOutcomeEventCount == other.uncertainOutcomeEventCount
                && uncertainOutcomeBatchCount == other.uncertainOutcomeBatchCount
                && captureDropEventCount == other.captureDropEventCount
                && captureDropBatchCount == other.captureDropBatchCount;
    }
}

final class StateRecord {
    final UUID installationId;
    final UUID streamEpoch;
    final long lastAckedSequence;
    final long highestIssuedSequence;
    final long nextSequence;
    final Counters counters;
    final Status status;
    final String poisonCategory;

    StateRecord(UUID installationId, UUID streamEpoch, long lastAckedSequence,
                long highestIssuedSequence, long nextSequence, Counters counters,
                Status status) {
        this(installationId, streamEpoch, lastAckedSequence, highestIssuedSequence,
                nextSequence, counters, status, null);
    }

    StateRecord(UUID installationId, UUID streamEpoch, long lastAckedSequence,
                long highestIssuedSequence, long nextSequence, Counters counters,
                Status status, String poisonCategory) {
        this.installationId = required(installationId);
        this.streamEpoch = required(streamEpoch);
        this.lastAckedSequence = nonNegative(lastAckedSequence);
        this.highestIssuedSequence = nonNegative(highestIssuedSequence);
        this.nextSequence = nextSequence;
        this.counters = required(counters);
        this.status = required(status);
        this.poisonCategory = poisonCategory;
        if ((status == Status.ACTIVE) != (poisonCategory == null)
                || status == Status.POISONED
                && !ReadReceiptProtocolV2.validNack(poisonCategory, false)) {
            throw new IllegalArgumentException("invalid poison state");
        }
    }

    StateRecord withIssued(long next, long highest, Counters updatedCounters) {
        return new StateRecord(installationId, streamEpoch, lastAckedSequence, highest,
                next, updatedCounters, status, poisonCategory);
    }

    StateRecord withAck(long acked) {
        return new StateRecord(installationId, streamEpoch, acked, highestIssuedSequence,
                nextSequence, counters, status, poisonCategory);
    }

    StateRecord withCounters(Counters updated) {
        return new StateRecord(installationId, streamEpoch, lastAckedSequence,
                highestIssuedSequence, nextSequence, updated, status, poisonCategory);
    }

    StateRecord poisoned(String category) {
        return new StateRecord(installationId, streamEpoch, lastAckedSequence,
                highestIssuedSequence, nextSequence, counters, Status.POISONED,
                required(category));
    }
}

final class CounterTarget {
    final UUID installationId;
    final UUID streamEpoch;
    final Counters counters;

    CounterTarget(UUID installationId, UUID streamEpoch, Counters counters) {
        this.installationId = required(installationId);
        this.streamEpoch = required(streamEpoch);
        this.counters = required(counters);
    }

    boolean matches(StateRecord state) {
        return installationId.equals(state.installationId)
                && streamEpoch.equals(state.streamEpoch);
    }

    boolean same(CounterTarget other) {
        return other != null && installationId.equals(other.installationId)
                && streamEpoch.equals(other.streamEpoch)
                && counters.same(other.counters.toProtocol());
    }
}
