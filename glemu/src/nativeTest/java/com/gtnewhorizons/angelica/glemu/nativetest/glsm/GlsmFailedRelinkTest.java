package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL20C;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A program in use that fails to relink keeps drawing with what it last linked, until glUseProgram changes the program, while it
 * reports the failed link. GL 4.6, section 7.3.
 */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmFailedRelinkTest {
    private static final String GREEN_FS = "#version 330 core\nout vec4 c;\nvoid main() { c = vec4(0.0, 1.0, 0.0, 1.0); }\n";

    private static int failingShader() {
        final int bad = GL20C.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20C.glShaderSource(bad, "not GLSL");
        GL20C.glCompileShader(bad);
        return bad;
    }

    @Test
    void aFailedRelinkKeepsDrawingWithTheLastExecutable() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, GREEN_FS);
        final int bad = failingShader();
        try {
            GLSMPrograms.glUseProgram(program);
            GL20C.glAttachShader(program, bad);
            GL20C.glLinkProgram(program);
            assertEquals(GL11.GL_FALSE, GL20C.glGetProgrami(program, GL20.GL_LINK_STATUS));
            GL11C.glGetError();
            GlsmHeadlessRig.bindTarget();
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError(), "drawing with the program still in use");
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "what the program last linked");

            // Once another program is in use, the failed one cannot come back.
            GLSMPrograms.glUseProgram(0);
            GL11C.glGetError();
            GLSMPrograms.glUseProgram(program);
            assertEquals(GL11.GL_INVALID_OPERATION, GL11C.glGetError(), "using a program whose last link failed");

            GL20C.glDetachShader(program, bad);
            GL20C.glLinkProgram(program);
            assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20.GL_LINK_STATUS));
            GLSMPrograms.glUseProgram(program);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError());
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "the relinked program");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GL20C.glDeleteShader(bad);
            GLSMPrograms.glDeleteProgram(program);
        }
    }
}
