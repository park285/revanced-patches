package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.system.OsConstants;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ReadReceiptMarkersTest {

    private static final int APP_UID = 12001;
    private static final String DIRECTORY = "/private/no-backup";
    private static final byte[] MARKER = ascii("enabled-v2\n");

    @Test
    public void descriptorValidationRejectsInvalidFilesAndRetainsOpenedIdentity() {
        Node validMarker = Node.regular(APP_UID, 0600, MARKER);
        Node[] invalidMarkers = {
                Node.directory(APP_UID, 0600, MARKER),
                Node.regular(APP_UID + 1, 0600, MARKER),
                Node.regular(APP_UID, 0640, MARKER),
                Node.regular(APP_UID, 0600, ascii("enabled-v2")),
                Node.regular(APP_UID, 0600, ascii("disabled-v2\n")),
        };

        for (Node invalid : invalidMarkers) {
            FakeFileOps fileOps = new FakeFileOps();
            fileOps.put(ReadReceiptMarkers.activationMarkerPath(DIRECTORY), invalid);

            assertFalse(ReadReceiptMarkers.isActivationEnabled(fileOps, DIRECTORY, APP_UID));
            assertEquals(1, fileOps.readOpens);
            assertEquals(1, fileOps.closes);
        }

        FakeFileOps symlink = new FakeFileOps();
        symlink.failReadOpen = true;
        assertFalse(ReadReceiptMarkers.isActivationEnabled(symlink, DIRECTORY, APP_UID));
        assertEquals(1, symlink.readOpens);
        assertEquals(0, symlink.fstats);

        FakeFileOps replaced = new FakeFileOps();
        String markerPath = ReadReceiptMarkers.activationMarkerPath(DIRECTORY);
        replaced.put(markerPath, validMarker);
        replaced.replacePathOnFstat = Node.regular(APP_UID, 0600, ascii("disabled-v2\n"));
        assertTrue(ReadReceiptMarkers.isActivationEnabled(replaced, DIRECTORY, APP_UID));
        assertEquals(1, replaced.readOpens);
        assertEquals(1, replaced.fstats);
        assertEquals(1, replaced.closes);

        byte[] token = tokenBytes(7);
        FakeFileOps existingToken = new FakeFileOps();
        existingToken.put(ReadReceiptMarkers.installationTokenPath(DIRECTORY), Node.regular(APP_UID, 0600, token));
        assertTrue(Arrays.equals(token, ReadReceiptMarkers.readExistingToken(existingToken, DIRECTORY, APP_UID)));

        FakeFileOps shortToken = new FakeFileOps();
        shortToken.put(
                ReadReceiptMarkers.installationTokenPath(DIRECTORY),
                Node.regular(APP_UID, 0600, new byte[31])
        );
        assertNull(ReadReceiptMarkers.readExistingToken(shortToken, DIRECTORY, APP_UID));
    }

    @Test
    public void exclusiveTokenCreationRequiresOneFullWriteAndBothSyncs() {
        byte[] candidate = tokenBytes(19);

        FakeFileOps success = new FakeFileOps();
        assertTrue(Arrays.equals(candidate, ReadReceiptMarkers.createOrReadInstallationToken(
                success, DIRECTORY, APP_UID, candidate
        )));
        assertEquals(1, success.createOpens);
        assertEquals(1, success.writeCalls);
        assertEquals(32, success.writtenBytes);
        assertEquals(1, success.fileSyncs);
        assertEquals(1, success.directoryOpens);
        assertEquals(1, success.directorySyncs);

        FakeFileOps shortWrite = new FakeFileOps();
        shortWrite.maximumWrite = 31;
        assertNull(ReadReceiptMarkers.createOrReadInstallationToken(shortWrite, DIRECTORY, APP_UID, candidate));
        assertEquals(1, shortWrite.writeCalls);
        assertEquals(0, shortWrite.fileSyncs);
        assertEquals(0, shortWrite.directoryOpens);

        FakeFileOps fileSyncFailure = new FakeFileOps();
        fileSyncFailure.failFileSync = true;
        assertNull(ReadReceiptMarkers.createOrReadInstallationToken(
                fileSyncFailure, DIRECTORY, APP_UID, candidate
        ));
        assertEquals(1, fileSyncFailure.fileSyncs);
        assertEquals(0, fileSyncFailure.directoryOpens);

        FakeFileOps directorySyncFailure = new FakeFileOps();
        directorySyncFailure.failDirectorySync = true;
        assertNull(ReadReceiptMarkers.createOrReadInstallationToken(
                directorySyncFailure, DIRECTORY, APP_UID, candidate
        ));
        assertEquals(1, directorySyncFailure.directoryOpens);
        assertEquals(1, directorySyncFailure.directorySyncs);

        FakeFileOps arbitraryErrno = new FakeFileOps();
        arbitraryErrno.createFailure = ReadReceiptFileOps.FailureReason.IO;
        assertNull(ReadReceiptMarkers.createOrReadInstallationToken(
                arbitraryErrno, DIRECTORY, APP_UID, candidate
        ));
        assertEquals(0, arbitraryErrno.readOpens);

        byte[] winner = tokenBytes(41);
        FakeFileOps exists = new FakeFileOps();
        exists.put(ReadReceiptMarkers.installationTokenPath(DIRECTORY), Node.regular(APP_UID, 0600, winner));
        assertTrue(Arrays.equals(winner, ReadReceiptMarkers.createOrReadInstallationToken(
                exists, DIRECTORY, APP_UID, candidate
        )));
        assertEquals(1, exists.createOpens);
        assertEquals(1, exists.readOpens);
        assertEquals(0, exists.writeCalls);
        assertEquals(0, exists.fileSyncs);
        assertEquals(0, exists.directoryOpens);
    }

    @Test
    public void twoCreatorsShareExclusiveWinnerAndAndroidFlagsAreExact() {
        byte[] winnerCandidate = tokenBytes(61);
        byte[] loserCandidate = tokenBytes(93);
        FakeFileOps shared = new FakeFileOps();

        byte[] winner = ReadReceiptMarkers.createOrReadInstallationToken(
                shared, DIRECTORY, APP_UID, winnerCandidate
        );
        byte[] loser = ReadReceiptMarkers.createOrReadInstallationToken(
                shared, DIRECTORY, APP_UID, loserCandidate
        );

        assertTrue(Arrays.equals(winnerCandidate, winner));
        assertTrue(Arrays.equals(winner, loser));
        assertEquals(2, shared.createOpens);
        assertEquals(1, shared.writeCalls);
        assertEquals(1, shared.readOpens);
        assertEquals(1, shared.fileSyncs);
        assertEquals(1, shared.directorySyncs);

        assertEquals(
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                AndroidReadReceiptFileOps.flagsFor(ReadReceiptFileOps.OpenKind.READ_EXISTING)
        );
        assertEquals(
                OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                        | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                AndroidReadReceiptFileOps.flagsFor(ReadReceiptFileOps.OpenKind.CREATE_EXCLUSIVE)
        );
        assertEquals(
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW
                        | AndroidReadReceiptFileOps.DIRECTORY_FLAG,
                AndroidReadReceiptFileOps.flagsFor(ReadReceiptFileOps.OpenKind.DIRECTORY)
        );
        assertEquals(0600, AndroidReadReceiptFileOps.modeFor(ReadReceiptFileOps.OpenKind.CREATE_EXCLUSIVE));
        assertEquals(0, AndroidReadReceiptFileOps.modeFor(ReadReceiptFileOps.OpenKind.READ_EXISTING));
        assertEquals(ReadReceiptFileOps.FailureReason.EXISTS,
                AndroidReadReceiptFileOps.classifyErrno(17, 17, 2));
        assertEquals(ReadReceiptFileOps.FailureReason.NOT_FOUND,
                AndroidReadReceiptFileOps.classifyErrno(2, 17, 2));
        assertEquals(ReadReceiptFileOps.FailureReason.IO,
                AndroidReadReceiptFileOps.classifyErrno(5, 17, 2));
        assertEquals("IO", new ReadReceiptFileOps.Failure(ReadReceiptFileOps.FailureReason.IO).getMessage());
    }

    @Test
    public void androidDirectoryOpenUsesBionicODirectoryValue() {
        assertEquals(0x4000, AndroidReadReceiptFileOps.DIRECTORY_FLAG);
        assertEquals(
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW | 0x4000,
                AndroidReadReceiptFileOps.flagsFor(ReadReceiptFileOps.OpenKind.DIRECTORY)
        );
    }

    @Test
    public void tokenCreationUsesImmutableCandidateSnapshot() {
        byte[] candidate = tokenBytes(111);
        byte[] expected = candidate.clone();
        FakeFileOps fileOps = new FakeFileOps();
        fileOps.beforeWrite = () -> Arrays.fill(candidate, (byte) 0);

        byte[] returned = ReadReceiptMarkers.createOrReadInstallationToken(
                fileOps, DIRECTORY, APP_UID, candidate
        );

        assertTrue(Arrays.equals(expected, returned));
        assertTrue(Arrays.equals(
                expected,
                fileOps.paths.get(ReadReceiptMarkers.installationTokenPath(DIRECTORY)).bytes
        ));
    }

    @Test
    public void descriptorGrowthAfterFstatFailsMarkerAndTokenValidation() {
        FakeFileOps marker = new FakeFileOps();
        marker.put(ReadReceiptMarkers.activationMarkerPath(DIRECTORY), Node.regular(APP_UID, 0600, MARKER));
        marker.appendAfterFstat = true;
        assertFalse(ReadReceiptMarkers.isActivationEnabled(marker, DIRECTORY, APP_UID));
        assertEquals(1, marker.readOpens);

        FakeFileOps token = new FakeFileOps();
        token.put(
                ReadReceiptMarkers.installationTokenPath(DIRECTORY),
                Node.regular(APP_UID, 0600, tokenBytes(5))
        );
        token.appendAfterFstat = true;
        assertNull(ReadReceiptMarkers.readExistingToken(token, DIRECTORY, APP_UID));
        assertEquals(1, token.readOpens);
    }

    @Test
    public void existingTokenDirectlyRejectsEveryInvalidDescriptorCase() {
        byte[] validBytes = tokenBytes(29);
        Node[] invalidTokens = {
                Node.directory(APP_UID, 0600, validBytes),
                Node.regular(APP_UID + 1, 0600, validBytes),
                Node.regular(APP_UID, 0640, validBytes),
                Node.regular(APP_UID, 0600, new byte[31]),
                Node.regular(APP_UID, 0600, new byte[33]),
        };

        for (Node invalid : invalidTokens) {
            FakeFileOps fileOps = new FakeFileOps();
            fileOps.put(ReadReceiptMarkers.installationTokenPath(DIRECTORY), invalid);
            assertNull(ReadReceiptMarkers.readExistingToken(fileOps, DIRECTORY, APP_UID));
            assertEquals(1, fileOps.readOpens);
            assertEquals(1, fileOps.closes);
        }

        FakeFileOps symlink = new FakeFileOps();
        symlink.failReadOpen = true;
        assertNull(ReadReceiptMarkers.readExistingToken(symlink, DIRECTORY, APP_UID));
        assertEquals(1, symlink.readOpens);
        assertEquals(0, symlink.fstats);

        FakeFileOps replaced = new FakeFileOps();
        String path = ReadReceiptMarkers.installationTokenPath(DIRECTORY);
        replaced.put(path, Node.regular(APP_UID, 0600, validBytes));
        replaced.replacePathOnFstat = Node.regular(APP_UID, 0600, tokenBytes(87));
        assertTrue(Arrays.equals(validBytes, ReadReceiptMarkers.readExistingToken(replaced, DIRECTORY, APP_UID)));
        assertEquals(1, replaced.readOpens);
        assertEquals(1, replaced.fstats);
    }

    @Test
    public void concurrentCreatorsSerializeUntilWinnerIsDirectoryDurable() throws Exception {
        byte[] winnerCandidate = tokenBytes(37);
        byte[] loserCandidate = tokenBytes(73);
        ConcurrentRaceFileOps fileOps = new ConcurrentRaceFileOps();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch loserInvoked = new CountDownLatch(1);
        try {
            Future<byte[]> winner = executor.submit(() -> ReadReceiptMarkers.createOrReadInstallationToken(
                    fileOps, DIRECTORY, APP_UID, winnerCandidate
            ));
            assertTrue(fileOps.fileSyncEntered.await(2, TimeUnit.SECONDS));

            Future<byte[]> loser = executor.submit(() -> {
                loserInvoked.countDown();
                return ReadReceiptMarkers.createOrReadInstallationToken(
                        fileOps, DIRECTORY, APP_UID, loserCandidate
                );
            });
            assertTrue(loserInvoked.await(2, TimeUnit.SECONDS));
            assertFalse(loser.isDone());
            assertEquals(1, fileOps.createOpens.get());
            assertEquals(0, fileOps.readOpens.get());

            fileOps.releaseFileSync.countDown();
            byte[] winnerResult = winner.get(2, TimeUnit.SECONDS);
            byte[] loserResult = loser.get(2, TimeUnit.SECONDS);

            assertTrue(Arrays.equals(winnerCandidate, winnerResult));
            assertTrue(Arrays.equals(winnerResult, loserResult));
            assertEquals(2, fileOps.createOpens.get());
            assertEquals(1, fileOps.writes.get());
            assertEquals(1, fileOps.readOpens.get());
            assertTrue(fileOps.directorySynced.get());
            assertFalse(fileOps.readBeforeDirectorySync.get());
        } finally {
            fileOps.releaseFileSync.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static byte[] tokenBytes(int seed) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return bytes;
    }

    private static byte[] ascii(String value) {
        byte[] bytes = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            bytes[index] = (byte) value.charAt(index);
        }
        return bytes;
    }

    private static final class FakeFileOps implements ReadReceiptFileOps {
        private final Map<String, Node> paths = new HashMap<>();
        private boolean failReadOpen;
        private ReadReceiptFileOps.FailureReason createFailure;
        private int maximumWrite = Integer.MAX_VALUE;
        private boolean failFileSync;
        private boolean failDirectorySync;
        private boolean appendAfterFstat;
        private Runnable beforeWrite;
        private Node replacePathOnFstat;
        private int readOpens;
        private int createOpens;
        private int directoryOpens;
        private int fstats;
        private int closes;
        private int writeCalls;
        private int writtenBytes;
        private int fileSyncs;
        private int directorySyncs;

        private void put(String path, Node node) {
            paths.put(path, node);
        }

        @Override
        public Handle open(String path, OpenKind kind) throws Failure {
            if (kind == OpenKind.CREATE_EXCLUSIVE) {
                createOpens++;
                if (createFailure != null) {
                    throw new Failure(createFailure);
                }
                if (paths.containsKey(path)) {
                    throw new Failure(FailureReason.EXISTS);
                }
                Node node = Node.regular(APP_UID, 0600, new byte[0]);
                paths.put(path, node);
                return new FakeHandle(path, node, kind);
            }
            if (kind == OpenKind.DIRECTORY) {
                directoryOpens++;
                return new FakeHandle(path, Node.directory(APP_UID, 0700, new byte[0]), kind);
            }
            readOpens++;
            if (failReadOpen) {
                throw new Failure(FailureReason.IO);
            }
            Node node = paths.get(path);
            if (node == null) {
                throw new Failure(FailureReason.IO);
            }
            return new FakeHandle(path, node, kind);
        }

        @Override
        public FileStatus fstat(Handle handle) {
            fstats++;
            FakeHandle fakeHandle = (FakeHandle) handle;
            if (replacePathOnFstat != null) {
                paths.put(fakeHandle.path, replacePathOnFstat);
            }
            Node node = fakeHandle.node;
            int reportedSize = node.bytes.length;
            if (appendAfterFstat) {
                node.bytes = Arrays.copyOf(node.bytes, node.bytes.length + 1);
            }
            return new FileStatus(node.regular, !node.regular, node.uid, node.mode,
                    reportedSize, 1, 1);
        }

        @Override
        public int read(Handle handle, byte[] bytes, int offset, int count) {
            FakeHandle fakeHandle = (FakeHandle) handle;
            int remaining = fakeHandle.node.bytes.length - fakeHandle.offset;
            int copied = Math.min(count, remaining);
            if (copied == 0) {
                return 0;
            }
            System.arraycopy(fakeHandle.node.bytes, fakeHandle.offset, bytes, offset, copied);
            fakeHandle.offset += copied;
            return copied;
        }

        @Override
        public int write(Handle handle, byte[] bytes, int offset, int count) {
            writeCalls++;
            if (beforeWrite != null) {
                beforeWrite.run();
            }
            int copied = Math.min(count, maximumWrite);
            FakeHandle fakeHandle = (FakeHandle) handle;
            fakeHandle.node.bytes = new byte[copied];
            System.arraycopy(bytes, offset, fakeHandle.node.bytes, 0, copied);
            writtenBytes += copied;
            return copied;
        }

        @Override
        public void fsync(Handle handle) throws Failure {
            FakeHandle fakeHandle = (FakeHandle) handle;
            if (fakeHandle.kind == OpenKind.DIRECTORY) {
                directorySyncs++;
                if (failDirectorySync) {
                    throw new Failure(FailureReason.IO);
                }
                return;
            }
            fileSyncs++;
            if (failFileSync) {
                throw new Failure(FailureReason.IO);
            }
        }

        @Override
        public void close(Handle handle) {
            closes++;
        }
    }

    private static final class ConcurrentRaceFileOps implements ReadReceiptFileOps {
        private final ConcurrentHashMap<String, Node> paths = new ConcurrentHashMap<>();
        private final CountDownLatch fileSyncEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFileSync = new CountDownLatch(1);
        private final AtomicInteger createOpens = new AtomicInteger();
        private final AtomicInteger readOpens = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicBoolean directorySynced = new AtomicBoolean();
        private final AtomicBoolean readBeforeDirectorySync = new AtomicBoolean();

        @Override
        public Handle open(String path, OpenKind kind) throws Failure {
            if (kind == OpenKind.CREATE_EXCLUSIVE) {
                createOpens.incrementAndGet();
                Node node = Node.regular(APP_UID, 0600, new byte[0]);
                Node existing = paths.putIfAbsent(path, node);
                if (existing != null) {
                    throw new Failure(FailureReason.EXISTS);
                }
                return new FakeHandle(path, node, kind);
            }
            if (kind == OpenKind.DIRECTORY) {
                return new FakeHandle(path, Node.directory(APP_UID, 0700, new byte[0]), kind);
            }
            readOpens.incrementAndGet();
            if (!directorySynced.get()) {
                readBeforeDirectorySync.set(true);
            }
            Node node = paths.get(path);
            if (node == null) {
                throw new Failure(FailureReason.IO);
            }
            return new FakeHandle(path, node, kind);
        }

        @Override
        public FileStatus fstat(Handle handle) {
            Node node = ((FakeHandle) handle).node;
            synchronized (node) {
                return new FileStatus(node.regular, !node.regular, node.uid, node.mode,
                        node.bytes.length, 1, 1);
            }
        }

        @Override
        public int read(Handle handle, byte[] bytes, int offset, int count) {
            FakeHandle fakeHandle = (FakeHandle) handle;
            synchronized (fakeHandle.node) {
                int copied = Math.min(count, fakeHandle.node.bytes.length - fakeHandle.offset);
                if (copied == 0) {
                    return 0;
                }
                System.arraycopy(fakeHandle.node.bytes, fakeHandle.offset, bytes, offset, copied);
                fakeHandle.offset += copied;
                return copied;
            }
        }

        @Override
        public int write(Handle handle, byte[] bytes, int offset, int count) {
            writes.incrementAndGet();
            Node node = ((FakeHandle) handle).node;
            synchronized (node) {
                node.bytes = Arrays.copyOfRange(bytes, offset, offset + count);
            }
            return count;
        }

        @Override
        public void fsync(Handle handle) throws Failure {
            if (((FakeHandle) handle).kind == OpenKind.DIRECTORY) {
                directorySynced.set(true);
                return;
            }
            fileSyncEntered.countDown();
            try {
                if (!releaseFileSync.await(2, TimeUnit.SECONDS)) {
                    throw new Failure(FailureReason.IO);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                throw new Failure(FailureReason.IO);
            }
        }

        @Override
        public void close(Handle handle) {
        }
    }

    private static final class FakeHandle implements ReadReceiptFileOps.Handle {
        private final String path;
        private final Node node;
        private final ReadReceiptFileOps.OpenKind kind;
        private int offset;

        private FakeHandle(String path, Node node, ReadReceiptFileOps.OpenKind kind) {
            this.path = path;
            this.node = node;
            this.kind = kind;
        }
    }

    private static final class Node {
        private final boolean regular;
        private final int uid;
        private final int mode;
        private byte[] bytes;

        private Node(boolean regular, int uid, int mode, byte[] bytes) {
            this.regular = regular;
            this.uid = uid;
            this.mode = mode;
            this.bytes = bytes.clone();
        }

        private static Node regular(int uid, int mode, byte[] bytes) {
            return new Node(true, uid, mode, bytes);
        }

        private static Node directory(int uid, int mode, byte[] bytes) {
            return new Node(false, uid, mode, bytes);
        }
    }
}
