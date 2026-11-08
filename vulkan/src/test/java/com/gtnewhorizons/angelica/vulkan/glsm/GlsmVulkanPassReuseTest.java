package com.gtnewhorizons.angelica.vulkan.glsm;

import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmFrameExtension;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.NativeGlsmRig;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.FULLSCREEN_VERTEX;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.RED;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("glsm-vulkan")
@ExtendWith(GlsmFrameExtension.class)
class GlsmVulkanPassReuseTest {

    private static final String SAMPLE_FS = """
        #version 330 core
        uniform sampler2D s;
        out vec4 fragColor;
        void main() { fragColor = texture(s, vec2(0.5)); }
        """;

    private static Object renderer() {
        return Reflect.get(NativeGlsmRig.gl(), "renderer");
    }

    @Test
    void samplingAnEarlierTargetKeepsTheCurrentPassWhole() {
        assumeTrue(Reflect.<Boolean>get(renderer(), "deferPasses"), "only deferred passes can move the layout change ahead of the pass");
        final int solid = GlTestSupport.program(FULLSCREEN_VERTEX, GlsmHeadlessRig.COLOR_FRAGMENT);
        final int sample = GlTestSupport.program(FULLSCREEN_VERTEX, SAMPLE_FS);
        final int source = GlsmHeadlessRig.colorTarget();
        final int sourceFbo = GlsmHeadlessRig.fboWithColor(source, 0);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
            GlsmHeadlessRig.fill(solid, 1.0f, 0.0f, 0.0f);

            GlsmHeadlessRig.bindTarget();
            GlsmHeadlessRig.fill(solid, 0.0f, 1.0f, 0.0f);
            final long passes = NativeGlsmRig.renderPasses();

            GLSMPrograms.glUseProgram(sample);
            GLSMPrograms.glUniform1i(GLSMPrograms.glGetUniformLocation(sample, "s"), 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, source);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            assertEquals(passes, NativeGlsmRig.renderPasses(), "sampling a texture rendered by an earlier pass must not split the current one");
            GLSMPrograms.glUseProgram(0);

            final int center = GlsmHeadlessRig.readTarget(SIZE / 2, SIZE / 2, 1, 1)[0];
            assertEquals(Integer.toHexString(RED), Integer.toHexString(center), "the draw sees what the earlier pass rendered");
        } finally {
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMFramebuffer.glDeleteFramebuffers(sourceFbo);
            GLSMTextures.glDeleteTextures(source);
            GLSMPrograms.glDeleteProgram(solid);
            GLSMPrograms.glDeleteProgram(sample);
        }
    }
}
