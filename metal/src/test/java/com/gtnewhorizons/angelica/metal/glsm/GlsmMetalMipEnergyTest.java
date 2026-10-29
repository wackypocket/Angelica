package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalMipEnergyTest {

    private static final int W = 1920, H = 1080;

    @Test
    void aMovingSourceKeepsItsEnergyInEveryOddSizedLevel() {
        final int id = GLSMTextures.glGenTextures();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        final double expected = 100.0 * 16;
        for (int pos = 0; pos < 1000; pos += 37) {
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
