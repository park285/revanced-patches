package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.security.MessageDigest;

final class ReadReceiptDurableModelSupport {
    private ReadReceiptDurableModelSupport() {
    }

    static long add(long left, long right) {
        return Math.addExact(left, right);
    }

    static long nonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("negative count");
        return value;
    }

    static <T> T required(T value) {
        if (value == null) throw new IllegalArgumentException("required value missing");
        return value;
    }

    static boolean sameNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    static boolean exactDigest(byte[] left, byte[] right) {
        return left != null && right != null && left.length == 32 && right.length == 32
                && MessageDigest.isEqual(left, right);
    }

    static void requireActive(Snapshot snapshot) {
        if (snapshot == null || snapshot.state == null || snapshot.state.status != Status.ACTIVE
                || snapshot.state.lastAckedSequence < 0
                || snapshot.state.highestIssuedSequence < snapshot.state.lastAckedSequence
                || snapshot.state.nextSequence <= snapshot.state.highestIssuedSequence) {
            throw new StorageException();
        }
    }
}
