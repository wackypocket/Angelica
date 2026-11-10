package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.resource.BufBase;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/**
 * Transform feedback, which Metal has no form of, as storage buffer writes on every backend: a captured program's vertex shader gets a
 * new main that stores its varyings at the vertex's index, instance by instance. That is primitive order only for glDrawArrays of a
 * list topology; other draws go through FeedbackDraws, and a stage emulated as a compute shader hands its outputs to captureStored.
 */
public final class GlTransformFeedback {

    private static final int MAX_BUFFERS = 4;
    public static final int STREAMS = 4;
    /** What a stage's stored outputs call the vertex stream of each record, for those emitting to several. */
    public static final String STREAM_RECORD = "angelica_stream";
    private static final String ENABLED = "angelica_feedback_enabled", BASE = "angelica_feedback_base", PER_INSTANCE = "angelica_feedback_perInstance", FIRST_INSTANCE = "angelica_feedback_firstInstance";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\s+.*$");

    /** What the shader writes it to, its components and which buffer it goes to. A skip takes up room in the buffer, with no expression. */
    private record Varying(String name, String type, int components, String expression, int buffer, int elements) {
        Varying(String name, String type, int components, String expression, int buffer) {
            this(name, type, components, expression, buffer, 1);
        }

        boolean skip() {
            return expression == null;
        }

        /** The name a stage storing its outputs knows it by. */
        String storedName() {
            final int dot = name.lastIndexOf('.');
            final String member = dot < 0 ? name : name.substring(dot + 1);
            final int bracket = member.indexOf('[');
            return bracket < 0 ? member : member.substring(0, bracket);
        }

        int element() {
            final int bracket = name.indexOf('[');
            return bracket < 0 ? 0 : Integer.parseInt(name.substring(bracket + 1, name.indexOf(']', bracket)));
        }
    }

    private static final class Declared {
        final String[] names;
        final int mode;
        List<Varying> resolved = List.of();
        /** The vertex stream each buffer takes its vertices from. */
        final int[] bufferStream = new int[MAX_BUFFERS];
        /** A varying sharing a buffer with one of another stream, which fails the link. */
        String mixed;

        Declared(String[] names, int mode) {
            this.names = names;
            this.mode = mode;
        }
    }

    private static final class Capture {
        final int[] buffer = new int[MAX_BUFFERS];
        final long[] offset = new long[MAX_BUFFERS], size = new long[MAX_BUFFERS];
        boolean active, paused;
        int primitiveMode;
        int program;
        /** What each vertex stream has captured since the capture began. */
        final int[] vertices = new int[STREAMS];
        /** What the last capture to end left for glDrawTransformFeedback to draw. */
        final int[] drawable = new int[STREAMS];
        boolean ended;
    }

    private static final Int2ObjectOpenHashMap<Declared> PROGRAMS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Declared> PENDING = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Capture> CAPTURES = new Int2ObjectOpenHashMap<>();
    private static int nextName = 1;

    /** What GL keeps per context. Like GlState, each thread has its own. */
    private static final class State {
        final Capture defaultCapture = new Capture();
        Capture current = defaultCapture;
        int currentName;
        final long[] primitives = new long[STREAMS], generated = new long[STREAMS], overflows = new long[STREAMS];
        /** While a draw runs in parts for stages emulated as compute shaders, its parts are not draws of its own. */
        boolean inParts;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GlTransformFeedback() {}

    private static int storageBinding(int index) {
        return GlState.MAX_BUFFER_BINDINGS - 1 - index;
    }

    static int create() {
        final int name = nextName++;
        CAPTURES.put(name, new Capture());
        return name;
    }

    /** glGenTransformFeedbacks only reserves a name, which becomes an object when first bound. */
    static int reserve() {
        final int name = nextName++;
        RESERVED.add(name);
        return name;
    }

    public static boolean known(int name) {
        return CAPTURES.containsKey(name) || RESERVED.contains(name);
    }

    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet RESERVED = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    static void delete(int name) {
        final State state = STATE.get();
        RESERVED.remove(name);
        if (CAPTURES.remove(name) != null && state.currentName == name) bind(0);
    }

    public static boolean exists(int name) {
        return CAPTURES.containsKey(name);
    }

    public static boolean active() {
        return STATE.get().current.active;
    }

    public static boolean paused() {
        return STATE.get().current.paused;
    }

    private static Capture capture(int name) {
        final State state = STATE.get();
        if (RESERVED.remove(name)) CAPTURES.put(name, new Capture());
        return name == 0 ? state.defaultCapture : CAPTURES.get(name);
    }

    static void bind(int name) {
        final State state = STATE.get();
        final Capture capture = capture(name);
        if (capture == null) return;
        state.current = capture;
        state.currentName = name;
    }

    /** glBindBufferBase and glBindBufferRange on the feedback target. */
    public static void bindBuffer(int index, int buffer, long offset, long size) {
        final State state = STATE.get();
        bindBuffer(state.current, index, buffer, offset, size);
    }

