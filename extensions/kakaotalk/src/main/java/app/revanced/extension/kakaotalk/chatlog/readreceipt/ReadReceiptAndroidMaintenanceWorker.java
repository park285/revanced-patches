package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.util.function.LongSupplier;

final class ReadReceiptAndroidMaintenanceWorker implements RetryWork {
    private static final int MAX_CONVERSIONS_PER_RUN = 32;
    private static final int MAX_RECLAIM_PAGES_PER_RUN = 256;
    private static final long MAX_CHECKPOINT_BYTES_PER_RUN =
            ReadReceiptDurableStream.MAX_TOTAL_BYTES;
    // 정상 cap 한 배의 crash overhang만 허용해 startup checkpoint I/O를 제한한다.
    static final long MAX_STARTUP_RECOVERY_WAL_BYTES =
            ReadReceiptDurableStream.MAX_TOTAL_BYTES * 2L;
    static final String EVENT_CAPACITY_QUERY =
            "SELECT COUNT(*) FROM (SELECT 1 FROM read_receipt_outbox LIMIT 250001)";
    static final String WAL_CHECKPOINT_PASSIVE_QUERY = "PRAGMA wal_checkpoint(PASSIVE)";
    static final String WAL_CHECKPOINT_TRUNCATE_QUERY = "PRAGMA wal_checkpoint(TRUNCATE)";
    private static final Object PROCESS_LOCK = new Object();

    interface Factory {
        Access create();
    }

    interface Access {
        void open();

        void checkpoint();

        void recoverOversizedWal();

        long eventRows();

        long mainBytes();

        long walBytes();

        long reclaimableBytes();

        void reclaimPages(int maxPages);

        void truncateWal();

        void close();
    }

    interface Maintainer {
        MaintenanceResult maintain(
                Capacity capacity);
    }

    private final Factory factory;
    private final Maintainer maintainer;

    ReadReceiptAndroidMaintenanceWorker(String databasePath, Maintainer maintainer) {
        this(() -> new AndroidAccess(databasePath), maintainer);
    }

    ReadReceiptAndroidMaintenanceWorker(Factory factory, Maintainer maintainer) {
        if (factory == null || maintainer == null) throw new IllegalArgumentException(
                "maintenance owner missing");
        this.factory = factory;
        this.maintainer = maintainer;
    }

    @Override
    public boolean runOnce() {
        synchronized (PROCESS_LOCK) {
            Access access = null;
            boolean completed = false;
            try {
                access = factory.create();
                if (access == null) return false;
                access.open();
                access.checkpoint();
                Capacity capacity = capacity(access);
                if (ReadReceiptDurableStream.overByteCapacity(capacity)
                        && capacity.walBytes > 0) {
                    access.truncateWal();
                    capacity = capacity(access);
                }
                for (int conversion = 0; conversion < MAX_CONVERSIONS_PER_RUN
                        && ReadReceiptDurableStream.overCapacity(capacity); conversion++) {
                    MaintenanceResult result =
                            maintainer.maintain(capacity);
                    if (!progress(result)) break;
                    access.checkpoint();
                    capacity = capacity(access);
                }
                if (ReadReceiptDurableStream.overByteCapacity(capacity)) {
                    long reclaimable = access.reclaimableBytes();
                    if (reclaimable > 0) {
                        access.reclaimPages(MAX_RECLAIM_PAGES_PER_RUN);
                    }
                    if (reclaimable > 0 || capacity.walBytes > 0) access.truncateWal();
                    capacity = capacity(access);
                }
                completed = !ReadReceiptDurableStream.overCapacity(capacity);
            } catch (DurabilityContractException exception) {
                throw exception;
            } catch (RuntimeException ignored) {
                completed = false;
            } finally {
                if (access != null) {
                    try {
                        access.close();
                    } catch (RuntimeException ignored) {
                        completed = false;
                    }
                }
            }
            return completed;
        }
    }

    boolean recoverPhysicalWalOnStart() {
        synchronized (PROCESS_LOCK) {
            Access access = null;
            boolean recovered = false;
            try {
                access = factory.create();
                if (access == null) return false;
                access.open();
                long walBytes = access.walBytes();
                if (checkpointFitsRunBudget(walBytes)) {
                    recovered = true;
                } else if (startupRecoveryFitsHardLimit(walBytes)) {
                    access.recoverOversizedWal();
                    recovered = checkpointFitsRunBudget(access.walBytes());
                }
            } catch (RuntimeException ignored) {
                recovered = false;
            } finally {
                if (access != null) {
                    try {
                        access.close();
                    } catch (RuntimeException ignored) {
                        recovered = false;
                    }
                }
            }
            return recovered;
        }
    }

    @Override
    public boolean recoveryRequiredOnStart() {
        return true;
    }

    private static boolean progress(MaintenanceResult result) {
        return result != null && (result.outcome
                == MaintenanceOutcome.CONVERTED
                || result.outcome == MaintenanceOutcome.COMPACTED);
    }

    private static Capacity capacity(Access access) {
        return new Capacity(
                access.eventRows(), access.mainBytes(), access.walBytes());
    }

    static boolean checkpointFitsRunBudget(long walBytes) {
        return walBytes >= 0 && walBytes <= MAX_CHECKPOINT_BYTES_PER_RUN;
    }

    static void requireCheckpointBudget(long walBytes) {
        if (!checkpointFitsRunBudget(walBytes)) {
            throw new DurabilityContractException();
        }
    }

    static boolean startupRecoveryFitsHardLimit(long walBytes) {
        return walBytes > MAX_CHECKPOINT_BYTES_PER_RUN
                && walBytes <= MAX_STARTUP_RECOVERY_WAL_BYTES;
    }

