package app.revanced.extension.kakaotalk.chatlog.readreceipt;

final class ReadReceiptBootstrap {

    enum Outcome {
        LIVE,
        ABSENT,
        SCHEMA_UNAVAILABLE
    }

    enum Category {
        PUBLISHED,
        EXISTING_LIVE,
        COMPETING_PUBLISHER,
        LIVE_UNSUPPORTED,
        LIVE_VALIDATION_FAILED,
        COMPETING_LIVE_UNSUPPORTED,
        COMPETING_LIVE_MISSING,
        COMPETING_LIVE_VALIDATION_FAILED,
        TEMP_CREATE_FAILED,
        SAME_DEVICE_VERIFY_FAILED,
        CROSS_DEVICE,
        SCHEMA_TRANSACTION_FAILED,
        CLOSE_FAILED,
        TEMP_FSYNC_FAILED,
        TEMP_VALIDATION_FAILED,
        PUBLISH_FAILED,
        LIVE_DIR_FSYNC_FAILED,
        TEMP_CLEANUP_FAILED,
        TEMP_RECOVERY_CLEANUP_FAILED,
        NO_BACKUP_DIR_FSYNC_FAILED
    }

    enum LiveValidation {
        ABSENT,
        EXACT,
        UNSUPPORTED
    }

    enum PublishResult {
        PUBLISHED,
        EXISTS
    }

    interface Attempt {
    }

    interface Ops {
        LiveValidation validateLiveReadOnlyExact() throws Failure;

        Attempt createTempExclusiveNoFollow0600() throws Failure;

        boolean verifyTempAndLiveDirectorySameDevice(Attempt attempt) throws Failure;

        // 실제 no-replace rename/fsync 내구성, DDL별 crash, SQLite handle close는 플랫폼 어댑터가 검증한다.
        void createRollbackJournalSchemaTransaction(Attempt attempt) throws Failure;

        void closeCreatedDatabase(Attempt attempt) throws Failure;

        void fsyncTemp(Attempt attempt) throws Failure;

        boolean validateTempReadOnlyExact(Attempt attempt) throws Failure;

        PublishResult publishVerifiedTempInodeNoReplace(Attempt attempt) throws Failure;

        void fsyncLiveDirectory() throws Failure;

        void cleanupOwnedTempIfInodeMatchesAttempt(Attempt attempt) throws Failure;

        void cleanupOwnedStaleTemps() throws Failure;

        void fsyncNoBackupDirectory() throws Failure;
    }

    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static final class Result {
        private final Outcome outcome;
        private final Category category;
        private final boolean activationReady;

        private Result(Outcome outcome, Category category, boolean activationReady) {
            this.outcome = outcome;
            this.category = category;
            this.activationReady = activationReady;
        }

        Outcome outcome() {
            return outcome;
        }

        Category category() {
            return category;
        }

        boolean activationReady() {
            return activationReady;
        }

        @Override
        public String toString() {
            return outcome.name() + ':' + category.name()
                    + (activationReady ? ":READY" : ":NOT_READY");
        }
    }

    static Result run(Ops ops) {
        final LiveValidation initial;
        try {
            initial = ops.validateLiveReadOnlyExact();
        } catch (Failure ignored) {
            return result(
                    Outcome.SCHEMA_UNAVAILABLE,
                    Category.LIVE_VALIDATION_FAILED,
                    false
            );
        }

        if (initial == LiveValidation.EXACT) {
            return recoverPublishedLive(ops);
        }
        if (initial != LiveValidation.ABSENT) {
            return result(Outcome.SCHEMA_UNAVAILABLE, Category.LIVE_UNSUPPORTED, false);
        }

        final Attempt attempt;
        try {
            attempt = ops.createTempExclusiveNoFollow0600();
        } catch (Failure ignored) {
            return result(Outcome.ABSENT, Category.TEMP_CREATE_FAILED, false);
        }
        if (attempt == null) {
            return result(Outcome.ABSENT, Category.TEMP_CREATE_FAILED, false);
        }

        try {
            if (!ops.verifyTempAndLiveDirectorySameDevice(attempt)) {
                return failBeforePublish(ops, attempt, Category.CROSS_DEVICE, Outcome.ABSENT);
            }
        } catch (Failure ignored) {
            return failBeforePublish(
                    ops, attempt, Category.SAME_DEVICE_VERIFY_FAILED, Outcome.ABSENT);
        }

        try {
            ops.createRollbackJournalSchemaTransaction(attempt);
        } catch (Failure ignored) {
            return failBeforePublish(
                    ops, attempt, Category.SCHEMA_TRANSACTION_FAILED, Outcome.ABSENT);
        }

        try {
            ops.closeCreatedDatabase(attempt);
        } catch (Failure ignored) {
            return failBeforePublish(ops, attempt, Category.CLOSE_FAILED, Outcome.ABSENT);
        }

        try {
            ops.fsyncTemp(attempt);
        } catch (Failure ignored) {
            return failBeforePublish(ops, attempt, Category.TEMP_FSYNC_FAILED, Outcome.ABSENT);
        }

        try {
            if (!ops.validateTempReadOnlyExact(attempt)) {
                return failBeforePublish(
                        ops, attempt, Category.TEMP_VALIDATION_FAILED, Outcome.ABSENT);
            }
        } catch (Failure ignored) {
            return failBeforePublish(
                    ops, attempt, Category.TEMP_VALIDATION_FAILED, Outcome.ABSENT);
        }

        final PublishResult publishResult;
        try {
            publishResult = ops.publishVerifiedTempInodeNoReplace(attempt);
        } catch (Failure ignored) {
            return failBeforePublish(ops, attempt, Category.PUBLISH_FAILED, Outcome.ABSENT);
        }
        if (publishResult == PublishResult.PUBLISHED) {
            return finishPublished(ops);
        }
        if (publishResult == PublishResult.EXISTS) {
            return acceptCompetingPublisher(ops, attempt);
        }
        return failBeforePublish(ops, attempt, Category.PUBLISH_FAILED, Outcome.ABSENT);
    }

