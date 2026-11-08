package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBSparseBuffer;
import org.lwjgl.opengl.ARBSparseTexture;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRBlendEquationAdvanced;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Features this GPU has but glemu does not offer: none is listed, a call to one does nothing and does not crash, its enums are refused,
 * and a shader that requires one does not compile. A mod that checks before using them falls back; one that does not keeps running.
 */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmUnofferedFeaturesTest {
    private static final String[] UNOFFERED = {"GL_ARB_sparse_texture", "GL_ARB_sparse_texture2", "GL_ARB_sparse_texture_clamp", "GL_ARB_sparse_buffer",
        "GL_KHR_blend_equation_advanced", "GL_KHR_blend_equation_advanced_coherent", "GL_EXT_texture_shadow_lod", "GL_ARB_texture_filter_minmax",
        "GL_ARB_shader_ballot", "GL_ARB_gpu_shader_int64", "GL_ARB_post_depth_coverage"};

    private static final int TEXTURE_REDUCTION_MODE = 0x9366, WEIGHTED_AVERAGE = 0x9367;

    @Test
    void noneIsListed() {
        final int count = GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS);
        final java.util.Set<String> listed = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) listed.add(GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i));
        final String joined = GL11C.glGetString(GL11C.GL_EXTENSIONS);
        for (String e : UNOFFERED) {
            assertFalse(listed.contains(e), e + " is listed");
            assertFalse(joined != null && (" " + joined + " ").contains(" " + e + " "), e + " is in GL_EXTENSIONS");
        }
        final GLCapabilities caps = GL.getCapabilities();
        assertFalse(caps.GL_ARB_sparse_texture);
        assertFalse(caps.GL_ARB_sparse_buffer);
        assertFalse(caps.GL_KHR_blend_equation_advanced);
        assertFalse(caps.GL_ARB_texture_filter_minmax);
        assertFalse(caps.GL_ARB_shader_ballot);
        assertFalse(caps.GL_ARB_gpu_shader_int64);
        assertFalse(caps.GL_ARB_post_depth_coverage);
        assertEquals(0, GL11C.glGetInteger(GL42C.GL_MAX_IMAGE_SAMPLES));
    }

    @Test
    void callingOneAnywayDoesNothing() {
        final int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, 1, GL11C.GL_RGBA8, 64, 64);
        GL11C.glGetError();
        ARBSparseTexture.glTexPageCommitmentARB(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 64, 64, 1, true);
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, 1024, GL15C.GL_STATIC_DRAW);
        ARBSparseBuffer.glBufferPageCommitmentARB(GL15C.GL_ARRAY_BUFFER, 0, 1024, true);
        KHRBlendEquationAdvanced.glBlendBarrierKHR();
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        GL15C.glDeleteBuffers(buffer);
        GL11C.glDeleteTextures(texture);
    }

    @Test
    void theirEnumsAreRefused() {
        GL11C.glGetError();
        final int sampler = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(sampler, TEXTURE_REDUCTION_MODE, WEIGHTED_AVERAGE);
        assertEquals(GL11C.GL_INVALID_ENUM, GL11C.glGetError(), "a sampler's reduction mode");
        final int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, TEXTURE_REDUCTION_MODE, WEIGHTED_AVERAGE);
        assertEquals(GL11C.GL_INVALID_ENUM, GL11C.glGetError(), "a texture's reduction mode");
        GL14C.glBlendEquation(KHRBlendEquationAdvanced.GL_MULTIPLY_KHR);
        assertEquals(GL11C.GL_INVALID_ENUM, GL11C.glGetError(), "an advanced blend equation");
        GL11C.glGetIntegerv(ARBSparseTexture.GL_MAX_SPARSE_TEXTURE_SIZE_ARB, new int[1]);
        assertEquals(GL11C.GL_INVALID_ENUM, GL11C.glGetError(), "a sparse texture limit");
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, ARBSparseTexture.GL_TEXTURE_SPARSE_ARB, GL11C.GL_TRUE);
        assertEquals(GL11C.GL_INVALID_ENUM, GL11C.glGetError(), "a sparse texture");
        GL11C.glDeleteTextures(texture);
        GL33C.glDeleteSamplers(sampler);
    }

    private static boolean compiles(int type, String source) {
        final int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        final boolean ok = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_TRUE;
        if (!ok) assertFalse(GL20C.glGetShaderInfoLog(shader).isBlank(), "a refused shader says why");
        GL20C.glDeleteShader(shader);
        return ok;
    }

    @Test
    void aShaderRequiringOneDoesNotCompile() {
        final String[][] shaders = {
            {"GL_ARB_sparse_texture2", "uniform sampler2D t; layout(std430, binding = 0) buffer B { vec4 o; }; void main() { vec4 v; sparseTextureARB(t, vec2(0.5), v); o = v; }"},
            {"GL_ARB_sparse_texture_clamp", "uniform sampler2D t; layout(std430, binding = 0) buffer B { vec4 o; }; void main() { o = textureClampARB(t, vec2(0.5), 0.0); }"},
            {"GL_ARB_shader_ballot", "layout(std430, binding = 0) buffer B { uint o; }; void main() { o = uint(readFirstInvocationARB(1.0)); }"},
            {"GL_EXT_texture_shadow_lod", "uniform samplerCubeShadow t; layout(std430, binding = 0) buffer B { float o; }; void main() { o = textureLod(t, vec4(1.0), 0.0); }"},
        };
        for (String[] s : shaders) {
            final String source = "#version 450 core\n#extension " + s[0] + " : require\nlayout(local_size_x = 1) in;\n" + s[1] + "\n";
            assertFalse(compiles(GL43C.GL_COMPUTE_SHADER, source), s[0] + " is required and compiles");
        }
        assertFalse(compiles(GL20C.GL_FRAGMENT_SHADER, "#version 450 core\n#extension GL_ARB_post_depth_coverage : require\nlayout(post_depth_coverage) in;\nout vec4 c; void main() { c = vec4(1.0); }\n"),
            "post depth coverage is required and compiles");
        assertFalse(compiles(GL20C.GL_FRAGMENT_SHADER, "#version 450 core\n#extension GL_KHR_blend_equation_advanced : require\nlayout(blend_support_multiply) out;\nout vec4 c; void main() { c = vec4(1.0); }\n"),
            "advanced blending is required and compiles");
        // Asked for with "enable", an extension the context lacks is only a warning, and the shader compiles if it uses nothing from it.
        assertTrue(compiles(GL43C.GL_COMPUTE_SHADER, "#version 450 core\n#extension GL_ARB_sparse_texture2 : enable\nlayout(local_size_x = 1) in;\nvoid main() { }\n"));
        assertFalse(compiles(GL43C.GL_COMPUTE_SHADER, "#version 450 core\n#extension GL_ARB_shader_ballot : enable\nlayout(local_size_x = 1) in;\n"
            + "layout(std430, binding = 0) buffer B { float o; };\nvoid main() { o = readFirstInvocationARB(1.0); }\n"), "an enabled extension glemu lacks still gives its functions");
    }

    /** GL_MAX_IMAGE_SAMPLES is 0, so an image load from a multisample texture is the shader's only way to meet one, and it must not bring the device down. */
    @Test
    void aMultisampleImageDoesNotBreakTheDevice() {
        final int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL32C.GL_TEXTURE_2D_MULTISAMPLE, texture);
        GL43C.glTexStorage2DMultisample(GL32C.GL_TEXTURE_2D_MULTISAMPLE, 4, GL11C.GL_RGBA8, 4, 4, true);
        GL11C.glGetError();
        GL42C.glBindImageTexture(0, texture, 0, false, 0, GL15C.GL_READ_ONLY, GL11C.GL_RGBA8);
        GL11C.glGetError();
        final int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shader, "#version 450 core\nlayout(local_size_x = 1) in;\nlayout(rgba8, binding = 0) readonly uniform image2DMS img;\n"
            + "layout(std430, binding = 0) buffer B { vec4 o; };\nvoid main() { o = imageLoad(img, ivec2(0), 1); }\n");
        GL20C.glCompileShader(shader);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        final int results = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 16, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_TRUE) {
            GL20C.glUseProgram(program);
            GL43C.glDispatchCompute(1, 1, 1);
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL20C.glUseProgram(0);
        }
        final ByteBuffer out = BufferUtils.createByteBuffer(16);
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        GL11C.glFinish();
        assertNotNull(GL11C.glGetString(GL11C.GL_RENDERER), "the device still answers after the dispatch");
        GL20C.glDeleteProgram(program);
        GL20C.glDeleteShader(shader);
        GL15C.glDeleteBuffers(results);
        GL11C.glDeleteTextures(texture);
    }
}
