package com.gtnewhorizons.angelica.dx12.util;

import org.lwjgl.system.MemoryUtil;

public final class Native {
    private Native() {}

    public static void copy(long src, long dst, long bytes) {
        MemoryUtil.memCopy(src, dst, bytes);
    }
}