    private static Result recoverPublishedLive(Ops ops) {
        Category category = Category.EXISTING_LIVE;
        try {
            ops.fsyncLiveDirectory();
        } catch (Failure ignored) {
            return result(Outcome.LIVE, Category.LIVE_DIR_FSYNC_FAILED, false);
        }
        try {
            ops.cleanupOwnedStaleTemps();
        } catch (Failure ignored) {
            category = Category.TEMP_RECOVERY_CLEANUP_FAILED;
        }
        try {
            ops.fsyncNoBackupDirectory();
        } catch (Failure ignored) {
            if (category == Category.EXISTING_LIVE) {
                category = Category.NO_BACKUP_DIR_FSYNC_FAILED;
            }
        }
        return result(Outcome.LIVE, category, true);
    }

    private static Result finishPublished(Ops ops) {
        Category category = Category.PUBLISHED;
        try {
            ops.fsyncLiveDirectory();
        } catch (Failure ignored) {
            return result(Outcome.LIVE, Category.LIVE_DIR_FSYNC_FAILED, false);
        }
        try {
            ops.fsyncNoBackupDirectory();
        } catch (Failure ignored) {
            if (category == Category.PUBLISHED) {
                category = Category.NO_BACKUP_DIR_FSYNC_FAILED;
            }
        }
        return result(Outcome.LIVE, category, true);
    }

    private static Result acceptCompetingPublisher(Ops ops, Attempt attempt) {
        final LiveValidation winner;
        try {
            winner = ops.validateLiveReadOnlyExact();
        } catch (Failure ignored) {
            return failBeforePublish(
                    ops,
                    attempt,
                    Category.COMPETING_LIVE_VALIDATION_FAILED,
                    Outcome.SCHEMA_UNAVAILABLE
            );
        }

        if (winner == LiveValidation.EXACT) {
            Category category = Category.COMPETING_PUBLISHER;
            try {
                ops.fsyncLiveDirectory();
            } catch (Failure ignored) {
                return result(Outcome.LIVE, Category.LIVE_DIR_FSYNC_FAILED, false);
            }
            try {
                ops.cleanupOwnedTempIfInodeMatchesAttempt(attempt);
            } catch (Failure ignored) {
                category = Category.TEMP_CLEANUP_FAILED;
            }
            try {
                ops.fsyncNoBackupDirectory();
            } catch (Failure ignored) {
                if (category == Category.COMPETING_PUBLISHER) {
                    category = Category.NO_BACKUP_DIR_FSYNC_FAILED;
                }
            }
            return result(Outcome.LIVE, category, true);
        }

        Category category = winner == LiveValidation.ABSENT
                ? Category.COMPETING_LIVE_MISSING
                : Category.COMPETING_LIVE_UNSUPPORTED;
        Outcome outcome = winner == LiveValidation.ABSENT
                ? Outcome.ABSENT
                : Outcome.SCHEMA_UNAVAILABLE;
        return failBeforePublish(ops, attempt, category, outcome);
    }

    private static Result failBeforePublish(
            Ops ops,
            Attempt attempt,
            Category category,
            Outcome outcome
    ) {
        try {
            ops.cleanupOwnedTempIfInodeMatchesAttempt(attempt);
        } catch (Failure ignored) {
        }
        try {
            ops.fsyncNoBackupDirectory();
        } catch (Failure ignored) {
        }
        return result(outcome, category, false);
    }

    private static Result result(Outcome outcome, Category category, boolean activationReady) {
        if (activationReady && outcome != Outcome.LIVE) throw new AssertionError();
        return new Result(outcome, category, activationReady);
    }

    private ReadReceiptBootstrap() {
    }
}
