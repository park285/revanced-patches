package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Test;

public final class ReadReceiptSourceEpochMarkerTest {
    private static final int UID = 12001;
    private static final String DIRECTORY = "/opaque/no-backup";
    private static final UUID TOKEN = UUID.fromString("01234567-89ab-4def-8abc-0123456789ab");
    private static final UUID NEXT_TOKEN =
            UUID.fromString("fedcba98-7654-4321-8765-fedcba987654");

    @Test
    public void exactDescriptorContentReturnsToken() {
        FakeFileOps files = valid();

        assertEquals(TOKEN, ReadReceiptSourceEpochMarker.read(files, DIRECTORY, UID));
        assertEquals(1, files.opens);
        assertEquals(1, files.fstats);
        assertEquals(1, files.closes);
    }

    @Test
    public void invalidMetadataContentOrDescriptorReplacementIsUnattributed() {
        for (FakeFileOps files : Arrays.asList(
                valid().mode(0640),
                valid().uid(UID + 1),
                valid().regular(false),
                valid().content("version=1\nsource_epoch_token=01234567-89AB-4def-8abc-0123456789ab\n"),
                valid().content("version=2\nsource_epoch_token=01234567-89ab-4def-8abc-0123456789ab\n"),
                valid().content("version=1\nsource_epoch_token=not-a-canonical-uuid________________\n"),
                valid().appendAfterFstat())) {
            assertNull(ReadReceiptSourceEpochMarker.read(files, DIRECTORY, UID));
        }
    }

    @Test
    public void openReadAndCloseFailuresAreUnattributedWithoutRetry() {
        FakeFileOps open = valid();
        open.failOpen = true;
        assertNull(ReadReceiptSourceEpochMarker.read(open, DIRECTORY, UID));
        assertEquals(1, open.opens);

        FakeFileOps read = valid();
        read.failRead = true;
        assertNull(ReadReceiptSourceEpochMarker.read(read, DIRECTORY, UID));

        FakeFileOps close = valid();
        close.failClose = true;
        assertNull(ReadReceiptSourceEpochMarker.read(close, DIRECTORY, UID));
    }

    @Test
    public void oldToNewMarkerTransitionUsesOneFreshDescriptorPerCapture() {
        FakeFileOps files = valid();

        assertEquals(TOKEN, ReadReceiptSourceEpochMarker.read(files, DIRECTORY, UID));
        files.content(marker(NEXT_TOKEN));
        assertEquals(NEXT_TOKEN, ReadReceiptSourceEpochMarker.read(files, DIRECTORY, UID));

        assertEquals(2, files.opens);
        assertEquals(2, files.fstats);
        assertEquals(2, files.closes);
    }

    @Test
    public void malformedMarkerDoesNotBecomeAStaleFallbackForTheNextCapture() {
        FakeFileOps files = valid().content(
                "version=1\nsource_epoch_token=not-a-canonical-uuid________________\n");

        assertNull(ReadReceiptSourceEpochMarker.read(files, DIRECTORY, UID));
        files.content(marker(NEXT_TOKEN));
        assertEquals(NEXT_TOKEN, ReadReceiptSourceEpochMarker.read(files, DIRECTORY, UID));

        assertEquals(2, files.opens);
        assertEquals(2, files.closes);
    }

    private static FakeFileOps valid() {
        return new FakeFileOps(ascii(marker(TOKEN)));
    }

    private static String marker(UUID token) {
        return "version=1\nsource_epoch_token=" + token + "\n";
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class FakeFileOps implements ReadReceiptFileOps {
        private byte[] content;
        private int uid = UID;
        private int mode = 0600;
        private boolean regular = true;
        private boolean failOpen;
        private boolean failRead;
        private boolean failClose;
        private boolean appendAfterFstat;
        private int offset;
        private int opens;
        private int fstats;
        private int closes;

        private FakeFileOps(byte[] content) {
            this.content = content;
        }

        private FakeFileOps mode(int value) {
            mode = value;
            return this;
        }

        private FakeFileOps uid(int value) {
            uid = value;
            return this;
        }

        private FakeFileOps regular(boolean value) {
            regular = value;
            return this;
        }

        private FakeFileOps content(String value) {
            content = ascii(value);
            return this;
        }

        private FakeFileOps appendAfterFstat() {
            appendAfterFstat = true;
            return this;
        }

        @Override
        public Handle open(String path, OpenKind kind) throws Failure {
            opens++;
            if (failOpen || kind != OpenKind.READ_EXISTING) throw new Failure(FailureReason.IO);
            offset = 0;
            return new Handle() { };
        }

        @Override
        public FileStatus fstat(Handle handle) {
            fstats++;
            long reportedSize = content.length;
            if (appendAfterFstat) content = Arrays.copyOf(content, content.length + 1);
            return new FileStatus(regular, !regular, uid, mode, reportedSize, 7, 9);
        }

        @Override
        public int read(Handle handle, byte[] bytes, int targetOffset, int count) throws Failure {
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
