package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalSamplerBindingTest {

    private static final int UNIT = 3;

    @Test
    void glsmDeleteClearsBothCaches() {
        final GlState st = GlsmMetalHeadlessRig.metal().st();
        final int sampler = GLSMTextures.glGenSamplers();
        GLSMTextures.glBindSampler(UNIT, sampler);
        assertEquals(sampler, st.samplers[UNIT], "Metal unit binding");
        assertEquals(sampler, GLSMTextures.getSamplerBinding(UNIT), "GLSM unit binding");

        GLSMTextures.glDeleteSamplers(sampler);

        assertEquals(0, st.samplers[UNIT], "Metal must drop the deleted sampler");
        assertEquals(0, GLSMTextures.getSamplerBinding(UNIT), "GLSM must drop the deleted sampler");
    }

    @Test
    void renderSystemRebindAfterGlsmUnbindReachesMetal() {
        assertTrue(RenderSystem.supportsSamplerObjects(), "RenderSystem sampler calls would no-op and prove nothing");
        final GlState st = GlsmMetalHeadlessRig.metal().st();
        final int sampler = RenderSystem.genSampler();
        try {
            RenderSystem.bindSamplerToUnit(UNIT, sampler);
            GLSMTextures.glBindSampler(UNIT, 0);
            assertEquals(0, st.samplers[UNIT], "setup: GLSM unbind must reach Metal");

            RenderSystem.bindSamplerToUnit(UNIT, sampler);

            assertEquals(sampler, st.samplers[UNIT], "RenderSystem skipped a bind Metal no longer holds");
        } finally {
            RenderSystem.destroySampler(sampler);
        }
    }

    @Test
    void pointingASamplerAtAnotherUnitRebindsItBeforeTheNextDraw() {
        final int program = GlTestSupport.program(GlsmMetalHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform sampler2D s;
            out vec4 fragColor;
            void main() { fragColor = texture(s, vec2(0.5)); }
            """);
        final int red = GlsmMetalHeadlessRig.createSolidTexture(0xFFFF0000);
        final int green = GlsmMetalHeadlessRig.createSolidTexture(0xFF00FF00);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, red);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, green);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            final int location = GLSMPrograms.glGetUniformLocation(program, "s");
            GLSMPrograms.glUniform1i(location, 0);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMPrograms.glUniform1i(location, 1);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMPrograms.glUseProgram(0);

            final int center = GlsmMetalHeadlessRig.readTarget(GlsmMetalHeadlessRig.SIZE / 2, GlsmMetalHeadlessRig.SIZE / 2, 1, 1)[0];
            assertEquals(Integer.toHexString(GlsmMetalHeadlessRig.GREEN), Integer.toHexString(center), "the second draw must sample unit 1");
        } finally {
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(red);
            GLSMTextures.glDeleteTextures(green);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void unitCountMatchesTheMetalSamplerArray() {
        GLSMTextures.glBindSampler(0, 0);
        assertEquals(GlState.MAX_UNITS, GLSMTextures.getSamplerUnitCount());
    }
}
