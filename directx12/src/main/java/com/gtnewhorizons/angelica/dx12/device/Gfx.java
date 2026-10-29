package com.gtnewhorizons.angelica.dx12.device;

public final class Gfx implements com.gtnewhorizons.angelica.glemu.resource.PixelFormats {
    private Gfx() {}

    public static final int TEX_1D = 0, TEX_1D_ARRAY = 1, TEX_2D = 2, TEX_2D_ARRAY = 3, TEX_CUBE = 5, TEX_CUBE_ARRAY = 6, TEX_3D = 7;

    public static final int USAGE_SHADER_READ = 0x1, USAGE_SHADER_WRITE = 0x2, USAGE_RENDER_TARGET = 0x4, USAGE_PIXEL_FORMAT_VIEW = 0x10, USAGE_SHADER_ATOMIC = 0x20;

    public static final int STORAGE_SHARED = 0, STORAGE_MANAGED = 1, STORAGE_PRIVATE = 2;
    public static final long RESOURCE_STORAGE_SHARED = 0L, RESOURCE_STORAGE_PRIVATE = 2L << 4;
    public static final long RESOURCE_CPU_WRITE_COMBINED = 1L;

    public static final int LOAD_DONT_CARE = 0, LOAD_LOAD = 1, LOAD_CLEAR = 2;
    public static final int STORE_DONT_CARE = 0, STORE_STORE = 1, STORE_UNKNOWN = 4;

    public static final int PRIM_POINT = 0, PRIM_LINE = 1, PRIM_LINE_STRIP = 2, PRIM_TRIANGLE = 3, PRIM_TRIANGLE_STRIP = 4;
    public static final int TOPOLOGY_CLASS_UNSPECIFIED = 0, TOPOLOGY_CLASS_POINT = 1, TOPOLOGY_CLASS_LINE = 2, TOPOLOGY_CLASS_TRIANGLE = 3;

    public static final int INDEX_UINT16 = 0, INDEX_UINT32 = 1;

    public static final int CULL_NONE = 0, CULL_FRONT = 1, CULL_BACK = 2;
    public static final int WINDING_CLOCKWISE = 0, WINDING_COUNTER_CLOCKWISE = 1;
    public static final int DEPTH_CLIP = 0, DEPTH_CLAMP = 1;
    public static final int FILL_FILL = 0, FILL_LINES = 1;

    public static final int COMPARE_NEVER = 0, COMPARE_LESS = 1, COMPARE_EQUAL = 2, COMPARE_LESS_EQUAL = 3, COMPARE_GREATER = 4,
        COMPARE_NOT_EQUAL = 5, COMPARE_GREATER_EQUAL = 6, COMPARE_ALWAYS = 7;

    public static final int STENCIL_KEEP = 0, STENCIL_ZERO = 1, STENCIL_REPLACE = 2, STENCIL_INCREMENT_CLAMP = 3, STENCIL_DECREMENT_CLAMP = 4,
        STENCIL_INVERT = 5, STENCIL_INCREMENT_WRAP = 6, STENCIL_DECREMENT_WRAP = 7;

    public static final int BLEND_ZERO = 0, BLEND_ONE = 1, BLEND_SRC_COLOR = 2, BLEND_ONE_MINUS_SRC_COLOR = 3, BLEND_SRC_ALPHA = 4,
        BLEND_ONE_MINUS_SRC_ALPHA = 5, BLEND_DST_COLOR = 6, BLEND_ONE_MINUS_DST_COLOR = 7, BLEND_DST_ALPHA = 8, BLEND_ONE_MINUS_DST_ALPHA = 9,
        BLEND_SRC_ALPHA_SATURATED = 10, BLEND_BLEND_COLOR = 11, BLEND_ONE_MINUS_BLEND_COLOR = 12, BLEND_BLEND_ALPHA = 13, BLEND_ONE_MINUS_BLEND_ALPHA = 14,
        BLEND_SRC1_COLOR = 15, BLEND_ONE_MINUS_SRC1_COLOR = 16, BLEND_SRC1_ALPHA = 17, BLEND_ONE_MINUS_SRC1_ALPHA = 18;
    public static final int BLEND_OP_ADD = 0, BLEND_OP_SUBTRACT = 1, BLEND_OP_REVERSE_SUBTRACT = 2, BLEND_OP_MIN = 3, BLEND_OP_MAX = 4;
    public static final int WRITE_RED = 1 << 3, WRITE_GREEN = 1 << 2, WRITE_BLUE = 1 << 1, WRITE_ALPHA = 1;

