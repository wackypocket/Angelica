package com.gtnewhorizons.angelica.glemu.pipeline;

import com.gtnewhorizons.angelica.glemu.gl.NativeTokens;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

import static com.gtnewhorizons.angelica.glemu.resource.Formats.pick;

public final class TokenVertexFormats {
    private TokenVertexFormats() {}

    /**
     * Metal refuses an integer attribute of the other signedness than the shader's input, which GL lets stand. Taking the bits
     * as the shader's kind is right for every value both kinds can hold.
     */
    public static int signedAs(int inputKind, int format) {
        if (inputKind == 1) {
            if (format == NativeTokens.VF_UCHAR) return NativeTokens.VF_CHAR;
            if (format == NativeTokens.VF_UCHAR2) return NativeTokens.VF_CHAR2;
            if (format == NativeTokens.VF_UCHAR3) return NativeTokens.VF_CHAR3;
            if (format == NativeTokens.VF_UCHAR4) return NativeTokens.VF_CHAR4;
            if (format == NativeTokens.VF_USHORT) return NativeTokens.VF_SHORT;
            if (format == NativeTokens.VF_USHORT2) return NativeTokens.VF_SHORT2;
            if (format == NativeTokens.VF_USHORT3) return NativeTokens.VF_SHORT3;
            if (format == NativeTokens.VF_USHORT4) return NativeTokens.VF_SHORT4;
            if (format == NativeTokens.VF_UINT) return NativeTokens.VF_INT;
            if (format == NativeTokens.VF_UINT2) return NativeTokens.VF_INT2;
            if (format == NativeTokens.VF_UINT3) return NativeTokens.VF_INT3;
            if (format == NativeTokens.VF_UINT4) return NativeTokens.VF_INT4;
        } else if (inputKind == 2) {
            if (format == NativeTokens.VF_CHAR) return NativeTokens.VF_UCHAR;
            if (format == NativeTokens.VF_CHAR2) return NativeTokens.VF_UCHAR2;
            if (format == NativeTokens.VF_CHAR3) return NativeTokens.VF_UCHAR3;
            if (format == NativeTokens.VF_CHAR4) return NativeTokens.VF_UCHAR4;
            if (format == NativeTokens.VF_SHORT) return NativeTokens.VF_USHORT;
            if (format == NativeTokens.VF_SHORT2) return NativeTokens.VF_USHORT2;
            if (format == NativeTokens.VF_SHORT3) return NativeTokens.VF_USHORT3;
            if (format == NativeTokens.VF_SHORT4) return NativeTokens.VF_USHORT4;
            if (format == NativeTokens.VF_INT) return NativeTokens.VF_UINT;
            if (format == NativeTokens.VF_INT2) return NativeTokens.VF_UINT2;
            if (format == NativeTokens.VF_INT3) return NativeTokens.VF_UINT3;
            if (format == NativeTokens.VF_INT4) return NativeTokens.VF_UINT4;
        }
        return format;
    }

    public static int format(int size, int type, boolean normalized, boolean integerInput) {
        if (size == GL12.GL_BGRA) return NativeTokens.VF_UCHAR4_NORM_BGRA;
        final int n = Math.max(1, Math.min(4, size));
        final boolean norm = normalized && !integerInput;
        return switch (type) {
            case GL11.GL_FLOAT -> pick(n, NativeTokens.VF_FLOAT, NativeTokens.VF_FLOAT2, NativeTokens.VF_FLOAT3, NativeTokens.VF_FLOAT4);
            case GL41.GL_FIXED -> pick(n, NativeTokens.VF_FLOAT, NativeTokens.VF_FLOAT2, NativeTokens.VF_FLOAT3, NativeTokens.VF_FLOAT4);
            case GL30.GL_HALF_FLOAT -> pick(n, NativeTokens.VF_HALF, NativeTokens.VF_HALF2, NativeTokens.VF_HALF3, NativeTokens.VF_HALF4);
            case GL11.GL_UNSIGNED_BYTE -> norm ? pick(n, NativeTokens.VF_UCHAR_NORM, NativeTokens.VF_UCHAR2_NORM, NativeTokens.VF_UCHAR3_NORM, NativeTokens.VF_UCHAR4_NORM)
                : pick(n, NativeTokens.VF_UCHAR, NativeTokens.VF_UCHAR2, NativeTokens.VF_UCHAR3, NativeTokens.VF_UCHAR4);
            case GL11.GL_BYTE -> norm ? pick(n, NativeTokens.VF_CHAR_NORM, NativeTokens.VF_CHAR2_NORM, NativeTokens.VF_CHAR3_NORM, NativeTokens.VF_CHAR4_NORM)
                : pick(n, NativeTokens.VF_CHAR, NativeTokens.VF_CHAR2, NativeTokens.VF_CHAR3, NativeTokens.VF_CHAR4);
            case GL11.GL_UNSIGNED_SHORT -> norm ? pick(n, NativeTokens.VF_USHORT_NORM, NativeTokens.VF_USHORT2_NORM, NativeTokens.VF_USHORT3_NORM, NativeTokens.VF_USHORT4_NORM)
                : pick(n, NativeTokens.VF_USHORT, NativeTokens.VF_USHORT2, NativeTokens.VF_USHORT3, NativeTokens.VF_USHORT4);
            case GL11.GL_SHORT -> norm ? pick(n, NativeTokens.VF_SHORT_NORM, NativeTokens.VF_SHORT2_NORM, NativeTokens.VF_SHORT3_NORM, NativeTokens.VF_SHORT4_NORM)
                : pick(n, NativeTokens.VF_SHORT, NativeTokens.VF_SHORT2, NativeTokens.VF_SHORT3, NativeTokens.VF_SHORT4);
            case GL11.GL_UNSIGNED_INT -> pick(n, NativeTokens.VF_UINT, NativeTokens.VF_UINT2, NativeTokens.VF_UINT3, NativeTokens.VF_UINT4);
            case GL11.GL_INT -> pick(n, NativeTokens.VF_INT, NativeTokens.VF_INT2, NativeTokens.VF_INT3, NativeTokens.VF_INT4);
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV -> NativeTokens.VF_UINT1010102_NORM;
            case GL33.GL_INT_2_10_10_10_REV -> NativeTokens.VF_INT1010102_NORM;
            case GL30.GL_UNSIGNED_INT_10F_11F_11F_REV -> NativeTokens.VF_FLOAT_RG11B10;
            case GL11.GL_DOUBLE -> NativeTokens.VF_INVALID;
            default -> NativeTokens.VF_FLOAT4;
        };
    }
}
