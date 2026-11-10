package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Device limits and formats where a backend's first answer is easy to get subtly wrong. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDeviceEdgeCaseTest {

    private static final int RAW_HALF = 0x80, ENCODED_HALF = 0xBC;

    @Test
    void anUploadOutsideTheLevelLeavesTheImageAlone() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            final ByteBuffer red = BufferUtils.createByteBuffer(16 * 4);
            for (int i = 0; i < 16; i++) GlsmHeadlessRig.putArgb(red, i, GlsmHeadlessRig.RED);
            GL11C.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 3, 3, 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, red);
            GL11C.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, -1, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, red);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTextureLevel(texture, 0), GlsmHeadlessRig.GREEN, "the level as it was");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void anImageOverTheLimitsLeavesTheTextureAlone() {
        final int over = GL11C.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) * 2;
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            GL11C.glGetError();
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, over, over, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            assertEquals(GL11.GL_INVALID_VALUE, GL11C.glGetError(), "GL's answer to the call");
            assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError(), "reading the error clears it");
            assertEquals(4, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH), "the size as it was");
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTextureLevel(texture, 0), GlsmHeadlessRig.GREEN, "the image as it was");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aTextureNeverGivenAnImageHasNoSizeAfterARefusal() {
        final int over = GL11C.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) * 2;
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, over, over, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            assertEquals(0, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        } finally {
            GL11C.glGetError();
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    /** Any of these reaching a device can abort the process on first use, so each is used. */
    @Test
    void imagesOverTheLimitsAreNeverCreated() {
        final int over = GL11C.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) * 2;
        final int layers = GL11C.glGetInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS) * 2;
        final int stored = GLSMTextures.glGenTextures();
        final int array = GLSMTextures.glGenTextures();
        final int renderbuffer = GL30.glGenRenderbuffers();
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, stored);
            GL42C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, over, over);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GLSMTextures.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
            GL12C.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, 4, 4, layers, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, over, over);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, renderbuffer);
            GL11C.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11C.glFinish();
        } finally {
            GL11C.glGetError();
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            GL30.glDeleteRenderbuffers(renderbuffer);
            GLSMTextures.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLSMTextures.glDeleteTextures(stored);
            GLSMTextures.glDeleteTextures(array);
        }
    }

    @Test
    void aDispatchOverTheLimitIsIgnored() {
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, """
            #version 430 core
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Out { uint written; };
            void main() { written = 1u; }
            """);
        final int program = GLSMPrograms.glCreateProgram();
        GLSMPrograms.glAttachShader(program, shader);
        GLSMPrograms.glLinkProgram(program);
        final int buffer = GL15C.glGenBuffers();
        try {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, BufferUtils.createByteBuffer(4), GL15C.GL_DYNAMIC_DRAW);
            GL30.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, buffer);
            GLSMPrograms.glUseProgram(program);
            final ByteBuffer out = BufferUtils.createByteBuffer(4);
            GL11C.glGetError();
            GL43C.glDispatchCompute(65536, 1, 1);
            assertEquals(GL11.GL_INVALID_VALUE, GL11C.glGetError(), "GL's answer to the call");
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, out);
            assertEquals(0, out.getInt(0), "nothing ran");
            GL43C.glDispatchCompute(1, 1, 1);
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, out);
            assertEquals(1, out.getInt(0), "a dispatch within the limit still runs");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            GLSMPrograms.glDeleteShader(shader);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    @Test
    void aDivisorAboveOneStepsEveryFewInstances() {
        final int program = GlTestSupport.program("""
            #version 330 core
            layout(location = 0) in vec2 a_Position;
            layout(location = 3) in vec4 a_Tint;
            out vec4 v_Tint;
            void main() {
                gl_Position = vec4(-1.0 + 0.5 * (float(gl_InstanceID) + a_Position.x), a_Position.y, 0.0, 1.0);
                v_Tint = a_Tint;
            }
            """, """
            #version 330 core
            in vec4 v_Tint;
            out vec4 fragColor;
            void main() { fragColor = v_Tint; }
            """);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        final int quad = GLSMBuffers.glGenBuffers();
        final int tints = GLSMBuffers.glGenBuffers();
        try {
            GlsmHeadlessRig.clearTargetToBlack();
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, quad);
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, floats(0, -1, 1, -1, 1, 1, 0, -1, 1, 1, 0, 1), GL15.GL_STATIC_DRAW);
            GLSMVertexInput.glEnableVertexAttribArray(0);
            GLSMVertexInput.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, tints);
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, floats(1, 0, 0, 1, 0, 0, 1, 1), GL15.GL_STATIC_DRAW);
            GLSMVertexInput.glEnableVertexAttribArray(3);
            GLSMVertexInput.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, 16, 0L);
            GLSMVertexInput.glVertexAttribDivisor(3, 2);
            GLSMPrograms.glUseProgram(program);
            GLSMDraws.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, 4);
            GLSMPrograms.glUseProgram(0);
            final int[] row = GlsmHeadlessRig.readTarget(0, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE, 1);
            final int[] expected = {GlsmHeadlessRig.RED, GlsmHeadlessRig.RED, GlsmHeadlessRig.BLUE, GlsmHeadlessRig.BLUE};
            for (int instance = 0; instance < expected.length; instance++) {
                assertEquals(GlsmHeadlessRig.describe(expected[instance]), GlsmHeadlessRig.describe(row[(2 * instance + 1) * GlsmHeadlessRig.SIZE / 8]), "instance " + instance);
            }
        } finally {
            GLSMVertexInput.glVertexAttribDivisor(3, 0);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMBuffers.glDeleteBuffers(quad);
            GLSMBuffers.glDeleteBuffers(tints);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void aBufferClearStopsAtItsRange() {
        final int buffer = GLSMBuffers.glGenBuffers();
        try {
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            final ByteBuffer initial = BufferUtils.createByteBuffer(16);
            for (int i = 0; i < 16; i++) initial.put(i, (byte) 0xAA);
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, initial, GL15.GL_STATIC_DRAW);

            final ByteBuffer one = BufferUtils.createByteBuffer(1).put(0, (byte) 0x55);
            GL43C.glClearBufferSubData(GL15.GL_ARRAY_BUFFER, GL30.GL_R8, 1, 2, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, one);
            assertBytes(new int[] {0xAA, 0x55, 0x55, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA});

            final ByteBuffer word = BufferUtils.createByteBuffer(4).put(0, (byte) 1).put(1, (byte) 2).put(2, (byte) 3).put(3, (byte) 4);
            GL43C.glClearBufferSubData(GL15.GL_ARRAY_BUFFER, GL11.GL_RGBA8, 8, 8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, word);
            assertBytes(new int[] {0xAA, 0x55, 0x55, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 1, 2, 3, 4, 1, 2, 3, 4});
        } finally {
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMBuffers.glDeleteBuffers(buffer);
        }
    }

    private static void assertBytes(int[] expected) {
        final ByteBuffer got = BufferUtils.createByteBuffer(expected.length);
        GL15C.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, got);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], got.get(i) & 0xFF, "byte " + i);
    }

    @Test
    void aClearOfAnSrgbImageEncodesOnlyWithFramebufferSrgb() {
        assertSrgbWrite(false, RAW_HALF, () -> GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT));
        assertSrgbWrite(true, ENCODED_HALF, () -> GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT));
    }

    @Test
    void aDrawToAnSrgbImageEncodesOnlyWithFramebufferSrgb() {
        assertSrgbWrite(false, RAW_HALF, () -> GlsmHeadlessRig.solidQuad(0.5f, 0.5f, 0.5f));
        assertSrgbWrite(true, ENCODED_HALF, () -> GlsmHeadlessRig.solidQuad(0.5f, 0.5f, 0.5f));
    }

    private static void assertSrgbWrite(boolean encode, int expected, Runnable write) {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL21.GL_SRGB8_ALPHA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = GlsmHeadlessRig.fboWithColor(texture, 0);
        try {
            if (encode) GL11C.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
            GLSMBlend.glClearColor(0.5f, 0.5f, 0.5f, 1f);
            write.run();
            final int got = GlsmHeadlessRig.readTextureLevel(texture, 0)[0] >> 8 & 0xFF;
            assertTrue(Math.abs(got - expected) <= 1, () -> "encode=" + encode + " stored 0x" + Integer.toHexString(got) + ", expected 0x" + Integer.toHexString(expected));
        } finally {
            GL11C.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GlsmHeadlessRig.bindTarget();
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void theUniformBlockLimitIsWhatTheDeviceBinds() {
        final int limit = GL11C.glGetInteger(GL31C.GL_MAX_UNIFORM_BLOCK_SIZE);
        assertTrue(limit >= 16384 && limit <= 65536, "limit " + limit);
    }

    private static FloatBuffer floats(float... values) {
        return BufferUtils.createFloatBuffer(values.length).put(values).flip();
    }
}
