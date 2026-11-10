package com.gtnewhorizons.angelica.glemu.gl;

/** Where a device layer sends what its driver or validation layer reports, as GL debug message types and severities. */
@FunctionalInterface
public interface DriverMessageSink {
    void message(int type, int severity, String message);
}
