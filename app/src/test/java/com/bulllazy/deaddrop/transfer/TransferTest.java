package com.bulllazy.deaddrop.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class TransferTest {
    @Test
    public void newTransferStartsQueued() {
        Transfer transfer = transferWithSize(100L);
        assertEquals(TransferState.QUEUED, transfer.getState());
        assertEquals(0L, transfer.getTransferredBytes());
        assertNull(transfer.getErrorMessage());
    }

    @Test
    public void queuedCanPrepareThenStart() {
        Transfer transfer = transferWithSize(100L);
        transfer.transitionTo(TransferState.PREPARING, 200L);
        transfer.transitionTo(TransferState.TRANSFERRING, 300L);
        assertEquals(TransferState.TRANSFERRING, transfer.getState());
        assertEquals(300L, transfer.getStartedAtMillis());
    }

    @Test
    public void transferringCanComplete() {
        Transfer transfer = transferring(100L);
        transfer.updateProgress(75L);
        transfer.transitionTo(TransferState.COMPLETED, 500L);
        assertEquals(TransferState.COMPLETED, transfer.getState());
        assertEquals(100L, transfer.getTransferredBytes());
        assertEquals(100, transfer.getProgressPercent());
        assertEquals(500L, transfer.getFinishedAtMillis());
    }

    @Test
    public void transferringCanFailWithError() {
        Transfer transfer = transferring(100L);
        transfer.fail("Connection lost", 500L);
        assertEquals(TransferState.FAILED, transfer.getState());
        assertEquals("Connection lost", transfer.getErrorMessage());
    }

    @Test
    public void queuedTransferCanBeCancelled() {
        Transfer transfer = transferWithSize(100L);
        transfer.transitionTo(TransferState.CANCELLED, 400L);
        assertEquals(TransferState.CANCELLED, transfer.getState());
        assertEquals(400L, transfer.getFinishedAtMillis());
    }

    @Test
    public void progressUsesIntegerPercentage() {
        Transfer transfer = transferring(1000L);
        transfer.updateProgress(333L);
        assertEquals(33, transfer.getProgressPercent());
    }

    @Test
    public void zeroByteTransferIsCompleteOnlyAfterCompletion() {
        Transfer transfer = transferring(0L);
        assertEquals(0, transfer.getProgressPercent());
        transfer.transitionTo(TransferState.COMPLETED, 400L);
        assertEquals(100, transfer.getProgressPercent());
    }

    @Test
    public void largeFileProgressDoesNotOverflow() {
        long largeSize = Long.MAX_VALUE - 1L;
        Transfer transfer = transferring(largeSize);
        transfer.updateProgress(largeSize / 2L);
        assertEquals(50, transfer.getProgressPercent());
    }

    @Test(expected = IllegalStateException.class)
    public void completedTransferCannotTransitionAgain() {
        Transfer transfer = transferring(1L);
        transfer.transitionTo(TransferState.COMPLETED, 400L);
        transfer.transitionTo(TransferState.CANCELLED, 500L);
    }

    private Transfer transferWithSize(long size) {
        return new Transfer("transfer-1", "sample.bin", size, 100L);
    }

    private Transfer transferring(long size) {
        Transfer transfer = transferWithSize(size);
        transfer.transitionTo(TransferState.PREPARING, 200L);
        transfer.transitionTo(TransferState.TRANSFERRING, 300L);
        return transfer;
    }
}
