package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** One of everything a call can name, bound and in a state GL accepts, so the only thing wrong with a probed call is what the probe changed. */
final class GlProbeFixture implements AutoCloseable {
    private static final String VS = """
        #version 330 core
        layout(location = 0) in vec4 p;
        void main() { gl_Position = p; }
        """;
    private static final String FS = """
        #version 330 core
        uniform float f1; uniform vec2 f2; uniform vec3 f3; uniform vec4 f4;
        uniform int i1; uniform ivec2 i2; uniform ivec3 i3; uniform ivec4 i4;
        uniform uint u1; uniform uvec2 u2; uniform uvec3 u3; uniform uvec4 u4;
        uniform mat2 m2; uniform mat3 m3; uniform mat4 m4;
        uniform mat2x3 m2x3; uniform mat3x2 m3x2; uniform mat2x4 m2x4; uniform mat4x2 m4x2; uniform mat3x4 m3x4; uniform mat4x3 m4x3;
        uniform sampler2D t;
        layout(std140) uniform U { vec4 v; };
        out vec4 o;
        void main() {
            float s = f1 + f2.x + f3.x + f4.x + float(i1 + i2.x + i3.x + i4.x) + float(u1 + u2.x + u3.x + u4.x);
            s += m2[0][0] + m3[0][0] + m4[0][0] + m2x3[0][0] + m3x2[0][0] + m2x4[0][0] + m4x2[0][0] + m3x4[0][0] + m4x3[0][0];
            o = texture(t, vec2(0.25)) + v + vec4(s);
        }
        """;
    private static final int[] TEXTURE_TARGETS = {GL11C.GL_TEXTURE_1D, GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_3D, GL30C.GL_TEXTURE_1D_ARRAY, GL30C.GL_TEXTURE_2D_ARRAY,
        GL31C.GL_TEXTURE_RECTANGLE, GL13C.GL_TEXTURE_CUBE_MAP};
    private static final int[] BUFFER_TARGETS = {GL15C.GL_ARRAY_BUFFER, GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, GL31C.GL_UNIFORM_BUFFER,
        GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, GL31C.GL_TEXTURE_BUFFER, GL40C.GL_DRAW_INDIRECT_BUFFER};
    private static final int[] PIXEL_STORES = {GL11C.GL_PACK_SWAP_BYTES, GL11C.GL_PACK_LSB_FIRST, GL11C.GL_PACK_ROW_LENGTH, GL12C.GL_PACK_IMAGE_HEIGHT, GL11C.GL_PACK_SKIP_ROWS,
        GL11C.GL_PACK_SKIP_PIXELS, GL12C.GL_PACK_SKIP_IMAGES, GL11C.GL_UNPACK_SWAP_BYTES, GL11C.GL_UNPACK_LSB_FIRST, GL11C.GL_UNPACK_ROW_LENGTH, GL12C.GL_UNPACK_IMAGE_HEIGHT,
        GL11C.GL_UNPACK_SKIP_ROWS, GL11C.GL_UNPACK_SKIP_PIXELS, GL12C.GL_UNPACK_SKIP_IMAGES};
    /** Large enough that no size a probe passes reads or writes past it. Calloc only maps the pages something touches. */
    private static final long SCRATCH_SIZE = 256L * 1024 * 1024;
    private static final int SCRATCH_DIRTY = 64 * 1024;

    final long scratch = MemoryUtil.nmemCallocChecked(1, SCRATCH_SIZE);
    /** What a call only reads. A call's answers must not become the counts and offsets of a later one, which would draw for minutes. */
    final long input = MemoryUtil.nmemCallocChecked(1, SCRATCH_SIZE);
    /** A char** of one string, since a null in one is a crash rather than an error. */
    final long strings = MemoryUtil.nmemCallocChecked(1, 64);
    private final ByteBuffer stringBytes = MemoryUtil.memASCII("x");

    int vs, fs, program;
    final Map<Integer, Integer> textures = new HashMap<>();
    final Map<Integer, Integer> buffers = new HashMap<>();
    int multisample, element, vao, framebuffer, color, renderbuffer, sampler, query, pipeline, feedback;
    long fence;

    GlProbeFixture() {
        MemoryUtil.memPutAddress(strings, MemoryUtil.memAddress(stringBytes));
    }

    /** Linking is the one slow step, so a program lives until a call may have destroyed it. */
    void newProgram() {
        vs = shader(GL20C.GL_VERTEX_SHADER, VS);
        fs = shader(GL20C.GL_FRAGMENT_SHADER, FS);
        program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL30C.glTransformFeedbackVaryings(program, new CharSequence[] {"gl_Position"}, GL30C.GL_INTERLEAVED_ATTRIBS);
        GL20C.glLinkProgram(program);
    }

