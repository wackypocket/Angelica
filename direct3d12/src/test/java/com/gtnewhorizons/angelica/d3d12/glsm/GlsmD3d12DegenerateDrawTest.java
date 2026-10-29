package com.gtnewhorizons.angelica.d3d12.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.d3d12.glsm.GlsmD3d12HeadlessRig.RED;

@Tag("glsm-d3d12")
@ExtendWith(GlsmD3d12FrameExtension.class)
class GlsmD3d12DegenerateDrawTest {

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, -3})
    void degenerateCountDrawArraysLeavesTheFollowingDrawIntact(int count) {
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
        GlsmD3d12HeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GlsmD3d12HeadlessRig.assertUniform(GlsmD3d12HeadlessRig.readTarget(), RED, "after a drawArrays with count " + count);
    }
}
