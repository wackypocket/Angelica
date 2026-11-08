package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** GL_TEXTURE_LOD_BIAS moves every sample down the levels, however its sampler reaches the sample. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmLodBiasTest {
    private static final float[][] LEVELS = {{1, 0, 0, 1}, {0, 1, 0, 1}, {0, 0, 1, 1}};

    private static final String COMPUTE = """
        #version 430 core
        layout(local_size_x = 1) in;
        uniform sampler2D plain;
        uniform sampler2D many[2];
        layout(std430, binding = 0) buffer Out { vec4 r[5]; };
        vec4 through(sampler2D t, vec2 c) { return textureLod(t, c, 0.0); }
        vec4 twice(sampler2D t, vec2 c) { return through(t, c); }
        void main() {
            r[0] = textureLod(plain, vec2(0.5), 0.0);
            r[1] = textureLod(many[1], vec2(0.5), 0.0);
            r[2] = through(plain, vec2(0.5));
            r[3] = twice(many[1], vec2(0.5));
            r[4] = textureLod(many[0], vec2(0.5), 0.0);
        }
        """;

    /** Four texels wide, each level one colour. */
    private static int texture(float bias) {
        final int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        for (int level = 0; level < LEVELS.length; level++) {
            final int size = 4 >> level;
            final FloatBuffer texels = BufferUtils.createFloatBuffer(size * size * 4);
            for (int i = 0; i < size * size; i++) texels.put(LEVELS[level]);
            texels.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA8, size, size, 0, GL11.GL_RGBA, GL11.GL_FLOAT, texels);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, bias);
        return texture;
    }

    private static float[][] run(int program) {
        final int results = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 5 * 16L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        GL43C.glDispatchCompute(1, 1, 1);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(5 * 16);
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        GL15C.glDeleteBuffers(results);
        final float[][] read = new float[5][4];
        for (int k = 0; k < 5; k++) for (int c = 0; c < 4; c++) read[k][c] = Math.round(out.getFloat((k * 4 + c) * 4));
        return read;
    }

    @Test
    void theBiasReachesEverySample() {
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, COMPUTE);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), () -> GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(shader);
        final int once = texture(1f), never = texture(0f), twice = texture(2f);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, once);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, never);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, twice);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "plain"), 0);
        GL20C.glUniform1iv(GL20C.glGetUniformLocation(program, "many"), new int[] {1, 2});
        GL20C.glUseProgram(0);

        float[][] read = run(program);
        assertArrayEquals(LEVELS[1], read[0], "a sampler");
        assertArrayEquals(LEVELS[2], read[1], "an element of a sampler array");
        assertArrayEquals(LEVELS[1], read[2], "a sampler passed to a function");
        assertArrayEquals(LEVELS[2], read[3], "an element passed on through two functions");
        assertArrayEquals(LEVELS[0], read[4], "a sampler with no bias");

        // A sampler object's bias is the one that counts, and a change reaches the next dispatch.
        final int sampler = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL33C.glSamplerParameterf(sampler, GL14.GL_TEXTURE_LOD_BIAS, 2f);
        GL33C.glBindSampler(0, sampler);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, once);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0f);
        read = run(program);
        assertArrayEquals(LEVELS[2], read[0], "a sampler object's bias");
        GL33C.glBindSampler(0, 0);
        read = run(program);
        assertArrayEquals(LEVELS[0], read[0], "the texture's bias, now none");

        GL33C.glDeleteSamplers(sampler);
        GL11.glDeleteTextures(new int[] {once, never, twice});
        GL20C.glDeleteProgram(program);
    }

    private static final String IN_STRUCTS = """
        #version 430 core
        layout(local_size_x = 1) in;
        struct Material { float weight; sampler2D colour; };
        struct Layers { sampler2D level[2]; };
        uniform Material one;
        uniform Material many[2];
        uniform Layers layers;
        layout(std430, binding = 0) buffer Out { vec4 r[5]; };
        vec4 through(sampler2D t, vec2 c) { return textureLod(t, c, 0.0); }
        void main() {
            r[0] = textureLod(one.colour, vec2(0.5), 0.0);
            r[1] = textureLod(many[1].colour, vec2(0.5), 0.0);
            r[2] = through(many[0].colour, vec2(0.5));
            r[3] = textureLod(layers.level[1], vec2(0.5), 0.0);
            r[4] = textureLod(layers.level[0], vec2(0.5), 0.0) * one.weight;
        }
        """;

    @Test
    void theBiasReachesSamplersInStructs() {
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, IN_STRUCTS);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), () -> GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(shader);
        final int once = texture(1f), never = texture(0f), twice = texture(2f);
        final int[] units = {once, twice, never, twice, never};
        for (int unit = 0; unit < units.length; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, units[unit]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "one.colour"), 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "many[1].colour"), 1);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "many[0].colour"), 2);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "layers.level[1]"), 3);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "layers.level[0]"), 4);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "one.weight"), 1f);
        GL20C.glUseProgram(0);
        final float[][] read = run(program);
        assertArrayEquals(LEVELS[1], read[0], "a sampler in a struct");
        assertArrayEquals(LEVELS[2], read[1], "a sampler in an element of a struct array");
        assertArrayEquals(LEVELS[0], read[2], "one passed to a function");
        assertArrayEquals(LEVELS[2], read[3], "an element of a sampler array in a struct");
        assertArrayEquals(LEVELS[0], read[4], "one with no bias");
        GL11.glDeleteTextures(new int[] {once, never, twice});
        GL20C.glDeleteProgram(program);
    }

    /** A program from SPIR-V gets the bias too, as glSpecializeShader turns it back into a source that is lowered like any other. */
    @Test
    void theBiasReachesAProgramFromSpirv() {
        final String source = """
            #version 450 core
            layout(local_size_x = 1) in;
            layout(binding = 0) uniform sampler2D plain;
            layout(std430, binding = 0) buffer Out { vec4 r[5]; };
            void main() { r[0] = textureLod(plain, vec2(0.5), 0.0); }
            """;
        final long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize(), options = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
        org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env(options, org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_opengl, org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_opengl_4_5);
        final long result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, source, org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader, "biased", "main", options);
        assertEquals(org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success, org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result), () -> org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result));
        final ByteBuffer words = org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes(result);
        final ByteBuffer binary = BufferUtils.createByteBuffer(words.remaining());
        binary.put(words).flip();
        org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
        org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(options);
        org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);

        final int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        org.lwjgl.opengl.GL41C.glShaderBinary(new int[] {shader}, org.lwjgl.opengl.GL46C.GL_SHADER_BINARY_FORMAT_SPIR_V, binary);
        org.lwjgl.opengl.GL46C.glSpecializeShader(shader, "main", new int[0], new int[0]);
        assertEquals(GL11.GL_TRUE, GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS), () -> GL20C.glGetShaderInfoLog(shader));
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), () -> GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(shader);
        final int once = texture(1f);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, once);
        assertArrayEquals(LEVELS[1], run(program)[0], "a sampler in a program from SPIR-V");
        GL11.glDeleteTextures(once);
        GL20C.glDeleteProgram(program);
    }
}
