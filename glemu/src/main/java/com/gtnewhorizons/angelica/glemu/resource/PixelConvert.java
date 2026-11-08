package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.gtnewhorizons.angelica.glemu.resource.PixelFormats.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Converts between GL client pixel layouts, as the unpack and pack state describes them, and the layouts images store. */
public final class PixelConvert {
    private PixelConvert() {}

    public static final class Store {
        public int rowLength, skipPixels, skipRows, alignment = 4, imageHeight, skipImages;
        public int blockWidth, blockHeight, blockDepth, blockSize;
        public boolean swapBytes;

        public void copyFrom(Store o) {
            rowLength = o.rowLength;
            skipPixels = o.skipPixels;
            skipRows = o.skipRows;
            alignment = o.alignment;
            imageHeight = o.imageHeight;
            skipImages = o.skipImages;
            blockWidth = o.blockWidth;
            blockHeight = o.blockHeight;
            blockDepth = o.blockDepth;
            blockSize = o.blockSize;
            swapBytes = o.swapBytes;
        }
    }

    public static int components(int glFormat) {
        return switch (glFormat) {
            case GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA, GL11.GL_LUMINANCE, GL11.GL_INTENSITY, GL11.GL_DEPTH_COMPONENT, GL11.GL_STENCIL_INDEX,
                 GL30.GL_RED_INTEGER, GL30.GL_GREEN_INTEGER, GL30.GL_BLUE_INTEGER, GL30.GL_ALPHA_INTEGER -> 1;
            case GL11.GL_LUMINANCE_ALPHA, GL30.GL_RG, GL30.GL_RG_INTEGER, GL30.GL_DEPTH_STENCIL -> 2;
            case GL11.GL_RGB, GL12.GL_BGR, GL30.GL_RGB_INTEGER, GL30.GL_BGR_INTEGER -> 3;
            default -> 4;
        };
    }

    public static int typeBytes(int glType) {
        return switch (glType) {
            case GL11.GL_UNSIGNED_BYTE, GL11.GL_BYTE, GL12.GL_UNSIGNED_BYTE_3_3_2, GL12.GL_UNSIGNED_BYTE_2_3_3_REV -> 1;
            case GL11.GL_UNSIGNED_SHORT, GL11.GL_SHORT, GL30.GL_HALF_FLOAT, GL12.GL_UNSIGNED_SHORT_5_6_5, GL12.GL_UNSIGNED_SHORT_5_6_5_REV,
                 GL12.GL_UNSIGNED_SHORT_4_4_4_4, GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV, GL12.GL_UNSIGNED_SHORT_5_5_5_1, GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV -> 2;
            case GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV -> 8;
            default -> 4;
        };
    }

    public static boolean isPacked(int glType) {
        return switch (glType) {
            case GL12.GL_UNSIGNED_INT_8_8_8_8, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, GL12.GL_UNSIGNED_INT_10_10_10_2, GL12.GL_UNSIGNED_INT_2_10_10_10_REV,
                 GL30.GL_UNSIGNED_INT_24_8, GL30.GL_UNSIGNED_INT_10F_11F_11F_REV, GL30.GL_UNSIGNED_INT_5_9_9_9_REV, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV,
                 GL12.GL_UNSIGNED_SHORT_5_6_5, GL12.GL_UNSIGNED_SHORT_5_6_5_REV, GL12.GL_UNSIGNED_SHORT_4_4_4_4, GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV,
                 GL12.GL_UNSIGNED_SHORT_5_5_5_1, GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV, GL12.GL_UNSIGNED_BYTE_3_3_2, GL12.GL_UNSIGNED_BYTE_2_3_3_REV -> true;
            default -> false;
        };
    }

    public static int pixelBytes(int glFormat, int glType) {
        return isPacked(glType) ? typeBytes(glType) : components(glFormat) * typeBytes(glType);
    }

    public static long rowStride(int width, int glFormat, int glType, Store s) {
        final int rowPixels = s.rowLength > 0 ? s.rowLength : width;
        final long raw = (long) rowPixels * pixelBytes(glFormat, glType);
        final int align = Math.max(1, s.alignment);
        return typeBytes(glType) >= align ? raw : (raw + align - 1) / align * align;
    }

    public static long clientBytes(int width, int height, int depth, int glFormat, int glType, Store s) {
        final long stride = rowStride(width, glFormat, glType, s);
        final int rows = s.imageHeight > 0 ? s.imageHeight : height;
        final int px = pixelBytes(glFormat, glType);
        final long image = stride * rows;
        return image * (s.skipImages + Math.max(0, depth - 1)) + stride * (s.skipRows + Math.max(0, height - 1)) + (long) (s.skipPixels + width) * px;
    }

    /** Bytes swap within each element, and a packed type's whole pixel is one element, save the two words of depth and stencil. */
    private static int swapUnit(int glType) {
        return Math.min(typeBytes(glType), 4);
    }

    private static void swapRow(long row, long bytes, int swap) {
        if (swap == 2) for (long o = row, end = row + bytes; o < end; o += 2) memPutShort(o, Short.reverseBytes(memGetShort(o)));
        else if (swap == 4) for (long o = row, end = row + bytes; o < end; o += 4) memPutInt(o, Integer.reverseBytes(memGetInt(o)));
    }

    /** Compresses client pixels into a compressed format's blocks, slice by slice. */
    public static boolean compress(Formats.Format f, int glFormat, int glType, long src, int w, int h, int d, Store s, long dst) {
        final Formats.Format texels = BlockCodec.isFloat(f) ? Formats.of(org.lwjgl.opengl.GL30.GL_RGBA32F, 0, 0)
            : BlockCodec.isSigned(f) ? Formats.of(org.lwjgl.opengl.GL31.GL_RGBA8_SNORM, 0, 0) : Formats.RGBA8;
        final int texelBytes = BlockCodec.isFloat(f) ? 16 : 4;
        final long temporary = nmemAlloc((long) w * h * d * texelBytes);
        try {
            if (!unpack(texels, glFormat, glType, src, w, h, d, s, temporary)) return false;
            final long sliceBytes = Formats.imageBytes(f, w, h);
            for (int z = 0; z < d; z++) BlockCodec.encode(f, temporary + (long) z * w * h * texelBytes, w, h, dst + z * sliceBytes);
            return true;
        } finally {
            nmemFree(temporary);
        }
    }

    public static boolean unpack(Formats.Format f, int glFormat, int glType, long src, int w, int h, int d, Store s, long dst) {
        final long stride = rowStride(w, glFormat, glType, s);
        final int rows = s.imageHeight > 0 ? s.imageHeight : h;
        final int px = pixelBytes(glFormat, glType);
        final long base = src + (long) s.skipImages * stride * rows + (long) s.skipRows * stride + (long) s.skipPixels * px;
        final int dstPx = f.texelBytes();
        final long dstRow = (long) w * dstPx;
        final int swap = s.swapBytes ? swapUnit(glType) : 1;
        final long swapped = swap > 1 ? nmemAlloc((long) w * px) : 0L;
        boolean ok = true;
        try {
            for (int z = 0; z < d && ok; z++) {
                for (int y = 0; y < h; y++) {
                    long in = base + (long) z * stride * rows + (long) y * stride;
                    final long out = dst + ((long) z * h + y) * dstRow;
                    if (swap > 1) {
                        memCopy(in, swapped, (long) w * px);
                        swapRow(swapped, (long) w * px, swap);
                        in = swapped;
                    }
                    if (!unpackRow(f, glFormat, glType, in, out, w) && !unpackThroughFloats(f, glFormat, glType, in, out, w)) {
                        ok = false;
                        break;
                    }
                }
            }
        } finally {
            if (swap > 1) nmemFree(swapped);
        }
        if (!ok) memSet(dst, 0, dstRow * h * d);
        return ok;
    }

