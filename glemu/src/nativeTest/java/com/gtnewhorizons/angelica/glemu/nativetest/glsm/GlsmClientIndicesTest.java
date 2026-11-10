package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmClientIndicesTest {
    private static void drawsGreen(Runnable draw) {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        GlsmHeadlessRig.bindTarget();
        GlsmHeadlessRig.clearTargetToBlack();
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMPrograms.glUseProgram(program);
        draw.run();
        GLSMPrograms.glUseProgram(0);
        GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.GREEN, "the triangle");
        GLSMVertexInput.glBindVertexArray(0);
        GLSMVertexInput.glDeleteVertexArrays(vao);
        GLSMPrograms.glDeleteProgram(program);
    }

    @Test
    void intIndicesInClientMemoryDraw() {
        final IntBuffer indices = BufferUtils.createIntBuffer(3).put(0, new int[] {0, 1, 2});
        drawsGreen(() -> GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, indices));
    }

    @Test
    void shortIndicesInClientMemoryDraw() {
        final ShortBuffer indices = BufferUtils.createShortBuffer(3).put(0, new short[] {0, 1, 2});
        drawsGreen(() -> GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, indices));
    }

    @Test
    void byteIndicesInClientMemoryDraw() {
        final ByteBuffer indices = BufferUtils.createByteBuffer(3).put(0, new byte[] {0, 1, 2});
        drawsGreen(() -> GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, indices));
        drawsGreen(() -> GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, 3, GL11.GL_UNSIGNED_BYTE, indices));
    }
}
