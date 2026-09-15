package com.bulllazy.deaddrop.transfer;

import com.bulllazy.deaddrop.model.SelectedFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Coordinates transfer metadata and state; transport belongs in a future layer. */
public final class TransferManager {
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();

    public synchronized Transfer createTransfer(SelectedFile selectedFile) {
        Transfer transfer = new Transfer(UUID.randomUUID().toString(), selectedFile.getDisplayName(),
                selectedFile.getSizeBytes(), selectedFile.getSelectedAtMillis());
        transfers.put(transfer.getTransferId(), transfer);
        return transfer;
    }

    public synchronized Transfer getTransfer(String transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Unknown transfer: " + transferId);
        }
        return transfer;
    }

    public void prepare(String id, long now) { getTransfer(id).transitionTo(TransferState.PREPARING, now); }
    public void start(String id, long now) { getTransfer(id).transitionTo(TransferState.TRANSFERRING, now); }
    public void updateProgress(String id, long bytes) { getTransfer(id).updateProgress(bytes); }
    public void complete(String id, long now) { getTransfer(id).transitionTo(TransferState.COMPLETED, now); }
    public void fail(String id, String error, long now) { getTransfer(id).fail(error, now); }
    public void cancel(String id, long now) { getTransfer(id).transitionTo(TransferState.CANCELLED, now); }

    public synchronized Map<String, Transfer> getTransfers() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(transfers));
    }
}