    /**
     * The layouts with no direct path, by GL's own definition of unpacking: each component becomes a float, and the texel is built from those.
     * An integer format stays refused, since GL never converts between integer and normalised pixels.
     */
    private static boolean unpackThroughFloats(Formats.Format f, int glFormat, int glType, long in, long out, int w) {
        final int components = unpackedComponents(glFormat, glType);
        if (components == 0) return false;
        final int px = pixelBytes(glFormat, glType), texel = f.texelBytes();
        final float[] read = new float[4], rgba = new float[4];
        for (int x = 0; x < w; x++) {
            if (!readComponents(glType, in + (long) x * px, components, read)) return false;
            rgba[0] = rgba[1] = rgba[2] = 0f;
            rgba[3] = 1f;
            for (int i = 0; i < components; i++) rgba[channelFor(glFormat, i)] = read[i];
            if (glFormat == GL11.GL_LUMINANCE || glFormat == GL11.GL_LUMINANCE_ALPHA) rgba[1] = rgba[2] = rgba[0];
            if (glFormat == GL11.GL_INTENSITY) rgba[1] = rgba[2] = rgba[3] = rgba[0];
            if (!writeTexel(f, rgba, out + (long) x * texel)) return false;
        }
        return true;
    }

    /** How many components a client pixel holds, or 0 where the type's layout does not fit the format or the format is not a colour one. */
    private static int unpackedComponents(int glFormat, int glType) {
        final int n = switch (glFormat) {
            case GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA, GL11.GL_LUMINANCE, GL11.GL_INTENSITY -> 1;
            case GL30.GL_RG, GL11.GL_LUMINANCE_ALPHA -> 2;
            case GL11.GL_RGB, GL12.GL_BGR -> 3;
            case GL11.GL_RGBA, GL12.GL_BGRA -> 4;
            default -> 0;
        };
        if (!isPacked(glType)) return n;
        return switch (glType) {
            case GL12.GL_UNSIGNED_BYTE_3_3_2, GL12.GL_UNSIGNED_BYTE_2_3_3_REV, GL12.GL_UNSIGNED_SHORT_5_6_5, GL12.GL_UNSIGNED_SHORT_5_6_5_REV,
                 GL30.GL_UNSIGNED_INT_10F_11F_11F_REV, GL30.GL_UNSIGNED_INT_5_9_9_9_REV -> glFormat == GL11.GL_RGB ? 3 : 0;
            case GL12.GL_UNSIGNED_SHORT_4_4_4_4, GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV, GL12.GL_UNSIGNED_SHORT_5_5_5_1, GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV,
                 GL12.GL_UNSIGNED_INT_8_8_8_8, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, GL12.GL_UNSIGNED_INT_10_10_10_2, GL12.GL_UNSIGNED_INT_2_10_10_10_REV -> n == 4 ? 4 : 0;
            default -> 0;
        };
    }

    private static boolean readComponents(int glType, long p, int n, float[] out) {
        switch (glType) {
            case GL11.GL_BYTE -> { for (int i = 0; i < n; i++) out[i] = Math.max(memGetByte(p + i) / 127f, -1f); }
            case GL11.GL_UNSIGNED_BYTE -> { for (int i = 0; i < n; i++) out[i] = (memGetByte(p + i) & 0xFF) / 255f; }
            case GL11.GL_SHORT -> { for (int i = 0; i < n; i++) out[i] = Math.max(memGetShort(p + i * 2L) / 32767f, -1f); }
            case GL11.GL_UNSIGNED_SHORT -> { for (int i = 0; i < n; i++) out[i] = (memGetShort(p + i * 2L) & 0xFFFF) / 65535f; }
            case GL11.GL_INT -> { for (int i = 0; i < n; i++) out[i] = (float) Math.max(memGetInt(p + i * 4L) / 2147483647.0, -1.0); }
            case GL11.GL_UNSIGNED_INT -> { for (int i = 0; i < n; i++) out[i] = (float) ((memGetInt(p + i * 4L) & 0xFFFFFFFFL) / 4294967295.0); }
            case GL30.GL_HALF_FLOAT -> { for (int i = 0; i < n; i++) out[i] = fromHalf(memGetShort(p + i * 2L)); }
            case GL11.GL_FLOAT -> { for (int i = 0; i < n; i++) out[i] = memGetFloat(p + i * 4L); }
            case GL30.GL_UNSIGNED_INT_10F_11F_11F_REV -> {
                final int v = memGetInt(p);
                out[0] = smallFloat(v & 0x7FF, 6);
                out[1] = smallFloat(v >>> 11 & 0x7FF, 6);
                out[2] = smallFloat(v >>> 22 & 0x3FF, 5);
            }
            case GL30.GL_UNSIGNED_INT_5_9_9_9_REV -> {
                final int v = memGetInt(p);
                final float scale = (float) Math.pow(2, (v >>> 27) - 15 - 9);
                out[0] = (v & 0x1FF) * scale;
                out[1] = (v >>> 9 & 0x1FF) * scale;
                out[2] = (v >>> 18 & 0x1FF) * scale;
            }
            default -> {
                final int[] layout = packedLayout(glType);
                if (layout == null) return false;
                final int pixel = typeBytes(glType) == 1 ? memGetByte(p) & 0xFF : typeBytes(glType) == 2 ? memGetShort(p) & 0xFFFF : memGetInt(p);
                fields(pixel, out, layout);
            }
        }
        return true;
    }

    /** A packed type's fields in component order, as pairs of each one's lowest bit and width, or null if it is not a plain colour layout. */
    private static int[] packedLayout(int glType) {
        return switch (glType) {
            case GL12.GL_UNSIGNED_BYTE_3_3_2 -> new int[] {5, 3, 2, 3, 0, 2};
            case GL12.GL_UNSIGNED_BYTE_2_3_3_REV -> new int[] {0, 3, 3, 3, 6, 2};
            case GL12.GL_UNSIGNED_SHORT_5_6_5 -> new int[] {11, 5, 5, 6, 0, 5};
            case GL12.GL_UNSIGNED_SHORT_5_6_5_REV -> new int[] {0, 5, 5, 6, 11, 5};
            case GL12.GL_UNSIGNED_SHORT_4_4_4_4 -> new int[] {12, 4, 8, 4, 4, 4, 0, 4};
            case GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV -> new int[] {0, 4, 4, 4, 8, 4, 12, 4};
            case GL12.GL_UNSIGNED_SHORT_5_5_5_1 -> new int[] {11, 5, 6, 5, 1, 5, 0, 1};
            case GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV -> new int[] {0, 5, 5, 5, 10, 5, 15, 1};
            case GL12.GL_UNSIGNED_INT_8_8_8_8 -> new int[] {24, 8, 16, 8, 8, 8, 0, 8};
            case GL12.GL_UNSIGNED_INT_8_8_8_8_REV -> new int[] {0, 8, 8, 8, 16, 8, 24, 8};
            case GL12.GL_UNSIGNED_INT_10_10_10_2 -> new int[] {22, 10, 12, 10, 2, 10, 0, 2};
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV -> new int[] {0, 10, 10, 10, 20, 10, 30, 2};
            default -> null;
        };
    }

