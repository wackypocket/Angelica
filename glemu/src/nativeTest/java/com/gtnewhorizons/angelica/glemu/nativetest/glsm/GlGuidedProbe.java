package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls each function the reference pages declare with arguments that work, then again with one argument changed, so an error comes from
 * that argument rather than from whichever check ran first. Keys in the output name the change: {@code valid}, or {@code level=-1}.
 * glemu/scripts/gen_conformance_signatures.py writes the table this reads.
 */
public final class GlGuidedProbe {
    private static final String[] VERSIONS = {"11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41", "42", "43", "44", "45", "46"};
    private static final int ABSENT = 0xBEEF;
    private static final int BIG = 1 << 20;
    private static final int FLUSH_EVERY = 64;
    /** A pointer here is an offset into a bound buffer, where anything else is a pointer into memory. */
    private static final Set<String> OFFSET_POINTERS = Set.of("indices", "indirect", "pointer");
    /** Would keep a callback pointer the probe frees. */
    private static final Set<String> NOT_CALLED = Set.of("glDebugMessageCallback");
    /** Names are handed out differently everywhere, so the only ones an answer can be compared for are those that cannot exist. */
    private static final Set<String> OBJECT_NAMES = Set.of("program", "shader", "texture", "buffer", "readBuffer", "writeBuffer", "framebuffer", "drawFramebuffer", "readFramebuffer",
        "renderbuffer", "sampler", "pipeline", "vaobj", "array", "xfb", "id");
    private static final Pattern DRAW = Pattern.compile("gl(Multi)?Draw.*");
    private static final Pattern UNIFORM = Pattern.compile("gl(?:Program)?Uniform(Matrix)?(\\d(?:x\\d)?)(ui|i|f|d)v?");
    private static final Map<String, Integer> CONSTANTS = constants();

    record Param(String name, String ctype, List<String> allowed) {
        boolean pointer() { return ctype.contains("*"); }
        boolean enumerated() { return ctype.equals("GLenum"); }
    }

    private final GlProbeFixture f;
    private final PrintWriter out;
    private final PrintWriter progress;
    private final Set<String> skipped;
    private final Runnable between;
    private int sinceFlush;

    GlGuidedProbe(GlProbeFixture f, PrintWriter out, PrintWriter progress, Set<String> skipped, Runnable between) {
        this.f = f;
        this.out = out;
        this.progress = progress;
        this.skipped = skipped;
        this.between = between;
    }

