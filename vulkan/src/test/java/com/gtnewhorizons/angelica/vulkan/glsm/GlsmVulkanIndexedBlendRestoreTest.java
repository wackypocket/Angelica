package com.gtnewhorizons.angelica.vulkan.glsm;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("glsm-vulkan")
@ExtendWith(GlsmVulkanFrameExtension.class)
class GlsmVulkanIndexedBlendRestoreTest {

    private static void vanillaBlendFunc() {
        GLSMBlend.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void assertBlendFactors(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, String when) {
        final GlState st = GlsmVulkanHeadlessRig.vulkan().st();
        assertEquals(srcRgb, st.blendSrcRgb[buffer], when + ": src rgb of buffer " + buffer);
        assertEquals(dstRgb, st.blendDstRgb[buffer], when + ": dst rgb of buffer " + buffer);
        assertEquals(srcAlpha, st.blendSrcA[buffer], when + ": src alpha of buffer " + buffer);
        assertEquals(dstAlpha, st.blendDstA[buffer], when + ": dst alpha of buffer " + buffer);
    }

    @Test
    void vanillaBlendFuncAfterIndexedOverrideReachesEveryDrawBuffer() {
        try {
            vanillaBlendFunc();
            RenderSystem.blendFuncSeparatei(1, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);

            assertBlendFactors(1, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, "override");
            assertBlendFactors(0, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, "override");

            vanillaBlendFunc();

            for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
                assertBlendFactors(i, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, "restore");
            }
            assertFalse(GLSMBlend.getBlendState().isFuncUnknown(), "restore forward must clear the unknown flag");
        } finally {
            GLSMBlend.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        }
    }
}
