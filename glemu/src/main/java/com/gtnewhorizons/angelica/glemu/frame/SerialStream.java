package com.gtnewhorizons.angelica.glemu.frame;

public interface SerialStream {
    long currentSerial();

    boolean isIdle(long serial);

    void defer(Runnable action);
}