    private static Map<String, Integer> constants() {
        final Map<String, Integer> out = new HashMap<>();
        try {
            for (var field : Class.forName("org.lwjgl.opengl.GL46C").getFields()) {
                if (field.getType() == int.class && Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("GL_")) out.put(field.getName(), field.getInt(null));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    static Map<String, List<Param>> signatures() throws IOException {
        final Map<String, List<Param>> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("../glemu/scripts/conformance-signatures.tsv"))) {
            if (line.startsWith("#")) continue;
            final String[] parts = line.split("\t", -1);
            out.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(new Param(parts[1], parts[2], parts[3].isEmpty() ? List.of() : List.of(parts[3].split(","))));
        }
        return out;
    }

    /** The native method a function's signature describes, under its own name or LWJGL's name for the form taking raw pointers. */
    private static Map<String, Method> natives(int maxVersion) throws ClassNotFoundException {
        final Map<String, Method> out = new HashMap<>();
        for (String version : VERSIONS) {
            if (Integer.parseInt(version) > maxVersion) continue;
            for (Method m : Class.forName("org.lwjgl.opengl.GL" + version + "C").getDeclaredMethods()) {
                if (!Modifier.isNative(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) continue;
                final String name = m.getName().startsWith("ngl") ? m.getName().substring(1) : m.getName();
                out.putIfAbsent(name, m);
            }
        }
        return out;
    }

    private static Class<?> javaType(Param p) {
        if (p.pointer()) return long.class;
        return switch (p.ctype()) {
            case "GLsync", "GLsizeiptr", "GLintptr", "GLuint64", "GLint64" -> long.class;
            case "GLfloat" -> float.class;
            case "GLdouble" -> double.class;
            case "GLboolean" -> boolean.class;
            case "GLshort", "GLushort" -> short.class;
            case "GLbyte", "GLubyte" -> byte.class;
            default -> int.class;
        };
    }

    private static boolean matches(Method m, List<Param> params) {
        final Class<?>[] types = m.getParameterTypes();
        if (types.length != params.size()) return false;
        for (int i = 0; i < types.length; i++) {
            if (types[i] != javaType(params.get(i))) return false;
        }
        return true;
    }

    private static boolean available(String function) {
        try {
            final var caps = GL.getCapabilities();
            return caps.getClass().getField(function).getLong(caps) != 0;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Whether a driver has what a wrapper of this name calls, which may carry a suffix LWJGL's own name drops. */
    static boolean availableUnderAnyName(String function) {
        for (String suffix : new String[] {"", "v", "iv", "fv", "i", "f"}) {
            if (available(function + suffix)) return true;
        }
        return false;
    }

    private static int version() {
        final String v = GL11C.glGetString(GL11C.GL_VERSION);
        return (v.charAt(0) - '0') * 10 + (v.charAt(2) - '0');
    }

    public static void run(Path requested, Runnable between) throws IOException, ClassNotFoundException {
        Files.createDirectories(requested.getParent());
        final Map<String, Method> natives = natives(version());
        // For working on a few functions, whose answers then go beside the full set instead of replacing it.
        final String only = System.getenv("ANGELICA_CONFORMANCE_ONLY");
        final Pattern wanted = only == null ? null : Pattern.compile(only);
        final Path file = wanted == null ? requested : Path.of(requested + ".partial");
        final Path skipList = Path.of("../glemu/scripts/conformance-probe-skip.txt");
        final Set<String> skipped = Files.exists(skipList) ? Set.copyOf(Files.readAllLines(skipList)) : Set.of();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file), true);
             PrintWriter progress = new PrintWriter(Files.newBufferedWriter(Path.of(file + ".progress")), true);
             GlProbeFixture fixture = new GlProbeFixture()) {
            final GlGuidedProbe probe = new GlGuidedProbe(fixture, w, progress, skipped, between);
            for (var e : signatures().entrySet()) {
                final Method m = natives.get(e.getKey());
                if (m == null || NOT_CALLED.contains(e.getKey()) || !matches(m, e.getValue()) || !available(e.getKey())) continue;
                if (wanted != null && !wanted.matcher(e.getKey()).matches()) continue;
                probe.function(e.getKey(), m, e.getValue());
            }
        }
    }

    private void function(String function, Method m, List<Param> params) {
        f.newProgram();
        call(function, m, params, null, null, "valid");
        for (Param p : params) {
            for (Map.Entry<String, Object> change : variations(function, p).entrySet()) {
                call(function, m, params, p, change.getValue(), p.name() + "=" + change.getKey());
            }
        }
        f.deleteProgram();
    }

    private void call(String function, Method m, List<Param> params, Param changed, Object value, String key) {
        final String id = function + "\t" + key;
        if (skipped.contains(id)) return;
        progress.println(id);
        GlConformanceScenarios.reset();
        f.build();
        final Object[] args = new Object[params.size()];
        final Map<String, Object> chosen = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            final Param p = params.get(i);
            final Object raw = p == changed ? value : valid(function, p, chosen);
            chosen.put(p.name(), raw);
            args[i] = convert(javaType(p), raw);
        }
        prepare(function);
        GlCallProbe.drain();
        String answer;
        try {
            m.invoke(null, args);
            answer = GlConformanceScenarios.errorName(GlCallProbe.drain());
        } catch (InvocationTargetException e) {
            GlCallProbe.drain();
            answer = "threw " + e.getCause().getClass().getSimpleName();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        out.println(id + "\t" + answer);
        f.tearDown();
        if (function.contains("Delete") || function.equals("glLinkProgram") || function.equals("glDetachShader")) {
            f.deleteProgram();
            f.newProgram();
        }
        if (++sinceFlush == FLUSH_EVERY) {
            sinceFlush = 0;
            between.run();
        }
    }

    /** The state a call needs that a fixture of bound objects does not have. */
    void prepare(String function) {
        switch (function) {
            case "glEndQuery", "glEndQueryIndexed" -> GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, GL15C.glGenQueries());
            case "glEndTransformFeedback", "glPauseTransformFeedback" -> GL30C.glBeginTransformFeedback(GL11C.GL_POINTS);
            case "glResumeTransformFeedback" -> {
                GL30C.glBeginTransformFeedback(GL11C.GL_POINTS);
                GL40C.glPauseTransformFeedback();
            }
            case "glDrawTransformFeedback", "glDrawTransformFeedbackInstanced", "glDrawTransformFeedbackStream", "glDrawTransformFeedbackStreamInstanced" -> {
                GL40C.glBindTransformFeedback(GL40C.GL_TRANSFORM_FEEDBACK, f.feedback);
                GL30C.glBindBufferBase(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, f.buffers.get(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER));
                GL30C.glBeginTransformFeedback(GL11C.GL_POINTS);
                GL30C.glEndTransformFeedback();
                GL40C.glBindTransformFeedback(GL40C.GL_TRANSFORM_FEEDBACK, 0);
            }
            case "glEndConditionalRender" -> GL30C.glBeginConditionalRender(f.query, GL30C.GL_QUERY_WAIT);
            case "glUnmapBuffer", "glFlushMappedBufferRange" -> GL30C.glMapBufferRange(GL15C.GL_ARRAY_BUFFER, 0, 256, GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_FLUSH_EXPLICIT_BIT);
            case "glCompressedTexSubImage2D", "glGetCompressedTexImage" -> GL13C.nglCompressedTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_COMPRESSED_RED_RGTC1, 4, 4, 0, 8, f.scratch);
            case "glCompressedTexSubImage3D" -> GL13C.nglCompressedTexImage3D(GL30C.GL_TEXTURE_2D_ARRAY, 0, GL30C.GL_COMPRESSED_RED_RGTC1, 4, 4, 1, 0, 8, f.scratch);
            case "glAttachShader" -> GL20C.glDetachShader(f.program, f.vs);
            default -> {}
        }
    }

    Object valid(String function, Param p, Map<String, Object> chosen) {
        if (p.pointer()) {
            if (OFFSET_POINTERS.contains(p.name()) && p.ctype().indexOf('*') == p.ctype().lastIndexOf('*') && !function.startsWith("glGet")) return 0L;
            final boolean stringList = p.ctype().contains("char") || p.ctype().contains("GLchar");
            if (stringList && p.ctype().indexOf('*') != p.ctype().lastIndexOf('*')) return f.strings;
            return p.ctype().contains("const") ? f.input : f.scratch;
        }
        if (p.enumerated()) return validEnum(function, p);
        return switch (p.name()) {
            case "program" -> f.program;
            case "shader" -> f.vs;
            case "texture" -> function.equals("glFramebufferTextureLayer") ? f.textures.get(GL30C.GL_TEXTURE_2D_ARRAY) : textureFor(chosen);
            case "buffer", "readBuffer", "writeBuffer" -> bufferNamed(p.name());
            case "framebuffer", "drawFramebuffer", "readFramebuffer" -> f.framebuffer;
            case "renderbuffer" -> f.renderbuffer;
            case "sampler" -> f.sampler;
            case "pipeline" -> f.pipeline;
            case "vaobj", "array" -> f.vao;
            case "xfb" -> f.feedback;
            case "id" -> function.contains("TransformFeedback") ? f.feedback : f.query;
            case "sync" -> f.fence;
            case "location" -> uniformFor(function);
            case "uniformBlockIndex" -> f.uniformBlock();
            case "width", "height" -> 4;
            case "depth", "n", "count", "instancecount", "drawcount", "numAttachments" -> 1;
            case "size" -> p.ctype().equals("GLsizeiptr") ? 16L : 4;
            case "length" -> p.ctype().equals("GLsizeiptr") ? 16L : 1;
            case "levels", "samples" -> 1;
            case "imageSize" -> 8;
            case "internalformat" -> GL11C.GL_RGBA8;
            case "timeout" -> function.equals("glWaitSync") ? -1L : 0L;
            case "bufSize", "maxLength" -> 256;
            case "value" -> function.equals("glPatchParameteri") ? 3 : 0;
            case "mask" -> function.equals("glClear") || function.startsWith("glBlit") ? GL11C.GL_COLOR_BUFFER_BIT : -1;
            case "access" -> GL30C.GL_MAP_READ_BIT;
            case "param" -> paramFor(chosen.get("pname"));
            default -> 0;
        };
    }

    private static int paramFor(Object pname) {
        if (!(pname instanceof Integer name)) return 0;
        return switch (name) {
            case GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_TEXTURE_WRAP_R -> GL11C.GL_REPEAT;
            case GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_TEXTURE_MAG_FILTER -> GL11C.GL_NEAREST;
            case GL14C.GL_TEXTURE_COMPARE_FUNC -> GL11C.GL_LEQUAL;
            case GL33C.GL_TEXTURE_SWIZZLE_R, GL33C.GL_TEXTURE_SWIZZLE_G, GL33C.GL_TEXTURE_SWIZZLE_B, GL33C.GL_TEXTURE_SWIZZLE_A -> GL11C.GL_RED;
            default -> 0;
        };
    }

    private int textureFor(Map<String, Object> chosen) {
        final Object target = chosen.containsKey("textarget") ? chosen.get("textarget") : chosen.containsKey("target") ? chosen.get("target") : null;
        if (target instanceof Integer t) {
            if (t == GL32C.GL_TEXTURE_2D_MULTISAMPLE) return f.multisample;
            final Integer found = f.textures.get(t);
            if (found != null) return found;
        }
        return f.textures.get(GL11C.GL_TEXTURE_2D);
    }

    private int bufferNamed(String name) {
        return switch (name) {
            case "readBuffer" -> f.buffers.get(GL31C.GL_COPY_READ_BUFFER);
            case "writeBuffer" -> f.buffers.get(GL31C.GL_COPY_WRITE_BUFFER);
            default -> f.buffers.get(GL15C.GL_ARRAY_BUFFER);
        };
    }

    private int uniformFor(String function) {
        final Matcher m = UNIFORM.matcher(function);
        if (!m.matches()) return 0;
        if (m.group(1) != null) return f.uniform("m" + m.group(2));
        final String prefix = switch (m.group(3)) {
            case "ui" -> "u";
            case "i" -> "i";
            default -> "f";
        };
        return f.uniform(prefix + m.group(2));
    }

    private static final List<String> PREFERRED = List.of("GL_TEXTURE_2D", "GL_ARRAY_BUFFER", "GL_UNIFORM_BUFFER", "GL_FRAMEBUFFER", "GL_RENDERBUFFER", "GL_SAMPLES_PASSED",
        "GL_TEXTURE_BASE_LEVEL", "GL_RGBA8", "GL_RGBA", "GL_UNSIGNED_BYTE", "GL_COLOR_ATTACHMENT0", "GL_TRIANGLES", "GL_FRAGMENT_SHADER", "GL_BLEND");

    /** Arguments the pages describe in prose or a table rather than in the parameter's own entry, where the usual default is wrong for the function. */
    private static final Map<String, String> SPECIFIC = Map.ofEntries(
        Map.entry("glActiveTexture.texture", "GL_TEXTURE0"),
        Map.entry("glBeginConditionalRender.mode", "GL_QUERY_WAIT"),
        Map.entry("glClearBufferfi.buffer", "GL_DEPTH_STENCIL"),
        Map.entry("glCreateShaderProgramv.type", "GL_FRAGMENT_SHADER"),
        Map.entry("glDrawElementsIndirect.type", "GL_UNSIGNED_INT"),
        Map.entry("glFramebufferTexture1D.textarget", "GL_TEXTURE_1D"),
        Map.entry("glFramebufferTexture3D.textarget", "GL_TEXTURE_3D"),
        Map.entry("glGetBooleanv.pname", "GL_BLEND"),
        Map.entry("glGetDoublev.pname", "GL_VIEWPORT"),
        Map.entry("glGetFloatv.pname", "GL_VIEWPORT"),
        Map.entry("glGetIntegerv.pname", "GL_VIEWPORT"),
        Map.entry("glGetInteger64v.pname", "GL_VIEWPORT"),
        Map.entry("glGetBooleani_v.target", "GL_UNIFORM_BUFFER_BINDING"),
        Map.entry("glGetIntegeri_v.target", "GL_UNIFORM_BUFFER_BINDING"),
        Map.entry("glGetFloati_v.target", "GL_VIEWPORT"),
        Map.entry("glGetDoublei_v.target", "GL_VIEWPORT"),
        Map.entry("glGetInteger64i_v.target", "GL_UNIFORM_BUFFER_START"),
        Map.entry("glGetActiveUniformBlockiv.pname", "GL_UNIFORM_BLOCK_BINDING"),
        Map.entry("glGetActiveUniformsiv.pname", "GL_UNIFORM_TYPE"),
        Map.entry("glGetBufferParameteriv.value", "GL_BUFFER_SIZE"),
        Map.entry("glGetBufferParameteri64v.value", "GL_BUFFER_SIZE"),
        Map.entry("glReadBuffer.mode", "GL_COLOR_ATTACHMENT0"),
        Map.entry("glVertexAttribLPointer.type", "GL_DOUBLE"),
        Map.entry("glGetFramebufferAttachmentParameteriv.pname", "GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE"),
        Map.entry("glGetProgramPipelineiv.pname", "GL_ACTIVE_PROGRAM"),
        Map.entry("glGetRenderbufferParameteriv.pname", "GL_RENDERBUFFER_WIDTH"),
        Map.entry("glGetShaderPrecisionFormat.precisionType", "GL_HIGH_FLOAT"),
        Map.entry("glGetStringi.name", "GL_EXTENSIONS"),
        Map.entry("glGetSynciv.pname", "GL_OBJECT_TYPE"),
        Map.entry("glPatchParameteri.pname", "GL_PATCH_VERTICES"),
        Map.entry("glPatchParameterfv.pname", "GL_PATCH_DEFAULT_OUTER_LEVEL"),
        Map.entry("glProgramParameteri.pname", "GL_PROGRAM_SEPARABLE"));

    private static String enumDefault(String function, String name) {
        final String specific = SPECIFIC.get(function + "." + name);
        if (specific != null) return specific;
        if (function.contains("Compressed") && (name.equals("internalformat") || name.equals("format"))) return "GL_COMPRESSED_RED_RGTC1";
        return switch (name) {
            case "textarget" -> "GL_TEXTURE_2D";
            case "target" -> {
                if (function.contains("Buffer") && !function.contains("Framebuffer") && !function.contains("Renderbuffer")) yield "GL_ARRAY_BUFFER";
                if (function.contains("Framebuffer")) yield "GL_FRAMEBUFFER";
                if (function.contains("Renderbuffer")) yield "GL_RENDERBUFFER";
                if (function.contains("Query")) yield "GL_SAMPLES_PASSED";
                yield "GL_TEXTURE_2D";
            }
            case "readTarget" -> "GL_COPY_READ_BUFFER";
            case "writeTarget" -> "GL_COPY_WRITE_BUFFER";
            case "internalformat" -> "GL_RGBA8";
            case "format" -> "GL_RGBA";
            case "type" -> "GL_UNSIGNED_BYTE";
            case "attachment" -> "GL_COLOR_ATTACHMENT0";
            case "buffer" -> "GL_COLOR";
            case "cap" -> "GL_BLEND";
            case "shadertype" -> "GL_FRAGMENT_SHADER";
            case "programInterface" -> "GL_UNIFORM";
            case "pname" -> "GL_TEXTURE_BASE_LEVEL";
            case "mode", "primitiveMode" -> "GL_TRIANGLES";
            case "access" -> "GL_READ_ONLY";
            case "provokeMode" -> "GL_LAST_VERTEX_CONVENTION";
            default -> "GL_NONE";
        };
    }

    private static int validEnum(String function, Param p) {
        final String specific = SPECIFIC.get(function + "." + p.name());
        if (specific != null && CONSTANTS.containsKey(specific)) return CONSTANTS.get(specific);
        if (function.contains("Compressed") && (p.name().equals("internalformat") || p.name().equals("format"))) return CONSTANTS.get("GL_COMPRESSED_RED_RGTC1");
        for (String wanted : PREFERRED) {
            if (p.allowed().contains(wanted)) return CONSTANTS.get(wanted);
        }
        for (String name : p.allowed()) {
            final Integer value = CONSTANTS.get(name);
            if (value != null) return value;
        }
        return CONSTANTS.getOrDefault(enumDefault(function, p.name()), 0);
    }

    /**
     * The values tried for one argument. "big" is past what any device can do but still fits the fixture's scratch memory; draws never get
     * it, since a draw of that size reads past its buffers, which GL leaves undefined.
     */
    Map<String, Object> variations(String function, Param p) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (p.pointer()) return out;
        if (p.enumerated()) {
            for (String name : p.allowed()) {
                if (CONSTANTS.containsKey(name)) out.put(name, CONSTANTS.get(name));
            }
            out.put("0", 0);
            out.put("0xBEEF", ABSENT);
            out.put("-1", -1);
            return out;
        }
        final Class<?> type = javaType(p);
        if (type == boolean.class) {
            out.put("true", 1);
            out.put("false", 0);
            return out;
        }
        if (type == float.class || type == double.class) {
            out.put("-1", -1);
            out.put("0", 0);
            out.put("1", 1);
            return out;
        }
        if (p.name().equals("sync")) {
            out.put("0", 0L);
            return out;
        }
        out.put("-1", -1);
        out.put("0", 0);
        // No limit is documented for how many names one call makes, so a large count only fills the heap.
        if (p.name().equals("n")) return out;
        out.put("0xBEEF", ABSENT);
        if (OBJECT_NAMES.contains(p.name())) return out;
        // Which uniform has a low location is the linker's choice, so a call naming one compares nothing.
        if (p.name().equals("location")) {
            out.remove("0");
            return out;
        }
        out.put("1", 1);
        if (!DRAW.matcher(function).matches()) out.put("big", BIG);
        return out;
    }

    static Object convert(Class<?> type, Object raw) {
        final Number n = raw instanceof Number number ? number : 0;
        if (type == int.class) return n.intValue();
        if (type == long.class) return n.longValue();
        if (type == float.class) return n.floatValue();
        if (type == double.class) return n.doubleValue();
        if (type == short.class) return n.shortValue();
        if (type == byte.class) return n.byteValue();
        return n.intValue() != 0;
    }
}
