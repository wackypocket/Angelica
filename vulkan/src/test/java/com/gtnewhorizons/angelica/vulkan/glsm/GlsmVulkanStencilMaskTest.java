package com.gtnewhorizons.angelica.vulkan.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-vulkan")
@ExtendWith(GlsmVulkanFrameExtension.class)
class GlsmVulkanStencilMaskTest {

    private static final int MASK = SIZE / 4;

    private static void writeMask() {
        GLSMDepthStencilAlpha.glClearStencil(0);
        GLSMDepthStencilAlpha.glStencilMask(0xFF);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        GLSMEnable.glEnable(GL11.GL_STENCIL_TEST);
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GLSMDepthStencilAlpha.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GLSMBlend.glColorMask(false, false, false, false);
        final float edge = -1.0f + 2.0f * MASK / SIZE;
        GLSMDraws.glBegin(GL11.GL_QUADS);
        GLSMDraws.glVertex3f(-1.0f, -1.0f, 0.0f);
        GLSMDraws.glVertex3f(edge, -1.0f, 0.0f);
        GLSMDraws.glVertex3f(edge, edge, 0.0f);
        GLSMDraws.glVertex3f(-1.0f, edge, 0.0f);
        GLSMDraws.glEnd();
        GLSMBlend.glColorMask(true, true, true, true);
        GLSMEnable.glDisable(GL11.GL_STENCIL_TEST);
    }

    private static void fillWhereMasked() {
        GLSMEnable.glEnable(GL11.GL_STENCIL_TEST);
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GLSMDepthStencilAlpha.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GlsmVulkanHeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GLSMEnable.glDisable(GL11.GL_STENCIL_TEST);
    }

    private static int[] redPixels() {
        final int[] pixels = GlsmVulkanHeadlessRig.readTarget();
        int count = 0, minX = SIZE, maxX = -1;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int argb = pixels[y * SIZE + x];
                if (((argb >> 16) & 0xFF) > 200 && ((argb >> 8) & 0xFF) < 64) {
                    count++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        return new int[] {count, minX, maxX};
    }

    @Test
    void fillIsClippedToTheStencilMask() {
        writeMask();
        fillWhereMasked();
        final int[] r = redPixels();
        assertEquals(MASK * MASK, r[0], "the fill must cover exactly the masked region");
        assertEquals(0, r[1], "the masked region starts at x=0");
        assertEquals(MASK - 1, r[2], "the masked region must not bleed past the mask");
    }

    @Test
    void maskSurvivesAPassBoundary() {
        writeMask();
        GlsmVulkanHeadlessRig.endFrame();
        GlsmVulkanHeadlessRig.beginFrame();
        GlsmVulkanHeadlessRig.bindTarget();
        fillWhereMasked();
        final int[] r = redPixels();
        assertEquals(MASK * MASK, r[0], "a stored stencil buffer must still clip the next pass");
        assertEquals(MASK - 1, r[2]);
    }

    @Test
    void clearingStencilWipesTheMask() {
        writeMask();
        GLSMDepthStencilAlpha.glClearStencil(0);
        GLSMFramebuffer.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        fillWhereMasked();
        assertEquals(0, redPixels()[0], "a cleared stencil buffer must clip everything");
    }
}
