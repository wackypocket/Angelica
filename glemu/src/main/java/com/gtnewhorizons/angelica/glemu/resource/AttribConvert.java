package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryUtil;

/** Reads vertex attributes the way section 10.2 of the OpenGL specification converts them, for those a device's vertex fetch has no format for. */
public final class AttribConvert {
    private AttribConvert() {}

    public static int components(int size, int type) {
        if (size == GL12.GL_BGRA) return 4;
        return type == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV ? 3 : size;
    }

    public static boolean unsigned(int type) {
        return type == GL11.GL_UNSIGNED_BYTE || type == GL11.GL_UNSIGNED_SHORT || type == GL11.GL_UNSIGNED_INT;
    }

    /** Writes each element's components tightly packed: as floats, or as 32-bit integers for an integer attribute. */
    public static void convert(long src, long srcStride, int count, int size, int type, boolean normalized, boolean integer, long dst) {
        final int components = components(size, type);
        final float[] values = new float[4];
        for (int e = 0; e < count; e++) {
            final long at = src + e * srcStride;
            if (integer) {
                for (int c = 0; c < components; c++, dst += Integer.BYTES) MemoryUtil.memPutInt(dst, integerAt(at, c, type));
                continue;
            }
            read(at, size, type, normalized, values);
            for (int c = 0; c < components; c++, dst += Float.BYTES) MemoryUtil.memPutFloat(dst, values[c]);
        }
    }

    private static int integerAt(long at, int c, int type) {
        return switch (type) {
            case GL11.GL_BYTE -> MemoryUtil.memGetByte(at + c);
            case GL11.GL_UNSIGNED_BYTE -> MemoryUtil.memGetByte(at + c) & 0xFF;
            case GL11.GL_SHORT -> MemoryUtil.memGetShort(at + c * 2L);
            case GL11.GL_UNSIGNED_SHORT -> MemoryUtil.memGetShort(at + c * 2L) & 0xFFFF;
            default -> MemoryUtil.memGetInt(at + c * 4L);
        };
    }

    private static void read(long at, int size, int type, boolean normalized, float[] out) {
        if (type == GL12.GL_UNSIGNED_INT_2_10_10_10_REV || type == GL33.GL_INT_2_10_10_10_REV) {
            packed(MemoryUtil.memGetInt(at), type == GL33.GL_INT_2_10_10_10_REV, size == GL12.GL_BGRA, normalized, out);
            return;
        }
        if (type == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV) {
            final int v = MemoryUtil.memGetInt(at);
            out[0] = ufloat(v & 0x7FF, 6);
            out[1] = ufloat(v >>> 11 & 0x7FF, 6);
            out[2] = ufloat(v >>> 22 & 0x3FF, 5);
            return;
        }
        final int components = components(size, type);
        for (int c = 0; c < components; c++) out[c] = component(at, c, type, normalized);
        // GL_BGRA swaps the first and third components.
        if (size == GL12.GL_BGRA) {
            final float first = out[0];
            out[0] = out[2];
            out[2] = first;
        }
    }

    private static float component(long at, int c, int type, boolean normalized) {
        return switch (type) {
            case GL11.GL_FLOAT -> MemoryUtil.memGetFloat(at + c * 4L);
            case GL11.GL_DOUBLE -> (float) MemoryUtil.memGetDouble(at + c * 8L);
            case GL30.GL_HALF_FLOAT -> half(MemoryUtil.memGetShort(at + c * 2L) & 0xFFFF);
            case GL41.GL_FIXED -> MemoryUtil.memGetInt(at + c * 4L) / 65536.0f;
            case GL11.GL_BYTE -> signed(MemoryUtil.memGetByte(at + c), 0x7FL, normalized);
            case GL11.GL_UNSIGNED_BYTE -> unsigned(MemoryUtil.memGetByte(at + c) & 0xFFL, 0xFFL, normalized);
            case GL11.GL_SHORT -> signed(MemoryUtil.memGetShort(at + c * 2L), 0x7FFFL, normalized);
            case GL11.GL_UNSIGNED_SHORT -> unsigned(MemoryUtil.memGetShort(at + c * 2L) & 0xFFFFL, 0xFFFFL, normalized);
            case GL11.GL_INT -> signed(MemoryUtil.memGetInt(at + c * 4L), 0x7FFFFFFFL, normalized);
            case GL11.GL_UNSIGNED_INT -> unsigned(MemoryUtil.memGetInt(at + c * 4L) & 0xFFFFFFFFL, 0xFFFFFFFFL, normalized);
            default -> 0.0f;
        };
    }

    private static float signed(long value, long max, boolean normalized) {
        return normalized ? (float) Math.max((double) value / max, -1.0) : value;
    }

    private static float unsigned(long value, long max, boolean normalized) {
        return normalized ? (float) ((double) value / max) : value;
    }

    private static void packed(int v, boolean signed, boolean bgra, boolean normalized, float[] out) {
        final int first = field(v, 0, 10, signed), second = field(v, 10, 10, signed), third = field(v, 20, 10, signed), alpha = field(v, 30, 2, signed);
        out[bgra ? 2 : 0] = packedValue(first, 10, signed, normalized);
        out[1] = packedValue(second, 10, signed, normalized);
        out[bgra ? 0 : 2] = packedValue(third, 10, signed, normalized);
        out[3] = packedValue(alpha, 2, signed, normalized);
    }

    private static int field(int v, int shift, int bits, boolean signed) {
        final int up = v << (32 - shift - bits);
        return signed ? up >> (32 - bits) : up >>> (32 - bits);
    }

    private static float packedValue(int value, int bits, boolean signed, boolean normalized) {
        if (!normalized) return value;
        return signed ? Math.max((float) value / ((1 << (bits - 1)) - 1), -1.0f) : (float) value / ((1 << bits) - 1);
    }

    private static float half(int bits) {
        final int exponent = bits >>> 10 & 0x1F, mantissa = bits & 0x3FF;
        final float sign = (bits & 0x8000) != 0 ? -1.0f : 1.0f;
        if (exponent == 0) return sign * mantissa / (1 << 24);
        if (exponent == 31) return mantissa == 0 ? sign * Float.POSITIVE_INFINITY : Float.NaN;
        return sign * (float) Math.scalb(1.0 + mantissa / 1024.0, exponent - 15);
    }

    private static float ufloat(int bits, int mantissaBits) {
        final int exponent = bits >>> mantissaBits, mantissa = bits & (1 << mantissaBits) - 1;
        if (exponent == 0) return (float) Math.scalb((double) mantissa, -14 - mantissaBits);
        if (exponent == 31) return mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        return (float) Math.scalb(1.0 + (double) mantissa / (1 << mantissaBits), exponent - 15);
    }
}
