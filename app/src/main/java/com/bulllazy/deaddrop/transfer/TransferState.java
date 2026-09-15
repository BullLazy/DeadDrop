package com.bulllazy.deaddrop.transfer;

/** Lifecycle states for a file transfer. */
public enum TransferState {
    QUEUED("Kuyrukta"),
    PREPARING("Hazırlanıyor"),
    TRANSFERRING("Aktarılıyor"),
    COMPLETED("Tamamlandı"),
    FAILED("Başarısız"),
    CANCELLED("İptal edildi");

    private final String displayName;

    TransferState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
