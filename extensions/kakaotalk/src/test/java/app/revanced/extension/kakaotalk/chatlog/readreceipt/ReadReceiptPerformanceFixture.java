package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class ReadReceiptPerformanceFixture {
    static final int NEAR_CAP_ROWS = 250_000;
    static final int TRANSFER_ROWS = 256;
    private static final UUID NEAR_CAP_BATCH_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NEAR_CAP_CAPTURE_SESSION_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TRANSFER_BATCH_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TRANSFER_CAPTURE_SESSION_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    private ReadReceiptPerformanceFixture() {
    }

    static ReadReceiptProtocolV2.Metadata seedNearCap(String databasePath, byte[] epoch) {
        SQLiteDatabase database = null;
        long now = System.currentTimeMillis();
        ReadReceiptProtocolV2.Metadata metadata = new ReadReceiptProtocolV2.Metadata(
                NEAR_CAP_BATCH_ID, 1L, NEAR_CAP_ROWS, NEAR_CAP_ROWS, now, 1L,
                NEAR_CAP_CAPTURE_SESSION_ID, null, null);
        try {
            database = SQLiteDatabase.openDatabase(databasePath, null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            database.setForeignKeyConstraintsEnabled(true);
            database.beginTransaction();
            byte[] batchId = ReadReceiptAndroidSqlite.uuidBytes(metadata.batchId);
            database.execSQL(
                    "INSERT INTO read_receipt_batches (stream_epoch, batch_id, first_sequence, "
                            + "last_sequence, event_count, persisted_at_ms, "
                            + "persisted_elapsed_ms, capture_session_id, capture_boot_id, "
                            + "source_epoch_token, metadata_digest, created_at_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)",
                    new Object[]{epoch, batchId, metadata.firstSequence, metadata.lastSequence,
                            metadata.eventCount, metadata.persistedAtMs,
                            metadata.persistedElapsedMs,
                            ReadReceiptAndroidSqlite.uuidBytes(metadata.captureSessionId),
                            ReadReceiptProtocolV2.metadataDigest(metadata), now});
            database.execSQL(
                    "WITH RECURSIVE n(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM n WHERE v < ?) "
                            + "INSERT INTO read_receipt_outbox "
                            + "(stream_epoch, sequence, event_id, batch_id, chat_id, user_id, "
                            + "watermark) SELECT ?, v, randomblob(16), ?, 1, 1, v FROM n",
                    new Object[]{NEAR_CAP_ROWS, epoch, batchId});
            database.execSQL(
                    "UPDATE read_receipt_stream_state SET next_sequence = ?, "
                            + "highest_issued_sequence = ?, updated_at_ms = ? "
                            + "WHERE singleton = 1",
                    new Object[]{NEAR_CAP_ROWS + 1L, NEAR_CAP_ROWS, now});
            database.setTransactionSuccessful();
        } finally {
            if (database != null) {
                try {
                    database.endTransaction();
                } catch (RuntimeException ignored) {
                }
                database.close();
            }
        }
        return metadata;
    }

    static void verifyNearCap(String databasePath, ReadReceiptProtocolV2.Metadata expected) {
        SQLiteDatabase database = null;
        Cursor batch = null;
        Cursor foreignKeys = null;
        try {
            database = SQLiteDatabase.openDatabase(databasePath, null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            batch = database.rawQuery(
                    "SELECT batch_id, first_sequence, last_sequence, event_count, "
                            + "persisted_at_ms, persisted_elapsed_ms, capture_session_id, "
                            + "capture_boot_id, source_epoch_token, metadata_digest "
                            + "FROM read_receipt_batches", null);
            if (!batch.moveToNext()
                    || !Arrays.equals(batch.getBlob(0),
                    ReadReceiptAndroidSqlite.uuidBytes(expected.batchId))
                    || batch.getLong(1) != expected.firstSequence
                    || batch.getLong(2) != expected.lastSequence
                    || batch.getLong(3) != expected.eventCount
                    || batch.getLong(4) != expected.persistedAtMs
                    || batch.getLong(5) != expected.persistedElapsedMs
                    || !Arrays.equals(batch.getBlob(6),
                    ReadReceiptAndroidSqlite.uuidBytes(expected.captureSessionId))
                    || !batch.isNull(7)
                    || !batch.isNull(8)
                    || !MessageDigest.isEqual(
                    batch.getBlob(9), ReadReceiptProtocolV2.metadataDigest(expected))
                    || batch.moveToNext()) {
                throw new IllegalStateException();
            }
            foreignKeys = database.rawQuery("PRAGMA foreign_key_check", null);
            if (foreignKeys.moveToNext() || countEvents(databasePath) != NEAR_CAP_ROWS) {
                throw new IllegalStateException();
            }
        } finally {
            if (foreignKeys != null) foreignKeys.close();
            if (batch != null) batch.close();
            if (database != null) database.close();
        }
    }

    static void verifyNearCapLease(String databasePath) {
        LeaseFrame frame = stream(databasePath).claimOrReplayLease();
        if (frame == null
                || frame.lease.firstSequence != 1L
                || frame.lease.lastSequence != TRANSFER_ROWS
                || frame.lease.events.size() != TRANSFER_ROWS
                || frame.lease.batches.size() != 1
                || frame.digest.length != 32) {
            throw new IllegalStateException();
        }
    }

    static void verifyNearCapMaintenance(String databasePath) {
        ReadReceiptAndroidMaintenanceWorker worker = new ReadReceiptAndroidMaintenanceWorker(
                databasePath,
                capacity -> {
                    throw new IllegalStateException();
                });
        if (!worker.runOnce()) throw new IllegalStateException();
    }

    static TransferResult transferDrain256(String databasePath) {
        ReadReceiptDurableStream stream = stream(databasePath);
        List<CapturedEvent> events = new ArrayList<>(TRANSFER_ROWS);
        for (int index = 1; index <= TRANSFER_ROWS; index++) {
            events.add(new CapturedEvent(new UUID(0x5555555555554555L, index),
                    1L, 1L, index));
        }
        AppendResult append = stream.append(new BatchInput(
                TRANSFER_BATCH_ID,
                System.currentTimeMillis(),
                1L,
                TRANSFER_CAPTURE_SESSION_ID,
                null,
                null,
                events));
        ReadReceiptProtocolV2.State before = stream.currentState();
        long beforeOutbox = countEvents(databasePath);
        if (append.outcome != AppendOutcome.COMMITTED
                || append.firstSequence != 1L
                || append.lastSequence != TRANSFER_ROWS
                || before.lastAckedSequence != 0L
                || before.highestIssuedSequence != TRANSFER_ROWS
                || before.activeLease != null
                || beforeOutbox != TRANSFER_ROWS) {
            throw new IllegalStateException();
        }

        long started = System.nanoTime();
        LeaseFrame frame = stream.claimOrReplayLease();
        if (frame == null
                || frame.lease.firstSequence != 1L
                || frame.lease.lastSequence != TRANSFER_ROWS
                || frame.lease.events.size() != TRANSFER_ROWS
                || frame.lease.losses.size() != 0) {
            throw new IllegalStateException();
        }
        ReadReceiptProtocolV2.State leased = stream.currentState();
        if (leased.activeLease == null
                || !leased.activeLease.leaseId.equals(frame.lease.leaseId)) {
            throw new IllegalStateException();
        }
        AckResult ack = stream.acceptAck(new Ack(
                frame.lease.installationId,
                frame.lease.streamEpoch,
                frame.lease.leaseId,
                frame.lease.lastSequence,
                frame.digest));
        ReadReceiptProtocolV2.State after = stream.currentState();
        long afterOutbox = countEvents(databasePath);
        long elapsed = System.nanoTime() - started;
        if (ack.outcome != AckOutcome.ACCEPTED
                || after.lastAckedSequence != TRANSFER_ROWS
                || after.highestIssuedSequence != TRANSFER_ROWS
                || after.activeLease != null
                || afterOutbox != 0L) {
            throw new IllegalStateException();
        }
        return new TransferResult(
                before.lastAckedSequence,
                before.highestIssuedSequence,
                beforeOutbox,
                frame.lease.events.size(),
                after.lastAckedSequence,
                after.highestIssuedSequence,
                afterOutbox,
                elapsed);
    }

    static long countEvents(String databasePath) {
        SQLiteDatabase database = null;
        Cursor cursor = null;
        try {
            database = SQLiteDatabase.openDatabase(databasePath, null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            cursor = database.rawQuery("SELECT COUNT(*) FROM read_receipt_outbox", null);
            if (!cursor.moveToNext()) throw new IllegalStateException();
            long count = cursor.getLong(0);
            if (count < 0 || cursor.moveToNext()) throw new IllegalStateException();
            return count;
        } finally {
            if (cursor != null) cursor.close();
            if (database != null) database.close();
        }
    }

    private static ReadReceiptDurableStream stream(String databasePath) {
        return new ReadReceiptDurableStream(
                new ReadReceiptAndroidDurableStore(databasePath),
                UUID::randomUUID,
                System::currentTimeMillis,
                (delayMs, work) -> { },
                () -> true,
                emptyCounterJournal());
    }

    private static CounterJournal emptyCounterJournal() {
        return new CounterJournal() {
            @Override
            public CounterTarget load() {
                return null;
            }

            @Override
            public void storeGuaranteed(CounterTarget target) {
                throw new IllegalStateException();
            }

            @Override
            public void clearExact(CounterTarget expected) {
                throw new IllegalStateException();
            }
        };
    }

    static final class TransferResult {
        final long beforeAcked;
        final long beforeHighest;
        final long beforeOutbox;
        final long frameEvents;
        final long afterAcked;
        final long afterHighest;
        final long afterOutbox;
        final long elapsedNanos;

        TransferResult(long beforeAcked, long beforeHighest, long beforeOutbox,
                       long frameEvents, long afterAcked, long afterHighest,
                       long afterOutbox, long elapsedNanos) {
            this.beforeAcked = beforeAcked;
            this.beforeHighest = beforeHighest;
            this.beforeOutbox = beforeOutbox;
            this.frameEvents = frameEvents;
            this.afterAcked = afterAcked;
            this.afterHighest = afterHighest;
            this.afterOutbox = afterOutbox;
            this.elapsedNanos = elapsedNanos;
        }
    }
}
