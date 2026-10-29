package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.RED;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalDegenerateDrawTest {

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, -3})
    void degenerateCountDrawArraysLeavesTheFollowingDrawIntact(int count) {
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
        GlsmMetalHeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GlsmMetalHeadlessRig.assertUniform(GlsmMetalHeadlessRig.readTarget(), RED, "after a drawArrays with count " + count);
    }
}
