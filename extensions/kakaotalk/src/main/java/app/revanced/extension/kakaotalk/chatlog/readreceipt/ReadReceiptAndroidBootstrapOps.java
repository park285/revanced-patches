package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

final class ReadReceiptAndroidBootstrapOps implements ReadReceiptBootstrap.Ops {

    interface RandomSource {
        void nextBytes(byte[] bytes);

        UUID nextUuid();
    }

    interface Clock {
        long nowMillis();
    }

    interface DirectoryEntries {
        String[] list(String directory);
    }

    enum Presence {
        ABSENT,
        PRESENT,
        UNAVAILABLE
    }

    static final class ActivationContext {
        private final byte[] token;

        private ActivationContext(byte[] token) {
            this.token = token.clone();
        }

        byte[] token() {
            return token.clone();
        }
    }

    private static final String LIVE_BASENAME = "iris_read_receipts.db";
    private static final String TEMP_PREFIX = ".iris_read_receipts.db.tmp-";
    private static final int PRIVATE_MODE = 0600;
    private static final int MAX_STALE_TEMP_CLEANUPS = 8;

    private final ReadReceiptFileOps fileOps;
    private final ReadReceiptAndroidSqlite sqlite;
    private final ReadReceiptNativeFs nativeFs;
    private final RandomSource random;
    private final Clock clock;
    private final String noBackupDirectory;
    private final String liveDirectory;
    private final String livePath;
    private final int uid;
    private final byte[] installationId;
    private final DirectoryEntries directoryEntries;

    ReadReceiptAndroidBootstrapOps(
            ReadReceiptFileOps fileOps,
            ReadReceiptAndroidSqlite sqlite,
            ReadReceiptNativeFs nativeFs,
            RandomSource random,
            Clock clock,
            String noBackupDirectory,
            String liveDirectory,
            int uid,
            byte[] installationId,
            DirectoryEntries directoryEntries
    ) {
        this.fileOps = fileOps;
        this.sqlite = sqlite;
        this.nativeFs = nativeFs;
        this.random = random;
        this.clock = clock;
        this.noBackupDirectory = noBackupDirectory;
        this.liveDirectory = liveDirectory;
        this.livePath = liveDirectory + "/" + LIVE_BASENAME;
        this.uid = uid;
        this.installationId = installationId.clone();
        this.directoryEntries = directoryEntries;
    }

    static ActivationContext activate(
            ReadReceiptFileOps fileOps,
            ReadReceiptAndroidSqlite sqlite,
            ReadReceiptNativeFs nativeFs,
            RandomSource random,
            Clock clock,
            String noBackupDirectory,
            String liveDirectory,
            int uid
    ) {
        return activate(
                fileOps, sqlite, nativeFs, random, clock,
                noBackupDirectory, liveDirectory, uid,
                directory -> new File(directory).list()
        );
    }

    static ActivationContext activate(
            ReadReceiptFileOps fileOps,
            ReadReceiptAndroidSqlite sqlite,
            ReadReceiptNativeFs nativeFs,
            RandomSource random,
            Clock clock,
            String noBackupDirectory,
            String liveDirectory,
            int uid,
            DirectoryEntries directoryEntries
    ) {
        String livePath = liveDirectory + "/" + LIVE_BASENAME;
        Presence presence = presence(fileOps, livePath, uid);
        if (presence == Presence.UNAVAILABLE) return null;

        byte[] token;
        if (presence == Presence.PRESENT) {
            token = ReadReceiptMarkers.readExistingToken(fileOps, noBackupDirectory, uid);
        } else {
            byte[] candidate = new byte[32];
            try {
                random.nextBytes(candidate);
            } catch (RuntimeException ignored) {
                return null;
            }
            token = ReadReceiptMarkers.createOrReadInstallationToken(
                    fileOps, noBackupDirectory, uid, candidate);
        }
        if (token == null) return null;

        byte[] installationId;
        try {
            installationId = ReadReceiptAndroidSqlite.uuidBytes(
                    ReadReceiptProtocolV2.installationId(token));
        } catch (RuntimeException ignored) {
            return null;
        }
        ReadReceiptAndroidBootstrapOps ops = new ReadReceiptAndroidBootstrapOps(
                fileOps, sqlite, nativeFs, random, clock,
                noBackupDirectory, liveDirectory, uid, installationId, directoryEntries);
        if (presence == Presence.ABSENT && !ops.cleanupOwnedStaleTempsBeforeCreate()) return null;
        return ReadReceiptBootstrap.run(ops).activationReady()
                ? new ActivationContext(token)
                : null;
    }

