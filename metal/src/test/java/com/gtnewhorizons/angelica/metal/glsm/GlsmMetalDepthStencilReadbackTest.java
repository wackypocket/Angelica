package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalDepthStencilReadbackTest {

    private static final int RX = 5, RY = 7, RW = 20, RH = 11;

    private int fbo;
    private int color;
    private int depthStencil;

    @BeforeEach
    void createTarget() {
        color = GlsmMetalHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        depthStencil = GlsmMetalHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, SIZE, SIZE, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
        fbo = GLSMFramebuffer.glGenFramebuffers();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, depthStencil, 0);
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMDepthStencilAlpha.glStencilMask(0xFF);
    }

    @AfterEach
    void deleteTarget() {
        GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
        GLSMFramebuffer.glDeleteFramebuffers(fbo);
        GLSMTextures.glDeleteTextures(color);
        GLSMTextures.glDeleteTextures(depthStencil);
        GlsmMetalHeadlessRig.bindTarget();
    }

    private static boolean inRegion(int x, int y) {
        return x >= RX && x < RX + RW && y >= RY && y < RY + RH;
    }

    private static float[] readDepth() {
        GlsmMetalHeadlessRig.endFrame();
        final FloatBuffer out = MemoryUtil.memAllocFloat(SIZE * SIZE);
        try {
            GLSMFramebuffer.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, out);
            final float[] r = new float[SIZE * SIZE];
            out.get(r);
            return r;
        } finally {
            MemoryUtil.memFree(out);
            GlsmMetalHeadlessRig.beginFrame();
        }
    }

    private static int[] readStencil() {
        GlsmMetalHeadlessRig.endFrame();
        final ByteBuffer out = MemoryUtil.memAlloc(SIZE * SIZE);
        try {
            GLSMTextures.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GLSMFramebuffer.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, out);
            final int[] r = new int[SIZE * SIZE];
            for (int i = 0; i < r.length; i++) r[i] = out.get(i) & 0xFF;
            return r;
        } finally {
            GLSMTextures.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            MemoryUtil.memFree(out);
            GlsmMetalHeadlessRig.beginFrame();
        }
    }

    @Test
    void readsAPendingClearThenAScissoredRegion() {
        GLSMDepthStencilAlpha.glClearDepth(0.25);
        GLSMDepthStencilAlpha.glClearStencil(0xA5);
        GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

        final float[] d0 = readDepth();
        for (int i = 0; i < d0.length; i++) assertEquals(0.25f, d0[i], 1e-6f, "pending depth clear at texel " + i);

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GLSMEnable.glEnable(GL11.GL_SCISSOR_TEST);
        GLSMRasterizer.glScissor(RX, RY, RW, RH);
        GLSMDepthStencilAlpha.glClearDepth(0.75);
        GLSMDepthStencilAlpha.glClearStencil(0x3C);
        GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);

        final int[] s = readStencil();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) assertEquals(inRegion(x, y) ? 0x3C : 0xA5, s[y * SIZE + x], "stencil at x=" + x + " y=" + y);
        }
        final float[] d1 = readDepth();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) assertEquals(inRegion(x, y) ? 0.75f : 0.25f, d1[y * SIZE + x], 1e-6f, "depth at x=" + x + " y=" + y);
        }
        final int[] again = readStencil();
        assertEquals(0x3C, again[RY * SIZE + RX], "stencil must survive the readbacks");
        assertEquals(0xA5, again[0], "stencil must survive the readbacks");
    }
}
