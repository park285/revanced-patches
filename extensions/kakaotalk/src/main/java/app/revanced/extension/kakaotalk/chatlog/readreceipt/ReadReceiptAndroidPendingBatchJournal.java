package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.os.Process;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class ReadReceiptAndroidPendingBatchJournal
        implements PendingBatchJournal {
    private static final int MAGIC = 0x49525042;
    private static final int VERSION = 2;
    private static final int EMPTY_TAG = 0;
    private static final int PENDING_TAG = 1;
    private static final int COMMITTING_TAG = 2;
    private static final int TERMINAL_COMMITTED_TAG = 3;
    private static final int TERMINAL_CONFIRMED_LOSS_TAG = 4;
    private static final int TERMINAL_UNCERTAIN_TAG = 5;
    private static final int EMPTY_SIZE = 38;
    private static final int MAX_EVENTS = 256;
    private static final int MAX_ENCODED_SIZE = 32 * 1024;
    private static final int MODE_0600 = 0600;
    private static final Object PROCESS_LOCK = new Object();

    interface NameSource {
        String next();
    }

    private final String directory;
    private final String basename;
    private final String livePath;
    private final int expectedUid;
    private final ReadReceiptAndroidCounterJournal.Ops ops;
    private final NameSource names;

    ReadReceiptAndroidPendingBatchJournal(String noBackupDirectory) {
        this(noBackupDirectory, "iris_read_receipt_v2.pending-batch", Process.myUid(),
                new ReadReceiptAndroidCounterJournal.AndroidOps(),
                () -> UUID.randomUUID().toString());
    }

    ReadReceiptAndroidPendingBatchJournal(String directory, String basename, int expectedUid,
                                          ReadReceiptAndroidCounterJournal.Ops ops,
                                          NameSource names) {
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
    public PendingBatchRecord load() {
        synchronized (PROCESS_LOCK) {
            return loadLocked();
        }
    }

    @Override
    public void publishPending(BatchInput input) {
        if (input == null || input.events.size() > MAX_EVENTS) throw failure();
        synchronized (PROCESS_LOCK) {
            if (loadLocked() != null) throw failure();
            publish(encode(new PendingBatchRecord(
                    PendingBatchState.PENDING, input, null)));
        }
    }

    @Override
    public void markCommittingExact(BatchInput expected) {
        synchronized (PROCESS_LOCK) {
            PendingBatchRecord current = loadLocked();
            requireState(current, expected, PendingBatchState.PENDING);
            publish(encode(new PendingBatchRecord(
                    PendingBatchState.COMMITTING, expected, null)));
        }
    }

    @Override
    public void markTerminalExact(BatchInput expected,
                                  PendingBatchState state,
                                  CounterTarget counterTarget) {
        if (state == null || state == PendingBatchState.PENDING
                || state == PendingBatchState.COMMITTING) {
            throw failure();
        }
        synchronized (PROCESS_LOCK) {
            PendingBatchRecord current = loadLocked();
            requireState(current, expected, PendingBatchState.COMMITTING);
            publish(encode(new PendingBatchRecord(
                    state, expected, counterTarget)));
        }
    }

    @Override
    public void clearTerminalExact(PendingBatchRecord expected) {
        if (expected == null || !expected.terminal()) throw failure();
        synchronized (PROCESS_LOCK) {
            PendingBatchRecord current = loadLocked();
            if (current == null || !current.same(expected)) return;
            publish(encodeEmpty());
        }
    }

    private PendingBatchRecord loadLocked() {
        ReadReceiptAndroidCounterJournal.Handle handle = null;
        try {
            handle = ops.openReadNoFollow(livePath);
            ReadReceiptAndroidCounterJournal.Status status = ops.fstat(handle);
            requireStatus(status, false);
            byte[] bytes = readExact(handle, (int) status.size);
            closeStrict(handle);
            handle = null;
            return decode(bytes);
        } catch (ReadReceiptAndroidCounterJournal.IoFailure exception) {
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
        ReadReceiptAndroidCounterJournal.Handle handle = null;
        ReadReceiptAndroidCounterJournal.Status created = null;
        boolean replaced = false;
        try {
            handle = ops.createExclusive(temporaryPath, MODE_0600);
            ReadReceiptAndroidCounterJournal.Status initial = ops.fstat(handle);
            requireStatus(initial, true);
            if (initial.size != 0) throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            created = initial;
            writeFull(handle, content);
            ReadReceiptAndroidCounterJournal.Status complete = ops.fstat(handle);
            requireStatus(complete, true);
            if (!sameIdentity(created, complete) || complete.size != content.length) {
                throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            }
            ops.fsync(handle);
            closeStrict(handle);
            handle = null;
            ops.replaceAtomic(temporaryPath, livePath);
            replaced = true;
            ops.fsyncDirectory(directory);
        } catch (ReadReceiptAndroidCounterJournal.IoFailure | RuntimeException exception) {
            closeQuietly(handle);
            if (!replaced && created != null) cleanupTemporary(temporaryPath, created);
            throw failure();
        }
    }

    private void cleanupTemporary(String path, ReadReceiptAndroidCounterJournal.Status expected) {
        try {
            if (ops.unlinkIfSame(path, expected)) ops.fsyncDirectory(directory);
        } catch (ReadReceiptAndroidCounterJournal.IoFailure | RuntimeException ignored) {
        }
    }

    private byte[] readExact(ReadReceiptAndroidCounterJournal.Handle handle, int size)
            throws ReadReceiptAndroidCounterJournal.IoFailure {
        if (size < EMPTY_SIZE || size > MAX_ENCODED_SIZE) {
            throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
        }
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < result.length) {
            int read = ops.read(handle, result, offset, result.length - offset);
            if (read <= 0 || read > result.length - offset) {
                throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            }
            offset += read;
        }
        return result;
    }

    private void writeFull(ReadReceiptAndroidCounterJournal.Handle handle, byte[] content)
            throws ReadReceiptAndroidCounterJournal.IoFailure {
        int offset = 0;
        while (offset < content.length) {
            int written = ops.write(handle, content, offset, content.length - offset);
            if (written <= 0 || written > content.length - offset) {
                throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
            }
            offset += written;
        }
    }

    private void requireStatus(ReadReceiptAndroidCounterJournal.Status status,
                               boolean allowEmpty)
            throws ReadReceiptAndroidCounterJournal.IoFailure {
        if (status == null || !status.regular || status.uid != expectedUid
                || status.mode != MODE_0600 || status.device < 0 || status.inode <= 0
                || status.size < 0 || status.size > MAX_ENCODED_SIZE
                || !allowEmpty && status.size < EMPTY_SIZE) {
            throw new ReadReceiptAndroidCounterJournal.IoFailure(false);
        }
    }

    private void closeStrict(ReadReceiptAndroidCounterJournal.Handle handle)
            throws ReadReceiptAndroidCounterJournal.IoFailure {
        if (handle != null) ops.close(handle);
    }

    private void closeQuietly(ReadReceiptAndroidCounterJournal.Handle handle) {
        if (handle == null) return;
        try {
            ops.close(handle);
        } catch (ReadReceiptAndroidCounterJournal.IoFailure | RuntimeException ignored) {
        }
    }

    private static boolean sameIdentity(ReadReceiptAndroidCounterJournal.Status left,
                                        ReadReceiptAndroidCounterJournal.Status right) {
        return left != null && right != null && left.regular && right.regular
                && left.uid == right.uid && left.mode == right.mode
                && left.device == right.device && left.inode == right.inode;
    }

    private static byte[] encodeEmpty() {
        return withDigest(payload(EMPTY_TAG, null));
    }

    private static byte[] encode(PendingBatchRecord record) {
        int tag = tag(record.state);
        byte[] encoded = withDigest(payload(tag, record));
        if (encoded.length > MAX_ENCODED_SIZE) throw failure();
        return encoded;
    }

    private static byte[] payload(int tag,
                                  PendingBatchRecord record) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(tag);
            if (tag != EMPTY_TAG) {
                writeInput(output, record.input);
                if (tag == TERMINAL_UNCERTAIN_TAG) {
                    writeCounterTarget(output, record.counterTarget);
                }
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

    private static PendingBatchRecord decode(byte[] encoded) {
        int payloadLength = encoded.length - 32;
        if (payloadLength < 6) throw failure();
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
            PendingBatchState state = state(tag);
            BatchInput batch = readInput(input);
            CounterTarget counter = tag == TERMINAL_UNCERTAIN_TAG
                    ? readCounterTarget(input) : null;
            if (input.available() != 0) throw failure();
            return new PendingBatchRecord(state, batch, counter);
        } catch (IOException | RuntimeException exception) {
            throw failure();
        }
    }

    private static void writeInput(DataOutputStream output,
                                   BatchInput input)
            throws IOException {
        if (input.events.size() > MAX_EVENTS) throw failure();
        writeUuid(output, input.batchId);
        output.writeLong(input.persistedAtMs);
        output.writeLong(input.persistedElapsedMs);
        writeUuid(output, input.captureSessionId);
        writeNullableUuid(output, input.captureBootId);
        writeNullableUuid(output, input.sourceEpochToken);
        output.writeInt(input.events.size());
        for (CapturedEvent event : input.events) {
            writeUuid(output, event.eventId);
            output.writeLong(event.chatId);
            output.writeLong(event.userId);
            output.writeLong(event.watermark);
        }
    }

    private static BatchInput readInput(DataInputStream input)
            throws IOException {
        UUID batchId = readUuid(input);
        long persistedAt = input.readLong();
        long persistedElapsed = input.readLong();
        UUID session = readUuid(input);
        UUID boot = readNullableUuid(input);
        UUID source = readNullableUuid(input);
        int count = input.readInt();
        if (count <= 0 || count > MAX_EVENTS) throw failure();
        List<CapturedEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            events.add(new CapturedEvent(readUuid(input),
                    input.readLong(), input.readLong(), input.readLong()));
        }
        return new BatchInput(batchId, persistedAt,
                persistedElapsed, session, boot, source, events);
    }

    private static void writeCounterTarget(DataOutputStream output,
                                           CounterTarget target)
            throws IOException {
        if (target == null) throw failure();
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

    private static CounterTarget readCounterTarget(DataInputStream input)
            throws IOException {
        UUID installation = readUuid(input);
        UUID epoch = readUuid(input);
        Counters counters = new Counters(
                input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong());
        return new CounterTarget(installation, epoch, counters);
    }

    private static void writeNullableUuid(DataOutputStream output, UUID value)
            throws IOException {
        output.writeBoolean(value != null);
        if (value != null) writeUuid(output, value);
    }

    private static UUID readNullableUuid(DataInputStream input) throws IOException {
        return input.readBoolean() ? readUuid(input) : null;
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static int tag(PendingBatchState state) {
        switch (state) {
            case PENDING:
                return PENDING_TAG;
            case COMMITTING:
                return COMMITTING_TAG;
            case TERMINAL_COMMITTED:
                return TERMINAL_COMMITTED_TAG;
            case TERMINAL_CONFIRMED_LOSS:
                return TERMINAL_CONFIRMED_LOSS_TAG;
            case TERMINAL_UNCERTAIN:
                return TERMINAL_UNCERTAIN_TAG;
            default:
                throw failure();
        }
    }

    private static PendingBatchState state(int tag) {
        switch (tag) {
            case PENDING_TAG:
                return PendingBatchState.PENDING;
            case COMMITTING_TAG:
                return PendingBatchState.COMMITTING;
            case TERMINAL_COMMITTED_TAG:
                return PendingBatchState.TERMINAL_COMMITTED;
            case TERMINAL_CONFIRMED_LOSS_TAG:
                return PendingBatchState.TERMINAL_CONFIRMED_LOSS;
            case TERMINAL_UNCERTAIN_TAG:
                return PendingBatchState.TERMINAL_UNCERTAIN;
            default:
                throw failure();
        }
    }

    private static void requireState(PendingBatchRecord current,
                                     BatchInput expected,
                                     PendingBatchState state) {
        if (current == null || current.state != state || !current.sameInput(expected)) {
            throw failure();
        }
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
}
