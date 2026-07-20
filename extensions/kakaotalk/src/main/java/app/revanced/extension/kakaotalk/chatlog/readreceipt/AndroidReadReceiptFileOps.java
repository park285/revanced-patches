package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;

final class AndroidReadReceiptFileOps implements ReadReceiptFileOps {
    // Android/bionic follows asm-generic O_DIRECTORY, not the glibc value.
    static final int DIRECTORY_FLAG = 0x4000;

    @Override
    public Handle open(String path, OpenKind kind) throws Failure {
        try {
            return new AndroidHandle(Os.open(path, flagsFor(kind), modeFor(kind)));
        } catch (ErrnoException exception) {
            throw failure(exception);
        }
    }

    @Override
    public FileStatus fstat(Handle handle) throws Failure {
        try {
            StructStat status = Os.fstat(descriptor(handle));
            return new FileStatus(
                    OsConstants.S_ISREG(status.st_mode),
                    OsConstants.S_ISDIR(status.st_mode),
                    status.st_uid,
                    status.st_mode & 07777,
                    status.st_size,
                    status.st_dev,
                    status.st_ino
            );
        } catch (ErrnoException exception) {
            throw failure(exception);
        }
    }

    @Override
    public int read(Handle handle, byte[] bytes, int offset, int count) throws Failure {
        try {
            return Os.read(descriptor(handle), bytes, offset, count);
        } catch (ErrnoException | InterruptedIOException ignored) {
            throw new Failure(FailureReason.IO);
        }
    }

    @Override
    public int write(Handle handle, byte[] bytes, int offset, int count) throws Failure {
        try {
            return Os.write(descriptor(handle), bytes, offset, count);
        } catch (ErrnoException | InterruptedIOException ignored) {
            throw new Failure(FailureReason.IO);
        }
    }

    @Override
    public void fsync(Handle handle) throws Failure {
        try {
            Os.fsync(descriptor(handle));
        } catch (ErrnoException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void close(Handle handle) throws Failure {
        try {
            Os.close(descriptor(handle));
        } catch (ErrnoException exception) {
            throw failure(exception);
        }
    }

    static int flagsFor(OpenKind kind) {
        if (kind == OpenKind.CREATE_EXCLUSIVE) {
            return OsConstants.O_WRONLY
                    | OsConstants.O_CREAT
                    | OsConstants.O_EXCL
                    | OsConstants.O_CLOEXEC
                    | OsConstants.O_NOFOLLOW;
        }
        if (kind == OpenKind.DIRECTORY) {
            return OsConstants.O_RDONLY
                    | OsConstants.O_CLOEXEC
                    | OsConstants.O_NOFOLLOW
                    | DIRECTORY_FLAG;
        }
        return OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW;
    }

    static int modeFor(OpenKind kind) {
        return kind == OpenKind.CREATE_EXCLUSIVE ? 0600 : 0;
    }

    private static FileDescriptor descriptor(Handle handle) {
        return ((AndroidHandle) handle).descriptor;
    }

    private static Failure failure(ErrnoException exception) {
        return new Failure(reasonForErrno(exception.errno));
    }

    static FailureReason reasonForErrno(int errno) {
        return classifyErrno(errno, OsConstants.EEXIST, OsConstants.ENOENT);
    }

    static FailureReason classifyErrno(int errno, int exists, int notFound) {
        if (errno == exists) return FailureReason.EXISTS;
        if (errno == notFound) return FailureReason.NOT_FOUND;
        return FailureReason.IO;
    }

    private static final class AndroidHandle implements Handle {
        private final FileDescriptor descriptor;

        private AndroidHandle(FileDescriptor descriptor) {
            this.descriptor = descriptor;
        }
    }
}
