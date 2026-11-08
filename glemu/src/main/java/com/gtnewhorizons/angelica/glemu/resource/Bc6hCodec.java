package com.gtnewhorizons.angelica.glemu.resource;

import static org.lwjgl.system.MemoryUtil.memGetFloat;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memPutLong;

/** BC6H blocks in the one mode written here: a single subset with 10-bit endpoints and 4-bit indices. Other modes cannot be read. */
final class Bc6hCodec {
    private static final int MODE = 3, MODE_BITS = 5, ENDPOINT_BITS = 10;
    private static final int[] WEIGHTS = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    private Bc6hCodec() {}

    /** Compresses an image of RGBA32F texels. */
    static void encode(boolean signed, long rgba, int w, int h, long dst) {
        final int[] texels = new int[48];
        long out = dst;
        for (int by = 0; by < h; by += 4) {
            for (int bx = 0; bx < w; bx += 4) {
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        final long p = rgba + ((long) Math.min(by + y, h - 1) * w + Math.min(bx + x, w - 1)) * 16L;
                        for (int c = 0; c < 3; c++) texels[(y * 4 + x) * 3 + c] = half(memGetFloat(p + c * 4L), signed);
                    }
                }
                encodeBlock(texels, signed, out);
                out += 16;
            }
        }
    }

    /** A float as the signed magnitude of its half form, which grows evenly with the value's logarithm. */
    private static int half(float v, boolean signed) {
        if (Float.isNaN(v)) return 0;
        final float limited = signed ? v : Math.max(v, 0f);
        final int bits = Float.floatToFloat16(limited) & 0xFFFF;
        final int magnitude = Math.min(bits & 0x7FFF, 0x7BFF);
        return (bits & 0x8000) != 0 ? -magnitude : magnitude;
    }

    private static int finalValue(int unquantized, boolean signed) {
        return signed ? (unquantized < 0 ? -(-unquantized * 31 >> 5) : unquantized * 31 >> 5) : unquantized * 31 >> 6;
    }

    private static int unquantize(int q, boolean signed) {
        if (!signed) {
            if (q == 0) return 0;
            return q == (1 << ENDPOINT_BITS) - 1 ? 0xFFFF : ((q << 16) + 0x8000) >> ENDPOINT_BITS;
        }
        final int magnitude = Math.abs(q);
        final int value = magnitude == 0 ? 0 : magnitude >= (1 << ENDPOINT_BITS - 1) - 1 ? 0x7FFF : ((magnitude << 15) + 0x4000) >> ENDPOINT_BITS - 1;
        return q < 0 ? -value : value;
    }

    private static int quantize(int half, boolean signed) {
        final int limit = signed ? (1 << ENDPOINT_BITS - 1) - 1 : (1 << ENDPOINT_BITS) - 1;
        final double full = signed ? half * 32.0 / 31.0 / 0x7FFF : half * 64.0 / 31.0 / 0xFFFF;
        return (int) Math.max(signed ? -limit : 0, Math.min(limit, Math.round(full * limit)));
    }

    private static int interpolate(int a, int b, int weight, boolean signed) {
        return finalValue(((64 - weight) * a + weight * b + 32) >> 6, signed);
    }

    private static void encodeBlock(int[] t, boolean signed, long out) {
        final int[] lo = new int[3], hi = new int[3];
        for (int c = 0; c < 3; c++) {
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int i = 0; i < 16; i++) {
                low = Math.min(low, t[i * 3 + c]);
                high = Math.max(high, t[i * 3 + c]);
            }
            lo[c] = quantize(low, signed);
            hi[c] = quantize(high, signed);
        }
        // One index serves all three channels, so try green and blue each running with and against red.
        final int[] index = new int[16], start = new int[3], end = new int[3];
        long bestTotal = Long.MAX_VALUE;
        for (int axes = 0; axes < 4; axes++) {
            final int[] a = {lo[0], (axes & 1) == 0 ? lo[1] : hi[1], (axes & 2) == 0 ? lo[2] : hi[2]};
            final int[] b = {hi[0], (axes & 1) == 0 ? hi[1] : lo[1], (axes & 2) == 0 ? hi[2] : lo[2]};
            final int[] chosen = new int[16];
            long total = 0;
            for (int i = 0; i < 16; i++) {
                long best = Long.MAX_VALUE;
                for (int w = 0; w < 16; w++) {
                    long error = 0;
                    for (int c = 0; c < 3; c++) {
                        final long d = interpolate(unquantize(a[c], signed), unquantize(b[c], signed), WEIGHTS[w], signed) - t[i * 3 + c];
                        error += d * d;
                    }
                    if (error < best) {
                        best = error;
                        chosen[i] = w;
                    }
                }
                total += best;
            }
            if (total < bestTotal) {
                bestTotal = total;
                System.arraycopy(chosen, 0, index, 0, 16);
                System.arraycopy(a, 0, start, 0, 3);
                System.arraycopy(b, 0, end, 0, 3);
            }
        }
        // The first index's top bit is not stored, so it has to be zero.
        final boolean swap = index[0] >= 8;
        final long[] words = new long[2];
        int at = put(words, 0, MODE, MODE_BITS);
        for (int e = 0; e < 2; e++) {
            for (int c = 0; c < 3; c++) at = put(words, at, (e == 0) != swap ? start[c] : end[c], ENDPOINT_BITS);
        }
        for (int i = 0; i < 16; i++) at = put(words, at, swap ? 15 - index[i] : index[i], i == 0 ? 3 : 4);
        memPutLong(out, words[0]);
        memPutLong(out + 8, words[1]);
    }

    private static int put(long[] words, int at, int value, int count) {
        for (int i = 0; i < count; i++) {
            if ((value >>> i & 1) != 0) words[at + i >>> 6] |= 1L << (at + i & 63);
        }
        return at + count;
    }

    private static int take(long[] words, int at, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) value |= (int) (words[at + i >>> 6] >>> (at + i & 63) & 1) << i;
        return value;
    }

    /** Expands a block into RGBA8 ints, answering false for a mode not read here. */
    static boolean decode(long in, int[] t, boolean signed) {
        final long[] words = {memGetLong(in), memGetLong(in + 8)};
        if (take(words, 0, MODE_BITS) != MODE) return false;
        int at = MODE_BITS;
        final int[][] ends = new int[2][3];
        for (int e = 0; e < 2; e++) {
            for (int c = 0; c < 3; c++) {
                final int raw = take(words, at, ENDPOINT_BITS);
                at += ENDPOINT_BITS;
                // A signed endpoint is two's complement.
                ends[e][c] = unquantize(signed && raw >= 1 << ENDPOINT_BITS - 1 ? raw - (1 << ENDPOINT_BITS) : raw, signed);
            }
        }
        for (int i = 0; i < 16; i++) {
            final int width = i == 0 ? 3 : 4;
            final int weight = WEIGHTS[take(words, at, width)];
            at += width;
            for (int c = 0; c < 3; c++) {
                final int v = interpolate(ends[0][c], ends[1][c], weight, signed);
                final float value = v < 0 ? -Float.float16ToFloat((short) -v) : Float.float16ToFloat((short) v);
                t[i * 4 + c] = Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
            }
        }
        return true;
    }
}
