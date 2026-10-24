package com.gtnewhorizons.angelica.vulkan.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

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
        final int px = pixelBytes(glFormat, glType);
        final int rowPixels = s.rowLength > 0 ? s.rowLength : width;
        final long raw = (long) rowPixels * px;
        final int align = Math.max(1, s.alignment);
        return px >= align ? raw : (raw + align - 1) / align * align;
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
        final int vk = f.vk();
        switch (vk) {
            case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_SNORM, VK_FORMAT_R8G8B8A8_UINT, VK_FORMAT_R8G8B8A8_SINT:
                return rowToRgba8(glFormat, glType, in, out, w, f.baseFormat() == GL11.GL_RGB);
            case VK_FORMAT_R8_UNORM, VK_FORMAT_R8_SNORM, VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT:
                return rowToR8(f, glFormat, glType, in, out, w);
            case VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8_UINT:
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
            case VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT:
                return rowToRgba32(glFormat, glType, in, out, w);
            case VK_FORMAT_R16G16B16A16_SFLOAT:
                return rowToRgba16f(glFormat, glType, in, out, w);
            case VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_D32_SFLOAT:
                if (components(glFormat) == 1 && typeBytes(glType) == 4) {
                    memCopy(in, out, (long) w * 4);
                    return true;
                }
                if (vk == VK_FORMAT_D32_SFLOAT && glType == GL11.GL_UNSIGNED_INT) {
                    for (int x = 0; x < w; x++) memPutFloat(out + x * 4L, (float) ((memGetInt(in + x * 4L) & 0xFFFFFFFFL) / 4294967295.0));
                    return true;
                }
                if (glType == GL11.GL_FLOAT && components(glFormat) > 1) {
                    final int n = components(glFormat);
                    for (int x = 0; x < w; x++) memPutInt(out + x * 4L, memGetInt(in + (long) x * n * 4));
                    return true;
                }
                return false;
            case VK_FORMAT_R16_SFLOAT:
                if (glType == GL30.GL_HALF_FLOAT && components(glFormat) == 1) {
                    memCopy(in, out, (long) w * 2);
                    return true;
                }
                if (glType == GL11.GL_FLOAT && components(glFormat) == 1) {
                    for (int x = 0; x < w; x++) memPutShort(out + x * 2L, toHalf(memGetFloat(in + x * 4L)));
                    return true;
                }
                return false;
            case VK_FORMAT_R16G16_SFLOAT:
                if (glType == GL11.GL_FLOAT && components(glFormat) == 2) {
                    for (int x = 0; x < w * 2; x++) memPutShort(out + x * 2L, toHalf(memGetFloat(in + x * 4L)));
                    return true;
                }
                break;
            case VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D24_UNORM_S8_UINT:
                if (glType == GL11.GL_FLOAT) {
                    for (int x = 0; x < w; x++) memPutInt(out + x * 4L, (int) Math.max(0, Math.min(0xFFFFFF, Math.round(memGetFloat(in + x * 4L) * 16777215.0))));
                    return true;
                }
                if (glType == GL11.GL_UNSIGNED_INT || glType == GL30.GL_UNSIGNED_INT_24_8) {
                    final boolean packed = glType == GL30.GL_UNSIGNED_INT_24_8;
                    for (int x = 0; x < w; x++) {
                        final int v = memGetInt(in + x * 4L);
                        memPutInt(out + x * 4L, packed ? v >>> 8 : v >>> 8);
                    }
                    return true;
                }
                return false;
            default:
                break;
        }
        // same-size passthrough
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
                    final int v = memGetInt(in + x * 4L); // bytes B G R A
                    memPutInt(out + x * 4L, (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF));
                }
            } else if (glType == GL12.GL_UNSIGNED_INT_8_8_8_8) {
                for (int x = 0; x < w; x++) {
                    final int v = memGetInt(in + x * 4L); // B<<24 | G<<16 | R<<8 | A
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
        // pick the channel the legacy format keeps
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
        final long stride = rowStride(w, glFormat, glType, s);
        final int px = pixelBytes(glFormat, glType);
        final long base = dst + (long) s.skipRows * stride + (long) s.skipPixels * px;
        final int srcPx = f.isDepthOrStencil() ? (f.vk() == VK_FORMAT_D16_UNORM ? 2 : 4) : f.texelBytes();
        for (int y = 0; y < h; y++) {
            if (!packRow(f, src + (long) y * w * srcPx, base + y * stride, w, glFormat, glType)) return false;
        }
        return true;
    }

    private static boolean packRow(Formats.Format f, long in, long out, int w, int glFormat, int glType) {
        final int vk = f.vk();
        final boolean bytes = glType == GL11.GL_UNSIGNED_BYTE;
        if (vk == VK_FORMAT_R8G8B8A8_UNORM || vk == VK_FORMAT_R8G8B8A8_SRGB) {
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
            return false;
        }
        if (f.isDepth()) {
            if (glFormat != GL11.GL_DEPTH_COMPONENT && glFormat != GL30.GL_DEPTH_STENCIL) return false;
            for (int x = 0; x < w; x++) {
                final float depth = switch (vk) {
                    case VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT -> memGetFloat(in + x * 4L);
                    case VK_FORMAT_D16_UNORM -> (memGetShort(in + x * 2L) & 0xFFFF) / 65535f;
                    default -> (memGetInt(in + x * 4L) & 0xFFFFFF) / 16777215f;
                };
                if (glType == GL11.GL_FLOAT) memPutFloat(out + x * 4L, depth);
                else if (glType == GL11.GL_UNSIGNED_INT) memPutInt(out + x * 4L, (int) Math.round(depth * 4294967295.0));
                else if (glType == GL30.GL_UNSIGNED_INT_24_8) memPutInt(out + x * 4L, Math.round(depth * 16777215f) << 8);
                else if (glType == GL11.GL_UNSIGNED_SHORT) memPutShort(out + x * 2L, (short) Math.round(depth * 65535f));
                else return false;
            }
            return true;
        }
        if (pixelBytes(glFormat, glType) == f.texelBytes()) {
            memCopy(in, out, (long) w * f.texelBytes());
            return true;
        }
        if (vk == VK_FORMAT_R32G32B32A32_SFLOAT && glType == GL11.GL_FLOAT) {
            final int n = components(glFormat);
            for (int x = 0; x < w; x++) for (int c = 0; c < n; c++) memPutInt(out + ((long) x * n + c) * 4, memGetInt(in + x * 16L + c * 4L));
            return true;
        }
        if (vk == VK_FORMAT_R16G16B16A16_SFLOAT && glType == GL11.GL_FLOAT) {
            final int n = components(glFormat);
            for (int x = 0; x < w; x++) for (int c = 0; c < n; c++) memPutFloat(out + ((long) x * n + c) * 4, fromHalf(memGetShort(in + x * 8L + c * 2L)));
            return true;
        }
        return false;
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