    /** Reads a packed pixel's fields into its components in order, as pairs of each field's lowest bit and width. */
    private static void fields(int pixel, float[] out, int... shiftAndBits) {
        for (int i = 0; i < shiftAndBits.length / 2; i++) {
            final long mask = (1L << shiftAndBits[i * 2 + 1]) - 1;
            out[i] = (float) (((pixel >>> shiftAndBits[i * 2]) & mask) / (double) mask);
        }
    }

    private static short unorm16(float v) {
        return (short) Math.round(clamp01(v) * 65535f);
    }

    private static byte snorm8(float v) {
        return (byte) Math.round(Math.max(-1f, Math.min(1f, v)) * 127f);
    }

    private static short snorm16(float v) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    private static boolean writeTexel(Formats.Format f, float[] rgba, long q) {
        final float a = f.baseFormat() == GL11.GL_RGB ? 1f : rgba[3];
        switch (f.pf()) {
            case PF_RGBA8_UNORM, PF_RGBA8_SRGB -> {
                for (int i = 0; i < 3; i++) memPutByte(q + i, unorm8(rgba[i]));
                memPutByte(q + 3, unorm8(a));
            }
            case PF_BGRA8_UNORM, PF_BGRA8_SRGB -> {
                for (int i = 0; i < 3; i++) memPutByte(q + i, unorm8(rgba[2 - i]));
                memPutByte(q + 3, unorm8(a));
            }
            case PF_RGBA8_SNORM -> {
                for (int i = 0; i < 3; i++) memPutByte(q + i, snorm8(rgba[i]));
                memPutByte(q + 3, snorm8(a));
            }
            case PF_R8_UNORM -> memPutByte(q, unorm8(rgba[f.baseFormat() == GL11.GL_ALPHA ? 3 : 0]));
            case PF_R8_SNORM -> memPutByte(q, snorm8(rgba[0]));
            case PF_RG8_UNORM -> {
                memPutByte(q, unorm8(rgba[0]));
                memPutByte(q + 1, unorm8(rgba[f.baseFormat() == GL11.GL_LUMINANCE_ALPHA ? 3 : 1]));
            }
            case PF_RG8_SNORM -> { for (int i = 0; i < 2; i++) memPutByte(q + i, snorm8(rgba[i])); }
            case PF_R16_UNORM -> memPutShort(q, unorm16(rgba[0]));
            case PF_RG16_UNORM -> { for (int i = 0; i < 2; i++) memPutShort(q + i * 2L, unorm16(rgba[i])); }
            case PF_RGBA16_UNORM -> {
                for (int i = 0; i < 3; i++) memPutShort(q + i * 2L, unorm16(rgba[i]));
                memPutShort(q + 6, unorm16(a));
            }
            case PF_R16_SNORM -> memPutShort(q, snorm16(rgba[0]));
            case PF_RG16_SNORM -> { for (int i = 0; i < 2; i++) memPutShort(q + i * 2L, snorm16(rgba[i])); }
            case PF_RGBA16_SNORM -> {
                for (int i = 0; i < 3; i++) memPutShort(q + i * 2L, snorm16(rgba[i]));
                memPutShort(q + 6, snorm16(a));
            }
            case PF_R16_FLOAT -> memPutShort(q, toHalf(rgba[0]));
            case PF_RG16_FLOAT -> { for (int i = 0; i < 2; i++) memPutShort(q + i * 2L, toHalf(rgba[i])); }
            case PF_RGBA16_FLOAT -> {
                for (int i = 0; i < 3; i++) memPutShort(q + i * 2L, toHalf(rgba[i]));
                memPutShort(q + 6, toHalf(a));
            }
            case PF_R32_FLOAT -> memPutFloat(q, rgba[0]);
            case PF_RG32_FLOAT -> { for (int i = 0; i < 2; i++) memPutFloat(q + i * 4L, rgba[i]); }
            case PF_RGBA32_FLOAT -> {
                for (int i = 0; i < 3; i++) memPutFloat(q + i * 4L, rgba[i]);
                memPutFloat(q + 12, a);
            }
            case PF_RGB10A2_UNORM -> {
                int v = Math.round(clamp01(a) * 3f) << 30;
                for (int i = 0; i < 3; i++) v |= Math.round(clamp01(rgba[i]) * 1023f) << (i * 10);
                memPutInt(q, v);
            }
            case PF_BGR5A1_UNORM -> memPutShort(q, (short) (Math.round(clamp01(a)) << 15 | Math.round(clamp01(rgba[0]) * 31f) << 10 | Math.round(clamp01(rgba[1]) * 31f) << 5 | Math.round(clamp01(rgba[2]) * 31f)));
            case PF_RG11B10_FLOAT -> memPutInt(q, toRg11b10(rgba));
            case PF_RGB9E5_FLOAT -> memPutInt(q, toRgb9e5(rgba));
            default -> { return false; }
        }
        return true;
    }

    /** A float as an unsigned small float of 5 exponent bits, rounding to nearest and saturating at the largest value. */
    private static int toSmallFloat(float v, int mantissaBits) {
        if (!(v > 0f)) return 0;
        final int full = 1 << mantissaBits;
        final int max = (30 << mantissaBits) | (full - 1);
        if (v == Float.POSITIVE_INFINITY) return max;
        final int e = Math.getExponent(v);
        if (e < -14) return Math.min(full - 1, Math.round(v * (float) Math.pow(2, 14) * full));
        if (e > 15) return max;
        final int bits = ((e + 15) << mantissaBits) + Math.round((v / (float) Math.pow(2, e) - 1f) * full);
        return Math.min(bits, max);
    }

    private static int toRg11b10(float[] rgba) {
        return toSmallFloat(rgba[0], 6) | toSmallFloat(rgba[1], 6) << 11 | toSmallFloat(rgba[2], 5) << 22;
    }

    /** Follows the shared exponent encoding the specification gives for RGB9_E5. */
    private static int toRgb9e5(float[] rgba) {
        final double most = 511.0 / 512.0 * Math.pow(2, 16);
        final double r = Math.max(0.0, Math.min(rgba[0], most)), g = Math.max(0.0, Math.min(rgba[1], most)), b = Math.max(0.0, Math.min(rgba[2], most));
        final double largest = Math.max(r, Math.max(g, b));
        int exp = largest == 0.0 ? -16 : Math.max(-16, Math.getExponent(largest)) + 1;
        double scale = Math.pow(2, exp - 9);
        if (Math.floor(largest / scale + 0.5) == 512.0) {
            exp++;
            scale *= 2;
        }
        return (int) Math.floor(r / scale + 0.5) | (int) Math.floor(g / scale + 0.5) << 9 | (int) Math.floor(b / scale + 0.5) << 18 | exp + 15 << 27;
    }

    private static boolean integerPixels(int glFormat) {
        return switch (glFormat) {
            case GL30.GL_RED_INTEGER, GL30.GL_GREEN_INTEGER, GL30.GL_BLUE_INTEGER, GL30.GL_ALPHA_INTEGER, GL30.GL_RG_INTEGER, GL30.GL_RGB_INTEGER,
                 GL30.GL_BGR_INTEGER, GL30.GL_RGBA_INTEGER, GL30.GL_BGRA_INTEGER -> true;
            default -> false;
        };
    }

