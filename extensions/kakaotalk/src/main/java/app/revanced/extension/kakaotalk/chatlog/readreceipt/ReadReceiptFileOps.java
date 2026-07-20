package app.revanced.extension.kakaotalk.chatlog.readreceipt;

interface ReadReceiptFileOps {

    Handle open(String path, OpenKind kind) throws Failure;

    FileStatus fstat(Handle handle) throws Failure;

    int read(Handle handle, byte[] bytes, int offset, int count) throws Failure;

    int write(Handle handle, byte[] bytes, int offset, int count) throws Failure;

    void fsync(Handle handle) throws Failure;

    void close(Handle handle) throws Failure;

    interface Handle {
    }

    enum OpenKind {
        READ_EXISTING,
        CREATE_EXCLUSIVE,
        DIRECTORY
    }

    enum FailureReason {
        EXISTS,
        NOT_FOUND,
        IO
    }

    final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final FailureReason reason;

        Failure(FailureReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        FailureReason reason() {
            return reason;
        }
    }

    final class FileStatus {
        final boolean regular;
        final boolean directory;
        final int uid;
        final int mode;
        final long size;
        final long device;
        final long inode;

        FileStatus(boolean regular, boolean directory, int uid, int mode, long size,
                   long device, long inode) {
            this.regular = regular;
            this.directory = directory;
            this.uid = uid;
            this.mode = mode;
            this.size = size;
            this.device = device;
            this.inode = inode;
        }
    }
}
