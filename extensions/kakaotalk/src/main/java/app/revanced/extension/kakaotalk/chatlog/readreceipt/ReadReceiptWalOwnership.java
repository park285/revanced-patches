package app.revanced.extension.kakaotalk.chatlog.readreceipt;

final class ReadReceiptWalOwnership {

    private static final ProcessDomain PROCESS_DOMAIN = new ProcessDomain();

    private final ConnectionFactory connectionFactory;
    private final ProcessDomain domain;

    ReadReceiptWalOwnership(ConnectionFactory connectionFactory) {
        this(connectionFactory, PROCESS_DOMAIN);
    }

    ReadReceiptWalOwnership(ConnectionFactory connectionFactory, ProcessDomain domain) {
        if (connectionFactory == null || domain == null) {
            throw new IllegalArgumentException("missing WAL owner");
        }
        this.connectionFactory = connectionFactory;
        this.domain = domain;
    }

    WriteCapability open(WriteRole role) {
        if (role == null) throw new IllegalArgumentException("missing WAL owner");
        synchronized (domain.lock) {
            requireOperationalDomain();
            WritableConnection connection = openWritable(role);
            if (!configure(connection)) {
                throw boundedFailure("WAL connection setup failed");
            }
            return new WriteHandle(connection);
        }
    }

    MaintenanceCapability openMaintenance() {
        synchronized (domain.lock) {
            requireMaintenanceAdmission();
            domain.maintenanceActive = true;

            MaintenanceConnection connection;
            try {
                connection = connectionFactory.openMaintenance();
                if (connection == null) throw new IllegalStateException();
            } catch (RuntimeException failure) {
                domain.maintenanceActive = false;
                throw boundedFailure("WAL connection open failed");
            }

            if (!configure(connection)) {
                domain.maintenanceActive = false;
                throw boundedFailure("WAL connection setup failed");
            }
            return new MaintenanceHandle(connection);
        }
    }

    private WritableConnection openWritable(WriteRole role) {
        try {
            WritableConnection connection = connectionFactory.open(role);
            if (connection == null) throw new IllegalStateException();
            return connection;
        } catch (RuntimeException failure) {
            throw boundedFailure("WAL connection open failed");
        }
    }

    private boolean configure(WritableConnection connection) {
        try {
            connection.setWalAutocheckpointZero();
            if (connection.readWalAutocheckpoint() != 0) {
                throw new IllegalStateException();
            }
            return true;
        } catch (RuntimeException failure) {
            if (!closeAfterFailedSetup(connection)) domain.broken = true;
            return false;
        }
    }

    private static boolean closeAfterFailedSetup(WritableConnection connection) {
        try {
            connection.close();
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void requireMaintenanceAdmission() {
        requireOperationalDomain();
        if (domain.maintenanceActive) {
            throw boundedFailure("maintenance writer already active");
        }
    }

    private void requireOperationalDomain() {
        if (domain.broken) throw boundedFailure("WAL domain broken");
    }

    private static IllegalStateException boundedFailure(String message) {
        return new IllegalStateException(message);
    }

    enum WriteRole {
        FAST_COMMIT,
        STARTUP,
        LEASE_CLAIM,
        REPLAY_SENDER,
        ACK,
        CAPTURE_CALLBACK
    }

    interface ConnectionFactory {
        WritableConnection open(WriteRole role);

        MaintenanceConnection openMaintenance();
    }

    interface WritableConnection {
        void setWalAutocheckpointZero();

        int readWalAutocheckpoint();

        void write();

        void close();
    }

    interface MaintenanceConnection extends WritableConnection {
        void checkpoint();

        long measureMainBytes();

        long measureWalBytes();

        long measureShmBytes();

        void pruneAndConvertLoss();
    }

    interface WriteCapability extends AutoCloseable {
        void write();

        @Override
        void close();
    }

    interface MaintenanceCapability extends WriteCapability {
        void checkpoint();

        StorageBytes measureBytes();

        void pruneAndConvertLoss();
    }

    static final class ProcessDomain {
        private final Object lock = new Object();
        private boolean maintenanceActive;
        private boolean broken;
    }

    static final class StorageBytes {
        final long main;
        final long wal;
        final long shm;

        private StorageBytes(long main, long wal, long shm) {
            this.main = main;
            this.wal = wal;
            this.shm = shm;
        }
    }

    private final class WriteHandle implements WriteCapability {
        private final WritableConnection connection;
        private boolean closed;

        private WriteHandle(WritableConnection connection) {
            this.connection = connection;
        }

        @Override
        public void write() {
            synchronized (domain.lock) {
                requireOperational();
                try {
                    connection.write();
                } catch (RuntimeException failure) {
                    throw boundedFailure("WAL write failed");
                }
            }
        }

        @Override
        public void close() {
            synchronized (domain.lock) {
                if (closed) return;
                closed = true;
                try {
                    connection.close();
                } catch (RuntimeException failure) {
                    domain.broken = true;
                    throw boundedFailure("WAL connection close failed");
                }
            }
        }

        private void requireOperational() {
            requireOperationalDomain();
            if (closed) throw boundedFailure("capability closed");
        }
    }

    private final class MaintenanceHandle implements MaintenanceCapability {
        private final MaintenanceConnection connection;
        private boolean closed;

        private MaintenanceHandle(MaintenanceConnection connection) {
            this.connection = connection;
        }

        @Override
        public void write() {
            synchronized (domain.lock) {
                requireOperational();
                try {
                    connection.write();
                } catch (RuntimeException failure) {
                    throw boundedFailure("WAL write failed");
                }
            }
        }

        @Override
        public void checkpoint() {
            synchronized (domain.lock) {
                requireOperational();
                try {
                    connection.checkpoint();
                } catch (RuntimeException failure) {
                    throw boundedFailure("WAL checkpoint failed");
                }
            }
        }

        @Override
        public StorageBytes measureBytes() {
            synchronized (domain.lock) {
                requireOperational();
                try {
                    return new StorageBytes(
                            connection.measureMainBytes(),
                            connection.measureWalBytes(),
                            connection.measureShmBytes()
                    );
                } catch (RuntimeException failure) {
                    throw boundedFailure("WAL size measurement failed");
                }
            }
        }

        @Override
        public void pruneAndConvertLoss() {
            synchronized (domain.lock) {
                requireOperational();
                try {
                    connection.pruneAndConvertLoss();
                } catch (RuntimeException failure) {
                    throw boundedFailure("WAL prune failed");
                }
            }
        }

        @Override
        public void close() {
            synchronized (domain.lock) {
                if (closed) return;
                closed = true;
                try {
                    connection.close();
                } catch (RuntimeException failure) {
                    domain.broken = true;
                    throw boundedFailure("WAL connection close failed");
                } finally {
                    domain.maintenanceActive = false;
                }
            }
        }

        private void requireOperational() {
            requireOperationalDomain();
            if (closed) throw boundedFailure("capability closed");
        }
    }
}
