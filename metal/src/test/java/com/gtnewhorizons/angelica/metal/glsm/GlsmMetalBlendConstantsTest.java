package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
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

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalBlendConstantsTest {

    @AfterEach
    void restoreBlend() {
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GLSMBlend.glBlendColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @ParameterizedTest(name = "blending enabled earlier in the frame: {0}")
    @ValueSource(booleans = {false, true})
    void constantsSetWhileBlendingIsDisabledReachTheNextBlendedDraw(boolean previouslyEnabled) {
        GlsmMetalHeadlessRig.bindTarget();
        GLSMBlend.glBlendFunc(GL14.GL_CONSTANT_COLOR, GL11.GL_ZERO);
        if (previouslyEnabled) {
            GLSMEnable.glEnable(GL11.GL_BLEND);
            GLSMBlend.glBlendColor(1.0f, 0.0f, 0.0f, 1.0f);
            GlsmMetalHeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        }
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMBlend.glBlendColor(0.0f, 1.0f, 0.0f, 1.0f);
        GlsmMetalHeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        final long passes = GlsmMetalHeadlessRig.renderPasses();

        GLSMEnable.glEnable(GL11.GL_BLEND);
        GlsmMetalHeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        assertEquals(passes, GlsmMetalHeadlessRig.renderPasses(), "enabling blending must not break the render pass");
        final int[] pixels = GlsmMetalHeadlessRig.readTarget();
        final int size = GlsmMetalHeadlessRig.SIZE;
        assertEquals(GlsmMetalHeadlessRig.GREEN, GlsmMetalHeadlessRig.pixelAt(pixels, size, size / 2, size / 2));
    }
}