    static void bindBuffer(int name, int index, int buffer, long offset, long size) {
        final Capture capture = capture(name);
        if (capture != null) bindBuffer(capture, index, buffer, offset, size);
    }

    private static void bindBuffer(Capture capture, int index, int buffer, long offset, long size) {
        if (index < 0 || index >= MAX_BUFFERS) return;
        capture.buffer[index] = buffer;
        capture.offset[index] = offset;
        capture.size[index] = size;
    }

    static void varyings(int program, String[] names, int mode) {
        PENDING.put(program, new Declared(names, mode));
    }

    /** What glTransformFeedbackVaryings names takes effect at the next link, and until then a program goes on capturing what it linked with. */
    public static void linking(int program) {
        final Declared pending = PENDING.remove(program);
        if (pending != null) PROGRAMS.put(program, pending);
    }

    /** The source to link instead of a captured program's vertex shader, or null when it captures nothing. */
    public static String capturingSource(int program, String source) {
        final Declared declared = PROGRAMS.get(program);
        if (declared == null || declared.names.length == 0) return null;
        final List<Varying> varyings = resolveAll(declared, source);
        declared.resolved = varyings;
        return rewrite(source, varyings);
    }

    /** Returns a declared name the stage does not write or one named twice, either of which fails the link, or null. */
    public static String unwritten(int program, String source) {
        final Declared declared = PROGRAMS.get(program);
        if (declared == null) return null;
        final String repeated = namedTwice(declared);
        if (repeated != null) return repeated;
        final String tooMany = overLimit(resolveAll(declared, source), declared.mode);
        if (tooMany != null) return tooMany;
        for (String name : declared.names) {
            if (!name.equals("gl_NextBuffer") && !name.startsWith("gl_SkipComponents") && resolve(name, source, 0) == null) return name;
        }
        return null;
    }

    /** A name past what MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS or MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS allow, which fails the link. */
    private static String overLimit(List<Varying> varyings, int mode) {
        final int[] used = new int[MAX_BUFFERS + 1];
        for (Varying varying : varyings) {
            if (mode == GL30.GL_SEPARATE_ATTRIBS) {
                if (varying.components() > 4 || varyings.size() > MAX_BUFFERS) return varying.name();
            } else if ((used[Math.min(varying.buffer(), MAX_BUFFERS)] += varying.components()) > 64) {
                return varying.name();
            }
        }
        return null;
    }

    private static String namedTwice(Declared declared) {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : declared.names) {
            if (!name.equals("gl_NextBuffer") && !name.startsWith("gl_SkipComponents") && !seen.add(name)) return name;
        }
        return null;
    }

    public static String declareFromStage(int program, String source) {
        final Declared declared = PROGRAMS.get(program);
        if (declared == null) return null;
        final String repeated = namedTwice(declared);
        if (repeated != null) return repeated;
        declared.resolved = resolveAll(declared, source);
        final String tooMany = overLimit(declared.resolved, declared.mode);
        if (tooMany != null) return tooMany;
        for (String name : declared.names) {
            if (!name.equals("gl_NextBuffer") && resolve(name, source, 0) == null) return name;
        }
        return null;
    }

    /** Separate attributes get a buffer each, and interleaved ones share one until gl_NextBuffer says otherwise. */
    private static List<Varying> resolveAll(Declared declared, String source) {
        final List<Varying> varyings = new ArrayList<>();
        java.util.Arrays.fill(declared.bufferStream, 0);
        declared.mixed = null;
        final boolean[] assigned = new boolean[MAX_BUFFERS];
        int buffer = 0;
        for (String name : declared.names) {
            if (name.equals("gl_NextBuffer")) {
                buffer++;
                continue;
            }
            final Varying varying = resolve(name, source, buffer);
            if (varying == null) continue;
            varyings.add(varying);
            if (!varying.skip() && buffer < MAX_BUFFERS) {
                final int stream = streamOf(source, varying.storedName());
                if (assigned[buffer] && declared.bufferStream[buffer] != stream) declared.mixed = name;
                declared.bufferStream[buffer] = stream;
                assigned[buffer] = true;
            }
            if (declared.mode == GL30.GL_SEPARATE_ATTRIBS) buffer++;
        }
        return varyings;
    }

    /** The stream a geometry shader's output belongs to: its own qualifier, or the default the last "layout(stream = n) out;" set. */
    private static int streamOf(String source, String name) {
        final Pattern named = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        final Matcher output = OUTPUT.matcher(source);
        int current = 0;
        while (output.find()) {
            final Matcher stream = STREAM.matcher(output.group(1) == null ? "" : output.group(1));
            final int given = stream.find() ? Integer.parseInt(stream.group(1)) : -1;
            final String rest = output.group(2), block = output.group(3);
            if (block == null && rest.isBlank()) {
                if (given >= 0) current = given;
                continue;
            }
            if (!named.matcher(block != null ? block : rest).find()) continue;
            if (block != null) {
                final Matcher member = Pattern.compile("\\blayout\\s*\\([^)]*\\bstream\\s*=\\s*(\\d+)[^)]*\\)[^;]*\\b" + Pattern.quote(name) + "\\b").matcher(block);
                if (member.find()) return Integer.parseInt(member.group(1));
            }
            return given >= 0 ? given : current;
        }
        return 0;
    }

