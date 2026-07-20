package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class ReadReceiptBootIdReaderTest {
    private static final UUID BOOT_ID =
            UUID.fromString("01234567-89ab-4def-8abc-0123456789ab");

    @Test
    public void exactProcDescriptorReturnsCanonicalBootId() {
        FakeFileOps files = valid(0L);

        assertEquals(BOOT_ID, ReadReceiptBootIdReader.read(files));
        assertEquals(1, files.opens);
        assertEquals(1, files.fstats);
        assertEquals(1, files.closes);
        assertTrue(files.maximumReadCount <= 37);
    }

    @Test
    public void unavailableOrInvalidDescriptorReturnsNullWithoutRetry() {
        FakeFileOps openFailure = valid(0L);
        openFailure.failOpen = true;
        assertNull(ReadReceiptBootIdReader.read(openFailure));
        assertEquals(1, openFailure.opens);

        for (FakeFileOps files : Arrays.asList(
                valid(37L).regular(false),
                valid(36L),
                valid(0L).content("01234567-89AB-4def-8abc-0123456789ab\n"),
                valid(0L).content("not-a-canonical-boot-identifier-value\n"),
                valid(0L).appendByte(),
                valid(0L).failRead(),
                valid(0L).failClose())) {
            assertNull(ReadReceiptBootIdReader.read(files));
            assertEquals(1, files.opens);
        }
    }

    @Test
    public void separateProcessReadsObserveDifferentBootIds() {
        FakeFileOps files = valid(0L);
        UUID next = UUID.fromString("fedcba98-7654-4321-8765-fedcba987654");

        assertEquals(BOOT_ID, ReadReceiptBootIdReader.read(files));
        files.content(next + "\n");
        assertEquals(next, ReadReceiptBootIdReader.read(files));

        assertEquals(2, files.opens);
        assertEquals(2, files.closes);
    }

    private static FakeFileOps valid(long reportedSize) {
        return new FakeFileOps((BOOT_ID + "\n").getBytes(StandardCharsets.US_ASCII), reportedSize);
    }

    private static final class FakeFileOps implements ReadReceiptFileOps {
        private byte[] content;
        private final long reportedSize;
        private boolean regular = true;
        private boolean failOpen;
        private boolean failRead;
        private boolean failClose;
        private int offset;
        private int opens;
        private int fstats;
        private int closes;
        private int maximumReadCount;

        private FakeFileOps(byte[] content, long reportedSize) {
            this.content = content;
            this.reportedSize = reportedSize;
        }

        private FakeFileOps regular(boolean value) {
            regular = value;
            return this;
        }

        private FakeFileOps content(String value) {
            content = value.getBytes(StandardCharsets.US_ASCII);
            return this;
        }

        private FakeFileOps appendByte() {
            content = Arrays.copyOf(content, content.length + 1);
            return this;
        }

        private FakeFileOps failRead() {
            failRead = true;
            return this;
        }

        private FakeFileOps failClose() {
            failClose = true;
            return this;
        }

        @Override
        public Handle open(String path, OpenKind kind) throws Failure {
            opens++;
            if (failOpen || kind != OpenKind.READ_EXISTING
                    || !"/proc/sys/kernel/random/boot_id".equals(path)) {
                throw new Failure(FailureReason.IO);
            }
            offset = 0;
            return new Handle() { };
        }

        @Override
        public FileStatus fstat(Handle handle) {
            fstats++;
            return new FileStatus(regular, !regular, 0, 0444, reportedSize, 1, 2);
        }

        @Override
        public int read(Handle handle, byte[] bytes, int targetOffset, int count) throws Failure {
            maximumReadCount = Math.max(maximumReadCount, count);
            if (failRead) throw new Failure(FailureReason.IO);
            int copied = Math.min(count, content.length - offset);
            if (copied <= 0) return 0;
            System.arraycopy(content, offset, bytes, targetOffset, copied);
            offset += copied;
            return copied;
        }

        @Override
        public int write(Handle handle, byte[] bytes, int offset, int count) {
            throw new AssertionError();
        }

        @Override
        public void fsync(Handle handle) {
            throw new AssertionError();
        }

        @Override
        public void close(Handle handle) throws Failure {
            closes++;
            if (failClose) throw new Failure(FailureReason.IO);
        }
    }
}
