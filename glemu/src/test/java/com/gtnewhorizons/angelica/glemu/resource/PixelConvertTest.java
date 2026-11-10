package com.gtnewhorizons.angelica.glemu.resource;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelConvertTest {

    @Test
    void rgbaSurvivesARoundTrip() {
        final ByteBuffer in = MemoryUtil.memAlloc(16), image = MemoryUtil.memAlloc(16), out = MemoryUtil.memAlloc(16);
        try {
            for (int i = 0; i < 16; i++) in.put(i, (byte) (i * 11));
            final PixelConvert.Store tight = new PixelConvert.Store();
            tight.alignment = 1;
            assertTrue(PixelConvert.unpack(Formats.RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(in), 2, 2, 1, tight, MemoryUtil.memAddress(image)));
            assertTrue(PixelConvert.pack(Formats.RGBA8, MemoryUtil.memAddress(image), 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, tight, MemoryUtil.memAddress(out)));
            for (int i = 0; i < 16; i++) assertEquals(in.get(i), out.get(i), "byte " + i);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void unpackStateSelectsTheSourceRegion() {
        final ByteBuffer in = MemoryUtil.memCalloc(4 * 4 * 4), image = MemoryUtil.memAlloc(4);
        try {
            final int row = 2, column = 1;
            in.put((row * 4 + column) * 4, (byte) 77);
            final PixelConvert.Store store = new PixelConvert.Store();
            store.rowLength = 4;
            store.skipRows = row;
            store.skipPixels = column;
            assertTrue(PixelConvert.unpack(Formats.RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(in), 1, 1, 1, store, MemoryUtil.memAddress(image)));
            assertEquals(77, image.get(0));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void unpackHonoursSwapBytes() {
        final ByteBuffer in = MemoryUtil.memAlloc(2), image = MemoryUtil.memAlloc(2);
        final PixelConvert.Store store = new PixelConvert.Store();
        store.swapBytes = true;
        try {
            in.put(0, (byte) 1).put(1, (byte) 2);
            assertTrue(PixelConvert.unpack(Formats.of(GL30.GL_R16, 0, 0), GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, MemoryUtil.memAddress(in), 1, 1, 1, store, MemoryUtil.memAddress(image)));
            assertEquals(2, image.get(0));
            assertEquals(1, image.get(1));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void bgraSwapsRedAndBlue() {
        final ByteBuffer in = MemoryUtil.memAlloc(4), image = MemoryUtil.memAlloc(4);
        try {
            in.put(0, (byte) 1).put(1, (byte) 2).put(2, (byte) 3).put(3, (byte) 4);
            assertTrue(PixelConvert.unpack(Formats.RGBA8, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(in), 1, 1, 1, new PixelConvert.Store(), MemoryUtil.memAddress(image)));
            assertEquals(3, image.get(0));
            assertEquals(2, image.get(1));
            assertEquals(1, image.get(2));
            assertEquals(4, image.get(3));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    private static Formats.Format format(int glInternal) {
        return Formats.of(glInternal, PixelFormats.PF_DEPTH32_FLOAT_STENCIL8, PixelFormats.PF_DEPTH32_FLOAT);
    }

    private static PixelConvert.Store tight() {
        final PixelConvert.Store store = new PixelConvert.Store();
        store.alignment = 1;
        return store;
    }

    @Test
    void unsignedDepthUploadsBecomeFloats() {
        final ByteBuffer in = MemoryUtil.memAlloc(8), image = MemoryUtil.memAlloc(8);
        try {
            in.putInt(0, 0xFFFFFFFF).putInt(4, 0);
            assertTrue(PixelConvert.unpack(format(GL14.GL_DEPTH_COMPONENT32), GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, MemoryUtil.memAddress(in), 2, 1, 1, tight(), MemoryUtil.memAddress(image)));
            assertEquals(1f, image.getFloat(0), 0.0001f);
            assertEquals(0f, image.getFloat(4), 0.0001f);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    private static float[] toRgba16f(int glFormat, byte... texel) {
        final ByteBuffer in = MemoryUtil.memAlloc(texel.length), image = MemoryUtil.memAlloc(8);
        try {
            in.put(0, texel);
            assertTrue(PixelConvert.unpack(format(GL30.GL_RGBA16F), glFormat, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(in), 1, 1, 1, tight(), MemoryUtil.memAddress(image)));
            final float[] out = new float[4];
            for (int c = 0; c < 4; c++) out[c] = PixelConvert.fromHalf(image.getShort(c * 2));
            return out;
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void bgraBytesIntoHalfFloatKeepTheirChannels() {
        assertArrayEquals(new float[] {1f, 0f, 0f, 1f}, toRgba16f(GL12.GL_BGRA, (byte) 0, (byte) 0, (byte) 255, (byte) 255), 0.001f);
    }

    @Test
    void redBytesIntoHalfFloatLeaveTheOtherColorsAtZero() {
        assertArrayEquals(new float[] {1f, 0f, 0f, 1f}, toRgba16f(GL11.GL_RED, (byte) 255), 0.001f);
    }

    @Test
    void bytesAreConvertedIntoAPackedFormatChannelByChannel() {
        final ByteBuffer in = MemoryUtil.memAlloc(4), image = MemoryUtil.memAlloc(4);
        try {
            in.put(0, (byte) 255).put(1, (byte) 0).put(2, (byte) 255).put(3, (byte) 255);
            assertTrue(PixelConvert.unpack(format(GL11.GL_RGB10_A2), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(in), 1, 1, 1, tight(), MemoryUtil.memAddress(image)));
            assertEquals(1023 | 1023 << 20 | 3 << 30, image.getInt(0));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void tenBitIntegerTexelsReadBackByChannel() {
        final ByteBuffer image = MemoryUtil.memAlloc(4), out = MemoryUtil.memAlloc(16);
        try {
            image.putInt(0, 1 | 2 << 10 | 3 << 20 | 1 << 30);
            assertTrue(PixelConvert.pack(format(GL33.GL_RGB10_A2UI), MemoryUtil.memAddress(image), 1, 1, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_INT, tight(), MemoryUtil.memAddress(out)));
            for (int c = 0; c < 4; c++) assertEquals(new int[] {1, 2, 3, 1}[c], out.getInt(c * 4), "channel " + c);
        } finally {
            MemoryUtil.memFree(image);
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void bgraTenBitUploadsSwapRedAndBlue() {
        final ByteBuffer in = MemoryUtil.memAlloc(4), image = MemoryUtil.memAlloc(4);
        try {
            in.putInt(0, 3 << 30 | 1 << 20 | 2 << 10 | 4);
            assertTrue(PixelConvert.unpack(format(GL11.GL_RGB10_A2), GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, MemoryUtil.memAddress(in), 1, 1, 1, tight(), MemoryUtil.memAddress(image)));
            assertEquals(3 << 30 | 4 << 20 | 2 << 10 | 1, image.getInt(0));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void sameSizedClientDataOfAnotherLayoutConvertsRatherThanReinterprets() {
        final ByteBuffer in = MemoryUtil.memAlloc(8), image = MemoryUtil.memAlloc(8);
        try {
            final long from = MemoryUtil.memAddress(in), to = MemoryUtil.memAddress(image);
            in.putInt(0, 0xFF000000).putInt(4, 0);
            assertTrue(PixelConvert.unpack(format(GL11.GL_RGB10_A2), GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8, from, 1, 1, 1, tight(), to), "packed bytes as 2_10_10_10");
            assertEquals(1023, image.getInt(0) & 1023, "red is the word's top byte");

            in.putShort(0, (short) 7).putShort(2, (short) 8).putShort(4, (short) 9).putShort(6, (short) 10);
            assertTrue(PixelConvert.unpack(format(GL11.GL_RGBA16), GL12.GL_BGRA, GL11.GL_UNSIGNED_SHORT, from, 1, 1, 1, tight(), to), "BGRA as RGBA16");
            assertEquals(9, image.getShort(0), "red came third");
            assertEquals(7, image.getShort(4), "blue came first");

            in.putShort(0, PixelConvert.toHalf(1f)).putShort(2, PixelConvert.toHalf(0f)).putShort(4, PixelConvert.toHalf(0f)).putShort(6, PixelConvert.toHalf(1f));
            assertTrue(PixelConvert.unpack(format(GL11.GL_RGBA16), GL11.GL_RGBA, GL30.GL_HALF_FLOAT, from, 1, 1, 1, tight(), to), "half floats as unsigned shorts");
            assertEquals((short) 0xFFFF, image.getShort(0), "one is the top of the range");

            in.putShort(0, (short) 7).putShort(2, (short) 8).putShort(4, (short) 9).putShort(6, (short) 10);
            assertTrue(PixelConvert.unpack(format(GL30.GL_RGBA16UI), GL30.GL_BGRA_INTEGER, GL11.GL_UNSIGNED_SHORT, from, 1, 1, 1, tight(), to), "BGRA as RGBA16UI");
            assertEquals(9, image.getShort(0));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void bgraIntegerPixelsKeepTheirChannelsBothWays() {
        final ByteBuffer in = MemoryUtil.memAlloc(16), image = MemoryUtil.memAlloc(16), out = MemoryUtil.memAlloc(16);
        try {
            for (int c = 0; c < 4; c++) in.putInt(c * 4, c + 1);
            final Formats.Format rgba32 = format(GL30.GL_RGBA32UI);
            assertTrue(PixelConvert.unpack(rgba32, GL30.GL_BGRA_INTEGER, GL11.GL_UNSIGNED_INT, MemoryUtil.memAddress(in), 1, 1, 1, tight(), MemoryUtil.memAddress(image)));
            for (int c = 0; c < 4; c++) assertEquals(new int[] {3, 2, 1, 4}[c], image.getInt(c * 4), "uploaded channel " + c);
            assertTrue(PixelConvert.pack(rgba32, MemoryUtil.memAddress(image), 1, 1, GL30.GL_BGRA_INTEGER, GL11.GL_UNSIGNED_INT, tight(), MemoryUtil.memAddress(out)));
            for (int c = 0; c < 4; c++) assertEquals(c + 1, out.getInt(c * 4), "read back channel " + c);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void readingOneChannelAsFloatsTakesThatChannel() {
        final ByteBuffer image = MemoryUtil.memAlloc(4), out = MemoryUtil.memAlloc(4);
        try {
            image.put(0, new byte[] {0, (byte) 255, 0, 0});
            assertTrue(PixelConvert.pack(Formats.RGBA8, MemoryUtil.memAddress(image), 1, 1, GL11.GL_GREEN, GL11.GL_FLOAT, tight(), MemoryUtil.memAddress(out)));
            assertEquals(1f, out.getFloat(0), 0.001f);
        } finally {
            MemoryUtil.memFree(image);
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void integersIntoAFloatImageAreNormalized() {
        final ByteBuffer in = MemoryUtil.memAlloc(16), image = MemoryUtil.memAlloc(16);
        try {
            for (int c = 0; c < 4; c++) in.putInt(c * 4, 0xFFFFFFFF);
            assertTrue(PixelConvert.unpack(format(GL30.GL_RGBA32F), GL11.GL_RGBA, GL11.GL_UNSIGNED_INT, MemoryUtil.memAddress(in), 1, 1, 1, tight(), MemoryUtil.memAddress(image)));
            for (int c = 0; c < 4; c++) assertEquals(1f, image.getFloat(c * 4), 0.001f, "channel " + c);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }

    @Test
    void bytesOfTheOtherSignednessAreConverted() {
        final ByteBuffer in = MemoryUtil.memAlloc(4), image = MemoryUtil.memAlloc(4);
        try {
            final long from = MemoryUtil.memAddress(in), to = MemoryUtil.memAddress(image);
            in.put(0, (byte) -100).put(1, (byte) 127).put(2, (byte) 0).put(3, (byte) -1);
            assertTrue(PixelConvert.unpack(format(GL11.GL_RGBA8), GL11.GL_RGBA, GL11.GL_BYTE, from, 1, 1, 1, tight(), to), "signed into unsigned");
            assertEquals(0, image.get(0) & 0xFF, "a negative value clamps to 0");
            assertEquals(255, image.get(1) & 0xFF);
            in.put(0, (byte) 255).put(1, (byte) 0);
            assertTrue(PixelConvert.unpack(format(GL31.GL_RGBA8_SNORM), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, from, 1, 1, 1, tight(), to), "unsigned into signed");
            assertEquals(127, image.get(0));
            assertEquals(0, image.get(1));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(image);
        }
    }
}
