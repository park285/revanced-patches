package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.Arrays;

final class ReadReceiptMarkers {

    private static final String ACTIVATION_MARKER = "iris_read_receipt_v2.enabled";
    private static final String INSTALLATION_TOKEN = "iris_read_receipt_v2.token";
    private static final byte[] ACTIVATION_BYTES = {
            'e', 'n', 'a', 'b', 'l', 'e', 'd', '-', 'v', '2', '\n'
    };
    private static final int PRIVATE_MODE = 0600;
    private static final int TOKEN_SIZE = 32;
    private static final Object TOKEN_CREATION_LOCK = new Object();

    private ReadReceiptMarkers() {
    }

    static boolean isActivationEnabled(ReadReceiptFileOps fileOps, String directory, int uid) {
        byte[] bytes = readValidated(
                fileOps,
                activationMarkerPath(directory),
                uid,
                ACTIVATION_BYTES.length
        );
        return bytes != null && Arrays.equals(ACTIVATION_BYTES, bytes);
    }

    static byte[] readExistingToken(ReadReceiptFileOps fileOps, String directory, int uid) {
        return readValidated(fileOps, installationTokenPath(directory), uid, TOKEN_SIZE);
    }

    static byte[] createOrReadInstallationToken(
            ReadReceiptFileOps fileOps,
            String directory,
            int uid,
            byte[] candidate
    ) {
        if (candidate == null || candidate.length != TOKEN_SIZE) {
            return null;
        }
        byte[] candidateSnapshot = candidate.clone();
        synchronized (TOKEN_CREATION_LOCK) {
            return createOrReadInstallationTokenLocked(fileOps, directory, uid, candidateSnapshot);
        }
    }

    private static byte[] createOrReadInstallationTokenLocked(
            ReadReceiptFileOps fileOps,
            String directory,
            int uid,
            byte[] candidateSnapshot
    ) {
        final ReadReceiptFileOps.Handle tokenHandle;
        try {
            tokenHandle = fileOps.open(
                    installationTokenPath(directory),
                    ReadReceiptFileOps.OpenKind.CREATE_EXCLUSIVE
            );
        } catch (ReadReceiptFileOps.Failure failure) {
            if (failure.reason() == ReadReceiptFileOps.FailureReason.EXISTS) {
                return readExistingToken(fileOps, directory, uid);
            }
            return null;
        }

        boolean tokenDurable = false;
        try {
            ReadReceiptFileOps.FileStatus initial = fileOps.fstat(tokenHandle);
            if (validCreatedToken(initial, uid, 0)
                    && fileOps.write(tokenHandle, candidateSnapshot, 0, TOKEN_SIZE) == TOKEN_SIZE
                    && validCreatedToken(fileOps.fstat(tokenHandle), uid, TOKEN_SIZE)) {
                fileOps.fsync(tokenHandle);
                tokenDurable = true;
            }
        } catch (ReadReceiptFileOps.Failure ignored) {
            tokenDurable = false;
        }
        try {
            fileOps.close(tokenHandle);
        } catch (ReadReceiptFileOps.Failure ignored) {
            tokenDurable = false;
        }
        if (!tokenDurable) {
            return null;
        }

        final ReadReceiptFileOps.Handle directoryHandle;
        try {
            directoryHandle = fileOps.open(directory, ReadReceiptFileOps.OpenKind.DIRECTORY);
        } catch (ReadReceiptFileOps.Failure ignored) {
            return null;
        }

        boolean directoryDurable = false;
        try {
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(directoryHandle);
            if (!status.regular && status.directory && status.uid == uid
                    && (status.mode & 0077) == 0 && status.device >= 0 && status.inode > 0) {
                fileOps.fsync(directoryHandle);
                directoryDurable = true;
            }
        } catch (ReadReceiptFileOps.Failure ignored) {
            directoryDurable = false;
        }
        try {
            fileOps.close(directoryHandle);
        } catch (ReadReceiptFileOps.Failure ignored) {
            directoryDurable = false;
        }
        return directoryDurable ? candidateSnapshot.clone() : null;
    }

    private static boolean validCreatedToken(
            ReadReceiptFileOps.FileStatus status,
            int uid,
            int expectedSize
    ) {
        return status.regular && !status.directory && status.uid == uid
                && status.mode == PRIVATE_MODE && status.size == expectedSize
                && status.device >= 0 && status.inode > 0;
    }

    static String activationMarkerPath(String directory) {
        return directory + "/" + ACTIVATION_MARKER;
    }

    static String installationTokenPath(String directory) {
        return directory + "/" + INSTALLATION_TOKEN;
    }

    private static byte[] readValidated(
            ReadReceiptFileOps fileOps,
            String path,
            int uid,
            int exactSize
    ) {
        final ReadReceiptFileOps.Handle handle;
        try {
            handle = fileOps.open(path, ReadReceiptFileOps.OpenKind.READ_EXISTING);
        } catch (ReadReceiptFileOps.Failure ignored) {
            return null;
        }

        byte[] result = null;
        try {
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            if (status.regular && status.uid == uid && status.mode == PRIVATE_MODE && status.size == exactSize) {
                result = readExact(fileOps, handle, exactSize);
            }
        } catch (ReadReceiptFileOps.Failure ignored) {
            result = null;
        }

        try {
            fileOps.close(handle);
        } catch (ReadReceiptFileOps.Failure ignored) {
            return null;
        }
        return result;
    }

    private static byte[] readExact(
            ReadReceiptFileOps fileOps,
            ReadReceiptFileOps.Handle handle,
            int size
    ) throws ReadReceiptFileOps.Failure {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = fileOps.read(handle, bytes, offset, size - offset);
            if (read <= 0) {
                return null;
            }
            offset += read;
        }
        byte[] extra = new byte[1];
        return fileOps.read(handle, extra, 0, 1) == 0 ? bytes : null;
    }
}
