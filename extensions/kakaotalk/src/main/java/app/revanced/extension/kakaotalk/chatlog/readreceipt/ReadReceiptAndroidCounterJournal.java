package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

final class ReadReceiptAndroidCounterJournal implements CounterJournal {
    private static final int MAGIC = 0x49524354;
    private static final int VERSION = 2;
    private static final int EMPTY_TAG = 0;
    private static final int TARGET_TAG = 1;
    private static final int EMPTY_SIZE = 38;
    private static final int TARGET_SIZE = 118;
    private static final int MODE_0600 = 0600;
    private static final Object PROCESS_LOCK = new Object();

    interface Handle {
    }

    interface Ops {
        Handle openReadNoFollow(String path) throws IoFailure;

        Handle createExclusive(String path, int mode) throws IoFailure;

        Status fstat(Handle handle) throws IoFailure;

        int read(Handle handle, byte[] output, int offset, int count) throws IoFailure;

        int write(Handle handle, byte[] input, int offset, int count) throws IoFailure;

        void fsync(Handle handle) throws IoFailure;

        void close(Handle handle) throws IoFailure;

        void replaceAtomic(String temporaryPath, String livePath) throws IoFailure;

        boolean unlinkIfSame(String path, Status expected) throws IoFailure;

        void fsyncDirectory(String path) throws IoFailure;
    }

    interface NameSource {
        String next();
    }

    static final class Status {
        final boolean regular;
        final int uid;
        final int mode;
        final long size;
        final long device;
        final long inode;

        Status(boolean regular, int uid, int mode, long size, long device, long inode) {
            this.regular = regular;
            this.uid = uid;
            this.mode = mode;
            this.size = size;
            this.device = device;
            this.inode = inode;
        }
    }

    static final class IoFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final boolean notFound;

