package com.gtnewhorizons.angelica.glemu.resource;

import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memPutLong;

/**
 * ETC2 and EAC blocks, held big-endian as the formats define them. Texels are row-major RGBA ints, as in {@link BlockCodec}. Every mode can be read;
 * writing uses the differential and individual modes only.
 */
final class EtcCodec {
    static final int ALPHA8 = 0, R11_UNORM = 1, R11_SNORM = 2;

    private static final int[][] INTENSITY = {{2, 8}, {5, 17}, {9, 29}, {13, 42}, {18, 60}, {24, 80}, {33, 106}, {47, 183}};
    private static final int[] DISTANCE = {3, 6, 11, 16, 23, 32, 41, 64};
    private static final int[][] EAC = {
        {-3, -6, -9, -15, 2, 5, 8, 14}, {-3, -7, -10, -13, 2, 6, 9, 12}, {-2, -5, -8, -13, 1, 4, 7, 12}, {-2, -4, -6, -13, 1, 3, 5, 12},
        {-3, -6, -8, -12, 2, 5, 7, 11}, {-3, -7, -9, -11, 2, 6, 8, 10}, {-4, -7, -8, -11, 3, 6, 7, 10}, {-3, -5, -8, -11, 2, 4, 7, 10},
        {-2, -6, -8, -10, 1, 5, 7, 9}, {-2, -5, -8, -10, 1, 4, 7, 9}, {-2, -4, -8, -10, 1, 3, 7, 9}, {-2, -5, -7, -10, 1, 4, 6, 9},
        {-3, -4, -7, -10, 2, 3, 6, 9}, {-1, -2, -3, -10, 0, 1, 2, 9}, {-4, -6, -8, -9, 3, 5, 7, 8}, {-3, -5, -7, -9, 2, 4, 6, 8},
    };

    private EtcCodec() {}

    static void encodeColor(int[] t, boolean punchThrough, long out) {
        boolean opaque = true;
        if (punchThrough) {
            for (int i = 0; i < 16; i++) opaque &= t[i * 4 + 3] >= 128;
        }
        long best = 0, bestError = Long.MAX_VALUE;
        for (int flip = 0; flip < 2; flip++) {
            final int[][] average = averages(t, flip == 1, punchThrough);
            final int[] first = new int[3], second = new int[3];
            boolean differential = true;
            for (int c = 0; c < 3; c++) {
                first[c] = (average[0][c] * 31 + 127) / 255;
                second[c] = (average[1][c] * 31 + 127) / 255;
                final int delta = second[c] - first[c];
                differential &= delta >= -4 && delta <= 3;
            }
            differential |= punchThrough;
            final boolean bit33 = punchThrough ? opaque : differential;
            final long[] word = {(flip == 1 ? 1L : 0L) << 32 | (bit33 ? 1L : 0L) << 33};
            final int[] base0 = new int[3], base1 = new int[3];
            for (int c = 0; c < 3; c++) {
                final int shift = 56 - 8 * c;
                if (differential) {
                    final int delta = Math.max(-4, Math.min(3, second[c] - first[c]));
                    final int other = first[c] + delta;
                    word[0] |= (long) first[c] << (shift + 3) | (long) (delta & 7) << shift;
                    base0[c] = first[c] << 3 | first[c] >> 2;
                    base1[c] = other << 3 | other >> 2;
                } else {
                    final int a = (average[0][c] * 15 + 127) / 255, b = (average[1][c] * 15 + 127) / 255;
                    word[0] |= (long) a << (shift + 4) | (long) b << shift;
                    base0[c] = a * 17;
                    base1[c] = b * 17;
                }
            }
            final long error = subblock(t, 0, flip == 1, base0, opaque, punchThrough, word) + subblock(t, 1, flip == 1, base1, opaque, punchThrough, word);
            if (error < bestError) {
                bestError = error;
                best = word[0];
            }
        }
        memPutLong(out, Long.reverseBytes(best));
    }

