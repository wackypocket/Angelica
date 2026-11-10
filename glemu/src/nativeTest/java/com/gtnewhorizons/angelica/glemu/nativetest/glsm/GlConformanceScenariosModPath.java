package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Calls what a mod calls: each overload GLSM redirects, on a driver as LWJGL's method and on a backend as GLSM's, then reads state back the
 * same way, so GLSM's caches are held to a driver's answers. Named to fall under GlsmRedirectAgent, which rewrites the reads here on a backend.
 * An answer is the error, then each state value the call changed.
 */
final class GlConformanceScenariosModPath {
    private static final String[] VERSIONS = {"11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41", "42", "43", "44", "45", "46"};
    private static final int FLUSH_EVERY = 64;
    private static final int BYTES = 64 * 1024;
    /** Would keep a callback the probe frees, or wait on what nothing here will finish. */
    private static final Set<String> NOT_CALLED = Set.of("glDebugMessageCallback", "glFinish", "glFlush");
    private static final int[] VALUES = {GL13.GL_ACTIVE_TEXTURE, GL11.GL_BLEND, GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB, GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA,
        GL20.GL_BLEND_EQUATION_RGB, GL20.GL_BLEND_EQUATION_ALPHA, GL11.GL_CULL_FACE, GL11.GL_CULL_FACE_MODE, GL11.GL_DEPTH_FUNC, GL11.GL_DEPTH_TEST, GL11.GL_DEPTH_WRITEMASK, GL11.GL_DITHER, GL11.GL_FRONT_FACE,
        GL11.GL_LOGIC_OP_MODE, GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES, GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
        GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES, GL11.GL_POLYGON_OFFSET_FILL, GL31.GL_PRIMITIVE_RESTART, GL31.GL_PRIMITIVE_RESTART_INDEX, GL32.GL_PROGRAM_POINT_SIZE,
        GL32.GL_PROVOKING_VERTEX, GL13.GL_SAMPLE_COVERAGE, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK, GL11.GL_STENCIL_FAIL, GL11.GL_STENCIL_PASS_DEPTH_FAIL,
        GL11.GL_STENCIL_PASS_DEPTH_PASS, GL11.GL_STENCIL_WRITEMASK, GL11.GL_STENCIL_CLEAR_VALUE, GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_REF, GL20.GL_STENCIL_BACK_VALUE_MASK, GL20.GL_STENCIL_BACK_FAIL, GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL,
        GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS, GL20.GL_STENCIL_BACK_WRITEMASK};
    private static final int[] FOUR_INTS = {GL11.GL_VIEWPORT, GL11.GL_SCISSOR_BOX, GL11.GL_COLOR_WRITEMASK};
    private static final int[] FOUR_FLOATS = {GL11.GL_COLOR_CLEAR_VALUE, GL14.GL_BLEND_COLOR};
    private static final int[] FLOATS = {GL11.GL_DEPTH_CLEAR_VALUE, GL11.GL_LINE_WIDTH, GL11.GL_POINT_SIZE, GL11.GL_POLYGON_OFFSET_FACTOR, GL11.GL_POLYGON_OFFSET_UNITS, GL13.GL_SAMPLE_COVERAGE_VALUE};
    /** Which objects are bound depends on how names are handed out, so only whether one is bound can be compared. */
    private static final int[] BOUND = {GL20.GL_CURRENT_PROGRAM, GL15.GL_ARRAY_BUFFER_BINDING, GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING, GL30.GL_VERTEX_ARRAY_BINDING, GL11.GL_TEXTURE_BINDING_2D, GL30.GL_FRAMEBUFFER_BINDING, GL30.GL_RENDERBUFFER_BINDING};

    private final GlGuidedProbe guided;
    private final GlProbeFixture f;
    private final boolean driver;
    private final PrintWriter out;
    private final PrintWriter progress;
    private final Set<String> skipped;
    private final Runnable between;
    private final Map<String, List<GlGuidedProbe.Param>> signatures;
    private final Pattern wanted;
    private int sinceFlush;
    int notBindable;