    /** Returns a declared name sharing a capture buffer with one of another stream, which fails the link, or null. */
    public static String mixedStreams(int program) {
        final Declared declared = PROGRAMS.get(program);
        return declared == null ? null : declared.mixed;
    }

    public static boolean declaresVaryings(int program) {
        final Declared declared = PROGRAMS.get(program);
        return declared != null && declared.names.length > 0;
    }

    /** How many 32 bit words a value of this type takes in a capture buffer. */
    private static int componentsOf(String type) {
        final int words = isDouble(type) ? 2 : 1;
        if (type.contains("mat")) return columnsOf(type) * rowsOf(type) * words;
        final char last = type.charAt(type.length() - 1);
        return (Character.isDigit(last) ? last - '0' : 1) * words;
    }

    private static boolean isDouble(String type) {
        return type.startsWith("d");
    }

    private static int columnsOf(String type) {
        return type.charAt(type.indexOf("mat") + 3) - '0';
    }

    private static int rowsOf(String type) {
        return type.contains("x") ? type.charAt(type.length() - 1) - '0' : columnsOf(type);
    }

    private static final Pattern OUTPUT = Pattern.compile("(?:\\blayout\\s*\\(([^)]*)\\)\\s*)?(?:\\b(?:flat|smooth|noperspective|centroid|sample|invariant|precise|highp|mediump|lowp)\\s+)*\\bout\\b([^;{]*)(\\{[^}]*\\}[^;]*)?;");
    private static final Pattern STREAM = Pattern.compile("\\bstream\\s*=\\s*(\\d+)");
    private static final String TYPES = "(float|double|int|uint|[iud]?vec[234]|d?mat[234](?:x[234])?)";
    /** A capture buffer holds a double's two words, which go through one of floats as they are. */
    private static final String DOUBLE_WORD = """
        float angelica_feedback_doubleWord(double v, int word) {
            return uintBitsToFloat(unpackDouble2x32(v)[word]);
        }
        """;

    /** Finds a member of an output block, named by the block's type, in the block that declares it. */
    private static Varying resolveBlockMember(String name, String source, int buffer) {
        final int dot = name.lastIndexOf('.');
        final String blockType = dot < 0 ? null : name.substring(0, dot);
        final String member = dot < 0 ? name : name.substring(dot + 1);
        final int bracket = member.indexOf('[');
        final String bare = bracket < 0 ? member : member.substring(0, bracket);
        final String index = bracket < 0 ? "" : member.substring(bracket);
        final Matcher blocks = Pattern.compile("\\bout\\s+(\\w+)\\s*\\{([^}]*)\\}\\s*(\\w*)").matcher(source);
        while (blocks.find()) {
            if (blockType != null && !blockType.equals(blocks.group(1))) continue;
            final Matcher field = Pattern.compile("\\b" + TYPES + "\\s+" + Pattern.quote(bare) + "\\s*(\\[[^\\]]*\\])?\\s*;").matcher(blocks.group(2));
            if (!field.find()) continue;
            final String instance = blocks.group(3);
            final String type = field.group(1);
            return new Varying(name, type, componentsOf(type), (instance.isEmpty() ? "" : instance + ".") + bare + index, buffer);
        }
        return null;
    }

