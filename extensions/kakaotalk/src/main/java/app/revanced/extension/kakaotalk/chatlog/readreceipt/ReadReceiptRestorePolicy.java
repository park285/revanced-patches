package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.Objects;

public final class ReadReceiptRestorePolicy {
    private static final MutationPermissions NO_MUTATIONS =
            new MutationPermissions(false, false, false, false);
    private static final MutationPermissions CREATE_TOKEN_DATABASE_AND_EPOCH =
            new MutationPermissions(true, true, true, false);
    private static final MutationPermissions CREATE_DATABASE_AND_EPOCH =
            new MutationPermissions(false, true, true, false);
    private static final MutationPermissions WRITE_LOCAL_DURABLE_POISON =
            new MutationPermissions(false, false, false, true);

    private ReadReceiptRestorePolicy() {
    }

    public static LocalDecision classifyLocal(LocalState state) {
        Objects.requireNonNull(state);

        switch (state) {
            case TOKEN_ABSENT_DATABASE_ABSENT:
                return new LocalDecision(
                        LocalAction.CREATE_TOKEN_DATABASE_AND_RANDOM_EPOCH,
                        HandshakeConsequence.NORMAL_NEW_INSTALLATION_MATRIX,
                        CREATE_TOKEN_DATABASE_AND_EPOCH);
            case VALID_TOKEN_DATABASE_ABSENT:
                return new LocalDecision(
                        LocalAction.CREATE_DATABASE_AND_RANDOM_EPOCH,
                        HandshakeConsequence.KNOWN_INSTALLATION_NEW_EPOCH,
                        CREATE_DATABASE_AND_EPOCH);
            case DATABASE_PRESENT_TOKEN_ABSENT:
                return new LocalDecision(
                        LocalAction.FAIL_CLOSED,
                        HandshakeConsequence.OPERATOR_REVIEW,
                        NO_MUTATIONS);
            case EXACT_DATABASE_INSTALLATION_MATCH:
                return new LocalDecision(
                        LocalAction.RUN_STARTUP_VALIDATION,
                        HandshakeConsequence.APPLY_HANDSHAKE_MATRIX,
                        NO_MUTATIONS);
            case EXACT_DATABASE_INSTALLATION_MISMATCH:
                return new LocalDecision(
                        LocalAction.FAIL_CLOSED,
                        HandshakeConsequence.OPERATOR_REVIEW,
                        NO_MUTATIONS);
            case UNKNOWN_SCHEMA_WITH_ANY_TOKEN_STATE:
                return new LocalDecision(
                        LocalAction.SCHEMA_UNAVAILABLE,
                        HandshakeConsequence.NO_HANDSHAKE_OR_SOCKET_BIND,
                        NO_MUTATIONS);
            case EXACT_DATABASE_INVARIANT_FAILURE:
                return new LocalDecision(
                        LocalAction.DURABLE_LOCAL_POISON,
                        HandshakeConsequence.REPEATED_STARTUP_POISONED,
                        WRITE_LOCAL_DURABLE_POISON);
            default:
                throw new AssertionError();
        }
    }

    public static HandshakeDecision classifyHandshake(HandshakeInput input) {
        Objects.requireNonNull(input);

        if (input.senderStatus == StreamStatus.POISONED
                || input.irisStatus == StreamStatus.POISONED
                || input.senderActiveLeaseStatus == ActiveLeaseStatus.POISONED
                || input.irisActiveLeaseStatus == ActiveLeaseStatus.POISONED) {
            return handshake(HandshakeClassification.STREAM_POISONED, false);
        }
        if (input.irisKnowledge == IrisKnowledge.KNOWN_STREAM) {
            boolean senderLeaseAbsent =
                    input.senderActiveLeaseStatus == ActiveLeaseStatus.NONE;
            boolean leaseRelationAbsent = input.leaseRelation == LeaseRelation.NONE;
            if (senderLeaseAbsent != leaseRelationAbsent) {
                throw new IllegalArgumentException("unsupported handshake state");
            }
        }

        switch (input.irisKnowledge) {
            case NO_STREAM:
                return input.senderAck == 0
                        ? handshake(HandshakeClassification.ACCEPT_NEW_STREAM, false)
                        : handshake(HandshakeClassification.STREAM_ROLLBACK, false);
            case UNKNOWN_INSTALLATION:
                if (input.senderAck == 0) {
                    return handshake(HandshakeClassification.ACCEPT_NEW_INSTALLATION, false);
                }
                throw new IllegalArgumentException("unsupported handshake state");
            case KNOWN_STREAM:
                return classifyKnownStream(input);
            default:
                throw new AssertionError();
        }
    }

