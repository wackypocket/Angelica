package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTexEnvGen;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Calls no redirect covers still land in the backend's function table rather than a bad pointer. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDirectGlCallTest {

    @Test
    void aFixedFunctionCallThroughAPointerReachesGlsm() {
        final FloatBuffer color = BufferUtils.createFloatBuffer(3).put(0, 0.25f).put(1, 0.5f).put(2, 0.75f);
        GL11.glColor3fv(color);
        assertEquals(0.5f, GLSMCurrentAttribs.getColor().getGreen(), 0.001f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    @Test
    void aCompressedTextureUploadedDirectlyIsSampled() {
        final ByteBuffer redBlock = BufferUtils.createByteBuffer(8);
        redBlock.put(0, (byte) 0x00).put(1, (byte) 0xF8).put(2, (byte) 0x00).put(3, (byte) 0xF8);
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL13C.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT, 4, 4, 0, redBlock);
            assertEquals(4, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
            GlsmHeadlessRig.bindTarget();
            GlsmHeadlessRig.clearTo(0f, 0f, 0f, 1f);
            GlsmHeadlessRig.texturedQuad(texture, 1f);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), 0xFFFF0000, "the block's color");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void unsignedUniformsRoundTrip() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform uint value;
            out vec4 fragColor;
            void main() { fragColor = vec4(float(value)); }
            """);
        try {
            GLSMPrograms.glUseProgram(program);
            final int location = GLSMPrograms.glGetUniformLocation(program, "value");
            assertTrue(location >= 0);
            GL30C.glUniform1ui(location, 7);
            assertEquals(7, GL30C.glGetUniformui(program, location));
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void clearBufferClearsTheDrawBuffer() {
        GlsmHeadlessRig.bindTarget();
        GlsmHeadlessRig.clearTo(0f, 0f, 0f, 1f);
        GL30C.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0f, 1f, 0f, 1f});
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), 0xFF00FF00, "the cleared color");
    }

    @Test
    void stringsComeBackAsCStrings() {
        assertNotNull(GL11C.glGetString(GL11.GL_VERSION));
        assertNotNull(GL30C.glGetStringi(GL11.GL_EXTENSIONS, 0));
    }

    @Test
    void programQueriesDescribeTheLinkedProgram() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform float tint;
            layout(std140) uniform Settings { vec4 scale; };
            out vec4 fragColor;
            void main() { fragColor = scale * tint; }
            """);
        try {
            assertEquals("tint", GL20C.glGetActiveUniform(program, 0, GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORM_MAX_LENGTH), BufferUtils.createIntBuffer(1), BufferUtils.createIntBuffer(1)));
            final int block = GL31C.glGetUniformBlockIndex(program, "Settings");
            assertEquals(16, GL31C.glGetActiveUniformBlocki(program, block, GL31C.GL_UNIFORM_BLOCK_DATA_SIZE));
            assertEquals("Settings", GL31C.glGetActiveUniformBlockName(program, block));
            final int uniform = GL43C.glGetProgramResourceIndex(program, GL43C.GL_UNIFORM, "tint");
            assertEquals("tint", GL43C.glGetProgramResourceName(program, GL43C.GL_UNIFORM, uniform));
        } finally {
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void labelsAndFormatQueriesAnswer() {
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL43C.glObjectLabel(GL43C.GL_BUFFER, buffer, "scratch");
        assertEquals("scratch", GL43C.glGetObjectLabel(GL43C.GL_BUFFER, buffer));
        GL15C.glDeleteBuffers(buffer);
        assertTrue(GL42C.glGetInternalformati(org.lwjgl.opengl.GL32C.GL_TEXTURE_2D_MULTISAMPLE, GL11.GL_RGBA8, GL42C.GL_NUM_SAMPLE_COUNTS) >= 1);
    }

    @Test
    void aTextureCanBeReadByName() {
        final int texture = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.RED);
        final ByteBuffer out = BufferUtils.createByteBuffer(GlsmHeadlessRig.SIZE * GlsmHeadlessRig.SIZE * 4 * 4);
        GL45C.glGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        assertEquals((byte) 0xFF, out.get(0));
        assertEquals((byte) 0, out.get(1));
        GLSMTextures.glDeleteTextures(texture);
    }

    @Test
    void aMappedBufferReportsItsPointer() {
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, 16L, GL15C.GL_DYNAMIC_DRAW);
        final ByteBuffer mapped = GL15C.glMapBuffer(GL15C.GL_ARRAY_BUFFER, GL15C.GL_WRITE_ONLY);
        assertEquals(MemoryUtil.memAddress(mapped), GL15C.glGetBufferPointer(GL15C.GL_ARRAY_BUFFER, GL15C.GL_BUFFER_MAP_POINTER));
        GL15C.glUnmapBuffer(GL15C.GL_ARRAY_BUFFER);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL15C.glDeleteBuffers(buffer);
    }

    @Test
    void aBufferTextureReadsItsBuffer() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform samplerBuffer data;
            out vec4 fragColor;
            void main() { fragColor = vec4(texelFetch(data, 5).r, texelFetch(data, 5000).r, textureSize(data) == 8192 ? 1.0 : 0.0, 1.0); }
            """);
        final int buffer = GL15C.glGenBuffers();
        final int texture = GLSMTextures.glGenTextures();
        final FloatBuffer values = BufferUtils.createFloatBuffer(8192);
        values.put(5, 1f).put(5000, 1f);
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, buffer);
        GL15C.glBufferData(GL31C.GL_TEXTURE_BUFFER, values, GL15C.GL_DYNAMIC_DRAW);
        GLSMTextures.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
        GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_R32F, buffer);
        try {
            GlsmHeadlessRig.bindTarget();
            GLSMPrograms.glUseProgram(program);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), 0xFFFFFFFF, "index 5, a later row, and the size");

            GL15C.glBufferSubData(GL31C.GL_TEXTURE_BUFFER, 5L * 4L, BufferUtils.createFloatBuffer(1));
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), 0xFF00FFFF, "index 5 after a write");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            GLSMTextures.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
            GLSMTextures.glDeleteTextures(texture);
            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, 0);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    @Test
    void aCompressedTextureReadsBackItsBlocks() {
        final ByteBuffer block = BufferUtils.createByteBuffer(8);
        block.put(0, (byte) 0x00).put(1, (byte) 0xF8).put(2, (byte) 0x00).put(3, (byte) 0xF8);
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GL13C.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT, 4, 4, 0, block);
            final ByteBuffer out = BufferUtils.createByteBuffer(8);
            GL45C.glGetCompressedTextureImage(texture, 0, out);
            assertEquals((byte) 0xF8, out.get(1));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void clearingATextureWritesTheGivenValue() {
        final int texture = GlsmHeadlessRig.createSolidTexture(GlsmHeadlessRig.RED);
        try {
            final ByteBuffer green = BufferUtils.createByteBuffer(4).put(1, (byte) 255).put(3, (byte) 255);
            GL44C.glClearTexImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, green);
            final ByteBuffer blue = BufferUtils.createByteBuffer(4).put(2, (byte) 255).put(3, (byte) 255);
            GL44C.glClearTexSubImage(texture, 0, 0, 0, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, blue);
            final ByteBuffer out = BufferUtils.createByteBuffer(GlsmHeadlessRig.SIZE * GlsmHeadlessRig.SIZE * 16);
            GL45C.glGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals((byte) 255, out.get(2), "the cleared corner");
            assertEquals((byte) 255, out.get(5), "the rest");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aTextureViewStartsWithTheOriginalsContents() {
        final int original = GL11C.glGenTextures();
        final int view = GL11C.glGenTextures();
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, original);
            GL42C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 2, 2);
            final int red = 0xFF0000FF;
            GL11C.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, new int[] {red, red, red, red});
            GL43C.glTextureView(view, GL11.GL_TEXTURE_2D, original, GL11.GL_RGBA8, 0, 1, 0, 1);
            final ByteBuffer out = BufferUtils.createByteBuffer(GlsmHeadlessRig.SIZE * GlsmHeadlessRig.SIZE * 16);
            GL45C.glGetTextureImage(view, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals((byte) 255, out.get(0));
        } finally {
            GLSMTextures.glDeleteTextures(original);
            GLSMTextures.glDeleteTextures(view);
        }
    }

    @Test
    void aProgramPipelineDrawsItsStagesWithTheirUniforms() {
        final int vertex = GL41C.glCreateShaderProgramv(GL20C.GL_VERTEX_SHADER, GlsmHeadlessRig.FULLSCREEN_VERTEX);
        final int fragment = GL41C.glCreateShaderProgramv(GL20C.GL_FRAGMENT_SHADER, """
            #version 330 core
            uniform vec4 tint;
            out vec4 fragColor;
            void main() { fragColor = tint; }
            """);
        final int pipeline = GL41C.glGenProgramPipelines();
        try {
            GL41C.glUseProgramStages(pipeline, GL41C.GL_VERTEX_SHADER_BIT, vertex);
            assertTrue(GL41C.glIsProgramPipeline(pipeline));
            GL41C.glUseProgramStages(pipeline, GL41C.GL_FRAGMENT_SHADER_BIT, fragment);
            assertEquals(vertex, GL41C.glGetProgramPipelinei(pipeline, GL20C.GL_VERTEX_SHADER));
            final int tint = GL20C.glGetUniformLocation(fragment, "tint");
            GL41C.glProgramUniform4f(fragment, tint, 0f, 1f, 0f, 1f);
            GlsmHeadlessRig.bindTarget();
            GL41C.glBindProgramPipeline(pipeline);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "a uniform set on the stage program");

            GL41C.glActiveShaderProgram(pipeline, fragment);
            GL20C.glUniform4f(tint, 0f, 0f, 1f, 1f);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLUE, "a uniform set through the active program");
        } finally {
            GL41C.glBindProgramPipeline(0);
            GL41C.glDeleteProgramPipelines(pipeline);
            GLSMPrograms.glDeleteProgram(vertex);
            GLSMPrograms.glDeleteProgram(fragment);
        }
    }

    @Test
    void glPointSizeReachesAProgramPipeline() {
        assumeTrue(NativeGlsmRig.gl().gpuCaps().pointSizeMax >= 6f, "this device draws points of one size");
        final int vertex = GL41C.glCreateShaderProgramv(GL20C.GL_VERTEX_SHADER, """
            #version 330 core
            void main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }
            """);
        final int fragment = GL41C.glCreateShaderProgramv(GL20C.GL_FRAGMENT_SHADER, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int pipeline = GL41C.glGenProgramPipelines();
        try {
            GL41C.glUseProgramStages(pipeline, GL41C.GL_VERTEX_SHADER_BIT, vertex);
            GL41C.glUseProgramStages(pipeline, GL41C.GL_FRAGMENT_SHADER_BIT, fragment);
            GlsmHeadlessRig.bindTarget();
            GlsmHeadlessRig.clearTo(0f, 0f, 0f, 1f);
            GL41C.glBindProgramPipeline(pipeline);
            GLSMRasterizer.glPointSize(6f);
            GLSMDraws.glDrawArrays(GL11.GL_POINTS, 0, 1);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(GlsmHeadlessRig.SIZE / 2 - 2, GlsmHeadlessRig.SIZE / 2 - 2, 1, 1), GlsmHeadlessRig.GREEN, "two pixels off the point's center");
        } finally {
            GLSMRasterizer.glPointSize(1f);
            GL41C.glBindProgramPipeline(0);
            GL41C.glDeleteProgramPipelines(pipeline);
            GLSMPrograms.glDeleteProgram(vertex);
            GLSMPrograms.glDeleteProgram(fragment);
        }
    }

    @Test
    void subroutinesSwitchTheFunctionCalled() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 400 core
            subroutine vec4 shade(float amount);
            subroutine(shade) vec4 green(float amount) { return vec4(0.0, amount, 0.0, 1.0); }
            subroutine(shade) vec4 blue(float amount) { return vec4(0.0, 0.0, amount, 1.0); }
            subroutine uniform shade chosen;
            out vec4 fragColor;
            void main() { fragColor = chosen(1.0); }
            """);
        try {
            assertEquals(1, GL40C.glGetSubroutineIndex(program, GL20C.GL_FRAGMENT_SHADER, "blue"));
            assertEquals(0, GL40C.glGetSubroutineUniformLocation(program, GL20C.GL_FRAGMENT_SHADER, "chosen"));
            assertEquals("green", GL40C.glGetActiveSubroutineName(program, GL20C.GL_FRAGMENT_SHADER, 0));
            assertEquals(2, GL40C.glGetActiveSubroutineUniformi(program, GL20C.GL_FRAGMENT_SHADER, 0, GL40C.GL_NUM_COMPATIBLE_SUBROUTINES));
            GlsmHeadlessRig.bindTarget();
            GLSMPrograms.glUseProgram(program);
            GL40C.glUniformSubroutinesui(GL20C.GL_FRAGMENT_SHADER, 0);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "the first subroutine");
            GL40C.glUniformSubroutinesui(GL20C.GL_FRAGMENT_SHADER, 1);
            assertEquals(1, GL40C.glGetUniformSubroutineui(GL20C.GL_FRAGMENT_SHADER, 0));
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLUE, "the second subroutine");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void transformFeedbackCapturesAndRedraws() {
        final int vs = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, """
            #version 330 core
            out vec2 captured;
            void main() {
                captured = vec2(float(gl_VertexID), 2.0);
                gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
            }
            """);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL30C.glTransformFeedbackVaryings(program, new CharSequence[] {"captured"}, GL30C.GL_INTERLEAVED_ATTRIBS);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), GL20C.glGetProgramInfoLog(program));
        assertEquals(1, GL20C.glGetProgrami(program, GL30C.GL_TRANSFORM_FEEDBACK_VARYINGS));

        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, 64L, GL15C.GL_DYNAMIC_READ);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        final int feedback = GL40C.glGenTransformFeedbacks();
        final int query = GL15C.glGenQueries();
        try {
            GL40C.glBindTransformFeedback(GL40C.GL_TRANSFORM_FEEDBACK, feedback);
            assertTrue(GL40C.glIsTransformFeedback(feedback));
            GL30C.glBindBufferBase(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, buffer);
            GLSMPrograms.glUseProgram(program);
            GlsmHeadlessRig.bindTarget();
            GL15C.glBeginQuery(GL30C.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, query);
            GL30C.glBeginTransformFeedback(GL11.GL_POINTS);
            GLSMDraws.glDrawArrays(GL11.GL_POINTS, 2, 3);
            GL30C.glEndTransformFeedback();
            GL15C.glEndQuery(GL30C.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
            assertEquals(3, GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT));

            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
            final FloatBuffer out = BufferUtils.createFloatBuffer(6);
            GL15C.glGetBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, out);
            assertEquals(2f, out.get(0), 0.001f, "the first vertex drawn, at the start of the buffer");
            assertEquals(2f, out.get(1), 0.001f);
            assertEquals(4f, out.get(4), 0.001f);

            GLSMDraws.glDrawArrays(GL11.GL_POINTS, 0, 1);
            GL40C.glDrawTransformFeedback(GL11.GL_POINTS, feedback);
        } finally {
            GLSMPrograms.glUseProgram(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL40C.glBindTransformFeedback(GL40C.GL_TRANSFORM_FEEDBACK, 0);
            GL40C.glDeleteTransformFeedbacks(feedback);
            GL15C.glDeleteQueries(query);
            GL15C.glDeleteBuffers(buffer);
            GLSMPrograms.glDeleteProgram(program);
            GLSMPrograms.glDeleteShader(vs);
        }
    }

    @Test
    void shaderObjectsWorkUnderTheirArbNames() {
        final int shader = ARBShaderObjects.glCreateShaderObjectARB(GL20C.GL_FRAGMENT_SHADER);
        ARBShaderObjects.glShaderSourceARB(shader, "#version 330 core\nout vec4 c; void main() { c = vec4(1.0); }\n");
        ARBShaderObjects.glCompileShaderARB(shader);
        assertEquals(1, ARBShaderObjects.glGetObjectParameteriARB(shader, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB));
        final int program = ARBShaderObjects.glCreateProgramObjectARB();
        ARBShaderObjects.glAttachObjectARB(program, shader);
        ARBShaderObjects.glLinkProgramARB(program);
        assertEquals(1, ARBShaderObjects.glGetObjectParameteriARB(program, ARBShaderObjects.GL_OBJECT_LINK_STATUS_ARB));
        ARBShaderObjects.glDeleteObjectARB(program);
        ARBShaderObjects.glDeleteObjectARB(shader);
    }

    @Test
    void lodBiasCanBeSetThroughABufferTexEnv() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            final FloatBuffer bias = BufferUtils.createFloatBuffer(4).put(0, 1.5f);
            GLSMTexEnvGen.glTexEnv(GL14.GL_TEXTURE_FILTER_CONTROL, GL14.GL_TEXTURE_LOD_BIAS, bias);
            assertEquals(1.5f, GLSMTextures.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS), 0.001f);
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void anImageBufferWritesReachItsBufferAtTheBarrier() {
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, """
            #version 430 core
            layout(local_size_x = 1) in;
            layout(r32f, binding = 2) uniform imageBuffer values;
            void main() {
                imageStore(values, 5, vec4(imageLoad(values, 5).r + float(imageSize(values)), 0.0, 0.0, 0.0));
                imageStore(values, 5000, vec4(7.0));
            }
            """);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), GL20C.glGetProgramInfoLog(program));
        final int buffer = GL15C.glGenBuffers();
        final int texture = GLSMTextures.glGenTextures();
        final FloatBuffer values = BufferUtils.createFloatBuffer(8192);
        values.put(5, 1f);
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, buffer);
        GL15C.glBufferData(GL31C.GL_TEXTURE_BUFFER, values, GL15C.GL_DYNAMIC_DRAW);
        GLSMTextures.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
        GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_R32F, buffer);
        try {
            GLSMPrograms.glUseProgram(program);
            GL42C.glBindImageTexture(2, texture, 0, false, 0, GL15C.GL_READ_WRITE, GL30C.GL_R32F);
            GL43C.glDispatchCompute(1, 1, 1);
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            final FloatBuffer out = BufferUtils.createFloatBuffer(8192);
            GL15C.glGetBufferSubData(GL31C.GL_TEXTURE_BUFFER, 0L, out);
            assertEquals(8193f, out.get(5), 0.5f, "the old value plus the buffer's size");
            assertEquals(7f, out.get(5000), 0.001f, "a texel in a later row");
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
            GLSMPrograms.glDeleteShader(shader);
            GLSMTextures.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
            GLSMTextures.glDeleteTextures(texture);
            GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, 0);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    /** Every device claims 4.6 even when it lacks a stage, so the stage extension flags must say truthfully whether that stage links. */
    @Test
    void everyDeviceClaimsTheNewestVersionAndTheStageFlagsTellTheTruth() {
        final GLCapabilities caps = GL.getCapabilities();
        assertTrue(caps.OpenGL46);
        assertEquals(4, GL11.glGetInteger(GL30C.GL_MAJOR_VERSION));
        assertEquals(6, GL11.glGetInteger(GL30C.GL_MINOR_VERSION));
        assertEquals("4.6", GL11.glGetString(GL11.GL_VERSION).substring(0, 3));
        assertEquals(256, GL11.glGetInteger(GL32C.GL_MAX_GEOMETRY_OUTPUT_VERTICES));
        assertEquals(32, GL11.glGetInteger(GL40C.GL_MAX_PATCH_VERTICES));
        assertEquals(caps.GL_ARB_tessellation_shader, RenderSystem.supportsTesselation());
        assertEquals(caps.GL_ARB_geometry_shader4, linksWithStage(GL32C.GL_GEOMETRY_SHADER, """
            #version 450 core
            layout(triangles) in;
            layout(triangle_strip, max_vertices = 3) out;
            void main() {
                for (int i = 0; i < 3; i++) {
                    gl_Position = gl_in[i].gl_Position;
                    EmitVertex();
                }
            }
            """), "geometry shaders");
        assertEquals(caps.GL_ARB_tessellation_shader, linksWithStage(GL40C.GL_TESS_EVALUATION_SHADER, """
            #version 450 core
            layout(triangles) in;
            void main() { gl_Position = gl_in[0].gl_Position * gl_TessCoord.x; }
            """), "tessellation shaders");
    }

    private static boolean linksWithStage(int type, String source) {
        final int vertex = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, "#version 450 core\nvoid main() { gl_Position = vec4(0.0); }\n");
        final int stage = GlTestSupport.compile(type, source);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, stage);
        GL20C.glLinkProgram(program);
        final boolean linked = GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11.GL_TRUE;
        GLSMPrograms.glDeleteProgram(program);
        GLSMPrograms.glDeleteShader(vertex);
        GLSMPrograms.glDeleteShader(stage);
        return linked;
    }

    @Test
    void attachedShadersAreListed() {
        final int vertex = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, GlsmHeadlessRig.FULLSCREEN_VERTEX);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        final IntBuffer count = BufferUtils.createIntBuffer(1), shaders = BufferUtils.createIntBuffer(2);
        GLSMPrograms.glGetAttachedShaders(program, count, shaders);
        assertEquals(1, count.get(0));
        assertEquals(vertex, shaders.get(0));
        GLSMPrograms.glDeleteProgram(program);
        GLSMPrograms.glDeleteShader(vertex);
    }

    @Test
    void aOneDimensionalTextureCopiesFromTheFramebuffer() {
        GlsmHeadlessRig.bindTarget();
        GlsmHeadlessRig.clearTo(0f, 1f, 0f, 1f);
        final int texture = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, texture);
        GLSMTextures.glCopyTexImage1D(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, 0, 0, 8, 0);
        assertEquals(8, GL11C.glGetTexLevelParameteri(GL11.GL_TEXTURE_1D, 0, GL11.GL_TEXTURE_WIDTH));
        GlsmHeadlessRig.clearTo(1f, 0f, 0f, 1f);
        GLSMTextures.glCopyTexSubImage1D(GL11.GL_TEXTURE_1D, 0, 4, 0, 0, 4);
        final ByteBuffer pixels = BufferUtils.createByteBuffer(32);
        GL11C.glGetTexImage(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        assertEquals((byte) 255, pixels.get(1), "first copy, green");
        assertEquals((byte) 255, pixels.get(16), "second copy, red");
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, 0);
        GLSMTextures.glDeleteTextures(texture);
    }

    @Test
    void aCompressedCubeFaceReadsBackAsOneFace() {
        assumeTrue(NativeGlsmRig.gl().gpuCaps().textureCompressionBC);
        final int texture = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, texture);
        final int faceBytes = 4096;
        GL13C.glCompressedTexImage2D(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X, 0, EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, 64, 64, 0, BufferUtils.createByteBuffer(faceBytes));
        assertEquals(faceBytes, GL11C.glGetTexLevelParameteri(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X, 0, GL13C.GL_TEXTURE_COMPRESSED_IMAGE_SIZE));
        final ByteBuffer out = BufferUtils.createByteBuffer(faceBytes + 16);
        for (int i = 0; i < out.capacity(); i++) out.put(i, (byte) 0x5A);
        GL13C.glGetCompressedTexImage(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X, 0, out);
        assertEquals(0, out.get(0), "the face");
        assertEquals(0x5A, out.get(faceBytes), "past the face");
        GLSMTextures.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, 0);
        GLSMTextures.glDeleteTextures(texture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/*\nvoid main() { old }\n*/\n", "#ifdef NOT_DEFINED\nvoid main() { old }\n#else\n"})
    void aMainThatNeverCompilesIsNotTakenForTheRealOne(String before) {
        final String after = before.startsWith("#") ? "#endif\n" : "";
        final int program = GlTestSupport.program("#version 330 core\n" + before + "void main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }\n" + after, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        GLSMPrograms.glDeleteProgram(program);
    }

    @Test
    void anOverlongDebugMessageIsCutToWhatTheLogCanReturn() {
        GL11C.glEnable(GL43C.GL_DEBUG_OUTPUT);
        NativeGlsmRig.gl().warnOnce("x".repeat(3000));
        final ByteBuffer text = BufferUtils.createByteBuffer(GL11C.glGetInteger(GL43C.GL_MAX_DEBUG_MESSAGE_LENGTH));
        final IntBuffer ints = BufferUtils.createIntBuffer(1);
        int read;
        do {
            text.clear();
            read = GL43C.glGetDebugMessageLog(1, ints.duplicate(), ints.duplicate(), ints.duplicate(), ints.duplicate(), ints.duplicate(), text);
        } while (read != 0 && GL11C.glGetInteger(GL43C.GL_DEBUG_LOGGED_MESSAGES) > 0);
        assertEquals(0, GL11C.glGetInteger(GL43C.GL_DEBUG_LOGGED_MESSAGES), "nothing stays stuck in the log");
        GL11C.glDisable(GL43C.GL_DEBUG_OUTPUT);
    }

    @Test
    void indexedStateAnswersPerDrawBuffer() {
        GL30C.glEnablei(GL11.GL_BLEND, 1);
        assertTrue(GL30C.glIsEnabledi(GL11.GL_BLEND, 1));
        assertFalse(GL30C.glIsEnabledi(GL11.GL_BLEND, 0));
        GL30C.glDisablei(GL11.GL_BLEND, 1);
        GL40C.glBlendEquationi(1, GL14C.GL_MAX);
        assertEquals(GL14C.GL_FUNC_ADD, GL11.glGetInteger(GL14C.GL_BLEND_EQUATION));
        GL40C.glBlendEquationi(1, GL14C.GL_FUNC_ADD);
    }

    @Test
    void aRenderbufferDescribesItself() {
        final int renderbuffer = GL30C.glGenRenderbuffers();
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, renderbuffer);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL11.GL_RGBA8, 32, 16);
        assertEquals(32, GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_WIDTH));
        assertEquals(16, GL45C.glGetNamedRenderbufferParameteri(renderbuffer, GL30C.GL_RENDERBUFFER_HEIGHT));
        assertEquals(8, GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_RED_SIZE));
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
        GL30C.glDeleteRenderbuffers(renderbuffer);
    }

    @Test
    void fragmentOutputsReportTheirLocations() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            layout(location = 1) out vec4 second;
            void main() { second = vec4(1.0); }
            """);
        assertEquals(1, GL30C.glGetFragDataLocation(program, "second"));
        assertEquals(-1, GL30C.glGetFragDataLocation(program, "missing"));
        GLSMPrograms.glDeleteProgram(program);
    }

    @Test
    void vertexAttributeQueriesDescribeThePointer() {
        final int vao = GL30C.glGenVertexArrays();
        final int vbo = GL15C.glGenBuffers();
        final int was = GL11.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
        GL20C.glVertexAttribPointer(3, 2, GL11.GL_UNSIGNED_BYTE, true, 8, 4L);
        assertEquals(GL11.GL_TRUE, GL20C.glGetVertexAttribi(3, GL20C.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED));
        assertEquals(vbo, GL20C.glGetVertexAttribi(3, GL15C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
        final PointerBuffer pointer = BufferUtils.createPointerBuffer(1);
        GL20C.glGetVertexAttribPointerv(3, GL20C.GL_VERTEX_ATTRIB_ARRAY_POINTER, pointer);
        assertEquals(4L, pointer.get(0));
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL30C.glBindVertexArray(was);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(vbo);
    }

    @Test
    void anArrayUniformReportsItsStrideOnlyInABuffer() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 330 core
            uniform vec4 values[2];
            layout(std140) uniform Block { vec4 buffered[2]; };
            out vec4 fragColor;
            void main() { fragColor = values[0] + values[1] + buffered[0] + buffered[1]; }
            """);
        final IntBuffer out = BufferUtils.createIntBuffer(1);
        GL43C.glGetProgramResourceiv(program, GL43C.GL_UNIFORM, GL43C.glGetProgramResourceIndex(program, GL43C.GL_UNIFORM, "values"), BufferUtils.createIntBuffer(1).put(0, GL43C.GL_ARRAY_STRIDE), null, out);
        assertEquals(-1, out.get(0));
        GL43C.glGetProgramResourceiv(program, GL43C.GL_UNIFORM, GL43C.glGetProgramResourceIndex(program, GL43C.GL_UNIFORM, "buffered"), BufferUtils.createIntBuffer(1).put(0, GL43C.GL_ARRAY_STRIDE), null, out);
        assertEquals(16, out.get(0));
        GLSMPrograms.glDeleteProgram(program);
    }

    @Test
    void aPipelineGivesWayToAProgramAndComesBackAfterIt() {
        final int vertex = GL41C.glCreateShaderProgramv(GL20C.GL_VERTEX_SHADER, GlsmHeadlessRig.FULLSCREEN_VERTEX);
        final int green = GL41C.glCreateShaderProgramv(GL20C.GL_FRAGMENT_SHADER, "#version 330 core\nout vec4 c; void main() { c = vec4(0.0, 1.0, 0.0, 1.0); }\n");
        final int blue = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, "#version 330 core\nout vec4 c; void main() { c = vec4(0.0, 0.0, 1.0, 1.0); }\n");
        final int pipeline = GL41C.glGenProgramPipelines();
        try {
            GlsmHeadlessRig.bindTarget();
            GL41C.glUseProgramStages(pipeline, GL41C.GL_VERTEX_SHADER_BIT, vertex);
            GL41C.glUseProgramStages(pipeline, GL41C.GL_FRAGMENT_SHADER_BIT, green);
            GL41C.glBindProgramPipeline(pipeline);
            GLSMPrograms.glUseProgram(blue);
            GL41C.glUseProgramStages(pipeline, GL41C.GL_FRAGMENT_SHADER_BIT, green);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLUE, "the current program, despite the pipeline changing");
            GLSMPrograms.glUseProgram(0);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "the pipeline, once the program is gone");
        } finally {
            GL41C.glBindProgramPipeline(0);
            GL41C.glDeleteProgramPipelines(pipeline);
            GLSMPrograms.glDeleteProgram(vertex);
            GLSMPrograms.glDeleteProgram(green);
            GLSMPrograms.glDeleteProgram(blue);
        }
    }

    @Test
    void transformFeedbackLeavesTheGenericStorageBindingAlone() {
        final int vs = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, "#version 330 core\nout float captured; void main() { captured = 1.0; gl_Position = vec4(0.0); }\n");
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL30C.glTransformFeedbackVaryings(program, new CharSequence[] {"captured"}, GL30C.GL_INTERLEAVED_ATTRIBS);
        GL20C.glLinkProgram(program);
        final int capture = GL15C.glGenBuffers(), generic = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, capture);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, 16L, GL15C.GL_DYNAMIC_READ);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, generic);
        GL30C.glBindBufferBase(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, capture);
        GLSMPrograms.glUseProgram(program);
        GL30C.glBeginTransformFeedback(GL11.GL_POINTS);
        assertEquals(generic, GL11.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING));
        GL30C.glEndTransformFeedback();
        assertEquals(generic, GL11.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING));
        GLSMPrograms.glUseProgram(0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        GL15C.glDeleteBuffers(capture);
        GL15C.glDeleteBuffers(generic);
        GLSMPrograms.glDeleteProgram(program);
        GLSMPrograms.glDeleteShader(vs);
    }

    @Test
    void directStateAccessReachesTheNamedBuffer() {
        final int buffer = GL45C.glCreateBuffers();
        final ByteBuffer data = BufferUtils.createByteBuffer(4).put(0, (byte) 7).put(3, (byte) 9);
        GL45C.glNamedBufferData(buffer, data, GL15C.GL_STATIC_DRAW);
        final ByteBuffer out = BufferUtils.createByteBuffer(4);
        GL45C.glGetNamedBufferSubData(buffer, 0L, out);
        assertEquals(9, out.get(3));
        GL15C.glDeleteBuffers(buffer);
    }

    @Test
    void featuresFromLaterVersionsAreAlsoFlaggedAsExtensions() {
        assertTrue(RenderSystem.supportsBufferBlending(), "indexed blending");
        assertTrue(RenderSystem.supportsCompute(), "compute");
        assertTrue(RenderSystem.supportsSSBO(), "storage buffers");
        assertTrue(RenderSystem.supportsImageLoadStore(), "image load and store");
        final GLCapabilities caps = GL.getCapabilities();
        assertTrue(caps.GL_ARB_draw_buffers_blend && caps.GL_ARB_multi_draw_indirect && caps.GL_ARB_buffer_storage && caps.GL_ARB_texture_storage && caps.GL_ARB_sampler_objects, "storage and draws");
        assertTrue(caps.GL_ARB_direct_state_access && caps.GL_ARB_program_interface_query && caps.GL_ARB_texture_view, "state access and queries");
    }

    @Test
    void pixelCallsThroughAPointerUseTheCallersMemory() {
        GlsmHeadlessRig.bindTarget();
        GlsmHeadlessRig.clearTo(1f, 0f, 0f, 1f);
        final ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11C.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals((byte) 255, pixel.get(0));
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            GlsmHeadlessRig.uploadSolidLevel(0, 2, GlsmHeadlessRig.GREEN);
            final ByteBuffer read = BufferUtils.createByteBuffer(16);
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals((byte) 255, read.get(1));
            assertEquals((byte) 0, read.get(0));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void anIndexedDrawThroughAPointerReadsTheCallersIndices() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, GlsmHeadlessRig.COLOR_FRAGMENT);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        final ByteBuffer indices = BufferUtils.createByteBuffer(3).put(0, (byte) 0).put(1, (byte) 1).put(2, (byte) 2);
        final Runnable[] draws = {
            () -> GL11C.glDrawElements(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_BYTE, indices),
            () -> GL12C.glDrawRangeElements(GL11.GL_TRIANGLES, 0, 2, GL11.GL_UNSIGNED_BYTE, indices),
            () -> GL31C.glDrawElementsInstanced(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_BYTE, indices, 1),
            () -> GL32C.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_BYTE, indices, 0),
        };
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GLSMPrograms.glUniform4f(GLSMPrograms.glGetUniformLocation(program, "c"), 0f, 1f, 0f, 1f);
            for (int i = 0; i < draws.length; i++) {
                GlsmHeadlessRig.clearTargetToBlack();
                GLSMPrograms.glUseProgram(program);
                draws[i].run();
                GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.GREEN, "draw " + i);
            }
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    private static int bufferSize(int buffer) {
        return GL45C.glGetNamedBufferParameteri(buffer, GL15C.GL_BUFFER_SIZE);
    }

    @Test
    void bufferCallsWithNoDataKeepTheirSize() {
        final int stored = GL45C.glCreateBuffers(), named = GL45C.glCreateBuffers(), bound = GL15C.glGenBuffers();
        try {
            GL45C.glNamedBufferStorage(stored, 64L, GL44C.GL_DYNAMIC_STORAGE_BIT);
            assertEquals(64, bufferSize(stored));
            GL45C.glNamedBufferData(named, 64L, GL15C.GL_STATIC_DRAW);
            assertEquals(64, bufferSize(named));
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, bound);
            GL44C.glBufferStorage(GL15C.GL_ARRAY_BUFFER, 64L, GL44C.GL_DYNAMIC_STORAGE_BIT);
            assertEquals(64, GL15C.glGetBufferParameteri(GL15C.GL_ARRAY_BUFFER, GL15C.GL_BUFFER_SIZE));
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL15C.glDeleteBuffers(stored);
            GL15C.glDeleteBuffers(named);
            GL15C.glDeleteBuffers(bound);
        }
    }

    @Test
    void bufferStorageWithDataTakesExactlyThatData() {
        final int buffer = GL45C.glCreateBuffers();
        try {
            final ByteBuffer data = BufferUtils.createByteBuffer(8);
            data.put(7, (byte) 42);
            GL45C.glNamedBufferStorage(buffer, data, 0);
            assertEquals(8, bufferSize(buffer));
            final ByteBuffer read = BufferUtils.createByteBuffer(8);
            GL45C.glGetNamedBufferSubData(buffer, 0L, read);
            assertEquals(42, read.get(7));
        } finally {
            GL15C.glDeleteBuffers(buffer);
        }
    }

    private static int unpackBuffer(int... bytes) {
        final ByteBuffer data = BufferUtils.createByteBuffer(bytes.length);
        for (int i = 0; i < bytes.length; i++) data.put(i, (byte) bytes[i]);
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, buffer);
        GL15C.glBufferData(GL21C.GL_PIXEL_UNPACK_BUFFER, data, GL15C.GL_STATIC_DRAW);
        return buffer;
    }

    @Test
    void volumeUploadsComeFromTheUnpackBuffer() {
        final int buffer = unpackBuffer(1, 2, 3, 4, 5, 6, 7, 8);
        final int texture = GLSMTextures.glGenTextures();
        try {
            GLSMTextures.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
            GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, 2, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4L);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            final ByteBuffer read = BufferUtils.createByteBuffer(8);
            GL11C.glGetTexImage(GL12C.GL_TEXTURE_3D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(5, read.get(0), "the offset upload");
            assertEquals(5, read.get(4), "the rest of the image");
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GLSMTextures.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
            GLSMTextures.glDeleteTextures(texture);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    @Test
    void lineUploadsComeFromTheUnpackBuffer() {
        final int buffer = unpackBuffer(1, 2, 3, 4, 5, 6, 7, 8);
        final int texture = GLSMTextures.glGenTextures();
        try {
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, texture);
            GL11C.glTexImage1D(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL11C.glTexSubImage1D(GL11.GL_TEXTURE_1D, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            final ByteBuffer read = BufferUtils.createByteBuffer(8);
            GL11C.glGetTexImage(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(1, read.get(0));
            assertEquals(1, read.get(4));
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_1D, 0);
            GLSMTextures.glDeleteTextures(texture);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    private static int layeredTexture() {
        final ByteBuffer layers = BufferUtils.createByteBuffer(8);
        layers.put(0, (byte) 10).put(4, (byte) 20);
        final int texture = GL45C.glCreateTextures(GL30C.GL_TEXTURE_2D_ARRAY);
        GL45C.glTextureStorage3D(texture, 1, GL11.GL_RGBA8, 1, 1, 2);
        GL45C.glTextureSubImage3D(texture, 0, 0, 0, 0, 1, 1, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, layers);
        return texture;
    }

    @Test
    void readingATextureReturnsEveryLayer() {
        final int texture = layeredTexture();
        try {
            final ByteBuffer read = BufferUtils.createByteBuffer(8);
            GL45C.glGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(10, read.get(0));
            assertEquals(20, read.get(4));
            final ByteBuffer second = BufferUtils.createByteBuffer(4);
            GL45C.glGetTextureSubImage(texture, 0, 0, 0, 1, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, second);
            assertEquals(20, second.get(0));
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void readingATextureFillsABoundPackBuffer() {
        final int texture = layeredTexture();
        final int buffer = GL15C.glGenBuffers();
        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffer);
            GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, 16L, GL15C.GL_STREAM_READ);
            GL45C.nglGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 8, 8L);
            final ByteBuffer read = BufferUtils.createByteBuffer(16);
            GL15C.glGetBufferSubData(GL21C.GL_PIXEL_PACK_BUFFER, 0L, read);
            assertEquals(10, read.get(8));
            assertEquals(20, read.get(12));
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GLSMTextures.glDeleteTextures(texture);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    @Test
    void aBindingDivisorStepsThatBindingAlone() {
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMVertexInput.glBindVertexArray(vao);
            GL43C.glVertexAttribBinding(0, 1);
            GL43C.glVertexAttribBinding(1, 1);
            GL43C.glVertexAttribBinding(2, 0);
            GL43C.glVertexBindingDivisor(0, 1);
            final int[] divisors = NativeGlsmRig.gl().st().vao.bindingDivisor;
            assertEquals(1, divisors[0]);
            assertEquals(0, divisors[1]);
            GL43C.glVertexBindingDivisor(0, 0);
        } finally {
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
        }
    }

    @Test
    void aStorageBlockMovesToTheBindingItIsGiven() {
        final int program = GlTestSupport.program(GlsmHeadlessRig.FULLSCREEN_VERTEX, """
            #version 430 core
            layout(std430, binding = 1) readonly buffer Tint { vec4 tint; };
            out vec4 fragColor;
            void main() { fragColor = tint; }
            """);
        final int red = GlsmTestBuffers.withData(16), green = GlsmTestBuffers.withData(16);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GlsmTestBuffers.put(red, new float[] {1f, 0f, 0f, 1f});
            GlsmTestBuffers.put(green, new float[] {0f, 1f, 0f, 1f});
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, red);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, green);
            GL43C.glShaderStorageBlockBinding(program, GL43C.glGetProgramResourceIndex(program, GL43C.GL_SHADER_STORAGE_BLOCK, "Tint"), 2);
            GlsmHeadlessRig.clearTargetToBlack();
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMPrograms.glUseProgram(0);
            GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.GREEN, "the buffer at the new binding");
        } finally {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, 0);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GlsmTestBuffers.delete(red);
            GlsmTestBuffers.delete(green);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void aBaseInstanceStartsInstancedAttributesThere() {
        drawsTheSecondTint(() -> GL42C.glDrawArraysInstancedBaseInstance(GL11.GL_TRIANGLES, 0, 6, 1, 1));
    }

    @Test
    void anArraysIndirectCommandTakesItsBaseInstance() {
        final int command = GlsmTestBuffers.withData(16);
        try {
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, command);
            GL15C.glBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER, 0L, new int[] {6, 1, 0, 1});
            drawsTheSecondTint(() -> GL40C.glDrawArraysIndirect(GL11.GL_TRIANGLES, 0L));
        } finally {
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
            GLSMBuffers.glDeleteBuffers(command);
        }
    }

    private static void drawsTheSecondTint(Runnable draw) {
        assumeTrue(GL.getCapabilities().GL_ARB_base_instance);
        final int program = GlTestSupport.program("""
            #version 330 core
            layout(location = 0) in vec2 position;
            layout(location = 3) in vec4 tint;
            out vec4 color;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                color = tint;
            }
            """, """
            #version 330 core
            in vec4 color;
            out vec4 fragColor;
            void main() { fragColor = color; }
            """);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        final int quad = GlsmTestBuffers.withData(48), tints = GlsmTestBuffers.withData(32);
        try {
            GlsmHeadlessRig.clearTargetToBlack();
            GLSMVertexInput.glBindVertexArray(vao);
            GlsmTestBuffers.put(quad, new float[] {-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1});
            GlsmTestBuffers.put(tints, new float[] {1, 0, 0, 1, 0, 0, 1, 1});
            GLSMBuffers.glBindBuffer(GL15C.GL_ARRAY_BUFFER, quad);
            GLSMVertexInput.glEnableVertexAttribArray(0);
            GLSMVertexInput.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
            GLSMBuffers.glBindBuffer(GL15C.GL_ARRAY_BUFFER, tints);
            GLSMVertexInput.glEnableVertexAttribArray(3);
            GLSMVertexInput.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, 16, 0L);
            GLSMVertexInput.glVertexAttribDivisor(3, 1);
            GLSMPrograms.glUseProgram(program);
            draw.run();
            GLSMPrograms.glUseProgram(0);
            GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.BLUE, "the second tint");
        } finally {
            GLSMVertexInput.glVertexAttribDivisor(3, 0);
            GLSMBuffers.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GlsmTestBuffers.delete(quad);
            GlsmTestBuffers.delete(tints);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void clearingATextureLevelLeavesTheOthers() {
        final int texture = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        try {
            GL45C.glTextureStorage2D(texture, 2, GL11.GL_RGBA8, 2, 2);
            final ByteBuffer base = BufferUtils.createByteBuffer(16);
            for (int i = 0; i < 16; i++) base.put(i, (byte) 9);
            GL45C.glTextureSubImage2D(texture, 0, 0, 0, 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, base);
            GL45C.glTextureSubImage2D(texture, 1, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, base);
            GL44C.glClearTexImage(texture, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            final ByteBuffer read = BufferUtils.createByteBuffer(16);
            GL45C.glGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(9, read.get(0), "level 0 kept");
            GL45C.glGetTextureImage(texture, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(0, read.get(0), "level 1 cleared");
        } finally {
            GLSMTextures.glDeleteTextures(texture);
        }
    }

    @Test
    void aComputeProgramReportsItsWholeWorkGroupSize() {
        final int program = GL20C.glCreateProgram();
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, "#version 430 core\nlayout(local_size_x = 2, local_size_y = 3, local_size_z = 4) in;\nvoid main() {}\n");
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        final IntBuffer size = BufferUtils.createIntBuffer(3);
        GL20C.glGetProgramiv(program, GL43C.GL_COMPUTE_WORK_GROUP_SIZE, size);
        assertEquals(2, size.get(0));
        assertEquals(3, size.get(1));
        assertEquals(4, size.get(2));
        GL20C.glDeleteShader(shader);
        GL20C.glDeleteProgram(program);
    }

    /** The left triangle is outside at all its vertices. The right one is outside at two of them, so it stays, whole. */
    @Test
    void aCullDistanceDropsOnlyPrimitivesOutsideAtEveryVertex() {
        final int vertex = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, """
            #version 450 core
            out float gl_CullDistance[1];
            const vec2 positions[6] = vec2[](vec2(-1.0, -1.0), vec2(0.0, -1.0), vec2(-1.0, 1.0), vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(0.0, 1.0));
            const float distances[6] = float[](-1.0, -1.0, -1.0, -1.0, 1.0, -1.0);
            void main() {
                gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
                gl_CullDistance[0] = distances[gl_VertexID];
            }
            """);
        final int fragment = GlTestSupport.compile(GL20C.GL_FRAGMENT_SHADER, "#version 450 core\nout vec4 color;\nvoid main() { color = vec4(1.0, 0.0, 0.0, 1.0); }\n");
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS));
        final int vao = GL30C.glGenVertexArrays();
        GlsmHeadlessRig.bindTarget();
        GlsmHeadlessRig.clearTo(0f, 0f, 0f, 1f);
        GL30C.glBindVertexArray(vao);
        GL20C.glUseProgram(program);
        GL11C.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL20C.glUseProgram(0);
        GL30C.glBindVertexArray(0);
        final int size = GlsmHeadlessRig.SIZE;
        GlsmHeadlessRig.assertTargetPixel(size / 8, size / 8, 0xFF000000, "the culled triangle");
        GlsmHeadlessRig.assertTargetPixel(size / 2 + 2, 1, 0xFFFF0000, "where the kept triangle's distance is negative");
        GlsmHeadlessRig.assertTargetPixel(size * 3 / 4, size / 8, 0xFFFF0000, "where the kept triangle's distance is positive");
        GL30C.glDeleteVertexArrays(vao);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        GL20C.glDeleteProgram(program);
    }

    @Test
    void aTessellationProgramReportsWhatItsShadersDeclare() {
        org.junit.jupiter.api.Assumptions.assumeTrue(GL.getCapabilities().GL_ARB_tessellation_shader);
        final int declared = tessellationProgram("layout(quads, fractional_odd_spacing, cw, point_mode) in;");
        assertEquals(4, GL20C.glGetProgrami(declared, GL40C.GL_TESS_CONTROL_OUTPUT_VERTICES));
        assertEquals(GL11.GL_QUADS, GL20C.glGetProgrami(declared, GL40C.GL_TESS_GEN_MODE));
        assertEquals(GL40C.GL_FRACTIONAL_ODD, GL20C.glGetProgrami(declared, GL40C.GL_TESS_GEN_SPACING));
        assertEquals(GL11.GL_CW, GL20C.glGetProgrami(declared, GL40C.GL_TESS_GEN_VERTEX_ORDER));
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(declared, GL40C.GL_TESS_GEN_POINT_MODE));
        GL20C.glDeleteProgram(declared);

        final int defaults = tessellationProgram("layout(triangles) in;");
        assertEquals(GL11.GL_TRIANGLES, GL20C.glGetProgrami(defaults, GL40C.GL_TESS_GEN_MODE));
        assertEquals(GL11.GL_EQUAL, GL20C.glGetProgrami(defaults, GL40C.GL_TESS_GEN_SPACING));
        assertEquals(GL11.GL_CCW, GL20C.glGetProgrami(defaults, GL40C.GL_TESS_GEN_VERTEX_ORDER));
        assertEquals(GL11.GL_FALSE, GL20C.glGetProgrami(defaults, GL40C.GL_TESS_GEN_POINT_MODE));
        GL20C.glDeleteProgram(defaults);
    }

    private static int tessellationProgram(String evaluationLayout) {
        final int vertex = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, "#version 450 core\nvoid main() { gl_Position = vec4(0.0); }\n");
        final int control = GlTestSupport.compile(GL40C.GL_TESS_CONTROL_SHADER, "#version 450 core\nlayout(vertices = 4) out;\nvoid main() { gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position; }\n");
        final int evaluation = GlTestSupport.compile(GL40C.GL_TESS_EVALUATION_SHADER, "#version 450 core\n" + evaluationLayout + "\nvoid main() { gl_Position = gl_in[0].gl_Position; }\n");
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, control);
        GL20C.glAttachShader(program, evaluation);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS));
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(control);
        GL20C.glDeleteShader(evaluation);
        return program;
    }

    @Test
    void anIndexedFragmentBindingOfIndexZeroIsAPlainBinding() {
        final int program = GL20C.glCreateProgram();
        final int fragment = GlTestSupport.compile(GL20C.GL_FRAGMENT_SHADER, "#version 330 core\nout vec4 first;\nout vec4 second;\nvoid main() { first = second = vec4(1.0); }\n");
        final int vertex = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, GlsmHeadlessRig.FULLSCREEN_VERTEX);
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL33C.glBindFragDataLocationIndexed(program, 1, 0, "first");
        GL30C.glBindFragDataLocation(program, 0, "second");
        GL20C.glLinkProgram(program);
        assertEquals(1, GL30C.glGetFragDataLocation(program, "first"));
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        GL20C.glDeleteProgram(program);
    }

    @Test
    void thePatchSizeIsRemembered() {
        assertEquals(3, GL11C.glGetInteger(GL40C.GL_PATCH_VERTICES));
        GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 4);
        assertEquals(4, GL11C.glGetInteger(GL40C.GL_PATCH_VERTICES));
        GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 3);
    }

    @Test
    void aQuadPatchDrawsWithTheSizeAsked() {
        assumeTrue(GL.getCapabilities().GL_ARB_tessellation_shader);
        final int vertex = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, """
            #version 410 core
            void main() { gl_Position = vec4(vec2(gl_VertexID % 2, gl_VertexID / 2) * 2.0 - 1.0, 0.0, 1.0); }
            """);
        final int control = GlTestSupport.compile(GL40C.GL_TESS_CONTROL_SHADER, """
            #version 410 core
            layout(vertices = 4) out;
            void main() {
                gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                gl_TessLevelOuter[0] = gl_TessLevelOuter[1] = gl_TessLevelOuter[2] = gl_TessLevelOuter[3] = 1.0;
                gl_TessLevelInner[0] = gl_TessLevelInner[1] = 1.0;
            }
            """);
        final int evaluation = GlTestSupport.compile(GL40C.GL_TESS_EVALUATION_SHADER, """
            #version 410 core
            layout(quads) in;
            void main() {
                gl_Position = mix(mix(gl_in[0].gl_Position, gl_in[1].gl_Position, gl_TessCoord.x), mix(gl_in[2].gl_Position, gl_in[3].gl_Position, gl_TessCoord.x), gl_TessCoord.y);
            }
            """);
        final int fragment = GlTestSupport.compile(GL20C.GL_FRAGMENT_SHADER, "#version 410 core\nout vec4 c;\nvoid main() { c = vec4(0.0, 1.0, 0.0, 1.0); }\n");
        final int program = GL20C.glCreateProgram();
        for (int shader : new int[] {vertex, control, evaluation, fragment}) GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GlsmHeadlessRig.clearTargetToBlack();
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMPrograms.glUseProgram(program);
            GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 4);
            GLSMDraws.glDrawArrays(GL40C.GL_PATCHES, 0, 4);
            GLSMPrograms.glUseProgram(0);
            GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.GREEN, "the tessellated quad");
        } finally {
            GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 3);
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            for (int shader : new int[] {vertex, control, evaluation, fragment}) GL20C.glDeleteShader(shader);
            GL20C.glDeleteProgram(program);
        }
    }
}
