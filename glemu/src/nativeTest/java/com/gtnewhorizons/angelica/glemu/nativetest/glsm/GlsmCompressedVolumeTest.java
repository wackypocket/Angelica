package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Of the compressed formats GL defines blocks for a volume only in BPTC, so a 3D texture takes BC6H and BC7 and refuses the rest. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmCompressedVolumeTest {
    private static final int[] BPTC = {
        GL42C.GL_COMPRESSED_RGBA_BPTC_UNORM, GL42C.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM,
        GL42C.GL_COMPRESSED_RGB_BPTC_SIGNED_FLOAT, GL42C.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT };

    private static void assertError(int expected, Runnable call) {
        GL11C.glGetError();
        call.run();
        assertEquals(expected, GL11C.glGetError());
    }

    /** Two 16-byte blocks, one per slice of a 4x4x2 volume; the leading bytes pick a defined mode in BC6H and BC7 alike. */
    private static ByteBuffer twoBlocks() {
        final ByteBuffer blocks = MemoryUtil.memAlloc(32);
        for (int i = 0; i < 32; i++) blocks.put(i, (byte) (i * 37 + 5));
        blocks.put(0, (byte) 0x40).put(16, (byte) 0x40);
        return blocks;
    }

    @Test
    void aBptcVolumeStoresItsBlocks() {
        for (int format : BPTC) {
            final int tex = GL11C.glGenTextures();
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
            final ByteBuffer in = twoBlocks();
            final ByteBuffer out = MemoryUtil.memAlloc(32);
            try {
                assertError(GL11.GL_NO_ERROR, () -> GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, 1, format, 4, 4, 2));
                assertError(GL11.GL_NO_ERROR, () -> GL13C.glCompressedTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, 4, 4, 2, format, in));
                assertError(GL11.GL_NO_ERROR, () -> GL13C.glGetCompressedTexImage(GL12C.GL_TEXTURE_3D, 0, out));
                assertEquals(in, out, "format 0x" + Integer.toHexString(format));
            } finally {
                MemoryUtil.memFree(in);
                MemoryUtil.memFree(out);
                GL11C.glDeleteTextures(tex);
            }
        }
    }

    @Test
    void aBptcVolumeCanBeSpecifiedWithoutStorage() {
        final int tex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        assertError(GL11.GL_NO_ERROR, () -> GL12C.nglTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL42C.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT, 4, 4, 2, 0, GL11.GL_RGB, GL11.GL_FLOAT, 0L));
        GL11C.glDeleteTextures(tex);
    }

    @Test
    void anotherCompressedFormatIsNoVolume() {
        final int tex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        final ByteBuffer block = MemoryUtil.memCalloc(8);
        try {
            assertError(GL11.GL_INVALID_OPERATION, () -> GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, 1, GL30C.GL_COMPRESSED_RED_RGTC1, 4, 4, 1));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL12C.nglTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_COMPRESSED_RED_RGTC1, 4, 4, 1, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL13C.glCompressedTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_COMPRESSED_RED_RGTC1, 4, 4, 1, 0, block));
        } finally {
            MemoryUtil.memFree(block);
            GL11C.glDeleteTextures(tex);
        }
    }

    @Test
    void aDepthFormatIsNoVolume() {
        final int tex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        assertError(GL11.GL_INVALID_OPERATION, () -> GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, 1, GL14C.GL_DEPTH_COMPONENT24, 4, 4, 1));
        GL11C.glDeleteTextures(tex);
    }
}
