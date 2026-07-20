package app.revanced.extension.kakaotalk.chatlog.readreceipt;

final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StorageException() {
        super("storage operation failed");
    }
}

final class RetrySchedulingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RetrySchedulingException() {
        super("retry scheduling contract failed");
    }
}

final class DurabilityContractException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DurabilityContractException() {
        super("durability contract failed");
    }
}

final class ProtocolContractException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ProtocolContractException() {
        super("protocol contract failed");
    }
}

final class PendingJournalContractException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PendingJournalContractException() {
        super("pending journal contract failed");
    }
}
