package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMFog;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMLighting;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTexEnvGen;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A call GL refuses changes nothing and reports why through glGetError, once. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmGlErrorTest {

    private static void assertError(int expected, Runnable call) {
        GL11C.glGetError();
        call.run();
        assertEquals(expected, GL11C.glGetError());
        assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError(), "reading the error clears it");
    }

    private static int buffer(int target, int size) {
        final int b = GL15C.glGenBuffers();
        GL15C.glBindBuffer(target, b);
        GL15C.glBufferData(target, size, GL15C.GL_DYNAMIC_DRAW);
        return b;
    }

    @Test
    void anUploadOutsideItsTextureIsAnInvalidValue() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            final ByteBuffer pixels = BufferUtils.createByteBuffer(4);
            assertError(GL11.GL_INVALID_VALUE, () -> GL11C.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 100, 100, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void anUploadToATextureWithNoImageIsAnInvalidOperation() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            final ByteBuffer pixels = BufferUtils.createByteBuffer(4);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aNegativeTextureSizeIsAnInvalidValue() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            assertError(GL11.GL_INVALID_VALUE, () -> GL11C.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, -1, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L));
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTextureLevel(texture, 0), GlsmHeadlessRig.GREEN, "the image as it was");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void anImmutableTextureRefusesANewImage() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL43C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 4, 4);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 8, 8, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aReadPastItsPackBufferIsAnInvalidOperation() {
        final int pbo = buffer(GL30.GL_PIXEL_PACK_BUFFER, 16);
        try {
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.nglReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L));
        } finally {
            GL15C.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            GL15C.glDeleteBuffers(pbo);
        }
    }

    @Test
    void anUploadPastItsUnpackBufferIsAnInvalidOperation() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            final int pbo = buffer(GL30.GL_PIXEL_UNPACK_BUFFER, 16);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L));
            GL15C.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15C.glDeleteBuffers(pbo);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void bufferMisuseIsReported() {
        final int a = buffer(GL15C.GL_ARRAY_BUFFER, 16);
        final int b = buffer(GL31C.GL_COPY_WRITE_BUFFER, 16);
        final int stored = GL15C.glGenBuffers();
        try {
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.nglBufferData(GL15C.GL_ARRAY_BUFFER, -16L, 0L, GL15C.GL_STATIC_DRAW));
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 1024L, BufferUtils.createByteBuffer(16)));
            assertError(GL11.GL_INVALID_VALUE, () -> GL31C.glCopyBufferSubData(GL15C.GL_ARRAY_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0L, 0L, 1024L));
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, stored);
            GL44C.glBufferStorage(GL15C.GL_ARRAY_BUFFER, 16L, 0);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL15C.nglBufferData(GL15C.GL_ARRAY_BUFFER, 16L, 0L, GL15C.GL_STATIC_DRAW));
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            GL15C.glDeleteBuffers(a);
            GL15C.glDeleteBuffers(b);
            GL15C.glDeleteBuffers(stored);
        }
    }

    @Test
    void anAllocationTheDeviceCannotMakeIsOutOfMemory() {
        final int b = GL15C.glGenBuffers();
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, b);
            assertError(GL11.GL_OUT_OF_MEMORY, () -> GL15C.nglBufferData(GL15C.GL_ARRAY_BUFFER, 1L << 42, 0L, GL15C.GL_STATIC_DRAW));
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, 16L, GL15C.GL_STATIC_DRAW);
            assertEquals(16, GL15C.glGetBufferParameteri(GL15C.GL_ARRAY_BUFFER, GL15C.GL_BUFFER_SIZE), "the buffer still works");
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glDeleteBuffers(b);
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void tooManyDrawBuffersIsAnInvalidValue() {
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        try {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            assertError(GL11.GL_INVALID_VALUE, () -> GL20C.glDrawBuffers(new int[64]));
        } finally {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
        }
    }

    @Test
    void anUnknownQueryIsAnInvalidEnum() {
        assertError(GL11.GL_INVALID_ENUM, () -> GL11C.glGetInteger(0xBEEF));
    }

    @Test
    void anIndirectDrawWithNoBufferIsAnInvalidOperation() {
        final int program = GLSMPrograms.glCreateProgram();
        final int vao = GL30C.glGenVertexArrays();
        try {
            GL30C.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL43C.nglMultiDrawArraysIndirect(GL11.GL_TRIANGLES, 0L, 1, 0));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL43C.glDispatchComputeIndirect(0L));
            final int small = buffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 16);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL40C.nglDrawArraysIndirect(GL11.GL_TRIANGLES, 1L << 20));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL43C.nglMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 1L << 20, 1, 0));
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
            GL15C.glDeleteBuffers(small);
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
        }
    }

    @Test
    void drawingToAnIncompleteFramebufferIsRefused() {
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        final int empty = GlsmHeadlessRig.newNearestTexture();
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, GlsmHeadlessRig.COLOR_FRAGMENT);
        final int vao = GL30C.glGenVertexArrays();
        try {
            GL30C.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            assertEquals(GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT, GL30C.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            assertError(GL30.GL_INVALID_FRAMEBUFFER_OPERATION, () -> GL11C.glDrawArrays(GL11.GL_TRIANGLES, 0, 3));
            assertError(GL30.GL_INVALID_FRAMEBUFFER_OPERATION, () -> GL11C.glClear(GL11.GL_COLOR_BUFFER_BIT));
            GL30C.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, empty, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT, GL30C.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            assertError(GL30.GL_INVALID_FRAMEBUFFER_OPERATION, () -> GL11C.glDrawArrays(GL11.GL_TRIANGLES, 0, 3));
        } finally {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GLSMTextures.glDeleteTextures(empty);
        }
    }

    /** Fixed function calls GLSM answers itself report errors like any other call, rather than throwing into the mod. */
    @Test
    void refusedFixedFunctionCallsReportErrors() {
        assertError(GL11.GL_INVALID_OPERATION, GLSMDraws::glEnd);
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMLighting.glLightf(0xBEEF, GL11.GL_SPOT_CUTOFF, 1f));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMLighting.glMaterialf(0xBEEF, GL11.GL_SHININESS, 1f));
        assertError(GL11.GL_INVALID_VALUE, () -> GLSMDraws.glGenLists(-1));
        assertError(GL11.GL_NO_ERROR, () -> assertEquals(0, GLSMDraws.glGenLists(0)));
        assertError(GL11.GL_INVALID_OPERATION, GLSMDraws::glEndList);
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMMatrices.glMatrixMode(0xBEEF));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMDepthStencilAlpha.glAlphaFunc(0xBEEF, 0f));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMRasterizer.glShadeModel(0xBEEF));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMFog.glFogi(0xBEEF, 0));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMFog.glFogi(GL11.GL_FOG_MODE, 0xBEEF));
        assertError(GL11.GL_INVALID_VALUE, () -> GLSMFog.glFogf(GL11.GL_FOG_DENSITY, -1f));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMTexEnvGen.glTexEnvi(0xBEEF, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMTexEnvGen.glTexEnvi(GL11.GL_TEXTURE_ENV, 0xBEEF, GL11.GL_MODULATE));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMTexEnvGen.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, 0xBEEF));
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMDraws.glBegin(0xBEEF));
    }

    @Test
    void refusedFixedFunctionCallsKeepTheirState() {
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glMatrixMode(0xBEEF);
        assertEquals(GL11.GL_MODELVIEW, GL11C.glGetInteger(GL11.GL_MATRIX_MODE));
        GLSMRasterizer.glShadeModel(GL11.GL_SMOOTH);
        GLSMRasterizer.glShadeModel(0xBEEF);
        assertEquals(GL11.GL_SMOOTH, GL11C.glGetInteger(GL11.GL_SHADE_MODEL));
        GLSMFog.glFogi(GL11.GL_FOG_MODE, GL11.GL_EXP);
        GLSMFog.glFogi(GL11.GL_FOG_MODE, 0xBEEF);
        assertEquals(GL11.GL_EXP, GL11C.glGetInteger(GL11.GL_FOG_MODE));
        GLSMFog.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
        GL11C.glGetError();
    }

    @Test
    void aNestedBeginIsRefusedAndTheFirstCarriesOn() {
        GL11C.glGetError();
        GLSMDraws.glBegin(GL11.GL_TRIANGLES);
        assertError(GL11.GL_INVALID_OPERATION, () -> GLSMDraws.glBegin(GL11.GL_POINTS));
        assertError(GL11.GL_NO_ERROR, GLSMDraws::glEnd);
    }

    @Test
    void theMatrixStackReportsOverflowAndUnderflow() {
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        final int depth = GL11C.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        final int room = GL11C.glGetInteger(GL11.GL_MAX_MODELVIEW_STACK_DEPTH) - depth;
        for (int i = 0; i < room; i++) GLSMMatrices.glPushMatrix();
        try {
            assertError(GL11.GL_STACK_OVERFLOW, GLSMMatrices::glPushMatrix);
        } finally {
            for (int i = 0; i < room; i++) GLSMMatrices.glPopMatrix();
        }
        for (int i = 1; i < depth; i++) GLSMMatrices.glPopMatrix();
        try {
            assertError(GL11.GL_STACK_UNDERFLOW, GLSMMatrices::glPopMatrix);
        } finally {
            for (int i = 1; i < depth; i++) GLSMMatrices.glPushMatrix();
        }
    }

    @Test
    void theAttribStackReportsOverflowAndUnderflow() {
        final int room = GLStateManager.MAX_ATTRIB_STACK_DEPTH - GLSMAttribStack.getAttribDepth();
        for (int i = 0; i < room; i++) GLSMAttribStack.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT);
        try {
            assertError(GL11.GL_STACK_OVERFLOW, () -> GLSMAttribStack.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT));
        } finally {
            for (int i = 0; i < room; i++) GLSMAttribStack.glPopAttrib();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError(), "the refused push was not on the stack to pop");
    }

    @Test
    void badArgumentsToLessCommonCallsReportErrors() {
        assertError(GL11.GL_INVALID_ENUM, () -> GL11C.glHint(0xBEEF, GL11.GL_NICEST));
        assertError(GL11.GL_INVALID_ENUM, () -> GL11C.glLogicOp(0xBEEF));
        assertError(GL11.GL_INVALID_ENUM, () -> GL30C.glEnablei(0xBEEF, 0));
        assertError(GL11.GL_INVALID_VALUE, () -> GL30C.glEnablei(GL11.GL_BLEND, 100000));
        assertError(GL11.GL_INVALID_ENUM, () -> GL30C.glBindRenderbuffer(0xBEEF, 0));
        assertError(GL11.GL_INVALID_ENUM, () -> GL30C.glGenerateMipmap(0xBEEF));
        assertError(GL11.GL_INVALID_ENUM, () -> GL32C.glFenceSync(0xBEEF, 0));
        assertError(GL11.GL_INVALID_VALUE, () -> GL33C.glBindSampler(100000, 0));
        assertError(GL11.GL_INVALID_OPERATION, () -> GL33C.glBindSampler(0, 100000));
        assertError(GL11.GL_INVALID_VALUE, () -> GL33C.glSamplerParameteri(100000, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST));
        assertError(GL11.GL_INVALID_VALUE, () -> GL40C.glBlendFunci(100000, GL11.GL_ONE, GL11.GL_ONE));
        assertError(GL11.GL_INVALID_ENUM, () -> GL40C.glPatchParameteri(0xBEEF, 3));
        assertError(GL11.GL_INVALID_VALUE, () -> GL20C.glValidateProgram(100000));
        assertError(GL11.GL_INVALID_OPERATION, () -> GL41C.glBindProgramPipeline(100000));
        assertError(GL11.GL_INVALID_OPERATION, () -> GL41C.glValidateProgramPipeline(100000));
    }

    @Test
    void theTransformFeedbackAndConditionalRenderStatesAreEnforced() {
        assertError(GL11.GL_INVALID_ENUM, () -> GL30C.glBeginTransformFeedback(0xBEEF));
        assertError(GL11.GL_INVALID_OPERATION, GL30C::glEndTransformFeedback);
        assertError(GL11.GL_INVALID_OPERATION, GL40C::glPauseTransformFeedback);
        assertError(GL11.GL_INVALID_OPERATION, GL40C::glResumeTransformFeedback);
        assertError(GL11.GL_INVALID_VALUE, () -> GL40C.glDrawTransformFeedback(GL11.GL_POINTS, 100000));
        assertError(GL11.GL_INVALID_OPERATION, GL30C::glEndConditionalRender);
        assertError(GL11.GL_INVALID_VALUE, () -> GL30C.glBeginConditionalRender(100000, GL30C.GL_QUERY_WAIT));
    }

    @Test
    void uniformsNeedAProgramInUse() {
        GLSMPrograms.glUseProgram(0);
        assertError(GL11.GL_INVALID_OPERATION, () -> GL20C.glUniform1f(0, 1f));
        assertError(GL11.GL_INVALID_OPERATION, () -> GL40C.glUniform1d(0, 1.0));
        assertError(GL11.GL_INVALID_OPERATION, () -> GL40C.glUniform1d(-1, 1.0));
    }

    /** Asking for a patch size stays legal on a device with no tessellation, where the program asking finds out at link. */
    @Test
    void aPatchSizeIsAcceptedWithoutTessellation() {
        assertError(GL11.GL_NO_ERROR, () -> GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 3));
    }

    /** None of these may throw through the entry point, which would leave GL with nothing to report and the call half done. */
    @Test
    void aBadRawCallIsAnErrorRatherThanAnException() {
        final int a = buffer(GL15C.GL_ARRAY_BUFFER, 16);
        final long memory = MemoryUtil.nmemCalloc(1, 64);
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL11C.glGetError();
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.nglBufferData(GL15C.GL_ARRAY_BUFFER, -1L, memory, GL15C.GL_STATIC_DRAW));
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.nglBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, -1L, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL13C.nglCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, -1, 0, 0, 4, 4, GL30C.GL_COMPRESSED_RED_RGTC1, 8, memory));
            assertError(GL11.GL_NO_ERROR, () -> assertTrue(GL45C.glCreateQueries(GL15C.GL_SAMPLES_PASSED) != 0));
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.nglGetBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, 1L << 20, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL20C.nglGetVertexAttribPointerv(-1, GL20C.GL_VERTEX_ATTRIB_ARRAY_POINTER, memory));
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            assertError(GL11.GL_INVALID_VALUE, () -> GL45C.nglGetTextureSubImage(texture, 0, 0, 0, 0, 1, 1, 1 << 20, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 64, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, -1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4)));
        } finally {
            MemoryUtil.nmemFree(memory);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glDeleteBuffers(a);
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    /** GL leaves this undefined, so it need not draw anything useful, but the indices must not be read from memory the buffer does not own. */
    @Test
    void drawingPastTheEndOfAnElementBufferDoesNotFault() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, "#version 330 core\nout vec4 f; void main() { f = vec4(1.0); }");
        final int vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);
        final int elements = buffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, 16);
        try {
            GLSMPrograms.glUseProgram(program);
            GL11C.glDrawElements(GL11.GL_TRIANGLES, 1 << 24, GL11.GL_UNSIGNED_BYTE, 0L);
            GL11C.glDrawElements(GL11.GL_LINE_LOOP, 1 << 24, GL11.GL_UNSIGNED_BYTE, 0L);
            GL11C.glDrawElements(GL11.GL_LINE_LOOP, 4, GL11.GL_UNSIGNED_BYTE, 1L << 40);
            GL11C.glGetError();
        } finally {
            GLSMPrograms.glUseProgram(0);
            GL30C.glBindVertexArray(0);
            GL15C.glDeleteBuffers(elements);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(program);
        }
    }

    /** One call for each family of argument checks, since the probe only records answers and cannot fail a build. */
    @Test
    void badArgumentsAreRefusedByFamily() {
        final int a = buffer(GL15C.GL_ARRAY_BUFFER, 16);
        final long memory = MemoryUtil.nmemCalloc(1, 256);
        try {
            GL11C.glGetError();
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.nglGenBuffers(-1, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL15C.nglDeleteBuffers(-1, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL20C.glGetProgrami(0xBEEF, GL20C.GL_LINK_STATUS));
            assertError(GL11.GL_INVALID_VALUE, () -> GL20C.glGetShaderi(0xBEEF, GL20C.GL_COMPILE_STATUS));
            assertError(GL11.GL_INVALID_ENUM, () -> GL15C.nglGetBufferParameteriv(0xBEEF, GL15C.GL_BUFFER_SIZE, memory));
            assertError(GL11.GL_INVALID_ENUM, () -> GL15C.nglGetBufferParameteriv(GL15C.GL_ARRAY_BUFFER, 0xBEEF, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL31C.glCopyBufferSubData(GL15C.GL_ARRAY_BUFFER, GL15C.GL_ARRAY_BUFFER, 0L, 0L, -1L));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL15C.nglGetQueryObjectiv(0xBEEF, GL15C.GL_QUERY_RESULT, memory));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, 0));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL33C.nglGetSamplerParameteriv(0, GL11.GL_TEXTURE_MIN_FILTER, memory));
            assertError(GL11.GL_INVALID_ENUM, () -> GL30C.nglClearBufferiv(0xBEEF, 0, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL30C.nglClearBufferiv(GL11.GL_COLOR, -1, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL32C.nglClientWaitSync(0L, 0, 0L));
            assertError(GL11.GL_INVALID_ENUM, () -> GL11C.glGetBoolean(0xBEEF));
            assertError(GL11.GL_INVALID_ENUM, () -> GL11C.nglTexParameteriv(0xBEEF, GL11.GL_TEXTURE_WRAP_S, memory));
            assertError(GL11.GL_INVALID_ENUM, () -> GL13C.nglCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, 0xBEEF, 4, 4, 0, 8, memory));
            assertError(GL11.GL_INVALID_VALUE, () -> GL13C.nglCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30C.GL_COMPRESSED_RED_RGTC1, 4, 4, 0, 1, memory));
            assertError(GL11.GL_INVALID_ENUM, () -> GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, 0xBEEF, 4, 4));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL30C.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4)));
            assertError(GL11.GL_INVALID_ENUM, () -> GL11C.glTexImage1D(0xBEEF, 0, GL11.GL_RGBA8, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4)));
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.glDrawArrays(GL40C.GL_PATCHES, 0, 0));
            assertError(GL11.GL_INVALID_VALUE, () -> GL12C.nglDrawRangeElements(GL11.GL_TRIANGLES, 1, 0, 0, GL11.GL_UNSIGNED_BYTE, 0L));
            assertError(GL11.GL_INVALID_VALUE, () -> GL41C.nglViewportArrayv(0xBEEF, 1, memory));
            assertError(GL11.GL_INVALID_ENUM, () -> GL41C.nglShaderBinary(1, memory, 0xBEEF, memory, 4));
        } finally {
            MemoryUtil.nmemFree(memory);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glDeleteBuffers(a);
        }
    }

    /** Unlike core, a compatibility profile makes an object of a name it has not seen, which old mods rely on. Boolean queries must keep answering too. */
    @Test
    void whatACompatibilityProfileAllowsStaysAllowed() {
        try {
            GL11C.glGetError();
            assertError(GL11.GL_NO_ERROR, () -> GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0xBEE0));
            assertError(GL11.GL_NO_ERROR, () -> GL11C.glBindTexture(GL11.GL_TEXTURE_2D, 0xBEE1));
            assertError(GL11.GL_NO_ERROR, () -> GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, 0xBEE2));
            assertError(GL11.GL_NO_ERROR, () -> GL15C.glEndQuery(GL15C.GL_SAMPLES_PASSED));
            assertError(GL11.GL_NO_ERROR, () -> GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(36)));
            assertError(GL11.GL_NO_ERROR, () -> GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, 4, GL11.GL_RGBA8, 4, 4));
            for (int pname : new int[] {GL11.GL_DEPTH_WRITEMASK, GL11.GL_COLOR_WRITEMASK, GL11.GL_BLEND, GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_DOUBLEBUFFER, GL11.GL_STEREO, GL13C.GL_SAMPLE_COVERAGE_INVERT, GL11.GL_DEPTH_TEST}) {
                assertError(GL11.GL_NO_ERROR, () -> GL11C.glGetBoolean(pname));
            }
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL11C.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL15C.glDeleteBuffers(0xBEE0);
            GL11C.glDeleteTextures(0xBEE1);
        }
    }

    /** What GLSM answers from its cache must be what GL would keep, including when a call is refused or could be taken for redundant. */
    @Test
    void theCacheKeepsWhatGlKeeps() {
        GL11C.glGetError();
        GLSMBlend.glLogicOp(GL11.GL_COPY);
        assertError(GL11.GL_INVALID_ENUM, () -> GLSMBlend.glLogicOp(0xBEEF));
        assertEquals(GL11.GL_COPY, GLSMQueries.glGetInteger(GL11.GL_LOGIC_OP_MODE));

        // A back face set alone must not make the next call for both faces look redundant.
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GLSMDepthStencilAlpha.glStencilFuncSeparate(GL11.GL_BACK, GL11.GL_NEVER, 0, 0xFF);
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        assertEquals(GL11.GL_ALWAYS, GLSMQueries.glGetInteger(GL20C.GL_STENCIL_BACK_FUNC));
        GLSMDepthStencilAlpha.glStencilMaskSeparate(GL11.GL_BACK, 0x0F);
        GLSMDepthStencilAlpha.glStencilMask(0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, GLSMQueries.glGetInteger(GL20C.GL_STENCIL_BACK_WRITEMASK));

        // A reference is clamped to what the buffer holds, while a mask is kept whole.
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, 0xBEEF, 0xBEEF);
        assertEquals(255, GLSMQueries.glGetInteger(GL11.GL_STENCIL_REF));
        assertEquals(0xBEEF, GLSMQueries.glGetInteger(GL11.GL_STENCIL_VALUE_MASK));
        GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, -1, 0xFFFFFFFF);
        assertEquals(0, GLSMQueries.glGetInteger(GL11.GL_STENCIL_REF));
        GLSMDepthStencilAlpha.glClearStencil(-1);
        assertEquals(255, GLSMQueries.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE));
        GLSMDepthStencilAlpha.glClearStencil(0);

        final int[] mask = new int[4];
        GLSMBlend.glColorMask(false, true, false, true);
        GLSMQueries.glGetInteger(GL11.GL_COLOR_WRITEMASK, mask);
        assertEquals(0, mask[0]);
        assertEquals(1, mask[1]);
        GLSMBlend.glColorMask(true, true, true, true);

        final int[] viewport = new int[4];
        final int[] was = new int[4];
        GLSMQueries.glGetInteger(GL11.GL_VIEWPORT, was);
        GLSMRasterizer.glViewport(1 << 20, 0, 1 << 24, 4);
        GLSMQueries.glGetInteger(GL11.GL_VIEWPORT, viewport);
        assertEquals(32767, viewport[0]);
        assertTrue(viewport[2] < 1 << 24, "the size is clamped to what a viewport can be");
        GLSMRasterizer.glViewport(was[0], was[1], was[2], was[3]);
    }

    @Test
    void displayListsRecordRatherThanFail() {
        GL11C.glGetError();
        assertError(GL11.GL_INVALID_VALUE, () -> DisplayListManager.glNewList(0, GL11.GL_COMPILE));
        assertError(GL11.GL_INVALID_ENUM, () -> DisplayListManager.glNewList(9, 0xBEEF));
        final int list = GLSMDraws.glGenLists(1);
        GLSMBlend.glBlendEquation(GL14C.GL_FUNC_ADD);
        DisplayListManager.glNewList(list, GL11.GL_COMPILE);
        GLSMBlend.glBlendEquation(GL14C.GL_FUNC_SUBTRACT);
        GLSMRasterizer.glSampleCoverage(0.5f, true);
        DisplayListManager.glEndList();
        assertEquals(GL14C.GL_FUNC_ADD, GLSMQueries.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB), "compiling only records");
        DisplayListManager.glCallList(list);
        assertEquals(GL14C.GL_FUNC_SUBTRACT, GLSMQueries.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB));
        GLSMBlend.glBlendEquation(GL14C.GL_FUNC_ADD);
    }

    /** A mapped pointer is aligned as GL_MIN_MAP_BUFFER_ALIGNMENT promises, which a mod may rely on for wide writes. */
    @Test
    void mappedPointersAreAlignedAsReported() {
        final int alignment = GL11C.glGetInteger(GL44C.GL_MIN_MAP_BUFFER_ALIGNMENT);
        for (int size : new int[] {4, 100, 4096}) {
            final int a = buffer(GL15C.GL_ARRAY_BUFFER, size);
            final java.nio.ByteBuffer mapped = GL30C.glMapBufferRange(GL15C.GL_ARRAY_BUFFER, 0, size, GL30C.GL_MAP_WRITE_BIT);
            final long address = MemoryUtil.memAddress(mapped);
            GL15C.glUnmapBuffer(GL15C.GL_ARRAY_BUFFER);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glDeleteBuffers(a);
            assertEquals(0L, address % alignment, "a buffer of " + size);
        }
    }

    /** Each of these is legal; refusing one leaves the mod with no image or no answer. */
    @Test
    void legalCallsWithNoDirectPathAreAccepted() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        final ByteBuffer client = BufferUtils.createByteBuffer(16);
        final ByteBuffer back = BufferUtils.createByteBuffer(4);
        try {
            GL11C.glGetError();
            // 0xF804 as 4_4_4_4 is red 15, green 8, blue 0, alpha 4.
            client.putShort(0, (short) 0xF804);
            assertError(GL11.GL_NO_ERROR, () -> GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_4_4_4_4, client));
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, back);
            assertEquals(255, back.get(0) & 0xFF);
            assertEquals(136, back.get(1) & 0xFF);
            assertEquals(0, back.get(2) & 0xFF);
            assertEquals(68, back.get(3) & 0xFF);
            // A negative byte clamps to 0 in an unsigned format, where copying it would make it bright.
            client.put(0, (byte) -100);
            assertError(GL11.GL_NO_ERROR, () -> GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30C.GL_R8, 1, 1, 0, GL11.GL_RED, GL11.GL_BYTE, client));
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, back);
            assertEquals(0, back.get(0) & 0xFF);
            assertError(GL11.GL_INVALID_OPERATION, () -> GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL12C.GL_BGR, GL12C.GL_UNSIGNED_SHORT_5_6_5, client));

            GL11C.glViewport(1, 2, 3, 4);
            final double[] doubles = new double[4];
            assertError(GL11.GL_NO_ERROR, () -> GL11C.glGetDoublev(GL11.GL_VIEWPORT, doubles));
            assertEquals(4.0, doubles[3]);
            final float[] floats = new float[4];
            assertError(GL11.GL_NO_ERROR, () -> GL41C.glGetFloati_v(GL11.GL_VIEWPORT, 0, floats));
            assertEquals(3f, floats[2]);
            final long[] longs = new long[4];
            assertError(GL11.GL_NO_ERROR, () -> GL32C.glGetInteger64v(GL11.GL_VIEWPORT, longs));
            assertEquals(2L, longs[1]);

            final int multisample = GL11C.glGenTextures();
            GL11C.glBindTexture(GL32C.GL_TEXTURE_2D_MULTISAMPLE, multisample);
            assertError(GL11.GL_NO_ERROR, () -> GL32C.glTexImage2DMultisample(GL32C.GL_TEXTURE_2D_MULTISAMPLE, 4, GL11.GL_RGBA8, 4, 4, true));
            GL11C.glBindTexture(GL32C.GL_TEXTURE_2D_MULTISAMPLE, 0);
            GL11C.glDeleteTextures(multisample);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void correctUseReportsNothing() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        final int a = buffer(GL15C.GL_ARRAY_BUFFER, 16);
        try {
            GL11C.glGetError();
            GlsmHeadlessRig.uploadSolidLevel(0, 4, GlsmHeadlessRig.GREEN);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, BufferUtils.createByteBuffer(16));
            GL11C.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            GL11C.glClear(GL11.GL_COLOR_BUFFER_BIT);
            assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glDeleteBuffers(a);
            GLSMTextures.glDeleteTextures(texture);
        }
    }
}
