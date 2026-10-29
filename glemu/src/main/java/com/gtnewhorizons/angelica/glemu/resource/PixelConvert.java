package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.system.MemoryUtil.*;
import static com.gtnewhorizons.angelica.glemu.resource.PixelFormats.*;

public final class PixelConvert {
    private PixelConvert() {}

    public static final class Store {
        public int rowLength, skipPixels, skipRows, alignment = 4, imageHeight, skipImages;
        public boolean swapBytes, lsbFirst;

        public void copyFrom(Store o) {
            rowLength = o.rowLength;
            skipPixels = o.skipPixels;
            skipRows = o.skipRows;
            alignment = o.alignment;
            imageHeight = o.imageHeight;
            skipImages = o.skipImages;
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

    public static boolean unpack(Formats.Format f, int glFormat, int glType, long src, int w, int h, int d, Store s, long dst) {
        final long stride = rowStride(w, glFormat, glType, s);
        final int rows = s.imageHeight > 0 ? s.imageHeight : h;
        final int px = pixelBytes(glFormat, glType);
        final long base = src + (long) s.skipImages * stride * rows + (long) s.skipRows * stride + (long) s.skipPixels * px;
        final int dstPx = f.texelBytes();
        final long dstRow = (long) w * dstPx;
        boolean ok = true;
        for (int z = 0; z < d && ok; z++) {
            for (int y = 0; y < h; y++) {
                final long in = base + (long) z * stride * rows + (long) y * stride;
                final long out = dst + ((long) z * h + y) * dstRow;
                if (!unpackRow(f, glFormat, glType, in, out, w)) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) memSet(dst, 0, dstRow * h * d);
        return ok;
    }

    private static boolean unpackRow(Formats.Format f, int glFormat, int glType, long in, long out, int w) {
        final int fmt = f.pf();
        switch (fmt) {
            case PF_RGBA8_UNORM, PF_RGBA8_SRGB, PF_RGBA8_SNORM, PF_RGBA8_UINT, PF_RGBA8_SINT:
                return rowToRgba8(glFormat, glType, in, out, w, f.baseFormat() == GL11.GL_RGB);
            case PF_R8_UNORM, PF_R8_SNORM, PF_R8_UINT, PF_R8_SINT:
                return rowToR8(f, glFormat, glType, in, out, w);
            case PF_RG8_UNORM, PF_RG8_SNORM, PF_RG8_UINT:
                if ((glFormat == GL11.GL_LUMINANCE_ALPHA || glFormat == GL30.GL_RG || glFormat == GL30.GL_RG_INTEGER) && (glType == GL11.GL_UNSIGNED_BYTE || glType == GL11.GL_BYTE)) {
                    memCopy(in, out, (long) w * 2);
                    return true;
                }
                if (glFormat == GL11.GL_RGBA && glType == GL11.GL_UNSIGNED_BYTE) {
                    for (int x = 0; x < w; x++) {
                        memPutByte(out + x * 2L, memGetByte(in + x * 4L));
                        memPutByte(out + x * 2L + 1, memGetByte(in + x * 4L + (f.baseFormat() == GL11.GL_LUMINANCE_ALPHA ? 3 : 1)));
                    }
                    return true;
                }
                return false;
            case PF_RGBA32_FLOAT, PF_RGBA32_UINT, PF_RGBA32_SINT:
                return rowToRgba32(glFormat, glType, in, out, w);
            case PF_RGBA16_FLOAT:
                return rowToRgba16f(glFormat, glType, in, out, w);
            case PF_R32_FLOAT, PF_R32_UINT, PF_R32_SINT, PF_DEPTH32_FLOAT:
                if (components(glFormat) == 1 && typeBytes(glType) == 4) {
                    memCopy(in, out, (long) w * 4);
                    return true;
                }
                if (fmt == PF_DEPTH32_FLOAT && glType == GL11.GL_UNSIGNED_INT) {
                    for (int x = 0; x < w; x++) memPutFloat(out + x * 4L, (float) ((memGetInt(in + x * 4L) & 0xFFFFFFFFL) / 4294967295.0));
                    return true;
                }
                if (glType == GL11.GL_FLOAT && components(glFormat) > 1) {
                    final int n = components(glFormat);
                    for (int x = 0; x < w; x++) memPutInt(out + x * 4L, memGetInt(in + (long) x * n * 4));
                    return true;
                }
                return false;
            case PF_R16_FLOAT:
                if (glType == GL30.GL_HALF_FLOAT && components(glFormat) == 1) {
                    memCopy(in, out, (long) w * 2);
                    return true;
                }
                if (glType == GL11.GL_FLOAT && components(glFormat) == 1) {
                    for (int x = 0; x < w; x++) memPutShort(out + x * 2L, toHalf(memGetFloat(in + x * 4L)));
                    return true;
                }
                return false;
            case PF_RG16_FLOAT:
                if (glType == GL11.GL_FLOAT && components(glFormat) == 2) {
                    for (int x = 0; x < w * 2; x++) memPutShort(out + x * 2L, toHalf(memGetFloat(in + x * 4L)));
                    return true;
                }
                break;
            case PF_X8_DEPTH24_UNORM, PF_DEPTH24_UNORM_STENCIL8:
                if (glType == GL11.GL_FLOAT) {
                    for (int x = 0; x < w; x++) memPutInt(out + x * 4L, (int) Math.max(0, Math.min(0xFFFFFF, Math.round(memGetFloat(in + x * 4L) * 16777215.0))));
                    return true;
                }
                if (glType == GL11.GL_UNSIGNED_INT || glType == GL30.GL_UNSIGNED_INT_24_8) {
                    for (int x = 0; x < w; x++) memPutInt(out + x * 4L, memGetInt(in + x * 4L) >>> 8);
                    return true;
                }
                return false;
            case PF_DEPTH32_FLOAT_STENCIL8:
                if (glType == GL11.GL_FLOAT) {
                    memCopy(in, out, (long) w * 4);
                    return true;
                }
                if (glType == GL30.GL_UNSIGNED_INT_24_8) {
                    for (int x = 0; x < w; x++) memPutFloat(out + x * 4L, (memGetInt(in + x * 4L) >>> 8) / 16777215f);
                    return true;
                }
                if (glType == GL11.GL_UNSIGNED_INT) {
                    for (int x = 0; x < w; x++) memPutFloat(out + x * 4L, (float) ((memGetInt(in + x * 4L) & 0xFFFFFFFFL) / 4294967295.0));
                    return true;
                }
                if (glType == GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV) {
                    for (int x = 0; x < w; x++) memPutFloat(out + x * 4L, memGetFloat(in + x * 8L));
                    return true;
                }
                return false;
            default:
                break;
        }
        if (pixelBytes(glFormat, glType) == f.texelBytes()) {
            memCopy(in, out, (long) w * f.texelBytes());
            return true;
        }
        return false;
    }

    private static boolean rowToRgba8(int glFormat, int glType, long in, long out, int w, boolean opaque) {
        final boolean bytes = glType == GL11.GL_UNSIGNED_BYTE || glType == GL11.GL_BYTE;
        if (glFormat == GL11.GL_RGBA || glFormat == GL30.GL_RGBA_INTEGER) {
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

    private static boolean rowToR8(Formats.Format f, int glFormat, int glType, long in, long out, int w) {
        final boolean bytes = glType == GL11.GL_UNSIGNED_BYTE || glType == GL11.GL_BYTE;
        if (!bytes) {
            if (glType == GL11.GL_FLOAT && components(glFormat) == 1) {
                for (int x = 0; x < w; x++) memPutByte(out + x, unorm8(memGetFloat(in + x * 4L)));
                return true;
            }
            return false;
        }
        final int n = components(glFormat);
        if (n == 1) {
            memCopy(in, out, w);
            return true;
        }
        final int pick = f.baseFormat() == GL11.GL_ALPHA ? (glFormat == GL11.GL_LUMINANCE_ALPHA ? 1 : n - 1) : (glFormat == GL12.GL_BGRA || glFormat == GL12.GL_BGR ? 2 : 0);
        for (int x = 0; x < w; x++) memPutByte(out + x, memGetByte(in + (long) x * n + pick));
        return true;
    }

    private static boolean rowToRgba32(int glFormat, int glType, long in, long out, int w) {
        if (typeBytes(glType) != 4 || isPacked(glType)) return false;
        final int n = components(glFormat);
        final boolean bgr = glFormat == GL12.GL_BGRA || glFormat == GL12.GL_BGR;
        final int one = glType == GL11.GL_FLOAT ? Float.floatToRawIntBits(1f) : 1;
        for (int x = 0; x < w; x++) {
            final long p = in + (long) x * n * 4;
            final long q = out + x * 16L;
            for (int c = 0; c < 4; c++) {
                final int srcC = bgr && c < 3 ? 2 - c : c;
                memPutInt(q + c * 4L, srcC < n ? memGetInt(p + srcC * 4L) : (c == 3 ? one : 0));
            }
        }
        return true;
    }

    private static boolean rowToRgba16f(int glFormat, int glType, long in, long out, int w) {
        final int n = components(glFormat);
        if (glType == GL30.GL_HALF_FLOAT) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 4; c++) memPutShort(out + x * 8L + c * 2L, c < n ? memGetShort(in + ((long) x * n + c) * 2) : (c == 3 ? (short) 0x3C00 : 0));
            }
            return true;
        }
        if (glType == GL11.GL_FLOAT) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 4; c++) memPutShort(out + x * 8L + c * 2L, c < n ? toHalf(memGetFloat(in + ((long) x * n + c) * 4)) : (c == 3 ? (short) 0x3C00 : 0));
            }
            return true;
        }
        if (glType == GL11.GL_UNSIGNED_BYTE) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 4; c++) memPutShort(out + x * 8L + c * 2L, c < n ? toHalf((memGetByte(in + (long) x * n + c) & 0xFF) / 255f) : (short) 0x3C00);
            }
            return true;
        }
        return false;
    }

    public static boolean pack(Formats.Format f, long src, int w, int h, int glFormat, int glType, Store s, long dst) {
        return pack(f, src, w, h, glFormat, glType, s, dst, 0L);
    }

    public static boolean pack(Formats.Format f, long src, int w, int h, int glFormat, int glType, Store s, long dst, long srcPitch) {
        final long stride = rowStride(w, glFormat, glType, s);
        final int px = pixelBytes(glFormat, glType);
        final long base = dst + (long) s.skipRows * stride + (long) s.skipPixels * px;
        final int srcPx = f.isDepthOrStencil() ? (f.pf() == PF_DEPTH16_UNORM ? 2 : 4) : f.texelBytes();
        final int swap = s.swapBytes && !isPacked(glType) ? typeBytes(glType) : 1;
        for (int y = 0; y < h; y++) {
            final long row = base + y * stride;
            if (!packRow(f, src + y * (srcPitch != 0L ? srcPitch : (long) w * srcPx), row, w, glFormat, glType)) return false;
            if (swap == 2) for (long o = row, end = row + (long) w * px; o < end; o += 2) memPutShort(o, Short.reverseBytes(memGetShort(o)));
            else if (swap == 4) for (long o = row, end = row + (long) w * px; o < end; o += 4) memPutInt(o, Integer.reverseBytes(memGetInt(o)));
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
            if (glType == GL11.GL_FLOAT) {
                final int n = components(glFormat);
                for (int x = 0; x < w; x++) {
                    for (int c = 0; c < n; c++) {
                        final int srcC = glFormat == GL11.GL_ALPHA ? 3 : (glFormat == GL12.GL_BGRA || glFormat == GL12.GL_BGR) && c < 3 ? 2 - c : c;
                        memPutFloat(out + ((long) x * n + c) * 4, (memGetByte(in + x * 4L + srcC) & 0xFF) / 255f);
                    }
                }
                return true;
            }
            if (bytes && components(glFormat) == 1) {
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
                } else if (glType == GL11.GL_UNSIGNED_INT) memPutInt(out + x * 4L, (int) Math.round(depth * 4294967295.0));
                else if (glType == GL30.GL_UNSIGNED_INT_24_8) memPutInt(out + x * 4L, Math.round(depth * 16777215f) << 8);
                else if (glType == GL11.GL_UNSIGNED_SHORT) memPutShort(out + x * 2L, (short) Math.round(depth * 65535f));
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
            case PF_RGB10A2_UINT -> glFormat == GL30.GL_RGBA_INTEGER && glType == GL12.GL_UNSIGNED_INT_2_10_10_10_REV;
            case PF_RG11B10_FLOAT -> glFormat == GL11.GL_RGB && glType == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV;
            case PF_RGB9E5_FLOAT -> glFormat == GL11.GL_RGB && glType == GL30.GL_UNSIGNED_INT_5_9_9_9_REV;
            default -> f.isInteger() && components(glFormat) == f.channels() && typeBytes(glType) * f.channels() == f.texelBytes() && !isPacked(glType);
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
            case GL12.GL_BGR, GL12.GL_BGRA -> index < 3 ? 2 - index : 3;
            default -> index;
        };
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

    private static boolean packIntegerRow(Formats.Format f, long in, long out, int w, int glFormat, int glType) {
        final int srcBytes = f.texelBytes() / Math.max(1, f.channels());
        final boolean signed = f.kind() == Formats.K_SINT;
        final int n = components(glFormat);
        final int dstBytes = typeBytes(glType);
        if (isPacked(glType)) return false;
        for (int x = 0; x < w; x++) {
            for (int i = 0; i < n; i++) {
                final int c = channelFor(glFormat, i);
                long v = c == 3 ? 1 : 0;
                if (c < f.channels()) {
                    final long p = in + (long) x * f.texelBytes() + (long) c * srcBytes;
                    v = switch (srcBytes) {
                        case 1 -> signed ? memGetByte(p) : memGetByte(p) & 0xFF;
                        case 2 -> signed ? memGetShort(p) : memGetShort(p) & 0xFFFF;
                        default -> signed ? memGetInt(p) : memGetInt(p) & 0xFFFFFFFFL;
                    };
                }
                final long q = out + ((long) x * n + i) * dstBytes;
                switch (dstBytes) {
                    case 1 -> memPutByte(q, (byte) v);
                    case 2 -> memPutShort(q, (short) v);
                    default -> memPutInt(q, (int) v);
                }
            }
        }
        return true;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static byte unorm8(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    public static short toHalf(float f) {
        return (short) Float.floatToFloat16(f);
    }

    public static float fromHalf(short h) {
        return Float.float16ToFloat(h);
    }
}
