package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class ReadReceiptBootstrapTest {

    @Test
    public void faultsAtEveryOrderedBoundarySeparateVisibilityFromActivationReadiness() {
        for (Boundary boundary : Boundary.values()) {
            FakeOps ops = new FakeOps();
            if (boundary == Boundary.CLEANUP) ops.competingPublisher = true;
            ops.failAt = boundary;

            ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

            assertEquals(boundary.name(), boundary.expectedOutcome, result.outcome());
            assertEquals(boundary.name(), boundary.expectedCategory, result.category());
            assertEquals(boundary.name(), boundary.activationReady, result.activationReady());
            assertEquals(boundary.name(), boundary.liveVisible, ops.liveVisible);

            if (!boundary.liveVisible) {
                ops.failAt = null;
                ReadReceiptBootstrap.Result retry = ReadReceiptBootstrap.run(ops);
                assertTrue(boundary.name(), retry.activationReady());
                assertTrue(boundary.name(), ops.liveDurable);
            }
        }
    }

    @Test
    public void ownPublishRequiresLiveDirectoryDurabilityBeforeSourceSyncOrActivation() {
        FakeOps ops = new FakeOps();
        ops.failAt = Boundary.LIVE_DIR_FSYNC;

        ReadReceiptBootstrap.Result first = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, first.outcome());
        assertEquals(ReadReceiptBootstrap.Category.LIVE_DIR_FSYNC_FAILED, first.category());
        assertFalse(first.activationReady());
        FakeCaller caller = new FakeCaller();
        caller.startIfReady(first);
        assertFalse(caller.started);
        assertTrue(ops.liveVisible);
        assertFalse(ops.liveDurable);
        assertFalse(ops.hasAnyTemp());
        assertFalse(ops.calls.contains("cleanup-owned-temp"));
        assertFalse(ops.calls.contains("fsync-no-backup-dir"));

        ops.failAt = null;
        ops.calls.clear();
        ReadReceiptBootstrap.Result reopened = ReadReceiptBootstrap.run(ops);

        assertTrue(reopened.activationReady());
        caller.startIfReady(reopened);
        assertTrue(caller.started);
        assertTrue(ops.liveDurable);
        assertFalse(ops.hasAnyTemp());
        assertEquals(Arrays.asList(
                "validate-live", "fsync-live-dir", "cleanup-owned-stale-temps",
                "fsync-no-backup-dir"
        ), ops.calls);
    }

    @Test
    public void rebootDiscardsAnUnsyncedPublishedDirectoryEntry() {
        FakeOps ops = new FakeOps();
        ops.failAt = Boundary.LIVE_DIR_FSYNC;

        ReadReceiptBootstrap.Result first = ReadReceiptBootstrap.run(ops);
        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, first.outcome());
        assertFalse(first.activationReady());
        assertTrue(ops.liveVisible);

        ops.reboot();
        assertFalse(ops.liveVisible);
        ops.failAt = null;
        ReadReceiptBootstrap.Result retry = ReadReceiptBootstrap.run(ops);

        assertTrue(retry.activationReady());
        assertTrue(ops.liveDurable);
    }

    @Test
    public void competingLoserCleanupFailureDoesNotBlockDurableWinnerActivation() {
        FakeOps ops = new FakeOps();
        ops.competingPublisher = true;
        ops.failAt = Boundary.CLEANUP;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.TEMP_CLEANUP_FAILED, result.category());
        assertTrue(result.activationReady());
        assertTrue(ops.liveDurable);
        assertTrue(ops.hasAnyTemp());
    }

    @Test
    public void successfulBootstrapUsesTheExactBoundaryOrder() {
        FakeOps ops = new FakeOps();

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.PUBLISHED, result.category());
        assertTrue(result.activationReady());
        assertEquals(Arrays.asList(
                "validate-live", "create-temp", "verify-same-device", "create-schema",
                "close", "fsync-temp", "validate-temp", "publish-no-replace",
                "fsync-live-dir", "fsync-no-backup-dir"
        ), ops.calls);
        assertTrue(ops.liveDurable);
        assertFalse(ops.hasAnyTemp());
    }

    @Test
    public void exactExistingLiveIsResyncedBeforeVerifiedRecoveryCleanup() {
        FakeOps ops = new FakeOps();
        ops.installExistingLive(true, true, 41);
        ops.addTempLink(41);

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.EXISTING_LIVE, result.category());
        assertTrue(result.activationReady());
        assertEquals(41, ops.liveGeneration);
        assertEquals(Arrays.asList(
                "validate-live", "fsync-live-dir", "cleanup-owned-stale-temps",
                "fsync-no-backup-dir"
        ), ops.calls);
        assertTrue(ops.recoveryCleanupVerifiedOwnedTemps);
        assertFalse(ops.hasAnyTemp());
    }

    @Test
    public void exactExistingLiveFsyncFailureIsNonreadyAndPreservesTemp() {
        FakeOps ops = new FakeOps();
        ops.installExistingLive(true, false, 47);
        ops.addTempLink(47);
        ops.failAt = Boundary.LIVE_DIR_FSYNC;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertFalse(result.activationReady());
        FakeCaller caller = new FakeCaller();
        caller.startIfReady(result);
        assertFalse(caller.started);
        assertTrue(ops.hasAnyTemp());
        assertEquals(Arrays.asList("validate-live", "fsync-live-dir"), ops.calls);
    }

    @Test
    public void unsupportedExistingLiveIsNeverCreatedPublishedOrMutated() {
        FakeOps ops = new FakeOps();
        ops.installExistingLive(false, true, 73);

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.SCHEMA_UNAVAILABLE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.LIVE_UNSUPPORTED, result.category());
        assertFalse(result.activationReady());
        assertEquals(73, ops.liveGeneration);
        assertEquals(Arrays.asList("validate-live"), ops.calls);
    }

    @Test
    public void checkedInitialLiveValidationFailurePreservesLiveAndNeverActivates() {
        FakeOps ops = new FakeOps();
        ops.installExistingLive(true, true, 79);
        ops.checkedFailureAt = CheckedFailurePoint.INITIAL_LIVE_VALIDATION;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.SCHEMA_UNAVAILABLE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.LIVE_VALIDATION_FAILED, result.category());
        assertFalse(result.activationReady());
        assertTrue(ops.liveVisible);
        assertTrue(ops.liveDurable);
        assertEquals(79, ops.liveGeneration);
        assertFalse(ops.hasAnyTemp());
        assertEquals(Arrays.asList("validate-live"), ops.calls);
        assertBounded(result);
    }

    @Test
    public void checkedCompetingWinnerValidationFailureCleansOnlyLoserAndNeverActivates() {
        FakeOps ops = new FakeOps();
        ops.competingPublisher = true;
        ops.checkedFailureAt = CheckedFailurePoint.COMPETING_LIVE_VALIDATION;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.SCHEMA_UNAVAILABLE, result.outcome());
        assertEquals(
                ReadReceiptBootstrap.Category.COMPETING_LIVE_VALIDATION_FAILED,
                result.category()
        );
        assertFalse(result.activationReady());
        assertTrue(ops.liveVisible);
        assertFalse(ops.liveDurable);
        assertEquals(99, ops.liveGeneration);
        assertFalse(ops.hasAnyTemp());
        assertEquals(Arrays.asList(
                "validate-live", "create-temp", "verify-same-device", "create-schema",
                "close", "fsync-temp", "validate-temp", "publish-no-replace",
                "validate-live", "cleanup-owned-temp", "fsync-no-backup-dir"
        ), ops.calls);
        assertBounded(result);
    }

    @Test
    public void checkedRecoveryCleanupFailureKeepsDurableLiveReadyAndTempUntouched() {
        FakeOps ops = new FakeOps();
        ops.installExistingLive(true, false, 83);
        ops.addTempLink(83);
        ops.checkedFailureAt = CheckedFailurePoint.RECOVERY_CLEANUP;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertEquals(
                ReadReceiptBootstrap.Category.TEMP_RECOVERY_CLEANUP_FAILED,
                result.category()
        );
        assertTrue(result.activationReady());
        assertTrue(ops.liveVisible);
        assertTrue(ops.liveDurable);
        assertEquals(83, ops.liveGeneration);
        assertTrue(ops.hasAnyTemp());
        assertEquals(Arrays.asList(
                "validate-live", "fsync-live-dir", "cleanup-owned-stale-temps",
                "fsync-no-backup-dir"
        ), ops.calls);
        assertBounded(result);
    }

    @Test
    public void competingPublisherMustBeValidatedAndDirectorySyncedByLoser() {
        FakeOps ops = new FakeOps();
        ops.competingPublisher = true;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.COMPETING_PUBLISHER, result.category());
        assertTrue(result.activationReady());
        assertTrue(ops.liveDurable);
        assertEquals(99, ops.liveGeneration);
        assertEquals(1, ops.publishCalls);
        assertFalse(ops.hasAnyTemp());
        assertEquals(Arrays.asList(
                "validate-live", "create-temp", "verify-same-device", "create-schema",
                "close", "fsync-temp", "validate-temp", "publish-no-replace",
                "validate-live", "fsync-live-dir", "cleanup-owned-temp",
                "fsync-no-backup-dir"
        ), ops.calls);
    }

    @Test
    public void competingPublisherFsyncFailureIsNonreadyAndPreservesLoserTemp() {
        FakeOps ops = new FakeOps();
        ops.competingPublisher = true;
        ops.failAt = Boundary.LIVE_DIR_FSYNC;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.LIVE, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.LIVE_DIR_FSYNC_FAILED, result.category());
        assertFalse(result.activationReady());
        FakeCaller caller = new FakeCaller();
        caller.startIfReady(result);
        assertFalse(caller.started);
        assertTrue(ops.hasAnyTemp());
        assertFalse(ops.liveDurable);
        assertFalse(ops.calls.contains("cleanup-owned-temp"));

        ops.failAt = null;
        ops.calls.clear();
        ReadReceiptBootstrap.Result reopened = ReadReceiptBootstrap.run(ops);

        assertTrue(reopened.activationReady());
        assertTrue(ops.liveDurable);
        assertTrue(ops.hasAnyTemp());
        assertEquals(Arrays.asList(
                "validate-live", "fsync-live-dir", "cleanup-owned-stale-temps",
                "fsync-no-backup-dir"
        ), ops.calls);
    }

    @Test
    public void invalidOrMissingCompetingPublisherNeverActivatesOrReplacesWinner() {
        FakeOps unsupported = new FakeOps();
        unsupported.competingPublisher = true;
        unsupported.competitorExact = false;

        ReadReceiptBootstrap.Result invalid = ReadReceiptBootstrap.run(unsupported);

        assertEquals(ReadReceiptBootstrap.Outcome.SCHEMA_UNAVAILABLE, invalid.outcome());
        assertEquals(ReadReceiptBootstrap.Category.COMPETING_LIVE_UNSUPPORTED, invalid.category());
        assertFalse(invalid.activationReady());
        assertEquals(99, unsupported.liveGeneration);
        assertFalse(unsupported.hasAnyTemp());

        FakeOps absent = new FakeOps();
        absent.competingPublisher = true;
        absent.competitorDisappearsBeforeValidation = true;

        ReadReceiptBootstrap.Result missing = ReadReceiptBootstrap.run(absent);

        assertEquals(ReadReceiptBootstrap.Outcome.ABSENT, missing.outcome());
        assertEquals(ReadReceiptBootstrap.Category.COMPETING_LIVE_MISSING, missing.category());
        assertFalse(missing.activationReady());
        assertFalse(absent.hasAnyTemp());
    }

    @Test
    public void crossDeviceAttemptFailsBeforeSchemaAndCleansOnlyOwnedTemp() {
        FakeOps ops = new FakeOps();
        ops.sameDevice = false;

        ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);

        assertEquals(ReadReceiptBootstrap.Outcome.ABSENT, result.outcome());
        assertEquals(ReadReceiptBootstrap.Category.CROSS_DEVICE, result.category());
        assertFalse(result.activationReady());
        assertEquals(Arrays.asList(
                "validate-live", "create-temp", "verify-same-device",
                "cleanup-owned-temp", "fsync-no-backup-dir"
        ), ops.calls);
        assertFalse(ops.liveVisible);
        assertFalse(ops.hasAnyTemp());
    }

    @Test
    public void processDeathAtEveryFreshBoundaryRecoversWithoutActivatingUnsyncedLive() {
        for (DeathPoint deathPoint : Arrays.asList(
                DeathPoint.INITIAL_LIVE_VALIDATION,
                DeathPoint.TEMP_CREATE,
                DeathPoint.SAME_DEVICE_VERIFY,
                DeathPoint.SCHEMA_TRANSACTION,
                DeathPoint.CLOSE,
                DeathPoint.TEMP_FSYNC,
                DeathPoint.TEMP_VALIDATION,
                DeathPoint.PUBLISH_BEFORE_RENAME,
                DeathPoint.PUBLISH_AFTER_RENAME,
                DeathPoint.LIVE_DIR_FSYNC,
                DeathPoint.NO_BACKUP_DIR_FSYNC
        )) {
            FakeOps ops = new FakeOps();
            ops.dieAt = deathPoint;

            expectProcessDeath(ops);
            if (deathPoint == DeathPoint.PUBLISH_AFTER_RENAME) {
                assertTrue(deathPoint.name(), ops.liveVisible);
                assertFalse(deathPoint.name(), ops.liveDurable);
            }

            ops.reboot();
            ops.dieAt = null;
            ReadReceiptBootstrap.Result recovered = ReadReceiptBootstrap.run(ops);

            assertTrue(deathPoint.name(), recovered.activationReady());
            assertTrue(deathPoint.name(), ops.liveDurable);
        }
    }

    @Test
    public void processDeathAcrossExactExistingRecoveryRetainsDurableLive() {
        for (DeathPoint deathPoint : Arrays.asList(
                DeathPoint.INITIAL_LIVE_VALIDATION,
                DeathPoint.LIVE_DIR_FSYNC,
                DeathPoint.RECOVERY_CLEANUP,
                DeathPoint.NO_BACKUP_DIR_FSYNC
        )) {
            FakeOps ops = new FakeOps();
            ops.installExistingLive(true, true, 67);
            ops.addTempLink(67);
            ops.dieAt = deathPoint;

            expectProcessDeath(ops);
            ops.reboot();
            assertTrue(deathPoint.name(), ops.liveVisible);
            ops.dieAt = null;

            ReadReceiptBootstrap.Result recovered = ReadReceiptBootstrap.run(ops);
            assertTrue(deathPoint.name(), recovered.activationReady());
            assertEquals(deathPoint.name(), 67, ops.liveGeneration);
        }
    }

    @Test
    public void processDeathAcrossCompetingWinnerPathsNeverActivatesBeforeRetry() {
        for (DeathPoint deathPoint : Arrays.asList(
                DeathPoint.COMPETING_LIVE_VALIDATION,
                DeathPoint.LIVE_DIR_FSYNC,
                DeathPoint.OWNED_CLEANUP,
                DeathPoint.NO_BACKUP_DIR_FSYNC
        )) {
            FakeOps ops = new FakeOps();
            ops.competingPublisher = true;
            ops.dieAt = deathPoint;

            expectProcessDeath(ops);
            ops.reboot();
            ops.dieAt = null;
            ReadReceiptBootstrap.Result recovered = ReadReceiptBootstrap.run(ops);

            assertTrue(deathPoint.name(), recovered.activationReady());
            assertTrue(deathPoint.name(), ops.liveDurable);
        }

        FakeOps absent = new FakeOps();
        absent.competingPublisher = true;
        absent.competitorDisappearsBeforeValidation = true;
        absent.dieAt = DeathPoint.COMPETING_LIVE_VALIDATION;
        expectProcessDeath(absent);
        absent.reboot();
        absent.dieAt = null;
        absent.competingPublisher = false;
        assertTrue(ReadReceiptBootstrap.run(absent).activationReady());
    }

    @Test
    public void failuresExposeOnlyBoundedClassificationsAndReadiness() {
        for (Boundary boundary : Boundary.values()) {
            FakeOps ops = new FakeOps();
            if (boundary == Boundary.CLEANUP) ops.competingPublisher = true;
            ops.failAt = boundary;
            ReadReceiptBootstrap.Result result = ReadReceiptBootstrap.run(ops);
            assertTrue(EnumSet.allOf(ReadReceiptBootstrap.Category.class).contains(result.category()));
            assertFalse(result.toString().contains("/"));
            assertFalse(result.toString().contains("token"));
            assertFalse(result.toString().contains("inode-"));
            assertEquals(result.activationReady(), result.toString().endsWith(":READY"));
        }
    }

    private static void expectProcessDeath(FakeOps ops) {
        try {
            ReadReceiptBootstrap.run(ops);
            fail("expected simulated process death at " + ops.dieAt.name());
        } catch (SimulatedProcessDeath expected) {
        }
    }

    private static void assertBounded(ReadReceiptBootstrap.Result result) {
        assertTrue(EnumSet.allOf(ReadReceiptBootstrap.Category.class).contains(result.category()));
        assertFalse(result.toString().contains("/"));
        assertFalse(result.toString().contains("token"));
        assertFalse(result.toString().contains("inode-"));
    }

    private enum Boundary {
        TEMP_CREATE(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.TEMP_CREATE_FAILED,
                false,
                false
        ),
        SAME_DEVICE_VERIFY(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.SAME_DEVICE_VERIFY_FAILED,
                false,
                false
        ),
        SCHEMA_TRANSACTION(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.SCHEMA_TRANSACTION_FAILED,
                false,
                false
        ),
        CLOSE(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.CLOSE_FAILED,
                false,
                false
        ),
        TEMP_FSYNC(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.TEMP_FSYNC_FAILED,
                false,
                false
        ),
        TEMP_VALIDATION(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.TEMP_VALIDATION_FAILED,
                false,
                false
        ),
        PUBLISH(
                ReadReceiptBootstrap.Outcome.ABSENT,
                ReadReceiptBootstrap.Category.PUBLISH_FAILED,
                false,
                false
        ),
        LIVE_DIR_FSYNC(
                ReadReceiptBootstrap.Outcome.LIVE,
                ReadReceiptBootstrap.Category.LIVE_DIR_FSYNC_FAILED,
                false,
                true
        ),
        CLEANUP(
                ReadReceiptBootstrap.Outcome.LIVE,
                ReadReceiptBootstrap.Category.TEMP_CLEANUP_FAILED,
                true,
                true
        ),
        NO_BACKUP_DIR_FSYNC(
                ReadReceiptBootstrap.Outcome.LIVE,
                ReadReceiptBootstrap.Category.NO_BACKUP_DIR_FSYNC_FAILED,
                true,
                true
        );

        private final ReadReceiptBootstrap.Outcome expectedOutcome;
        private final ReadReceiptBootstrap.Category expectedCategory;
        private final boolean activationReady;
        private final boolean liveVisible;

        Boundary(
                ReadReceiptBootstrap.Outcome expectedOutcome,
                ReadReceiptBootstrap.Category expectedCategory,
                boolean activationReady,
                boolean liveVisible
        ) {
            this.expectedOutcome = expectedOutcome;
            this.expectedCategory = expectedCategory;
            this.activationReady = activationReady;
            this.liveVisible = liveVisible;
        }
    }

    private enum DeathPoint {
        INITIAL_LIVE_VALIDATION,
        TEMP_CREATE,
        SAME_DEVICE_VERIFY,
        SCHEMA_TRANSACTION,
        CLOSE,
        TEMP_FSYNC,
        TEMP_VALIDATION,
        PUBLISH_BEFORE_RENAME,
        PUBLISH_AFTER_RENAME,
        COMPETING_LIVE_VALIDATION,
        LIVE_DIR_FSYNC,
        OWNED_CLEANUP,
        RECOVERY_CLEANUP,
        NO_BACKUP_DIR_FSYNC
    }

    private enum CheckedFailurePoint {
        INITIAL_LIVE_VALIDATION,
        COMPETING_LIVE_VALIDATION,
        RECOVERY_CLEANUP
    }

    private static final class FakeOps implements ReadReceiptBootstrap.Ops {
        private final List<String> calls = new ArrayList<>();
        private Boundary failAt;
        private DeathPoint dieAt;
        private CheckedFailurePoint checkedFailureAt;
        private boolean liveVisible;
        private boolean liveDurable;
        private boolean liveExact;
        private int liveGeneration;
        private final Set<Integer> tempGenerations = new HashSet<>();
        private final Set<Integer> durableTempGenerations = new HashSet<>();
        private boolean tempExact;
        private boolean tempClosed;
        private boolean tempSynced;
        private boolean sameDevice = true;
        private boolean competingPublisher;
        private boolean competitorExact = true;
        private boolean competitorDisappearsBeforeValidation;
        private int publishCalls;
        private int liveValidationCalls;
        private boolean recoveryCleanupVerifiedOwnedTemps;
        private int nextAttempt;

        @Override
        public ReadReceiptBootstrap.LiveValidation validateLiveReadOnlyExact()
                throws ReadReceiptBootstrap.Failure {
            calls.add("validate-live");
            liveValidationCalls++;
            CheckedFailurePoint failurePoint = liveValidationCalls == 1
                    ? CheckedFailurePoint.INITIAL_LIVE_VALIDATION
                    : CheckedFailurePoint.COMPETING_LIVE_VALIDATION;
            if (checkedFailureAt == failurePoint) throw new ReadReceiptBootstrap.Failure();
            ReadReceiptBootstrap.LiveValidation result;
            if (!liveVisible) {
                result = ReadReceiptBootstrap.LiveValidation.ABSENT;
            } else {
                result = liveExact
                        ? ReadReceiptBootstrap.LiveValidation.EXACT
                        : ReadReceiptBootstrap.LiveValidation.UNSUPPORTED;
            }
            dieAfter(liveValidationCalls == 1
                    ? DeathPoint.INITIAL_LIVE_VALIDATION
                    : DeathPoint.COMPETING_LIVE_VALIDATION);
            return result;
        }

        @Override
        public ReadReceiptBootstrap.Attempt createTempExclusiveNoFollow0600()
                throws ReadReceiptBootstrap.Failure {
            enter(Boundary.TEMP_CREATE, "create-temp");
            tempExact = false;
            tempClosed = false;
            tempSynced = false;
            FakeAttempt attempt = new FakeAttempt(++nextAttempt);
            tempGenerations.add(attempt.generation);
            durableTempGenerations.add(attempt.generation);
            dieAfter(DeathPoint.TEMP_CREATE);
            return attempt;
        }

        @Override
        public boolean verifyTempAndLiveDirectorySameDevice(ReadReceiptBootstrap.Attempt attempt)
                throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            enter(Boundary.SAME_DEVICE_VERIFY, "verify-same-device");
            dieAfter(DeathPoint.SAME_DEVICE_VERIFY);
            return sameDevice;
        }

        @Override
        public void createRollbackJournalSchemaTransaction(ReadReceiptBootstrap.Attempt attempt)
                throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            enter(Boundary.SCHEMA_TRANSACTION, "create-schema");
            tempExact = true;
            dieAfter(DeathPoint.SCHEMA_TRANSACTION);
        }

        @Override
        public void closeCreatedDatabase(ReadReceiptBootstrap.Attempt attempt)
                throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            enter(Boundary.CLOSE, "close");
            tempClosed = true;
            dieAfter(DeathPoint.CLOSE);
        }

        @Override
        public void fsyncTemp(ReadReceiptBootstrap.Attempt attempt)
                throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            assertTrue(tempClosed);
            enter(Boundary.TEMP_FSYNC, "fsync-temp");
            tempSynced = true;
            dieAfter(DeathPoint.TEMP_FSYNC);
        }

        @Override
        public boolean validateTempReadOnlyExact(ReadReceiptBootstrap.Attempt attempt)
                throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            enter(Boundary.TEMP_VALIDATION, "validate-temp");
            boolean result = tempExact && tempClosed && tempSynced;
            dieAfter(DeathPoint.TEMP_VALIDATION);
            return result;
        }

        @Override
        public ReadReceiptBootstrap.PublishResult publishVerifiedTempInodeNoReplace(
                ReadReceiptBootstrap.Attempt attempt
        ) throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            enter(Boundary.PUBLISH, "publish-no-replace");
            publishCalls++;
            dieAfter(DeathPoint.PUBLISH_BEFORE_RENAME);
            if (competingPublisher) {
                liveVisible = !competitorDisappearsBeforeValidation;
                liveDurable = false;
                liveExact = competitorExact;
                liveGeneration = 99;
                dieAfter(DeathPoint.PUBLISH_AFTER_RENAME);
                return ReadReceiptBootstrap.PublishResult.EXISTS;
            }
            liveVisible = true;
            liveDurable = false;
            liveExact = tempExact;
            liveGeneration = ((FakeAttempt) attempt).generation;
            tempGenerations.remove(liveGeneration);
            dieAfter(DeathPoint.PUBLISH_AFTER_RENAME);
            return ReadReceiptBootstrap.PublishResult.PUBLISHED;
        }

        @Override
        public void fsyncLiveDirectory() throws ReadReceiptBootstrap.Failure {
            enter(Boundary.LIVE_DIR_FSYNC, "fsync-live-dir");
            liveDurable = liveVisible;
            dieAfter(DeathPoint.LIVE_DIR_FSYNC);
        }

        @Override
        public void cleanupOwnedTempIfInodeMatchesAttempt(ReadReceiptBootstrap.Attempt attempt)
                throws ReadReceiptBootstrap.Failure {
            requireAttempt(attempt);
            enter(Boundary.CLEANUP, "cleanup-owned-temp");
            tempGenerations.remove(((FakeAttempt) attempt).generation);
            dieAfter(DeathPoint.OWNED_CLEANUP);
        }

        @Override
        public void cleanupOwnedStaleTemps() throws ReadReceiptBootstrap.Failure {
            calls.add("cleanup-owned-stale-temps");
            if (checkedFailureAt == CheckedFailurePoint.RECOVERY_CLEANUP) {
                throw new ReadReceiptBootstrap.Failure();
            }
            assertTrue(liveVisible && liveExact);
            recoveryCleanupVerifiedOwnedTemps = true;
            tempGenerations.remove(liveGeneration);
            dieAfter(DeathPoint.RECOVERY_CLEANUP);
        }

        @Override
        public void fsyncNoBackupDirectory() throws ReadReceiptBootstrap.Failure {
            enter(Boundary.NO_BACKUP_DIR_FSYNC, "fsync-no-backup-dir");
            durableTempGenerations.clear();
            durableTempGenerations.addAll(tempGenerations);
            dieAfter(DeathPoint.NO_BACKUP_DIR_FSYNC);
        }

        private void installExistingLive(boolean exact, boolean durable, int generation) {
            liveVisible = true;
            liveDurable = durable;
            liveExact = exact;
            liveGeneration = generation;
        }

        private void addTempLink(int generation) {
            tempGenerations.add(generation);
            durableTempGenerations.add(generation);
        }

        private boolean hasAnyTemp() {
            return !tempGenerations.isEmpty();
        }

        private void reboot() {
            liveVisible = liveDurable;
            tempGenerations.clear();
            tempGenerations.addAll(durableTempGenerations);
            calls.clear();
            liveValidationCalls = 0;
        }

        private void enter(Boundary boundary, String call) throws ReadReceiptBootstrap.Failure {
            calls.add(call);
            if (failAt == boundary) throw new ReadReceiptBootstrap.Failure();
        }

        private void dieAfter(DeathPoint point) {
            if (dieAt == point) throw new SimulatedProcessDeath();
        }

        private void requireAttempt(ReadReceiptBootstrap.Attempt attempt) {
            assertTrue(attempt instanceof FakeAttempt);
            assertTrue(((FakeAttempt) attempt).generation <= nextAttempt);
        }
    }

    private static final class FakeAttempt implements ReadReceiptBootstrap.Attempt {
        private final int generation;

        private FakeAttempt(int generation) {
            this.generation = generation;
        }
    }

    private static final class FakeCaller {
        private boolean started;

        private void startIfReady(ReadReceiptBootstrap.Result result) {
            if (result.activationReady()) started = true;
        }
    }

    private static final class SimulatedProcessDeath extends Error {
    }
}