    private static int[][] averages(int[] t, boolean flip, boolean punchThrough) {
        final int[][] sum = new int[2][3];
        final int[] count = new int[2];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                final int at = (y * 4 + x) * 4;
                if (punchThrough && t[at + 3] < 128) continue;
                final int s = flip ? y >> 1 : x >> 1;
                count[s]++;
                for (int c = 0; c < 3; c++) sum[s][c] += t[at + c];
            }
        }
        for (int s = 0; s < 2; s++) {
            for (int c = 0; c < 3; c++) sum[s][c] = count[s] == 0 ? 0 : (sum[s][c] + count[s] / 2) / count[s];
        }
        return sum;
    }

    /** Adds a subblock's best table and indices to the word, answering the error. */
    private static long subblock(int[] t, int s, boolean flip, int[] base, boolean opaque, boolean punchThrough, long[] word) {
        long bestError = Long.MAX_VALUE;
        long bestIndices = 0;
        int bestTable = 0;
        for (int table = 0; table < 8; table++) {
            final int[] modifiers = modifiers(table, opaque);
            long error = 0, indices = 0;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    if ((flip ? y >> 1 : x >> 1) != s) continue;
                    final int at = (y * 4 + x) * 4;
                    int choice = 2;
                    if (!punchThrough || t[at + 3] >= 128) {
                        long nearest = Long.MAX_VALUE;
                        for (int m = 0; m < 4; m++) {
                            if (!opaque && m == 2) continue;
                            long e = 0;
                            for (int c = 0; c < 3; c++) {
                                final int d = clamp(base[c] + modifiers[m]) - t[at + c];
                                e += (long) d * d;
                            }
                            if (e < nearest) {
                                nearest = e;
                                choice = m;
                            }
                        }
                        error += nearest;
                    }
                    final int position = x * 4 + y;
                    indices |= (long) (choice >> 1) << (16 + position) | (long) (choice & 1) << position;
                }
            }
            if (error < bestError) {
                bestError = error;
                bestTable = table;
                bestIndices = indices;
            }
        }
        word[0] |= bestIndices | (long) bestTable << (s == 0 ? 37 : 34);
        return bestError;
    }

    private static int[] modifiers(int table, boolean opaque) {
        final int a = INTENSITY[table][0], b = INTENSITY[table][1];
        return opaque ? new int[] {a, b, -a, -b} : new int[] {0, b, 0, -b};
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static int bits(long word, int top, int count) {
        return (int) (word >>> (top - count + 1) & (1L << count) - 1);
    }

    private static int signed3(int v) {
        return v >= 4 ? v - 8 : v;
    }

    static void decodeColor(long in, int[] t, boolean punchThrough) {
        final long w = Long.reverseBytes(memGetLong(in));
        final boolean bit33 = (w >>> 33 & 1) != 0;
        final boolean opaque = !punchThrough || bit33;
        if (!punchThrough && !bit33) {
            final int[] a = new int[3], b = new int[3];
            for (int c = 0; c < 3; c++) {
                a[c] = bits(w, 63 - 8 * c, 4) * 17;
                b[c] = bits(w, 59 - 8 * c, 4) * 17;
            }
            subblocks(w, t, a, b, opaque);
            return;
        }
        final int[] first = new int[3], second = new int[3];
        for (int c = 0; c < 3; c++) {
            first[c] = bits(w, 63 - 8 * c, 5);
            second[c] = first[c] + signed3(bits(w, 58 - 8 * c, 3));
        }
        if (second[0] < 0 || second[0] > 31) {
            final int[] a = {(bits(w, 60, 2) << 2 | bits(w, 57, 2)) * 17, bits(w, 55, 4) * 17, bits(w, 51, 4) * 17};
            final int[] b = {bits(w, 47, 4) * 17, bits(w, 43, 4) * 17, bits(w, 39, 4) * 17};
            final int d = DISTANCE[bits(w, 35, 2) << 1 | bits(w, 32, 1)];
            paint(w, t, opaque, new int[][] {a, shifted(b, d), b, shifted(b, -d)});
        } else if (second[1] < 0 || second[1] > 31) {
            final int r0 = bits(w, 62, 4), g0 = bits(w, 58, 3) << 1 | bits(w, 52, 1), b0 = bits(w, 51, 1) << 3 | bits(w, 49, 3);
            final int r1 = bits(w, 46, 4), g1 = bits(w, 42, 4), b1 = bits(w, 38, 4);
            final int order = (r0 << 8 | g0 << 4 | b0) >= (r1 << 8 | g1 << 4 | b1) ? 1 : 0;
            final int d = DISTANCE[bits(w, 34, 1) << 2 | bits(w, 32, 1) << 1 | order];
            final int[] a = {r0 * 17, g0 * 17, b0 * 17}, b = {r1 * 17, g1 * 17, b1 * 17};
            paint(w, t, opaque, new int[][] {shifted(a, d), shifted(a, -d), shifted(b, d), shifted(b, -d)});
        } else if (second[2] < 0 || second[2] > 31) {
            planar(w, t);
        } else {
            final int[] a = new int[3], b = new int[3];
            for (int c = 0; c < 3; c++) {
                a[c] = first[c] << 3 | first[c] >> 2;
                b[c] = second[c] << 3 | second[c] >> 2;
            }
            subblocks(w, t, a, b, opaque);
        }
    }

    private static int[] shifted(int[] color, int by) {
        return new int[] {clamp(color[0] + by), clamp(color[1] + by), clamp(color[2] + by)};
    }

    private static int index(long w, int x, int y) {
        final int position = x * 4 + y;
        return (int) (w >>> (16 + position) & 1) << 1 | (int) (w >>> position & 1);
    }

    private static void subblocks(long w, int[] t, int[] first, int[] second, boolean opaque) {
        final boolean flip = (w >>> 32 & 1) != 0;
        final int[] table0 = modifiers(bits(w, 39, 3), opaque), table1 = modifiers(bits(w, 36, 3), opaque);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                final int s = flip ? y >> 1 : x >> 1;
                final int i = index(w, x, y);
                final int at = (y * 4 + x) * 4;
                if (!opaque && i == 2) {
                    transparent(t, at);
                    continue;
                }
                for (int c = 0; c < 3; c++) t[at + c] = clamp((s == 0 ? first : second)[c] + (s == 0 ? table0 : table1)[i]);
            }
        }
    }

    private static void paint(long w, int[] t, boolean opaque, int[][] colors) {
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                final int i = index(w, x, y);
                final int at = (y * 4 + x) * 4;
                if (!opaque && i == 2) {
                    transparent(t, at);
                    continue;
                }
                for (int c = 0; c < 3; c++) t[at + c] = colors[i][c];
            }
        }
    }

    private static void transparent(int[] t, int at) {
        for (int c = 0; c < 4; c++) t[at + c] = 0;
    }

    private static void planar(long w, int[] t) {
        final int[] o = {
            six(bits(w, 62, 6)), seven(bits(w, 56, 1) << 6 | bits(w, 54, 6)), six(bits(w, 48, 1) << 5 | bits(w, 44, 2) << 3 | bits(w, 41, 3)),
        };
        final int[] h = {six(bits(w, 38, 5) << 1 | bits(w, 32, 1)), seven(bits(w, 31, 7)), six(bits(w, 24, 6))};
        final int[] v = {six(bits(w, 18, 6)), seven(bits(w, 12, 7)), six(bits(w, 5, 6))};
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                for (int c = 0; c < 3; c++) t[(y * 4 + x) * 4 + c] = clamp((x * (h[c] - o[c]) + y * (v[c] - o[c]) + 4 * o[c] + 2) >> 2);
            }
        }
    }

    private static int six(int v) {
        return v << 2 | v >> 4;
    }

    private static int seven(int v) {
        return v << 1 | v >> 6;
    }

    private static int target(int texel, int kind) {
        return switch (kind) {
            case R11_UNORM -> (texel * 2047 + 127) / 255;
            case R11_SNORM -> Math.round(Math.max(texel, -127) * 1023 / 127f);
            default -> texel;
        };
    }

    private static int channelValue(int kind, int base, int multiplier, int modifier) {
        return switch (kind) {
            case R11_UNORM -> Math.max(0, Math.min(2047, base * 8 + 4 + (multiplier == 0 ? modifier : modifier * multiplier * 8)));
            case R11_SNORM -> Math.max(-1023, Math.min(1023, Math.max(base, -127) * 8 + (multiplier == 0 ? modifier : modifier * multiplier * 8)));
            default -> clamp(base + modifier * multiplier);
        };
    }

    static void encodeChannel(int[] t, int channel, int kind, long out) {
        final int[] targets = new int[16];
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            targets[i] = target(t[i * 4 + channel], kind);
            lo = Math.min(lo, targets[i]);
            hi = Math.max(hi, targets[i]);
        }
        final int scale = kind == ALPHA8 ? 1 : 8, offset = kind == R11_UNORM ? 4 : 0;
        final int baseLow = kind == R11_SNORM ? -127 : 0, baseHigh = kind == R11_SNORM ? 127 : 255;
        long bestError = Long.MAX_VALUE, best = 0;
        for (int table = 0; table < 16; table++) {
            final int[] row = EAC[table];
            final int low = row[3], high = row[7];
            final int fit = Math.max(1, Math.min(15, Math.round((hi - lo) / (float) ((high - low) * scale))));
            for (int multiplier = Math.max(1, fit - 1); multiplier <= Math.min(15, fit + 1); multiplier++) {
                final float middle = ((hi + lo) / 2f - offset - (high + low) / 2f * multiplier * scale) / scale;
                final int base = Math.max(baseLow, Math.min(baseHigh, Math.round(middle)));
                long error = 0, selectors = 0;
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        final int want = targets[y * 4 + x];
                        int choice = 0;
                        long nearest = Long.MAX_VALUE;
                        for (int s = 0; s < 8; s++) {
                            final long d = channelValue(kind, base, multiplier, row[s]) - want;
                            if (d * d < nearest) {
                                nearest = d * d;
                                choice = s;
                            }
                        }
                        error += nearest;
                        selectors |= (long) choice << (45 - (x * 4 + y) * 3);
                    }
                }
                if (error < bestError) {
                    bestError = error;
                    best = (base & 0xFFL) << 56 | (long) multiplier << 52 | (long) table << 48 | selectors;
                }
            }
        }
        memPutLong(out, Long.reverseBytes(best));
    }

    static void decodeChannel(long in, int[] t, int channel, int kind) {
        final long w = Long.reverseBytes(memGetLong(in));
        final int base = kind == R11_SNORM ? (byte) (w >>> 56) : (int) (w >>> 56 & 0xFF);
        final int multiplier = (int) (w >>> 52 & 15);
        final int[] row = EAC[(int) (w >>> 48 & 15)];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                final int v = channelValue(kind, base, multiplier, row[(int) (w >>> (45 - (x * 4 + y) * 3) & 7)]);
                t[(y * 4 + x) * 4 + channel] = switch (kind) {
                    case R11_UNORM -> (v * 255 + 1023) / 2047;
                    case R11_SNORM -> Math.round(v * 127 / 1023f);
                    default -> v;
                };
            }
        }
    }
}
