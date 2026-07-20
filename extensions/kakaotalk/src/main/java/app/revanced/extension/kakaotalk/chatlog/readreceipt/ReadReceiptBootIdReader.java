package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class ReadReceiptBootIdReader {
    private static final String PATH = "/proc/sys/kernel/random/boot_id";
    private static final int EXACT_SIZE = 37;

    private ReadReceiptBootIdReader() {
    }

    static UUID read(ReadReceiptFileOps fileOps) {
        ReadReceiptFileOps.Handle handle = null;
        UUID bootId = null;
        try {
            handle = fileOps.open(PATH, ReadReceiptFileOps.OpenKind.READ_EXISTING);
            ReadReceiptFileOps.FileStatus status = fileOps.fstat(handle);
            if (status.regular && !status.directory
                    && (status.size == 0L || status.size == EXACT_SIZE)) {
                byte[] content = readExact(fileOps, handle);
                bootId = content == null ? null : parse(content);
            }
        } catch (ReadReceiptFileOps.Failure | RuntimeException ignored) {
            bootId = null;
        } finally {
            if (handle != null) {
                try {
                    fileOps.close(handle);
                } catch (ReadReceiptFileOps.Failure ignored) {
                    bootId = null;
                }
            }
        }
        return bootId;
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
        if (content[content.length - 1] != '\n') return null;
        String encoded = new String(content, 0, content.length - 1, StandardCharsets.US_ASCII);
        if (!encoded.equals(encoded.toLowerCase(Locale.ROOT))) return null;
        try {
            UUID parsed = UUID.fromString(encoded);
            return parsed.toString().equals(encoded) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
