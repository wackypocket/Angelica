package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.RED;

@Tag("glsm-dx12")
@ExtendWith(GlsmDx12FrameExtension.class)
class GlsmDx12DegenerateDrawTest {

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, -3})
    void degenerateCountDrawArraysLeavesTheFollowingDrawIntact(int count) {
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
        GlsmDx12HeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTarget(), RED, "after a drawArrays with count " + count);
    }
}
