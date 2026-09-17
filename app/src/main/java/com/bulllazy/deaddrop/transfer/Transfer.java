package com.bulllazy.deaddrop.transfer;

import com.bulllazy.deaddrop.model.FileNameSanitizer;

import java.util.Objects;

/** Mutable, state-validated metadata for one transfer; it never contains file data. */
public final class Transfer {
    private final String transferId;
    private final String fileName;
    private final long totalBytes;
    private final long createdAtMillis;
    private TransferState state;
    private long transferredBytes;
    private long startedAtMillis;
    private long finishedAtMillis;
    private String errorMessage;

    public Transfer(String transferId, String fileName, long totalBytes, long createdAtMillis) {
        if (transferId == null || transferId.trim().isEmpty()) {
            throw new IllegalArgumentException("transferId must not be empty");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (totalBytes < 0L) {
            throw new IllegalArgumentException("totalBytes must not be negative");
        }
        this.transferId = transferId;
        this.fileName = FileNameSanitizer.sanitize(fileName);
        this.totalBytes = totalBytes;
        this.createdAtMillis = createdAtMillis;
        this.state = TransferState.QUEUED;
    }

    public synchronized void transitionTo(TransferState nextState, long nowMillis) {
        Objects.requireNonNull(nextState, "nextState");
        if (!isAllowedTransition(state, nextState)) {
            throw new IllegalStateException("Cannot transition from " + state + " to " + nextState);
        }
        state = nextState;
        if (nextState == TransferState.TRANSFERRING && startedAtMillis == 0L) {
            startedAtMillis = nowMillis;
        }
        if (nextState == TransferState.COMPLETED) {
            transferredBytes = totalBytes;
            finishedAtMillis = nowMillis;
            errorMessage = null;
        } else if (nextState == TransferState.CANCELLED) {
            finishedAtMillis = nowMillis;
        } else if (nextState == TransferState.PREPARING) {
            errorMessage = null;
            finishedAtMillis = 0L;
        }
    }

    /** Marks a recoverable interruption without losing the already transferred byte count. */
    public synchronized void pause(String message, long nowMillis) {
        if (state != TransferState.TRANSFERRING && state != TransferState.PREPARING) {
            throw new IllegalStateException("Only an active transfer can be paused");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Pause message must not be empty");
        }
        errorMessage = message;
        state = TransferState.PAUSED;
        finishedAtMillis = nowMillis;
    }

    public synchronized void updateProgress(long bytesTransferred) {
        if (state != TransferState.TRANSFERRING) {
            throw new IllegalStateException("Progress may only change while transferring");
        }
        if (bytesTransferred < 0L || bytesTransferred > totalBytes) {
            throw new IllegalArgumentException("Progress must be between zero and total bytes");
        }
        transferredBytes = bytesTransferred;
    }

    public synchronized void fail(String message, long nowMillis) {
        if (state.isTerminal()) {
            throw new IllegalStateException("A terminal transfer cannot fail");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Failure message must not be empty");
        }
        errorMessage = message;
        state = TransferState.FAILED;
        finishedAtMillis = nowMillis;
    }

    private boolean isAllowedTransition(TransferState from, TransferState to) {
        switch (from) {
            case QUEUED:
                return to == TransferState.PREPARING || to == TransferState.CANCELLED;
            case PREPARING:
                return to == TransferState.TRANSFERRING || to == TransferState.FAILED
                        || to == TransferState.CANCELLED || to == TransferState.PAUSED;
            case TRANSFERRING:
                return to == TransferState.COMPLETED || to == TransferState.FAILED
                        || to == TransferState.CANCELLED || to == TransferState.PAUSED;
            case PAUSED:
                return to == TransferState.PREPARING || to == TransferState.CANCELLED;
            default:
                return false;
        }
    }

    public String getTransferId() { return transferId; }
    public String getFileName() { return fileName; }
    public long getTotalBytes() { return totalBytes; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public synchronized TransferState getState() { return state; }
    public synchronized long getTransferredBytes() { return transferredBytes; }
    public synchronized long getStartedAtMillis() { return startedAtMillis; }
    public synchronized long getFinishedAtMillis() { return finishedAtMillis; }
    public synchronized String getErrorMessage() { return errorMessage; }

    public synchronized int getProgressPercent() {
        if (totalBytes == 0L) {
            return state == TransferState.COMPLETED ? 100 : 0;
        }
        return (int) ((transferredBytes * 100d) / totalBytes);
    }

    /** Rebuilds persisted metadata without replaying timestamps or file contents. */
    public static Transfer restore(String transferId, String fileName, long totalBytes,
                                   long createdAtMillis, TransferState restoredState,
                                   long restoredBytes, String restoredError,
                                   long restoredStartedAt, long restoredFinishedAt) {
        Transfer transfer = new Transfer(transferId, fileName, totalBytes, createdAtMillis);
        if (restoredState == TransferState.QUEUED) {
            return transfer;
        }
        transfer.state = restoredState;
        transfer.transferredBytes = Math.max(0L, Math.min(restoredBytes, totalBytes));
        transfer.errorMessage = restoredError;
        transfer.startedAtMillis = restoredStartedAt;
        transfer.finishedAtMillis = restoredFinishedAt;
        if (restoredState == TransferState.COMPLETED) {
            transfer.transferredBytes = totalBytes;
        }
        return transfer;
    }
}
