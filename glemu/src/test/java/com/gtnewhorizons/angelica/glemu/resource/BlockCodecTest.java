package com.gtnewhorizons.angelica.glemu.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.ARBTextureCompressionBPTC;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockCodecTest {
    private static final int SIZE = 11;

    private static long blockWords(long big) {
        final long at = MemoryUtil.nmemAlloc(8);
        MemoryUtil.memPutLong(at, Long.reverseBytes(big));
        return at;
    }

    /** Writes a gradient, checks nothing lands past the blocks, and answers the decoded texels' worst difference from it. */
    private static int roundTrip(int internalFormat, boolean signed) {
        final Formats.Format f = Formats.of(internalFormat, 0, 0);
        final long source = MemoryUtil.nmemAlloc(SIZE * SIZE * 4L);
        final long decoded = MemoryUtil.nmemAlloc(SIZE * SIZE * 4L);
        final long bytes = Formats.imageBytes(f, SIZE, SIZE);
        final long blocks = MemoryUtil.nmemAlloc(bytes + 32);
        try {
            MemoryUtil.memSet(blocks + bytes, 0x5A, 32);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    final long p = source + (y * SIZE + x) * 4L;
                    final int r = x * 8 + 40, g = y * 8 + 40, b = (x + y) * 4 + 40;
                    MemoryUtil.memPutByte(p, (byte) (signed ? r / 2 : r));
                    MemoryUtil.memPutByte(p + 1, (byte) (signed ? g / 2 : g));
                    MemoryUtil.memPutByte(p + 2, (byte) (signed ? b / 2 : b));
                    MemoryUtil.memPutByte(p + 3, (byte) (signed ? 127 : 255));
                }
            }
            BlockCodec.encode(f, source, SIZE, SIZE, blocks);
            for (int i = 0; i < 32; i++) assertEquals(0x5A, MemoryUtil.memGetByte(blocks + bytes + i), "wrote past the blocks");
            assertTrue(BlockCodec.decode(f, blocks, SIZE, SIZE, decoded));
            int worst = 0;
            final int channels = f.baseFormat() == org.lwjgl.opengl.GL11.GL_RED ? 1 : f.baseFormat() == org.lwjgl.opengl.GL30.GL_RG ? 2 : 3;
            for (long i = 0; i < SIZE * SIZE; i++) {
                for (int c = 0; c < channels; c++) {
                    worst = Math.max(worst, Math.abs(MemoryUtil.memGetByte(source + i * 4 + c) - MemoryUtil.memGetByte(decoded + i * 4 + c)));
                }
            }
            return worst;
        } finally {
            MemoryUtil.nmemFree(source);
            MemoryUtil.nmemFree(decoded);
            MemoryUtil.nmemFree(blocks);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {GL43.GL_COMPRESSED_RGB8_ETC2, GL43.GL_COMPRESSED_SRGB8_ETC2, GL43.GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2,
        GL43.GL_COMPRESSED_SRGB8_PUNCHTHROUGH_ALPHA1_ETC2, GL43.GL_COMPRESSED_RGBA8_ETC2_EAC, GL43.GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC,
        GL43.GL_COMPRESSED_R11_EAC, GL43.GL_COMPRESSED_RG11_EAC, ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB})
    void unsignedFormatsStayWithinTheirBlocksAndNearTheirTexels(int internalFormat) {
        final int worst = roundTrip(internalFormat, false);
        assertTrue(worst <= 32, "worst difference " + worst);
    }

    @ParameterizedTest
    @ValueSource(ints = {GL43.GL_COMPRESSED_SIGNED_R11_EAC, GL43.GL_COMPRESSED_SIGNED_RG11_EAC})
    void signedFormatsStayWithinTheirBlocksAndNearTheirTexels(int internalFormat) {
        final int worst = roundTrip(internalFormat, true);
        assertTrue(worst <= 8, "worst difference " + worst);
    }

    private static int[] decodeOne(long big) {
        final long at = blockWords(big);
        final long rgba = MemoryUtil.nmemAlloc(64);
        try {
            assertTrue(BlockCodec.decode(Formats.of(GL43.GL_COMPRESSED_RGB8_ETC2, 0, 0), at, 4, 4, rgba));
            final int[] texels = new int[64];
            for (int i = 0; i < 64; i++) texels[i] = MemoryUtil.memGetByte(rgba + i) & 0xFF;
            return texels;
        } finally {
            MemoryUtil.nmemFree(at);
            MemoryUtil.nmemFree(rgba);
        }
    }

    @Test
    void individualModeAddsTheSmallModifierToItsBase() {
        // Subblock colours 0x8, table 0, every index 00: 0x88 + 2 throughout.
        final int[] t = decodeOne(0x8888880000000000L);
        for (int c = 0; c < 3; c++) assertEquals(0x88 + 2, t[c]);
    }

    @Test
    void tModePaintsItsFirstBaseAtIndexZero() {
        // Red 31 with a delta of +1 overflows, which selects T mode. Base one: red 0b1101 across the split field, green 3, blue 5.
        final long word = 31L << 59 | 1L << 56 | 3L << 52 | 5L << 48 | 1L << 33;
        final int[] t = decodeOne(word);
        assertEquals(0xDD, t[0]);
        assertEquals(0x33, t[1]);
        assertEquals(0x55, t[2]);
    }

    @Test
    void planarModeInterpolatesFromItsOrigin() {
        // Blue 31 + 1 overflows and the others do not, which selects planar. The origin's red is full and the H and V colours are black.
        final int originRed = 31 << 1 | 1;
        final long word = (long) originRed << 57 | 1L << 33 | 31L << 43 | 1L << 40;
        final int[] t = decodeOne(word);
        assertEquals(255, t[0]);
        assertEquals(105, t[2]);
        assertEquals(0, t[60]);
    }

    @Test
    void readingABc6hModeNotWrittenHereAnswersFalse() {
        final Formats.Format f = Formats.of(ARBTextureCompressionBPTC.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT_ARB, 0, 0);
        final long blocks = MemoryUtil.nmemCalloc(1, 16);
        final long rgba = MemoryUtil.nmemAlloc(64);
        try {
            assertFalse(BlockCodec.decode(f, blocks, 4, 4, rgba));
        } finally {
            MemoryUtil.nmemFree(blocks);
            MemoryUtil.nmemFree(rgba);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bc6hKeepsValuesAndStaysWithinItsBlock(boolean signed) {
        final Formats.Format f = Formats.of(signed ? ARBTextureCompressionBPTC.GL_COMPRESSED_RGB_BPTC_SIGNED_FLOAT_ARB
            : ARBTextureCompressionBPTC.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT_ARB, 0, 0);
        final long source = MemoryUtil.nmemAlloc(16 * 16);
        final long blocks = MemoryUtil.nmemAlloc(48);
        final long rgba = MemoryUtil.nmemAlloc(64);
        try {
            MemoryUtil.memSet(blocks + 16, 0x5A, 32);
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(source + i * 16L, 0.25f + i * 0.03f);
                MemoryUtil.memPutFloat(source + i * 16L + 4, 0.5f);
                MemoryUtil.memPutFloat(source + i * 16L + 8, 0.75f - i * 0.03f);
                MemoryUtil.memPutFloat(source + i * 16L + 12, 1f);
            }
            BlockCodec.encode(f, source, 4, 4, blocks);
            for (int i = 16; i < 48; i++) assertEquals(0x5A, MemoryUtil.memGetByte(blocks + i));
            assertTrue(BlockCodec.decode(f, blocks, 4, 4, rgba));
            for (int i = 0; i < 16; i++) {
                final int red = MemoryUtil.memGetByte(rgba + i * 4L) & 0xFF;
                assertEquals((0.25f + i * 0.03f) * 255f, red, 24f, "texel " + i);
                assertEquals(0.5f * 255f, MemoryUtil.memGetByte(rgba + i * 4L + 1) & 0xFF, 24f, "texel " + i);
            }
        } finally {
            MemoryUtil.nmemFree(source);
            MemoryUtil.nmemFree(blocks);
            MemoryUtil.nmemFree(rgba);
        }
    }
}