    private GlConformanceScenariosModPath(GlGuidedProbe guided, GlProbeFixture f, boolean driver, PrintWriter out, PrintWriter progress, Set<String> skipped, Runnable between, Map<String, List<GlGuidedProbe.Param>> signatures, Pattern wanted) {
        this.guided = guided;
        this.f = f;
        this.driver = driver;
        this.out = out;
        this.progress = progress;
        this.skipped = skipped;
        this.between = between;
        this.signatures = signatures;
        this.wanted = wanted;
    }

    static void run(Path requested, Runnable between, boolean driver) throws IOException {
        Files.createDirectories(requested.getParent());
        final String only = System.getenv("ANGELICA_CONFORMANCE_ONLY");
        final Pattern wanted = only == null ? null : Pattern.compile(only);
        final Path file = wanted == null ? requested : Path.of(requested + ".partial");
        final Path skipList = Path.of("../glemu/scripts/conformance-probe-skip.txt");
        final Set<String> skipped = Files.exists(skipList) ? Set.copyOf(Files.readAllLines(skipList)) : Set.of();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file), true);
             PrintWriter progress = new PrintWriter(Files.newBufferedWriter(Path.of(file + ".progress")), true);
             GlProbeFixture fixture = new GlProbeFixture()) {
            final GlGuidedProbe guided = new GlGuidedProbe(fixture, null, null, skipped, between);
            final GlConformanceScenariosModPath probe = new GlConformanceScenariosModPath(guided, fixture, driver, w, progress, skipped, between, GlGuidedProbe.signatures(), wanted);
            final Map<String, String> redirects = new TreeMap<>(GLSMRedirector.getGLPrefixMethodRedirects());
            for (var e : redirects.entrySet()) probe.function(e.getKey(), e.getValue());
            w.println("# overloads with an argument type this cannot make: " + probe.notBindable);
        }
    }

    private void function(String name, String glsmName) {
        if (NOT_CALLED.contains(name) || wanted != null && !wanted.matcher(name).matches()) return;
        final Class<?> owner;
        try {
            owner = Class.forName(GLSMRedirector.getTargetOwner(glsmName).replace('/', '.'), false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            return;
        }
        for (String overload : GLSMRedirector.targetOverloads(glsmName)) {
            // Looked up one at a time: some signatures name types this classpath lacks, which no mod here could call either.
            final java.lang.invoke.MethodHandle glsm;
            final Class<?>[] types;
            try {
                final java.lang.invoke.MethodType type = java.lang.invoke.MethodType.fromMethodDescriptorString(overload, getClass().getClassLoader());
                glsm = java.lang.invoke.MethodHandles.publicLookup().findStatic(owner, glsmName, type);
                types = type.parameterArray();
            } catch (ReflectiveOperationException | TypeNotPresentException | LinkageError e) {
                notBindable++;
                continue;
            }
            final Method lwjgl = lwjgl(name, types);
            if (lwjgl == null) continue;
            if (driver && !GlGuidedProbe.availableUnderAnyName(name)) continue;
            overload(name, lwjgl, glsm, types);
        }
    }

    private static Method lwjgl(String name, Class<?>[] types) {
        for (String suffix : new String[] {"C", ""}) {
            for (String version : VERSIONS) {
                try {
                    return Class.forName("org.lwjgl.opengl.GL" + version + suffix).getMethod(name, types);
                } catch (ReflectiveOperationException e) {
                    // Not in this class.
                }
            }
        }
        return null;
    }

    private List<GlGuidedProbe.Param> page(String name) {
        for (String s : new String[] {name, name + "v", name + "iv", name + "fv", name + "i", name.replaceAll("[fid]$", "") + "iv"}) {
            final List<GlGuidedProbe.Param> found = signatures.get(s);
            if (found != null) return found;
        }
        return List.of();
    }

    /** Which of the page's parameters each Java argument is. LWJGL drops counts it can read from a buffer, and answers one output as the return value. */
    private List<GlGuidedProbe.Param> aligned(String name, Class<?>[] types) {
        final List<GlGuidedProbe.Param> params = page(name), out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < types.length; i++) {
            final boolean memory = !types[i].isPrimitive();
            GlGuidedProbe.Param found = null;
            for (int p = next; p < params.size(); p++) {
                final GlGuidedProbe.Param candidate = params.get(p);
                if (candidate.pointer() == memory || types[i] == long.class && candidate.pointer()) {
                    found = candidate;
                    next = p + 1;
                    break;
                }
            }
            out.add(found != null ? found : new GlGuidedProbe.Param("arg" + i, memory ? "const void *" : types[i].getName(), List.of()));
        }
        return out;
    }

    private Object memory(Class<?> type, GlGuidedProbe.Param p) {
        final long at = p.ctype().contains("const") ? f.input : f.scratch;
        if (type == ByteBuffer.class || type == Buffer.class) return MemoryUtil.memByteBuffer(at, BYTES);
        if (type == IntBuffer.class) return MemoryUtil.memIntBuffer(at, BYTES / 4);
        if (type == FloatBuffer.class) return MemoryUtil.memFloatBuffer(at, BYTES / 4);
        if (type == DoubleBuffer.class) return MemoryUtil.memDoubleBuffer(at, BYTES / 8);
        if (type == ShortBuffer.class) return MemoryUtil.memShortBuffer(at, BYTES / 2);
        if (type == LongBuffer.class) return MemoryUtil.memLongBuffer(at, BYTES / 8);
        if (type == CharSequence.class) return "x";
        if (type == CharSequence[].class) return new CharSequence[] {"x"};
        if (type == int[].class) return new int[16];
        if (type == float[].class) return new float[16];
        if (type == double[].class) return new double[16];
        if (type == short[].class) return new short[16];
        if (type == long[].class) return new long[16];
        return null;
    }

    private static boolean clientIndices(String name, Class<?>[] types) {
        if (!name.matches("gl(Multi)?Draw(Range)?Elements.*")) return false;
        for (Class<?> c : types) if (Buffer.class.isAssignableFrom(c)) return true;
        return false;
    }

    /** A call whose count comes from the buffer must be given a count that fits: one value for a uniform that is no array, and one draw buffer. */
    private static Object sized(String name, Object memory) {
        if (!(memory instanceof Buffer buffer)) return memory;
        if (name.equals("glDrawBuffers")) return buffer.limit(1);
        final java.util.regex.Matcher uniform = Pattern.compile("glUniform(Matrix)?(\\d)(?:x(\\d))?.*").matcher(name);
        if (!uniform.matches()) return buffer;
        final int n = Integer.parseInt(uniform.group(2));
        final int components = uniform.group(1) == null ? n : n * (uniform.group(3) == null ? n : Integer.parseInt(uniform.group(3)));
        return buffer.limit(components);
    }

    private void overload(String name, Method lwjgl, java.lang.invoke.MethodHandle glsm, Class<?>[] types) {
        for (Class<?> c : types) {
            if (!c.isPrimitive() && memory(c, new GlGuidedProbe.Param("", "const void *", List.of())) == null) {
                notBindable++;
                return;
            }
        }
        final List<GlGuidedProbe.Param> params = aligned(name, types);
        final String id = name + " " + Arrays.stream(types).map(Class::getSimpleName).toList().toString().replace(" ", "");
        f.newProgram();
        call(id, name, lwjgl, glsm, types, params, -1, null, "valid");
        for (int i = 0; i < params.size(); i++) {
            // With no page to name it, nothing says what a value of this argument means, and it may be a raw pointer.
            if (!types[i].isPrimitive() || params.get(i).name().startsWith("arg")) continue;
            for (var change : guided.variations(name, params.get(i)).entrySet()) {
                call(id, name, lwjgl, glsm, types, params, i, change.getValue(), params.get(i).name() + "=" + change.getKey());
            }
        }
        f.deleteProgram();
    }

    private void call(String id, String name, Method lwjgl, java.lang.invoke.MethodHandle glsm, Class<?>[] types, List<GlGuidedProbe.Param> params, int changed, Object value, String key) {
        final String line = id + "\t" + key;
        if (skipped.contains(line)) return;
        progress.println(line);
        GlConformanceScenarios.reset();
        f.build();
        final Object[] args = new Object[types.length];
        final Map<String, Object> chosen = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            final GlGuidedProbe.Param p = params.get(i);
            if (!types[i].isPrimitive()) {
                args[i] = sized(name, memory(types[i], p));
                continue;
            }
            final Object raw = i == changed ? value : p.name().startsWith("arg") && types[i] == long.class ? (Object) f.input : guided.valid(name, p, chosen);
            chosen.put(p.name(), raw);
            args[i] = GlGuidedProbe.convert(types[i], raw);
        }
        guided.prepare(name);
        if (clientIndices(name, types)) {
            // Indices in memory are read from it only with no element buffer bound, as a mod passes them. With one bound the address is taken
            // as an offset into it, far past its end, which NVIDIA reads and dies on. Client arrays go with no vertex array, as GL requires.
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        final Map<String, String> before = state();
        drain();
        String answer;
        try {
            if (driver) lwjgl.invoke(null, args);
            else glsm.invokeWithArguments(args);
            answer = GlConformanceScenarios.errorName(drain());
        } catch (InvocationTargetException e) {
            drain();
            answer = "threw " + e.getCause().getClass().getSimpleName();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (Throwable t) {
            drain();
            answer = "threw " + t.getClass().getSimpleName();
        }
        final Map<String, String> after = state();
        drain();
        final StringBuilder changes = new StringBuilder();
        for (var e : after.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) changes.append(changes.isEmpty() ? "|" : ",").append(e.getKey()).append('=').append(e.getValue());
        }
        out.println(line + "\t" + answer + changes);
        // A driver with a compatibility profile is left inside glBegin or glNewList just as we are, where it ignores later calls without an error.
        if (!driver || org.lwjgl.opengl.GL.getCapabilities().glEnd != 0L) endLegacy();
        f.tearDown();
        if (name.contains("Delete") || name.equals("glLinkProgram") || name.equals("glDetachShader")) {
            f.deleteProgram();
            f.newProgram();
        }
        if (++sinceFlush == FLUSH_EVERY) {
            sinceFlush = 0;
            between.run();
        }
    }

    private static int drain() {
        return GlCallProbe.drain();
    }

    private static void endLegacy() {
        GL11.glEnd();
        GL11.glEndList();
        GL11.glRenderMode(GL11.GL_RENDER);
    }

    private static Map<String, String> state() {
        final Map<String, String> out = new TreeMap<>();
        for (int pname : VALUES) out.put(Integer.toHexString(pname), String.valueOf(GL11.glGetInteger(pname)));
        final IntBuffer ints = BufferUtils.createIntBuffer(16);
        for (int pname : FOUR_INTS) {
            ints.clear();
            GL11.glGetIntegerv(pname, ints);
            out.put(Integer.toHexString(pname), ints.get(0) + " " + ints.get(1) + " " + ints.get(2) + " " + ints.get(3));
        }
        final FloatBuffer floats = BufferUtils.createFloatBuffer(16);
        for (int pname : FOUR_FLOATS) {
            floats.clear();
            GL11.glGetFloatv(pname, floats);
            out.put(Integer.toHexString(pname), floats.get(0) + " " + floats.get(1) + " " + floats.get(2) + " " + floats.get(3));
        }
        for (int pname : FLOATS) out.put(Integer.toHexString(pname), String.valueOf(GL11.glGetFloat(pname)));
        for (int pname : BOUND) out.put(Integer.toHexString(pname), GL11.glGetInteger(pname) != 0 ? "bound" : "none");
        return out;
    }
}
