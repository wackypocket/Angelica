package com.gtnewhorizons.angelica.metal.device;

import com.gtnewhorizons.angelica.glemu.gl.NativeTokens;

public final class Mtl implements NativeTokens {
    private Mtl() {}

    public static final int STORAGE_PRIVATE = 2;
    public static final long RESOURCE_STORAGE_SHARED = 0L, RESOURCE_STORAGE_PRIVATE = 2L << 4;
    public static final long RESOURCE_CPU_WRITE_COMBINED = 1L;

    public static final int LOAD_DONT_CARE = 0, LOAD_LOAD = 1, LOAD_CLEAR = 2;
    public static final int STORE_DONT_CARE = 0, STORE_STORE = 1, STORE_UNKNOWN = 4, STORE_RESOLVE_AND_KEEP = 3;
    public static final int WINDING_CLOCKWISE = 0, WINDING_COUNTER_CLOCKWISE = 1;
    public static final int DEPTH_CLIP = 0, DEPTH_CLAMP = 1;
    public static final int FILL_FILL = 0, FILL_LINES = 1;

    public static final int BORDER_TRANSPARENT_BLACK = 0, BORDER_OPAQUE_BLACK = 1, BORDER_OPAQUE_WHITE = 2;

    public static final int CB_COMPLETED = 4, CB_ERROR = 5;

    public static final long LANGUAGE_2_3 = (2L << 16) + 3, LANGUAGE_3_1 = (3L << 16) + 1;

    public static final long GPU_FAMILY_APPLE7 = 1007, GPU_FAMILY_MAC2 = 2002, GPU_FAMILY_METAL3 = 5001;
}
