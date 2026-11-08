package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glemu.resource.PixelFormats.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Compresses client pixels when GL is handed uncompressed data for a compressed internal format, and expands blocks when it is asked to read one back
 * as pixels. Speed and simplicity over quality: endpoints are the block's bounding box, and of BC7 only mode 6 is written or read. ETC2 and EAC are in
 * {@link EtcCodec}, BC6H in {@link Bc6hCodec}.
 */
public final class BlockCodec {
    private static final int[] BC7_WEIGHTS = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    private BlockCodec() {}

    public static boolean isSigned(Formats.Format f) {
        return f.pf() == PF_BC4_R_SNORM || f.pf() == PF_BC5_RG_SNORM || f.pf() == PF_EAC_R11_SNORM || f.pf() == PF_EAC_RG11_SNORM;
    }

    /** Whether a format compresses from RGBA32F texels, to keep values that eight bits cannot hold. */
    public static boolean isFloat(Formats.Format f) {
        return f.pf() == PF_BC6H_SFLOAT || f.pf() == PF_BC6H_UFLOAT;
    }

    /** Compresses an image of RGBA8 texels, RGBA8_SNORM where the format is signed, or RGBA32F where it {@link #isFloat is float}. */
    public static void encode(Formats.Format f, long rgba, int w, int h, long dst) {
        if (isFloat(f)) {
            Bc6hCodec.encode(f.pf() == PF_BC6H_SFLOAT, rgba, w, h, dst);
            return;
        }
        final int[] texels = new int[64];
        long out = dst;
        for (int by = 0; by < h; by += 4) {
            for (int bx = 0; bx < w; bx += 4) {
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        final long p = rgba + ((long) Math.min(by + y, h - 1) * w + Math.min(bx + x, w - 1)) * 4L;
                        for (int c = 0; c < 4; c++) {
                            final int v = memGetByte(p + c);
                            texels[(y * 4 + x) * 4 + c] = isSigned(f) ? v : v & 0xFF;
                        }
                    }
                }
                out = encodeBlock(f, texels, out);
            }
        }
    }

    private static long encodeBlock(Formats.Format f, int[] t, long out) {
        switch (f.pf()) {
            case PF_BC1_RGBA, PF_BC1_RGBA_SRGB -> { colorBlock(t, f.baseFormat() == GL11.GL_RGBA, out); return out + 8; }
            case PF_BC2_RGBA, PF_BC2_RGBA_SRGB -> {
                long alpha = 0;
                for (int i = 0; i < 16; i++) alpha |= (long) (t[i * 4 + 3] * 15 + 127) / 255 << (i * 4);
                memPutLong(out, alpha);
                colorBlock(t, false, out + 8);
                return out + 16;
            }
            case PF_BC3_RGBA, PF_BC3_RGBA_SRGB -> {
                channelBlock(t, 3, false, out);
                colorBlock(t, false, out + 8);
                return out + 16;
            }
            case PF_BC4_R_UNORM, PF_BC4_R_SNORM -> { channelBlock(t, 0, isSigned(f), out); return out + 8; }
            case PF_BC5_RG_UNORM, PF_BC5_RG_SNORM -> {
                channelBlock(t, 0, isSigned(f), out);
                channelBlock(t, 1, isSigned(f), out + 8);
                return out + 16;
            }
            case PF_BC7_RGBA_UNORM, PF_BC7_RGBA_SRGB -> { bc7Block(t, out); return out + 16; }
            case PF_ETC2_RGB8, PF_ETC2_RGB8_SRGB -> { EtcCodec.encodeColor(t, false, out); return out + 8; }
            case PF_ETC2_RGB8A1, PF_ETC2_RGB8A1_SRGB -> { EtcCodec.encodeColor(t, true, out); return out + 8; }
            case PF_EAC_RGBA8, PF_EAC_RGBA8_SRGB -> {
                EtcCodec.encodeChannel(t, 3, EtcCodec.ALPHA8, out);
                EtcCodec.encodeColor(t, false, out + 8);
                return out + 16;
            }
            case PF_EAC_R11_UNORM, PF_EAC_R11_SNORM -> { EtcCodec.encodeChannel(t, 0, elevenBits(f), out); return out + 8; }
            case PF_EAC_RG11_UNORM, PF_EAC_RG11_SNORM -> {
                EtcCodec.encodeChannel(t, 0, elevenBits(f), out);
                EtcCodec.encodeChannel(t, 1, elevenBits(f), out + 8);
                return out + 16;
            }
            default -> throw new IllegalArgumentException("no encoder for pixel format " + f.pf());
        }
    }

    private static int elevenBits(Formats.Format f) {
        return isSigned(f) ? EtcCodec.R11_SNORM : EtcCodec.R11_UNORM;
    }

    private static int to565(int r, int g, int b) {
        return (r * 31 + 127) / 255 << 11 | (g * 63 + 127) / 255 << 5 | (b * 31 + 127) / 255;
    }

    private static void colorBlock(int[] t, boolean punchThrough, long out) {
        final int[] lo = {255, 255, 255}, hi = {0, 0, 0};
        boolean transparent = false;
        for (int i = 0; i < 16; i++) {
            if (punchThrough && t[i * 4 + 3] < 128) {
                transparent = true;
                continue;
            }
            for (int c = 0; c < 3; c++) {
                lo[c] = Math.min(lo[c], t[i * 4 + c]);
                hi[c] = Math.max(hi[c], t[i * 4 + c]);
            }
        }
        if (lo[0] > hi[0]) lo[0] = lo[1] = lo[2] = hi[0] = hi[1] = hi[2] = 0;
        int c0 = to565(hi[0], hi[1], hi[2]), c1 = to565(lo[0], lo[1], lo[2]);
        if (transparent ? c0 > c1 : c0 < c1) {
            final int swap = c0;
            c0 = c1;
            c1 = swap;
        }
        final int[][] palette = palette(c0, c1);
        int bits = 0;
        for (int i = 0; i < 16; i++) {
            int best = 3;
            if (!transparent || t[i * 4 + 3] >= 128) {
                long bestError = Long.MAX_VALUE;
                for (int p = 0; p < (transparent ? 3 : c0 == c1 ? 1 : 4); p++) {
                    long error = 0;
                    for (int c = 0; c < 3; c++) error += (long) (t[i * 4 + c] - palette[p][c]) * (t[i * 4 + c] - palette[p][c]);
                    if (error < bestError) {
                        bestError = error;
                        best = p;
                    }
                }
            }
            bits |= best << (i * 2);
        }
        memPutShort(out, (short) c0);
        memPutShort(out + 2, (short) c1);
        memPutInt(out + 4, bits);
    }

    private static int[][] palette(int c0, int c1) {
        final int[] a = expand565(c0), b = expand565(c1);
        final int[][] p = new int[4][3];
        for (int c = 0; c < 3; c++) {
            p[0][c] = a[c];
            p[1][c] = b[c];
            p[2][c] = c0 > c1 ? (2 * a[c] + b[c]) / 3 : (a[c] + b[c]) / 2;
            p[3][c] = c0 > c1 ? (a[c] + 2 * b[c]) / 3 : 0;
        }
        return p;
    }

    private static int[] expand565(int c) {
        return new int[] {(c >>> 11 & 31) * 255 / 31, (c >>> 5 & 63) * 255 / 63, (c & 31) * 255 / 31};
    }

    private static void channelBlock(int[] t, int channel, boolean signed, long out) {
        int lo = signed ? 127 : 255, hi = signed ? -128 : 0;
        for (int i = 0; i < 16; i++) {
            lo = Math.min(lo, t[i * 4 + channel]);
            hi = Math.max(hi, t[i * 4 + channel]);
        }
        final int[] values = channelPalette(hi, lo, signed);
        long bits = 0;
        for (int i = 0; i < 16; i++) {
            int best = 0;
            for (int p = 1; p < 8; p++) {
                if (Math.abs(t[i * 4 + channel] - values[p]) < Math.abs(t[i * 4 + channel] - values[best])) best = p;
            }
            bits |= (long) best << (i * 3);
        }
        memPutLong(out, (hi & 0xFFL) | (lo & 0xFFL) << 8 | bits << 16);
    }

    private static int[] channelPalette(int a, int b, boolean signed) {
        final int[] v = new int[8];
        v[0] = a;
        v[1] = b;
        if (a > b) {
            for (int i = 1; i < 7; i++) v[i + 1] = ((7 - i) * a + i * b) / 7;
        } else {
            for (int i = 1; i < 5; i++) v[i + 1] = ((5 - i) * a + i * b) / 5;
            v[6] = signed ? -127 : 0;
            v[7] = signed ? 127 : 255;
        }
        return v;
    }

    private static void bc7Block(int[] t, long out) {
        final int[] e0 = {255, 255, 255, 255}, e1 = {0, 0, 0, 0};
        for (int i = 0; i < 16; i++) {
            for (int c = 0; c < 4; c++) {
                e0[c] = Math.min(e0[c], t[i * 4 + c]);
                e1[c] = Math.max(e1[c], t[i * 4 + c]);
            }
        }
        // Each endpoint's low bit is a p-bit its channels share: 0 for the first, 1 for the second.
        for (int c = 0; c < 4; c++) {
            e0[c] &= 0xFE;
            e1[c] |= 1;
        }
        final int[] index = new int[16];
        for (int i = 0; i < 16; i++) {
            long best = Long.MAX_VALUE;
            for (int w = 0; w < 16; w++) {
                long error = 0;
                for (int c = 0; c < 4; c++) {
                    final int v = ((64 - BC7_WEIGHTS[w]) * e0[c] + BC7_WEIGHTS[w] * e1[c] + 32) >> 6;
                    error += (long) (v - t[i * 4 + c]) * (v - t[i * 4 + c]);
                }
                if (error < best) {
                    best = error;
                    index[i] = w;
                }
            }
        }
        // The first index's top bit is not stored, so it has to be zero.
        boolean flip = index[0] >= 8;
        long lo = 1L << 6, hi = 0;
        int at = 7;
        for (int c = 0; c < 4; c++) {
            for (int e = 0; e < 2; e++) {
                final int v = ((e == 0) != flip ? e0[c] : e1[c]) >>> 1;
                lo |= (long) v << at;
                at += 7;
            }
        }
        // Mode 6's fields fill the low word to bit 62: the p bits follow, then the indices.
        lo |= (flip ? 1L : 0L) << 63;
        hi |= flip ? 0L : 1L;
        int bit = 1;
        for (int i = 0; i < 16; i++) {
            final int v = flip ? 15 - index[i] : index[i];
            hi |= (long) v << bit;
            bit += i == 0 ? 3 : 4;
        }
        memPutLong(out, lo);
        memPutLong(out + 8, hi);
    }

    /** Expands blocks into RGBA8 or RGBA8_SNORM texels of a level this size, answering false for BC6H and BC7 blocks this cannot read. */
    public static boolean decode(Formats.Format f, long blocks, int w, int h, long rgba) {
        final int[] t = new int[64];
        long in = blocks;
        for (int by = 0; by < h; by += 4) {
            for (int bx = 0; bx < w; bx += 4) {
                java.util.Arrays.fill(t, 0);
                for (int i = 0; i < 16; i++) t[i * 4 + 3] = isSigned(f) ? 127 : 255;
                if (!decodeBlock(f, in, t)) return false;
                in += f.blockBytes();
                for (int y = 0; y < 4 && by + y < h; y++) {
                    for (int x = 0; x < 4 && bx + x < w; x++) {
                        final long p = rgba + ((long) (by + y) * w + bx + x) * 4L;
                        for (int c = 0; c < 4; c++) memPutByte(p + c, (byte) t[(y * 4 + x) * 4 + c]);
                    }
                }
            }
        }
        return true;
    }

    private static boolean decodeBlock(Formats.Format f, long in, int[] t) {
        switch (f.pf()) {
            case PF_BC1_RGBA, PF_BC1_RGBA_SRGB -> readColor(in, t, f.baseFormat() == GL11.GL_RGBA);
            case PF_BC2_RGBA, PF_BC2_RGBA_SRGB -> {
                final long alpha = memGetLong(in);
                for (int i = 0; i < 16; i++) t[i * 4 + 3] = (int) (alpha >>> (i * 4) & 15) * 17;
                readColor(in + 8, t, false);
            }
            case PF_BC3_RGBA, PF_BC3_RGBA_SRGB -> {
                readChannel(in, t, 3, false);
                readColor(in + 8, t, false);
            }
            case PF_BC4_R_UNORM, PF_BC4_R_SNORM -> readChannel(in, t, 0, isSigned(f));
            case PF_BC5_RG_UNORM, PF_BC5_RG_SNORM -> {
                readChannel(in, t, 0, isSigned(f));
                readChannel(in + 8, t, 1, isSigned(f));
            }
            case PF_BC6H_SFLOAT, PF_BC6H_UFLOAT -> { return Bc6hCodec.decode(in, t, f.pf() == PF_BC6H_SFLOAT); }
            case PF_BC7_RGBA_UNORM, PF_BC7_RGBA_SRGB -> { return readBc7(in, t); }
            case PF_ETC2_RGB8, PF_ETC2_RGB8_SRGB -> EtcCodec.decodeColor(in, t, false);
            case PF_ETC2_RGB8A1, PF_ETC2_RGB8A1_SRGB -> EtcCodec.decodeColor(in, t, true);
            case PF_EAC_RGBA8, PF_EAC_RGBA8_SRGB -> {
                EtcCodec.decodeChannel(in, t, 3, EtcCodec.ALPHA8);
                EtcCodec.decodeColor(in + 8, t, false);
            }
            case PF_EAC_R11_UNORM, PF_EAC_R11_SNORM -> EtcCodec.decodeChannel(in, t, 0, elevenBits(f));
            case PF_EAC_RG11_UNORM, PF_EAC_RG11_SNORM -> {
                EtcCodec.decodeChannel(in, t, 0, elevenBits(f));
                EtcCodec.decodeChannel(in + 8, t, 1, elevenBits(f));
            }
            default -> { return false; }
        }
        return true;
    }

    private static void readColor(long in, int[] t, boolean punchThrough) {
        final int c0 = memGetShort(in) & 0xFFFF, c1 = memGetShort(in + 2) & 0xFFFF, bits = memGetInt(in + 4);
        final int[][] p = palette(c0, c1);
        for (int i = 0; i < 16; i++) {
            final int index = bits >>> (i * 2) & 3;
            for (int c = 0; c < 3; c++) t[i * 4 + c] = p[index][c];
            if (punchThrough && c0 <= c1 && index == 3) t[i * 4 + 3] = 0;
        }
    }

    private static void readChannel(long in, int[] t, int channel, boolean signed) {
        final int a = memGetByte(in), b = memGetByte(in + 1);
        final int[] values = signed ? channelPalette(a, b, true) : channelPalette(a & 0xFF, b & 0xFF, false);
        final long bits = memGetLong(in) >>> 16;
        for (int i = 0; i < 16; i++) t[i * 4 + channel] = values[(int) (bits >>> (i * 3) & 7)];
    }

    private static boolean readBc7(long in, int[] t) {
        final long lo = memGetLong(in), hi = memGetLong(in + 8);
        if ((lo & 0x7F) != 1L << 6) return false;
        final int p0 = (int) (lo >>> 63), p1 = (int) (hi & 1);
        final int[] e0 = new int[4], e1 = new int[4];
        int at = 7;
        for (int c = 0; c < 4; c++) {
            e0[c] = (int) (lo >>> at & 0x7F) << 1 | p0;
            e1[c] = (int) (lo >>> (at + 7) & 0x7F) << 1 | p1;
            at += 14;
        }
        int bit = 1;
        for (int i = 0; i < 16; i++) {
            final int width = i == 0 ? 3 : 4;
            final int weight = BC7_WEIGHTS[(int) (hi >>> bit & (1L << width) - 1)];
            bit += width;
            for (int c = 0; c < 4; c++) t[i * 4 + c] = ((64 - weight) * e0[c] + weight * e1[c] + 32) >> 6;
        }
        return true;
    }
}
