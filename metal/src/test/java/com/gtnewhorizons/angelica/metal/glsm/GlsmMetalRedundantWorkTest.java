package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.RED;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalRedundantWorkTest {

    private static final String POSITION_VERTEX = """
        #version 330 core
        layout(location = 0) in vec2 p;
        void main() { gl_Position = vec4(p, 0.0, 1.0); }
        """;

    private static long renames() {
        return Reflect.<Long>get(Reflect.get(GlsmMetalHeadlessRig.metal(), "buffers"), "renames");
    }

    private static long objectCreations() {
        return Reflect.<Long>get(Reflect.get(GlsmMetalHeadlessRig.metal(), "renderer"), "objectCreations");
    }

    @Test
    void unsynchronizedRangeMapOfABufferInUseWritesInPlace() {
        final int bytes = 4096;
        final int program = GlTestSupport.program(POSITION_VERTEX, GlTestSupport.TRIVIAL_FS);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        final int vbo = GLSMBuffers.glGenBuffers();
        final ByteBuffer initial = MemoryUtil.memCalloc(bytes);
        try {
            initial.putFloat(0, -1.0f).putFloat(4, -1.0f).putFloat(8, 1.0f).putFloat(12, -1.0f).putFloat(16, -1.0f).putFloat(20, 1.0f);
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, initial, GL15.GL_DYNAMIC_DRAW);
            GLSMVertexInput.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
            GLSMVertexInput.glEnableVertexAttribArray(0);
            GLSMPrograms.glUseProgram(program);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMPrograms.glUseProgram(0);

            final long before = renames();
            final ByteBuffer mapped = GLSMBuffers.glMapBufferRange(GL15.GL_ARRAY_BUFFER, bytes / 2, 256,
                GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
            assertNotNull(mapped, "an unsynchronized write map of a host-visible buffer must succeed");
            for (int i = 0; i < 256; i++) mapped.put(i, (byte) (i + 1));
            GLSMBuffers.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
            assertEquals(before, renames(), "an unsynchronized map must write in place rather than copy the buffer");

            final ByteBuffer back = MemoryUtil.memAlloc(bytes);
            try {
                GLSMBuffers.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, back);
                assertEquals(1.0f, back.getFloat(8), "bytes outside the mapped range keep their contents");
                for (int i = 0; i < 256; i++) assertEquals((byte) (i + 1), back.get(bytes / 2 + i), "mapped byte " + i);
            } finally {
                MemoryUtil.memFree(back);
            }
        } finally {
            MemoryUtil.memFree(initial);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMBuffers.glDeleteBuffers(vbo);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void reapplyingCurrentStateLeavesTheGenerationsAlone() {
        final MetalGL gl = GlsmMetalHeadlessRig.metal();
        final GlState st = gl.st();
        gl.blendColor(0.25f, 0.5f, 0.75f, 1.0f);
        gl.blendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        gl.blendFuncSeparatei(1, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        gl.colorMaski(1, true, true, true, true);
        gl.polygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        gl.polygonOffset(1.0f, 2.0f);
        gl.stencilFuncSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_ALWAYS, 3, 0xFF);
        gl.stencilOpSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        gl.stencilMaskSeparate(GL11.GL_FRONT_AND_BACK, 0xFF);
        gl.depthRange(0.0, 1.0);
        gl.lineWidth(1.0f);
        gl.logicOp(GL11.GL_COPY);
        gl.provokingVertex(GL32.GL_LAST_VERTEX_CONVENTION);
        final int pipelineGen = st.pipelineGen, dynamicGen = st.dynamicGen;

        gl.blendColor(0.25f, 0.5f, 0.75f, 1.0f);
        gl.blendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        gl.blendFuncSeparatei(1, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        gl.colorMaski(1, true, true, true, true);
        gl.polygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        gl.polygonOffset(1.0f, 2.0f);
        gl.stencilFuncSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_ALWAYS, 3, 0xFF);
        gl.stencilOpSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        gl.stencilMaskSeparate(GL11.GL_FRONT_AND_BACK, 0xFF);
        gl.depthRange(0.0, 1.0);
        gl.lineWidth(1.0f);
        gl.logicOp(GL11.GL_COPY);
        gl.provokingVertex(GL32.GL_LAST_VERTEX_CONVENTION);
        assertEquals(pipelineGen, st.pipelineGen, "re-sending the current state must not invalidate the pipeline key");
        assertEquals(dynamicGen, st.dynamicGen, "re-sending the current state must not re-record dynamic state");

        gl.blendColor(0.0f, 0.0f, 0.0f, 0.0f);
        gl.polygonOffset(0.0f, 0.0f);
        gl.stencilFuncSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_ALWAYS, 0, 0xFF);
        gl.stencilOpSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        assertEquals(pipelineGen + 1, st.pipelineGen, "a real stencil op change still invalidates the pipeline key");
        assertEquals(dynamicGen + 3, st.dynamicGen, "real dynamic changes still re-record");
    }

    @Test
    void alternatingTexturesOnOneProgramReuseTheirViewsAndSamplers() {
        final int program = GlTestSupport.program(GlsmMetalHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform sampler2D s;
            out vec4 fragColor;
            void main() { fragColor = texture(s, vec2(0.5)); }
            """);
        final int red = GlsmMetalHeadlessRig.createSolidTexture(RED);
        final int green = GlsmMetalHeadlessRig.createSolidTexture(GREEN);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GLSMPrograms.glUniform1i(GLSMPrograms.glGetUniformLocation(program, "s"), 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, red);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, green);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            final long warm = objectCreations();
            for (int i = 0; i < 6; i++) {
                GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, i % 2 == 0 ? red : green);
                GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            assertEquals(warm, objectCreations(), "switching back to a texture already drawn with must reuse its view and sampler");
            GLSMPrograms.glUseProgram(0);

            final int center = GlsmMetalHeadlessRig.readTarget(SIZE / 2, SIZE / 2, 1, 1)[0];
            assertEquals(Integer.toHexString(GREEN), Integer.toHexString(center), "the last draw samples the last bound texture");
        } finally {
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(red);
            GLSMTextures.glDeleteTextures(green);
            GLSMPrograms.glDeleteProgram(program);
        }
    }
}
