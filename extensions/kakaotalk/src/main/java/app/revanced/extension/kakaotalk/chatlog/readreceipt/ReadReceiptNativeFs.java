package app.revanced.extension.kakaotalk.chatlog.readreceipt;

final class ReadReceiptNativeFs {
    private static final String TEMP_PREFIX = ".iris_read_receipts.db.tmp-";
    enum Result {
        OK,
        EXISTS,
        UNAVAILABLE,
        INVALID_ARGUMENT,
        NOT_FOUND,
        IDENTITY_MISMATCH,
        UNSUPPORTED,
        IO_ERROR
    }

    interface Loader {
        boolean load();
    }

    interface NativeCalls {
        int publish(String tempPath, String liveDirectory, String basename,
                    long expectedDevice, long expectedInode);

        int fsyncPath(String path, long expectedDevice, long expectedInode);

        int fsyncDirectory(String directory);

        int unlinkIfExactInode(String directory, String basename,
                               long expectedDevice, long expectedInode);

    }

    private static final NativeCalls JNI_CALLS = new NativeCalls() {
        @Override
        public int publish(String tempPath, String liveDirectory, String basename,
                           long expectedDevice, long expectedInode) {
            return nativePublish(
                    tempPath, liveDirectory, basename, expectedDevice, expectedInode);
        }

        @Override
        public int fsyncPath(String path, long expectedDevice, long expectedInode) {
            return nativeFsyncPath(path, expectedDevice, expectedInode);
        }

        @Override
        public int fsyncDirectory(String directory) {
            return nativeFsyncDirectory(directory);
        }

        @Override
        public int unlinkIfExactInode(String directory, String basename,
                                      long expectedDevice, long expectedInode) {
            return nativeUnlinkIfExactInode(
                    directory, basename, expectedDevice, expectedInode);
        }

    };

    private final boolean available;
    private final NativeCalls calls;

    ReadReceiptNativeFs() {
        this(ReadReceiptNativeFs::loadLibrary, JNI_CALLS);
    }

    ReadReceiptNativeFs(Loader loader, NativeCalls calls) {
        this.calls = calls;
        this.available = load(loader) && calls != null;
    }

    Result publish(String tempPath, String liveDirectory, String basename,
                   long expectedDevice, long expectedInode) {
        if (!validPath(tempPath)
                || !validPath(liveDirectory)
                || !validBasename(basename)
                || !validIdentity(expectedDevice, expectedInode)) {
            return Result.INVALID_ARGUMENT;
        }
        return invoke(() -> calls.publish(
                tempPath, liveDirectory, basename, expectedDevice, expectedInode));
    }

    Result fsyncPath(String path, long expectedDevice, long expectedInode) {
        if (!validPath(path) || !validIdentity(expectedDevice, expectedInode)) {
            return Result.INVALID_ARGUMENT;
        }
        return invoke(() -> calls.fsyncPath(path, expectedDevice, expectedInode));
    }

    Result fsyncDirectory(String directory) {
        if (!validPath(directory)) return Result.INVALID_ARGUMENT;
        return invoke(() -> calls.fsyncDirectory(directory));
    }

    Result unlinkIfExactInode(String directory, String basename,
                              long expectedDevice, long expectedInode) {
        if (!validPath(directory)
                || !validTempBasename(basename)
                || !validIdentity(expectedDevice, expectedInode)) {
            return Result.INVALID_ARGUMENT;
        }
        return invoke(() -> calls.unlinkIfExactInode(
                directory, basename, expectedDevice, expectedInode));
    }

    @Override
    public String toString() {
        return "ReadReceiptNativeFs:" + (available ? "AVAILABLE" : "UNAVAILABLE");
    }

    private Result invoke(IntCall call) {
        if (!available) return Result.UNAVAILABLE;
        try {
            return map(call.run());
        } catch (LinkageError | RuntimeException ignored) {
            return Result.IO_ERROR;
        }
    }

    private static boolean load(Loader loader) {
        if (loader == null) return false;
        try {
            return loader.load();
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean loadLibrary() {
        System.loadLibrary("readreceiptfs");
        return true;
    }

    private static Result map(int code) {
        switch (code) {
            case 0:
                return Result.OK;
            case 1:
                return Result.EXISTS;
            case 2:
                return Result.UNAVAILABLE;
            case 3:
                return Result.INVALID_ARGUMENT;
            case 4:
                return Result.NOT_FOUND;
            case 5:
                return Result.IDENTITY_MISMATCH;
            case 6:
                return Result.UNSUPPORTED;
            case 7:
            default:
                return Result.IO_ERROR;
        }
    }

    private static boolean validPath(String path) {
        return path != null && !path.isEmpty() && path.indexOf('\0') < 0;
    }

    private static boolean validBasename(String basename) {
        return basename != null
                && !basename.isEmpty()
                && !".".equals(basename)
                && !"..".equals(basename)
                && basename.indexOf('/') < 0
                && basename.indexOf('\0') < 0;
    }

    private static boolean validTempBasename(String basename) {
        if (!validBasename(basename)
                || !basename.startsWith(TEMP_PREFIX)
                || basename.length() != TEMP_PREFIX.length() + 32) {
            return false;
        }
        for (int index = TEMP_PREFIX.length(); index < basename.length(); index++) {
            char value = basename.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validIdentity(long device, long inode) {
        return device >= 0 && inode > 0;
    }

    private interface IntCall {
        int run();
    }

    private static native int nativePublish(
            String tempPath,
            String liveDirectory,
            String basename,
            long expectedDevice,
            long expectedInode
    );

    private static native int nativeFsyncPath(
            String path,
            long expectedDevice,
            long expectedInode
    );

    private static native int nativeFsyncDirectory(String directory);

    private static native int nativeUnlinkIfExactInode(
            String directory,
            String basename,
            long expectedDevice,
            long expectedInode
    );

}