    static final class AndroidAccess implements Access {
        private final String databasePath;
        private final LongSupplier walBytes;
        private SQLiteDatabase database;

        AndroidAccess(String databasePath) {
            this(databasePath, () -> regularFileSize(databasePath + "-wal", true));
        }

        AndroidAccess(String databasePath, LongSupplier walBytes) {
            this.databasePath = databasePath;
            if (walBytes == null) throw failure();
            this.walBytes = walBytes;
        }

        @Override
        public void open() {
            if (database != null || databasePath == null || databasePath.isEmpty()) {
                throw failure();
            }
            SQLiteDatabase opened = null;
            try {
                opened = SQLiteDatabase.openDatabase(databasePath, null,
                        SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                opened.setForeignKeyConstraintsEnabled(true);
                if (scalar(opened, "PRAGMA busy_timeout=0") != 0
                        || scalar(opened, "PRAGMA foreign_keys") != 1
                        || scalar(opened, "PRAGMA wal_autocheckpoint=0") != 0
                        || scalar(opened, "PRAGMA page_size")
                        != ReadReceiptSchemaV2.PAGE_SIZE_BYTES
                        || scalar(opened, "PRAGMA auto_vacuum")
                        != ReadReceiptSchemaV2.AUTO_VACUUM_INCREMENTAL) {
                    throw failure();
                }
                database = opened;
            } catch (RuntimeException exception) {
                if (opened != null) {
                    try {
                        opened.close();
                    } catch (RuntimeException ignored) {
                    }
                }
                throw failure();
            }
        }

        @Override
        public void checkpoint() {
            requireOpen();
            requireBoundedWal();
            checkpointWal(false);
        }

        @Override
        public void recoverOversizedWal() {
            requireOpen();
            checkpointWal(true);
        }

        @Override
        public long eventRows() {
            requireOpen();
            return scalar(database, EVENT_CAPACITY_QUERY);
        }

        @Override
        public long mainBytes() {
            requireOpen();
            return regularFileSize(databasePath, false);
        }

        @Override
        public long walBytes() {
            requireOpen();
            return walBytes.getAsLong();
        }

        @Override
        public long reclaimableBytes() {
            requireOpen();
            long pages = scalar(database, "PRAGMA freelist_count");
            long pageSize = scalar(database, "PRAGMA page_size");
            try {
                return Math.multiplyExact(pages, pageSize);
            } catch (ArithmeticException exception) {
                throw failure();
            }
        }

        @Override
        public void reclaimPages(int maxPages) {
            requireOpen();
            if (maxPages <= 0 || maxPages > MAX_RECLAIM_PAGES_PER_RUN) throw failure();
            try {
                if (scalar(database, "PRAGMA busy_timeout=0") != 0) throw failure();
                long before = scalar(database, "PRAGMA freelist_count");
                if (before == 0) throw failure();
                drainRows(database, "PRAGMA incremental_vacuum(" + maxPages + ")");
                if (scalar(database, "PRAGMA freelist_count") >= before) throw failure();
            } catch (RuntimeException exception) {
                throw failure();
            }
        }

        @Override
        public void truncateWal() {
            requireOpen();
            requireBoundedWal();
            checkpointWal(true);
        }

        private void checkpointWal(boolean requireEmpty) {
            Cursor cursor = null;
            try {
                if (scalar(database, "PRAGMA busy_timeout=0") != 0) throw failure();
                cursor = database.rawQuery(requireEmpty
                        ? WAL_CHECKPOINT_TRUNCATE_QUERY : WAL_CHECKPOINT_PASSIVE_QUERY, null);
                if (!cursor.moveToNext() || cursor.getLong(0) != 0 || requireEmpty
                        && (cursor.getLong(1) != 0 || cursor.getLong(2) != 0)
                        || cursor.moveToNext()) {
                    throw failure();
                }
            } catch (RuntimeException exception) {
                throw failure();
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        @Override
        public void close() {
            if (database == null) return;
            SQLiteDatabase closing = database;
            database = null;
            try {
                closing.close();
            } catch (RuntimeException exception) {
                throw failure();
            }
        }

        private void requireOpen() {
            if (database == null) throw failure();
        }

        private void requireBoundedWal() {
            requireCheckpointBudget(walBytes.getAsLong());
        }

        private static long scalar(SQLiteDatabase database, String sql) {
            Cursor cursor = null;
            try {
                cursor = database.rawQuery(sql, null);
                if (!cursor.moveToNext()) throw failure();
                long value = cursor.getLong(0);
                if (value < 0 || cursor.moveToNext()) throw failure();
                return value;
            } catch (RuntimeException exception) {
                throw failure();
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        private static void drainRows(SQLiteDatabase database, String sql) {
            Cursor cursor = null;
            try {
                cursor = database.rawQuery(sql, null);
                int rows = 0;
                while (cursor.moveToNext()) {
                    if (++rows > MAX_RECLAIM_PAGES_PER_RUN) throw failure();
                }
            } catch (RuntimeException exception) {
                throw failure();
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        private static long regularFileSize(String path, boolean absentIsZero) {
            try {
                StructStat status = Os.lstat(path);
                if (!OsConstants.S_ISREG(status.st_mode) || status.st_size < 0) throw failure();
                return status.st_size;
            } catch (ErrnoException exception) {
                if (absentIsZero && exception.errno == OsConstants.ENOENT) return 0;
                throw failure();
            } catch (RuntimeException exception) {
                throw failure();
            }
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("maintenance operation failed");
    }
}
