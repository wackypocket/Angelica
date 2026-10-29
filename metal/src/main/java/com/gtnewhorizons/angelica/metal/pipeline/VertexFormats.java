package com.gtnewhorizons.angelica.metal.pipeline;

import com.gtnewhorizons.angelica.metal.device.Mtl;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

public final class VertexFormats {
    private VertexFormats() {}

            public static int mtlFormat(int size, int type, boolean normalized, boolean integerInput) {
        if (size == GL12.GL_BGRA) return Mtl.VF_UCHAR4_NORM_BGRA;
        final int n = Math.max(1, Math.min(4, size));
        final boolean norm = normalized && !integerInput;
        return switch (type) {
            case GL11.GL_FLOAT -> pick(n, Mtl.VF_FLOAT, Mtl.VF_FLOAT2, Mtl.VF_FLOAT3, Mtl.VF_FLOAT4);
            case GL30.GL_HALF_FLOAT, GL41.GL_FIXED -> pick(n, Mtl.VF_HALF, Mtl.VF_HALF2, Mtl.VF_HALF3, Mtl.VF_HALF4);
            case GL11.GL_UNSIGNED_BYTE -> norm ? pick(n, Mtl.VF_UCHAR_NORM, Mtl.VF_UCHAR2_NORM, Mtl.VF_UCHAR3_NORM, Mtl.VF_UCHAR4_NORM)
                : pick(n, Mtl.VF_UCHAR, Mtl.VF_UCHAR2, Mtl.VF_UCHAR3, Mtl.VF_UCHAR4);
            case GL11.GL_BYTE -> norm ? pick(n, Mtl.VF_CHAR_NORM, Mtl.VF_CHAR2_NORM, Mtl.VF_CHAR3_NORM, Mtl.VF_CHAR4_NORM)
                : pick(n, Mtl.VF_CHAR, Mtl.VF_CHAR2, Mtl.VF_CHAR3, Mtl.VF_CHAR4);
            case GL11.GL_UNSIGNED_SHORT -> norm ? pick(n, Mtl.VF_USHORT_NORM, Mtl.VF_USHORT2_NORM, Mtl.VF_USHORT3_NORM, Mtl.VF_USHORT4_NORM)
                : pick(n, Mtl.VF_USHORT, Mtl.VF_USHORT2, Mtl.VF_USHORT3, Mtl.VF_USHORT4);
            case GL11.GL_SHORT -> norm ? pick(n, Mtl.VF_SHORT_NORM, Mtl.VF_SHORT2_NORM, Mtl.VF_SHORT3_NORM, Mtl.VF_SHORT4_NORM)
                : pick(n, Mtl.VF_SHORT, Mtl.VF_SHORT2, Mtl.VF_SHORT3, Mtl.VF_SHORT4);
            case GL11.GL_UNSIGNED_INT -> pick(n, Mtl.VF_UINT, Mtl.VF_UINT2, Mtl.VF_UINT3, Mtl.VF_UINT4);
            case GL11.GL_INT -> pick(n, Mtl.VF_INT, Mtl.VF_INT2, Mtl.VF_INT3, Mtl.VF_INT4);
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV -> Mtl.VF_UINT1010102_NORM;
            case GL33.GL_INT_2_10_10_10_REV -> Mtl.VF_INT1010102_NORM;
            case GL30.GL_UNSIGNED_INT_10F_11F_11F_REV -> Mtl.VF_FLOAT_RG11B10;
            case GL11.GL_DOUBLE -> Mtl.VF_INVALID;
            default -> Mtl.VF_FLOAT4;
        };
    }

    public static boolean exact(int size, int type, boolean normalized) {
        return switch (type) {
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV, GL33.GL_INT_2_10_10_10_REV -> normalized;
            case GL11.GL_DOUBLE -> false;
            default -> true;
        };
    }

    public static boolean isIntegerFormat(int mtl) {
        return switch (mtl) {
            case Mtl.VF_UCHAR, Mtl.VF_UCHAR2, Mtl.VF_UCHAR3, Mtl.VF_UCHAR4, Mtl.VF_CHAR, Mtl.VF_CHAR2, Mtl.VF_CHAR3, Mtl.VF_CHAR4,
                 Mtl.VF_USHORT, Mtl.VF_USHORT2, Mtl.VF_USHORT3, Mtl.VF_USHORT4, Mtl.VF_SHORT, Mtl.VF_SHORT2, Mtl.VF_SHORT3, Mtl.VF_SHORT4,
                 Mtl.VF_UINT, Mtl.VF_UINT2, Mtl.VF_UINT3, Mtl.VF_UINT4, Mtl.VF_INT, Mtl.VF_INT2, Mtl.VF_INT3, Mtl.VF_INT4 -> true;
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
