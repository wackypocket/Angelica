package com.gtnewhorizons.angelica.vulkan.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.BLACK;
import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.FULLSCREEN_VERTEX;
import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.RED;
import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("glsm-vulkan")
@ExtendWith(GlsmVulkanFrameExtension.class)
class GlsmVulkanPassReuseTest {

    private static final String COLOR_FS = """
        #version 330 core
        uniform vec4 c;
        out vec4 fragColor;
        void main() { fragColor = c; }
        """;

    private static final String SAMPLE_FS = """
        #version 330 core
        uniform sampler2D s;
        out vec4 fragColor;
        void main() { fragColor = texture(s, vec2(0.5)); }
        """;

    private static final String POSITION_VERTEX = """
        #version 330 core
        layout(location = 0) in vec2 p;
        void main() { gl_Position = vec4(p, 0.0, 1.0); }
        """;

    private static Object renderer() {
        return Reflect.get(GlsmVulkanHeadlessRig.vulkan(), "renderer");
    }

    private static long pipelinesCreated() {
        return Reflect.<Long>get(Reflect.get(renderer(), "pipelines"), "created");
    }

    private static long dynamicEmitted() {
        return Reflect.<Long>get(Reflect.get(renderer(), "dynamicCache"), "emitted");
    }

    private static void fill(int program, float r, float g, float b) {
        GLSMPrograms.glUseProgram(program);
        GLSMPrograms.glUniform4f(GLSMPrograms.glGetUniformLocation(program, "c"), r, g, b, 1.0f);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static int colorTarget() {
        final int tex = GlsmVulkanHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    @Test
    void samplingAnEarlierTargetKeepsTheCurrentPassWhole() {
        assumeTrue(Reflect.<Boolean>get(renderer(), "deferPasses"), "only deferred passes can move the layout change ahead of the pass");
        final int solid = GlTestSupport.program(FULLSCREEN_VERTEX, COLOR_FS);
        final int sample = GlTestSupport.program(FULLSCREEN_VERTEX, SAMPLE_FS);
        final int source = colorTarget();
        final int sourceFbo = GlsmVulkanHeadlessRig.fboWithColor(source, 0);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
            fill(solid, 1.0f, 0.0f, 0.0f);

            GlsmVulkanHeadlessRig.bindTarget();
            fill(solid, 0.0f, 1.0f, 0.0f);
            final long passes = GlsmVulkanHeadlessRig.renderPasses();

            GLSMPrograms.glUseProgram(sample);
            GLSMPrograms.glUniform1i(GLSMPrograms.glGetUniformLocation(sample, "s"), 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, source);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            assertEquals(passes, GlsmVulkanHeadlessRig.renderPasses(), "sampling a texture rendered by an earlier pass must not split the current one");
            GLSMPrograms.glUseProgram(0);

            final int center = GlsmVulkanHeadlessRig.readTarget(SIZE / 2, SIZE / 2, 1, 1)[0];
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

    @Test
    void unchangedScissorIsReappliedInTheNextPass() {
        final int solid = GlTestSupport.program(FULLSCREEN_VERTEX, COLOR_FS);
        final int other = colorTarget();
        final int otherFbo = GlsmVulkanHeadlessRig.fboWithColor(other, 0);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GlsmVulkanHeadlessRig.bindTarget();
            GLSMEnable.glEnable(GL11.GL_SCISSOR_TEST);
            GLSMRasterizer.glScissor(0, 0, SIZE / 2, SIZE);
            fill(solid, 1.0f, 0.0f, 0.0f);

            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, otherFbo);
            fill(solid, 0.0f, 0.0f, 1.0f);

            GlsmVulkanHeadlessRig.bindTarget();
            final long emitted = dynamicEmitted();
            fill(solid, 0.0f, 1.0f, 0.0f);
            if (Reflect.<Boolean>get(renderer(), "deferPasses")) {
                assertTrue(dynamicEmitted() > emitted, "a pass recorded into a fresh secondary must set its dynamic state again");
            } else {
                assertEquals(emitted, dynamicEmitted(), "dynamic state carries over between passes in one command buffer");
            }
            GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
            GLSMPrograms.glUseProgram(0);

            final int[] px = GlsmVulkanHeadlessRig.readTarget();
            GlsmVulkanHeadlessRig.assertPixel(px, SIZE, SIZE / 4, SIZE / 2, GREEN, "inside the scissor");
            GlsmVulkanHeadlessRig.assertPixel(px, SIZE, SIZE * 3 / 4, SIZE / 2, BLACK, "outside the scissor");
        } finally {
            GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMFramebuffer.glDeleteFramebuffers(otherFbo);
            GLSMTextures.glDeleteTextures(other);
            GLSMPrograms.glDeleteProgram(solid);
        }
    }

    @Test
    void vaosWithTheSameLayoutShareAPipeline() {
        final int program = GlTestSupport.program(POSITION_VERTEX, GlTestSupport.TRIVIAL_FS);
        final int[] vaos = new int[3];
        final int[] vbos = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                vaos[i] = GLSMVertexInput.glGenVertexArrays();
                vbos[i] = GLSMBuffers.glGenBuffers();
                GLSMVertexInput.glBindVertexArray(vaos[i]);
                GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbos[i]);
                if (i < 2) {
                    GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1, -1, 3, -1, -1, 3}, GL15.GL_STATIC_DRAW);
                    GLSMVertexInput.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
                } else {
                    GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, new short[] {-32767, -32767, 32767, -32767, -32767, 32767}, GL15.GL_STATIC_DRAW);
                    GLSMVertexInput.glVertexAttribPointer(0, 2, GL11.GL_SHORT, true, 0, 0L);
                }
                GLSMVertexInput.glEnableVertexAttribArray(0);
            }
            GLSMPrograms.glUseProgram(program);
            GLSMVertexInput.glBindVertexArray(vaos[0]);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            final long warm = pipelinesCreated();

            GLSMVertexInput.glBindVertexArray(vaos[1]);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            assertEquals(warm, pipelinesCreated(), "a second VAO with the same layout reuses the pipeline");

            GLSMVertexInput.glBindVertexArray(vaos[2]);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            assertEquals(warm + 1, pipelinesCreated(), "a different vertex format needs its own pipeline");
            GLSMPrograms.glUseProgram(0);
        } finally {
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
            for (int i = 0; i < 3; i++) {
                GLSMVertexInput.glDeleteVertexArrays(vaos[i]);
                GLSMBuffers.glDeleteBuffers(vbos[i]);
            }
            GLSMPrograms.glDeleteProgram(program);
        }
    }
}
