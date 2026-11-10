package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.ARBOcclusionQuery;
import org.lwjgl.opengl.ARBRobustness;
import org.lwjgl.opengl.ARBTransposeMatrix;
import org.lwjgl.opengl.ARBWindowPos;
import org.lwjgl.opengl.EXTBlendMinmax;
import org.lwjgl.opengl.EXTDebugLabel;
import org.lwjgl.opengl.EXTDebugMarker;
import org.lwjgl.opengl.EXTDrawBuffers2;
import org.lwjgl.opengl.EXTProvokingVertex;
import org.lwjgl.opengl.EXTSecondaryColor;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.opengl.KHRRobustness;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Each extension flag the backends raise beyond their core list, shown to do what the extension promises. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmExtensionFlagsTest {

    /** The flags the tests below show working; each must be advertised. */
    private static final List<String> EARNED = List.of(
        "GL_ARB_half_float_pixel", "GL_EXT_packed_float", "GL_EXT_texture_shared_exponent", "GL_EXT_texture_snorm", "GL_ARB_texture_rgb10_a2ui",
        "GL_EXT_bgra", "GL_ARB_depth_texture", "GL_EXT_packed_depth_stencil", "GL_ARB_robustness",
        "GL_KHR_robustness", "GL_ARB_transpose_matrix", "GL_ARB_window_pos", "GL_EXT_secondary_color", "GL_EXT_blend_minmax", "GL_EXT_blend_subtract",
        "GL_EXT_blend_color", "GL_EXT_provoking_vertex", "GL_EXT_draw_buffers2", "GL_ARB_invalidate_subdata", "GL_ARB_multi_bind",
        "GL_ARB_get_texture_sub_image", "GL_ARB_debug_output", "GL_EXT_debug_marker", "GL_EXT_debug_label", "GL_ARB_texture_border_clamp",
        "GL_ARB_texture_rectangle", "GL_ARB_shadow", "GL_EXT_shadow_funcs", "GL_ARB_texture_buffer_range", "GL_ARB_texture_env_combine");

    @Test
    void everyFlagEarnedHereIsAdvertised() {
        final GLCapabilities caps = GL.getCapabilities();
        for (String flag : EARNED) assertTrue(advertised(caps, flag), flag);
        for (String extension : ShaderExtensionSnippets.ALL.keySet()) assertTrue(advertised(caps, "GL_" + extension), extension);
        final boolean bc = NativeGlsmRig.gl().gpuCaps().textureCompressionBC;
        assertEquals(bc, caps.GL_EXT_texture_compression_s3tc);
        assertEquals(bc, caps.GL_ARB_texture_compression_rgtc);
        assertEquals(bc, caps.GL_ARB_texture_compression_bptc);
    }

    private static boolean advertised(GLCapabilities caps, String flag) {
        try {
            return GLCapabilities.class.getField(flag).getBoolean(caps);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(flag, e);
        }
    }

    static Stream<Arguments> shaderExtensions() {
        return ShaderExtensionSnippets.ALL.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shaderExtensions")
    void aShaderUsingTheExtensionLinks(String extension, String fragment) {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, fragment);
        assertEquals(GL11.GL_TRUE, GLSMPrograms.glGetProgrami(program, GL20.GL_LINK_STATUS), extension);
        GLSMPrograms.glDeleteProgram(program);
    }

    private static ByteBuffer bytes(int... values) {
        final ByteBuffer b = BufferUtils.createByteBuffer(values.length);
        for (int i = 0; i < values.length; i++) b.put(i, (byte) values[i]);
        return b;
    }

    private static void assertRoundTrips(int internalFormat, int format, int type, ByteBuffer texel) {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, 1, 1, 0, format, type, texel);
            final ByteBuffer read = BufferUtils.createByteBuffer(texel.remaining());
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, type, read);
            final byte[] want = new byte[texel.remaining()], got = new byte[read.remaining()];
            texel.get(texel.position(), want);
            read.get(read.position(), got);
            assertArrayEquals(want, got);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void halfFloatPixels() {
        assertRoundTrips(GL30.GL_RGBA16F, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, bytes(0, 0x3C, 0, 0x38, 0, 0x34, 0, 0x3C));
    }

    @Test
    void packedFloatPixels() {
        assertRoundTrips(GL30.GL_R11F_G11F_B10F, GL11.GL_RGB, GL30.GL_UNSIGNED_INT_10F_11F_11F_REV, bytes(0xC0, 0x03, 0xF8, 0x7F));
    }

    @Test
    void sharedExponentPixels() {
        assertRoundTrips(GL30.GL_RGB9_E5, GL11.GL_RGB, GL30.GL_UNSIGNED_INT_5_9_9_9_REV, bytes(0, 1, 0x04, 0x80));
    }

    @Test
    void signedNormalizedPixels() {
        assertRoundTrips(GL31.GL_RGBA8_SNORM, GL11.GL_RGBA, GL11.GL_BYTE, bytes(127, -127, 1, 64));
    }

    @Test
    void integerTenBitPixels() {
        assertRoundTrips(GL33.GL_RGB10_A2UI, GL30.GL_RGBA_INTEGER, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, bytes(0x01, 0x08, 0x30, 0xC0));
    }

    @Test
    void integerPixels() {
        assertRoundTrips(GL30.GL_RGBA32UI, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_INT, bytes(1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0));
    }

    @Test
    void bgraPixels() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bytes(10, 20, 30, 40));
            final ByteBuffer read = BufferUtils.createByteBuffer(4);
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertArrayEquals(new byte[] {30, 20, 10, 40}, new byte[] {read.get(0), read.get(1), read.get(2), read.get(3)});
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void depthPixels() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            final FloatBuffer depth = BufferUtils.createFloatBuffer(1).put(0, 0.5f);
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 1, 1, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            final FloatBuffer read = BufferUtils.createFloatBuffer(1);
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, read);
            assertEquals(0.5f, read.get(0), 0.001f);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void packedDepthStencilAttachmentsHoldBoth() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, 4, 4, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        try {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
            GL11C.glClearDepth(0.5);
            GL11C.glClearStencil(0x5A);
            GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
            final IntBuffer read = BufferUtils.createIntBuffer(16);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, read);
            assertEquals(0x5A, read.get(0) & 0xFF, "stencil");
            assertEquals(0x800000, read.get(0) >>> 8, 0x100, "depth");
        } finally {
            GL11C.glClearDepth(1.0);
            GL11C.glClearStencil(0);
            GlsmHeadlessRig.bindTarget();
            GLSMFramebuffer.glDeleteFramebuffers(fbo);
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    private static void assertCompressedRoundTrips(int format, int... block) {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL13C.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, format, 4, 4, 0, bytes(block));
            final ByteBuffer read = BufferUtils.createByteBuffer(block.length);
            GL13C.glGetCompressedTexImage(GL11.GL_TEXTURE_2D, 0, read);
            for (int i = 0; i < block.length; i++) assertEquals((byte) block[i], read.get(i), "byte " + i);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void s3tcBlocks() {
        assumeTrue(NativeGlsmRig.gl().gpuCaps().textureCompressionBC);
        assertCompressedRoundTrips(EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, 255, 0, 0, 0, 0, 0, 0, 0, 0, 0xF8, 0, 0xF8, 0, 0, 0, 0);
    }

    @Test
    void rgtcBlocks() {
        assumeTrue(NativeGlsmRig.gl().gpuCaps().textureCompressionBC);
        assertCompressedRoundTrips(GL30.GL_COMPRESSED_RED_RGTC1, 255, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void bptcBlocks() {
        assumeTrue(NativeGlsmRig.gl().gpuCaps().textureCompressionBC);
        assertCompressedRoundTrips(GL42.GL_COMPRESSED_RGBA_BPTC_UNORM, 0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static int query(int target) {
        final int query = ARBOcclusionQuery.glGenQueriesARB();
        GlsmHeadlessRig.clearTargetToBlack();
        ARBOcclusionQuery.glBeginQueryARB(target, query);
        GlsmHeadlessRig.solidQuad(1f, 0f, 0f);
        ARBOcclusionQuery.glEndQueryARB(target);
        GL11C.glFinish();
        final int result = ARBOcclusionQuery.glGetQueryObjectiARB(query, ARBOcclusionQuery.GL_QUERY_RESULT_ARB);
        ARBOcclusionQuery.glDeleteQueriesARB(query);
        return result;
    }

    @Test
    void occlusionQueriesReportEverythingVisible() {
        assertTrue(query(ARBOcclusionQuery.GL_SAMPLES_PASSED_ARB) > 0, "samples passed");
        assertEquals(1, query(GL33.GL_ANY_SAMPLES_PASSED), "any samples passed");
    }

    @Test
    void robustnessReportsNoReset() {
        assertEquals(GL11.GL_NO_ERROR, ARBRobustness.glGetGraphicsResetStatusARB());
        assertEquals(GL11.GL_NO_ERROR, KHRRobustness.glGetGraphicsResetStatus());
    }

    @Test
    void transposeMatricesLoad() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            final FloatBuffer m = BufferUtils.createFloatBuffer(16);
            for (int i = 0; i < 16; i++) m.put(i, i);
            ARBTransposeMatrix.glLoadTransposeMatrixfARB(m);
            final FloatBuffer read = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, read);
            assertEquals(4f, read.get(1), 0f);
            assertEquals(1f, read.get(4), 0f);
        } finally {
            GL11.glPopMatrix();
        }
    }

    @Test
    void windowPositionsPlaceTheRaster() {
        GlsmHeadlessRig.clearTargetToBlack();
        ARBWindowPos.glWindowPos2iARB(2, 3);
        final ByteBuffer pixel = bytes(0, 255, 0, 255);
        GL11.glDrawPixels(1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GlsmHeadlessRig.assertTargetPixel(2, 3, GlsmHeadlessRig.GREEN, "the pixel at the window position");
    }

    @Test
    void secondaryColorAddsToThePrimary() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glEnable(GL14.GL_COLOR_SUM);
        try {
            EXTSecondaryColor.glSecondaryColor3fEXT(0f, 1f, 0f);
            GlsmHeadlessRig.solidQuad(1f, 0f, 0f);
        } finally {
            GL11.glDisable(GL14.GL_COLOR_SUM);
            EXTSecondaryColor.glSecondaryColor3fEXT(0f, 0f, 0f);
        }
        GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, 0xFFFFFF00, "red plus green");
    }

    private static void assertBlends(int equation, int expected) {
        GlsmHeadlessRig.clearTo(1f, 0f, 0f, 1f);
        GLSMEnable.glEnable(GL11.GL_BLEND);
        GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        EXTBlendMinmax.glBlendEquationEXT(equation);
        try {
            GlsmHeadlessRig.solidQuad(1f, 1f, 0f);
        } finally {
            EXTBlendMinmax.glBlendEquationEXT(GL14.GL_FUNC_ADD);
            GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
            GLSMEnable.glDisable(GL11.GL_BLEND);
        }
        GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, expected, "blended");
    }

    @Test
    void blendEquationsApply() {
        assertBlends(GL14.GL_MAX, 0xFFFFFF00);
        assertBlends(GL14.GL_MIN, GlsmHeadlessRig.RED);
        assertBlends(GL14.GL_FUNC_SUBTRACT, 0x0000FF00);
    }

    @Test
    void theProvokingVertexIsRemembered() {
        EXTProvokingVertex.glProvokingVertexEXT(EXTProvokingVertex.GL_FIRST_VERTEX_CONVENTION_EXT);
        assertEquals(EXTProvokingVertex.GL_FIRST_VERTEX_CONVENTION_EXT, GL11C.glGetInteger(EXTProvokingVertex.GL_PROVOKING_VERTEX_EXT));
        EXTProvokingVertex.glProvokingVertexEXT(EXTProvokingVertex.GL_LAST_VERTEX_CONVENTION_EXT);
    }

    /** Converted quads split through the provoking vertex of either convention (GLDeviceTest.aFlatQuadFollowsTheFirstVertexConvention). */
    @Test
    void quadsSayTheyFollowTheProvokingVertexConvention() {
        assertTrue(GL11C.glGetBoolean(EXTProvokingVertex.GL_QUADS_FOLLOW_PROVOKING_VERTEX_CONVENTION_EXT));
    }

    @Test
    void indexedStateAnswersThroughTheExtNames() {
        EXTDrawBuffers2.glEnableIndexedEXT(GL11.GL_BLEND, 1);
        assertTrue(EXTDrawBuffers2.glIsEnabledIndexedEXT(GL11.GL_BLEND, 1));
        EXTDrawBuffers2.glDisableIndexedEXT(GL11.GL_BLEND, 1);
    }

    @Test
    void invalidationIsAHintThatLeavesDataInPlace() {
        final int buffer = GlsmTestBuffers.withData(16);
        try {
            GlsmTestBuffers.put(buffer, new float[] {1f, 2f, 3f, 4f});
            GL43C.glInvalidateBufferSubData(buffer, 0L, 4L);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            final FloatBuffer read = BufferUtils.createFloatBuffer(4);
            GL15C.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, read);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            assertEquals(4f, read.get(3));
        } finally {
            GlsmTestBuffers.delete(buffer);
        }
    }

    @Test
    void multiBindBindsEveryUnit() {
        final int a = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.RED), b = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.GREEN);
        try {
            final IntBuffer names = BufferUtils.createIntBuffer(2).put(0, a).put(1, b);
            GL44C.glBindTextures(0, names);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(b, GL11C.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(a, GL11C.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            GL44C.glBindTextures(0, BufferUtils.createIntBuffer(2));
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glDeleteTextures(a);
            GLSMTextures.glDeleteTextures(b);
        }
    }

    @Test
    void aTextureRegionCanBeRead() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes(1, 2, 3, 4, 5, 6, 7, 8));
            final ByteBuffer read = BufferUtils.createByteBuffer(4);
            GL45C.glGetTextureSubImage(texture, 0, 1, 0, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(5, read.get(0));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void debugAliasesAndMarkersWork() {
        final int depth = GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH);
        EXTDebugMarker.glPushGroupMarkerEXT("group");
        assertEquals(depth + 1, GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH));
        EXTDebugMarker.glPopGroupMarkerEXT();
        final int texture = GlsmHeadlessRig.newNearestTexture();
        EXTDebugLabel.glLabelObjectEXT(GL11.GL_TEXTURE, texture, "label");
        assertEquals("label", NativeGlsmRig.gl().objectLabelOf(GL11.GL_TEXTURE, texture));
        GLSMTextures.glDeleteTextures(texture);
        ARBDebugOutput.glDebugMessageControlARB(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, (IntBuffer) null, true);
    }

    private static int sampled(String sampler, String fetch, int target, int texture) {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform %s s;
            out vec4 fragColor;
            void main() { fragColor = %s; }
            """.formatted(sampler, fetch));
        try {
            GlsmHeadlessRig.clearTargetToBlack();
            GLSMTextures.glBindTexture(target, texture);
            final int vao = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMPrograms.glUseProgram(0);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            return GlsmHeadlessRig.readTarget()[GlsmHeadlessRig.SIZE * GlsmHeadlessRig.SIZE / 2 + GlsmHeadlessRig.SIZE / 2];
        } finally {
            GLSMTextures.glBindTexture(target, 0);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    private static int nearestTexture(int target) {
        final int texture = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(target, texture);
        GLSMTextures.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return texture;
    }

    @Test
    void borderClampReadsTheBorderColor() {
        final int texture = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.RED);
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            assertEquals(GlsmHeadlessRig.BLACK, sampled("sampler2D", "vec4(texture(s, vec2(2.0)).rgb, 1.0)", GL11.GL_TEXTURE_2D, texture));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    private static void assertBorderIsWhite(int texture) {
        assertEquals(0xFFFFFFFF, sampled("sampler2D", "vec4(texture(s, vec2(2.0)).rgb, 1.0)", GL11.GL_TEXTURE_2D, texture));
    }

    private static final float[] WHITE = {1f, 1f, 1f, 1f};

    @Test
    void aSamplerAnswersEveryQueryItWasSet() {
        final int sampler = GL33C.glGenSamplers();
        try {
            GL33C.glSamplerParameterf(sampler, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, 4f);
            assertEquals(4f, GL33C.glGetSamplerParameterf(sampler, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT));
            final int[] border = {7, 8, 9, 10};
            GL33C.glSamplerParameterIiv(sampler, GL11.GL_TEXTURE_BORDER_COLOR, border);
            final int[] read = new int[4];
            GL33C.glGetSamplerParameterIiv(sampler, GL11.GL_TEXTURE_BORDER_COLOR, read);
            assertArrayEquals(border, read);
        } finally {
            GL33C.glDeleteSamplers(sampler);
        }
    }

    @Test
    void aSamplerObjectKeepsItsBorderColor() {
        final int texture = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.RED);
        final int sampler = GL33C.glGenSamplers();
        try {
            GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            GL33C.glSamplerParameterfv(sampler, GL11.GL_TEXTURE_BORDER_COLOR, WHITE);
            final float[] read = new float[4];
            GL33C.glGetSamplerParameterfv(sampler, GL11.GL_TEXTURE_BORDER_COLOR, read);
            assertArrayEquals(WHITE, read);
            GLSMTextures.glBindSampler(0, sampler);
            assertBorderIsWhite(texture);
        } finally {
            GLSMTextures.glBindSampler(0, 0);
            GL33C.glDeleteSamplers(sampler);
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aTextureKeepsABorderColorSetByName() {
        final int texture = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.RED);
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            GL45C.glTextureParameterfv(texture, GL11.GL_TEXTURE_BORDER_COLOR, WHITE);
            assertBorderIsWhite(texture);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void rectangleTexturesAreAddressedInTexels() {
        final int texture = nearestTexture(GL31.GL_TEXTURE_RECTANGLE);
        try {
            GL11C.glTexImage2D(GL31.GL_TEXTURE_RECTANGLE, 0, GL11.GL_RGBA8, 2, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes(255, 0, 0, 255, 0, 255, 0, 255));
            assertEquals(GlsmHeadlessRig.GREEN, sampled("sampler2DRect", "texture(s, vec2(1.5, 0.5))", GL31.GL_TEXTURE_RECTANGLE, texture));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void shadowSamplersCompare() {
        final int texture = nearestTexture(GL11.GL_TEXTURE_2D);
        try {
            GL11C.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 1, 1, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, BufferUtils.createFloatBuffer(1).put(0, 0.5f));
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_ALWAYS);
            assertEquals(GlsmHeadlessRig.RED, sampled("sampler2DShadow", "vec4(texture(s, vec3(0.5, 0.5, 0.25)), 0.0, 0.0, 1.0)", GL11.GL_TEXTURE_2D, texture));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aBufferTextureRangeStartsAtItsOffset() {
        final int buffer = GlsmTestBuffers.withData(32);
        final int texture = GLSMTextures.glGenTextures();
        try {
            GlsmTestBuffers.put(buffer, new float[] {1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f});
            GLSMTextures.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL43C.glTexBufferRange(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer, 16L, 16L);
            assertEquals(GlsmHeadlessRig.GREEN, sampled("samplerBuffer", "texelFetch(s, 0)", GL31.GL_TEXTURE_BUFFER, texture));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
            GlsmTestBuffers.delete(buffer);
        }
    }

    @Test
    void textureEnvironmentCombineSubtracts() {
        final int texture = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.GREEN);
        try {
            GlsmHeadlessRig.clearTargetToBlack();
            GlsmHeadlessRig.bindBaseTexture(texture);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL13.GL_SUBTRACT);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_PRIMARY_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, GL11.GL_TEXTURE);
            GlsmHeadlessRig.texturedQuad(texture, 1f);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, 0xFFFF00FF, "white minus green");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }
}
