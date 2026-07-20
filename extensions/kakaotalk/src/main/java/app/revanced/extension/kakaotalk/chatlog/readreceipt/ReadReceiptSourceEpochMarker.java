package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class ReadReceiptSourceEpochMarker {
    private static final String BASENAME = "iris_source_epoch_v1.marker";
    private static final String PREFIX = "version=1\nsource_epoch_token=";
    private static final int EXACT_SIZE = 66;
    private static final int PRIVATE_MODE = 0600;

    private ReadReceiptSourceEpochMarker() {
    }

    static UUID read(ReadReceiptFileOps fileOps, String directory, int uid) {
        ReadReceiptFileOps.Handle handle = null;
        try {
            handle = fileOps.open(directory + "/" + BASENAME,
                    ReadReceiptFileOps.OpenKind.READ_EXISTING);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            if (!status.regular || status.directory || status.uid != uid
                    || status.mode != PRIVATE_MODE || status.size != EXACT_SIZE) {
                return null;
            }
            byte[] content = readExact(fileOps, handle);
            return content == null ? null : parse(content);
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            return null;
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                    return null;
                }
            }
        }
    }

    private static byte[] readExact(
            ReadReceiptFileOps fileOps,
            ReadReceiptFileOps.Handle handle
    ) throws ReadReceiptFileOps.Failure {
        byte[] content = new byte[EXACT_SIZE];
        int offset = 0;
        while (offset < content.length) {
            int count = fileOps.read(handle, content, offset, content.length - offset);
            if (count <= 0) return null;
            offset += count;
        }
        return fileOps.read(handle, new byte[1], 0, 1) == 0 ? content : null;
    }

    private static UUID parse(byte[] content) {
        String value = new String(content, StandardCharsets.US_ASCII);
        if (!value.startsWith(PREFIX) || value.charAt(value.length() - 1) != '\n') return null;
        String encoded = value.substring(PREFIX.length(), value.length() - 1);
        if (encoded.length() != 36 || !encoded.equals(encoded.toLowerCase(Locale.ROOT))) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(encoded);
            return parsed.toString().equals(encoded) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