    private static boolean readIntegers(int glType, long p, int n, long[] out) {
        switch (glType) {
            case GL11.GL_BYTE -> { for (int i = 0; i < n; i++) out[i] = memGetByte(p + i); }
            case GL11.GL_UNSIGNED_BYTE -> { for (int i = 0; i < n; i++) out[i] = memGetByte(p + i) & 0xFF; }
            case GL11.GL_SHORT -> { for (int i = 0; i < n; i++) out[i] = memGetShort(p + i * 2L); }
            case GL11.GL_UNSIGNED_SHORT -> { for (int i = 0; i < n; i++) out[i] = memGetShort(p + i * 2L) & 0xFFFF; }
            case GL11.GL_INT -> { for (int i = 0; i < n; i++) out[i] = memGetInt(p + i * 4L); }
            case GL11.GL_UNSIGNED_INT -> { for (int i = 0; i < n; i++) out[i] = memGetInt(p + i * 4L) & 0xFFFFFFFFL; }
            default -> {
                final int[] layout = packedLayout(glType);
                if (layout == null) return false;
                final int pixel = typeBytes(glType) == 1 ? memGetByte(p) & 0xFF : typeBytes(glType) == 2 ? memGetShort(p) & 0xFFFF : memGetInt(p);
                for (int i = 0; i < layout.length / 2; i++) out[i] = pixel >>> layout[i * 2] & (1L << layout[i * 2 + 1]) - 1;
            }
        }
        return true;
    }

    /** Integer pixels clamp to the image's range rather than wrap. */
    private static void writeIntegerTexel(Formats.Format f, long[] rgba, long q) {
        if (f.pf() == PF_RGB10A2_UINT) {
            int v = (int) clampBits(rgba[3], 2, false) << 30;
            for (int i = 0; i < 3; i++) v |= (int) clampBits(rgba[i], 10, false) << (i * 10);
            memPutInt(q, v);
            return;
        }
        final int size = f.texelBytes() / f.channels();
        final boolean signed = f.kind() == Formats.K_SINT;
        for (int i = 0; i < f.channels(); i++) {
            final long v = clampBits(i == 3 && f.a() == 0 ? 1 : rgba[i], size * 8, signed);
            final long at = q + (long) i * size;
            switch (size) {
                case 1 -> memPutByte(at, (byte) v);
                case 2 -> memPutShort(at, (short) v);
                default -> memPutInt(at, (int) v);
            }
        }
    }

    private static long clampBits(long v, int bits, boolean signed) {
        return signed ? Math.max(-(1L << bits - 1), Math.min((1L << bits - 1) - 1, v)) : Math.max(0L, Math.min((1L << bits) - 1, v));
    }

    private static boolean unpackIntegerRow(Formats.Format f, int glFormat, int glType, long in, long out, int w) {
        if (!integerPixels(glFormat)) return false;
        final int n = components(glFormat), px = pixelBytes(glFormat, glType), texel = f.texelBytes();
        if (isPacked(glType) && packedLayout(glType) == null) return false;
        final long[] read = new long[4], rgba = new long[4];
        for (int x = 0; x < w; x++) {
            if (!readIntegers(glType, in + (long) x * px, n, read)) return false;
            rgba[0] = rgba[1] = rgba[2] = 0;
            rgba[3] = 1;
            for (int i = 0; i < n; i++) rgba[channelFor(glFormat, i)] = read[i];
            writeIntegerTexel(f, rgba, out + (long) x * texel);
        }
        return true;
    }

    private static float depthOf(int glType, long in, int x, float[] read) {
        return switch (glType) {
            case GL30.GL_UNSIGNED_INT_24_8 -> (memGetInt(in + x * 4L) >>> 8) / 16777215f;
            case GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV -> memGetFloat(in + x * 8L);
            default -> {
                readComponents(glType, in + (long) x * typeBytes(glType), 1, read);
                yield read[0];
            }
        };
    }

    private static boolean unpackDepthRow(Formats.Format f, int glFormat, int glType, long in, long out, int w) {
        if (glFormat != GL11.GL_DEPTH_COMPONENT && glFormat != GL30.GL_DEPTH_STENCIL) return false;
        final int fmt = f.pf();
        final boolean d24 = fmt == PF_X8_DEPTH24_UNORM || fmt == PF_DEPTH24_UNORM_STENCIL8;
        if (d24 && (glType == GL11.GL_UNSIGNED_INT || glType == GL30.GL_UNSIGNED_INT_24_8)) {
            for (int x = 0; x < w; x++) memPutInt(out + x * 4L, memGetInt(in + x * 4L) >>> 8);
            return true;
        }
        final float[] read = new float[4];
        for (int x = 0; x < w; x++) {
            final float depth = depthOf(glType, in, x, read);
            switch (fmt) {
                case PF_DEPTH16_UNORM -> memPutShort(out + x * 2L, unorm16(depth));
                case PF_DEPTH32_FLOAT, PF_DEPTH32_FLOAT_STENCIL8 -> memPutFloat(out + x * 4L, depth);
                default -> {
                    if (!d24) return false;
                    memPutInt(out + x * 4L, Math.round(clamp01(depth) * 16777215f));
                }
            }
        }
        return true;
    }

    /** Stencil indices of any type keep their low bits. */
    private static boolean stencilRow(int glType, long in, long out, int w) {
        for (int x = 0; x < w; x++) {
            final int value;
            switch (glType) {
                case GL11.GL_UNSIGNED_BYTE, GL11.GL_BYTE -> value = memGetByte(in + x);
                case GL11.GL_UNSIGNED_SHORT, GL11.GL_SHORT -> value = memGetShort(in + x * 2L);
                case GL11.GL_UNSIGNED_INT, GL11.GL_INT -> value = memGetInt(in + x * 4L);
                case GL11.GL_FLOAT -> value = (int) memGetFloat(in + x * 4L);
                default -> {
                    return false;
                }
            }
            memPutByte(out + x, (byte) value);
        }
        return true;
    }

