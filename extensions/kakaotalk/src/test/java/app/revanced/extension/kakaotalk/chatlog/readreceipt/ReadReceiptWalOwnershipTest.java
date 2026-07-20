package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ReadReceiptWalOwnershipTest {

    @Test
    public void wal001EveryWritableRoleIsFactoryOwnedConfiguredAndVerifiedBeforeUse() {
        for (ReadReceiptWalOwnership.WriteRole role : ReadReceiptWalOwnership.WriteRole.values()) {
            List<String> trace = new ArrayList<>();
            FakeFactory factory = new FakeFactory(trace);
            ReadReceiptWalOwnership ownership = isolated(factory);

            ReadReceiptWalOwnership.WriteCapability capability = ownership.open(role);
            capability.write();
            capability.close();

            assertEquals(role.name(), Arrays.asList(
                    role.name() + ":factory",
                    role.name() + ":set",
                    role.name() + ":verify",
                    role.name() + ":write",
                    role.name() + ":close"
            ), trace);
        }
    }

    @Test
    public void wal001EverySetupFailureClosesFactoryOwnedConnectionWithoutUse() {
        for (ReadReceiptWalOwnership.WriteRole role : ReadReceiptWalOwnership.WriteRole.values()) {
            assertSetupFailure(role, connection -> connection.failSet = true,
                    Arrays.asList(role.name() + ":factory", role.name() + ":set",
                            role.name() + ":close"));
            assertSetupFailure(role, connection -> connection.failVerify = true,
                    Arrays.asList(role.name() + ":factory", role.name() + ":set",
                            role.name() + ":verify", role.name() + ":close"));
            assertSetupFailure(role, connection -> connection.walAutocheckpointReadback = 1,
                    Arrays.asList(role.name() + ":factory", role.name() + ":set",
                            role.name() + ":verify", role.name() + ":close"));
        }
        assertMaintenanceSetupFailure(connection -> connection.failSet = true,
                Arrays.asList("MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:close"));
        assertMaintenanceSetupFailure(connection -> connection.failVerify = true,
                Arrays.asList("MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                        "MAINTENANCE:close"));
        assertMaintenanceSetupFailure(connection -> connection.walAutocheckpointReadback = 1,
                Arrays.asList("MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                        "MAINTENANCE:close"));
    }

    @Test
    public void wal001OnlyMaintenanceCapabilityReceivesMaintenanceOperations() {
        Set<String> writeMethods = new HashSet<>();
        for (Method method : ReadReceiptWalOwnership.WriteCapability.class.getMethods()) {
            writeMethods.add(method.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList("write", "close")), writeMethods);

        List<String> trace = new ArrayList<>();
        FakeFactory factory = new FakeFactory(trace);
        ReadReceiptWalOwnership.MaintenanceCapability capability =
                isolated(factory).openMaintenance();
        capability.write();
        capability.checkpoint();
        ReadReceiptWalOwnership.StorageBytes bytes = capability.measureBytes();
        capability.pruneAndConvertLoss();
        capability.close();

        assertEquals(11, bytes.main);
        assertEquals(12, bytes.wal);
        assertEquals(13, bytes.shm);
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "MAINTENANCE:write", "MAINTENANCE:checkpoint", "MAINTENANCE:main-bytes",
                "MAINTENANCE:wal-bytes", "MAINTENANCE:shm-bytes", "MAINTENANCE:prune",
                "MAINTENANCE:close"
        ), trace);
    }

    @Test
    public void wal001MaintenanceAdmissionIsProcessWideBeforeFactoryCreation() {
        List<String> firstTrace = new ArrayList<>();
        List<String> secondTrace = new ArrayList<>();
        ReadReceiptWalOwnership firstOwnership =
                new ReadReceiptWalOwnership(new FakeFactory(firstTrace));
        ReadReceiptWalOwnership secondOwnership =
                new ReadReceiptWalOwnership(new FakeFactory(secondTrace));
        ReadReceiptWalOwnership.MaintenanceCapability first = firstOwnership.openMaintenance();

        try {
            assertIllegal("maintenance writer already active", secondOwnership::openMaintenance);
            assertTrue(secondTrace.isEmpty());
        } finally {
            first.close();
        }

        ReadReceiptWalOwnership.MaintenanceCapability reopened =
                secondOwnership.openMaintenance();
        reopened.close();
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "MAINTENANCE:close"
        ), firstTrace);
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "MAINTENANCE:close"
        ), secondTrace);
    }

    @Test
    public void wal001FailedMaintenanceSetupReleasesAdmissionOnlyAfterSuccessfulCleanup() {
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();
        List<String> failedTrace = new ArrayList<>();
        FakeFactory failedFactory = new FakeFactory(failedTrace);
        failedFactory.maintenanceConnection = new FakeMaintenanceConnection(
                "failed", failedTrace);
        failedFactory.maintenanceConnection.failVerify = true;

        assertIllegal("WAL connection setup failed",
                () -> new ReadReceiptWalOwnership(failedFactory, domain).openMaintenance());

        List<String> retryTrace = new ArrayList<>();
        ReadReceiptWalOwnership.MaintenanceCapability retry =
                new ReadReceiptWalOwnership(new FakeFactory(retryTrace), domain)
                        .openMaintenance();
        retry.close();
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "failed:set", "failed:verify", "failed:close"
        ), failedTrace);
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "MAINTENANCE:close"
        ), retryTrace);
    }

    @Test
    public void wal001MaintenanceCloseFailureBreaksDomainAndDeniesAllNewAcquisitions() {
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();
        List<String> firstTrace = new ArrayList<>();
        FakeFactory firstFactory = new FakeFactory(firstTrace);
        firstFactory.maintenanceConnection = new FakeMaintenanceConnection(
                "first", firstTrace);
        firstFactory.maintenanceConnection.failClose = true;
        ReadReceiptWalOwnership.MaintenanceCapability first =
                new ReadReceiptWalOwnership(firstFactory, domain).openMaintenance();

        IllegalStateException closeFailure =
                assertIllegal("WAL connection close failed", first::close);
        assertBounded(closeFailure);

        List<String> deniedMaintenanceTrace = new ArrayList<>();
        ReadReceiptWalOwnership deniedMaintenance = new ReadReceiptWalOwnership(
                new FakeFactory(deniedMaintenanceTrace), domain);
        assertBounded(assertIllegal("WAL domain broken",
                deniedMaintenance::openMaintenance));
        assertTrue(deniedMaintenanceTrace.isEmpty());

        List<String> deniedWriteTrace = new ArrayList<>();
        ReadReceiptWalOwnership deniedWrite = new ReadReceiptWalOwnership(
                new FakeFactory(deniedWriteTrace), domain);
        assertBounded(assertIllegal("WAL domain broken",
                () -> deniedWrite.open(ReadReceiptWalOwnership.WriteRole.ACK)));
        assertTrue(deniedWriteTrace.isEmpty());
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "first:set", "first:verify", "first:close"
        ), firstTrace);
    }

    @Test
    public void wal001MaintenanceSetupCleanupFailureBreaksDomain() {
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();
        List<String> failedTrace = new ArrayList<>();
        FakeFactory failedFactory = new FakeFactory(failedTrace);
        failedFactory.maintenanceConnection = new FakeMaintenanceConnection(
                "failed", failedTrace);
        failedFactory.maintenanceConnection.failVerify = true;
        failedFactory.maintenanceConnection.failClose = true;

        IllegalStateException setupFailure = assertIllegal("WAL connection setup failed",
                () -> new ReadReceiptWalOwnership(failedFactory, domain).openMaintenance());
        assertBounded(setupFailure);

        List<String> deniedTrace = new ArrayList<>();
        ReadReceiptWalOwnership denied =
                new ReadReceiptWalOwnership(new FakeFactory(deniedTrace), domain);
        assertBounded(assertIllegal("WAL domain broken", denied::openMaintenance));
        assertTrue(deniedTrace.isEmpty());
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "failed:set", "failed:verify", "failed:close"
        ), failedTrace);
    }

    @Test
    public void wal001WritableSetupCleanupFailureBreaksDomainBeforeNextFactoryCall() {
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();
        List<String> failedTrace = new ArrayList<>();
        FakeFactory failedFactory = new FakeFactory(failedTrace);
        failedFactory.writeConnection = new FakeConnection("failed", failedTrace);
        failedFactory.writeConnection.failVerify = true;
        failedFactory.writeConnection.failClose = true;

        IllegalStateException setupFailure = assertIllegal("WAL connection setup failed",
                () -> new ReadReceiptWalOwnership(failedFactory, domain)
                        .open(ReadReceiptWalOwnership.WriteRole.STARTUP));
        assertBounded(setupFailure);

        List<String> deniedTrace = new ArrayList<>();
        ReadReceiptWalOwnership denied =
                new ReadReceiptWalOwnership(new FakeFactory(deniedTrace), domain);
        assertBounded(assertIllegal("WAL domain broken",
                () -> denied.open(ReadReceiptWalOwnership.WriteRole.ACK)));
        assertTrue(deniedTrace.isEmpty());
        assertEquals(Arrays.asList(
                "STARTUP:factory", "failed:set", "failed:verify", "failed:close"
        ), failedTrace);
    }

    @Test
    public void wal001WriteCloseFailureBlocksExistingWriteAndNewAcquisition() {
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();
        List<String> closingTrace = new ArrayList<>();
        FakeFactory closingFactory = new FakeFactory(closingTrace);
        closingFactory.writeConnection = new FakeConnection("closing", closingTrace);
        closingFactory.writeConnection.failClose = true;
        ReadReceiptWalOwnership.WriteCapability closing =
                new ReadReceiptWalOwnership(closingFactory, domain)
                        .open(ReadReceiptWalOwnership.WriteRole.ACK);

        List<String> existingTrace = new ArrayList<>();
        ReadReceiptWalOwnership.WriteCapability existing =
                new ReadReceiptWalOwnership(new FakeFactory(existingTrace), domain)
                        .open(ReadReceiptWalOwnership.WriteRole.REPLAY_SENDER);

        assertBounded(assertIllegal("WAL connection close failed", closing::close));
        closing.close();
        assertBounded(assertIllegal("WAL domain broken", existing::write));
        existing.close();
        existing.close();

        List<String> deniedTrace = new ArrayList<>();
        ReadReceiptWalOwnership denied =
                new ReadReceiptWalOwnership(new FakeFactory(deniedTrace), domain);
        assertBounded(assertIllegal("WAL domain broken",
                () -> denied.open(ReadReceiptWalOwnership.WriteRole.FAST_COMMIT)));
        assertTrue(deniedTrace.isEmpty());
        assertEquals(Arrays.asList(
                "ACK:factory", "closing:set", "closing:verify", "closing:close"
        ), closingTrace);
        assertEquals(Arrays.asList(
                "REPLAY_SENDER:factory", "REPLAY_SENDER:set", "REPLAY_SENDER:verify",
                "REPLAY_SENDER:close"
        ), existingTrace);
    }

    @Test
    public void wal001BrokenDomainBlocksEveryExistingMaintenanceOperationButAllowsClose() {
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();
        List<String> maintenanceTrace = new ArrayList<>();
        ReadReceiptWalOwnership.MaintenanceCapability maintenance =
                new ReadReceiptWalOwnership(new FakeFactory(maintenanceTrace), domain)
                        .openMaintenance();

        List<String> breakerTrace = new ArrayList<>();
        FakeFactory breakerFactory = new FakeFactory(breakerTrace);
        breakerFactory.writeConnection = new FakeConnection("breaker", breakerTrace);
        breakerFactory.writeConnection.failClose = true;
        ReadReceiptWalOwnership.WriteCapability breaker =
                new ReadReceiptWalOwnership(breakerFactory, domain)
                        .open(ReadReceiptWalOwnership.WriteRole.CAPTURE_CALLBACK);
        assertBounded(assertIllegal("WAL connection close failed", breaker::close));

        assertBounded(assertIllegal("WAL domain broken", maintenance::write));
        assertBounded(assertIllegal("WAL domain broken", maintenance::checkpoint));
        assertBounded(assertIllegal("WAL domain broken", maintenance::measureBytes));
        assertBounded(assertIllegal("WAL domain broken", maintenance::pruneAndConvertLoss));
        maintenance.close();
        maintenance.close();

        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "MAINTENANCE:close"
        ), maintenanceTrace);
    }

    @Test
    public void wal001MaintenanceOperationsSerializeWithWritesAcrossOwnershipInstances()
            throws Exception {
        List<String> trace = new ArrayList<>();
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        CountDownLatch checkpointCalled = new CountDownLatch(1);
        ReadReceiptWalOwnership.ProcessDomain domain = new ReadReceiptWalOwnership.ProcessDomain();

        FakeFactory writerFactory = new FakeFactory(trace);
        writerFactory.writeConnection = new BlockingConnection(
                "FAST_COMMIT", trace, writeEntered, finishWrite);
        FakeFactory maintenanceFactory = new FakeFactory(trace);
        maintenanceFactory.maintenanceConnection = new FakeMaintenanceConnection(
                "MAINTENANCE", trace, checkpointCalled);
        ReadReceiptWalOwnership.WriteCapability writerCapability =
                new ReadReceiptWalOwnership(writerFactory, domain)
                        .open(ReadReceiptWalOwnership.WriteRole.FAST_COMMIT);
        ReadReceiptWalOwnership.MaintenanceCapability maintenanceCapability =
                new ReadReceiptWalOwnership(maintenanceFactory, domain).openMaintenance();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = thread(writerCapability::write, failure);
        writer.start();
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS));

        Thread checkpoint = thread(maintenanceCapability::checkpoint, failure);
        checkpoint.start();
        assertFalse(checkpointCalled.await(100, TimeUnit.MILLISECONDS));

        finishWrite.countDown();
        writer.join(1_000);
        checkpoint.join(1_000);

        assertFalse(writer.isAlive());
        assertFalse(checkpoint.isAlive());
        assertNull(failure.get());
        assertEquals(Arrays.asList(
                "FAST_COMMIT:factory", "FAST_COMMIT:set", "FAST_COMMIT:verify",
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "FAST_COMMIT:write-start", "FAST_COMMIT:write-end",
                "MAINTENANCE:checkpoint"
        ), trace);
        writerCapability.close();
        maintenanceCapability.close();
    }

    @Test
    public void wal001AdapterFailuresAreBoundedAndDoNotExposeCauses() {
        List<String> openTrace = new ArrayList<>();
        FakeFactory openFactory = new FakeFactory(openTrace);
        openFactory.failWriteOpen = true;
        assertBounded(assertIllegal("WAL connection open failed",
                () -> isolated(openFactory).open(ReadReceiptWalOwnership.WriteRole.ACK)));

        List<String> writeTrace = new ArrayList<>();
        FakeFactory writeFactory = new FakeFactory(writeTrace);
        writeFactory.writeConnection = new FakeConnection("ACK", writeTrace);
        writeFactory.writeConnection.failWrite = true;
        ReadReceiptWalOwnership.WriteCapability write =
                isolated(writeFactory).open(ReadReceiptWalOwnership.WriteRole.ACK);
        assertBounded(assertIllegal("WAL write failed", write::write));
        write.close();

        assertMaintenanceOperationFailure(
                connection -> connection.failCheckpoint = true,
                "WAL checkpoint failed",
                ReadReceiptWalOwnership.MaintenanceCapability::checkpoint);
        assertMaintenanceOperationFailure(
                connection -> connection.failMeasure = true,
                "WAL size measurement failed",
                ReadReceiptWalOwnership.MaintenanceCapability::measureBytes);
        assertMaintenanceOperationFailure(
                connection -> connection.failPrune = true,
                "WAL prune failed",
                ReadReceiptWalOwnership.MaintenanceCapability::pruneAndConvertLoss);
    }

    @Test
    public void wal001ClosedCapabilitiesRejectFurtherUseAndCloseOnce() {
        List<String> writeTrace = new ArrayList<>();
        ReadReceiptWalOwnership.WriteCapability write = isolated(new FakeFactory(writeTrace))
                .open(ReadReceiptWalOwnership.WriteRole.ACK);
        write.close();
        write.close();
        assertIllegal("capability closed", write::write);
        assertEquals(Arrays.asList(
                "ACK:factory", "ACK:set", "ACK:verify", "ACK:close"
        ), writeTrace);

        List<String> maintenanceTrace = new ArrayList<>();
        ReadReceiptWalOwnership.MaintenanceCapability maintenance =
                isolated(new FakeFactory(maintenanceTrace)).openMaintenance();
        maintenance.close();
        maintenance.close();
        assertIllegal("capability closed", maintenance::write);
        assertIllegal("capability closed", maintenance::checkpoint);
        assertIllegal("capability closed", maintenance::measureBytes);
        assertIllegal("capability closed", maintenance::pruneAndConvertLoss);
        assertEquals(Arrays.asList(
                "MAINTENANCE:factory", "MAINTENANCE:set", "MAINTENANCE:verify",
                "MAINTENANCE:close"
        ), maintenanceTrace);
    }

    private static ReadReceiptWalOwnership isolated(FakeFactory factory) {
        return new ReadReceiptWalOwnership(factory, new ReadReceiptWalOwnership.ProcessDomain());
    }

    private static void assertSetupFailure(
            ReadReceiptWalOwnership.WriteRole role,
            ConnectionMutation<FakeConnection> mutation,
            List<String> expectedTrace
    ) {
        List<String> trace = new ArrayList<>();
        FakeFactory factory = new FakeFactory(trace);
        factory.writeConnection = new FakeConnection(role.name(), trace);
        mutation.apply(factory.writeConnection);

        IllegalStateException failure = assertIllegal("WAL connection setup failed",
                () -> isolated(factory).open(role));
        assertBounded(failure);
        assertEquals(expectedTrace, trace);
    }

    private static void assertMaintenanceSetupFailure(
            ConnectionMutation<FakeMaintenanceConnection> mutation,
            List<String> expectedTrace
    ) {
        List<String> trace = new ArrayList<>();
        FakeFactory factory = new FakeFactory(trace);
        factory.maintenanceConnection = new FakeMaintenanceConnection("MAINTENANCE", trace);
        mutation.apply(factory.maintenanceConnection);

        IllegalStateException failure = assertIllegal("WAL connection setup failed",
                () -> isolated(factory).openMaintenance());
        assertBounded(failure);
        assertEquals(expectedTrace, trace);
    }

    private static void assertMaintenanceOperationFailure(
            ConnectionMutation<FakeMaintenanceConnection> mutation,
            String expectedMessage,
            MaintenanceOperation operation
    ) {
        List<String> trace = new ArrayList<>();
        FakeFactory factory = new FakeFactory(trace);
        factory.maintenanceConnection = new FakeMaintenanceConnection("MAINTENANCE", trace);
        mutation.apply(factory.maintenanceConnection);
        ReadReceiptWalOwnership.MaintenanceCapability capability =
                isolated(factory).openMaintenance();

        assertBounded(assertIllegal(expectedMessage, () -> operation.run(capability)));
        capability.close();
    }

    private static Thread thread(Runnable action, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private static IllegalStateException assertIllegal(String message, Runnable action) {
        try {
            action.run();
            fail("expected IllegalStateException");
            throw new AssertionError();
        } catch (IllegalStateException exception) {
            assertEquals(message, exception.getMessage());
            return exception;
        }
    }

    private static void assertBounded(IllegalStateException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private interface ConnectionMutation<T> {
        void apply(T connection);
    }

    private interface MaintenanceOperation {
        void run(ReadReceiptWalOwnership.MaintenanceCapability capability);
    }

    private static final class FakeFactory
            implements ReadReceiptWalOwnership.ConnectionFactory {
        private final List<String> trace;
        private FakeConnection writeConnection;
        private FakeMaintenanceConnection maintenanceConnection;
        private boolean failWriteOpen;

        private FakeFactory(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public ReadReceiptWalOwnership.WritableConnection open(
                ReadReceiptWalOwnership.WriteRole role
        ) {
            trace.add(role.name() + ":factory");
            if (failWriteOpen) throw new RuntimeException("sensitive open failure");
            if (writeConnection != null) return writeConnection;
            return new FakeConnection(role.name(), trace);
        }

        @Override
        public ReadReceiptWalOwnership.MaintenanceConnection openMaintenance() {
            trace.add("MAINTENANCE:factory");
            if (maintenanceConnection != null) return maintenanceConnection;
            return new FakeMaintenanceConnection("MAINTENANCE", trace);
        }
    }

    private static class FakeConnection implements ReadReceiptWalOwnership.WritableConnection {
        private final String role;
        protected final List<String> trace;
        protected int walAutocheckpointReadback;
        protected boolean failSet;
        protected boolean failVerify;
        protected boolean failWrite;
        protected boolean failClose;

        private FakeConnection(String role, List<String> trace) {
            this.role = role;
            this.trace = trace;
        }

        @Override
        public void setWalAutocheckpointZero() {
            trace.add(role + ":set");
            if (failSet) throw new RuntimeException("sensitive set failure");
        }

        @Override
        public int readWalAutocheckpoint() {
            trace.add(role + ":verify");
            if (failVerify) throw new RuntimeException("sensitive verify failure");
            return walAutocheckpointReadback;
        }

        @Override
        public void write() {
            trace.add(role + ":write");
            if (failWrite) throw new RuntimeException("sensitive write failure");
        }

        @Override
        public void close() {
            trace.add(role + ":close");
            if (failClose) throw new RuntimeException("sensitive close failure");
        }
    }

    private static final class BlockingConnection extends FakeConnection {
        private final String role;
        private final CountDownLatch entered;
        private final CountDownLatch finish;

        private BlockingConnection(
                String role,
                List<String> trace,
                CountDownLatch entered,
                CountDownLatch finish
        ) {
            super(role, trace);
            this.role = role;
            this.entered = entered;
            this.finish = finish;
        }

        @Override
        public void write() {
            trace.add(role + ":write-start");
            entered.countDown();
            try {
                assertTrue(finish.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
            trace.add(role + ":write-end");
        }
    }

    private static final class FakeMaintenanceConnection extends FakeConnection
            implements ReadReceiptWalOwnership.MaintenanceConnection {
        private final CountDownLatch checkpointCalled;
        private boolean failCheckpoint;
        private boolean failMeasure;
        private boolean failPrune;

        private FakeMaintenanceConnection(String role, List<String> trace) {
            this(role, trace, null);
        }

        private FakeMaintenanceConnection(
                String role,
                List<String> trace,
                CountDownLatch checkpointCalled
        ) {
            super(role, trace);
            this.checkpointCalled = checkpointCalled;
        }

        @Override
        public void checkpoint() {
            trace.add("MAINTENANCE:checkpoint");
            if (checkpointCalled != null) checkpointCalled.countDown();
            if (failCheckpoint) throw new RuntimeException("sensitive checkpoint failure");
        }

        @Override
        public long measureMainBytes() {
            trace.add("MAINTENANCE:main-bytes");
            if (failMeasure) throw new RuntimeException("sensitive measure failure");
            return 11;
        }

        @Override
        public long measureWalBytes() {
            trace.add("MAINTENANCE:wal-bytes");
            return 12;
        }

        @Override
        public long measureShmBytes() {
            trace.add("MAINTENANCE:shm-bytes");
            return 13;
        }

        @Override
        public void pruneAndConvertLoss() {
            trace.add("MAINTENANCE:prune");
            if (failPrune) throw new RuntimeException("sensitive prune failure");
        }
    }
}
