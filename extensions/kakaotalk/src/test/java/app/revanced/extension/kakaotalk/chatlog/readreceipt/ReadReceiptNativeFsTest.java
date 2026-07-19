package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ReadReceiptNativeFsTest {
    private static final String SENSITIVE_TEMP = "/data/user/0/com.kakao.talk/no_backup/secret.tmp";
    private static final String SENSITIVE_DIRECTORY = "/data/user/0/com.kakao.talk/no_backup";
    private static final String TEMP_BASENAME =
            ".iris_read_receipts.db.tmp-00112233445566778899aabbccddeeff";

    @Test
    public void loadFailureFailsClosedWithoutCallingNative() {
        FakeCalls calls = new FakeCalls();
        ReadReceiptNativeFs fs = new ReadReceiptNativeFs(() -> false, calls);

        assertEquals(
                ReadReceiptNativeFs.Result.UNAVAILABLE,
                fs.publish(SENSITIVE_TEMP, SENSITIVE_DIRECTORY, "live.db", 7, 11)
        );
        assertEquals(ReadReceiptNativeFs.Result.UNAVAILABLE, fs.fsyncDirectory(SENSITIVE_DIRECTORY));
        assertEquals(ReadReceiptNativeFs.Result.UNAVAILABLE,
                fs.unlinkIfExactInode(SENSITIVE_DIRECTORY, TEMP_BASENAME, 7, 11));
        assertEquals(0, calls.invocations);
    }

    @Test
    public void loaderExceptionFailsClosed() {
        ReadReceiptNativeFs fs = new ReadReceiptNativeFs(
                () -> { throw new UnsatisfiedLinkError("sensitive library path"); },
                new FakeCalls()
        );

        assertEquals(ReadReceiptNativeFs.Result.UNAVAILABLE, fs.fsyncDirectory(SENSITIVE_DIRECTORY));
    }

    @Test
    public void mapsEveryBoundedNativeResultAndRejectsUnknownCodes() {
        FakeCalls calls = new FakeCalls();
        ReadReceiptNativeFs fs = new ReadReceiptNativeFs(() -> true, calls);
        EnumSet<ReadReceiptNativeFs.Result> observed = EnumSet.noneOf(ReadReceiptNativeFs.Result.class);

        for (int code = 0; code <= 7; code++) {
            calls.code = code;
            observed.add(fs.fsyncDirectory(SENSITIVE_DIRECTORY));
        }
        calls.code = -1;
        assertEquals(ReadReceiptNativeFs.Result.IO_ERROR, fs.fsyncDirectory(SENSITIVE_DIRECTORY));
        calls.code = 8;
        assertEquals(ReadReceiptNativeFs.Result.IO_ERROR, fs.fsyncDirectory(SENSITIVE_DIRECTORY));

        assertEquals(EnumSet.allOf(ReadReceiptNativeFs.Result.class), observed);
    }

    @Test
    public void delegatesAllOperationsAndMapsCallFailure() {
        FakeCalls calls = new FakeCalls();
        ReadReceiptNativeFs fs = new ReadReceiptNativeFs(() -> true, calls);

        assertEquals(ReadReceiptNativeFs.Result.OK,
                fs.publish(SENSITIVE_TEMP, SENSITIVE_DIRECTORY, "live.db", 7, 11));
        assertEquals(ReadReceiptNativeFs.Result.OK, fs.fsyncPath(SENSITIVE_TEMP, 7, 11));
        assertEquals(ReadReceiptNativeFs.Result.OK, fs.fsyncDirectory(SENSITIVE_DIRECTORY));
        assertEquals(ReadReceiptNativeFs.Result.OK,
                fs.unlinkIfExactInode(SENSITIVE_DIRECTORY, TEMP_BASENAME, 7, 11));
        assertEquals(4, calls.invocations);

        calls.throwOnCall = true;
        assertEquals(ReadReceiptNativeFs.Result.IO_ERROR,
                fs.publish(SENSITIVE_TEMP, SENSITIVE_DIRECTORY, "live.db", 7, 11));
    }

    @Test
    public void invalidArgumentsAreBoundedAndNeverReachNative() {
        FakeCalls calls = new FakeCalls();
        ReadReceiptNativeFs fs = new ReadReceiptNativeFs(() -> true, calls);

        assertEquals(ReadReceiptNativeFs.Result.INVALID_ARGUMENT,
                fs.publish(null, SENSITIVE_DIRECTORY, "live.db", 7, 11));
        assertEquals(ReadReceiptNativeFs.Result.INVALID_ARGUMENT,
                fs.publish(SENSITIVE_TEMP, SENSITIVE_DIRECTORY, "../live.db", 7, 11));
        assertEquals(ReadReceiptNativeFs.Result.INVALID_ARGUMENT,
                fs.fsyncPath("", 7, 11));
        assertEquals(ReadReceiptNativeFs.Result.INVALID_ARGUMENT, fs.fsyncDirectory(""));
        assertEquals(ReadReceiptNativeFs.Result.INVALID_ARGUMENT,
                fs.unlinkIfExactInode(SENSITIVE_DIRECTORY, "../secret.tmp", 7, 11));
        assertEquals(0, calls.invocations);
    }

    @Test
    public void resultTextIsBoundedAndContainsNoSensitivePath() {
        FakeCalls calls = new FakeCalls();
        ReadReceiptNativeFs fs = new ReadReceiptNativeFs(() -> true, calls);

        for (ReadReceiptNativeFs.Result result : ReadReceiptNativeFs.Result.values()) {
            String text = result.toString();
            assertFalse(text.contains(SENSITIVE_TEMP));
            assertFalse(text.contains(SENSITIVE_DIRECTORY));
            assertFalse(text.length() > 32);
        }
        assertFalse(fs.toString().contains(SENSITIVE_DIRECTORY));
    }

    private static final class FakeCalls implements ReadReceiptNativeFs.NativeCalls {
        int code;
        int invocations;
        boolean throwOnCall;

        @Override
        public int publish(String tempPath, String liveDirectory, String basename,
                           long expectedDevice, long expectedInode) {
            return result();
        }

        @Override
        public int fsyncPath(String path, long expectedDevice, long expectedInode) {
            return result();
        }

        @Override
        public int fsyncDirectory(String directory) {
            return result();
        }

        @Override
        public int unlinkIfExactInode(String directory, String basename,
                                      long expectedDevice, long expectedInode) {
            return result();
        }

        private int result() {
            invocations++;
            if (throwOnCall) throw new UnsatisfiedLinkError("sensitive path");
            return code;
        }
    }
}
