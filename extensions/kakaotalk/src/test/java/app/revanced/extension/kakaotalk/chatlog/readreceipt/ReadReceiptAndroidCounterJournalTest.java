package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReadReceiptAndroidCounterJournalTest {
    private static final UUID INSTALLATION = uuid(1);
    private static final UUID EPOCH = uuid(2);

    @Test
    public void storeIsNoFollow0600AtomicAndRestartReadable() {
        FakeOps ops = new FakeOps();
        ReadReceiptAndroidCounterJournal journal = journal(ops);
        CounterTarget target = target(1);

        journal.storeGuaranteed(target);
        CounterTarget restored = journal(ops).load();

        assertNotNull(restored);
        assertEquals(1, restored.counters.uncertainOutcomeEventCount);
        assertEquals(Arrays.asList("CREATE:0600", "FSTAT", "WRITE", "FSTAT", "FSYNC", "CLOSE",
                "REPLACE", "FSYNC_DIR", "OPEN_NOFOLLOW", "FSTAT", "READ", "CLOSE"),
                ops.trace);
        assertEquals(0, ops.lastOpenFlags & FakeOps.FOLLOW_FLAG);
    }

    @Test
    public void fullWriteLoopHandlesShortWritesAndFsyncsBeforePublish() {
        FakeOps ops = new FakeOps();
        ops.maxWrite = 7;

        journal(ops).storeGuaranteed(target(2));

        int firstFsync = ops.trace.indexOf("FSYNC");
        int replace = ops.trace.indexOf("REPLACE");
        assertFalse(firstFsync < 0);
        assertFalse(replace < 0);
        assertFalse(firstFsync > replace);
        assertFalse(ops.files.get("dir/journal").length == 0);
    }

    @Test
    public void clearExactIsContentIdentitySafeAndPublishesEmptyState() {
        FakeOps ops = new FakeOps();
        ReadReceiptAndroidCounterJournal journal = journal(ops);
        CounterTarget stored = target(3);
        journal.storeGuaranteed(stored);
        byte[] before = ops.files.get("dir/journal").clone();

        journal.clearExact(target(4));
        assertEquals(Arrays.toString(before), Arrays.toString(ops.files.get("dir/journal")));

        journal.clearExact(stored);
        assertNull(journal(ops).load());
        assertFalse(ops.files.get("dir/journal").length == 0);
    }

    @Test
    public void invalidOwnerModeTypeSizeAndDigestFailWithBoundedMessage() {
        for (String fault : Arrays.asList("owner", "mode", "type", "size", "digest")) {
            FakeOps ops = new FakeOps();
            ReadReceiptAndroidCounterJournal journal = journal(ops);
            journal.storeGuaranteed(target(5));
            ops.fault = fault;
            try {
                journal.load();
                fail("expected storage failure");
            } catch (StorageException expected) {
                assertEquals("storage operation failed", expected.getMessage());
                assertFalse(expected.getMessage().contains(INSTALLATION.toString()));
            }
        }
    }

    @Test
    public void publishFaultsNeverAccumulateTempOrphans() {
        for (String operation : Arrays.asList(
                "CREATE", "WRITE", "FSYNC", "REPLACE", "FSYNC_DIR")) {
            FakeOps ops = new FakeOps();
            ReadReceiptAndroidCounterJournal journal = journal(ops);
            journal.storeGuaranteed(target(6));

            for (int repetition = 0; repetition < 4; repetition++) {
                byte[] before = ops.files.get("dir/journal").clone();
                ops.failOperation = operation;
                try {
                    journal.storeGuaranteed(target(7 + repetition));
                    fail("expected storage failure");
                } catch (StorageException expected) {
                    assertEquals("storage operation failed", expected.getMessage());
                }

                if (!"FSYNC_DIR".equals(operation)) {
                    assertEquals(operation, Arrays.toString(before),
                            Arrays.toString(ops.files.get("dir/journal")));
                }
                assertEquals(operation, 0, temporaryFileCount(ops, "journal"));
                if (!"CREATE".equals(operation) && !"FSYNC_DIR".equals(operation)) {
                    int unlink = ops.trace.lastIndexOf("UNLINK_IF_SAME");
                    int directoryFsync = ops.trace.lastIndexOf("FSYNC_DIR");
                    assertTrue(operation, unlink >= 0 && unlink < directoryFsync);
                }
            }
        }
    }

    @Test
    public void cleanupNeverUnlinksSubstitutedTempPath() {
        FakeOps ops = new FakeOps();
        ReadReceiptAndroidCounterJournal journal = journal(ops);
        journal.storeGuaranteed(target(20));
        byte[] live = ops.files.get("dir/journal").clone();
        ops.failOperation = "FSYNC";
        ops.substituteBeforeUnlink = true;

        try {
            journal.storeGuaranteed(target(21));
            fail("expected storage failure");
        } catch (StorageException expected) {
            assertEquals("storage operation failed", expected.getMessage());
        }

        assertEquals(Arrays.toString(live), Arrays.toString(ops.files.get("dir/journal")));
        assertEquals(1, temporaryFileCount(ops, "journal"));
        assertEquals(1, Collections.frequency(ops.trace, "FSYNC_DIR"));
    }

    private static ReadReceiptAndroidCounterJournal journal(FakeOps ops) {
        return new ReadReceiptAndroidCounterJournal("dir", "journal", 1000, ops,
                new IncrementingNames());
    }

    private static CounterTarget target(long value) {
        return new CounterTarget(INSTALLATION, EPOCH,
                new Counters(0, 0, value, value + 1,
                        value + 2, value + 3));
    }

    private static UUID uuid(long value) {
        return new UUID(0x8000_0000_0000_0000L, value);
    }

    static int temporaryFileCount(FakeOps ops, String basename) {
        String prefix = "dir/." + basename + ".";
        int count = 0;
        for (String path : ops.files.keySet()) {
            if (path.startsWith(prefix)) count++;
        }
        return count;
    }

    private static final class IncrementingNames
            implements ReadReceiptAndroidCounterJournal.NameSource {
        int value;

        @Override
        public String next() {
            return "tmp-" + value++;
        }
    }

    static final class FakeOps implements ReadReceiptAndroidCounterJournal.Ops {
        static final int FOLLOW_FLAG = 1;
        final Map<String, byte[]> files = new HashMap<>();
        final Map<String, Long> inodes = new HashMap<>();
        final Map<Handle, String> paths = new HashMap<>();
        final List<String> trace = new ArrayList<>();
        int maxWrite = Integer.MAX_VALUE;
        int lastOpenFlags;
        String fault;
        String failOperation;
        boolean substituteBeforeUnlink;
        long nextInode = 10;

        @Override
        public ReadReceiptAndroidCounterJournal.Handle openReadNoFollow(String path)
                throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("OPEN_NOFOLLOW");
            lastOpenFlags = 0;
            if (!files.containsKey(path)) throw new ReadReceiptAndroidCounterJournal.IoFailure(true);
            Handle handle = new Handle();
            paths.put(handle, path);
            return handle;
        }

        @Override
        public ReadReceiptAndroidCounterJournal.Handle createExclusive(String path, int mode)
                throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("CREATE:" + String.format("%04o", mode));
            fail("CREATE");
            if (files.containsKey(path)) throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            files.put(path, new byte[0]);
            inodes.put(path, nextInode++);
            Handle handle = new Handle();
            paths.put(handle, path);
            return handle;
        }

        @Override
        public ReadReceiptAndroidCounterJournal.Status fstat(
                ReadReceiptAndroidCounterJournal.Handle handle)
                throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("FSTAT");
            String path = paths.get(handle);
            byte[] bytes = files.get(path);
            return new ReadReceiptAndroidCounterJournal.Status(!"type".equals(fault),
                    "owner".equals(fault) ? 1001 : 1000,
                    "mode".equals(fault) ? 0644 : 0600,
                    "size".equals(fault) ? 1000 : bytes.length, 1, inodes.get(path));
        }

        @Override
        public int read(ReadReceiptAndroidCounterJournal.Handle handle, byte[] output,
                        int offset, int count) {
            trace.add("READ");
            byte[] source = files.get(paths.get(handle));
            int read = Math.min(count, source.length - offset);
            if (read <= 0) return 0;
            System.arraycopy(source, offset, output, offset, read);
            if ("digest".equals(fault)) output[output.length - 1] ^= 1;
            return read;
        }

        @Override
        public int write(ReadReceiptAndroidCounterJournal.Handle handle, byte[] input,
                         int offset, int count) throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("WRITE");
            fail("WRITE");
            int written = Math.min(count, maxWrite);
            String path = paths.get(handle);
            byte[] current = files.get(path);
            byte[] next = Arrays.copyOf(current, current.length + written);
            System.arraycopy(input, offset, next, current.length, written);
            files.put(path, next);
            return written;
        }

        @Override
        public void fsync(ReadReceiptAndroidCounterJournal.Handle handle)
                throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("FSYNC");
            fail("FSYNC");
        }

        @Override
        public void close(ReadReceiptAndroidCounterJournal.Handle handle) {
            trace.add("CLOSE");
            paths.remove(handle);
        }

        @Override
        public void replaceAtomic(String temporaryPath, String livePath)
                throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("REPLACE");
            fail("REPLACE");
            files.put(livePath, files.remove(temporaryPath));
            inodes.put(livePath, inodes.remove(temporaryPath));
        }

        @Override
        public boolean unlinkIfSame(String path, ReadReceiptAndroidCounterJournal.Status expected)
                throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("UNLINK_IF_SAME");
            byte[] value = files.get(path);
            if (value == null) return false;
            if (substituteBeforeUnlink) {
                substituteBeforeUnlink = false;
                files.put(path, new byte[]{99});
                inodes.put(path, nextInode++);
            }
            long inode = inodes.get(path);
            if (!expected.regular || expected.uid != 1000 || expected.mode != 0600
                    || expected.device != 1 || expected.inode != inode) {
                throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            }
            files.remove(path);
            inodes.remove(path);
            return true;
        }

        @Override
        public void fsyncDirectory(String path) throws ReadReceiptAndroidCounterJournal.IoFailure {
            trace.add("FSYNC_DIR");
            fail("FSYNC_DIR");
        }

        private void fail(String operation) throws ReadReceiptAndroidCounterJournal.IoFailure {
            if (operation.equals(failOperation)) {
                failOperation = null;
                throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            }
        }
    }

    private static final class Handle implements ReadReceiptAndroidCounterJournal.Handle {
    }
}
