package com.gtnewhorizons.angelica.metal.glsm;

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
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.BLACK;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.FULLSCREEN_VERTEX;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalPassReuseTest {

    private static final String COLOR_FS = """
        #version 330 core
        uniform vec4 c;
        out vec4 fragColor;
        void main() { fragColor = c; }
        """;

    private static long dynamicEmitted() {
        return Reflect.<Long>get(Reflect.get(Reflect.get(GlsmMetalHeadlessRig.metal(), "renderer"), "dynamicCache"), "emitted");
    }

    private static void fill(int program, float r, float g, float b) {
        GLSMPrograms.glUseProgram(program);
        GLSMPrograms.glUniform4f(GLSMPrograms.glGetUniformLocation(program, "c"), r, g, b, 1.0f);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    @Test
    void unchangedScissorIsReappliedInTheNextPass() {
        final int solid = GlTestSupport.program(FULLSCREEN_VERTEX, COLOR_FS);
        final int other = GlsmMetalHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        final int otherFbo = GlsmMetalHeadlessRig.fboWithColor(other, 0);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GlsmMetalHeadlessRig.bindTarget();
            GLSMEnable.glEnable(GL11.GL_SCISSOR_TEST);
            GLSMRasterizer.glScissor(0, 0, SIZE / 2, SIZE);
            fill(solid, 1.0f, 0.0f, 0.0f);

            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, otherFbo);
            fill(solid, 0.0f, 0.0f, 1.0f);

            GlsmMetalHeadlessRig.bindTarget();
            final long emitted = dynamicEmitted();
            fill(solid, 0.0f, 1.0f, 0.0f);
            assertTrue(dynamicEmitted() > emitted, "a new render encoder must have its dynamic state set again");
            GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
            GLSMPrograms.glUseProgram(0);

            final int[] px = GlsmMetalHeadlessRig.readTarget();
            GlsmMetalHeadlessRig.assertPixel(px, SIZE, SIZE / 4, SIZE / 2, GREEN, "inside the scissor");
            GlsmMetalHeadlessRig.assertPixel(px, SIZE, SIZE * 3 / 4, SIZE / 2, BLACK, "outside the scissor");
        } finally {
            GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMFramebuffer.glDeleteFramebuffers(otherFbo);
            GLSMTextures.glDeleteTextures(other);
            GLSMPrograms.glDeleteProgram(solid);
        }
    }
}