    private static boolean unpackRow(Formats.Format f, int glFormat, int glType, long in, long out, int w) {
        if (glFormat == GL11.GL_STENCIL_INDEX && f.texelBytes() == 1) return stencilRow(glType, in, out, w);
        if (f.isInteger()) return unpackIntegerRow(f, glFormat, glType, in, out, w);
        if (f.isDepthOrStencil()) return unpackDepthRow(f, glFormat, glType, in, out, w);
        final int fmt = f.pf();
        switch (fmt) {
            case PF_RGBA8_UNORM, PF_RGBA8_SRGB:
                if (glType != GL11.GL_BYTE && rowToRgba8(glFormat, glType, in, out, w, f.baseFormat() == GL11.GL_RGB)) return true;
                break;
            case PF_R8_UNORM:
                if (glType == GL11.GL_UNSIGNED_BYTE && rowToR8(f, glFormat, in, out, w)) return true;
                break;
            case PF_RG8_UNORM:
                if (glType == GL11.GL_UNSIGNED_BYTE && glFormat == (f.baseFormat() == GL11.GL_LUMINANCE_ALPHA ? GL11.GL_LUMINANCE_ALPHA : GL30.GL_RG)) {
                    memCopy(in, out, (long) w * 2);
                    return true;
                }
                break;
            case PF_RGBA8_SNORM:
                if (glType == GL11.GL_BYTE && glFormat == GL11.GL_RGBA && f.baseFormat() == GL11.GL_RGBA) {
                    memCopy(in, out, (long) w * 4);
                    return true;
                }
                break;
            case PF_R8_SNORM:
                if (glType == GL11.GL_BYTE && glFormat == GL11.GL_RED) {
                    memCopy(in, out, w);
                    return true;
                }
                break;
            case PF_RG8_SNORM:
                if (glType == GL11.GL_BYTE && glFormat == GL30.GL_RG) {
                    memCopy(in, out, (long) w * 2);
                    return true;
                }
                break;
            case PF_RGB10A2_UNORM:
                if (glFormat == GL12.GL_BGRA && glType == GL12.GL_UNSIGNED_INT_2_10_10_10_REV) {
                    for (int x = 0; x < w; x++) {
                        final int v = memGetInt(in + x * 4L);
                        memPutInt(out + x * 4L, v & 0xC00FFC00 | (v & 0x3FF) << 20 | v >>> 20 & 0x3FF);
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        // Only a client layout that matches the texel can be copied; anything else of the same size would be reinterpreted.
        if (isNaturalLayout(f, glFormat, glType) && pixelBytes(glFormat, glType) == f.texelBytes()) {
            memCopy(in, out, (long) w * f.texelBytes());
            return true;
        }
        return false;
    }

    private static boolean isReversedOrder(int glFormat) {
        return glFormat == GL12.GL_BGRA || glFormat == GL12.GL_BGR || glFormat == GL30.GL_BGRA_INTEGER || glFormat == GL30.GL_BGR_INTEGER;
    }

    private static boolean rowToRgba8(int glFormat, int glType, long in, long out, int w, boolean opaque) {
        final boolean bytes = glType == GL11.GL_UNSIGNED_BYTE || glType == GL11.GL_BYTE;
        if (glFormat == GL11.GL_RGBA) {
            if (bytes || glType == GL12.GL_UNSIGNED_INT_8_8_8_8_REV) {
                memCopy(in, out, (long) w * 4);
            } else if (glType == GL12.GL_UNSIGNED_INT_8_8_8_8) {
                for (int x = 0; x < w; x++) memPutInt(out + x * 4L, Integer.reverseBytes(memGetInt(in + x * 4L)));
            } else if (glType == GL11.GL_FLOAT) {
                for (int x = 0; x < w * 4; x++) memPutByte(out + x, unorm8(memGetFloat(in + x * 4L)));
            } else {
                return false;
            }
            if (opaque) forceAlpha(out, w);
            return true;
        }
        if (glFormat == GL12.GL_BGRA) {
            if (bytes || glType == GL12.GL_UNSIGNED_INT_8_8_8_8_REV) {
                for (int x = 0; x < w; x++) {
                    final int v = memGetInt(in + x * 4L);
                    memPutInt(out + x * 4L, (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF));
                }
            } else if (glType == GL12.GL_UNSIGNED_INT_8_8_8_8) {
                for (int x = 0; x < w; x++) {
                    final int v = memGetInt(in + x * 4L);
                    final int r = (v >>> 8) & 0xFF, g = (v >>> 16) & 0xFF, b = v >>> 24, a = v & 0xFF;
                    memPutInt(out + x * 4L, r | g << 8 | b << 16 | a << 24);
                }
            } else {
                return false;
            }
            if (opaque) forceAlpha(out, w);
            return true;
        }
        if ((glFormat == GL11.GL_RGB || glFormat == GL12.GL_BGR) && bytes) {
            final boolean bgr = glFormat == GL12.GL_BGR;
            for (int x = 0; x < w; x++) {
                final long p = in + x * 3L;
                final int c0 = memGetByte(p) & 0xFF, c1 = memGetByte(p + 1) & 0xFF, c2 = memGetByte(p + 2) & 0xFF;
                memPutInt(out + x * 4L, (bgr ? c2 | c0 << 16 : c0 | c2 << 16) | c1 << 8 | 0xFF000000);
            }
            return true;
        }
        if (glFormat == GL11.GL_RGB && glType == GL11.GL_FLOAT) {
            for (int x = 0; x < w; x++) {
                final long p = in + x * 12L;
                memPutInt(out + x * 4L, (unorm8(memGetFloat(p)) & 0xFF) | (unorm8(memGetFloat(p + 4)) & 0xFF) << 8 | (unorm8(memGetFloat(p + 8)) & 0xFF) << 16 | 0xFF000000);
            }
            return true;
        }
        if (bytes && components(glFormat) == 1) {
            for (int x = 0; x < w; x++) {
                final int v = memGetByte(in + x) & 0xFF;
                final int px = switch (glFormat) {
                    case GL11.GL_LUMINANCE -> v | v << 8 | v << 16 | 0xFF000000;
                    case GL11.GL_INTENSITY -> v | v << 8 | v << 16 | v << 24;
                    case GL11.GL_ALPHA -> v << 24;
                    case GL11.GL_GREEN -> v << 8 | 0xFF000000;
                    case GL11.GL_BLUE -> v << 16 | 0xFF000000;
                    default -> v | 0xFF000000;
                };
                memPutInt(out + x * 4L, px);
            }
            return true;
        }
        if (bytes && glFormat == GL11.GL_LUMINANCE_ALPHA) {
            for (int x = 0; x < w; x++) {
                final int l = memGetByte(in + x * 2L) & 0xFF, a = memGetByte(in + x * 2L + 1) & 0xFF;
                memPutInt(out + x * 4L, l | l << 8 | l << 16 | a << 24);
            }
            return true;
        }
        return false;
    }

    private static void forceAlpha(long out, int w) {
        for (int x = 0; x < w; x++) memPutByte(out + x * 4L + 3, (byte) 0xFF);
    }

    /** Unsigned bytes into one channel, where the client has that channel. Anything else goes through the general path. */
    private static boolean rowToR8(Formats.Format f, int glFormat, long in, long out, int w) {
        final boolean alpha = f.baseFormat() == GL11.GL_ALPHA;
        final int n = components(glFormat);
        final int pick = switch (glFormat) {
            case GL11.GL_RED, GL11.GL_LUMINANCE, GL11.GL_INTENSITY, GL30.GL_RG -> alpha ? -1 : 0;
            case GL11.GL_ALPHA -> alpha ? 0 : -1;
            case GL11.GL_LUMINANCE_ALPHA -> alpha ? 1 : 0;
            case GL11.GL_RGB -> alpha ? -1 : 0;
            case GL12.GL_BGR -> alpha ? -1 : 2;
            case GL11.GL_RGBA -> alpha ? 3 : 0;
            case GL12.GL_BGRA -> alpha ? 3 : 2;
            default -> -1;
        };
        if (pick < 0) return false;
        if (n == 1) {
            memCopy(in, out, w);
            return true;
        }
        for (int x = 0; x < w; x++) memPutByte(out + x, memGetByte(in + (long) x * n + pick));
        return true;
    }

    public static boolean pack(Formats.Format f, long src, int w, int h, int glFormat, int glType, Store s, long dst) {
        return pack(f, src, w, h, glFormat, glType, s, dst, 0L);
    }

    public static boolean pack(Formats.Format f, long src, int w, int h, int glFormat, int glType, Store s, long dst, long srcPitch) {
        final long stride = rowStride(w, glFormat, glType, s);
        final int px = pixelBytes(glFormat, glType);
        final long base = dst + (long) s.skipRows * stride + (long) s.skipPixels * px;
        final int srcPx = f.isDepthOrStencil() ? (f.pf() == PF_DEPTH16_UNORM ? 2 : 4) : f.texelBytes();
        final int swap = s.swapBytes ? swapUnit(glType) : 1;
        for (int y = 0; y < h; y++) {
            final long row = base + y * stride;
            if (!packRow(f, src + y * (srcPitch != 0L ? srcPitch : (long) w * srcPx), row, w, glFormat, glType)) return false;
            swapRow(row, (long) w * px, swap);
        }
        return true;
    }

    private static boolean packRow(Formats.Format f, long in, long out, int w, int glFormat, int glType) {
        final int fmt = f.pf();
        final boolean bytes = glType == GL11.GL_UNSIGNED_BYTE;
        if ((fmt == PF_RGBA8_UNORM || fmt == PF_RGBA8_SRGB) && f.baseFormat() == GL11.GL_RGBA && f.swizzle() == null) {
            if ((glFormat == GL11.GL_RGBA && (bytes || glType == GL12.GL_UNSIGNED_INT_8_8_8_8_REV))) {
                memCopy(in, out, (long) w * 4);
                return true;
            }
            if (glFormat == GL12.GL_BGRA && (bytes || glType == GL12.GL_UNSIGNED_INT_8_8_8_8_REV)) {
                for (int x = 0; x < w; x++) {
                    final int v = memGetInt(in + x * 4L);
                    memPutInt(out + x * 4L, (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF));
                }
                return true;
            }
            if (glFormat == GL11.GL_RGBA && glType == GL12.GL_UNSIGNED_INT_8_8_8_8) {
                for (int x = 0; x < w; x++) memPutInt(out + x * 4L, Integer.reverseBytes(memGetInt(in + x * 4L)));
                return true;
            }
            if ((glFormat == GL11.GL_RGB || glFormat == GL12.GL_BGR) && bytes) {
                final boolean bgr = glFormat == GL12.GL_BGR;
                for (int x = 0; x < w; x++) {
                    final long p = in + x * 4L, q = out + x * 3L;
                    memPutByte(q, memGetByte(p + (bgr ? 2 : 0)));
                    memPutByte(q + 1, memGetByte(p + 1));
                    memPutByte(q + 2, memGetByte(p + (bgr ? 0 : 2)));
                }
                return true;
            }
            if (bytes && components(glFormat) == 1 && glFormat != GL11.GL_LUMINANCE) {
                final int c = switch (glFormat) {
                    case GL11.GL_GREEN -> 1;
                    case GL11.GL_BLUE -> 2;
                    case GL11.GL_ALPHA -> 3;
                    default -> 0;
                };
                for (int x = 0; x < w; x++) memPutByte(out + x, memGetByte(in + x * 4L + c));
                return true;
            }
        }
        if (f.isDepth()) {
            if (glFormat != GL11.GL_DEPTH_COMPONENT && glFormat != GL30.GL_DEPTH_STENCIL) return false;
            for (int x = 0; x < w; x++) {
                final float depth = switch (fmt) {
                    case PF_DEPTH32_FLOAT, PF_DEPTH32_FLOAT_STENCIL8 -> memGetFloat(in + x * 4L);
                    case PF_DEPTH16_UNORM -> (memGetShort(in + x * 2L) & 0xFFFF) / 65535f;
                    default -> (memGetInt(in + x * 4L) & 0xFFFFFF) / 16777215f;
                };
                if (glType == GL11.GL_FLOAT) memPutFloat(out + x * 4L, depth);
                else if (glType == GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV) {
                    memPutFloat(out + x * 8L, depth);
                    memPutInt(out + x * 8L + 4, 0);
                } else if (glType == GL11.GL_UNSIGNED_INT) memPutInt(out + x * 4L, (int) Math.round(clamp01(depth) * 4294967295.0));
                else if (glType == GL30.GL_UNSIGNED_INT_24_8) memPutInt(out + x * 4L, Math.round(clamp01(depth) * 16777215f) << 8);
                else if (glType == GL11.GL_UNSIGNED_SHORT) memPutShort(out + x * 2L, unorm16(depth));
                else if (glType == GL11.GL_UNSIGNED_BYTE) memPutByte(out + x, unorm8(depth));
                else if (glType == GL11.GL_SHORT) memPutShort(out + x * 2L, snorm16(depth));
                else if (glType == GL11.GL_BYTE) memPutByte(out + x, snorm8(depth));
                else if (glType == GL11.GL_INT) memPutInt(out + x * 4L, (int) Math.round(Math.max(-1f, Math.min(1f, depth)) * 2147483647.0));
                else if (glType == GL30.GL_HALF_FLOAT) memPutShort(out + x * 2L, toHalf(depth));
                else return false;
            }
            return true;
        }
        if (isNaturalLayout(f, glFormat, glType)) {
            memCopy(in, out, (long) w * f.texelBytes());
            return true;
        }
        if (f.isInteger()) return packIntegerRow(f, in, out, w, glFormat, glType);
        if (f.isCompressed()) return false;
        final float[] rgba = new float[4];
        final int n = components(glFormat);
        final int px = pixelBytes(glFormat, glType);
        for (int x = 0; x < w; x++) {
            if (!decode(f, in + (long) x * f.texelBytes(), rgba)) return false;
            // GL reads an image of one channel with only red set, so a luminance read does not count it three times.
            if (f.swizzle() != null && f.channels() <= 2) rgba[1] = rgba[2] = 0f;
            if (!encode(rgba, out + (long) x * px, glFormat, glType, n)) return false;
        }
        return true;
    }

    private static boolean isNaturalLayout(Formats.Format f, int glFormat, int glType) {
        if (f.swizzle() != null) return false;
        return switch (f.pf()) {
            case PF_R8_UNORM -> glFormat == GL11.GL_RED && glType == GL11.GL_UNSIGNED_BYTE;
            case PF_RG8_UNORM -> glFormat == GL30.GL_RG && glType == GL11.GL_UNSIGNED_BYTE;
            case PF_R16_UNORM -> glFormat == GL11.GL_RED && glType == GL11.GL_UNSIGNED_SHORT;
            case PF_RG16_UNORM -> glFormat == GL30.GL_RG && glType == GL11.GL_UNSIGNED_SHORT;
            case PF_RGBA16_UNORM -> f.baseFormat() == GL11.GL_RGBA && glFormat == GL11.GL_RGBA && glType == GL11.GL_UNSIGNED_SHORT;
            case PF_R16_FLOAT -> glFormat == GL11.GL_RED && glType == GL30.GL_HALF_FLOAT;
            case PF_RG16_FLOAT -> glFormat == GL30.GL_RG && glType == GL30.GL_HALF_FLOAT;
            case PF_RGBA16_FLOAT -> f.baseFormat() == GL11.GL_RGBA && glFormat == GL11.GL_RGBA && glType == GL30.GL_HALF_FLOAT;
            case PF_R32_FLOAT -> glFormat == GL11.GL_RED && glType == GL11.GL_FLOAT;
            case PF_RG32_FLOAT -> glFormat == GL30.GL_RG && glType == GL11.GL_FLOAT;
            case PF_RGBA32_FLOAT -> f.baseFormat() == GL11.GL_RGBA && glFormat == GL11.GL_RGBA && glType == GL11.GL_FLOAT;
            case PF_RGB10A2_UNORM -> glFormat == GL11.GL_RGBA && glType == GL12.GL_UNSIGNED_INT_2_10_10_10_REV;
            case PF_BGR5A1_UNORM -> glFormat == GL12.GL_BGRA && glType == GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV;
            case PF_RGB10A2_UINT -> glFormat == GL30.GL_RGBA_INTEGER && glType == GL12.GL_UNSIGNED_INT_2_10_10_10_REV;
            case PF_RG11B10_FLOAT -> glFormat == GL11.GL_RGB && glType == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV;
            case PF_RGB9E5_FLOAT -> glFormat == GL11.GL_RGB && glType == GL30.GL_UNSIGNED_INT_5_9_9_9_REV;
            case PF_R16_SNORM -> glFormat == GL11.GL_RED && glType == GL11.GL_SHORT;
            case PF_RG16_SNORM -> glFormat == GL30.GL_RG && glType == GL11.GL_SHORT;
            case PF_RGBA16_SNORM -> f.baseFormat() == GL11.GL_RGBA && glFormat == GL11.GL_RGBA && glType == GL11.GL_SHORT;
            default -> f.isInteger() && !isReversedOrder(glFormat) && components(glFormat) == f.channels() && typeBytes(glType) * f.channels() == f.texelBytes() && !isPacked(glType)
                && (f.kind() == Formats.K_SINT) == (glType == GL11.GL_BYTE || glType == GL11.GL_SHORT || glType == GL11.GL_INT);
        };
    }

    private static boolean decode(Formats.Format f, long p, float[] out) {
        out[0] = out[1] = out[2] = 0f;
        out[3] = 1f;
        final int fmt = f.pf();
        switch (fmt) {
            case PF_RGBA8_UNORM, PF_RGBA8_SRGB -> { for (int c = 0; c < 4; c++) out[c] = (memGetByte(p + c) & 0xFF) / 255f; }
            case PF_BGRA8_UNORM, PF_BGRA8_SRGB -> {
                out[0] = (memGetByte(p + 2) & 0xFF) / 255f;
                out[1] = (memGetByte(p + 1) & 0xFF) / 255f;
                out[2] = (memGetByte(p) & 0xFF) / 255f;
                out[3] = (memGetByte(p + 3) & 0xFF) / 255f;
            }
            case PF_R8_UNORM, PF_RG8_UNORM -> { for (int c = 0; c < f.texelBytes(); c++) out[c] = (memGetByte(p + c) & 0xFF) / 255f; }
            case PF_R8_SNORM, PF_RG8_SNORM, PF_RGBA8_SNORM -> { for (int c = 0; c < f.texelBytes(); c++) out[c] = Math.max(memGetByte(p + c) / 127f, -1f); }
            case PF_R16_UNORM, PF_RG16_UNORM, PF_RGBA16_UNORM -> { for (int c = 0; c < f.texelBytes() / 2; c++) out[c] = (memGetShort(p + c * 2L) & 0xFFFF) / 65535f; }
            case PF_R16_SNORM, PF_RG16_SNORM, PF_RGBA16_SNORM -> { for (int c = 0; c < f.texelBytes() / 2; c++) out[c] = Math.max(memGetShort(p + c * 2L) / 32767f, -1f); }
            case PF_R16_FLOAT, PF_RG16_FLOAT, PF_RGBA16_FLOAT -> { for (int c = 0; c < f.texelBytes() / 2; c++) out[c] = fromHalf(memGetShort(p + c * 2L)); }
            case PF_R32_FLOAT, PF_RG32_FLOAT, PF_RGBA32_FLOAT -> { for (int c = 0; c < f.texelBytes() / 4; c++) out[c] = memGetFloat(p + c * 4L); }
            case PF_RGB10A2_UNORM -> {
                final int v = memGetInt(p);
                out[0] = (v & 0x3FF) / 1023f;
                out[1] = ((v >>> 10) & 0x3FF) / 1023f;
                out[2] = ((v >>> 20) & 0x3FF) / 1023f;
                out[3] = (v >>> 30) / 3f;
            }
            case PF_BGR5A1_UNORM -> {
                final int v = memGetShort(p) & 0xFFFF;
                out[0] = ((v >>> 10) & 0x1F) / 31f;
                out[1] = ((v >>> 5) & 0x1F) / 31f;
                out[2] = (v & 0x1F) / 31f;
                out[3] = v >>> 15;
            }
            case PF_RG11B10_FLOAT -> {
                final int v = memGetInt(p);
                out[0] = smallFloat(v & 0x7FF, 6);
                out[1] = smallFloat((v >>> 11) & 0x7FF, 6);
                out[2] = smallFloat((v >>> 22) & 0x3FF, 5);
            }
            case PF_RGB9E5_FLOAT -> {
                final int v = memGetInt(p);
                final float scale = (float) Math.pow(2, (v >>> 27) - 15 - 9);
                out[0] = (v & 0x1FF) * scale;
                out[1] = ((v >>> 9) & 0x1FF) * scale;
                out[2] = ((v >>> 18) & 0x1FF) * scale;
            }
            default -> { return false; }
        }
        final int[] sw = f.swizzle();
        if (sw != null) {
            final float r = out[0], g = out[1], b = out[2], a = out[3];
            for (int c = 0; c < 4; c++) {
                out[c] = switch (sw[c]) {
                    case SWIZZLE_RED -> r;
                    case SWIZZLE_GREEN -> g;
                    case SWIZZLE_BLUE -> b;
                    case SWIZZLE_ALPHA -> a;
                    case SWIZZLE_ONE -> 1f;
                    default -> 0f;
                };
            }
            return true;
        }
        switch (f.baseFormat()) {
            case GL11.GL_RGB -> out[3] = 1f;
            case GL30.GL_RG -> { out[2] = 0f; out[3] = 1f; }
            case GL30.GL_RED -> { out[1] = 0f; out[2] = 0f; out[3] = 1f; }
            default -> { }
        }
        return true;
    }

    private static float smallFloat(int bits, int mantissaBits) {
        final int e = bits >>> mantissaBits;
        final int m = bits & ((1 << mantissaBits) - 1);
        if (e == 0) return m / (float) (1 << mantissaBits) * (float) Math.pow(2, -14);
        if (e == 31) return m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        return (1f + m / (float) (1 << mantissaBits)) * (float) Math.pow(2, e - 15);
    }

    private static int channelFor(int glFormat, int index) {
        return switch (glFormat) {
            case GL11.GL_RED, GL11.GL_LUMINANCE -> 0;
            case GL11.GL_GREEN -> 1;
            case GL11.GL_BLUE -> 2;
            case GL11.GL_ALPHA -> 3;
            case GL11.GL_LUMINANCE_ALPHA -> index == 0 ? 0 : 3;
            case GL12.GL_BGR, GL12.GL_BGRA, GL30.GL_BGR_INTEGER, GL30.GL_BGRA_INTEGER -> index < 3 ? 2 - index : 3;
            default -> index;
        };
    }

    /** Packs components in the format's order into fields given as pairs of each one's lowest bit and width. */
    private static int packedFields(float[] rgba, int glFormat, int... shiftAndBits) {
        int out = 0;
        for (int i = 0; i < shiftAndBits.length / 2; i++) {
            final int max = (1 << shiftAndBits[i * 2 + 1]) - 1;
            out |= Math.round(clamp01(rgba[channelFor(glFormat, i)]) * max) << shiftAndBits[i * 2];
        }
        return out;
    }

    private static boolean encode(float[] rgba, long q, int glFormat, int glType, int n) {
        switch (glType) {
            case GL12.GL_UNSIGNED_INT_8_8_8_8, GL12.GL_UNSIGNED_INT_8_8_8_8_REV -> {
                int v = 0;
                for (int i = 0; i < 4; i++) v |= (unorm8(rgba[channelFor(glFormat, i)]) & 0xFF) << (glType == GL12.GL_UNSIGNED_INT_8_8_8_8_REV ? i * 8 : 24 - i * 8);
                memPutInt(q, v);
                return true;
            }
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV -> {
                int v = 0;
                for (int i = 0; i < 3; i++) v |= Math.round(clamp01(rgba[channelFor(glFormat, i)]) * 1023f) << (i * 10);
                memPutInt(q, v | Math.round(clamp01(rgba[3]) * 3f) << 30);
                return true;
            }
            case GL30.GL_UNSIGNED_INT_10F_11F_11F_REV -> { memPutInt(q, toRg11b10(rgba)); return true; }
            case GL30.GL_UNSIGNED_INT_5_9_9_9_REV -> { memPutInt(q, toRgb9e5(rgba)); return true; }
            case GL12.GL_UNSIGNED_BYTE_3_3_2 -> { memPutByte(q, (byte) packedFields(rgba, glFormat, 5, 3, 2, 3, 0, 2)); return true; }
            case GL12.GL_UNSIGNED_BYTE_2_3_3_REV -> { memPutByte(q, (byte) packedFields(rgba, glFormat, 0, 3, 3, 3, 6, 2)); return true; }
            case GL12.GL_UNSIGNED_SHORT_5_6_5_REV -> { memPutShort(q, (short) packedFields(rgba, glFormat, 0, 5, 5, 6, 11, 5)); return true; }
            case GL12.GL_UNSIGNED_SHORT_4_4_4_4 -> { memPutShort(q, (short) packedFields(rgba, glFormat, 12, 4, 8, 4, 4, 4, 0, 4)); return true; }
            case GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV -> { memPutShort(q, (short) packedFields(rgba, glFormat, 0, 4, 4, 4, 8, 4, 12, 4)); return true; }
            case GL12.GL_UNSIGNED_SHORT_5_5_5_1 -> { memPutShort(q, (short) packedFields(rgba, glFormat, 11, 5, 6, 5, 1, 5, 0, 1)); return true; }
            case GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV -> { memPutShort(q, (short) packedFields(rgba, glFormat, 0, 5, 5, 5, 10, 5, 15, 1)); return true; }
            case GL12.GL_UNSIGNED_INT_10_10_10_2 -> { memPutInt(q, packedFields(rgba, glFormat, 22, 10, 12, 10, 2, 10, 0, 2)); return true; }
            case GL12.GL_UNSIGNED_SHORT_5_6_5 -> {
                memPutShort(q, (short) (Math.round(clamp01(rgba[0]) * 31f) << 11 | Math.round(clamp01(rgba[1]) * 63f) << 5 | Math.round(clamp01(rgba[2]) * 31f)));
                return true;
            }
            default -> { }
        }
        if (isPacked(glType)) return false;
        for (int i = 0; i < n; i++) {
            final float v = glFormat == GL11.GL_LUMINANCE || glFormat == GL11.GL_LUMINANCE_ALPHA && i == 0 ? rgba[0] + rgba[1] + rgba[2] : rgba[channelFor(glFormat, i)];
            switch (glType) {
                case GL11.GL_UNSIGNED_BYTE -> memPutByte(q + i, unorm8(v));
                case GL11.GL_BYTE -> memPutByte(q + i, (byte) Math.round(Math.max(-1f, Math.min(1f, v)) * 127f));
                case GL11.GL_UNSIGNED_SHORT -> memPutShort(q + i * 2L, (short) Math.round(clamp01(v) * 65535f));
                case GL11.GL_SHORT -> memPutShort(q + i * 2L, (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f));
                case GL11.GL_UNSIGNED_INT -> memPutInt(q + i * 4L, (int) Math.round(clamp01(v) * 4294967295.0));
                case GL11.GL_INT -> memPutInt(q + i * 4L, (int) Math.round(Math.max(-1f, Math.min(1f, v)) * 2147483647.0));
                case GL11.GL_FLOAT -> memPutFloat(q + i * 4L, v);
                case GL30.GL_HALF_FLOAT -> memPutShort(q + i * 2L, toHalf(v));
                default -> { return false; }
            }
        }
        return true;
    }

    /** Integers go out clamped to what the client's type can hold, in the fields a packed type gives them. */
    private static boolean packIntegerRow(Formats.Format f, long in, long out, int w, int glFormat, int glType) {
        if (!integerPixels(glFormat)) return false;
        final int srcBytes = f.texelBytes() / Math.max(1, f.channels());
        final boolean signed = f.kind() == Formats.K_SINT;
        final int n = components(glFormat);
        final int[] layout = isPacked(glType) ? packedLayout(glType) : null;
        if (isPacked(glType) && layout == null) return false;
        final int px = pixelBytes(glFormat, glType), element = typeBytes(glType);
        final boolean signedOut = glType == GL11.GL_BYTE || glType == GL11.GL_SHORT || glType == GL11.GL_INT;
        for (int x = 0; x < w; x++) {
            int packed = 0;
            for (int i = 0; i < n; i++) {
                final int c = channelFor(glFormat, i);
                long v = c == 3 ? 1 : 0;
                if (f.pf() == PF_RGB10A2_UINT) {
                    final int texel = memGetInt(in + x * 4L);
                    v = c == 3 ? texel >>> 30 : texel >>> (c * 10) & 0x3FF;
                } else if (c < f.channels()) {
                    final long p = in + (long) x * f.texelBytes() + (long) c * srcBytes;
                    v = switch (srcBytes) {
                        case 1 -> signed ? memGetByte(p) : memGetByte(p) & 0xFF;
                        case 2 -> signed ? memGetShort(p) : memGetShort(p) & 0xFFFF;
                        default -> signed ? memGetInt(p) : memGetInt(p) & 0xFFFFFFFFL;
                    };
                }
                if (layout != null) {
                    packed |= (int) clampBits(v, layout[i * 2 + 1], false) << layout[i * 2];
                    continue;
                }
                v = clampBits(v, element * 8, signedOut);
                final long q = out + (long) x * px + (long) i * element;
                switch (element) {
                    case 1 -> memPutByte(q, (byte) v);
                    case 2 -> memPutShort(q, (short) v);
                    default -> memPutInt(q, (int) v);
                }
            }
            if (layout != null) {
                final long q = out + (long) x * px;
                switch (element) {
                    case 1 -> memPutByte(q, (byte) packed);
                    case 2 -> memPutShort(q, (short) packed);
                    default -> memPutInt(q, packed);
                }
            }
        }
        return true;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static byte unorm8(float v) {
        return (byte) Math.round(clamp01(v) * 255f);
    }

    public static short toHalf(float f) {
        return (short) Float.floatToFloat16(f);
    }

    public static float fromHalf(short h) {
        return Float.float16ToFloat(h);
    }
}
