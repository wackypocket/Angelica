package com.gtnewhorizons.angelica.vulkan.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-vulkan")
@ExtendWith(GlsmVulkanFrameExtension.class)
class GlsmVulkanCopyTexSubImageFromFbo0Test {

    private static final int FB = 32;
    private static final int TEX = 16;
    private static final int SENTINEL = 0xFF102030;

    private static int pattern(int x, int y) {
        return 0xFF000000 | (x * 8) << 16 | (y * 8) << 8 | 0x40;
    }

    private static void fillDefaultFramebuffer() {
        GlsmVulkanHeadlessRig.installFinalTarget(FB, FB);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLSMRasterizer.glViewport(0, 0, FB, FB);
        final int tex = GlsmVulkanHeadlessRig.newNearestTexture();
        final ByteBuffer texels = MemoryUtil.memAlloc(FB * FB * 4);
        try {
            for (int y = 0; y < FB; y++) for (int x = 0; x < FB; x++) GlsmVulkanHeadlessRig.putArgb(texels, y * FB + x, pattern(x, y));
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, FB, FB, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        } finally {
            MemoryUtil.memFree(texels);
        }
        GlsmVulkanHeadlessRig.texturedQuad(tex, 1.0f);
        GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
        GLSMTextures.glDeleteTextures(tex);
    }

    private static int sentinelDestination() {
        final int dst = GlsmVulkanHeadlessRig.newNearestTexture();
        final ByteBuffer texels = MemoryUtil.memAlloc(TEX * TEX * 4);
        try {
            for (int i = 0; i < TEX * TEX; i++) GlsmVulkanHeadlessRig.putArgb(texels, i, SENTINEL);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TEX, TEX, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        } finally {
            MemoryUtil.memFree(texels);
        }
        return dst;
    }

    private static void check(int dst, int dx, int dy, int sx, int sy, int w, int h) {
        final int[] got = GlsmVulkanHeadlessRig.readTextureLevel(dst, 0);
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                final boolean inside = x >= dx && x < dx + w && y >= dy && y < dy + h;
                final int expected = inside ? pattern(sx + x - dx, sy + y - dy) : SENTINEL;
                final int px = x, py = y;
                assertEquals(Integer.toHexString(expected), Integer.toHexString(got[y * TEX + x]), () -> "texel (" + px + "," + py + ")");
            }
        }
    }

    @Test
    void fullCopyKeepsGlRowOrder() {
        try {
            fillDefaultFramebuffer();
            final int dst = sentinelDestination();
            GLSMTextures.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, TEX, TEX);
            check(dst, 0, 0, 0, 0, TEX, TEX);
            GLSMTextures.glDeleteTextures(dst);
        } finally {
            GlsmVulkanHeadlessRig.dropFinalTarget();
            GlsmVulkanHeadlessRig.beginFrame();
        }
    }

    @Test
    void subRectCopyLandsAtItsOffset() {
        try {
            fillDefaultFramebuffer();
            final int dst = sentinelDestination();
            GLSMTextures.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 5, 9, 3, 2, 7, 4);
            check(dst, 5, 9, 3, 2, 7, 4);
            GLSMTextures.glDeleteTextures(dst);
        } finally {
            GlsmVulkanHeadlessRig.dropFinalTarget();
            GlsmVulkanHeadlessRig.beginFrame();
        }
    }
}
