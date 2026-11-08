package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmBlendConstantsTest {

    @AfterEach
    void restoreBlend() {
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GLSMBlend.glBlendColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @ParameterizedTest(name = "blending enabled earlier in the frame: {0}")
    @ValueSource(booleans = {false, true})
    void constantsSetWhileBlendingIsDisabledReachTheNextBlendedDraw(boolean previouslyEnabled) {
        GlsmHeadlessRig.bindTarget();
        GLSMBlend.glBlendFunc(GL14.GL_CONSTANT_COLOR, GL11.GL_ZERO);
        if (previouslyEnabled) {
            GLSMEnable.glEnable(GL11.GL_BLEND);
            GLSMBlend.glBlendColor(1.0f, 0.0f, 0.0f, 1.0f);
            GlsmHeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        }
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMBlend.glBlendColor(0.0f, 1.0f, 0.0f, 1.0f);
        GlsmHeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        final long passes = GlsmRigHooks.renderPasses();

        GLSMEnable.glEnable(GL11.GL_BLEND);
        GlsmHeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        assertEquals(passes, GlsmRigHooks.renderPasses(), "enabling blending must not break the render pass");
        final int[] pixels = GlsmHeadlessRig.readTarget();
        final int size = GlsmHeadlessRig.SIZE;
        assertEquals(GlsmHeadlessRig.GREEN, GlsmHeadlessRig.pixelAt(pixels, size, size / 2, size / 2));
    }
}
