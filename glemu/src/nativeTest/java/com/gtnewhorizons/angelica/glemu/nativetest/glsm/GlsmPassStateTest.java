package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmFrameExtension;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig;
import com.gtnewhorizons.angelica.glemu.pipeline.PipelinesBase;
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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.BLACK;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.FULLSCREEN_VERTEX;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmPassStateTest {

    private static final String POSITION_VERTEX = """
        #version 330 core
        layout(location = 0) in vec2 p;
        void main() { gl_Position = vec4(p, 0.0, 1.0); }
        """;

    private static long pipelinesCreated() {
        return Reflect.<PipelinesBase<?>>get(Reflect.get(NativeGlsmRig.gl(), "renderer"), "pipelines").pipelineCount();
    }

    @Test
    void unchangedScissorIsReappliedInTheNextPass() {
        final int solid = GlTestSupport.program(FULLSCREEN_VERTEX, GlsmHeadlessRig.COLOR_FRAGMENT);
        final int other = GlsmHeadlessRig.colorTarget();
        final int otherFbo = GlsmHeadlessRig.fboWithColor(other, 0);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GlsmHeadlessRig.bindTarget();
            GLSMEnable.glEnable(GL11.GL_SCISSOR_TEST);
            GLSMRasterizer.glScissor(0, 0, SIZE / 2, SIZE);
            GlsmHeadlessRig.fill(solid, 1.0f, 0.0f, 0.0f);

            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, otherFbo);
            GlsmHeadlessRig.fill(solid, 0.0f, 0.0f, 1.0f);

            GlsmHeadlessRig.bindTarget();
            GlsmHeadlessRig.fill(solid, 0.0f, 1.0f, 0.0f);
            GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
            GLSMPrograms.glUseProgram(0);

            final int[] px = GlsmHeadlessRig.readTarget();
            GlsmHeadlessRig.assertPixel(px, SIZE, SIZE / 4, SIZE / 2, GREEN, "inside the scissor");
            GlsmHeadlessRig.assertPixel(px, SIZE, SIZE * 3 / 4, SIZE / 2, BLACK, "outside the scissor");
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
