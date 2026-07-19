package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.util.UUID;

final class ReadReceiptAndroidStateProjection {
    private ReadReceiptAndroidStateProjection() {
    }

    static Stream decodeStream(ReadReceiptAndroidStartupStore.Rows rows)
            throws ReadReceiptAndroidStartupStore.Failure {
        requireSingleton(rows);
        return new Stream(uuid(rows.blobValue(1)), uuid(rows.blobValue(2)), rows.longValue(3),
                rows.longValue(4), rows.longValue(5), counters(rows, 6),
                rows.stringValue(12), nullableString(rows, 13));
    }

    static Lease decodeLease(ReadReceiptAndroidStartupStore.Rows rows)
            throws ReadReceiptAndroidStartupStore.Failure {
        requireSingleton(rows);
        return new Lease(uuid(rows.blobValue(1)), uuid(rows.blobValue(2)), rows.longValue(3),
                rows.longValue(4), rows.longValue(5), requiredBlob(rows, 6),
                rows.stringValue(7), nullableString(rows, 8), counters(rows, 9));
    }

    private static void requireSingleton(ReadReceiptAndroidStartupStore.Rows rows)
            throws ReadReceiptAndroidStartupStore.Failure {
        if (rows.longValue(0) != 1) throw new ReadReceiptAndroidStartupStore.Failure();
    }

    private static Counters counters(ReadReceiptAndroidStartupStore.Rows rows, int first)
            throws ReadReceiptAndroidStartupStore.Failure {
        return new Counters(rows.longValue(first), rows.longValue(first + 1),
                rows.longValue(first + 2), rows.longValue(first + 3),
                rows.longValue(first + 4), rows.longValue(first + 5));
    }

    private static String nullableString(ReadReceiptAndroidStartupStore.Rows rows, int column)
            throws ReadReceiptAndroidStartupStore.Failure {
        return rows.isNull(column) ? null : rows.stringValue(column);
    }

    private static byte[] requiredBlob(ReadReceiptAndroidStartupStore.Rows rows, int column)
            throws ReadReceiptAndroidStartupStore.Failure {
        byte[] value = rows.blobValue(column);
        if (value == null) throw new ReadReceiptAndroidStartupStore.Failure();
        return value.clone();
    }

    private static UUID uuid(byte[] value) throws ReadReceiptAndroidStartupStore.Failure {
        if (value == null || value.length != 16) {
            throw new ReadReceiptAndroidStartupStore.Failure();
        }
        long high = 0;
        long low = 0;
        for (int index = 0; index < 8; index++) {
            high = high << 8 | value[index] & 0xffL;
            low = low << 8 | value[index + 8] & 0xffL;
        }
        return new UUID(high, low);
    }

    static final class Stream {
        final UUID installationId;
        final UUID streamEpoch;
        final long nextSequence;
        final long lastAckedSequence;
        final long highestIssuedSequence;
        final Counters counters;
        final String status;
        final String poisonCategory;

        Stream(UUID installationId, UUID streamEpoch, long nextSequence,
               long lastAckedSequence, long highestIssuedSequence, Counters counters,
               String status, String poisonCategory) {
            this.installationId = installationId;
            this.streamEpoch = streamEpoch;
            this.nextSequence = nextSequence;
            this.lastAckedSequence = lastAckedSequence;
            this.highestIssuedSequence = highestIssuedSequence;
            this.counters = counters;
            this.status = status;
            this.poisonCategory = poisonCategory;
        }
    }

    static final class Lease {
        final UUID streamEpoch;
        final UUID leaseId;
        final long firstSequence;
        final long lastSequence;
        final long previousSequence;
        final byte[] digest;
        final String status;
        final String poisonCategory;
        final Counters counters;

        Lease(UUID streamEpoch, UUID leaseId, long firstSequence, long lastSequence,
              long previousSequence, byte[] digest, String status, String poisonCategory,
              Counters counters) {
            this.streamEpoch = streamEpoch;
            this.leaseId = leaseId;
            this.firstSequence = firstSequence;
            this.lastSequence = lastSequence;
            this.previousSequence = previousSequence;
            this.digest = digest;
            this.status = status;
            this.poisonCategory = poisonCategory;
            this.counters = counters;
        }
    }

    static final class Counters {
        final long confirmedDroppedEventCount;
        final long confirmedDroppedBatchCount;
        final long uncertainOutcomeEventCount;
        final long uncertainOutcomeBatchCount;
        final long captureDropEventCount;
        final long captureDropBatchCount;

        Counters(long confirmedDroppedEventCount, long confirmedDroppedBatchCount,
                 long uncertainOutcomeEventCount, long uncertainOutcomeBatchCount,
                 long captureDropEventCount, long captureDropBatchCount) {
            this.confirmedDroppedEventCount = confirmedDroppedEventCount;
            this.confirmedDroppedBatchCount = confirmedDroppedBatchCount;
            this.uncertainOutcomeEventCount = uncertainOutcomeEventCount;
            this.uncertainOutcomeBatchCount = uncertainOutcomeBatchCount;
            this.captureDropEventCount = captureDropEventCount;
            this.captureDropBatchCount = captureDropBatchCount;
        }
    }
}
