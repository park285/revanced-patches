package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReadReceiptAndroidPendingBatchJournalTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);

    @Test
    public void allStatesRoundTripAcrossRestartAndClearOnlyTerminalExact() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput input = input(10, 2);

        journal.publishPending(input);
        assertState(journal(ops).load(), PendingBatchState.PENDING, input);
        journal.markCommittingExact(input);
        assertState(journal(ops).load(), PendingBatchState.COMMITTING,
                input);
        journal.markTerminalExact(input,
                PendingBatchState.TERMINAL_COMMITTED, null);
        PendingBatchRecord terminal = journal(ops).load();
        assertState(terminal, PendingBatchState.TERMINAL_COMMITTED,
                input);

        PendingBatchRecord wrong =
                new PendingBatchRecord(
                        PendingBatchState.TERMINAL_COMMITTED,
                        input(11, 2), null);
        journal.clearTerminalExact(wrong);
        assertNotNull(journal.load());
        journal.clearTerminalExact(terminal);
        assertNull(journal(ops).load());
    }

    @Test
    public void committedClearTransitionsDirectlyFromCommittingToEmpty() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput input = input(25, 2);
        journal.publishPending(input);
        journal.markCommittingExact(input);
        assertStorageFailure(() -> journal.clearCommittingExact(input(27, 2)));
        assertState(journal.load(), PendingBatchState.COMMITTING, input);

        journal.clearCommittingExact(input);

        assertNull(journal(ops).load());
    }

    @Test
    public void uncertainTerminalRoundTripsAbsoluteCounterTarget() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput input = input(12, 1);
        CounterTarget target = target(7);
        journal.publishPending(input);
        journal.markCommittingExact(input);

        journal.markTerminalExact(input,
                PendingBatchState.TERMINAL_UNCERTAIN, target);

        PendingBatchRecord restored = journal(ops).load();
        assertState(restored, PendingBatchState.TERMINAL_UNCERTAIN,
                input);
        assertTrue(restored.counterTarget.same(target));
    }

    @Test
    public void publishIsNoFollow0600AndFsyncsFileBeforeRenameThenDirectory() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ops.maxWrite = 7;

        journal(ops).publishPending(input(13, 2));

        assertTrue(ops.trace.contains("CREATE:0600"));
        assertTrue(ops.trace.indexOf("FSYNC") < ops.trace.indexOf("REPLACE"));
        assertTrue(ops.trace.indexOf("REPLACE") < ops.trace.indexOf("FSYNC_DIR"));
        assertEquals(0, ops.lastOpenFlags
                & ReadReceiptAndroidCounterJournalTest.FakeOps.FOLLOW_FLAG);
        assertNotNull(journal(ops).load());
    }

    @Test
    public void occupiedSlotAndWrongIdentityTransitionsFailWithoutReplacement() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput stored = input(14, 1);
        journal.publishPending(stored);
        byte[] before = live(ops).clone();

        assertStorageFailure(() -> journal.publishPending(input(15, 1)));
        assertStorageFailure(() -> journal.markCommittingExact(input(15, 1)));

        assertEquals(Arrays.toString(before), Arrays.toString(live(ops)));
        assertState(journal.load(), PendingBatchState.PENDING, stored);
    }

    @Test
    public void malformedReplacedWrongOwnerModeTypeAndTruncationFailWithBoundedError() {
        for (String fault : Arrays.asList("owner", "mode", "type", "digest")) {
            ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
            ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
            journal.publishPending(input(16, 1));
            ops.fault = fault;
            assertStorageFailure(journal::load);
        }

        ReadReceiptAndroidCounterJournalTest.FakeOps truncated = newOps();
        ReadReceiptAndroidPendingBatchJournal truncatedJournal = journal(truncated);
        truncatedJournal.publishPending(input(17, 1));
        truncated.files.put("dir/pending", new byte[10]);
        assertStorageFailure(truncatedJournal::load);

        ReadReceiptAndroidCounterJournalTest.FakeOps replaced = newOps();
        ReadReceiptAndroidPendingBatchJournal replacedJournal = journal(replaced);
        replacedJournal.publishPending(input(18, 1));
        byte[] bytes = live(replaced).clone();
        bytes[bytes.length - 1] ^= 1;
        replaced.files.put("dir/pending", bytes);
        assertStorageFailure(replacedJournal::load);
    }

    @Test
    public void everyPreRenameFaultPreservesPriorLiveRecord() {
        for (String operation : Arrays.asList("CREATE", "WRITE", "FSYNC", "REPLACE")) {
            ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
            ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
            BatchInput input = input(19, 1);
            journal.publishPending(input);

            for (int repetition = 0; repetition < 4; repetition++) {
                byte[] before = live(ops).clone();
                ops.failOperation = operation;

                assertStorageFailure(() -> journal.markCommittingExact(input));

                assertEquals(operation, Arrays.toString(before), Arrays.toString(live(ops)));
                assertState(journal.load(),
                        PendingBatchState.PENDING, input);
                assertEquals(operation, 0,
                        ReadReceiptAndroidCounterJournalTest.temporaryFileCount(ops, "pending"));
            }
        }
    }

    @Test
    public void directoryFsyncAmbiguityLeavesCompleteNewStateNeverCorruption() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput input = input(20, 1);
        journal.publishPending(input);
        ops.failOperation = "FSYNC_DIR";

        assertStorageFailure(() -> journal.markCommittingExact(input));

        assertState(journal.load(), PendingBatchState.COMMITTING, input);
        assertEquals(0,
                ReadReceiptAndroidCounterJournalTest.temporaryFileCount(ops, "pending"));
    }

    @Test
    public void cleanupNeverUnlinksSubstitutedPendingTempPath() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput input = input(24, 1);
        journal.publishPending(input);
        ops.failOperation = "FSYNC";
        ops.substituteBeforeUnlink = true;

        assertStorageFailure(() -> journal.markCommittingExact(input));

        assertState(journal.load(), PendingBatchState.PENDING, input);
        assertEquals(1,
                ReadReceiptAndroidCounterJournalTest.temporaryFileCount(ops, "pending"));
    }

    @Test
    public void clearFaultsKeepTerminalOrDurableEmptyAndNeverPending() {
        for (String operation : Arrays.asList(
                "CREATE", "WRITE", "FSYNC", "REPLACE", "FSYNC_DIR")) {
            ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
            ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
            BatchInput input = input(21, 1);
            journal.publishPending(input);
            journal.markCommittingExact(input);
            journal.markTerminalExact(input,
                    PendingBatchState.TERMINAL_COMMITTED, null);
            PendingBatchRecord terminal = journal.load();
            ops.failOperation = operation;

            assertStorageFailure(() -> journal.clearTerminalExact(terminal));

            PendingBatchRecord after = journal.load();
            if ("FSYNC_DIR".equals(operation)) {
                assertNull(after);
            } else {
                assertState(after,
                        PendingBatchState.TERMINAL_COMMITTED, input);
            }
            assertEquals(operation, 0,
                    ReadReceiptAndroidCounterJournalTest.temporaryFileCount(ops, "pending"));
        }
    }

    @Test
    public void committedClearFaultsKeepCommittingOrDurableEmpty() {
        for (String operation : Arrays.asList(
                "CREATE", "WRITE", "FSYNC", "REPLACE", "FSYNC_DIR")) {
            ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
            ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
            BatchInput input = input(26, 1);
            journal.publishPending(input);
            journal.markCommittingExact(input);
            ops.failOperation = operation;

            assertStorageFailure(() -> journal.clearCommittingExact(input));

            PendingBatchRecord after = journal.load();
            if ("FSYNC_DIR".equals(operation)) {
                assertNull(after);
            } else {
                assertState(after, PendingBatchState.COMMITTING, input);
            }
            assertEquals(operation, 0,
                    ReadReceiptAndroidCounterJournalTest.temporaryFileCount(ops, "pending"));
        }
    }

    @Test
    public void payloadIsBoundedAt256EventsAndPreservesNullableMetadata() {
        ReadReceiptAndroidCounterJournalTest.FakeOps ops = newOps();
        ReadReceiptAndroidPendingBatchJournal journal = journal(ops);
        BatchInput maximum = input(22, 256);

        journal.publishPending(maximum);
        PendingBatchRecord restored = journal.load();

        assertTrue(restored.sameInput(maximum));
        assertNull(restored.input.captureBootId);
        assertNull(restored.input.sourceEpochToken);
        journal.markCommittingExact(maximum);

        ReadReceiptAndroidCounterJournalTest.FakeOps tooLargeOps = newOps();
        assertStorageFailure(() -> journal(tooLargeOps).publishPending(input(23, 257)));
        assertFalse(tooLargeOps.files.containsKey("dir/pending"));
    }

    private static ReadReceiptAndroidPendingBatchJournal journal(
            ReadReceiptAndroidCounterJournalTest.FakeOps ops) {
        return new ReadReceiptAndroidPendingBatchJournal("dir", "pending", 1000, ops,
                new Names());
    }

    private static ReadReceiptAndroidCounterJournalTest.FakeOps newOps() {
        return new ReadReceiptAndroidCounterJournalTest.FakeOps();
    }

    private static byte[] live(ReadReceiptAndroidCounterJournalTest.FakeOps ops) {
        return ops.files.get("dir/pending");
    }

    private static BatchInput input(int seed, int count) {
        List<CapturedEvent> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            events.add(new CapturedEvent(uuid(seed * 1000L + index),
                    seed, index, seed + index));
        }
        return new BatchInput(uuid(seed), 1_000L + seed,
                2_000L + seed, uuid(30), null, null, events);
    }

    private static CounterTarget target(long value) {
        return new CounterTarget(INSTALLATION, EPOCH,
                new Counters(0, 0, value, value + 1,
                        value + 2, value + 3));
    }

    private static void assertState(PendingBatchRecord actual,
                                    PendingBatchState state,
                                    BatchInput input) {
        assertNotNull(actual);
        assertEquals(state, actual.state);
        assertTrue(actual.sameInput(input));
    }

    private static void assertStorageFailure(Action action) {
        try {
            action.run();
            fail("expected storage failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
            assertFalse(expected.getMessage().contains(INSTALLATION.toString()));
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    private interface Action {
        void run();
    }

    private static final class Names implements ReadReceiptAndroidPendingBatchJournal.NameSource {
        int value;

        @Override
        public String next() {
            return "tmp-" + value++;
        }
    }
}
