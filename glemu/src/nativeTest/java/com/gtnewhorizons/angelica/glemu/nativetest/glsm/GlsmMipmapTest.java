package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmMipmapTest {

    private static final int W = 1920, H = 1080;

    @Test
    void generatingMipmapsOnASingleLevelTextureBuildsTheWholeChain() {
        final int id = GLSMTextures.glGenTextures();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        final ByteBuffer texels = MemoryUtil.memAlloc(W * H * 4);
        try {
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    final boolean on = x >= 960 && x < 976 && y >= 540 && y < 556;
                    GlsmHeadlessRig.putArgb(texels, y * W + x, on ? 0xFFFFFFFF : 0xFF000000);
                }
            }
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        } finally {
            MemoryUtil.memFree(texels);
        }
        GLSMTextures.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        final long level0 = energy(id, 0);
        for (int level = 1; level <= 5; level++) {
            assertEquals(Math.max(1, W >> level), GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH), "width of level " + level);
            final long scaled = energy(id, level) << (2 * level);
            assertTrue(Math.abs(scaled - level0) <= level0 / 20, "level " + level + " energy " + scaled + " vs level 0 " + level0);
        }
    }

    private static long energy(int texture, int level) {
        long sum = 0;
        for (int p : GlsmHeadlessRig.readTextureLevel(texture, level)) sum += (p >> 8) & 0xFF;
        return sum;
    }

    @Test
    void aMovingSourceKeepsItsEnergyInEveryOddSizedLevel() {
        final int id = GLSMTextures.glGenTextures();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        final double expected = 100.0 * 16;
        for (int pos = 0; pos < 1000; pos += 250) {
            final FloatBuffer data = MemoryUtil.memCallocFloat(W * H * 4);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) data.put(((300 + pos / 2 + y) * W + 200 + pos + x) * 4, 100f);
            }
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, W, H, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data);
            MemoryUtil.memFree(data);
            GLSMTextures.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            for (int level = 2; level <= 7; level++) {
                final int lw = GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                final int lh = GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                final ByteBuffer raw = MemoryUtil.memAlloc(lw * lh * 16);
                try {
                    GLSMTextures.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_FLOAT, raw);
                    final FloatBuffer out = raw.order(ByteOrder.nativeOrder()).asFloatBuffer();
                    double sum = 0;
                    for (int i = 0; i < lw * lh; i++) sum += out.get(i * 4);
                    assertEquals(expected, sum * (1 << (2 * level)), expected * 0.01, "level " + level + " with the source at offset " + pos);
                } finally {
                    MemoryUtil.memFree(raw);
                }
            }
        }
    }
}