    static Presence presence(ReadReceiptFileOps fileOps, String path, int uid) {
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(path, ReadReceiptFileOps.OpenKind.READ_EXISTING);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            return status.regular && status.uid == uid && status.mode == PRIVATE_MODE
                    ? Presence.PRESENT
                    : Presence.UNAVAILABLE;
        } catch (ReadReceiptFileOps.Failure failure) {
            return failure.reason() == ReadReceiptFileOps.FailureReason.NOT_FOUND
                    ? Presence.ABSENT
                    : Presence.UNAVAILABLE;
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                    return Presence.UNAVAILABLE;
                }
            }
        }
    }

    @Override
    public ReadReceiptBootstrap.LiveValidation validateLiveReadOnlyExact() {
        Presence presence = presence(fileOps, livePath, uid);
        if (presence == Presence.ABSENT) return ReadReceiptBootstrap.LiveValidation.ABSENT;
        if (presence != Presence.PRESENT) return ReadReceiptBootstrap.LiveValidation.UNSUPPORTED;
        return sqlite.classifyReadOnly(livePath, installationId)
                == ReadReceiptAndroidSqlite.Classification.EXACT
                ? ReadReceiptBootstrap.LiveValidation.EXACT
                : ReadReceiptBootstrap.LiveValidation.UNSUPPORTED;
    }

    @Override
    public ReadReceiptBootstrap.Attempt createTempExclusiveNoFollow0600()
            throws ReadReceiptBootstrap.Failure {
        byte[] suffix = new byte[16];
        try {
            random.nextBytes(suffix);
        } catch (RuntimeException ignored) {
            throw failure();
        }
        String tempPath = noBackupDirectory + "/" + TEMP_PREFIX + hex(suffix);
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(tempPath, ReadReceiptFileOps.OpenKind.CREATE_EXCLUSIVE);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            if (!status.regular || status.uid != uid || status.mode != PRIVATE_MODE
                    || status.size != 0 || status.inode <= 0 || status.device < 0) {
                throw failure();
            }
            AndroidAttempt attempt = new AndroidAttempt(
                    tempPath, TEMP_PREFIX + hex(suffix), status.device, status.inode, handle);
            handle = null;
            return attempt;
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            throw failure();
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                }
            }
        }
    }

    @Override
    public boolean verifyTempAndLiveDirectorySameDevice(ReadReceiptBootstrap.Attempt rawAttempt)
            throws ReadReceiptBootstrap.Failure {
        AndroidAttempt attempt = attempt(rawAttempt);
        ReadReceiptFileOps.Handle directory = null;
        try {
            directory = fileOps.open(liveDirectory, ReadReceiptFileOps.OpenKind.DIRECTORY);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(directory);
            fileOps.close(directory);
            directory = null;
            return status.directory && status.device == attempt.device;
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            throw failure();
        } finally {
            if (directory != null) {
                try {
                    fileOps.close(directory);
                } catch (ReadReceiptFileOps.Failure ignored) {
                }
            }
        }
    }

    @Override
    public void createRollbackJournalSchemaTransaction(ReadReceiptBootstrap.Attempt rawAttempt)
            throws ReadReceiptBootstrap.Failure {
        AndroidAttempt attempt = attempt(rawAttempt);
        UUID epoch;
        long now;
        try {
            epoch = random.nextUuid();
            now = clock.nowMillis();
        } catch (RuntimeException ignored) {
            throw failure();
        }
        if (epoch == null || now < 0 || !sqlite.createFresh(
                attempt.path, installationId, ReadReceiptAndroidSqlite.uuidBytes(epoch), now)
                || !matchesOpenAttempt(attempt) || !matchesAttempt(attempt)) {
            closeAttemptQuietly(attempt);
            throw failure();
        }
    }

    @Override
    public void closeCreatedDatabase(ReadReceiptBootstrap.Attempt rawAttempt)
            throws ReadReceiptBootstrap.Failure {
        closeAttempt(attempt(rawAttempt));
    }

    @Override
    public void fsyncTemp(ReadReceiptBootstrap.Attempt rawAttempt)
            throws ReadReceiptBootstrap.Failure {
        AndroidAttempt attempt = attempt(rawAttempt);
        requireOk(nativeFs.fsyncPath(attempt.path, attempt.device, attempt.inode));
    }

    @Override
    public boolean validateTempReadOnlyExact(ReadReceiptBootstrap.Attempt rawAttempt)
            throws ReadReceiptBootstrap.Failure {
        AndroidAttempt attempt = attempt(rawAttempt);
        if (!matchesAttempt(attempt)) return false;
        return sqlite.classifyReadOnly(attempt.path, installationId)
                == ReadReceiptAndroidSqlite.Classification.EXACT;
    }

    @Override
    public ReadReceiptBootstrap.PublishResult publishVerifiedTempInodeNoReplace(
            ReadReceiptBootstrap.Attempt rawAttempt
    ) throws ReadReceiptBootstrap.Failure {
        AndroidAttempt attempt = attempt(rawAttempt);
        ReadReceiptNativeFs.Result result = nativeFs.publish(
                attempt.path, liveDirectory, LIVE_BASENAME, attempt.device, attempt.inode);
        if (result == ReadReceiptNativeFs.Result.OK) {
            return ReadReceiptBootstrap.PublishResult.PUBLISHED;
        }
        if (result == ReadReceiptNativeFs.Result.EXISTS) {
            return ReadReceiptBootstrap.PublishResult.EXISTS;
        }
        throw failure();
    }

    @Override
    public void fsyncLiveDirectory() throws ReadReceiptBootstrap.Failure {
        requireOk(nativeFs.fsyncDirectory(liveDirectory));
    }

    @Override
    public void cleanupOwnedTempIfInodeMatchesAttempt(ReadReceiptBootstrap.Attempt rawAttempt)
            throws ReadReceiptBootstrap.Failure {
        AndroidAttempt attempt = attempt(rawAttempt);
        closeAttempt(attempt);
        requireOk(nativeFs.unlinkIfExactInode(
                noBackupDirectory, attempt.basename, attempt.device, attempt.inode));
    }

    @Override
    public void cleanupOwnedStaleTemps() throws ReadReceiptBootstrap.Failure {
        long directoryDevice = verifiedNoBackupDirectoryDevice();
        String[] names;
        try {
            names = directoryEntries.list(noBackupDirectory);
        } catch (RuntimeException ignored) {
            throw failure();
        }
        if (names == null) throw failure();
        Arrays.sort(names);
        int cleanupAttempts = 0;
        for (String name : names) {
            if (!validTempBasename(name)) continue;
            ReadReceiptFileOps.FileStatus status = ownedTempStatus(name, directoryDevice);
            if (status == null) throw failure();
            if (cleanupAttempts >= MAX_STALE_TEMP_CLEANUPS) throw failure();
            cleanupAttempts++;
            ReadReceiptNativeFs.Result result = nativeFs.unlinkIfExactInode(
                    noBackupDirectory, name, status.device, status.inode);
            if (result != ReadReceiptNativeFs.Result.OK
                    && result != ReadReceiptNativeFs.Result.NOT_FOUND) {
                throw failure();
            }
        }
    }

    @Override
    public void fsyncNoBackupDirectory() throws ReadReceiptBootstrap.Failure {
        requireOk(nativeFs.fsyncDirectory(noBackupDirectory));
    }

    private boolean matchesAttempt(AndroidAttempt attempt) throws ReadReceiptBootstrap.Failure {
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(attempt.path, ReadReceiptFileOps.OpenKind.READ_EXISTING);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            return status.regular && status.uid == uid && status.mode == PRIVATE_MODE
                    && status.device == attempt.device && status.inode == attempt.inode;
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            throw failure();
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                    throw failure();
                }
            }
        }
    }

    private boolean cleanupOwnedStaleTempsBeforeCreate() {
        boolean complete = true;
        try {
            cleanupOwnedStaleTemps();
        } catch (ReadReceiptBootstrap.Failure ignored) {
            complete = false;
        }
        try {
            fsyncNoBackupDirectory();
        } catch (ReadReceiptBootstrap.Failure ignored) {
            return false;
        }
        return complete;
    }

    private long verifiedNoBackupDirectoryDevice() throws ReadReceiptBootstrap.Failure {
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(noBackupDirectory, ReadReceiptFileOps.OpenKind.DIRECTORY);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            if (!status.directory || status.regular || status.uid != uid
                    || status.device < 0 || status.inode <= 0) {
                throw failure();
            }
            return status.device;
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            throw failure();
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                    throw failure();
                }
            }
        }
    }

    private ReadReceiptFileOps.FileStatus ownedTempStatus(
            String basename, long directoryDevice
    )
            throws ReadReceiptBootstrap.Failure {
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(
                    noBackupDirectory + "/" + basename,
                    ReadReceiptFileOps.OpenKind.READ_EXISTING
            );
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            return status.regular && !status.directory && status.uid == uid
                    && status.mode == PRIVATE_MODE && status.size >= 0
                    && status.device == directoryDevice && status.inode > 0
                    ? status
                    : null;
        } catch (ReadReceiptFileOps.Failure exception) {
            if (exception.reason() == ReadReceiptFileOps.FailureReason.NOT_FOUND) return null;
            throw failure();
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                    throw failure();
                }
            }
        }
    }

    private static boolean validTempBasename(String name) {
        if (name == null || !name.startsWith(TEMP_PREFIX)
                || name.length() != TEMP_PREFIX.length() + 32) {
            return false;
        }
        for (int index = TEMP_PREFIX.length(); index < name.length(); index++) {
            char value = name.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesOpenAttempt(AndroidAttempt attempt)
            throws ReadReceiptBootstrap.Failure {
        if (attempt.handle == null) return false;
        try {
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(attempt.handle);
            return status.regular && !status.directory && status.uid == uid
                    && status.mode == PRIVATE_MODE && status.size > 0
                    && status.device == attempt.device && status.inode == attempt.inode;
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            throw failure();
        }
    }

    private void closeAttempt(AndroidAttempt attempt) throws ReadReceiptBootstrap.Failure {
        ReadReceiptFileOps.Handle handle = attempt.handle;
        if (handle == null) return;
        attempt.handle = null;
        try {
            fileOps.close(handle);
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            throw failure();
        }
    }

    private void closeAttemptQuietly(AndroidAttempt attempt) {
        try {
            closeAttempt(attempt);
        } catch (ReadReceiptBootstrap.Failure ignored) {
        }
    }

    private static AndroidAttempt attempt(ReadReceiptBootstrap.Attempt attempt)
            throws ReadReceiptBootstrap.Failure {
        if (!(attempt instanceof AndroidAttempt)) throw failure();
        return (AndroidAttempt) attempt;
    }

    private static void requireOk(ReadReceiptNativeFs.Result result)
            throws ReadReceiptBootstrap.Failure {
        if (result != ReadReceiptNativeFs.Result.OK) throw failure();
    }

    private static String hex(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            encoded[index * 2] = alphabet[(bytes[index] >>> 4) & 0xf];
            encoded[index * 2 + 1] = alphabet[bytes[index] & 0xf];
        }
        return new String(encoded);
    }

    private static ReadReceiptBootstrap.Failure failure() {
        return new ReadReceiptBootstrap.Failure();
    }

    private static final class AndroidAttempt implements ReadReceiptBootstrap.Attempt {
        private final String path;
        private final String basename;
        private final long device;
        private final long inode;
        private ReadReceiptFileOps.Handle handle;

        private AndroidAttempt(String path, String basename, long device, long inode,
                               ReadReceiptFileOps.Handle handle) {
            this.path = path;
            this.basename = basename;
            this.device = device;
            this.inode = inode;
            this.handle = handle;
        }
    }

    static final class SecureRandomSource implements RandomSource {
        private final SecureRandom random = new SecureRandom();

        @Override
        public void nextBytes(byte[] bytes) {
            random.nextBytes(bytes);
        }

        @Override
        public UUID nextUuid() {
            return UUID.randomUUID();
        }
    }
}
