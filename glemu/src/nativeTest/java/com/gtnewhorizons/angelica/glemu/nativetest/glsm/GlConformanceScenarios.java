package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The same GL calls for a real driver and for our backends, writing what each answers so the two can be compared.
 * Calls use the classes mods compile against, so on a backend they take the redirected path a mod's do.
 * glemu/scripts/compare_conformance.py reads the output.
 */
public final class GlConformanceScenarios {
    public enum Needs { CORE_41, LEGACY }

    interface Body {
        void run() throws Throwable;
    }

    private record Scenario(Needs needs, Body body) {}

    private static final String VS = "#version 330 core\nvoid main() { gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0); }";
    private static final String ATTR_VS = "#version 330 core\nlayout(location = 0) in vec4 p; void main() { gl_Position = p; }";
    private static final String RED_FS = "#version 330 core\nout vec4 f; void main() { f = vec4(1.0, 0.0, 0.0, 1.0); }";
    private static final String COLOR_FS = "#version 330 core\nuniform vec4 c; out vec4 f; void main() { f = c; }";
    private static final String TEX_FS = "#version 330 core\nuniform sampler2D t; out vec4 f; void main() { f = texture(t, vec2(0.25)); }";
    private static final String UBO_FS = "#version 330 core\nlayout(std140) uniform U { vec4 v; }; out vec4 f; void main() { f = v; }";
    static final int SIZE = 16;
    private static final int BOGUS = 0xBEEF;

    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
    private PrintWriter out;
    private String current;

