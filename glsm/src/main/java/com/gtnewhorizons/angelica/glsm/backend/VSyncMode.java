package com.gtnewhorizons.angelica.glsm.backend;

public enum VSyncMode {
    OFF,
    ON,
    MAILBOX,
    FIFO_LATEST_READY;

    public boolean tearFree() {
        return this != OFF;
    }
}