        IoFailure(boolean notFound) {
            this.notFound = notFound;
        }
    }

    private final String directory;
    private final String basename;
    private final String livePath;
    private final int expectedUid;
    private final Ops ops;
    private final NameSource names;

    ReadReceiptAndroidCounterJournal(String noBackupDirectory) {
        this(noBackupDirectory, "iris_read_receipt_v2.counter-target", Process.myUid(),
                new AndroidOps(), () -> UUID.randomUUID().toString());
    }

    ReadReceiptAndroidCounterJournal(String directory, String basename, int expectedUid,
                                     Ops ops, NameSource names) {
        if (!validPath(directory) || !validBasename(basename) || expectedUid < 0
                || ops == null || names == null) throw failure();
        this.directory = directory;
        this.basename = basename;
        this.livePath = directory + "/" + basename;
        this.expectedUid = expectedUid;
        this.ops = ops;
        this.names = names;
    }

    @Override
    public CounterTarget load() {
        synchronized (PROCESS_LOCK) {
            return loadLocked();
        }
    }

    @Override
    public void storeGuaranteed(CounterTarget target) {
        if (target == null) throw failure();
        synchronized (PROCESS_LOCK) {
            publish(encodeTarget(target));
        }
    }

    @Override
    public void clearExact(CounterTarget expected) {
        if (expected == null) throw failure();
        synchronized (PROCESS_LOCK) {
            CounterTarget current = loadLocked();
            if (current == null || !current.same(expected)) return;
            publish(encodeEmpty());
        }
    }

    private CounterTarget loadLocked() {
        Handle handle = null;
        try {
            handle = ops.openReadNoFollow(livePath);
            Status status = ops.fstat(handle);
            requireStatus(status, false);
            byte[] bytes = readExact(handle, (int) status.size);
            closeStrict(handle);
            handle = null;
            return decode(bytes);
        } catch (IoFailure exception) {
            closeQuietly(handle);
            if (exception.notFound) return null;
            throw failure();
        } catch (RuntimeException exception) {
            closeQuietly(handle);
            throw failure();
        }
    }

    private void publish(byte[] content) {
        String suffix = names.next();
        if (!validBasename(suffix)) throw failure();
        String temporaryPath = directory + "/." + basename + "." + suffix;
        Handle handle = null;
        Status created = null;
        boolean replaced = false;
        try {
            handle = ops.createExclusive(temporaryPath, MODE_0600);
            Status initial = ops.fstat(handle);
            requireStatus(initial, true);
            if (initial.size != 0) throw new IoFailure(false);
            created = initial;
            writeFull(handle, content);
            Status complete = ops.fstat(handle);
            requireStatus(complete, true);
            if (!sameIdentity(created, complete) || complete.size != content.length) {
                throw new IoFailure(false);
            }
            ops.fsync(handle);
            closeStrict(handle);
            handle = null;
            ops.replaceAtomic(temporaryPath, livePath);
            replaced = true;
            ops.fsyncDirectory(directory);
        } catch (IoFailure | RuntimeException exception) {
            closeQuietly(handle);
            if (!replaced && created != null) cleanupTemporary(temporaryPath, created);
            throw failure();
        }
    }

    private void cleanupTemporary(String path, Status expected) {
        try {
            if (ops.unlinkIfSame(path, expected)) ops.fsyncDirectory(directory);
        } catch (IoFailure | RuntimeException ignored) {
        }
    }

    private byte[] readExact(Handle handle, int size) throws IoFailure {
        if (size != EMPTY_SIZE && size != TARGET_SIZE) throw new IoFailure(false);
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < result.length) {
            int read = ops.read(handle, result, offset, result.length - offset);
            if (read <= 0 || read > result.length - offset) throw new IoFailure(false);
            offset += read;
        }
        return result;
    }

    private void writeFull(Handle handle, byte[] content) throws IoFailure {
        int offset = 0;
        while (offset < content.length) {
            int written = ops.write(handle, content, offset, content.length - offset);
            if (written <= 0 || written > content.length - offset) throw new IoFailure(false);
            offset += written;
        }
    }

    private void requireStatus(Status status, boolean allowEmpty) throws IoFailure {
        if (status == null || !status.regular || status.uid != expectedUid
                || status.mode != MODE_0600 || status.device < 0 || status.inode <= 0
                || status.size < 0 || status.size > TARGET_SIZE
                || !allowEmpty && status.size != EMPTY_SIZE && status.size != TARGET_SIZE) {
            throw new IoFailure(false);
        }
    }

    private void closeStrict(Handle handle) throws IoFailure {
        if (handle != null) ops.close(handle);
    }

    private void closeQuietly(Handle handle) {
        if (handle == null) return;
        try {
            ops.close(handle);
        } catch (IoFailure | RuntimeException ignored) {
        }
    }

    private static boolean sameIdentity(Status left, Status right) {
        return left != null && right != null && left.regular && right.regular
                && left.uid == right.uid && left.mode == right.mode
                && left.device == right.device && left.inode == right.inode;
    }

    private static byte[] encodeEmpty() {
        return withDigest(payload(EMPTY_TAG, null));
    }

    private static byte[] encodeTarget(CounterTarget target) {
        return withDigest(payload(TARGET_TAG, target));
    }

    private static byte[] payload(int tag, CounterTarget target) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(tag);
            if (tag == TARGET_TAG) {
                writeUuid(output, target.installationId);
                writeUuid(output, target.streamEpoch);
                Counters counters = target.counters;
                output.writeLong(counters.confirmedDroppedEventCount);
                output.writeLong(counters.confirmedDroppedBatchCount);
                output.writeLong(counters.uncertainOutcomeEventCount);
                output.writeLong(counters.uncertainOutcomeBatchCount);
                output.writeLong(counters.captureDropEventCount);
                output.writeLong(counters.captureDropBatchCount);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw failure();
        }
    }

    private static byte[] withDigest(byte[] payload) {
        byte[] digest = ReadReceiptProtocolV2.sha256(payload);
        byte[] result = Arrays.copyOf(payload, payload.length + digest.length);
        System.arraycopy(digest, 0, result, payload.length, digest.length);
        return result;
    }

    private static CounterTarget decode(byte[] encoded) {
        int payloadLength = encoded.length - 32;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] storedDigest = Arrays.copyOfRange(encoded, payloadLength, encoded.length);
        if (!MessageDigest.isEqual(storedDigest, ReadReceiptProtocolV2.sha256(payload))) {
            throw failure();
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) throw failure();
            int tag = input.readUnsignedByte();
            if (tag == EMPTY_TAG) {
                if (encoded.length != EMPTY_SIZE || input.available() != 0) throw failure();
                return null;
            }
            if (tag != TARGET_TAG || encoded.length != TARGET_SIZE) throw failure();
            UUID installation = readUuid(input);
            UUID epoch = readUuid(input);
            Counters counters = new Counters(
                    input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                    input.readLong(), input.readLong());
            if (input.available() != 0) throw failure();
            return new CounterTarget(installation, epoch, counters);
        } catch (IOException | RuntimeException exception) {
            throw failure();
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static boolean validPath(String path) {
        return path != null && !path.isEmpty() && path.indexOf('\0') < 0;
    }

    private static boolean validBasename(String value) {
        return value != null && !value.isEmpty() && !".".equals(value) && !"..".equals(value)
                && value.indexOf('/') < 0 && value.indexOf('\0') < 0;
    }

    private static StorageException failure() {
        return new StorageException();
    }

    private static final class AndroidHandle implements Handle {
        final FileDescriptor descriptor;

        AndroidHandle(FileDescriptor descriptor) {
            this.descriptor = descriptor;
        }
    }

    static final class AndroidOps implements Ops {
        @Override
        public Handle openReadNoFollow(String path) throws IoFailure {
            return open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC
                    | OsConstants.O_NOFOLLOW, 0);
        }

        @Override
        public Handle createExclusive(String path, int mode) throws IoFailure {
            return open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                    | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, mode);
        }

        @Override
        public Status fstat(Handle handle) throws IoFailure {
            try {
                StructStat status = Os.fstat(descriptor(handle));
                return new Status(OsConstants.S_ISREG(status.st_mode), status.st_uid,
                        status.st_mode & 0777, status.st_size, status.st_dev, status.st_ino);
            } catch (ErrnoException | RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        @Override
        public int read(Handle handle, byte[] output, int offset, int count) throws IoFailure {
            try {
                return Os.read(descriptor(handle), output, offset, count);
            } catch (ErrnoException | IOException | RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        @Override
        public int write(Handle handle, byte[] input, int offset, int count) throws IoFailure {
            try {
                return Os.write(descriptor(handle), input, offset, count);
            } catch (ErrnoException | IOException | RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        @Override
        public void fsync(Handle handle) throws IoFailure {
            try {
                Os.fsync(descriptor(handle));
            } catch (ErrnoException | RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        @Override
        public void close(Handle handle) throws IoFailure {
            try {
                Os.close(descriptor(handle));
            } catch (ErrnoException | RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        @Override
        public void replaceAtomic(String temporaryPath, String livePath) throws IoFailure {
            try {
                Os.rename(temporaryPath, livePath);
            } catch (ErrnoException | RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        @Override
        public boolean unlinkIfSame(String path, Status expected) throws IoFailure {
            Handle handle = null;
            try {
                handle = openReadNoFollow(path);
                Status current = fstat(handle);
                if (!sameIdentity(expected, current)) throw new IoFailure(false);
                Os.remove(path);
                close(handle);
                return true;
            } catch (IoFailure exception) {
                closeQuietly(handle);
                if (exception.notFound) return false;
                throw exception;
            } catch (ErrnoException | RuntimeException exception) {
                closeQuietly(handle);
                if (exception instanceof ErrnoException
                        && ((ErrnoException) exception).errno == OsConstants.ENOENT) {
                    return false;
                }
                throw new IoFailure(false);
            }
        }

        private void closeQuietly(Handle handle) {
            if (handle == null) return;
            try {
                close(handle);
            } catch (IoFailure | RuntimeException ignored) {
            }
        }

        @Override
        public void fsyncDirectory(String path) throws IoFailure {
            FileDescriptor descriptor = null;
            try {
                descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC
                        | OsConstants.O_NOFOLLOW, 0);
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                    throw new IoFailure(false);
                }
                Os.fsync(descriptor);
                Os.close(descriptor);
            } catch (IoFailure exception) {
                if (descriptor != null) {
                    try {
                        Os.close(descriptor);
                    } catch (ErrnoException | RuntimeException ignored) {
                    }
                }
                throw exception;
            } catch (ErrnoException | RuntimeException exception) {
                if (descriptor != null) {
                    try {
                        Os.close(descriptor);
                    } catch (ErrnoException | RuntimeException ignored) {
                    }
                }
                throw new IoFailure(false);
            }
        }

        private static Handle open(String path, int flags, int mode) throws IoFailure {
            try {
                return new AndroidHandle(Os.open(path, flags, mode));
            } catch (ErrnoException exception) {
                throw new IoFailure(exception.errno == OsConstants.ENOENT);
            } catch (RuntimeException exception) {
                throw new IoFailure(false);
            }
        }

        private static FileDescriptor descriptor(Handle handle) throws IoFailure {
            if (!(handle instanceof AndroidHandle)) throw new IoFailure(false);
            return ((AndroidHandle) handle).descriptor;
        }
    }
}
