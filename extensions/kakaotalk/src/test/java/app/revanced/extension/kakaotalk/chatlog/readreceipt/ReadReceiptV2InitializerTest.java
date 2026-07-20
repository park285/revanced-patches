package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReadReceiptV2InitializerTest {

    private static final String KAKAOTALK_PACKAGE = "com.kakao.talk";

    @Test
    public void own001RequiresExactMainProcessBeforeAnyV2SideEffect() {
        Case[] cases = {
                new Case("exact main", KAKAOTALK_PACKAGE, KAKAOTALK_PACKAGE, false, false, true),
                new Case("suffix", KAKAOTALK_PACKAGE + ":push", KAKAOTALK_PACKAGE, false, false, false),
                new Case("null process", null, KAKAOTALK_PACKAGE, false, false, false),
                new Case("null package", KAKAOTALK_PACKAGE, null, false, false, false),
                new Case("mismatched package", KAKAOTALK_PACKAGE, "com.kakao.talk.beta", false, false, false),
                new Case("wrong package", "com.example.other", "com.example.other", false, false, false),
                new Case("process lookup failure", null, KAKAOTALK_PACKAGE, true, false, false),
                new Case("package lookup failure", KAKAOTALK_PACKAGE, null, false, true, false),
        };

        for (Case testCase : cases) {
            SideEffectProbe probe = new SideEffectProbe();

            boolean initialized = ReadReceiptV2Initializer.initialize(
                    () -> testCase.processLookupFailure ? failLookup() : testCase.processName,
                    () -> testCase.packageLookupFailure ? failLookup() : testCase.packageName,
                    probe::create
            );

            assertFalse(testCase.name, initialized);
            assertEquals(testCase.name, testCase.owner ? 1 : 0, probe.instancesCreated);
            assertEquals(testCase.name, testCase.owner ? 1 : 0, probe.markerAccesses);
            assertEquals(testCase.name, 0, probe.tokenAccesses);
            assertEquals(testCase.name, 0, probe.databaseAccesses);
            assertEquals(testCase.name, 0, probe.coordinatorAllocations);
            assertEquals(testCase.name, 0, probe.workerStarts);
            assertEquals(testCase.name, 0, probe.socketBinds);
            assertEquals(testCase.name, 0, probe.counterMutations);
        }
    }

    @Test
    public void markerLookupFailureFailsClosed() {
        SideEffectProbe probe = new SideEffectProbe();
        probe.markerLookupFailure = true;

        boolean initialized = initializeExactOwner(probe);

        assertFalse(initialized);
        assertEquals(1, probe.instancesCreated);
        assertEquals(1, probe.markerAccesses);
        assertEquals(0, probe.runtimeActivations);
    }

    @Test
    public void enabledMarkerActivatesDownstreamExactlyOnce() {
        SideEffectProbe probe = new SideEffectProbe();
        probe.markerEnabled = true;

        boolean initialized = initializeExactOwner(probe);

        assertTrue(initialized);
        assertEquals(1, probe.instancesCreated);
        assertEquals(1, probe.markerAccesses);
        assertEquals(1, probe.runtimeActivations);
    }

    @Test
    public void runtimeActivationFailureIsContained() {
        SideEffectProbe probe = new SideEffectProbe();
        probe.markerEnabled = true;
        probe.runtimeActivationFailure = true;

        boolean initialized = initializeExactOwner(probe);

        assertFalse(initialized);
        assertEquals(1, probe.instancesCreated);
        assertEquals(1, probe.markerAccesses);
        assertEquals(1, probe.runtimeActivations);
    }

    private static boolean initializeExactOwner(SideEffectProbe probe) {
        return ReadReceiptV2Initializer.initialize(
                () -> KAKAOTALK_PACKAGE,
                () -> KAKAOTALK_PACKAGE,
                probe::create
        );
    }

    private static String failLookup() {
        throw new RuntimeException("lookup failed");
    }

    private static final class Case {
        private final String name;
        private final String processName;
        private final String packageName;
        private final boolean processLookupFailure;
        private final boolean packageLookupFailure;
        private final boolean owner;

        private Case(
                String name,
                String processName,
                String packageName,
                boolean processLookupFailure,
                boolean packageLookupFailure,
                boolean owner
        ) {
            this.name = name;
            this.processName = processName;
            this.packageName = packageName;
            this.processLookupFailure = processLookupFailure;
            this.packageLookupFailure = packageLookupFailure;
            this.owner = owner;
        }
    }

    private static final class SideEffectProbe implements ReadReceiptV2Initializer.SystemSideEffects {
        private int instancesCreated;
        private int markerAccesses;
        private int tokenAccesses;
        private int databaseAccesses;
        private int coordinatorAllocations;
        private int workerStarts;
        private int socketBinds;
        private int counterMutations;
        private int runtimeActivations;
        private boolean markerEnabled;
        private boolean markerLookupFailure;
        private boolean runtimeActivationFailure;

        private ReadReceiptV2Initializer.SystemSideEffects create() {
            instancesCreated++;
            return this;
        }

        @Override
        public boolean isActivationMarkerEnabled() {
            markerAccesses++;
            if (markerLookupFailure) {
                throw new RuntimeException("marker failed");
            }
            return markerEnabled;
        }

        @Override
        public boolean activateEnabledRuntime() {
            runtimeActivations++;
            if (runtimeActivationFailure) {
                throw new RuntimeException("activation failed");
            }
            tokenAccesses++;
            databaseAccesses++;
            coordinatorAllocations++;
            workerStarts++;
            socketBinds++;
            counterMutations++;
            return true;
        }
    }
}
