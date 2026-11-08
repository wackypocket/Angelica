package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBComputeVariableGroupSize;
import org.lwjgl.opengl.ARBGPUShaderInt64;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.ARBShadingLanguageInclude;
import org.lwjgl.opengl.EXTBindableUniform;
import org.lwjgl.opengl.EXTDirectStateAccess;
import org.lwjgl.opengl.EXTDrawBuffers2;
import org.lwjgl.opengl.EXTFramebufferBlitLayers;
import org.lwjgl.opengl.EXTStencilTwoSide;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.NVFence;
import org.lwjgl.opengl.NVPathRendering;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extension functions called the way a mod would, through LWJGL's own function pointers. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmExtensionCallTest {

    private static final int S = GlsmHeadlessRig.SIZE;

    @Test
    void aVendorExtensionCallIsAHarmlessNoOp() {
        assertEquals(0, NVPathRendering.glGenPathsNV(1));
        assertFalse(NVFence.glIsFenceNV(1));
    }

    @Test
    void multiTexCallsActOnTheirUnitAndLeaveTheActiveOne() {
        final int texture = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        EXTDirectStateAccess.glBindMultiTextureEXT(GL13.GL_TEXTURE3, GL11.GL_TEXTURE_2D, texture);
        assertEquals(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        assertEquals(texture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDeleteTextures(texture);
    }

    @Test
    void textureDsaWithATargetArgumentReadsItsArgumentsRightly() {
        final int texture = GL11.glGenTextures();
        final int was = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        EXTDirectStateAccess.glTextureParameteriEXT(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        final ByteBuffer pixels = BufferUtils.createByteBuffer(16);
        pixels.put(0, (byte) 200);
        EXTDirectStateAccess.glTextureImage2DEXT(texture, GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        assertEquals(was, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        assertEquals(GL11.GL_NEAREST, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
        assertEquals(2, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, was);
        GL11.glDeleteTextures(texture);
    }

    @Test
    void matrixDsaLeavesTheMatrixModeAlone() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        EXTDirectStateAccess.glMatrixLoadIdentityEXT(GL11.GL_PROJECTION);
        EXTDirectStateAccess.glMatrixTranslatefEXT(GL11.GL_PROJECTION, 3f, 0f, 0f);
        assertEquals(GL11.GL_MODELVIEW, GL11.glGetInteger(GL11.GL_MATRIX_MODE));
        final FloatBuffer m = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, m);
        assertEquals(3f, m.get(12), 0.001f);
        EXTDirectStateAccess.glMatrixLoadIdentityEXT(GL11.GL_PROJECTION);
    }

    @Test
    void indexedStateGoesToTheCoreCalls() {
        EXTDrawBuffers2.glColorMaskIndexedEXT(0, true, false, true, true);
        final ByteBuffer mask = BufferUtils.createByteBuffer(16);
        EXTDrawBuffers2.glGetBooleanIndexedvEXT(GL11.GL_COLOR_WRITEMASK, 0, mask);
        assertEquals(1, mask.get(0));
        assertEquals(0, mask.get(1));
        assertEquals(1, mask.get(2));
        assertEquals(1, mask.get(3));
        EXTDrawBuffers2.glColorMaskIndexedEXT(0, true, true, true, true);
    }

    @Test
    void int64UniformsReachTheShader() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 450 core
            #extension GL_ARB_gpu_shader_int64 : require
            uniform int64_t big;
            out vec4 fragColor;
            void main() { fragColor = big == 0x100000002L ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0); }
            """);
        try {
            GLSMPrograms.glUseProgram(program);
            final int location = GLSMPrograms.glGetUniformLocation(program, "big");
            assertTrue(location >= 0);
            ARBGPUShaderInt64.glUniform1i64ARB(location, 0x100000002L);
            final LongBuffer back = BufferUtils.createLongBuffer(1);
            ARBGPUShaderInt64.glGetUniformi64vARB(program, location, back);
            assertEquals(0x100000002L, back.get(0));
            GlsmHeadlessRig.bindTarget();
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "the 64-bit comparison");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void namedStringsExpandInIncludes() {
        ARBShadingLanguageInclude.glNamedStringARB(ARBShadingLanguageInclude.GL_SHADER_INCLUDE_ARB, "/green.glsl", "const vec4 GREEN = vec4(0.0, 1.0, 0.0, 1.0);");
        assertTrue(ARBShadingLanguageInclude.glIsNamedStringARB("/green.glsl"));
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            #extension GL_ARB_shading_language_include : require
            #include "/green.glsl"
            out vec4 fragColor;
            void main() { fragColor = GREEN; }
            """);
        try {
            GLSMPrograms.glUseProgram(program);
            GlsmHeadlessRig.bindTarget();
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "a color from the included string");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            ARBShadingLanguageInclude.glDeleteNamedStringARB("/green.glsl");
        }
    }

    private static ByteBuffer solidImage(int size, int r, int g, int b) {
        final ByteBuffer image = BufferUtils.createByteBuffer(size * size * 4);
        for (int i = 0; i < size * size; i++) image.put(i * 4, (byte) r).put(i * 4 + 1, (byte) g).put(i * 4 + 2, (byte) b).put(i * 4 + 3, (byte) 255);
        return image;
    }

    @Test
    void aColorTableRemapsAnImage() {
        GlsmHeadlessRig.clearTargetToBlack();
        final ByteBuffer table = BufferUtils.createByteBuffer(8);
        table.put(0, (byte) 0).put(1, (byte) 255).put(2, (byte) 0).put(3, (byte) 255);
        table.put(4, (byte) 0).put(5, (byte) 0).put(6, (byte) 0).put(7, (byte) 255);
        ARBImaging.glColorTable(ARBImaging.GL_COLOR_TABLE, GL11.GL_RGBA8, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, table);
        GL11.glEnable(ARBImaging.GL_COLOR_TABLE);
        assertTrue(GL11.glIsEnabled(ARBImaging.GL_COLOR_TABLE));
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solidImage(4, 255, 0, 0));
        GL11.glDisable(ARBImaging.GL_COLOR_TABLE);
        GlsmHeadlessRig.assertPixel(GlsmHeadlessRig.readTarget(), S, 1, 1, GlsmHeadlessRig.GREEN, "each channel looked up on its own");
    }

    @Test
    void histogramAndMinmaxCountWhatIsDrawn() {
        GlsmHeadlessRig.clearTargetToBlack();
        ARBImaging.glHistogram(ARBImaging.GL_HISTOGRAM, 2, GL11.GL_RGBA8, true);
        ARBImaging.glMinmax(ARBImaging.GL_MINMAX, GL11.GL_RGBA8, true);
        GL11.glEnable(ARBImaging.GL_HISTOGRAM);
        GL11.glEnable(ARBImaging.GL_MINMAX);
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solidImage(4, 255, 0, 0));
        GL11.glDisable(ARBImaging.GL_HISTOGRAM);
        GL11.glDisable(ARBImaging.GL_MINMAX);
        final IntBuffer counts = BufferUtils.createIntBuffer(8);
        ARBImaging.glGetHistogram(ARBImaging.GL_HISTOGRAM, true, GL11.GL_RGBA, GL11.GL_UNSIGNED_INT, MemoryUtil.memByteBuffer(counts));
        assertEquals(16, counts.get(4), "all red values in the upper bin");
        assertEquals(16, counts.get(1), "all green values in the lower bin");
        final FloatBuffer range = BufferUtils.createFloatBuffer(8);
        ARBImaging.glGetMinmax(ARBImaging.GL_MINMAX, true, GL11.GL_RGBA, GL11.GL_FLOAT, MemoryUtil.memByteBuffer(range));
        assertEquals(1f, range.get(4), 0.001f, "the highest red");
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLACK, "a sink stops the image");
    }

    @Test
    void aConvolutionFiltersAnImage() {
        GlsmHeadlessRig.clearTargetToBlack();
        final FloatBuffer kernel = BufferUtils.createFloatBuffer(4).put(0, new float[] {0.5f, 0.5f, 0.5f, 0.5f});
        ARBImaging.glConvolutionFilter1D(ARBImaging.GL_CONVOLUTION_1D, GL11.GL_RGBA8, 1, GL11.GL_RGBA, GL11.GL_FLOAT, MemoryUtil.memByteBuffer(kernel));
        GL11.glEnable(ARBImaging.GL_CONVOLUTION_1D);
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solidImage(4, 255, 255, 255));
        GL11.glDisable(ARBImaging.GL_CONVOLUTION_1D);
        final int pixel = GlsmHeadlessRig.pixelAt(GlsmHeadlessRig.readTarget(), S, 1, 1);
        assertEquals(0x80, pixel >> 16 & 0xFF, 2, "halved by the filter");
    }

    @Test
    void aBlitReadsTheLayerItNames() {
        final int array = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
        GL42C.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, 1, GL11.GL_RGBA8, S, S, 2);
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, array, 0, 1);
        GL11.glClearColor(0f, 1f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL30C.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, array, 0, 0);
        GL11.glClearColor(1f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GlsmHeadlessRig.bindTarget();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        EXTFramebufferBlitLayers.glBlitFramebufferLayerEXT(0, 0, S, S, 1, 0, 0, S, S, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlsmHeadlessRig.bindTarget();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
        GL11.glDeleteTextures(array);
        GLSMFramebuffer.glDeleteFramebuffers(fbo);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "layer 1, though layer 0 is attached");
    }

    @Test
    void aTextureCanBeARenderbuffersStorage() {
        final int renderbuffer = GLSMFramebuffer.glGenRenderbuffers();
        GLSMFramebuffer.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
        GLSMFramebuffer.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, S, S);
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GLSMFramebuffer.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, renderbuffer);
        GL11.glClearColor(0f, 1f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GlsmHeadlessRig.clearTargetToBlack();
        final int texture = GlsmHeadlessRig.newNearestTexture();
        EXTDirectStateAccess.glTextureRenderbufferEXT(texture, GL11.GL_TEXTURE_2D, renderbuffer);
        GlsmHeadlessRig.texturedQuad(texture, 1f);
        GL11.glDeleteTextures(texture);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "what the renderbuffer holds");
        GLSMFramebuffer.glDeleteFramebuffers(fbo);
        GLSMFramebuffer.glDeleteRenderbuffers(renderbuffer);
    }

    @Test
    void stencilCallsAimAtTheActiveFace() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glEnable(EXTStencilTwoSide.GL_STENCIL_TEST_TWO_SIDE_EXT);
        EXTStencilTwoSide.glActiveStencilFaceEXT(GL11.GL_BACK);
        GL11.glStencilFunc(GL11.GL_NEVER, 0, 0xFF);
        EXTStencilTwoSide.glActiveStencilFaceEXT(GL11.GL_FRONT);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glDisable(EXTStencilTwoSide.GL_STENCIL_TEST_TWO_SIDE_EXT);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glColor4f(0f, 1f, 0f, 1f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1f, -1f);
        GL11.glVertex2f(0f, -1f);
        GL11.glVertex2f(0f, 1f);
        GL11.glVertex2f(-1f, 1f);
        GL11.glEnd();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, -1f);
        GL11.glVertex2f(0f, 1f);
        GL11.glVertex2f(1f, 1f);
        GL11.glVertex2f(1f, -1f);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        final int[] pixels = GlsmHeadlessRig.readTarget();
        GlsmHeadlessRig.assertPixel(pixels, S, S / 4, S / 2, GlsmHeadlessRig.GREEN, "the front-facing quad");
        GlsmHeadlessRig.assertPixel(pixels, S, S * 3 / 4, S / 2, GlsmHeadlessRig.BLACK, "the back-facing quad");
    }

    @Test
    void moreBindableBuffersThanBindingsStillDraw() {
        GlsmHeadlessRig.clearTargetToBlack();
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            #extension GL_EXT_bindable_uniform : enable
            bindable uniform vec4 tint;
            out vec4 fragColor;
            void main() { fragColor = tint; }
            """);
        final int location = GLSMPrograms.glGetUniformLocation(program, "tint");
        final FloatBuffer green = BufferUtils.createFloatBuffer(4).put(0, new float[] {0f, 1f, 0f, 1f});
        GLSMPrograms.glUseProgram(program);
        for (int i = 0; i < GlState.MAX_BUFFER_BINDINGS + 6; i++) {
            final int buffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, green, GL15C.GL_STATIC_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            EXTBindableUniform.glUniformBufferEXT(program, location, buffer);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
        GLSMPrograms.glUseProgram(0);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "the last buffer's color");
        GLSMPrograms.glDeleteProgram(program);
    }

    @Test
    void aBindableUniformReadsItsBuffer() {
        GlsmHeadlessRig.clearTargetToBlack();
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            #extension GL_EXT_bindable_uniform : enable
            bindable uniform vec4 tint;
            out vec4 fragColor;
            void main() { fragColor = tint; }
            """);
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, BufferUtils.createFloatBuffer(4).put(0, new float[] {0f, 1f, 0f, 1f}), GL15C.GL_STATIC_DRAW);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        try {
            final int location = GLSMPrograms.glGetUniformLocation(program, "tint");
            assertTrue(location >= 0);
            assertEquals(16, EXTBindableUniform.glGetUniformBufferSizeEXT(program, location));
            EXTBindableUniform.glUniformBufferEXT(program, location, buffer);
            GLSMPrograms.glUseProgram(program);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "the color in the buffer");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    @Test
    void aDispatchChoosesItsLocalSize() {
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, """
            #version 430 core
            #extension GL_ARB_compute_variable_group_size : require
            layout(local_size_variable) in;
            layout(std430, binding = 3) buffer Out { uint values[]; };
            uniform uint scale;
            void main() { values[gl_LocalInvocationIndex] = gl_LocalGroupSizeARB.x * scale; }
            """);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), GL20C.glGetProgramInfoLog(program));
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 64L, GL15C.GL_DYNAMIC_READ);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, buffer);
        try {
            GLSMPrograms.glUseProgram(program);
            GL30C.glUniform1ui(GLSMPrograms.glGetUniformLocation(program, "scale"), 10);
            ARBComputeVariableGroupSize.glDispatchComputeGroupSizeARB(1, 1, 1, 4, 1, 1);
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            final IntBuffer out = BufferUtils.createIntBuffer(4);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, out);
            assertEquals(40, out.get(3), "the fourth invocation of four, through a uniform set on the original program");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            GL15C.glDeleteBuffers(buffer);
            GLSMPrograms.glDeleteProgram(program);
            GLSMPrograms.glDeleteShader(shader);
        }
    }

    @Test
    void aNamedStringReportsItsLengthAndType() {
        ARBShadingLanguageInclude.glNamedStringARB(ARBShadingLanguageInclude.GL_SHADER_INCLUDE_ARB, "/four", "1234");
        assertEquals(5, ARBShadingLanguageInclude.glGetNamedStringiARB("/four", ARBShadingLanguageInclude.GL_NAMED_STRING_LENGTH_ARB));
        assertEquals(ARBShadingLanguageInclude.GL_SHADER_INCLUDE_ARB, ARBShadingLanguageInclude.glGetNamedStringiARB("/four", ARBShadingLanguageInclude.GL_NAMED_STRING_TYPE_ARB));
        ARBShadingLanguageInclude.glDeleteNamedStringARB("/four");
    }
}
