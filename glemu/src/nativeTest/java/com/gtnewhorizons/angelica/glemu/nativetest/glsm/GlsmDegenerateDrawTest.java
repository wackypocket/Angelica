package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.RED;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDegenerateDrawTest {

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, -3})
    void degenerateCountDrawArraysLeavesTheFollowingDrawIntact(int count) {
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
        GlsmHeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), RED, "after a drawArrays with count " + count);
    }
}
