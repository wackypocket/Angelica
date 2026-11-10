package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-native")
@ExtendWith(GlsmOutOfFrameExtension.class)
class GlsmOutOfFrameReadbackTest {

    private static final int TEX_SIZE = 64;

    @Test
    void outOfFrameTexImageReadback() {
        final ByteBuffer pattern = pattern(TEX_SIZE, TEX_SIZE, 1);
        final int tex = GlsmHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TEX_SIZE, TEX_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pattern);
        assertReadback(tex, TEX_SIZE, TEX_SIZE, pattern);
        GLSMTextures.glDeleteTextures(tex);
    }

    @ParameterizedTest(name = "in frame: {0}")
    @ValueSource(booleans = {true, false})
    void copyImageSubDataReadback(boolean inFrame) {
        if (inFrame) GlsmHeadlessRig.beginFrame();
        copyAndReadBack(inFrame ? 3 : 4);
    }

    private static void copyAndReadBack(int seed) {
        final ByteBuffer pattern = pattern(TEX_SIZE, TEX_SIZE, seed);
        final int src = GlsmHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TEX_SIZE, TEX_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pattern);
        final int dst = GlsmHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TEX_SIZE, TEX_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GLSMTextures.glCopyImageSubData(src, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, dst, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, TEX_SIZE, TEX_SIZE, 1);
        assertReadback(dst, TEX_SIZE, TEX_SIZE, pattern);
        GLSMTextures.glDeleteTextures(src);
        GLSMTextures.glDeleteTextures(dst);
    }

    private static ByteBuffer pattern(int w, int h, int seed) {
        final ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            buf.put((byte) (i * 7 + seed));
            buf.put((byte) (i * 13 + 0x40));
            buf.put((byte) (i * 29 + seed * 3));
            buf.put((byte) (0x80 | i));
        }
        return buf.flip();
    }

    private static void assertReadback(int tex, int w, int h, ByteBuffer expected) {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer got = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < got.capacity(); i++) got.put(i, (byte) 0xCD);
        GLSMTextures.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, got);

        for (int i = 0; i < w * h * 4; i++) {
            assertEquals(expected.get(i) & 0xFF, got.get(i) & 0xFF, "byte " + i);
        }
    }

}