    private static HandshakeDecision classifyKnownStream(HandshakeInput input) {
        if (input.epochRelation == EpochRelation.DIFFERENT) {
            return handshake(HandshakeClassification.UNKNOWN_EPOCH, true);
        }
        if (input.irisCursor > input.senderHighestIssued
                || input.leaseRelation == LeaseRelation.CONTAINS_IRIS_CURSOR) {
            return handshake(HandshakeClassification.STREAM_ROLLBACK, true);
        }
        if (input.irisCursor == input.senderAck) {
            return handshake(HandshakeClassification.ACCEPT, false);
        }
        if (input.irisCursor > input.senderAck
                && input.leaseRelation == LeaseRelation.ENDS_AT_IRIS_CURSOR) {
            return handshake(HandshakeClassification.ACCEPT_EXACT_REPLAY_ONLY, false);
        }
        return handshake(HandshakeClassification.STREAM_ROLLBACK, true);
    }

    private static HandshakeDecision handshake(
            HandshakeClassification classification,
            boolean irisDurablePoisonWriteRequired
    ) {
        return new HandshakeDecision(
                classification,
                irisDurablePoisonWriteRequired,
                false,
                NO_MUTATIONS);
    }

    public enum LocalState {
        TOKEN_ABSENT_DATABASE_ABSENT,
        VALID_TOKEN_DATABASE_ABSENT,
        DATABASE_PRESENT_TOKEN_ABSENT,
        EXACT_DATABASE_INSTALLATION_MATCH,
        EXACT_DATABASE_INSTALLATION_MISMATCH,
        UNKNOWN_SCHEMA_WITH_ANY_TOKEN_STATE,
        EXACT_DATABASE_INVARIANT_FAILURE
    }

    public enum LocalAction {
        CREATE_TOKEN_DATABASE_AND_RANDOM_EPOCH,
        CREATE_DATABASE_AND_RANDOM_EPOCH,
        FAIL_CLOSED,
        RUN_STARTUP_VALIDATION,
        SCHEMA_UNAVAILABLE,
        DURABLE_LOCAL_POISON
    }

    public enum HandshakeConsequence {
        NORMAL_NEW_INSTALLATION_MATRIX,
        KNOWN_INSTALLATION_NEW_EPOCH,
        OPERATOR_REVIEW,
        APPLY_HANDSHAKE_MATRIX,
        NO_HANDSHAKE_OR_SOCKET_BIND,
        REPEATED_STARTUP_POISONED
    }

    public enum StreamStatus {
        ACTIVE,
        POISONED
    }

    public enum ActiveLeaseStatus {
        NONE,
        ACTIVE,
        POISONED
    }

    public enum EpochRelation {
        SAME,
        DIFFERENT,
        NOT_APPLICABLE
    }

    public enum LeaseRelation {
        NONE,
        ENDS_AT_IRIS_CURSOR,
        CONTAINS_IRIS_CURSOR,
        OTHER
    }

    public enum HandshakeClassification {
        ACCEPT_NEW_STREAM,
        ACCEPT,
        ACCEPT_EXACT_REPLAY_ONLY,
        STREAM_ROLLBACK,
        UNKNOWN_EPOCH,
        ACCEPT_NEW_INSTALLATION,
        STREAM_POISONED
    }

