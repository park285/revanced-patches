package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class ReadReceiptRestorePolicyTest {

    @Test
    public void rst001ClassifiesEveryLocalRestoreRow() {
        LocalCase[] cases = {
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.TOKEN_ABSENT_DATABASE_ABSENT,
                        ReadReceiptRestorePolicy.LocalAction.CREATE_TOKEN_DATABASE_AND_RANDOM_EPOCH,
                        ReadReceiptRestorePolicy.HandshakeConsequence.NORMAL_NEW_INSTALLATION_MATRIX),
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.VALID_TOKEN_DATABASE_ABSENT,
                        ReadReceiptRestorePolicy.LocalAction.CREATE_DATABASE_AND_RANDOM_EPOCH,
                        ReadReceiptRestorePolicy.HandshakeConsequence.KNOWN_INSTALLATION_NEW_EPOCH),
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.DATABASE_PRESENT_TOKEN_ABSENT,
                        ReadReceiptRestorePolicy.LocalAction.FAIL_CLOSED,
                        ReadReceiptRestorePolicy.HandshakeConsequence.OPERATOR_REVIEW),
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.EXACT_DATABASE_INSTALLATION_MATCH,
                        ReadReceiptRestorePolicy.LocalAction.RUN_STARTUP_VALIDATION,
                        ReadReceiptRestorePolicy.HandshakeConsequence.APPLY_HANDSHAKE_MATRIX),
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.EXACT_DATABASE_INSTALLATION_MISMATCH,
                        ReadReceiptRestorePolicy.LocalAction.FAIL_CLOSED,
                        ReadReceiptRestorePolicy.HandshakeConsequence.OPERATOR_REVIEW),
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.UNKNOWN_SCHEMA_WITH_ANY_TOKEN_STATE,
                        ReadReceiptRestorePolicy.LocalAction.SCHEMA_UNAVAILABLE,
                        ReadReceiptRestorePolicy.HandshakeConsequence.NO_HANDSHAKE_OR_SOCKET_BIND),
                new LocalCase(
                        ReadReceiptRestorePolicy.LocalState.EXACT_DATABASE_INVARIANT_FAILURE,
                        ReadReceiptRestorePolicy.LocalAction.DURABLE_LOCAL_POISON,
                        ReadReceiptRestorePolicy.HandshakeConsequence.REPEATED_STARTUP_POISONED),
        };

        for (LocalCase testCase : cases) {
            ReadReceiptRestorePolicy.LocalDecision actual =
                    ReadReceiptRestorePolicy.classifyLocal(testCase.state);

            assertEquals(testCase.action, actual.action());
            assertEquals(testCase.consequence, actual.handshakeConsequence());
            assertMutationPermissions(actual.mutations(), testCase.action);
        }
    }

    @Test
    public void rst001ClassifiesEveryHandshakeRow() {
        HandshakeCase[] cases = {
                new HandshakeCase(
                        ReadReceiptRestorePolicy.HandshakeInput.noStream(0),
                        ReadReceiptRestorePolicy.HandshakeClassification.ACCEPT_NEW_STREAM,
                        false),
                new HandshakeCase(
                        ReadReceiptRestorePolicy.HandshakeInput.noStream(1),
                        ReadReceiptRestorePolicy.HandshakeClassification.STREAM_ROLLBACK,
                        false),
                new HandshakeCase(
                        known(4, 4, 7, ReadReceiptRestorePolicy.LeaseRelation.NONE),
                        ReadReceiptRestorePolicy.HandshakeClassification.ACCEPT,
                        false),
                new HandshakeCase(
                        known(5, 4, 7, ReadReceiptRestorePolicy.LeaseRelation.ENDS_AT_IRIS_CURSOR),
                        ReadReceiptRestorePolicy.HandshakeClassification.ACCEPT_EXACT_REPLAY_ONLY,
                        false),
                new HandshakeCase(
                        known(5, 4, 7, ReadReceiptRestorePolicy.LeaseRelation.NONE),
                        ReadReceiptRestorePolicy.HandshakeClassification.STREAM_ROLLBACK,
                        true),
                new HandshakeCase(
                        known(3, 4, 7, ReadReceiptRestorePolicy.LeaseRelation.NONE),
                        ReadReceiptRestorePolicy.HandshakeClassification.STREAM_ROLLBACK,
                        true),
                new HandshakeCase(
                        known(8, 4, 7, ReadReceiptRestorePolicy.LeaseRelation.NONE),
                        ReadReceiptRestorePolicy.HandshakeClassification.STREAM_ROLLBACK,
                        true),
                new HandshakeCase(
                        known(5, 4, 7, ReadReceiptRestorePolicy.LeaseRelation.CONTAINS_IRIS_CURSOR),
                        ReadReceiptRestorePolicy.HandshakeClassification.STREAM_ROLLBACK,
                        true),
                new HandshakeCase(
                        ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                                ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                                ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                                ReadReceiptRestorePolicy.EpochRelation.DIFFERENT,
                                0,
                                0,
                                0,
                                ReadReceiptRestorePolicy.LeaseRelation.NONE),
                        ReadReceiptRestorePolicy.HandshakeClassification.UNKNOWN_EPOCH,
                        true),
                new HandshakeCase(
                        ReadReceiptRestorePolicy.HandshakeInput.unknownInstallation(0),
                        ReadReceiptRestorePolicy.HandshakeClassification.ACCEPT_NEW_INSTALLATION,
                        false),
        };

        for (HandshakeCase testCase : cases) {
            ReadReceiptRestorePolicy.HandshakeDecision actual =
                    ReadReceiptRestorePolicy.classifyHandshake(testCase.input);

            assertEquals(testCase.classification, actual.classification());
            assertEquals(testCase.irisDurablePoisonWriteRequired,
                    actual.requiresIrisDurablePoisonWrite());
            assertFalse(actual.retryable());
            assertNoHandshakeLocalMutation(actual.mutations());
        }
    }

    @Test
    public void poisonPrecedenceIsAppliedBeforeEveryHandshakeMatrixBranch() {
        ReadReceiptRestorePolicy.HandshakeInput[] inputs = {
                ReadReceiptRestorePolicy.HandshakeInput.noStream(
                        ReadReceiptRestorePolicy.StreamStatus.POISONED, 0),
                ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.StreamStatus.POISONED,
                        ReadReceiptRestorePolicy.EpochRelation.SAME,
                        4,
                        4,
                        7,
                        ReadReceiptRestorePolicy.LeaseRelation.NONE),
                ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                        ReadReceiptRestorePolicy.StreamStatus.POISONED,
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.EpochRelation.DIFFERENT,
                        0,
                        0,
                        0,
                        ReadReceiptRestorePolicy.LeaseRelation.NONE),
                ReadReceiptRestorePolicy.HandshakeInput.unknownInstallation(
                        ReadReceiptRestorePolicy.StreamStatus.POISONED, 0),
        };

        for (ReadReceiptRestorePolicy.HandshakeInput input : inputs) {
            ReadReceiptRestorePolicy.HandshakeDecision actual =
                    ReadReceiptRestorePolicy.classifyHandshake(input);

            assertEquals(ReadReceiptRestorePolicy.HandshakeClassification.STREAM_POISONED,
                    actual.classification());
            assertFalse(actual.requiresIrisDurablePoisonWrite());
            assertFalse(actual.retryable());
            assertNoHandshakeLocalMutation(actual.mutations());
        }
    }

    @Test
    public void poisonedActiveLeaseHasPrecedenceBeforeEveryHandshakeMatrixBranch() {
        ReadReceiptRestorePolicy.HandshakeInput[] inputs = {
                ReadReceiptRestorePolicy.HandshakeInput.noStream(
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.POISONED,
                        0),
                ReadReceiptRestorePolicy.HandshakeInput.unknownInstallation(
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.POISONED,
                        0),
                ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.POISONED,
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                        ReadReceiptRestorePolicy.EpochRelation.SAME,
                        5,
                        4,
                        7,
                        ReadReceiptRestorePolicy.LeaseRelation.ENDS_AT_IRIS_CURSOR),
                ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.POISONED,
                        ReadReceiptRestorePolicy.EpochRelation.DIFFERENT,
                        0,
                        0,
                        0,
                        ReadReceiptRestorePolicy.LeaseRelation.NONE),
        };

        for (ReadReceiptRestorePolicy.HandshakeInput input : inputs) {
            ReadReceiptRestorePolicy.HandshakeDecision actual =
                    ReadReceiptRestorePolicy.classifyHandshake(input);

            assertEquals(ReadReceiptRestorePolicy.HandshakeClassification.STREAM_POISONED,
                    actual.classification());
            assertFalse(actual.requiresIrisDurablePoisonWrite());
            assertFalse(actual.retryable());
            assertNoHandshakeLocalMutation(actual.mutations());
        }
    }

    @Test
    public void nonPoisonedKnownStreamRejectsInconsistentSenderLeaseState() {
        LeaseConsistencyCase[] cases = {
                new LeaseConsistencyCase(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                        ReadReceiptRestorePolicy.LeaseRelation.ENDS_AT_IRIS_CURSOR),
                new LeaseConsistencyCase(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                        ReadReceiptRestorePolicy.LeaseRelation.CONTAINS_IRIS_CURSOR),
                new LeaseConsistencyCase(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                        ReadReceiptRestorePolicy.LeaseRelation.OTHER),
                new LeaseConsistencyCase(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.ACTIVE,
                        ReadReceiptRestorePolicy.LeaseRelation.NONE),
        };

        for (LeaseConsistencyCase testCase : cases) {
            assertUnsupportedHandshake(knownWithSenderLease(
                    testCase.status,
                    testCase.relation));
        }
    }

    @Test
    public void nonPoisonedKnownStreamAcceptsRepresentableSenderLeaseState() {
        ReadReceiptRestorePolicy.HandshakeInput[] inputs = {
                knownWithSenderLease(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                        ReadReceiptRestorePolicy.LeaseRelation.NONE),
                knownWithSenderLease(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.ACTIVE,
                        ReadReceiptRestorePolicy.LeaseRelation.ENDS_AT_IRIS_CURSOR),
                knownWithSenderLease(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.ACTIVE,
                        ReadReceiptRestorePolicy.LeaseRelation.CONTAINS_IRIS_CURSOR),
                knownWithSenderLease(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.ACTIVE,
                        ReadReceiptRestorePolicy.LeaseRelation.OTHER),
        };

        for (ReadReceiptRestorePolicy.HandshakeInput input : inputs) {
            ReadReceiptRestorePolicy.classifyHandshake(input);
        }
    }

    @Test
    public void poisonedSenderLeasePrecedesInconsistentLeaseRelation() {
        ReadReceiptRestorePolicy.HandshakeDecision actual =
                ReadReceiptRestorePolicy.classifyHandshake(knownWithSenderLease(
                        ReadReceiptRestorePolicy.ActiveLeaseStatus.POISONED,
                        ReadReceiptRestorePolicy.LeaseRelation.NONE));

        assertEquals(ReadReceiptRestorePolicy.HandshakeClassification.STREAM_POISONED,
                actual.classification());
        assertFalse(actual.requiresIrisDurablePoisonWrite());
        assertFalse(actual.retryable());
    }

    @Test
    public void unspecifiedHandshakeStateIsRejectedInsteadOfInventingAClassification() {
        assertUnsupportedHandshake(
                ReadReceiptRestorePolicy.HandshakeInput.unknownInstallation(1));
    }

    private static ReadReceiptRestorePolicy.HandshakeInput knownWithSenderLease(
            ReadReceiptRestorePolicy.ActiveLeaseStatus senderLeaseStatus,
            ReadReceiptRestorePolicy.LeaseRelation leaseRelation
    ) {
        return ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                senderLeaseStatus,
                ReadReceiptRestorePolicy.ActiveLeaseStatus.NONE,
                ReadReceiptRestorePolicy.EpochRelation.SAME,
                5,
                4,
                7,
                leaseRelation);
    }

    private static void assertUnsupportedHandshake(
            ReadReceiptRestorePolicy.HandshakeInput input
    ) {
        try {
            ReadReceiptRestorePolicy.classifyHandshake(input);
            fail("expected bounded rejection");
        } catch (IllegalArgumentException exception) {
            assertEquals("unsupported handshake state", exception.getMessage());
        }
    }

    private static ReadReceiptRestorePolicy.HandshakeInput known(
            long irisCursor,
            long senderAck,
            long senderHighestIssued,
            ReadReceiptRestorePolicy.LeaseRelation leaseRelation
    ) {
        return ReadReceiptRestorePolicy.HandshakeInput.knownStream(
                ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                ReadReceiptRestorePolicy.StreamStatus.ACTIVE,
                ReadReceiptRestorePolicy.EpochRelation.SAME,
                irisCursor,
                senderAck,
                senderHighestIssued,
                leaseRelation);
    }

    private static void assertMutationPermissions(
            ReadReceiptRestorePolicy.MutationPermissions actual,
            ReadReceiptRestorePolicy.LocalAction action
    ) {
        assertEquals(action == ReadReceiptRestorePolicy.LocalAction.CREATE_TOKEN_DATABASE_AND_RANDOM_EPOCH,
                actual.mayCreateToken());
        assertEquals(action == ReadReceiptRestorePolicy.LocalAction.CREATE_TOKEN_DATABASE_AND_RANDOM_EPOCH
                        || action == ReadReceiptRestorePolicy.LocalAction.CREATE_DATABASE_AND_RANDOM_EPOCH,
                actual.mayCreateDatabase());
        assertEquals(action == ReadReceiptRestorePolicy.LocalAction.CREATE_TOKEN_DATABASE_AND_RANDOM_EPOCH
                        || action == ReadReceiptRestorePolicy.LocalAction.CREATE_DATABASE_AND_RANDOM_EPOCH,
                actual.mayCreateEpoch());
        assertEquals(action == ReadReceiptRestorePolicy.LocalAction.DURABLE_LOCAL_POISON,
                actual.mayWriteLocalDurablePoison());
        assertFalse(actual.mayReplaceToken());
        assertFalse(actual.mayReplaceDatabase());
        assertFalse(actual.mayReplaceEpoch());
        assertFalse(actual.mayChangeCursor());
        assertFalse(actual.mayChangeSchema());
    }

    private static void assertNoHandshakeLocalMutation(
            ReadReceiptRestorePolicy.MutationPermissions actual
    ) {
        assertFalse(actual.mayCreateToken());
        assertFalse(actual.mayCreateDatabase());
        assertFalse(actual.mayCreateEpoch());
        assertFalse(actual.mayWriteLocalDurablePoison());
        assertFalse(actual.mayReplaceToken());
        assertFalse(actual.mayReplaceDatabase());
        assertFalse(actual.mayReplaceEpoch());
        assertFalse(actual.mayChangeCursor());
        assertFalse(actual.mayChangeSchema());
    }

    private static final class LocalCase {
        private final ReadReceiptRestorePolicy.LocalState state;
        private final ReadReceiptRestorePolicy.LocalAction action;
        private final ReadReceiptRestorePolicy.HandshakeConsequence consequence;

        private LocalCase(
                ReadReceiptRestorePolicy.LocalState state,
                ReadReceiptRestorePolicy.LocalAction action,
                ReadReceiptRestorePolicy.HandshakeConsequence consequence
        ) {
            this.state = state;
            this.action = action;
            this.consequence = consequence;
        }
    }

    private static final class HandshakeCase {
        private final ReadReceiptRestorePolicy.HandshakeInput input;
        private final ReadReceiptRestorePolicy.HandshakeClassification classification;
        private final boolean irisDurablePoisonWriteRequired;

        private HandshakeCase(
                ReadReceiptRestorePolicy.HandshakeInput input,
                ReadReceiptRestorePolicy.HandshakeClassification classification,
                boolean irisDurablePoisonWriteRequired
        ) {
            this.input = input;
            this.classification = classification;
            this.irisDurablePoisonWriteRequired = irisDurablePoisonWriteRequired;
        }
    }

    private static final class LeaseConsistencyCase {
        private final ReadReceiptRestorePolicy.ActiveLeaseStatus status;
        private final ReadReceiptRestorePolicy.LeaseRelation relation;

        private LeaseConsistencyCase(
                ReadReceiptRestorePolicy.ActiveLeaseStatus status,
                ReadReceiptRestorePolicy.LeaseRelation relation
        ) {
            this.status = status;
            this.relation = relation;
        }
    }
}
