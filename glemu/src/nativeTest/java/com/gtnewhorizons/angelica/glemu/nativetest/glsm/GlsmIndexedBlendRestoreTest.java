package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmIndexedBlendRestoreTest {

    private static void vanillaBlendFunc() {
        GLSMBlend.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Test
    void vanillaBlendFuncAfterIndexedOverrideReachesEveryDrawBuffer() {
        try {
            vanillaBlendFunc();
            RenderSystem.blendFuncSeparatei(1, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);

            GlsmRigHooks.assertBlendFactors(1, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, "override");
            GlsmRigHooks.assertBlendFactors(0, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, "override");

            vanillaBlendFunc();

            for (int i = 0; i < GlsmRigHooks.maxDrawBuffers(); i++) {
                GlsmRigHooks.assertBlendFactors(i, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, "restore");
            }
            assertFalse(GLSMBlend.getBlendState().isFuncUnknown(), "restore forward must clear the unknown flag");
        } finally {
            GLSMBlend.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        }
    }
}