    public static void runAll(Path file, Set<Needs> available, Runnable between) throws IOException {
        Files.createDirectories(file.getParent());
        final GlConformanceScenarios s = new GlConformanceScenarios();
        s.define();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file), true)) {
            s.out = w;
            w.println("# " + GL11.glGetString(GL11.GL_RENDERER) + " | " + GL11.glGetString(GL11.GL_VERSION));
            for (Map.Entry<String, Scenario> e : s.scenarios.entrySet()) {
                if (!available.contains(e.getValue().needs())) continue;
                s.current = e.getKey();
                reset();
                try {
                    e.getValue().body().run();
                } catch (Throwable t) {
                    s.record("threw", t.getClass().getSimpleName());
                }
                drain();
                between.run();
            }
        }
    }

    void record(String key, Object value) {
        out.println(current + "\t" + key + "\t" + value);
    }

    static int drain() {
        int first = GL11.GL_NO_ERROR;
        for (int i = 0; i < 16; i++) {
            final int e = GL11.glGetError();
            if (e == GL11.GL_NO_ERROR) break;
            if (first == GL11.GL_NO_ERROR) first = e;
        }
        return first;
    }

    static String errorName(int e) {
        return switch (e) {
            case GL11.GL_NO_ERROR -> "no_error";
            case GL11.GL_INVALID_ENUM -> "invalid_enum";
            case GL11.GL_INVALID_VALUE -> "invalid_value";
            case GL11.GL_INVALID_OPERATION -> "invalid_operation";
            case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "invalid_framebuffer_operation";
            case GL11.GL_OUT_OF_MEMORY -> "out_of_memory";
            case GL11.GL_STACK_OVERFLOW -> "stack_overflow";
            case GL11.GL_STACK_UNDERFLOW -> "stack_underflow";
            default -> "0x" + Integer.toHexString(e);
        };
    }

    /** What GL says about one call: an error, or a Java exception a driver would never throw. */
    private void call(String key, Body body) {
        drain();
        try {
            body.run();
            record(key, errorName(drain()));
        } catch (Throwable t) {
            record(key, "threw " + t.getClass().getSimpleName());
            drain();
        }
    }

    private void refused(String name, Body body) {
        s(name, () -> {
            setUp();
            call("error", body);
        });
    }

    void s(String name, Body body) {
        scenarios.put(name, new Scenario(Needs.CORE_41, body));
    }

    void legacy(String name, Body body) {
        scenarios.put(name, new Scenario(Needs.LEGACY, body));
    }

    static void reset() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        GL20.glUseProgram(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        for (int target : new int[] {GL15.GL_ARRAY_BUFFER, GL21.GL_PIXEL_PACK_BUFFER, GL21.GL_PIXEL_UNPACK_BUFFER, GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, GL31.GL_UNIFORM_BUFFER}) {
            GL15.glBindBuffer(target, 0);
        }
        for (int cap : new int[] {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_STENCIL_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_CULL_FACE, GL11.GL_POLYGON_OFFSET_FILL, GL31.GL_PRIMITIVE_RESTART}) {
            GL11.glDisable(cap);
        }
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDepthMask(true);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFFFFFFFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glStencilMask(0xFFFFFFFF);
        GL11.glColorMask(true, true, true, true);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClearDepth(1.0);
        GL11.glClearStencil(0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glScissor(0, 0, SIZE, SIZE);
        GL11.glPolygonOffset(0f, 0f);
        GL11.glLineWidth(1f);
        GL11.glLogicOp(GL11.GL_COPY);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glDepthRange(0.0, 1.0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        drain();
    }

    static int setUp() {
        final int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        return vao;
    }

    private static int shader(int type, String source) {
        final int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        return s;
    }

    static int program(String vs, String fs) {
        final int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, shader(GL20.GL_VERTEX_SHADER, vs));
        GL20.glAttachShader(p, shader(GL20.GL_FRAGMENT_SHADER, fs));
        GL20.glLinkProgram(p);
        return p;
    }

    private static int texture(int w, int h) {
        final int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return t;
    }

    private static int buffer(int target, long size) {
        final int b = GL15.glGenBuffers();
        GL15.glBindBuffer(target, b);
        GL15.glBufferData(target, size, GL15.GL_DYNAMIC_DRAW);
        return b;
    }

    /** A framebuffer every pixel scenario draws to, with depth and stencil. */
    static int target() {
        setUp();
        final int f = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture(SIZE, SIZE), 0);
        final int r = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, r);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, SIZE, SIZE);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, r);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        return f;
    }

    void pixelAt(String key, int x, int y) {
        pixel(key, x, y);
    }

    /** The whole target, as one answer that compare_conformance.py checks pixel by pixel. */
    void frame(String key) {
        final ByteBuffer p = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glFinish();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, p);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < p.capacity(); i++) out.append(i == 0 ? "" : ",").append(p.get(i) & 0xFF);
        record("px:" + key, out);
    }

    void depthAt(String key, int x, int y) {
        final FloatBuffer p = BufferUtils.createFloatBuffer(1);
        GL11.glFinish();
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, p);
        record("depth:" + key, Math.round(p.get(0) * 1000f) / 1000f);
    }

    void stencilAt(String key, int x, int y) {
        final ByteBuffer p = BufferUtils.createByteBuffer(4);
        GL11.glFinish();
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, p);
        record("stencil:" + key, p.get(0) & 0xFF);
    }

    void floatsAt(String key, int x, int y) {
        final FloatBuffer p = BufferUtils.createFloatBuffer(4);
        GL11.glFinish();
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, p);
        record("floats:" + key, p.get(0) + " " + p.get(1) + " " + p.get(2) + " " + p.get(3));
    }

    static int programWithVaryings(String vs, String fs, CharSequence[] varyings) {
        final int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, shader(GL20.GL_VERTEX_SHADER, vs));
        GL20.glAttachShader(p, shader(GL20.GL_FRAGMENT_SHADER, fs));
        GL30.glTransformFeedbackVaryings(p, varyings, GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(p);
        return p;
    }

    private void pixel(String key, int x, int y) {
        final ByteBuffer p = BufferUtils.createByteBuffer(4);
        GL11.glFinish();
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, p);
        record("px:" + key, (p.get(0) & 0xFF) + "," + (p.get(1) & 0xFF) + "," + (p.get(2) & 0xFF) + "," + (p.get(3) & 0xFF));
    }

    private void integers(String key, int... pnames) {
        for (int pname : pnames) {
            final int value = GL11.glGetInteger(pname);
            final int error = drain();
            record(key + ":0x" + Integer.toHexString(pname), error == GL11.GL_NO_ERROR ? String.valueOf(value) : errorName(error));
        }
    }

    private void define() {
        defineAcceptedQueries();
        defineStateAfterRefusals();
        defineDefaults();
        defineRoundTrips();
        definePixelConversions();
        new GlConformanceScenariosDrawing(this).define();
        defineInvalidEnums();
        defineInvalidValues();
        defineInvalidOperations();
        defineObjects();
        defineBuffers();
        defineTextures();
        defineFramebuffers();
        definePrograms();
        definePixels();
        defineLegacy();
    }

    /** Whether each constant GL defines is taken as a query, since a query we do not know answers a mod with a wrong 0. */
    private void defineAcceptedQueries() {
        s("queries accepted", () -> {
            final java.util.TreeSet<Integer> constants = new java.util.TreeSet<>();
            for (Class<?> c : new Class<?>[] {GL11.class, GL12.class, GL13.class, GL14.class, GL15.class, GL20.class, GL21.class, GL30.class, GL31.class, GL32.class, GL33.class, org.lwjgl.opengl.GL40.class, org.lwjgl.opengl.GL41.class}) {
                for (java.lang.reflect.Field f : c.getFields()) {
                    if (f.getType() == int.class && f.getName().startsWith("GL_")) constants.add(f.getInt(null));
                }
            }
            drain();
            for (int pname : constants) {
                final IntBuffer v = BufferUtils.createIntBuffer(64);
                GL11.glGetIntegerv(pname, v);
                record("accept:0x" + Integer.toHexString(pname), drain() == GL11.GL_NO_ERROR);
            }
            for (int pname : constants) {
                GL11.glIsEnabled(pname);
                record("accept-cap:0x" + Integer.toHexString(pname), drain() == GL11.GL_NO_ERROR);
            }
        });
    }

    private void defineStateAfterRefusals() {
        s("refused calls keep state", () -> {
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthFunc(BOGUS);
            integers("depth func", GL11.GL_DEPTH_FUNC);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL11.glBlendFunc(BOGUS, BOGUS);
            integers("blend", GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB);
            GL14.glBlendEquation(GL14.GL_MAX);
            GL14.glBlendEquation(BOGUS);
            integers("blend equation", GL20.GL_BLEND_EQUATION_RGB);
            GL11.glCullFace(GL11.GL_FRONT);
            GL11.glCullFace(BOGUS);
            GL11.glFrontFace(GL11.GL_CW);
            GL11.glFrontFace(BOGUS);
            integers("faces", GL11.GL_CULL_FACE_MODE, GL11.GL_FRONT_FACE);
            GL11.glStencilFunc(GL11.GL_LESS, 1, 1);
            GL11.glStencilFunc(BOGUS, 2, 2);
            GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glStencilOp(BOGUS, BOGUS, BOGUS);
            integers("stencil", GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK, GL11.GL_STENCIL_FAIL);
            GL11.glViewport(1, 2, 3, 4);
            GL11.glViewport(5, 6, -7, -8);
            final IntBuffer v = BufferUtils.createIntBuffer(16);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, v);
            record("viewport", v.get(0) + "," + v.get(1) + "," + v.get(2) + "," + v.get(3));
            GL11.glScissor(1, 2, 3, 4);
            GL11.glScissor(5, 6, -7, -8);
            v.clear();
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, v);
            record("scissor", v.get(0) + "," + v.get(1) + "," + v.get(2) + "," + v.get(3));
            GL11.glLineWidth(1f);
            GL11.glLineWidth(-1f);
            record("line width", GL11.glGetFloat(GL11.GL_LINE_WIDTH));
            GL11.glPointSize(2f);
            GL11.glPointSize(-1f);
            record("point size", GL11.glGetFloat(GL11.GL_POINT_SIZE));
            GL11.glPointSize(1f);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 2);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 3);
            integers("alignment", GL11.GL_UNPACK_ALIGNMENT);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 100000);
            integers("active texture", GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            final int texture = texture(4, 4);
            GL11.glBindTexture(BOGUS, 77);
            record("bound texture", GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, BOGUS);
            record("min filter", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
            final int b = buffer(GL15.GL_ARRAY_BUFFER, 16);
            GL15.glBindBuffer(BOGUS, 77);
            record("bound buffer", GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) == b);
            final int p = program(VS, RED_FS);
            GL20.glUseProgram(p);
            GL20.glUseProgram(GL20.glCreateProgram());
            record("program kept", GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == p);
            GL20.glUseProgram(100000);
            record("program kept for an unknown name", GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == p);
            GL20.glUseProgram(0);
            final int vao = setUp();
            GL30.glBindVertexArray(100000);
            record("vertex array kept", GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) == vao);
            final int framebuffer = target();
            GL30.glBindFramebuffer(BOGUS, 77);
            record("framebuffer kept", GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == framebuffer);
            GL11.glDrawBuffer(BOGUS);
            GL11.glReadBuffer(BOGUS);
            integers("color buffers", GL11.GL_DRAW_BUFFER, GL11.GL_READ_BUFFER);
            GL11.glClear(0x1);
            drain();
        });
    }

    private void defineDefaults() {
        s("default state", () -> {
            integers("int", GL13.GL_ACTIVE_TEXTURE, GL15.GL_ARRAY_BUFFER_BINDING, GL20.GL_CURRENT_PROGRAM, GL30.GL_DRAW_FRAMEBUFFER_BINDING, GL30.GL_READ_FRAMEBUFFER_BINDING,
                GL30.GL_RENDERBUFFER_BINDING, GL11.GL_TEXTURE_BINDING_2D, GL11.GL_DEPTH_FUNC, GL11.GL_CULL_FACE_MODE, GL11.GL_FRONT_FACE, GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB,
                GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA, GL20.GL_BLEND_EQUATION_RGB, GL20.GL_BLEND_EQUATION_ALPHA, GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_FAIL,
                GL11.GL_STENCIL_PASS_DEPTH_FAIL, GL11.GL_STENCIL_PASS_DEPTH_PASS, GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_FAIL, GL20.GL_STENCIL_BACK_REF,
                GL11.GL_UNPACK_ALIGNMENT, GL11.GL_PACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS, GL12.GL_UNPACK_IMAGE_HEIGHT,
                GL12.GL_UNPACK_SKIP_IMAGES, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL21.GL_PIXEL_PACK_BUFFER_BINDING, GL21.GL_PIXEL_UNPACK_BUFFER_BINDING,
                GL11.GL_LOGIC_OP_MODE, GL11.GL_LINE_SMOOTH_HINT, GL11.GL_POLYGON_SMOOTH_HINT, GL20.GL_FRAGMENT_SHADER_DERIVATIVE_HINT, GL31.GL_PRIMITIVE_RESTART_INDEX);
            for (int cap : new int[] {GL11.GL_BLEND, GL11.GL_CULL_FACE, GL11.GL_DEPTH_TEST, GL11.GL_DITHER, GL11.GL_COLOR_LOGIC_OP, GL11.GL_LINE_SMOOTH, GL11.GL_POLYGON_SMOOTH,
                GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_POLYGON_OFFSET_LINE, GL11.GL_POLYGON_OFFSET_POINT, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL13.GL_MULTISAMPLE,
                GL13.GL_SAMPLE_ALPHA_TO_COVERAGE, GL13.GL_SAMPLE_ALPHA_TO_ONE, GL13.GL_SAMPLE_COVERAGE, GL31.GL_PRIMITIVE_RESTART, GL32.GL_DEPTH_CLAMP, GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS,
                GL32.GL_PROGRAM_POINT_SIZE, GL30.GL_RASTERIZER_DISCARD}) {
                record("enabled:0x" + Integer.toHexString(cap), GL11.glIsEnabled(cap));
            }
            record("depth writemask", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            record("line width", GL11.glGetFloat(GL11.GL_LINE_WIDTH));
            record("point size", GL11.glGetFloat(GL11.GL_POINT_SIZE));
            record("polygon offset factor", GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR));
            record("polygon offset units", GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
            record("sample coverage value", GL11.glGetFloat(GL13.GL_SAMPLE_COVERAGE_VALUE));
            record("error", errorName(drain()));
        });
    }

    /** Every layout a client can hand GL converts to the texture's format, so what reads back holds the same colour a driver would keep. */
    private void definePixelConversions() {
        final byte[] pattern = {(byte) 0x92, 0x40, (byte) 0xC7, 0x35, 0x6B, (byte) 0xA8, 0x1D, (byte) 0xF0, (byte) 0x81, 0x7F, 0x33, (byte) 0xE6, 0x55, (byte) 0xAA, (byte) 0xCC, 0x10};
        final int[] plain = {GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE, GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT, GL11.GL_INT, GL11.GL_UNSIGNED_INT, GL30.GL_HALF_FLOAT};
        final int[] packed3 = {GL12.GL_UNSIGNED_BYTE_3_3_2, GL12.GL_UNSIGNED_BYTE_2_3_3_REV, GL12.GL_UNSIGNED_SHORT_5_6_5, GL12.GL_UNSIGNED_SHORT_5_6_5_REV};
        final int[] packed4 = {GL12.GL_UNSIGNED_SHORT_4_4_4_4, GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV, GL12.GL_UNSIGNED_SHORT_5_5_5_1, GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV,
            GL12.GL_UNSIGNED_INT_8_8_8_8, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, GL12.GL_UNSIGNED_INT_10_10_10_2, GL12.GL_UNSIGNED_INT_2_10_10_10_REV};
        final int[] formats = {GL11.GL_RED, GL30.GL_RG, GL11.GL_RGB, GL12.GL_BGR, GL11.GL_RGBA, GL12.GL_BGRA};
        for (int internal : new int[] {GL11.GL_RGBA8, GL30.GL_R8, GL30.GL_RG8, GL30.GL_RGBA16F}) {
            s("unpack: into 0x" + Integer.toHexString(internal), () -> {
                final ByteBuffer client = BufferUtils.createByteBuffer(pattern.length);
                client.put(pattern).flip();
                final int t = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                for (int format : formats) {
                    final int components = format == GL11.GL_RED ? 1 : format == GL30.GL_RG ? 2 : format == GL11.GL_RGB || format == GL12.GL_BGR ? 3 : 4;
                    final int[][] types = {plain, components == 3 ? packed3 : components == 4 ? packed4 : new int[0]};
                    for (int[] list : types) {
                        for (int type : list) {
                            final String key = Integer.toHexString(format) + ":" + Integer.toHexString(type);
                            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, 1, 1, 0, format, type, client);
                            record("error " + key, errorName(drain()));
                            final ByteBuffer back = BufferUtils.createByteBuffer(4);
                            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, back);
                            drain();
                            record("px:" + key, (back.get(0) & 0xFF) + "," + (back.get(1) & 0xFF) + "," + (back.get(2) & 0xFF) + "," + (back.get(3) & 0xFF));
                        }
                    }
                }
                GL11.glDeleteTextures(t);
            });
        }
    }

    private void defineRoundTrips() {
        final int[][] blendFactors = {{GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA}, {GL11.GL_DST_COLOR, GL11.GL_ZERO}, {GL14.GL_CONSTANT_COLOR, GL14.GL_ONE_MINUS_CONSTANT_ALPHA}};
        s("set and read: blend", () -> {
            for (int[] f : blendFactors) {
                GL11.glBlendFunc(f[0], f[1]);
                integers("func " + f[0], GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB, GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA);
            }
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ONE);
            integers("separate", GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB, GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA);
            GL14.glBlendEquation(GL14.GL_FUNC_SUBTRACT);
            integers("equation", GL20.GL_BLEND_EQUATION_RGB, GL20.GL_BLEND_EQUATION_ALPHA);
            GL20.glBlendEquationSeparate(GL14.GL_MIN, GL14.GL_MAX);
            integers("equation separate", GL20.GL_BLEND_EQUATION_RGB, GL20.GL_BLEND_EQUATION_ALPHA);
            GL14.glBlendColor(0.25f, 0.5f, 0.75f, 1f);
            final FloatBuffer c = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloatv(GL14.GL_BLEND_COLOR, c);
            record("blend color", c.get(0) + "," + c.get(1) + "," + c.get(2) + "," + c.get(3));
        });
        s("set and read: depth and stencil", () -> {
            for (int f : new int[] {GL11.GL_NEVER, GL11.GL_LEQUAL, GL11.GL_ALWAYS}) {
                GL11.glDepthFunc(f);
                integers("depth func " + f, GL11.GL_DEPTH_FUNC);
            }
            GL11.glDepthMask(false);
            record("depth mask", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            GL11.glDepthMask(true);
            GL11.glDepthRange(0.25, 0.75);
            final FloatBuffer r = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, r);
            record("depth range", r.get(0) + "," + r.get(1));
            GL11.glDepthRange(0.0, 1.0);
            GL11.glStencilFunc(GL11.GL_EQUAL, 3, 7);
            integers("stencil func", GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK, GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_REF, GL20.GL_STENCIL_BACK_VALUE_MASK);
            GL20.glStencilFuncSeparate(GL11.GL_BACK, GL11.GL_LESS, 5, 9);
            integers("stencil separate", GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK, GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_REF, GL20.GL_STENCIL_BACK_VALUE_MASK);
            GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_INCR, GL11.GL_INVERT);
            integers("stencil op", GL11.GL_STENCIL_FAIL, GL11.GL_STENCIL_PASS_DEPTH_FAIL, GL11.GL_STENCIL_PASS_DEPTH_PASS, GL20.GL_STENCIL_BACK_FAIL);
            GL11.glStencilMask(0x55);
            integers("stencil mask", GL11.GL_STENCIL_WRITEMASK, GL20.GL_STENCIL_BACK_WRITEMASK);
        });
        s("set and read: rasterizer", () -> {
            GL11.glCullFace(GL11.GL_FRONT_AND_BACK);
            GL11.glFrontFace(GL11.GL_CW);
            integers("faces", GL11.GL_CULL_FACE_MODE, GL11.GL_FRONT_FACE);
            GL11.glPolygonOffset(1.5f, 2.5f);
            record("offset", GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR) + "," + GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
            GL11.glPolygonOffset(0f, 0f);
            GL11.glPointSize(4f);
            record("point size", GL11.glGetFloat(GL11.GL_POINT_SIZE));
            GL11.glPointSize(1f);
            GL11.glColorMask(true, false, true, false);
            final IntBuffer m = BufferUtils.createIntBuffer(16);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, m);
            record("color mask", m.get(0) + "," + m.get(1) + "," + m.get(2) + "," + m.get(3));
            GL11.glViewport(1, 2, 3, 4);
            final IntBuffer v = BufferUtils.createIntBuffer(16);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, v);
            record("viewport", v.get(0) + "," + v.get(1) + "," + v.get(2) + "," + v.get(3));
            GL11.glScissor(5, 6, 7, 8);
            final IntBuffer b = BufferUtils.createIntBuffer(16);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, b);
            record("scissor", b.get(0) + "," + b.get(1) + "," + b.get(2) + "," + b.get(3));
            GL11.glClearColor(0.25f, 0.5f, 0.75f, 1f);
            final FloatBuffer c = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, c);
            record("clear color", c.get(0) + "," + c.get(1) + "," + c.get(2) + "," + c.get(3));
            GL11.glClearDepth(0.5);
            record("clear depth", GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE));
            GL11.glClearStencil(7);
            integers("clear stencil", GL11.GL_STENCIL_CLEAR_VALUE);
        });
        s("set and read: enables", () -> {
            for (int cap : new int[] {GL11.GL_BLEND, GL11.GL_CULL_FACE, GL11.GL_DEPTH_TEST, GL11.GL_DITHER, GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST,
                GL13.GL_SAMPLE_COVERAGE, GL13.GL_SAMPLE_ALPHA_TO_COVERAGE, GL31.GL_PRIMITIVE_RESTART, GL32.GL_DEPTH_CLAMP, GL30.GL_RASTERIZER_DISCARD, GL11.GL_COLOR_LOGIC_OP}) {
                final boolean was = GL11.glIsEnabled(cap);
                GL11.glEnable(cap);
                record("on:0x" + Integer.toHexString(cap), GL11.glIsEnabled(cap) + "," + GL11.glGetBoolean(cap));
                GL11.glDisable(cap);
                record("off:0x" + Integer.toHexString(cap), GL11.glIsEnabled(cap));
                if (was) GL11.glEnable(cap);
            }
        });
        s("set and read: pixel store", () -> {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 32);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 2);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 3);
            integers("store", GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_SKIP_ROWS);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        });
    }

    private void defineInvalidEnums() {
        refused("enum: glEnable", () -> GL11.glEnable(BOGUS));
        refused("enum: glDisable", () -> GL11.glDisable(BOGUS));
        refused("enum: glIsEnabled", () -> GL11.glIsEnabled(BOGUS));
        refused("enum: glDepthFunc", () -> GL11.glDepthFunc(BOGUS));
        refused("enum: glBlendFunc", () -> GL11.glBlendFunc(BOGUS, GL11.GL_ONE));
        refused("enum: glBlendEquation", () -> GL14.glBlendEquation(BOGUS));
        refused("enum: glCullFace", () -> GL11.glCullFace(BOGUS));
        refused("enum: glFrontFace", () -> GL11.glFrontFace(BOGUS));
        refused("enum: glStencilFunc", () -> GL11.glStencilFunc(BOGUS, 0, 0));
        refused("enum: glStencilOp", () -> GL11.glStencilOp(BOGUS, GL11.GL_KEEP, GL11.GL_KEEP));
        refused("enum: glPolygonMode", () -> GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, BOGUS));
        refused("enum: glHint", () -> GL11.glHint(BOGUS, GL11.GL_NICEST));
        refused("enum: glPixelStorei", () -> GL11.glPixelStorei(BOGUS, 1));
        refused("enum: glGetInteger", () -> GL11.glGetInteger(BOGUS));
        refused("enum: glGetString", () -> GL11.glGetString(BOGUS));
        refused("enum: glBindTexture", () -> GL11.glBindTexture(BOGUS, 1));
        refused("enum: glTexParameteri target", () -> GL11.glTexParameteri(BOGUS, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST));
        refused("enum: glTexParameteri pname", () -> { texture(4, 4); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, BOGUS, GL11.GL_NEAREST); });
        refused("enum: glTexParameteri value", () -> { texture(4, 4); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, BOGUS); });
        refused("enum: glTexImage2D target", () -> GL11.glTexImage2D(BOGUS, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("enum: glTexImage2D format", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, BOGUS, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("enum: glTexImage2D type", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, BOGUS, (ByteBuffer) null));
        refused("enum: glTexImage2D internal format", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, BOGUS, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("enum: glBindBuffer", () -> GL15.glBindBuffer(BOGUS, 1));
        refused("enum: glBufferData target", () -> GL15.glBufferData(BOGUS, 16L, GL15.GL_STATIC_DRAW));
        refused("enum: glBufferData usage", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 16L, BOGUS); });
        refused("enum: glBindFramebuffer", () -> GL30.glBindFramebuffer(BOGUS, 0));
        refused("enum: glCheckFramebufferStatus", () -> GL30.glCheckFramebufferStatus(BOGUS));
        refused("enum: glDrawArrays", () -> { GL20.glUseProgram(program(VS, RED_FS)); GL11.glDrawArrays(BOGUS, 0, 3); });
        refused("enum: glDrawElements type", () -> { GL20.glUseProgram(program(VS, RED_FS)); buffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 12); GL11.glDrawElements(GL11.GL_TRIANGLES, 3, BOGUS, 0L); });
        refused("enum: glCreateShader", () -> GL20.glCreateShader(BOGUS));
        refused("enum: glVertexAttribPointer type", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL20.glVertexAttribPointer(0, 4, BOGUS, false, 0, 0L); });
        refused("enum: glActiveTexture", () -> GL13.glActiveTexture(GL13.GL_TEXTURE0 + 100000));
        refused("enum: glReadPixels format", () -> GL11.glReadPixels(0, 0, 1, 1, BOGUS, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(16)));
        refused("enum: glReadPixels type", () -> GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, BOGUS, BufferUtils.createByteBuffer(16)));
        refused("enum: glDrawBuffer", () -> GL11.glDrawBuffer(BOGUS));
        refused("enum: glReadBuffer", () -> GL11.glReadBuffer(BOGUS));
        refused("enum: glBeginQuery", () -> GL15.glBeginQuery(BOGUS, GL15.glGenQueries()));
        refused("enum: glClear mask", () -> GL11.glClear(0x1));
    }

    private void defineInvalidValues() {
        refused("value: glViewport negative", () -> GL11.glViewport(0, 0, -1, -1));
        refused("value: glScissor negative", () -> GL11.glScissor(0, 0, -1, -1));
        refused("value: glLineWidth zero", () -> GL11.glLineWidth(0f));
        refused("value: glPointSize zero", () -> GL11.glPointSize(0f));
        refused("value: glPixelStorei alignment", () -> GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 3));
        refused("value: glPixelStorei negative", () -> GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, -1));
        refused("value: glDrawArrays count", () -> { GL20.glUseProgram(program(VS, RED_FS)); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, -1); });
        refused("value: glDrawElements count", () -> { GL20.glUseProgram(program(VS, RED_FS)); buffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 12); GL11.glDrawElements(GL11.GL_TRIANGLES, -1, GL11.GL_UNSIGNED_INT, 0L); });
        refused("value: glDrawArraysInstanced count", () -> { GL20.glUseProgram(program(VS, RED_FS)); GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 3, -1); });
        refused("value: glTexImage2D size", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, -1, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("value: glTexImage2D over limit", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) * 2, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("value: glTexImage2D level", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, -1, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("value: glTexImage2D high level", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 40, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("value: glTexImage2D border", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 5, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        refused("value: glTexSubImage2D outside", () -> { texture(4, 4); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 100, 100, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(16)); });
        refused("value: glTexSubImage2D level", () -> { texture(4, 4); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 9, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(16)); });
        refused("value: glRenderbufferStorage size", () -> { GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, GL30.glGenRenderbuffers()); GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, -1, 4); });
        refused("value: glRenderbufferStorage over limit", () -> { GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, GL30.glGenRenderbuffers()); GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE) * 2, 4); });
        refused("value: glBufferData size", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL15.glBufferData(GL15.GL_ARRAY_BUFFER, -16L, GL15.GL_STATIC_DRAW); });
        refused("value: glBufferSubData outside", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 1024L, BufferUtils.createByteBuffer(16)); });
        refused("value: glMapBufferRange outside", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 1024L, 16L, GL30.GL_MAP_WRITE_BIT); });
        refused("value: glCopyBufferSubData outside", () -> { buffer(GL31.GL_COPY_READ_BUFFER, 16); buffer(GL31.GL_COPY_WRITE_BUFFER, 16); GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, 1024L); });
        refused("value: glBindBufferBase index", () -> GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 100000, buffer(GL31.GL_UNIFORM_BUFFER, 16)));
        refused("value: glBindBufferRange size", () -> GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, 0, buffer(GL31.GL_UNIFORM_BUFFER, 16), 0L, 0L));
        refused("value: glVertexAttribPointer index", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL20.glVertexAttribPointer(100000, 4, GL11.GL_FLOAT, false, 0, 0L); });
        refused("value: glVertexAttribPointer size", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL20.glVertexAttribPointer(0, 7, GL11.GL_FLOAT, false, 0, 0L); });
        refused("value: glVertexAttribPointer stride", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, -4, 0L); });
        refused("value: glEnableVertexAttribArray index", () -> GL20.glEnableVertexAttribArray(100000));
        refused("value: glDisableVertexAttribArray index", () -> GL20.glDisableVertexAttribArray(100000));
        refused("value: glVertexAttrib4f index", () -> GL20.glVertexAttrib4f(100000, 0f, 0f, 0f, 1f));
        refused("value: glVertexAttribDivisor index", () -> GL33.glVertexAttribDivisor(100000, 1));
        refused("value: glDrawBuffers count", () -> { target(); GL20.glDrawBuffers(new int[64]); });
        refused("value: glDeleteProgram unknown", () -> GL20.glDeleteProgram(100000));
        refused("value: glDeleteShader unknown", () -> GL20.glDeleteShader(100000));
        refused("value: glLinkProgram unknown", () -> GL20.glLinkProgram(100000));
        refused("value: glCompileShader unknown", () -> GL20.glCompileShader(100000));
        refused("value: glUseProgram unknown", () -> GL20.glUseProgram(100000));
        refused("value: glAttachShader unknown", () -> GL20.glAttachShader(100000, 100001));
        refused("value: glGetUniformLocation unknown", () -> GL20.glGetUniformLocation(100000, "x"));
    }

    private void defineInvalidOperations() {
        refused("operation: glUseProgram unlinked", () -> GL20.glUseProgram(GL20.glCreateProgram()));
        refused("operation: glUseProgram failed link", () -> GL20.glUseProgram(program(VS, "#version 330 core\nthis is not glsl")));
        refused("operation: draw with failed program", () -> { final int p = program(VS, RED_FS); GL20.glUseProgram(p); GL20.glAttachShader(p, shader(GL20.GL_FRAGMENT_SHADER, "bad")); GL20.glLinkProgram(p); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); });
        refused("operation: glGetUniformLocation unlinked", () -> GL20.glGetUniformLocation(GL20.glCreateProgram(), "x"));
        refused("operation: glUniform with no program", () -> GL20.glUniform1f(0, 1f));
        refused("operation: glUniform at bad location", () -> { GL20.glUseProgram(program(VS, COLOR_FS)); GL20.glUniform4f(1000, 1f, 1f, 1f, 1f); });
        refused("operation: glUniform of the wrong size", () -> { final int p = program(VS, COLOR_FS); GL20.glUseProgram(p); GL20.glUniform1f(GL20.glGetUniformLocation(p, "c"), 1f); });
        refused("operation: glUniform of the wrong type", () -> { final int p = program(VS, COLOR_FS); GL20.glUseProgram(p); GL20.glUniform4i(GL20.glGetUniformLocation(p, "c"), 1, 1, 1, 1); });
        refused("operation: sampler uniform as float", () -> { final int p = program(VS, TEX_FS); GL20.glUseProgram(p); GL20.glUniform1f(GL20.glGetUniformLocation(p, "t"), 1f); });
        refused("value: sampler uniform over units", () -> { final int p = program(VS, TEX_FS); GL20.glUseProgram(p); GL20.glUniform1i(GL20.glGetUniformLocation(p, "t"), 100000); });
        refused("operation: shader passed as program", () -> GL20.glLinkProgram(GL20.glCreateShader(GL20.GL_VERTEX_SHADER)));
        refused("operation: program passed as shader", () -> GL20.glCompileShader(GL20.glCreateProgram()));
        refused("operation: glAttachShader twice", () -> { final int p = GL20.glCreateProgram(); final int s = GL20.glCreateShader(GL20.GL_VERTEX_SHADER); GL20.glAttachShader(p, s); GL20.glAttachShader(p, s); });
        refused("operation: glDetachShader not attached", () -> GL20.glDetachShader(GL20.glCreateProgram(), GL20.glCreateShader(GL20.GL_VERTEX_SHADER)));
        refused("operation: glBindTexture other target", () -> { final int t = texture(4, 4); GL11.glBindTexture(GL12.GL_TEXTURE_3D, t); });
        refused("operation: glTexSubImage2D with no image", () -> { final int t = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, t); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(16)); });
        refused("operation: glCopyTexSubImage2D with no image", () -> { final int t = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, t); GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, 1, 1); });
        refused("operation: format and type mismatch", () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGB, GL12.GL_UNSIGNED_INT_8_8_8_8, (ByteBuffer) null));
        refused("operation: glMapBuffer twice", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL15.glMapBuffer(GL15.GL_ARRAY_BUFFER, GL15.GL_WRITE_ONLY); GL15.glMapBuffer(GL15.GL_ARRAY_BUFFER, GL15.GL_WRITE_ONLY); });
        refused("operation: glUnmapBuffer unmapped", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER); });
        refused("operation: glBufferSubData while mapped", () -> { buffer(GL15.GL_ARRAY_BUFFER, 16); GL15.glMapBuffer(GL15.GL_ARRAY_BUFFER, GL15.GL_WRITE_ONLY); GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, BufferUtils.createByteBuffer(4)); });
        refused("operation: glBufferData with no buffer", () -> { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 16L, GL15.GL_STATIC_DRAW); });
        refused("operation: glMapBuffer with no buffer", () -> { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL15.glMapBuffer(GL15.GL_ARRAY_BUFFER, GL15.GL_WRITE_ONLY); });
        refused("operation: glReadPixels past pack buffer", () -> { target(); buffer(GL21.GL_PIXEL_PACK_BUFFER, 16); GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L); });
        refused("operation: upload past unpack buffer", () -> { texture(4, 4); buffer(GL21.GL_PIXEL_UNPACK_BUFFER, 16); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L); });
        refused("operation: glBeginQuery twice", () -> { GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, GL15.glGenQueries()); GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, GL15.glGenQueries()); });
        refused("operation: glEndQuery with none", () -> GL15.glEndQuery(GL15.GL_SAMPLES_PASSED));
        refused("operation: glBindVertexArray unknown", () -> GL30.glBindVertexArray(100000));
        refused("operation: glFramebufferTexture2D on default", () -> GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture(4, 4), 0));
        refused("operation: attaching an unknown texture", () -> { target(); GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 100000, 0); });
        refused("operation: attaching an unknown renderbuffer", () -> { target(); GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, 100000); });
        refused("operation: glDrawBuffers on default", () -> GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0}));
        refused("operation: glGenerateMipmap with no image", () -> { final int t = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, t); GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D); });
        refused("operation: vertex pointer with no buffer", () -> { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 0, 16L); });
        refused("framebuffer: draw on incomplete", () -> { GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GL30.glGenFramebuffers()); GL20.glUseProgram(program(VS, RED_FS)); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); });
        refused("framebuffer: clear on incomplete", () -> { GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GL30.glGenFramebuffers()); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); });
        refused("framebuffer: glReadPixels on incomplete", () -> { GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GL30.glGenFramebuffers()); GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(16)); });
    }

    private void defineObjects() {
        s("objects: names before and after binding", () -> {
            final int t = GL11.glGenTextures();
            record("texture generated", GL11.glIsTexture(t));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            record("texture bound", GL11.glIsTexture(t));
            GL11.glDeleteTextures(t);
            record("texture deleted", GL11.glIsTexture(t));
            integers("binding after delete", GL11.GL_TEXTURE_BINDING_2D);
            final int b = GL15.glGenBuffers();
            record("buffer generated", GL15.glIsBuffer(b));
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, b);
            record("buffer bound", GL15.glIsBuffer(b));
            GL15.glDeleteBuffers(b);
            record("buffer deleted", GL15.glIsBuffer(b));
            integers("binding after delete", GL15.GL_ARRAY_BUFFER_BINDING);
            final int f = GL30.glGenFramebuffers();
            record("framebuffer generated", GL30.glIsFramebuffer(f));
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
            record("framebuffer bound", GL30.glIsFramebuffer(f));
            GL30.glDeleteFramebuffers(f);
            record("framebuffer deleted", GL30.glIsFramebuffer(f));
            integers("binding after delete", GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            final int r = GL30.glGenRenderbuffers();
            record("renderbuffer generated", GL30.glIsRenderbuffer(r));
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, r);
            record("renderbuffer bound", GL30.glIsRenderbuffer(r));
            GL30.glDeleteRenderbuffers(r);
            record("renderbuffer deleted", GL30.glIsRenderbuffer(r));
            integers("binding after delete", GL30.GL_RENDERBUFFER_BINDING);
            final int v = GL30.glGenVertexArrays();
            record("vertex array generated", GL30.glIsVertexArray(v));
            GL30.glBindVertexArray(v);
            record("vertex array bound", GL30.glIsVertexArray(v));
            GL30.glDeleteVertexArrays(v);
            record("vertex array deleted", GL30.glIsVertexArray(v));
            integers("binding after delete", GL30.GL_VERTEX_ARRAY_BINDING);
            final int q = GL15.glGenQueries();
            record("query generated", GL15.glIsQuery(q));
            GL15.glDeleteQueries(q);
            record("query deleted", GL15.glIsQuery(q));
            final int p = GL20.glCreateProgram();
            record("program created", GL20.glIsProgram(p));
            GL20.glDeleteProgram(p);
            record("program deleted", GL20.glIsProgram(p));
            final int sh = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            record("shader created", GL20.glIsShader(sh));
            GL20.glDeleteShader(sh);
            record("shader deleted", GL20.glIsShader(sh));
            record("zero", GL11.glIsTexture(0) + "," + GL15.glIsBuffer(0) + "," + GL30.glIsFramebuffer(0) + "," + GL20.glIsProgram(0));
            record("error", errorName(drain()));
        });
        s("objects: deleting a program in use keeps it", () -> {
            setUp();
            final int p = program(VS, RED_FS);
            GL20.glUseProgram(p);
            GL20.glDeleteProgram(p);
            record("still a program", GL20.glIsProgram(p));
            record("delete status", GL20.glGetProgrami(p, GL20.GL_DELETE_STATUS));
            record("still current", GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == p);
            GL20.glUseProgram(0);
            record("gone after use ends", GL20.glIsProgram(p));
            record("error", errorName(drain()));
        });
    }

    private void defineBuffers() {
        s("buffers: parameters and contents", () -> {
            final int b = buffer(GL15.GL_ARRAY_BUFFER, 16);
            record("size", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE));
            record("usage", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_USAGE));
            record("mapped", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_MAPPED));
            record("access", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_ACCESS));
            final ByteBuffer data = BufferUtils.createByteBuffer(16);
            for (int i = 0; i < 16; i++) data.put(i, (byte) i);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, data);
            final ByteBuffer back = BufferUtils.createByteBuffer(4);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 8L, back);
            record("read back", back.get(0) + "," + back.get(3));
            final ByteBuffer map = GL15.glMapBuffer(GL15.GL_ARRAY_BUFFER, GL15.GL_READ_WRITE);
            record("map", map == null ? "null" : map.remaining() + "," + map.get(12));
            record("mapped while mapped", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_MAPPED));
            if (map != null) map.put(0, (byte) 99);
            record("unmap", GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER));
            final ByteBuffer first = BufferUtils.createByteBuffer(1);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, first);
            record("written through map", first.get(0));
            final ByteBuffer range = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 4L, 8L, GL30.GL_MAP_READ_BIT);
            record("range", range == null ? "null" : range.remaining() + "," + range.get(0));
            record("range offset", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL30.GL_BUFFER_MAP_OFFSET));
            record("range length", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL30.GL_BUFFER_MAP_LENGTH));
            GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer(GL31.GL_COPY_WRITE_BUFFER, 16));
            GL31.glCopyBufferSubData(GL15.GL_ARRAY_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 4L, 0L, 4L);
            final ByteBuffer copied = BufferUtils.createByteBuffer(1);
            GL15.glGetBufferSubData(GL31.GL_COPY_WRITE_BUFFER, 0L, copied);
            record("copied", copied.get(0));
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 0L, GL15.GL_STATIC_DRAW);
            record("size zero", GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE));
            GL15.glDeleteBuffers(b);
            record("error", errorName(drain()));
        });
    }

    private void defineTextures() {
        s("textures: defaults and parameters", () -> {
            final int t = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            for (int pname : new int[] {GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R, GL12.GL_TEXTURE_BASE_LEVEL, GL12.GL_TEXTURE_MAX_LEVEL,
                GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_TEXTURE_COMPARE_FUNC, GL33.GL_TEXTURE_SWIZZLE_R, GL33.GL_TEXTURE_SWIZZLE_A}) {
                record("default:0x" + Integer.toHexString(pname), GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, pname));
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 4);
            record("set", GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER) + "," + GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S) + "," + GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL));
            record("no image width", GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
            record("no image format", GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
            record("error", errorName(drain()));
        });
        s("textures: levels and sizes", () -> {
            texture(16, 8);
            for (int level = 0; level < 6; level++) {
                record("level " + level, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH) + "," + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT));
            }
            record("internal format", GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
            record("red bits", GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_RED_SIZE));
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            for (int level = 0; level < 6; level++) {
                record("mip " + level, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH) + "," + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT));
            }
            record("level past the limit error", errorName(drain()));
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, -1, GL11.GL_TEXTURE_WIDTH);
            record("negative level error", errorName(drain()));
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, BOGUS);
            record("bogus pname error", errorName(drain()));
        });
        s("textures: upload and read back", () -> {
            texture(2, 2);
            final ByteBuffer in = BufferUtils.createByteBuffer(16);
            for (int i = 0; i < 16; i++) in.put(i, (byte) (i * 16));
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, in);
            final ByteBuffer back = BufferUtils.createByteBuffer(16);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, back);
            record("rgba", (back.get(0) & 0xFF) + "," + (back.get(5) & 0xFF) + "," + (back.get(15) & 0xFF));
            back.clear();
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, back);
            record("bgra", (back.get(0) & 0xFF) + "," + (back.get(2) & 0xFF));
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            final ByteBuffer rgb = BufferUtils.createByteBuffer(3);
            rgb.put(0, (byte) 10).put(1, (byte) 20).put(2, (byte) 30);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, rgb);
            back.clear();
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, back);
            record("rgb upload", (back.get(0) & 0xFF) + "," + (back.get(1) & 0xFF) + "," + (back.get(2) & 0xFF) + "," + (back.get(3) & 0xFF));
            record("error", errorName(drain()));
        });
        s("textures: formats keep their internal format", () -> {
            for (int internal : new int[] {GL11.GL_RGBA8, GL11.GL_RGB8, GL30.GL_R8, GL30.GL_RG8, GL30.GL_RGBA16F, GL30.GL_R32F, GL30.GL_RGBA32F, GL21.GL_SRGB8_ALPHA8, GL14.GL_DEPTH_COMPONENT24, GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_COMPONENT32F, GL30.GL_R11F_G11F_B10F, GL11.GL_RGB10_A2}) {
                final int t = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
                final boolean depth = internal == GL14.GL_DEPTH_COMPONENT24 || internal == GL30.GL_DEPTH_COMPONENT32F;
                final boolean stencil = internal == GL30.GL_DEPTH24_STENCIL8;
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, 4, 4, 0, depth ? GL11.GL_DEPTH_COMPONENT : stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_RGBA, stencil ? GL30.GL_UNSIGNED_INT_24_8 : depth ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                record("0x" + Integer.toHexString(internal), errorName(drain()) + "," + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
            }
        });
    }

    private void status(String key) {
        record(key, "0x" + Integer.toHexString(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)));
    }

    private void defineFramebuffers() {
        s("framebuffers: status", () -> {
            status("default");
            final int f = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
            status("nothing attached");
            final int empty = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, empty);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, empty, 0);
            status("texture with no image");
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture(4, 4), 0);
            status("one color");
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, texture(8, 8), 0);
            status("two sizes");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, GL11.glGenTextures());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 4, 4, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), 0);
            status("depth texture as color");
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture(4, 4), 0);
            status("color texture as depth");
            record("error", errorName(drain()));
        });
        s("framebuffers: attachment queries", () -> {
            target();
            record("color type", GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
            record("depth type", GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
            record("unused type", GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
            record("red bits", GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE));
            record("depth bits", GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE));
            record("stencil bits", GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
            integers("draw buffers", GL11.GL_DRAW_BUFFER, GL20.GL_DRAW_BUFFER0, GL20.GL_DRAW_BUFFER1, GL11.GL_READ_BUFFER);
            record("error", errorName(drain()));
        });
        s("framebuffers: renderbuffer parameters", () -> {
            final int r = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, r);
            record("before storage", GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH));
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, 8, 4);
            record("size", GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH) + "," + GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT));
            record("format", GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT));
            record("red bits", GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_RED_SIZE));
            record("error", errorName(drain()));
        });
    }

    private void definePrograms() {
        s("programs: compile and link results", () -> {
            final int good = shader(GL20.GL_VERTEX_SHADER, VS);
            record("good compile", GL20.glGetShaderi(good, GL20.GL_COMPILE_STATUS));
            record("shader type", GL20.glGetShaderi(good, GL20.GL_SHADER_TYPE));
            final int bad = shader(GL20.GL_VERTEX_SHADER, "#version 330 core\nthis is not glsl");
            record("bad compile", GL20.glGetShaderi(bad, GL20.GL_COMPILE_STATUS));
            record("bad compile has a log", GL20.glGetShaderi(bad, GL20.GL_INFO_LOG_LENGTH) > 0);
            final int p = program(VS, COLOR_FS);
            record("link", GL20.glGetProgrami(p, GL20.GL_LINK_STATUS));
            record("attached", GL20.glGetProgrami(p, GL20.GL_ATTACHED_SHADERS));
            record("active uniforms", GL20.glGetProgrami(p, GL20.GL_ACTIVE_UNIFORMS));
            record("location", GL20.glGetUniformLocation(p, "c") >= 0);
            record("no such uniform", GL20.glGetUniformLocation(p, "missing"));
            final int empty = GL20.glCreateProgram();
            record("never linked", GL20.glGetProgrami(empty, GL20.GL_LINK_STATUS));
            GL20.glLinkProgram(empty);
            record("empty link", GL20.glGetProgrami(empty, GL20.GL_LINK_STATUS));
            final int failed = program(VS, "#version 330 core\nthis is not glsl");
            record("failed link", GL20.glGetProgrami(failed, GL20.GL_LINK_STATUS));
            record("failed link has a log", GL20.glGetProgrami(failed, GL20.GL_INFO_LOG_LENGTH) > 0);
            final int attr = program(ATTR_VS, RED_FS);
            record("active attributes", GL20.glGetProgrami(attr, GL20.GL_ACTIVE_ATTRIBUTES));
            record("attribute location", GL20.glGetAttribLocation(attr, "p"));
            record("no such attribute", GL20.glGetAttribLocation(attr, "missing"));
            final int block = program(VS, UBO_FS);
            record("uniform block index", GL31.glGetUniformBlockIndex(block, "U"));
            record("no such block", GL31.glGetUniformBlockIndex(block, "missing"));
            record("active blocks", GL20.glGetProgrami(block, GL31.GL_ACTIVE_UNIFORM_BLOCKS));
            record("block size", GL31.glGetActiveUniformBlocki(block, 0, GL31.GL_UNIFORM_BLOCK_DATA_SIZE));
            record("error", errorName(drain()));
        });
        s("programs: uniform values read back", () -> {
            final int p = program(VS, COLOR_FS);
            GL20.glUseProgram(p);
            final int c = GL20.glGetUniformLocation(p, "c");
            final FloatBuffer v = BufferUtils.createFloatBuffer(4);
            GL20.glGetUniformfv(p, c, v);
            record("default", v.get(0) + "," + v.get(3));
            GL20.glUniform4f(c, 0.25f, 0.5f, 0.75f, 1f);
            GL20.glGetUniformfv(p, c, v);
            record("set", v.get(0) + "," + v.get(1) + "," + v.get(2) + "," + v.get(3));
            GL20.glUniform4f(-1, 9f, 9f, 9f, 9f);
            record("location -1 is ignored", errorName(drain()));
            record("error", errorName(drain()));
        });
        s("vertex arrays: attribute state", () -> {
            setUp();
            final int b = buffer(GL15.GL_ARRAY_BUFFER, 64);
            record("enabled default", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            record("size default", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
            record("type default", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE));
            GL20.glVertexAttribPointer(0, 3, GL11.GL_SHORT, true, 8, 4L);
            GL20.glEnableVertexAttribArray(0);
            GL33.glVertexAttribDivisor(0, 2);
            record("enabled", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            record("size", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
            record("type", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE));
            record("normalized", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED));
            record("stride", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
            record("divisor", GL20.glGetVertexAttribi(0, GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR));
            record("buffer", GL20.glGetVertexAttribi(0, GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING) == b);
            setUp();
            record("a new array starts clean", GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            record("error", errorName(drain()));
        });
    }

    private void definePixels() {
        s("pixels: clear", () -> {
            target();
            GL11.glClearColor(1f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            pixel("cleared", 8, 8);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, 4, 4);
            GL11.glClearColor(0f, 1f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            pixel("inside scissor", 2, 2);
            pixel("outside scissor", 8, 8);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(false, false, true, false);
            GL11.glClearColor(0f, 0f, 1f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            pixel("color mask", 8, 8);
        });
        s("pixels: draw", () -> {
            target();
            GL20.glUseProgram(program(VS, RED_FS));
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("drawn", 8, 8);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glViewport(0, 0, 4, 4);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("inside viewport", 2, 2);
            pixel("outside viewport", 8, 8);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_FRONT);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("culled", 8, 8);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("not culled", 8, 8);
        });
        s("pixels: depth", () -> {
            target();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL20.glUseProgram(program(VS, RED_FS));
            GL11.glDepthFunc(GL11.GL_NEVER);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("never", 8, 8);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("less", 8, 8);
            final FloatBuffer depth = BufferUtils.createFloatBuffer(1);
            GL11.glReadPixels(8, 8, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            record("depth value", Math.round(depth.get(0) * 1000f));
        });
        s("pixels: stencil", () -> {
            target();
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilFunc(GL11.GL_ALWAYS, 5, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
            GL20.glUseProgram(program(VS, RED_FS));
            GL11.glColorMask(false, false, false, false);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL11.glColorMask(true, true, true, true);
            final ByteBuffer stencil = BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(8, 8, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, stencil);
            record("stencil value", stencil.get(0));
            GL11.glStencilFunc(GL11.GL_NOTEQUAL, 5, 0xFF);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("rejected", 8, 8);
            GL11.glStencilFunc(GL11.GL_EQUAL, 5, 0xFF);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("passed", 8, 8);
        });
        s("pixels: blend", () -> {
            target();
            final int p = program(VS, COLOR_FS);
            GL20.glUseProgram(p);
            final int c = GL20.glGetUniformLocation(p, "c");
            GL11.glClearColor(0f, 0f, 1f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUniform4f(c, 1f, 0f, 0f, 0.5f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("alpha", 8, 8);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL20.glUniform4f(c, 0f, 0.5f, 0f, 0f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("additive", 8, 8);
            GL14.glBlendEquation(GL14.GL_MAX);
            GL20.glUniform4f(c, 1f, 1f, 0f, 0f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("max", 8, 8);
        });
        s("pixels: textured", () -> {
            target();
            final int t = texture(2, 2);
            final ByteBuffer in = BufferUtils.createByteBuffer(16);
            for (int i = 0; i < 16; i += 4) in.put(i, (byte) 0).put(i + 1, (byte) 255).put(i + 2, (byte) 0).put(i + 3, (byte) 255);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, in);
            final int p = program(VS, TEX_FS);
            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "t"), 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("texture", 8, 8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, GL11.glGenTextures());
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("incomplete texture reads black", 8, 8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        });
        s("pixels: uniform buffer", () -> {
            target();
            final int p = program(VS, UBO_FS);
            GL20.glUseProgram(p);
            final int b = buffer(GL31.GL_UNIFORM_BUFFER, 16);
            final FloatBuffer v = BufferUtils.createFloatBuffer(4).put(0, 0f).put(1, 1f).put(2, 0f).put(3, 1f);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, v);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, b);
            GL31.glUniformBlockBinding(p, 0, 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            pixel("from the buffer", 8, 8);
            record("error", errorName(drain()));
        });
        s("pixels: indexed and instanced", () -> {
            target();
            final FloatBuffer positions = BufferUtils.createFloatBuffer(12).put(new float[] {-1f, -1f, 0f, 1f, 3f, -1f, 0f, 1f, -1f, 3f, 0f, 1f}).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, GL15.glGenBuffers());
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, positions, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(0);
            final IntBuffer indices = BufferUtils.createIntBuffer(3).put(new int[] {0, 1, 2}).flip();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, GL15.glGenBuffers());
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            GL20.glUseProgram(program(ATTR_VS, RED_FS));
            GL11.glDrawElements(GL11.GL_TRIANGLES, 3, GL11.GL_UNSIGNED_INT, 0L);
            pixel("indexed", 8, 8);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, 3, GL11.GL_UNSIGNED_INT, 0L, 2);
            pixel("instanced", 8, 8);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 3, 0);
            pixel("zero instances", 8, 8);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 0);
            pixel("zero vertices", 8, 8);
            record("error", errorName(drain()));
        });
        s("pixels: read and blit", () -> {
            final int a = target();
            GL11.glClearColor(1f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            final int b = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, b);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture(SIZE, SIZE), 0);
            GL30.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            record("blit error", errorName(drain()));
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, b);
            pixel("blitted", 8, 8);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, a);
            GL30.glBlitFramebuffer(0, 0, 1, 1, 0, 0, 1, 1, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_LINEAR);
            record("depth blit with a linear filter", errorName(drain()));
            GL30.glBlitFramebuffer(0, 0, 1, 1, 0, 0, 1, 1, 0x1, GL11.GL_NEAREST);
            record("blit with a bad mask", errorName(drain()));
            GL30.glBlitFramebuffer(0, 0, 1, 1, 0, 0, 1, 1, GL11.GL_COLOR_BUFFER_BIT, BOGUS);
            record("blit with a bad filter", errorName(drain()));
        });
    }

    private void defineLegacy() {
        legacy("legacy: immediate mode", () -> {
            target();
            GL11.glColor4f(1f, 0f, 0f, 1f);
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2f(-1f, -1f);
            GL11.glVertex2f(3f, -1f);
            GL11.glVertex2f(-1f, 3f);
            GL11.glEnd();
            pixel("drawn", 8, 8);
            record("error", errorName(drain()));
        });
        legacy("legacy: glEnd with no glBegin", () -> {
            setUp();
            call("error", () -> GL11.glEnd());
        });
        legacy("legacy: nested glBegin", () -> {
            setUp();
            GL11.glBegin(GL11.GL_TRIANGLES);
            call("error", () -> GL11.glBegin(GL11.GL_TRIANGLES));
            GL11.glEnd();
        });
        legacy("legacy: matrix stack overflow", () -> {
            call("first push", () -> GL11.glPushMatrix());
            GL11.glPopMatrix();
            call("underflow", () -> GL11.glPopMatrix());
            drain();
            int pushed = 0;
            String error = "no_error";
            for (; pushed < 100; pushed++) {
                GL11.glPushMatrix();
                final int e = drain();
                if (e != GL11.GL_NO_ERROR) {
                    error = errorName(e);
                    break;
                }
            }
            record("overflow", error);
            for (int i = 0; i < pushed; i++) GL11.glPopMatrix();
        });
        legacy("legacy: attrib stack overflow", () -> {
            call("underflow", () -> GL11.glPopAttrib());
            int pushed = 0;
            String error = "no_error";
            try {
                for (; pushed < 100; pushed++) {
                    GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT);
                    final int e = drain();
                    if (e != GL11.GL_NO_ERROR) {
                        error = errorName(e);
                        break;
                    }
                }
            } catch (Throwable t) {
                error = "threw " + t.getClass().getSimpleName();
            }
            record("overflow", error);
            for (int i = 0; i < pushed; i++) GL11.glPopAttrib();
        });
        legacy("legacy: bad enums", () -> {
            call("glLightf", () -> GL11.glLightf(BOGUS, GL11.GL_SPOT_CUTOFF, 1f));
            call("glMaterialf", () -> GL11.glMaterialf(BOGUS, GL11.GL_SHININESS, 1f));
            call("glMatrixMode", () -> GL11.glMatrixMode(BOGUS));
            call("glAlphaFunc", () -> GL11.glAlphaFunc(BOGUS, 0f));
            call("glShadeModel", () -> GL11.glShadeModel(BOGUS));
            call("glFogi", () -> GL11.glFogi(BOGUS, 0));
            call("glTexEnvi", () -> GL11.glTexEnvi(BOGUS, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE));
            call("glGenLists zero", () -> GL11.glGenLists(0));
            call("glCallList unknown", () -> GL11.glCallList(100000));
            call("glEndList with none", () -> GL11.glEndList());
        });
    }
}
