package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.util.Arrays;
import java.util.regex.Pattern;

final class ReadReceiptPerformanceSandbox {
    private static final String PARENT = "/data/user/0/com.kakao.talk/code_cache";
    private static final Pattern NONCE = Pattern.compile("rrv2-perf-[0-9a-f]{16}");
    private static final int PRIVATE_DIRECTORY_MODE = 0700;

    final String root;
    final String noBackup;
    final String databases;

    private ReadReceiptPerformanceSandbox(String root, String noBackup, String databases) {
        this.root = root;
        this.noBackup = noBackup;
        this.databases = databases;
    }

    static boolean acceptsNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    static String rootFor(String nonce) {
        if (!acceptsNonce(nonce)) throw new IllegalStateException();
        return PARENT + "/" + nonce;
    }

    static ReadReceiptPerformanceSandbox create(String root, int uid) {
        requireExactRoot(root);
        requireDirectory(PARENT, uid);
        File rootFile = new File(root);
        if (rootFile.exists() || !rootFile.mkdir()) throw new IllegalStateException();
        try {
            chmod(root);
            requireDirectory(root, uid);
            String noBackup = root + "/no_backup";
            String databases = root + "/databases";
            createDirectory(noBackup, uid);
            createDirectory(databases, uid);
            return new ReadReceiptPerformanceSandbox(root, noBackup, databases);
        } catch (RuntimeException exception) {
            remove(root, uid);
            throw exception;
        }
    }

    static boolean remove(String root, int uid) {
        try {
            requireExactRoot(root);
            File candidate = new File(root);
            if (!candidate.exists()) return true;
            removeOwned(candidate, uid, root);
            return !candidate.exists();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void createDirectory(String path, int uid) {
        File directory = new File(path);
        if (!directory.mkdir()) throw new IllegalStateException();
        chmod(path);
        requireDirectory(path, uid);
    }

    private static void chmod(String path) {
        try {
            Os.chmod(path, PRIVATE_DIRECTORY_MODE);
        } catch (ErrnoException exception) {
            throw new IllegalStateException();
        }
    }

    private static void requireDirectory(String path, int uid) {
        try {
            StructStat status = Os.lstat(path);
            if (!OsConstants.S_ISDIR(status.st_mode) || status.st_uid != uid) {
                throw new IllegalStateException();
            }
        } catch (ErrnoException exception) {
            throw new IllegalStateException();
        }
    }

    private static void requireExactRoot(String root) {
        if (root == null || !root.startsWith(PARENT + "/")) throw new IllegalStateException();
        String basename = root.substring(PARENT.length() + 1);
        if (!acceptsNonce(basename) || root.indexOf('/', PARENT.length() + 1) >= 0) {
            throw new IllegalStateException();
        }
    }

    private static void removeOwned(File file, int uid, String root) {
        String path = file.getAbsolutePath();
        if (!path.equals(root) && !path.startsWith(root + "/")) throw new IllegalStateException();
        StructStat status;
        try {
            status = Os.lstat(path);
        } catch (ErrnoException exception) {
            if (exception.errno == OsConstants.ENOENT) return;
            throw new IllegalStateException();
        }
        if (status.st_uid != uid || OsConstants.S_ISLNK(status.st_mode)) {
            throw new IllegalStateException();
        }
        if (OsConstants.S_ISDIR(status.st_mode)) {
            File[] children = file.listFiles();
            if (children == null) throw new IllegalStateException();
            Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
            for (File child : children) removeOwned(child, uid, root);
        } else if (!OsConstants.S_ISREG(status.st_mode)) {
            throw new IllegalStateException();
        }
        try {
            Os.remove(path);
        } catch (ErrnoException exception) {
            throw new IllegalStateException();
        }
    }
}
