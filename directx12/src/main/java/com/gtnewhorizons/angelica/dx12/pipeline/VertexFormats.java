package com.gtnewhorizons.angelica.dx12.pipeline;

import com.gtnewhorizons.angelica.dx12.device.Gfx;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

public final class VertexFormats {
    private VertexFormats() {}

            public static int gfxFormat(int size, int type, boolean normalized, boolean integerInput) {
        if (size == GL12.GL_BGRA) return Gfx.VF_UCHAR4_NORM_BGRA;
        final int n = Math.max(1, Math.min(4, size));
        final boolean norm = normalized && !integerInput;
        return switch (type) {
            case GL11.GL_FLOAT -> pick(n, Gfx.VF_FLOAT, Gfx.VF_FLOAT2, Gfx.VF_FLOAT3, Gfx.VF_FLOAT4);
            case GL30.GL_HALF_FLOAT, GL41.GL_FIXED -> pick(n, Gfx.VF_HALF, Gfx.VF_HALF2, Gfx.VF_HALF3, Gfx.VF_HALF4);
            case GL11.GL_UNSIGNED_BYTE -> norm ? pick(n, Gfx.VF_UCHAR_NORM, Gfx.VF_UCHAR2_NORM, Gfx.VF_UCHAR3_NORM, Gfx.VF_UCHAR4_NORM)
                : pick(n, Gfx.VF_UCHAR, Gfx.VF_UCHAR2, Gfx.VF_UCHAR3, Gfx.VF_UCHAR4);
            case GL11.GL_BYTE -> norm ? pick(n, Gfx.VF_CHAR_NORM, Gfx.VF_CHAR2_NORM, Gfx.VF_CHAR3_NORM, Gfx.VF_CHAR4_NORM)
                : pick(n, Gfx.VF_CHAR, Gfx.VF_CHAR2, Gfx.VF_CHAR3, Gfx.VF_CHAR4);
            case GL11.GL_UNSIGNED_SHORT -> norm ? pick(n, Gfx.VF_USHORT_NORM, Gfx.VF_USHORT2_NORM, Gfx.VF_USHORT3_NORM, Gfx.VF_USHORT4_NORM)
                : pick(n, Gfx.VF_USHORT, Gfx.VF_USHORT2, Gfx.VF_USHORT3, Gfx.VF_USHORT4);
            case GL11.GL_SHORT -> norm ? pick(n, Gfx.VF_SHORT_NORM, Gfx.VF_SHORT2_NORM, Gfx.VF_SHORT3_NORM, Gfx.VF_SHORT4_NORM)
                : pick(n, Gfx.VF_SHORT, Gfx.VF_SHORT2, Gfx.VF_SHORT3, Gfx.VF_SHORT4);
            case GL11.GL_UNSIGNED_INT -> pick(n, Gfx.VF_UINT, Gfx.VF_UINT2, Gfx.VF_UINT3, Gfx.VF_UINT4);
            case GL11.GL_INT -> pick(n, Gfx.VF_INT, Gfx.VF_INT2, Gfx.VF_INT3, Gfx.VF_INT4);
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV -> Gfx.VF_UINT1010102_NORM;
            case GL33.GL_INT_2_10_10_10_REV -> Gfx.VF_INT1010102_NORM;
            case GL30.GL_UNSIGNED_INT_10F_11F_11F_REV -> Gfx.VF_FLOAT_RG11B10;
            case GL11.GL_DOUBLE -> Gfx.VF_INVALID;
            default -> Gfx.VF_FLOAT4;
        };
    }

    public static boolean exact(int size, int type, boolean normalized) {
        return switch (type) {
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV, GL33.GL_INT_2_10_10_10_REV -> normalized;
            case GL11.GL_DOUBLE -> false;
            default -> true;
        };
    }

    public static boolean isIntegerFormat(int fmt) {
        return switch (fmt) {
            case Gfx.VF_UCHAR, Gfx.VF_UCHAR2, Gfx.VF_UCHAR3, Gfx.VF_UCHAR4, Gfx.VF_CHAR, Gfx.VF_CHAR2, Gfx.VF_CHAR3, Gfx.VF_CHAR4,
                 Gfx.VF_USHORT, Gfx.VF_USHORT2, Gfx.VF_USHORT3, Gfx.VF_USHORT4, Gfx.VF_SHORT, Gfx.VF_SHORT2, Gfx.VF_SHORT3, Gfx.VF_SHORT4,
                 Gfx.VF_UINT, Gfx.VF_UINT2, Gfx.VF_UINT3, Gfx.VF_UINT4, Gfx.VF_INT, Gfx.VF_INT2, Gfx.VF_INT3, Gfx.VF_INT4 -> true;
            default -> false;
        };
    }

    public static boolean isSignedIntegerFormat(int fmt) {
        return switch (fmt) {
            case Gfx.VF_CHAR, Gfx.VF_CHAR2, Gfx.VF_CHAR3, Gfx.VF_CHAR4, Gfx.VF_SHORT, Gfx.VF_SHORT2, Gfx.VF_SHORT3, Gfx.VF_SHORT4,
                 Gfx.VF_INT, Gfx.VF_INT2, Gfx.VF_INT3, Gfx.VF_INT4 -> true;
            default -> false;
        };
    }

    private static int pick(int n, int a, int b, int c, int d) {
        return switch (n) {
            case 1 -> a;
            case 2 -> b;
            case 3 -> c;
            default -> d;
        };
    }
}