    private enum IrisKnowledge {
        NO_STREAM,
        KNOWN_STREAM,
        UNKNOWN_INSTALLATION
    }

    public static final class LocalDecision {
        private final LocalAction action;
        private final HandshakeConsequence handshakeConsequence;
        private final MutationPermissions mutations;

        private LocalDecision(
                LocalAction action,
                HandshakeConsequence handshakeConsequence,
                MutationPermissions mutations
        ) {
            this.action = action;
            this.handshakeConsequence = handshakeConsequence;
            this.mutations = mutations;
        }

        public LocalAction action() {
            return action;
        }

        public HandshakeConsequence handshakeConsequence() {
            return handshakeConsequence;
        }

        public MutationPermissions mutations() {
            return mutations;
        }
    }

    public static final class HandshakeInput {
        private final IrisKnowledge irisKnowledge;
        private final StreamStatus senderStatus;
        private final StreamStatus irisStatus;
        private final ActiveLeaseStatus senderActiveLeaseStatus;
        private final ActiveLeaseStatus irisActiveLeaseStatus;
        private final EpochRelation epochRelation;
        private final long irisCursor;
        private final long senderAck;
        private final long senderHighestIssued;
        private final LeaseRelation leaseRelation;

        private HandshakeInput(
                IrisKnowledge irisKnowledge,
                StreamStatus senderStatus,
                StreamStatus irisStatus,
                ActiveLeaseStatus senderActiveLeaseStatus,
                ActiveLeaseStatus irisActiveLeaseStatus,
                EpochRelation epochRelation,
                long irisCursor,
                long senderAck,
                long senderHighestIssued,
                LeaseRelation leaseRelation
        ) {
            if (irisCursor < 0 || senderAck < 0 || senderHighestIssued < 0
                    || senderAck > senderHighestIssued) {
                throw new IllegalArgumentException("invalid handshake state");
            }
            this.irisKnowledge = Objects.requireNonNull(irisKnowledge);
            this.senderStatus = Objects.requireNonNull(senderStatus);
            this.irisStatus = irisStatus;
            this.senderActiveLeaseStatus = Objects.requireNonNull(senderActiveLeaseStatus);
            this.irisActiveLeaseStatus = Objects.requireNonNull(irisActiveLeaseStatus);
            this.epochRelation = Objects.requireNonNull(epochRelation);
            this.irisCursor = irisCursor;
            this.senderAck = senderAck;
            this.senderHighestIssued = senderHighestIssued;
            this.leaseRelation = Objects.requireNonNull(leaseRelation);
        }

        public static HandshakeInput noStream(long senderAck) {
            return noStream(StreamStatus.ACTIVE, senderAck);
        }

        public static HandshakeInput noStream(StreamStatus senderStatus, long senderAck) {
            return noStream(senderStatus, ActiveLeaseStatus.NONE, senderAck);
        }

        public static HandshakeInput noStream(
                StreamStatus senderStatus,
                ActiveLeaseStatus senderActiveLeaseStatus,
                long senderAck
        ) {
            return new HandshakeInput(
                    IrisKnowledge.NO_STREAM,
                    senderStatus,
                    null,
                    senderActiveLeaseStatus,
                    ActiveLeaseStatus.NONE,
                    EpochRelation.NOT_APPLICABLE,
                    0,
                    senderAck,
                    Math.max(senderAck, 0),
                    LeaseRelation.NONE);
        }

        public static HandshakeInput unknownInstallation(long senderAck) {
            return unknownInstallation(StreamStatus.ACTIVE, senderAck);
        }

        public static HandshakeInput unknownInstallation(
                StreamStatus senderStatus,
                long senderAck
        ) {
            return unknownInstallation(senderStatus, ActiveLeaseStatus.NONE, senderAck);
        }

