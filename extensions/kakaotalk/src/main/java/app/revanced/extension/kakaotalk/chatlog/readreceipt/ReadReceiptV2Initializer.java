package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.io.File;

public final class ReadReceiptV2Initializer {

    private static final String KAKAOTALK_PACKAGE = "com.kakao.talk";
    private static final Object RUNTIME_LOCK = new Object();
    private static boolean runtimeAttempted;
    private static ReadReceiptV2Runtime activeRuntime;

    private ReadReceiptV2Initializer() {
    }

    public static boolean initialize(Context context) {
        return initialize(
                Application::getProcessName,
                () -> {
                    ApplicationInfo applicationInfo = context.getApplicationInfo();
                    return applicationInfo == null ? null : applicationInfo.packageName;
                },
                () -> new DeferredSystemSideEffects(
                        context.getNoBackupFilesDir().getAbsolutePath(),
                        databaseDirectory(context),
                        Process.myUid()
                )
        );
    }

    static boolean initialize(
            ValueLookup processNameLookup,
            ValueLookup packageNameLookup,
            SystemSideEffectsFactory sideEffectsFactory
    ) {
        String processName = lookup(processNameLookup);
        if (!KAKAOTALK_PACKAGE.equals(processName)) {
            return false;
        }

        String packageName = lookup(packageNameLookup);
        if (!processName.equals(packageName)) {
            return false;
        }

        final SystemSideEffects sideEffects;
        try {
            sideEffects = sideEffectsFactory.create();
        } catch (RuntimeException ignored) {
            return false;
        }

        final boolean markerEnabled;
        try {
            markerEnabled = sideEffects.isActivationMarkerEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (!markerEnabled) {
            return false;
        }

        try {
            return sideEffects.activateEnabledRuntime();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String lookup(ValueLookup lookup) {
        try {
            return lookup.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String databaseDirectory(Context context) {
        File database = context.getDatabasePath("iris_read_receipts.db");
        File parent = database == null ? null : database.getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    interface ValueLookup {
        String get();
    }

    interface SystemSideEffectsFactory {
        SystemSideEffects create();
    }

    interface SystemSideEffects {
        boolean isActivationMarkerEnabled();

        boolean activateEnabledRuntime();
    }

    private static final class DeferredSystemSideEffects implements SystemSideEffects {
        private final ReadReceiptFileOps fileOps = new AndroidReadReceiptFileOps();
        private final String noBackupDirectory;
        private final String databaseDirectory;
        private final int uid;

        private DeferredSystemSideEffects(String noBackupDirectory, String databaseDirectory, int uid) {
            this.noBackupDirectory = noBackupDirectory;
            this.databaseDirectory = databaseDirectory;
            this.uid = uid;
        }

        @Override
        public boolean isActivationMarkerEnabled() {
            return ReadReceiptMarkers.isActivationEnabled(fileOps, noBackupDirectory, uid);
        }

        @Override
        public boolean activateEnabledRuntime() {
            if (databaseDirectory.isEmpty()) return false;
            synchronized (RUNTIME_LOCK) {
                if (activeRuntime != null) return activeRuntime.healthy();
                if (runtimeAttempted) return false;
                runtimeAttempted = true;
            }
            ReadReceiptAndroidBootstrapOps.ActivationContext activation =
                    ReadReceiptAndroidBootstrapOps.activate(
                    fileOps,
                    new ReadReceiptAndroidSqlite(),
                    new ReadReceiptNativeFs(),
                    new ReadReceiptAndroidBootstrapOps.SecureRandomSource(),
                    System::currentTimeMillis,
                    noBackupDirectory,
                    databaseDirectory,
                    uid
            );
            if (activation == null) {
                return false;
            }
            ReadReceiptStartupValidation validation = new ReadReceiptStartupValidation(
                    new ReadReceiptAndroidStartupStore(
                            databaseDirectory + "/iris_read_receipts.db"),
                    () -> ReadReceiptV2Runtime.prepare(
                            activation.token(),
                            noBackupDirectory,
                            databaseDirectory,
                            fileOps,
                            uid)
            );
            ReadReceiptStartupValidation.Result result = validation.validateAndStart();
            if (result.outcome != ReadReceiptStartupValidation.Outcome.READY
                    || !(result.activation instanceof ReadReceiptV2Runtime)) return false;
            ReadReceiptV2Runtime readyRuntime = (ReadReceiptV2Runtime) result.activation;
            if (!readyRuntime.healthy()) return false;
            synchronized (RUNTIME_LOCK) {
                activeRuntime = readyRuntime;
            }
            return readyRuntime.healthy();
        }
    }
}