    /**
     * Writes the vertices at the given indices of what a stage stored, a primitive at a time, into the capture buffers. A primitive that does
     * not fit is left out and so are those after it, as in GL. An index of skipped is not a vertex.
     */
    public static void captureStored(ByteBuffer stored, int stride, Map<String, Integer> offsets, int[] indices, int skipped, int perPrimitive) {
        final State state = STATE.get();
        final Capture capture = state.current;
        if (!capture.active || capture.paused || capture.program == 0) return;
        final Declared declared = PROGRAMS.get(capture.program);
        if (declared == null || declared.resolved.isEmpty()) return;
        final List<Varying> varyings = declared.resolved;
        final int bufferCount = varyings.stream().mapToInt(Varying::buffer).max().orElse(-1) + 1;
        final int[] strides = new int[bufferCount];
        for (Varying varying : varyings) strides[varying.buffer()] += varying.components() * Float.BYTES;
        final Integer streamAt = offsets.get(STREAM_RECORD);
        final int generic = GlEntryHelpers.gl().getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int stream = 0; stream < STREAMS; stream++) {
            int kept = 0;
            for (int index : indices) {
                if (index != skipped && (streamAt == null ? 0 : stored.getInt(index * stride + streamAt)) == stream) kept++;
            }
            boolean writes = false;
            for (int b = 0; b < bufferCount; b++) writes |= strides[b] > 0 && declared.bufferStream[b] == stream;
            if (kept == 0 || !writes) continue;
            final ByteBuffer[] out = new ByteBuffer[bufferCount];
            for (int b = 0; b < bufferCount; b++) {
                if (declared.bufferStream[b] == stream) out[b] = ByteBuffer.allocate(kept * strides[b]).order(ByteOrder.nativeOrder());
            }
            for (int index : indices) {
                if (index == skipped || (streamAt == null ? 0 : stored.getInt(index * stride + streamAt)) != stream) continue;
                for (Varying varying : varyings) {
                    final ByteBuffer target = out[varying.buffer()];
                    if (target == null) continue;
                    final Integer offset = varying.skip() ? null : offsets.get(varying.storedName());
                    if (offset == null) {
                        target.position(target.position() + varying.components() * Float.BYTES);
                        continue;
                    }
                    final int at = index * stride + offset + varying.element() * elementBytes(varying);
                    if (isDouble(varying.type())) {
                        for (int c = 0; c < varying.components() / 2; c++) target.putDouble(stored.getDouble(at + c * Double.BYTES));
                    } else {
                        for (int c = 0; c < varying.components(); c++) target.putInt(stored.getInt(at + c * Float.BYTES));
                    }
                }
            }
            append(state, stream, out, strides, kept, perPrimitive, varyings);
        }
        GlEntryHelpers.gl().bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
    }

    /** Writes as many whole primitives as fit after what the stream has captured, and counts them. */
    private static void append(State state, int stream, ByteBuffer[] out, int[] strides, int kept, int perPrimitive, List<Varying> varyings) {
        final Capture capture = state.current;
        final int first = capture.vertices[stream];
        int fits = kept;
        for (int b = 0; b < out.length; b++) {
            if (b >= strides.length || capture.buffer[b] == 0 || out[b] == null || strides[b] == 0) continue;
            GlEntryHelpers.gl().bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, capture.buffer[b]);
            final long whole = GlEntryHelpers.gl().getBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER, GL15.GL_BUFFER_SIZE) - capture.offset[b];
            final long room = capture.size[b] > 0L ? Math.min(capture.size[b], whole) : whole;
            final long used = first * (long) strides[b];
            final long canHold = Math.max(0L, room - used) / strides[b];
            fits = (int) Math.min(fits, canHold - canHold % perPrimitive);
        }
        for (int b = 0; b < out.length; b++) {
            if (b >= strides.length || capture.buffer[b] == 0 || out[b] == null || strides[b] == 0 || fits == 0) continue;
            final ByteBuffer direct = ByteBuffer.allocateDirect(fits * strides[b]).order(ByteOrder.nativeOrder());
            direct.put(direct.position(), out[b], 0, fits * strides[b]);
            GlEntryHelpers.gl().bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, capture.buffer[b]);
            final long at = capture.offset[b] + first * (long) strides[b];
            final int buffer = b;
            if (varyings.stream().noneMatch(v -> v.buffer() == buffer && v.skip())) {
                GlEntryHelpers.gl().bufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, at, direct);
                continue;
            }
            // What a capture skips stays as it was, which another stream may have written.
            for (int vertex = 0; vertex < fits; vertex++) {
                int cursor = vertex * strides[b];
                for (Varying varying : varyings) {
                    if (varying.buffer() != b) continue;
                    final int bytes = varying.components() * Float.BYTES;
                    if (!varying.skip()) GlEntryHelpers.gl().bufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, at + cursor, direct.slice(cursor, bytes).order(ByteOrder.nativeOrder()));
                    cursor += bytes;
                }
            }
        }
        capture.vertices[stream] += fits;
        state.primitives[stream] += fits / perPrimitive;
        if (fits < kept) state.overflows[stream]++;
    }

    private static Varying resolve(String name, String source, int buffer) {
        if (name.startsWith("gl_SkipComponents")) {
            final int count = Integer.parseInt(name.substring("gl_SkipComponents".length()));
            return new Varying(name, "float", count, null, buffer);
        }
        if (name.equals("gl_Position") || name.equals("gl_FrontColor") || name.equals("gl_BackColor") || name.equals("gl_FrontSecondaryColor") || name.equals("gl_BackSecondaryColor") || name.startsWith("gl_TexCoord[")) {
            return new Varying(name, "vec4", 4, name, buffer);
        }
        if (name.equals("gl_PointSize") || name.equals("gl_FogFragCoord") || name.startsWith("gl_ClipDistance[")) return new Varying(name, "float", 1, name, buffer);
        if (name.equals("gl_VertexID")) return new Varying(name, "int", 1, name, buffer);
        final int bracket = name.indexOf('[');
        final String bare = bracket < 0 ? name : name.substring(0, bracket);
        if (name.indexOf('.') < 0) {
            final Matcher matcher = Pattern.compile("\\b(?:out|varying)\\s+(?:\\w+\\s+)*" + TYPES + "\\s+" + Pattern.quote(bare) + "\\b\\s*(?:\\[\\s*(\\d+)\\s*\\])?").matcher(source);
            if (matcher.find()) {
                // Named without an element, an array is captured whole.
                final int elements = bracket < 0 && matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
                return new Varying(name, matcher.group(1), componentsOf(matcher.group(1)) * elements, name, buffer, elements);
            }
        }
        return resolveBlockMember(name, source, buffer);
    }

    private static String rewrite(String source, List<Varying> varyings) {
        final StringBuilder blocks = new StringBuilder("#extension GL_ARB_shader_storage_buffer_object : require\n");
        blocks.append("uniform int ").append(ENABLED).append(";\nuniform int ").append(BASE).append(";\nuniform int ").append(PER_INSTANCE).append(";\nuniform int ").append(FIRST_INSTANCE).append(";\n");
        final int buffers = varyings.stream().mapToInt(Varying::buffer).max().orElse(-1) + 1;
        final int[] strides = new int[buffers], cursors = new int[buffers];
        for (Varying varying : varyings) strides[varying.buffer()] += varying.components();
        if (varyings.stream().anyMatch(v -> isDouble(v.type()))) blocks.append(DOUBLE_WORD);
        final StringBuilder store = new StringBuilder("    if (").append(ENABLED).append(" != 0) {\n");
        for (int i = 0; i < buffers; i++) {
            blocks.append("layout(std430, binding = ").append(storageBinding(i)).append(") buffer AngelicaFeedback").append(i);
            blocks.append(" { float angelica_feedback").append(i).append("[]; };\n");
        }
        for (Varying varying : varyings) {
            final int buffer = varying.buffer();
            for (int c = 0; c < varying.components(); c++) {
                final int cursor = cursors[buffer]++;
                if (varying.skip()) continue;
                store.append("        angelica_feedback").append(buffer).append("[(gl_VertexID + ").append(BASE).append(" + (gl_InstanceID - ").append(FIRST_INSTANCE).append(") * ").append(PER_INSTANCE).append(") * ").append(strides[buffer]);
                store.append(" + ").append(cursor).append("] = ").append(component(varying, c)).append(";\n");
            }
        }
        store.append("    }\n");
        final Matcher version = VERSION.matcher(source);
        final String declared = version.find()
            ? source.substring(0, version.end()) + "\n" + blocks + source.substring(version.end())
            : blocks + source;
        return MAIN.matcher(declared).replaceAll("void angelica_feedback_main(")
            + "\nvoid main() {\n    angelica_feedback_main();\n" + store + "}\n";
    }

    private static String component(Varying varying, int whole) {
        final int each = varying.components() / varying.elements();
        final String name = varying.elements() > 1 ? varying.expression() + "[" + whole / each + "]" : varying.expression();
        final String type = varying.type();
        if (isDouble(type)) {
            final int index = whole % each / 2;
            final String value = type.contains("mat") ? name + "[" + index / rowsOf(type) + "][" + index % rowsOf(type) + "]"
                : type.equals("double") ? name : name + "[" + index + "]";
            return "angelica_feedback_doubleWord(" + value + ", " + whole % 2 + ")";
        }
        final int index = whole % each;
        if (type.startsWith("mat")) {
            return name + "[" + index / rowsOf(type) + "][" + index % rowsOf(type) + "]";
        }
        final boolean scalar = type.equals("float") || type.equals("int") || type.equals("uint");
        final String value = scalar ? name : name + "[" + index + "]";
        if (type.startsWith("int") || type.startsWith("ivec")) return "intBitsToFloat(" + value + ")";
        if (type.startsWith("uint") || type.startsWith("uvec")) return "uintBitsToFloat(" + value + ")";
        return value;
    }

    /** The program's GL_TRANSFORM_FEEDBACK_* queries, or -1 for any other. */
    public static int programQuery(int program, int pname) {
        final Declared declared = PROGRAMS.get(program);
        return switch (pname) {
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER_MODE -> declared == null ? GL30.GL_INTERLEAVED_ATTRIBS : declared.mode;
            case GL30.GL_TRANSFORM_FEEDBACK_VARYINGS -> declared == null ? 0 : declared.resolved.size();
            case GL30.GL_TRANSFORM_FEEDBACK_VARYING_MAX_LENGTH -> {
                int longest = 0;
                if (declared != null) {
                    for (Varying varying : declared.resolved) longest = Math.max(longest, varying.name().length() + 1);
                }
                yield longest;
            }
            default -> -1;
        };
    }

    /** One entry of the transform feedback varying interface. A skip or gl_NextBuffer has no type, and end is where it leaves its buffer. */
    public record ResourceVarying(String name, int glType, int arraySize, int offset, int buffer, int end) {}

    static List<ResourceVarying> resourceVaryings(int program) {
        final Declared declared = PROGRAMS.get(program);
        final List<ResourceVarying> out = new ArrayList<>();
        if (declared == null) return out;
        final int[] used = new int[MAX_BUFFERS + 1];
        int next = 0, buffer = 0;
        for (String name : declared.names) {
            if (name.equals("gl_NextBuffer")) {
                out.add(new ResourceVarying(name, 0, 0, -1, buffer++, 0));
                continue;
            }
            if (next >= declared.resolved.size() || !declared.resolved.get(next).name().equals(name)) continue;
            final Varying varying = declared.resolved.get(next);
            final int bytes = varying.components() * Integer.BYTES;
            final int at = used[varying.buffer()];
            used[varying.buffer()] = at + bytes;
            buffer = varying.buffer();
            out.add(varying.skip() ? new ResourceVarying(name, 0, varying.components(), -1, buffer, at + bytes)
                : new ResourceVarying(name, varyingType(program, next), varying.elements(), at, buffer, at + bytes));
            next++;
        }
        return out;
    }

    static String varyingName(int program, int index) {
        final Declared declared = PROGRAMS.get(program);
        return declared == null || index < 0 || index >= declared.resolved.size() ? "" : declared.resolved.get(index).name();
    }

    public static int varyingCount(int program) {
        final Declared declared = PROGRAMS.get(program);
        return declared == null ? 0 : declared.resolved.size();
    }

    static int varyingType(int program, int index) {
        final Declared declared = PROGRAMS.get(program);
        if (declared == null || index < 0 || index >= declared.resolved.size()) return 0;
        return switch (declared.resolved.get(index).type()) {
            case "float" -> GL11.GL_FLOAT;
            case "vec2" -> GL20.GL_FLOAT_VEC2;
            case "vec3" -> GL20.GL_FLOAT_VEC3;
            case "vec4" -> GL20.GL_FLOAT_VEC4;
            case "int" -> GL11.GL_INT;
            case "ivec2" -> GL20.GL_INT_VEC2;
            case "ivec3" -> GL20.GL_INT_VEC3;
            case "ivec4" -> GL20.GL_INT_VEC4;
            case "uint" -> GL11.GL_UNSIGNED_INT;
            case "uvec2" -> GL30.GL_UNSIGNED_INT_VEC2;
            case "uvec3" -> GL30.GL_UNSIGNED_INT_VEC3;
            case "uvec4" -> GL30.GL_UNSIGNED_INT_VEC4;
            case "mat2" -> GL20.GL_FLOAT_MAT2;
            case "mat3" -> GL20.GL_FLOAT_MAT3;
            case "mat4" -> GL20.GL_FLOAT_MAT4;
            case "mat2x3" -> GL21.GL_FLOAT_MAT2x3;
            case "mat2x4" -> GL21.GL_FLOAT_MAT2x4;
            case "mat3x2" -> GL21.GL_FLOAT_MAT3x2;
            case "mat3x4" -> GL21.GL_FLOAT_MAT3x4;
            case "mat4x2" -> GL21.GL_FLOAT_MAT4x2;
            case "mat4x3" -> GL21.GL_FLOAT_MAT4x3;
            case "double" -> GL11.GL_DOUBLE;
            case "dvec2" -> GL40.GL_DOUBLE_VEC2;
            case "dvec3" -> GL40.GL_DOUBLE_VEC3;
            case "dvec4" -> GL40.GL_DOUBLE_VEC4;
            case "dmat2" -> GL40.GL_DOUBLE_MAT2;
            case "dmat3" -> GL40.GL_DOUBLE_MAT3;
            case "dmat4" -> GL40.GL_DOUBLE_MAT4;
            default -> 0;
        };
    }

    static void begin(int primitiveMode) {
        final State state = STATE.get();
        final int program = GLStateManager.ctx().activeProgram;
        final Capture capture = state.current;
        capture.active = true;
        capture.paused = false;
        capture.primitiveMode = primitiveMode;
        capture.program = program;
        java.util.Arrays.fill(capture.vertices, 0);
        final int generic = rb().getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int i = 0; i < MAX_BUFFERS; i++) {
            GlEntryHelpers.gl().bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, storageBinding(i), capture.buffer[i], capture.offset[i], capture.size[i]);
        }
        rb().bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        setEnabled(capture, true);
    }

    static void pause() {
        final State state = STATE.get();
        state.current.paused = true;
        setEnabled(state.current, false);
    }

    static void resume() {
        final State state = STATE.get();
        state.current.paused = false;
        // Another object may have captured since this one paused.
        for (int i = 0; i < MAX_BUFFERS; i++) {
            GlEntryHelpers.gl().bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, storageBinding(i), state.current.buffer[i], state.current.offset[i], state.current.size[i]);
        }
        setEnabled(state.current, true);
    }

    static void end() {
        final State state = STATE.get();
        final Capture capture = state.current;
        capture.active = false;
        System.arraycopy(capture.vertices, 0, capture.drawable, 0, STREAMS);
        capture.ended = true;
        for (int i = 0; i < MAX_BUFFERS; i++) GlEntryHelpers.gl().markBufferWritten(capture.buffer[i]);
        setEnabled(capture, false);
        final int generic = rb().getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int i = 0; i < MAX_BUFFERS; i++) GlEntryHelpers.gl().bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, storageBinding(i), 0);
        rb().bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        rb().memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
    }

    private static void setEnabled(Capture capture, boolean enabled) {
        if (capture.program == 0) return;
        final int location = rb().getUniformLocation(capture.program, ENABLED);
        if (location >= 0) rb().uniform1i(location, enabled ? 1 : 0);
    }

    public static boolean capturing() {
        final Capture capture = STATE.get().current;
        return capture.active && !capture.paused;
    }

    /** Runs ahead of a draw while capturing, to continue after what the last draw wrote. */
    public static void noteDraw(int mode, int first, int count, int instances, int firstInstance, boolean discarded) {
        final State state = STATE.get();
        if (state.inParts || discarded) return;
        state.generated[0] += primitivesIn(mode, Math.max(0, count)) * Math.max(0, instances);
        final Capture capture = state.current;
        if (!capture.active || capture.paused || capture.program == 0) return;
        feedbackUniform(capture, BASE, capture.vertices[0] - first);
        feedbackUniform(capture, PER_INSTANCE, count);
        feedbackUniform(capture, FIRST_INSTANCE, firstInstance);
        final int total = Math.max(0, count) * Math.max(0, instances);
        capture.vertices[0] += total;
        state.primitives[0] += total / verticesPerPrimitive(capture.primitiveMode);
        if (outgrew(capture)) state.overflows[0]++;
    }

    /** What capture needs of the program: something declared to capture, and a buffer for it. */
    public static boolean canCapture(int program) {
        final State state = STATE.get();
        if (program == 0) return true;
        final Declared declared = PROGRAMS.get(program);
        if (declared == null || declared.names.length == 0) return false;
        for (Varying varying : declared.resolved) {
            if (varying.buffer() < MAX_BUFFERS && state.current.buffer[varying.buffer()] == 0) return false;
        }
        return true;
    }

    /** Whether a capture still underway, paused or not, is writing what this program declares. */
    public static boolean capturedBy(int program) {
        final Capture current = STATE.get().current;
        if (current.active && current.program == program) return true;
        for (Capture capture : CAPTURES.values()) {
            if (capture.active && capture.program == program) return true;
        }
        return false;
    }

    public static boolean isActive(int name) {
        final Capture capture = CAPTURES.get(name);
        return capture != null && capture.active;
    }

    public static int capturedMode() {
        return STATE.get().current.primitiveMode;
    }

    /** The current object's answer to a query of the context's state. */
    public static int currentState(int pname) {
        final State state = STATE.get();
        return pname == GL40.GL_TRANSFORM_FEEDBACK_BINDING ? state.currentName : query(state.currentName, pname);
    }

    /** A pipeline captures what the program of its last stage before rasterizing declares. */
    static void inherit(int program, int from) {
        final Declared declared = PROGRAMS.get(from);
        if (declared != null) PROGRAMS.put(program, new Declared(declared.names, declared.mode));
    }

    /** Whether the object has seen a capture end, which glDrawTransformFeedback needs to have something to draw. */
    public static boolean hasEnded(int name) {
        final Capture capture = capture(name);
        return capture != null && capture.ended;
    }

    /** How many bytes a vertex takes in each buffer of the current capture. */
    public static int[] strideBytes() {
        final int[] strides = new int[MAX_BUFFERS];
        final Declared declared = PROGRAMS.get(STATE.get().current.program);
        if (declared != null) {
            for (Varying varying : declared.resolved) strides[varying.buffer()] += varying.components() * Float.BYTES;
        }
        return strides;
    }

    /** Has the vertex shader store into these, by vertex and then by instance, rather than into the capture buffers. */
    public static void storeIn(int[] buffers, long[] bytes, int lowest, int span, int firstInstance) {
        final Capture capture = STATE.get().current;
        for (int b = 0; b < MAX_BUFFERS; b++) {
            if (bytes[b] > 0L) GlEntryHelpers.gl().bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, storageBinding(b), buffers[b], 0L, bytes[b]);
        }
        feedbackUniform(capture, BASE, -lowest);
        feedbackUniform(capture, PER_INSTANCE, span);
        feedbackUniform(capture, FIRST_INSTANCE, firstInstance);
    }

    public static void storeInCaptureBuffers() {
        final Capture capture = STATE.get().current;
        for (int b = 0; b < MAX_BUFFERS; b++) {
            GlEntryHelpers.gl().bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, storageBinding(b), capture.buffer[b], capture.offset[b], capture.size[b]);
        }
    }

    /** Appends the stored vertices in this order to the capture buffers, whole primitives while they fit. */
    public static void writeVertices(ByteBuffer[] stored, int[] order, int perPrimitive) {
        final State state = STATE.get();
        final int[] strides = strideBytes();
        final ByteBuffer[] out = new ByteBuffer[MAX_BUFFERS];
        for (int b = 0; b < MAX_BUFFERS; b++) {
            if (strides[b] == 0) continue;
            out[b] = ByteBuffer.allocate(order.length * strides[b]).order(ByteOrder.nativeOrder());
            for (int vertex : order) {
                out[b].put(out[b].position(), stored[b], vertex * strides[b], strides[b]);
                out[b].position(out[b].position() + strides[b]);
            }
        }
        state.generated[0] += order.length / perPrimitive;
        append(state, 0, out, strides, order.length, perPrimitive, PROGRAMS.get(state.current.program).resolved);
    }

    public static long primitivesIn(int mode, int count) {
        return Math.max(0, switch (mode) {
            case GL11.GL_POINTS, GL11.GL_LINE_LOOP -> count;
            case GL11.GL_LINES -> count / 2;
            case GL11.GL_LINE_STRIP -> count - 1;
            case GL11.GL_TRIANGLES -> count / 3;
            case GL11.GL_TRIANGLE_STRIP, GL11.GL_TRIANGLE_FAN -> count - 2;
            case GL11.GL_QUADS -> count / 4 * 2;
            case GL11.GL_QUAD_STRIP -> (count - 2) / 2 * 2;
            case GL11.GL_POLYGON -> count - 2;
            case GL32.GL_LINES_ADJACENCY -> count / 4;
            case GL32.GL_LINE_STRIP_ADJACENCY -> count - 3;
            case GL32.GL_TRIANGLES_ADJACENCY -> count / 6;
            case GL32.GL_TRIANGLE_STRIP_ADJACENCY -> (count - 4) / 2;
            default -> count;
        });
    }

    private static boolean outgrew(Capture capture) {
        final Declared declared = PROGRAMS.get(capture.program);
        if (declared == null) return false;
        final long[] stride = new long[MAX_BUFFERS];
        for (Varying varying : declared.resolved) stride[varying.buffer()] += (long) varying.components() * varying.elements() * Float.BYTES;
        for (int b = 0; b < MAX_BUFFERS; b++) {
            if (capture.buffer[b] == 0 || stride[b] == 0L) continue;
            final BufBase buffer = GlEntryHelpers.gl().buffers().get(capture.buffer[b]);
            if (buffer == null) continue;
            final long whole = buffer.size - capture.offset[b];
            if (capture.vertices[0] * stride[b] > (capture.size[b] > 0L ? Math.min(capture.size[b], whole) : whole)) return true;
        }
        return false;
    }

    /** How many draws so far wrote past the end of a buffer of this stream, which the overflow queries watch for. */
    public static long overflows(int stream) {
        return STATE.get().overflows[stream];
    }

    public static long overflowsInAnyStream() {
        long total = 0L;
        for (long overflows : STATE.get().overflows) total += overflows;
        return total;
    }

    /** What an array element takes in a stored record by std430: a double counts as two words. */
    private static int elementBytes(Varying varying) {
        final int words = varying.components();
        return words == 1 ? Float.BYTES : words == 2 ? 2 * Float.BYTES : words <= 4 ? 4 * Float.BYTES : 8 * Float.BYTES;
    }

    private static void feedbackUniform(Capture capture, String name, int value) {
        // Called from inside a draw, which already holds the backend, so this cannot go through rb() and leave it unlocked.
        final int location = GlEntryHelpers.gl().getUniformLocation(capture.program, name);
        if (location >= 0) GlEntryHelpers.gl().uniform1i(location, value);
    }

    private static int verticesPerPrimitive(int mode) {
        return mode == GL11.GL_POINTS ? 1 : mode == GL11.GL_LINES ? 2 : 3;
    }

    public static boolean inParts() {
        return STATE.get().inParts;
    }

    public static void drawingInParts(boolean inParts) {
        STATE.get().inParts = inParts;
    }

    public static long generatedPrimitives(int stream) {
        return STATE.get().generated[stream];
    }

    /** For a stage run as a compute shader, as a draw leaves it with no vertices to count. */
    public static void noteGenerated(int stream, long primitives) {
        STATE.get().generated[stream] += primitives;
    }

    public static long capturedPrimitives(int stream) {
        return STATE.get().primitives[stream];
    }

    /** glGetIntegeri_v of the current object's bindings, or -1 for any other query. */
    public static long indexedQuery(int pname, int index) {
        if (pname != GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING && pname != GL30.GL_TRANSFORM_FEEDBACK_BUFFER_START && pname != GL30.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE) return -1L;
        return bufferQuery(STATE.get().currentName, pname, index);
    }

    static int vertices(int name) {
        return vertices(name, 0);
    }

    static int vertices(int name, int stream) {
        final Capture capture = capture(name);
        return capture == null ? 0 : capture.drawable[stream];
    }

    static int query(int name, int pname) {
        final Capture capture = capture(name);
        if (capture == null) return 0;
        return switch (pname) {
            case GL40.GL_TRANSFORM_FEEDBACK_BUFFER_ACTIVE -> capture.active ? 1 : 0;
            case GL40.GL_TRANSFORM_FEEDBACK_BUFFER_PAUSED -> capture.paused ? 1 : 0;
            default -> 0;
        };
    }

    static long bufferQuery(int name, int pname, int index) {
        final Capture capture = capture(name);
        if (capture == null || index < 0 || index >= MAX_BUFFERS) return 0L;
        return switch (pname) {
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING -> capture.buffer[index];
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER_START -> capture.offset[index];
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE -> capture.size[index];
            default -> 0L;
        };
    }
}