        public static HandshakeInput unknownInstallation(
                StreamStatus senderStatus,
                ActiveLeaseStatus senderActiveLeaseStatus,
                long senderAck
        ) {
            return new HandshakeInput(
                    IrisKnowledge.UNKNOWN_INSTALLATION,
                    senderStatus,
                    null,
                    senderActiveLeaseStatus,
                    ActiveLeaseStatus.NONE,
                    EpochRelation.NOT_APPLICABLE,
                    0,
                    senderAck,
                    Math.max(senderAck, 0),
                    LeaseRelation.NONE);
        }

        public static HandshakeInput knownStream(
                StreamStatus senderStatus,
                StreamStatus irisStatus,
                EpochRelation epochRelation,
                long irisCursor,
                long senderAck,
                long senderHighestIssued,
                LeaseRelation leaseRelation
        ) {
            return knownStream(
                    senderStatus,
                    irisStatus,
                    leaseRelation == LeaseRelation.NONE
                            ? ActiveLeaseStatus.NONE
                            : ActiveLeaseStatus.ACTIVE,
                    ActiveLeaseStatus.NONE,
                    epochRelation,
                    irisCursor,
                    senderAck,
                    senderHighestIssued,
                    leaseRelation);
        }

        public static HandshakeInput knownStream(
                StreamStatus senderStatus,
                StreamStatus irisStatus,
                ActiveLeaseStatus senderActiveLeaseStatus,
                ActiveLeaseStatus irisActiveLeaseStatus,
                EpochRelation epochRelation,
                long irisCursor,
                long senderAck,
                long senderHighestIssued,
                LeaseRelation leaseRelation
        ) {
            if (epochRelation == EpochRelation.NOT_APPLICABLE) {
                throw new IllegalArgumentException("invalid handshake state");
            }
            return new HandshakeInput(
                    IrisKnowledge.KNOWN_STREAM,
                    senderStatus,
                    Objects.requireNonNull(irisStatus),
                    senderActiveLeaseStatus,
                    irisActiveLeaseStatus,
                    epochRelation,
                    irisCursor,
                    senderAck,
                    senderHighestIssued,
                    leaseRelation);
        }
    }

    public static final class HandshakeDecision {
        private final HandshakeClassification classification;
        private final boolean irisDurablePoisonWriteRequired;
        private final boolean retryable;
        private final MutationPermissions mutations;

        private HandshakeDecision(
                HandshakeClassification classification,
                boolean irisDurablePoisonWriteRequired,
                boolean retryable,
                MutationPermissions mutations
        ) {
            this.classification = classification;
            this.irisDurablePoisonWriteRequired = irisDurablePoisonWriteRequired;
            this.retryable = retryable;
            this.mutations = mutations;
        }

        public HandshakeClassification classification() {
            return classification;
        }

        public boolean requiresIrisDurablePoisonWrite() {
            return irisDurablePoisonWriteRequired;
        }

        public boolean retryable() {
            return retryable;
        }

        public MutationPermissions mutations() {
            return mutations;
        }
    }

    public static final class MutationPermissions {
        private final boolean createToken;
        private final boolean createDatabase;
        private final boolean createEpoch;
        private final boolean writeLocalDurablePoison;

        private MutationPermissions(
                boolean createToken,
                boolean createDatabase,
                boolean createEpoch,
                boolean writeLocalDurablePoison
        ) {
            this.createToken = createToken;
            this.createDatabase = createDatabase;
            this.createEpoch = createEpoch;
            this.writeLocalDurablePoison = writeLocalDurablePoison;
        }

        public boolean mayCreateToken() {
            return createToken;
        }

        public boolean mayCreateDatabase() {
            return createDatabase;
        }

        public boolean mayCreateEpoch() {
            return createEpoch;
        }

        public boolean mayWriteLocalDurablePoison() {
            return writeLocalDurablePoison;
        }

        public boolean mayReplaceToken() {
            return false;
        }

        public boolean mayReplaceDatabase() {
            return false;
        }

        public boolean mayReplaceEpoch() {
            return false;
        }

        public boolean mayChangeCursor() {
            return false;
        }

        public boolean mayChangeSchema() {
            return false;
        }
    }
}
