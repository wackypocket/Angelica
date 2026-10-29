package com.gtnewhorizons.angelica.dx12.util;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public final class DebugNames {
    private static final int SET_NAME = 6;

    private final boolean enabled;

    public DebugNames(Dx12GL gl, boolean enabled) {
        this.enabled = enabled;
    }

    public void name(long object, String name) {
        if (!enabled || object == 0L || name == null) return;
        try (Arena a = Arena.ofConfined()) {
            final byte[] utf16 = (name + "\0").getBytes(StandardCharsets.UTF_16LE);
            final MemorySegment s = a.allocate(utf16.length, 2);
            MemorySegment.copy(utf16, 0, s, JAVA_BYTE, 0, utf16.length);
            Com.hr(object, SET_NAME, s.address());
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void push(String label) {
    }

    public void pop() {
    }

    public void closeAll() {
    }
}
