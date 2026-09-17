package com.bulllazy.deaddrop.transfer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TransferResumeTest {
    @Test
    public void interruptionCanResumeWithoutLosingProgress() {
        Transfer transfer = new Transfer("id", "file.bin", 100L, 1L);
        transfer.transitionTo(TransferState.PREPARING, 2L);
        transfer.transitionTo(TransferState.TRANSFERRING, 3L);
        transfer.updateProgress(40L);
        transfer.pause("Connection lost", 4L);
        assertEquals(TransferState.PAUSED, transfer.getState());
        assertEquals(40L, transfer.getTransferredBytes());
        transfer.transitionTo(TransferState.PREPARING, 5L);
        transfer.transitionTo(TransferState.TRANSFERRING, 6L);
        transfer.updateProgress(100L);
        transfer.transitionTo(TransferState.COMPLETED, 7L);
        assertEquals(100, transfer.getProgressPercent());
    }

    @Test
    public void restoredActiveStateIsAvailableToLifecycleOwner() {
        Transfer transfer = Transfer.restore("id", "file.bin", 200L, 1L,
                TransferState.PAUSED, 75L, "interrupted", 3L, 4L);
        assertEquals(TransferState.PAUSED, transfer.getState());
        assertEquals(75L, transfer.getTransferredBytes());
        assertEquals(37, transfer.getProgressPercent());
    }
}
