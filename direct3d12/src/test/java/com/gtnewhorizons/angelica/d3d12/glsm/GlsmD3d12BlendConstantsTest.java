package com.gtnewhorizons.angelica.d3d12.glsm;

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

@Tag("glsm-d3d12")
@ExtendWith(GlsmD3d12FrameExtension.class)
class GlsmD3d12BlendConstantsTest {

    @AfterEach
    void restoreBlend() {
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GLSMBlend.glBlendColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @ParameterizedTest(name = "blending enabled earlier in the frame: {0}")
    @ValueSource(booleans = {false, true})
    void constantsSetWhileBlendingIsDisabledReachTheNextBlendedDraw(boolean previouslyEnabled) {
        GlsmD3d12HeadlessRig.bindTarget();
        GLSMBlend.glBlendFunc(GL14.GL_CONSTANT_COLOR, GL11.GL_ZERO);
        if (previouslyEnabled) {
            GLSMEnable.glEnable(GL11.GL_BLEND);
            GLSMBlend.glBlendColor(1.0f, 0.0f, 0.0f, 1.0f);
            GlsmD3d12HeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        }
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMBlend.glBlendColor(0.0f, 1.0f, 0.0f, 1.0f);
        GlsmD3d12HeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        final long passes = GlsmD3d12HeadlessRig.renderPasses();

        GLSMEnable.glEnable(GL11.GL_BLEND);
        GlsmD3d12HeadlessRig.solidQuad(1.0f, 1.0f, 1.0f);
        assertEquals(passes, GlsmD3d12HeadlessRig.renderPasses(), "enabling blending must not break the render pass");
        final int[] pixels = GlsmD3d12HeadlessRig.readTarget();
        final int size = GlsmD3d12HeadlessRig.SIZE;
        assertEquals(GlsmD3d12HeadlessRig.GREEN, GlsmD3d12HeadlessRig.pixelAt(pixels, size, size / 2, size / 2));
    }
}