    public static final int VF_INVALID = 0;
    public static final int VF_UCHAR2 = 1, VF_UCHAR3 = 2, VF_UCHAR4 = 3, VF_CHAR2 = 4, VF_CHAR3 = 5, VF_CHAR4 = 6;
    public static final int VF_UCHAR2_NORM = 7, VF_UCHAR3_NORM = 8, VF_UCHAR4_NORM = 9, VF_CHAR2_NORM = 10, VF_CHAR3_NORM = 11, VF_CHAR4_NORM = 12;
    public static final int VF_USHORT2 = 13, VF_USHORT3 = 14, VF_USHORT4 = 15, VF_SHORT2 = 16, VF_SHORT3 = 17, VF_SHORT4 = 18;
    public static final int VF_USHORT2_NORM = 19, VF_USHORT3_NORM = 20, VF_USHORT4_NORM = 21, VF_SHORT2_NORM = 22, VF_SHORT3_NORM = 23, VF_SHORT4_NORM = 24;
    public static final int VF_HALF2 = 25, VF_HALF3 = 26, VF_HALF4 = 27;
    public static final int VF_FLOAT = 28, VF_FLOAT2 = 29, VF_FLOAT3 = 30, VF_FLOAT4 = 31;
    public static final int VF_INT = 32, VF_INT2 = 33, VF_INT3 = 34, VF_INT4 = 35, VF_UINT = 36, VF_UINT2 = 37, VF_UINT3 = 38, VF_UINT4 = 39;
    public static final int VF_INT1010102_NORM = 40, VF_UINT1010102_NORM = 41, VF_UCHAR4_NORM_BGRA = 42;
    public static final int VF_UCHAR = 45, VF_CHAR = 46, VF_UCHAR_NORM = 47, VF_CHAR_NORM = 48, VF_USHORT = 49, VF_SHORT = 50,
        VF_USHORT_NORM = 51, VF_SHORT_NORM = 52, VF_HALF = 53, VF_FLOAT_RG11B10 = 54, VF_FLOAT_RGB9E5 = 55;

    public static final int STEP_CONSTANT = 0, STEP_PER_VERTEX = 1, STEP_PER_INSTANCE = 2;

    public static final int FILTER_NEAREST = 0, FILTER_LINEAR = 1;
    public static final int MIP_NOT_MIPMAPPED = 0, MIP_NEAREST = 1, MIP_LINEAR = 2;
    public static final int ADDRESS_CLAMP_TO_EDGE = 0, ADDRESS_MIRROR_CLAMP_TO_EDGE = 1, ADDRESS_REPEAT = 2, ADDRESS_MIRROR_REPEAT = 3,
        ADDRESS_CLAMP_TO_ZERO = 4, ADDRESS_CLAMP_TO_BORDER = 5;
    public static final int BORDER_TRANSPARENT_BLACK = 0, BORDER_OPAQUE_BLACK = 1, BORDER_OPAQUE_WHITE = 2;

    public static final int BLIT_NONE = 0, BLIT_DEPTH_FROM_DEPTH_STENCIL = 1, BLIT_STENCIL_FROM_DEPTH_STENCIL = 2;

    public static final int CB_NOT_ENQUEUED = 0, CB_ENQUEUED = 1, CB_COMMITTED = 2, CB_SCHEDULED = 3, CB_COMPLETED = 4, CB_ERROR = 5;

    public static final long LANGUAGE_2_3 = (2L << 16) + 3, LANGUAGE_3_0 = 3L << 16, LANGUAGE_3_1 = (3L << 16) + 1;

    public static final long GPU_FAMILY_APPLE7 = 1007, GPU_FAMILY_MAC2 = 2002, GPU_FAMILY_Dx123 = 5001;
}