    private static int shader(int type, String source) {
        final int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, source);
        GL20C.glCompileShader(s);
        return s;
    }

    int uniform(String name) {
        return GL20C.glGetUniformLocation(program, name);
    }

    int uniformBlock() {
        return GL31C.glGetUniformBlockIndex(program, "U");
    }

    void build() {
        MemoryUtil.memSet(scratch, 0, SCRATCH_DIRTY);
        // An overload with no page to say it only reads may still write here, and only ever within the buffer a probe hands it.
        MemoryUtil.memSet(input, 0, SCRATCH_DIRTY);
        for (int store : PIXEL_STORES) GL11C.glPixelStorei(store, 0);

        vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);
        for (int target : BUFFER_TARGETS) {
            final int b = GL15C.glGenBuffers();
            GL15C.glBindBuffer(target, b);
            GL15C.glBufferData(target, 256, GL15C.GL_DYNAMIC_DRAW);
            buffers.put(target, b);
        }
        element = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, element);
        GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, 256, GL15C.GL_DYNAMIC_DRAW);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffers.get(GL15C.GL_ARRAY_BUFFER));
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 4, GL11C.GL_FLOAT, false, 16, 0L);
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, 0, buffers.get(GL31C.GL_UNIFORM_BUFFER));
        GL30C.glBindBufferBase(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, buffers.get(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER));

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        for (int target : TEXTURE_TARGETS) {
            final int t = GL11C.glGenTextures();
            GL11C.glBindTexture(target, t);
            define(target);
            if (target != GL31C.GL_TEXTURE_RECTANGLE) GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            textures.put(target, t);
        }
        multisample = GL11C.glGenTextures();
        GL11C.glBindTexture(GL32C.GL_TEXTURE_2D_MULTISAMPLE, multisample);
        GL32C.glTexImage2DMultisample(GL32C.GL_TEXTURE_2D_MULTISAMPLE, 4, GL11C.GL_RGBA8, 4, 4, true);

        color = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, color);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 16, 16, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures.get(GL11C.GL_TEXTURE_2D));
        renderbuffer = GL30C.glGenRenderbuffers();
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, renderbuffer);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, 16, 16);
        framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, renderbuffer);
        GL11C.glViewport(0, 0, 16, 16);

        sampler = GL33C.glGenSamplers();
        GL33C.glBindSampler(0, sampler);
        query = GL15C.glGenQueries();
        GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, query);
        GL15C.glEndQuery(GL15C.GL_SAMPLES_PASSED);
        pipeline = GL41C.glGenProgramPipelines();
        feedback = GL40C.glGenTransformFeedbacks();
        fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL20C.glUseProgram(program);
    }

    private static void define(int target) {
        switch (target) {
            case GL11C.GL_TEXTURE_1D -> GL11C.glTexImage1D(target, 0, GL11C.GL_RGBA8, 4, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            case GL12C.GL_TEXTURE_3D, GL30C.GL_TEXTURE_2D_ARRAY -> GL12C.glTexImage3D(target, 0, GL11C.GL_RGBA8, 4, 4, 4, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            case GL13C.GL_TEXTURE_CUBE_MAP -> {
                for (int face = 0; face < 6; face++) {
                    GL11C.glTexImage2D(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL11C.GL_RGBA8, 4, 4, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                }
            }
            default -> GL11C.glTexImage2D(target, 0, GL11C.GL_RGBA8, 4, 4, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
    }

    /** Whatever a call began ends here, since a begun query or capture outlives the objects it was given. */
    void tearDown() {
        GL30C.glEndTransformFeedback();
        GL40C.glBindTransformFeedback(GL40C.GL_TRANSFORM_FEEDBACK, 0);
        GL30C.glEndConditionalRender();
        for (int target : new int[] {GL15C.GL_SAMPLES_PASSED, GL33C.GL_ANY_SAMPLES_PASSED, GL30C.GL_PRIMITIVES_GENERATED, GL30C.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, GL33C.GL_TIME_ELAPSED}) {
            GL15C.glEndQuery(target);
        }
        GL20C.glUseProgram(0);
        GL41C.glBindProgramPipeline(0);
        GL33C.glBindSampler(0, 0);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
        GL30C.glBindVertexArray(0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        for (var e : textures.entrySet()) {
            GL11C.glBindTexture(e.getKey(), 0);
            GL11C.glDeleteTextures(e.getValue());
        }
        textures.clear();
        GL11C.glBindTexture(GL32C.GL_TEXTURE_2D_MULTISAMPLE, 0);
        GL11C.glDeleteTextures(multisample);
        GL11C.glDeleteTextures(color);
        for (var e : buffers.entrySet()) {
            GL15C.glBindBuffer(e.getKey(), 0);
            GL15C.glDeleteBuffers(e.getValue());
        }
        buffers.clear();
        GL15C.glDeleteBuffers(element);
        GL30C.glDeleteRenderbuffers(renderbuffer);
        GL30C.glDeleteFramebuffers(framebuffer);
        GL30C.glDeleteVertexArrays(vao);
        GL33C.glDeleteSamplers(sampler);
        GL15C.glDeleteQueries(query);
        GL41C.glDeleteProgramPipelines(pipeline);
        GL40C.glDeleteTransformFeedbacks(feedback);
        GL32C.glDeleteSync(fence);
        // Binding an unseen name makes it an object in a compatibility profile, where a core driver refuses it. The next call's check must not find it.
        for (int unseen : new int[] {-1, 0xBEEF}) {
            GL15C.glDeleteBuffers(unseen);
            GL11C.glDeleteTextures(unseen);
            GL30C.glDeleteRenderbuffers(unseen);
            GL30C.glDeleteFramebuffers(unseen);
            GL15C.glDeleteQueries(unseen);
        }
        // Ending what was never begun complains, which says nothing about the call.
        GlCallProbe.drain();
    }

    void deleteProgram() {
        GL20C.glDeleteProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
    }

    @Override
    public void close() {
        MemoryUtil.nmemFree(scratch);
        MemoryUtil.nmemFree(input);
        MemoryUtil.nmemFree(strings);
        MemoryUtil.memFree(stringBytes);
    }
}
