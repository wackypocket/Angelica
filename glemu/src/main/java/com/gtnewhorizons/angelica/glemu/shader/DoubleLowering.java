package com.gtnewhorizons.angelica.glemu.shader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes doubles out of a module for devices that have none, leaving arithmetic done by {@link SoftDouble}. A double becomes a uvec2 of its words, a
 * dvec an array of those, and a dmat an array of columns. In a buffer the columns of a dmat with three rows are wrapped in a struct, since Metal has no
 * nested arrays with a stride of their own. Values crossing between stages go as their words (see wordType), so each stage packs them alike.
 */
public final class DoubleLowering {
    private static final int OP_UNDEF = 1, OP_SOURCE_CONTINUED = 2, OP_SOURCE = 3, OP_SOURCE_EXTENSION = 4, OP_NAME = 5, OP_MEMBER_NAME = 6, OP_STRING = 7, OP_LINE = 8;
    private static final int OP_EXTENSION = 10, OP_EXT_INST_IMPORT = 11, OP_EXT_INST = 12, OP_MEMORY_MODEL = 14, OP_ENTRY_POINT = 15, OP_EXECUTION_MODE = 16, OP_CAPABILITY = 17;
    private static final int OP_TYPE_VOID = 19, OP_TYPE_BOOL = 20, OP_TYPE_INT = 21, OP_TYPE_FLOAT = 22, OP_TYPE_VECTOR = 23, OP_TYPE_MATRIX = 24, OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLED_IMAGE = 27;
    private static final int OP_TYPE_ARRAY = 28, OP_TYPE_RUNTIME_ARRAY = 29, OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32, OP_TYPE_FUNCTION = 33;
    private static final int OP_CONSTANT_TRUE = 41, OP_CONSTANT_FALSE = 42, OP_CONSTANT = 43, OP_CONSTANT_COMPOSITE = 44, OP_CONSTANT_NULL = 46;
    private static final int OP_SPEC_TRUE = 48, OP_SPEC_FALSE = 49, OP_SPEC_CONSTANT = 50, OP_SPEC_COMPOSITE = 51;
    private static final int OP_FUNCTION = 54, OP_FUNCTION_PARAMETER = 55, OP_FUNCTION_END = 56, OP_FUNCTION_CALL = 57, OP_VARIABLE = 59, OP_LOAD = 61, OP_STORE = 62, OP_COPY_MEMORY = 63;
    private static final int OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66, OP_DECORATE = 71, OP_MEMBER_DECORATE = 72, OP_VECTOR_EXTRACT_DYNAMIC = 77, OP_VECTOR_INSERT_DYNAMIC = 78;
    private static final int OP_VECTOR_SHUFFLE = 79, OP_COMPOSITE_CONSTRUCT = 80, OP_COMPOSITE_EXTRACT = 81, OP_COMPOSITE_INSERT = 82, OP_COPY_OBJECT = 83, OP_TRANSPOSE = 84;
    private static final int OP_CONVERT_F_TO_U = 109, OP_CONVERT_F_TO_S = 110, OP_CONVERT_S_TO_F = 111, OP_CONVERT_U_TO_F = 112, OP_F_CONVERT = 115;
    private static final int OP_F_NEGATE = 127, OP_F_ADD = 129, OP_F_SUB = 131, OP_F_MUL = 133, OP_F_DIV = 136, OP_F_REM = 140, OP_F_MOD = 141;
    private static final int OP_VECTOR_TIMES_SCALAR = 142, OP_MATRIX_TIMES_SCALAR = 143, OP_VECTOR_TIMES_MATRIX = 144, OP_MATRIX_TIMES_VECTOR = 145, OP_MATRIX_TIMES_MATRIX = 146, OP_OUTER_PRODUCT = 147, OP_DOT = 148;
    private static final int OP_IS_NAN = 156, OP_IS_INF = 157, OP_LOGICAL_NOT = 168, OP_LOGICAL_OR = 166, OP_LOGICAL_AND = 167, OP_SELECT = 169;
    private static final int OP_F_ORD_EQUAL = 180, OP_F_UNORD_GREATER_EQUAL = 191;
    private static final int OP_EMIT_VERTEX = 218, OP_END_PRIMITIVE = 219, OP_EMIT_STREAM_VERTEX = 220, OP_END_STREAM_PRIMITIVE = 221, OP_CONTROL_BARRIER = 224, OP_MEMORY_BARRIER = 225, OP_ATOMIC_STORE = 228;
    private static final int OP_PHI = 245, OP_LOOP_MERGE = 246, OP_SELECTION_MERGE = 247, OP_LABEL = 248, OP_BRANCH = 249, OP_BRANCH_CONDITIONAL = 250, OP_SWITCH = 251, OP_KILL = 252, OP_RETURN = 253, OP_RETURN_VALUE = 254, OP_UNREACHABLE = 255;
    private static final int OP_IMAGE_WRITE = 99, OP_NO_LINE = 317, OP_MODULE_PROCESSED = 330, OP_EXECUTION_MODE_ID = 331, OP_DECORATE_ID = 332, OP_COPY_LOGICAL = 400, OP_DECORATE_STRING = 5632, OP_MEMBER_DECORATE_STRING = 5633;
    private static final int OP_TERMINATE_INVOCATION = 4416, OP_DEMOTE = 5380, OP_INTERLOCK_BEGIN = 5364, OP_INTERLOCK_END = 5365;

    private static final int OP_IADD = 128, OP_IMUL = 132;
    private static final int OP_BITCAST = 124, MODEL_VERTEX = 0, MODEL_TESS_CONTROL = 1, MODEL_FRAGMENT = 4;
    private static final int DEC_FLAT = 14, DEC_PATCH = 15, DEC_LOCATION = 30, DEC_BUILTIN = 11, BUILTIN_INVOCATION_ID = 8;
    private static final int DEC_ROW_MAJOR = 4, DEC_COL_MAJOR = 5, DEC_ARRAY_STRIDE = 6, DEC_MATRIX_STRIDE = 7, DEC_OFFSET = 35;
    private static final int SC_INPUT = 1, SC_UNIFORM = 2, SC_OUTPUT = 3, SC_PRIVATE = 6, SC_FUNCTION = 7, SC_PUSH_CONSTANT = 9, SC_STORAGE_BUFFER = 12;
    private static final int CAP_FLOAT64 = 10, CAP_INT64 = 11;

    private static final int GLSL_ROUND = 1, GLSL_ROUND_EVEN = 2, GLSL_TRUNC = 3, GLSL_F_ABS = 4, GLSL_F_SIGN = 6, GLSL_FLOOR = 8, GLSL_CEIL = 9, GLSL_FRACT = 10;
    private static final int GLSL_SQRT = 31, GLSL_INVERSE_SQRT = 32, GLSL_DETERMINANT = 33, GLSL_MATRIX_INVERSE = 34, GLSL_MODF = 35, GLSL_MODF_STRUCT = 36;
    private static final int GLSL_F_MIN = 37, GLSL_F_MAX = 40, GLSL_F_CLAMP = 43, GLSL_F_MIX = 46, GLSL_STEP = 48, GLSL_SMOOTH_STEP = 49, GLSL_FMA = 50, GLSL_FREXP = 51, GLSL_FREXP_STRUCT = 52, GLSL_LDEXP = 53;
    private static final int GLSL_PACK_DOUBLE = 59, GLSL_UNPACK_DOUBLE = 65, GLSL_LENGTH = 66, GLSL_DISTANCE = 67, GLSL_CROSS = 68, GLSL_NORMALIZE = 69, GLSL_FACE_FORWARD = 70, GLSL_REFLECT = 71, GLSL_REFRACT = 72;
    private static final int GLSL_N_MIN = 79, GLSL_N_MAX = 80, GLSL_N_CLAMP = 81;

    public static boolean hasDoubles(int[] w) {
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            final int op = w[i] & 0xFFFF;
            if (op == OP_TYPE_FLOAT && w[i + 2] == 64) return true;
            if (op == OP_FUNCTION) return false;
        }
        return false;
    }

    public static int[] lower(int[] words) {
        return lower(words, false);
    }

    /**
     * With the device's own doubles, only a vertex shader's double inputs change: they arrive as words, as no vertex format a device must
     * offer holds them. Each dvec3 or dvec4 takes its second half from a location of its own from {@link #HIGH_LOCATIONS} on, in the order of theirs.
     */
    public static int[] lower(int[] words, boolean nativeDoubles) {
        if (!hasDoubles(words)) return words;
        final DoubleLowering lowering = new DoubleLowering(words, nativeDoubles);
        return nativeDoubles ? lowering.runNative() : lowering.run();
    }

    public static final int HIGH_LOCATIONS = 16;

    /** Whether the software doubles may work in 64 bit integers, which a device with them runs faster than pairs of words. */
    public static void useInt64(boolean available) {
        SoftDouble.int64 = available;
    }

    private static int[] inst(int op, int... operands) {
        final int[] out = new int[operands.length + 1];
        out[0] = (operands.length + 1) << 16 | op;
        System.arraycopy(operands, 0, out, 1, operands.length);
        return out;
    }

    private static int op(int[] i) {
        return i[0] & 0xFFFF;
    }

    private static boolean hasTypeAndResult(int op) {
        return switch (op) {
            case OP_SOURCE_CONTINUED, OP_SOURCE, OP_SOURCE_EXTENSION, OP_NAME, OP_MEMBER_NAME, OP_STRING, OP_LINE, OP_NO_LINE, OP_EXTENSION, OP_EXT_INST_IMPORT, OP_MEMORY_MODEL, OP_ENTRY_POINT,
                OP_EXECUTION_MODE, OP_CAPABILITY, OP_FUNCTION_END, OP_STORE, OP_COPY_MEMORY, OP_DECORATE, OP_MEMBER_DECORATE, OP_LABEL, OP_BRANCH, OP_BRANCH_CONDITIONAL, OP_SWITCH, OP_KILL,
                OP_RETURN, OP_RETURN_VALUE, OP_UNREACHABLE, OP_LOOP_MERGE, OP_SELECTION_MERGE, OP_EMIT_VERTEX, OP_END_PRIMITIVE, OP_EMIT_STREAM_VERTEX, OP_END_STREAM_PRIMITIVE, OP_CONTROL_BARRIER,
                OP_MEMORY_BARRIER, OP_ATOMIC_STORE, OP_IMAGE_WRITE, OP_TERMINATE_INVOCATION, OP_DEMOTE, OP_INTERLOCK_BEGIN, OP_INTERLOCK_END, 0 -> false;
            default -> true;
        };
    }

    /** Rewrites each word of a function's instruction that is an id, leaving literals alone. */
    private static void mapIds(int[] i, java.util.function.IntUnaryOperator map) {
        final int op = op(i);
        final int from;
        final int to;
        switch (op) {
            case OP_EXT_INST -> {
                i[1] = map.applyAsInt(i[1]);
                i[2] = map.applyAsInt(i[2]);
                i[3] = map.applyAsInt(i[3]);
                for (int k = 5; k < i.length; k++) i[k] = map.applyAsInt(i[k]);
                return;
            }
            case OP_COMPOSITE_EXTRACT, OP_LOAD -> {
                from = 1;
                to = 4;
            }
            case OP_COMPOSITE_INSERT, OP_VECTOR_SHUFFLE -> {
                from = 1;
                to = 5;
            }
            case OP_STORE -> {
                from = 1;
                to = 3;
            }
            case OP_VARIABLE -> {
                i[1] = map.applyAsInt(i[1]);
                i[2] = map.applyAsInt(i[2]);
                if (i.length > 4) i[4] = map.applyAsInt(i[4]);
                return;
            }
            case OP_FUNCTION -> {
                i[1] = map.applyAsInt(i[1]);
                i[2] = map.applyAsInt(i[2]);
                i[4] = map.applyAsInt(i[4]);
                return;
            }
            case OP_SELECTION_MERGE -> {
                from = 1;
                to = 2;
            }
            case OP_LOOP_MERGE -> {
                from = 1;
                to = 3;
            }
            case OP_BRANCH_CONDITIONAL -> {
                from = 1;
                to = 4;
            }
            case OP_SWITCH -> {
                i[1] = map.applyAsInt(i[1]);
                i[2] = map.applyAsInt(i[2]);
                for (int k = 4; k < i.length; k += 2) i[k] = map.applyAsInt(i[k]);
                return;
            }
            case OP_LINE, OP_NO_LINE -> {
                return;
            }
            default -> {
                from = 1;
                to = i.length;
            }
        }
        for (int k = from; k < Math.min(to, i.length); k++) i[k] = map.applyAsInt(i[k]);
    }

    private final int[] source;
    private int bound;
    private final List<int[]> capabilities = new ArrayList<>(), extensions = new ArrayList<>(), imports = new ArrayList<>(), head = new ArrayList<>(), entryPoints = new ArrayList<>();
    private final List<int[]> names = new ArrayList<>(), annotations = new ArrayList<>(), globals = new ArrayList<>(), libGlobals = new ArrayList<>();
    private final List<List<int[]>> functions = new ArrayList<>();
    private final Map<Integer, int[]> orig = new HashMap<>(), defined = new HashMap<>();
    private final Map<String, Integer> interned = new HashMap<>();
    private final Map<Integer, Integer> valueType = new HashMap<>(), bufferMatrix = new HashMap<>();
    private final Set<Integer> strided = new HashSet<>(), offsetStructs = new HashSet<>();
    private int f64, uvec2, uint, sint, f32, bool, glsl, wrap3, mainFunction;

    // The library, by function name.
    private final Map<String, int[]> libFunction = new HashMap<>();
    private final Map<Integer, List<int[]>> libBodies = new LinkedHashMap<>();
    private final Set<Integer> usedLib = new HashSet<>();

    // Stage crossings: the variable the shader's values live in, and the variable of words that crosses.
    private final Map<Integer, Integer> crossing = new LinkedHashMap<>();
    private final Map<Integer, Integer> crossingType = new HashMap<>();

    // The function being lowered.
    private List<int[]> out;
    private Map<Integer, List<Integer>> temps;
    private List<int[]> tempVariables;
    private Map<Integer, Integer> tempsTaken;

    private final boolean nativeDoubles;
    private int executionModel = -1;

    private DoubleLowering(int[] words, boolean nativeDoubles) {
        this.source = words;
        this.bound = words[3];
        this.nativeDoubles = nativeDoubles;
    }

    private int fresh() {
        return bound++;
    }

    private int[] runNative() {
        parse();
        discoverTypes();
        if (executionModel != MODEL_VERTEX) return source;
        for (int[] g : globals) if (op(g) == OP_TYPE_POINTER && g[2] == SC_INPUT && containsDouble(g[3])) g[2] = SC_PRIVATE;
        findWideSlots();
        for (int[] g : new ArrayList<>(globals)) {
            if (op(g) != OP_VARIABLE || g[3] != SC_INPUT || executionModel != MODEL_VERTEX) continue;
            final int[] pointer = orig.get(g[1]);
            if (pointer != null && containsDouble(pointer[3])) planVertexInput(g, g[2], pointer[3]);
        }
        if (vertexInputs.isEmpty()) return source;
        final List<List<int[]>> kept = new ArrayList<>();
        for (List<int[]> function : functions) {
            if (function.get(0)[2] != mainFunction) {
                kept.add(function);
                continue;
            }
            final List<int[]> copied = new ArrayList<>();
            out = copied;
            boolean placed = false;
            for (int k = 0; k < function.size(); k++) {
                final int[] in = function.get(k);
                if (!placed && k > 0 && op(function.get(k - 1)) != OP_FUNCTION && op(in) != OP_VARIABLE && op(in) != OP_LABEL && op(in) != OP_FUNCTION_PARAMETER) {
                    copyVertexInputs();
                    placed = true;
                }
                copied.add(in);
            }
            kept.add(copied);
        }
        return assemble(kept);
    }

    private int[] run() {
        parse();
        lowerTypes();
        mergeLibrary();
        final List<List<int[]>> lowered = new ArrayList<>();
        for (List<int[]> function : functions) lowered.add(lowerFunction(function));
        return assemble(lowered);
    }

    private void parse() {
        List<int[]> current = null;
        for (int i = 5; i < source.length; ) {
            final int count = source[i] >>> 16;
            final int[] in = Arrays.copyOfRange(source, i, i + count);
            i += count;
            final int op = op(in);
            if (current != null) {
                current.add(in);
                if (op == OP_FUNCTION_END) current = null;
                continue;
            }
            switch (op) {
                case OP_CAPABILITY -> {
                    if (in[1] != CAP_FLOAT64 || nativeDoubles) capabilities.add(in);
                }
                case OP_EXTENSION -> extensions.add(in);
                case OP_EXT_INST_IMPORT -> {
                    imports.add(in);
                    glsl = in[1];
                }
                case OP_ENTRY_POINT -> {
                    entryPoints.add(in);
                    mainFunction = in[2];
                    if (executionModel < 0) executionModel = in[1];
                }
                case OP_MEMORY_MODEL, OP_EXECUTION_MODE, OP_EXECUTION_MODE_ID -> head.add(in);
                case OP_SOURCE, OP_SOURCE_CONTINUED, OP_SOURCE_EXTENSION, OP_STRING, OP_MODULE_PROCESSED, OP_LINE, OP_NO_LINE -> {
                }
                case OP_NAME, OP_MEMBER_NAME -> names.add(in);
                case OP_DECORATE, OP_MEMBER_DECORATE, OP_DECORATE_ID, OP_DECORATE_STRING, OP_MEMBER_DECORATE_STRING -> annotations.add(in);
                case OP_FUNCTION -> {
                    current = new ArrayList<>();
                    current.add(in);
                    functions.add(current);
                }
                default -> {
                    globals.add(in);
                    final boolean typed = hasType(op);
                    final int id = in[typed ? 2 : 1];
                    defined.put(id, in);
                    orig.put(id, in.clone());
                    if (typed) valueType.put(id, in[1]);
                }
            }
        }
        for (int[] a : annotations) {
            if (op(a) == OP_DECORATE && a[2] == DEC_ARRAY_STRIDE) strided.add(a[1]);
            if (op(a) == OP_MEMBER_DECORATE && a[3] == DEC_OFFSET) offsetStructs.add(a[1]);
        }
    }

    private static boolean hasType(int op) {
        return switch (op) {
            case OP_TYPE_VOID, OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_IMAGE, 26, OP_TYPE_SAMPLED_IMAGE, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY, OP_TYPE_STRUCT, 31,
                OP_TYPE_POINTER, OP_TYPE_FUNCTION, 34, 35, 36, 37, 38, 39, 322, 327 -> false;
            default -> true;
        };
    }

    private boolean containsDouble(int type) {
        final int[] t = orig.get(type);
        if (t == null) return false;
        return switch (op(t)) {
            case OP_TYPE_FLOAT -> t[2] == 64;
            case OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> containsDouble(t[2]);
            case OP_TYPE_POINTER -> containsDouble(t[3]);
            case OP_TYPE_STRUCT -> {
                for (int k = 2; k < t.length; k++) if (containsDouble(t[k])) yield true;
                yield false;
            }
            default -> false;
        };
    }

    private boolean isDouble(int type) {
        final int[] t = orig.get(type);
        return t != null && op(t) == OP_TYPE_FLOAT && t[2] == 64;
    }

    private boolean isDvec(int type) {
        final int[] t = orig.get(type);
        return t != null && op(t) == OP_TYPE_VECTOR && isDouble(t[2]);
    }

    private boolean isDmat(int type) {
        final int[] t = orig.get(type);
        return t != null && op(t) == OP_TYPE_MATRIX && isDouble(orig.get(t[2])[2]);
    }

    private int vectorSize(int type) {
        return orig.get(type)[3];
    }

    private int matrixColumnType(int type) {
        return orig.get(type)[2];
    }

    private int matrixColumns(int type) {
        return orig.get(type)[3];
    }

    private int matrixRows(int type) {
        return vectorSize(matrixColumnType(type));
    }

    private boolean isBufferContext(int type) {
        final int[] t = orig.get(type);
        if (t == null) return false;
        return op(t) == OP_TYPE_STRUCT ? offsetStructs.contains(type) : (op(t) == OP_TYPE_ARRAY || op(t) == OP_TYPE_RUNTIME_ARRAY) && strided.contains(type);
    }

    private static boolean isBufferClass(int storage) {
        return storage == SC_UNIFORM || storage == SC_STORAGE_BUFFER || storage == SC_PUSH_CONSTANT;
    }

    /** What a dmat with three rows is in a buffer, or the type itself for everything else. */
    private int bufferType(int type) {
        if (isDouble(type)) return uvec2;
        if (!isDmat(type) || matrixRows(type) != 3) return type;
        return bufferMatrix.computeIfAbsent(type, t -> {
            final int id = fresh();
            globals.add(inst(OP_TYPE_ARRAY, id, wrap3(), constUint(matrixColumns(t))));
            defined.put(id, globals.get(globals.size() - 1));
            annotations.add(inst(OP_DECORATE, id, DEC_ARRAY_STRIDE, 32));
            return id;
        });
    }

    private int wrap3() {
        if (wrap3 == 0) {
            wrap3 = fresh();
            final int[] struct = inst(OP_TYPE_STRUCT, wrap3, plainDvec(3));
            globals.add(struct);
            defined.put(wrap3, struct);
            annotations.add(inst(OP_MEMBER_DECORATE, wrap3, 0, DEC_OFFSET, 0));
        }
        return wrap3;
    }

    private final Map<Integer, Integer> plainDvecs = new HashMap<>();

    /** The array for a dvec of this size, one id whatever dvec types the module declares. */
    private int plainDvec(int size) {
        return plainDvecs.get(size) != null ? plainDvecs.get(size) : newDvec(size);
    }

    private int newDvec(int size) {
        final int id = fresh();
        final int[] array = inst(OP_TYPE_ARRAY, id, uvec2, constUint(size));
        globals.add(array);
        defined.put(id, array);
        annotations.add(inst(OP_DECORATE, id, DEC_ARRAY_STRIDE, 8));
        plainDvecs.put(size, id);
        interned.put(key(OP_TYPE_ARRAY, uvec2, constUint(size)), id);
        return id;
    }

    private int plainType(int type) {
        return isDouble(type) ? uvec2 : type;
    }

    private static String key(int op, int... operands) {
        return op + ":" + Arrays.toString(operands);
    }

    private int intern(int op, int... operands) {
        final String k = key(op, operands);
        final Integer known = interned.get(k);
        if (known != null) return known;
        final int id = fresh();
        final int[] words = new int[operands.length + 1];
        words[0] = id;
        System.arraycopy(operands, 0, words, 1, operands.length);
        final int[] made = inst(op, words);
        globals.add(made);
        defined.put(id, made);
        interned.put(k, id);
        return id;
    }

    private int constUint(int value) {
        final String k = key(OP_CONSTANT, uint, value);
        final Integer known = interned.get(k);
        if (known != null) return known;
        final int id = fresh();
        final int[] made = inst(OP_CONSTANT, uint, id, value);
        globals.add(made);
        defined.put(id, made);
        valueType.put(id, uint);
        interned.put(k, id);
        return id;
    }

    private int pointerTo(int storage, int type) {
        return intern(OP_TYPE_POINTER, storage, type);
    }

    private void lowerTypes() {
        discoverTypes();

        final List<int[]> rewritten = new ArrayList<>(globals);
        globals.clear();
        globals.addAll(rewritten);
        for (int[] g : rewritten) rewriteGlobal(g);
        globals.removeIf(g -> op(g) == OP_TYPE_FLOAT && g[2] == 64);
        defined.remove(f64);
        // A matrix's layout in a buffer now belongs to the array standing for it.
        annotations.removeIf(a -> {
            if (op(a) != OP_MEMBER_DECORATE || a[3] != DEC_MATRIX_STRIDE && a[3] != DEC_COL_MAJOR && a[3] != DEC_ROW_MAJOR) return false;
            final int[] struct = orig.get(a[1]);
            return struct != null && isDmatLike(struct[2 + a[2]]);
        });
        names.removeIf(n -> n[1] == f64);
        annotations.removeIf(a -> op(a) == OP_DECORATE && a[1] == f64);
        planCrossings();
    }

    private void discoverTypes() {
        for (int[] g : globals) {
            final int op = op(g);
            if (op == OP_TYPE_FLOAT && g[2] == 64) f64 = g[1];
            if (op == OP_TYPE_INT && g[2] == 32) {
                if (g[3] == 0) uint = g[1];
                else sint = g[1];
            }
            if (op == OP_TYPE_FLOAT && g[2] == 32) f32 = g[1];
            if (op == OP_TYPE_BOOL) bool = g[1];
        }
        for (int[] g : globals) {
            final int op = op(g);
            if (op == OP_TYPE_FLOAT && g[2] == 64 || op == OP_TYPE_ARRAY || op == OP_TYPE_RUNTIME_ARRAY || op == OP_TYPE_STRUCT) continue;
            if (op >= OP_TYPE_VOID && op <= OP_TYPE_FUNCTION && !containsDoubleOperand(g)) interned.put(key(op, Arrays.copyOfRange(g, 2, g.length)), g[1]);
            if (op == OP_CONSTANT && !isDouble(g[1])) interned.put(key(op, g[1], g[3]), g[2]);
        }
        if (uint == 0) uint = intern(OP_TYPE_INT, 32, 0);
        if (sint == 0) sint = intern(OP_TYPE_INT, 32, 1);
        if (f32 == 0) f32 = intern(OP_TYPE_FLOAT, 32);
        if (bool == 0) bool = intern(OP_TYPE_BOOL);
        uvec2 = intern(OP_TYPE_VECTOR, uint, 2);
    }

    private boolean isDmatLike(int type) {
        final int[] t = orig.get(type);
        if (t == null) return false;
        if (op(t) == OP_TYPE_ARRAY || op(t) == OP_TYPE_RUNTIME_ARRAY) return isDmatLike(t[2]);
        return isDmat(type);
    }

    private boolean containsDoubleOperand(int[] g) {
        final int op = op(g);
        return switch (op) {
            case OP_TYPE_VECTOR, OP_TYPE_MATRIX -> containsDouble(g[2]);
            case OP_TYPE_POINTER -> containsDouble(g[3]);
            case OP_TYPE_FUNCTION -> {
                for (int k = 2; k < g.length; k++) if (containsDouble(g[k])) yield true;
                yield false;
            }
            default -> false;
        };
    }

    private void rewriteGlobal(int[] g) {
        final int op = op(g);
        switch (op) {
            case OP_TYPE_VECTOR -> {
                if (!isDouble(g[2])) return;
                final int id = g[1], size = g[3];
                final Integer same = plainDvecs.get(size);
                final int[] array = inst(OP_TYPE_ARRAY, id, uvec2, constUint(size));
                replaceGlobal(g, array);
                if (same == null) {
                    plainDvecs.put(size, id);
                    interned.put(key(OP_TYPE_ARRAY, uvec2, constUint(size)), id);
                }
                annotations.add(inst(OP_DECORATE, id, DEC_ARRAY_STRIDE, 8));
            }
            case OP_TYPE_MATRIX -> {
                if (!isDouble(orig.get(g[2])[2])) return;
                final int id = g[1], column = g[2], rows = vectorSize(column);
                replaceGlobal(g, inst(OP_TYPE_ARRAY, id, column, constUint(g[3])));
                if (rows != 3) annotations.add(inst(OP_DECORATE, id, DEC_ARRAY_STRIDE, rows == 2 ? 16 : 32));
                interned.put(key(OP_TYPE_ARRAY, column, constUint(g[3])), id);
            }
            case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> {
                if (!containsDouble(g[2])) return;
                final int element = strided.contains(g[1]) ? (isDvec(g[2]) && vectorSize(g[2]) == 3 ? wrap3() : bufferType(g[2])) : plainType(g[2]);
                g[2] = element;
            }
            case OP_TYPE_STRUCT -> {
                final boolean buffer = offsetStructs.contains(g[1]);
                for (int k = 2; k < g.length; k++) if (containsDouble(g[k])) g[k] = buffer ? bufferType(g[k]) : plainType(g[k]);
            }
            case OP_TYPE_POINTER -> {
                if (!containsDouble(g[3])) return;
                if (g[2] == SC_INPUT || g[2] == SC_OUTPUT) g[2] = SC_PRIVATE;
                g[3] = isBufferClass(g[2]) ? bufferType(g[3]) : plainType(g[3]);
            }
            case OP_TYPE_FUNCTION -> {
                for (int k = 2; k < g.length; k++) g[k] = plainType(g[k]);
            }
            case OP_CONSTANT, OP_SPEC_CONSTANT -> {
                if (!isDouble(g[1])) return;
                replaceGlobal(g, inst(OP_CONSTANT_COMPOSITE, uvec2, g[2], constUint(g[3]), constUint(g[4])));
            }
            case OP_CONSTANT_NULL, OP_UNDEF, OP_CONSTANT_COMPOSITE, OP_SPEC_COMPOSITE -> g[1] = plainType(g[1]);
            case OP_VARIABLE -> {
                // The variable's pointer type was rewritten with the rest.
            }
            default -> {
            }
        }
    }

    private void replaceGlobal(int[] old, int[] replacement) {
        final int at = indexOfIdentity(old);
        globals.set(at, replacement);
        defined.put(replacement[hasType(op(replacement)) ? 2 : 1], replacement);
    }

    private int indexOfIdentity(int[] g) {
        for (int i = 0; i < globals.size(); i++) if (globals.get(i) == g) return i;
        throw new IllegalStateException("not a global");
    }

    private int uvec4() {
        return intern(OP_TYPE_VECTOR, uint, 4);
    }

    private final Map<String, Integer> interfaceArrays = new HashMap<>();

    /** An array for an interface, which takes no stride, kept apart from the strided ones of the same shape. */
    private int interfaceArray(int element, int count) {
        final String k = element + "x" + count;
        final Integer known = interfaceArrays.get(k);
        if (known != null) return known;
        final int id = fresh();
        final int[] made = inst(OP_TYPE_ARRAY, id, element, constUint(count));
        globals.add(made);
        defined.put(id, made);
        interfaceArrays.put(k, id);
        interfaceShapes.put(id, new int[] {element, count});
        return id;
    }

    /** Each interface array's element and length, for an array of them to be one array, as Metal takes no arrays of arrays between stages. */
    private final Map<Integer, int[]> interfaceShapes = new HashMap<>();

    private final Map<Integer, Integer> wordTypes = new HashMap<>();

    /**
     * What crosses between stages for a type holding doubles: its words, packed four to a location so it takes the locations GL gives it. A
     * double is a uvec2, a dvec2 a uvec4, a dvec3 or dvec4 two of those, and a matrix its columns' in turn.
     */
    private int wordType(int type) {
        if (!containsDouble(type)) return type;
        final Integer known = wordTypes.get(type);
        if (known != null) return known;
        final int made;
        if (isDouble(type)) {
            made = uvec2;
        } else if (isDvec(type)) {
            made = vectorSize(type) == 2 ? uvec4() : interfaceArray(uvec4(), 2);
        } else if (isDmat(type)) {
            made = interfaceArray(uvec4(), matrixColumns(type) * (matrixRows(type) == 2 ? 1 : 2));
        } else {
            final int[] t = orig.get(type);
            if (op(t) == OP_TYPE_ARRAY) {
                final int element = wordType(t[2]);
                final int[] inner = interfaceShapes.get(element);
                made = inner != null ? interfaceArray(inner[0], intConstant(t[3]) * inner[1]) : interfaceArray(element, intConstant(t[3]));
            } else {
                made = fresh();
                final int[] operands = new int[t.length - 1];
                operands[0] = made;
                for (int k = 2; k < t.length; k++) operands[k - 1] = wordType(t[k]);
                final int[] struct = inst(OP_TYPE_STRUCT, operands);
                globals.add(struct);
                defined.put(made, struct);
                for (int[] d : new ArrayList<>(annotations)) {
                    if ((op(d) == OP_MEMBER_DECORATE || op(d) == OP_DECORATE) && d[1] == type) {
                        final int[] copy = d.clone();
                        copy[1] = made;
                        annotations.add(copy);
                    }
                }
            }
        }
        wordTypes.put(type, made);
        return made;
    }

    private int toWords(int type, int value) {
        if (isDouble(type)) return value;
        if (isDvec(type)) {
            final int size = vectorSize(type);
            final int[] w = new int[8];
            for (int k = 0; k < 4; k++) {
                if (k < size) {
                    final int d = extract(uvec2, value, k);
                    w[2 * k] = extract(uint, d, 0);
                    w[2 * k + 1] = extract(uint, d, 1);
                } else {
                    w[2 * k] = w[2 * k + 1] = constUint(0);
                }
            }
            final int first = construct(uvec4(), w[0], w[1], w[2], w[3]);
            return size == 2 ? first : construct(wordType(type), first, construct(uvec4(), w[4], w[5], w[6], w[7]));
        }
        if (isDmat(type)) {
            final int column = matrixColumnType(type), rows = matrixRows(type);
            final List<Integer> parts = new ArrayList<>();
            for (int c = 0; c < matrixColumns(type); c++) {
                final int w = toWords(column, extract(column, value, c));
                if (rows == 2) {
                    parts.add(w);
                } else {
                    parts.add(extract(uvec4(), w, 0));
                    parts.add(extract(uvec4(), w, 1));
                }
            }
            return construct(wordType(type), parts.stream().mapToInt(Integer::intValue).toArray());
        }
        final int[] t = orig.get(type);
        final int count = op(t) == OP_TYPE_ARRAY ? intConstant(t[3]) : t.length - 2;
        final List<Integer> parts = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            final int element = op(t) == OP_TYPE_ARRAY ? t[2] : t[2 + k];
            final int part = extract(plainType(element), value, k);
            final int words = containsDouble(element) ? toWords(element, part) : part;
            final int[] inner = op(t) == OP_TYPE_ARRAY ? interfaceShapes.get(wordType(element)) : null;
            if (inner == null) {
                parts.add(words);
            } else {
                for (int j = 0; j < inner[1]; j++) parts.add(extract(inner[0], words, j));
            }
        }
        return construct(wordType(type), parts.stream().mapToInt(Integer::intValue).toArray());
    }

    private int doubleOfWords(int vector, int first) {
        return construct(uvec2, extract(uint, vector, first), extract(uint, vector, first + 1));
    }

    private int fromWords(int type, int words) {
        if (isDouble(type)) return words;
        if (isDvec(type)) {
            final int size = vectorSize(type);
            final int first = size == 2 ? words : extract(uvec4(), words, 0);
            final int second = size == 2 ? 0 : extract(uvec4(), words, 1);
            final int[] parts = new int[size];
            for (int k = 0; k < size; k++) parts[k] = doubleOfWords(k < 2 ? first : second, 2 * (k & 1));
            return construct(type, parts);
        }
        if (isDmat(type)) {
            final int column = matrixColumnType(type), rows = matrixRows(type), columns = matrixColumns(type);
            final int[] parts = new int[columns];
            for (int c = 0; c < columns; c++) {
                final int w = rows == 2 ? extract(uvec4(), words, c) : construct(wordType(column), extract(uvec4(), words, 2 * c), extract(uvec4(), words, 2 * c + 1));
                parts[c] = fromWords(column, w);
            }
            return construct(type, parts);
        }
        final int[] t = orig.get(type);
        final int count = op(t) == OP_TYPE_ARRAY ? intConstant(t[3]) : t.length - 2;
        final int[] parts = new int[count];
        for (int k = 0; k < count; k++) {
            final int element = op(t) == OP_TYPE_ARRAY ? t[2] : t[2 + k];
            final int[] inner = op(t) == OP_TYPE_ARRAY ? interfaceShapes.get(wordType(element)) : null;
            final int part;
            if (inner == null) {
                part = extract(wordType(element), words, k);
            } else {
                final int[] pieces = new int[inner[1]];
                for (int j = 0; j < inner[1]; j++) pieces[j] = extract(inner[0], words, k * inner[1] + j);
                part = construct(wordType(element), pieces);
            }
            parts[k] = containsDouble(element) ? fromWords(element, part) : part;
        }
        return construct(type, parts);
    }

    /** Moves what decorates, names and lists a variable over to the one that takes its place in the interface. */
    private void takeOver(int variable, int replacement) {
        for (int[] a : annotations) if ((op(a) == OP_DECORATE || op(a) == OP_DECORATE_ID || op(a) == OP_DECORATE_STRING) && a[1] == variable) a[1] = replacement;
        for (int[] n : names) if (op(n) == OP_NAME && n[1] == variable) n[1] = replacement;
        for (int e = 0; e < entryPoints.size(); e++) {
            final int[] entry = entryPoints.get(e);
            for (int k = interfaceStart(entry); k < entry.length; k++) if (entry[k] == variable) entry[k] = replacement;
        }
    }

    /** Where an entry point's interface starts, after its name, a string of literal words. */
    private static int interfaceStart(int[] entry) {
        int k = 3;
        while (k < entry.length && (entry[k] >>> 24) != 0 && (entry[k] & 0xFF00) != 0 && (entry[k] & 0xFF0000) != 0) k++;
        return k + 1;
    }

    private void addToInterface(int variable) {
        for (int e = 0; e < entryPoints.size(); e++) {
            final int[] entry = entryPoints.get(e);
            final int[] longer = Arrays.copyOf(entry, entry.length + 1);
            longer[entry.length] = variable;
            longer[0] = longer.length << 16 | OP_ENTRY_POINT;
            entryPoints.set(e, longer);
        }
    }

    private void planCrossings() {
        if (executionModel == MODEL_VERTEX) findWideSlots();
        for (int[] g : new ArrayList<>(globals)) {
            if (op(g) != OP_VARIABLE || g[3] != SC_PRIVATE && g[3] != SC_INPUT && g[3] != SC_OUTPUT) continue;
            final int[] pointer = orig.get(g[1]);
            if (pointer == null || pointer[2] != SC_INPUT && pointer[2] != SC_OUTPUT || !containsDouble(pointer[3])) continue;
            final int storage = pointer[2], variable = g[2], shape = pointer[3];
            if (storage == SC_INPUT && executionModel == MODEL_VERTEX) {
                planVertexInput(g, variable, shape);
                continue;
            }
            final int crossed = fresh();
            final int[] made = inst(OP_VARIABLE, pointerTo(storage, wordType(shape)), crossed, storage);
            globals.add(made);
            defined.put(crossed, made);
            g[3] = SC_PRIVATE;
            crossing.put(variable, crossed);
            crossingType.put(variable, shape);
            takeOver(variable, crossed);
            if (storage == SC_INPUT && executionModel == MODEL_FRAGMENT) flatten(crossed, wordType(shape));
            if (storage == SC_OUTPUT && executionModel == MODEL_TESS_CONTROL && !decorated(crossed, DEC_PATCH)) perInvocation.add(variable);
        }
    }

    private boolean decorated(int id, int decoration) {
        for (int[] a : annotations) if (op(a) == OP_DECORATE && a[1] == id && a[2] == decoration) return true;
        return false;
    }

    /** A fragment shader's integer inputs must be flat, as GL has its doubles be. */
    private void flatten(int variable, int words) {
        if (decorated(variable, DEC_FLAT)) return;
        final int[] t = defined.get(words);
        if (op(t) != OP_TYPE_STRUCT) {
            annotations.add(inst(OP_DECORATE, variable, DEC_FLAT));
            return;
        }
        for (int m = 0; m < t.length - 2; m++) {
            boolean flat = false;
            for (int[] a : annotations) flat |= op(a) == OP_MEMBER_DECORATE && a[1] == words && a[2] == m && a[3] == DEC_FLAT;
            if (!flat) annotations.add(inst(OP_MEMBER_DECORATE, words, m, DEC_FLAT));
        }
    }

    /** A control shader's outputs for each vertex, of which an invocation writes only its own. */
    private final Set<Integer> perInvocation = new HashSet<>();
    private int invocationId;

    private int invocationId() {
        if (invocationId != 0) return invocationId;
        for (int[] a : annotations) {
            if (op(a) == OP_DECORATE && a[2] == DEC_BUILTIN && a[3] == BUILTIN_INVOCATION_ID) return invocationId = a[1];
        }
        invocationId = fresh();
        final int[] made = inst(OP_VARIABLE, pointerTo(SC_INPUT, sint), invocationId, SC_INPUT);
        globals.add(made);
        defined.put(invocationId, made);
        annotations.add(inst(OP_DECORATE, invocationId, DEC_BUILTIN, BUILTIN_INVOCATION_ID));
        addToInterface(invocationId);
        return invocationId;
    }

    /** A vertex shader's double input: the vectors' first halves at its locations, and the second halves of wider ones from HIGH_LOCATIONS on. */
    private record VertexInput(int variable, int shape, int low, int lowElement, int high, int highElement, boolean arrayed) {}

    private final List<VertexInput> vertexInputs = new ArrayList<>();

    private int slotSize(int type) {
        if (isDouble(type)) return 1;
        if (isDvec(type)) return vectorSize(type);
        if (isDmat(type)) return matrixRows(type);
        return slotSize(orig.get(type)[2]);
    }

    private int slotCount(int type) {
        if (isDouble(type) || isDvec(type)) return 1;
        if (isDmat(type)) return matrixColumns(type);
        final int[] t = orig.get(type);
        return intConstant(t[3]) * slotCount(t[2]);
    }

    /** The locations of a vertex shader's vectors of three or four doubles, whose second halves take locations from HIGH_LOCATIONS on in this order. */
    private final java.util.TreeSet<Integer> wideSlots = new java.util.TreeSet<>();

    private void findWideSlots() {
        for (int[] g : globals) {
            if (op(g) != OP_VARIABLE) continue;
            final int[] pointer = orig.get(g[1]);
            if (pointer == null || pointer[2] != SC_INPUT || !containsDouble(pointer[3]) || slotSize(pointer[3]) <= 2) continue;
            final int location = locationOf(g[2]);
            for (int k = 0; k < slotCount(pointer[3]); k++) wideSlots.add(location + k);
        }
    }

    private int locationOf(int variable) {
        for (int[] a : annotations) if (op(a) == OP_DECORATE && a[1] == variable && a[2] == DEC_LOCATION) return a[3];
        throw new IllegalArgumentException("a double vertex input has no location");
    }

    private void planVertexInput(int[] g, int variable, int shape) {
        final int size = slotSize(shape), slots = slotCount(shape);
        final boolean arrayed = !isDouble(shape) && !isDvec(shape);
        final int lowElement = size == 1 ? uvec2 : uvec4();
        final int low = fresh();
        final int[] lowVariable = inst(OP_VARIABLE, pointerTo(SC_INPUT, arrayed ? interfaceArray(lowElement, slots) : lowElement), low, SC_INPUT);
        globals.add(lowVariable);
        defined.put(low, lowVariable);
        takeOver(variable, low);
        int high = 0, highElement = 0;
        if (size > 2) {
            final int location = HIGH_LOCATIONS + wideSlots.headSet(locationOf(low)).size();
            if (location + slots > 31) throw new IllegalArgumentException("more than 15 vectors of three or four doubles are vertex inputs, and each needs a second location");
            highElement = size == 3 ? uvec2 : uvec4();
            high = fresh();
            final int[] highVariable = inst(OP_VARIABLE, pointerTo(SC_INPUT, arrayed ? interfaceArray(highElement, slots) : highElement), high, SC_INPUT);
            globals.add(highVariable);
            defined.put(high, highVariable);
            annotations.add(inst(OP_DECORATE, high, DEC_LOCATION, location));
            addToInterface(high);
        }
        g[3] = SC_PRIVATE;
        vertexInputs.add(new VertexInput(variable, shape, low, lowElement, high, highElement, arrayed));
    }

    private void copyVertexInputs() {
        for (VertexInput v : vertexInputs) {
            final int lowType = defined.get(defined.get(v.low)[1])[3];
            final int lows = fresh();
            emit(OP_LOAD, lowType, lows, v.low);
            int highs = 0;
            if (v.high != 0) {
                highs = fresh();
                emit(OP_LOAD, defined.get(defined.get(v.high)[1])[3], highs, v.high);
            }
            emit(OP_STORE, v.variable, fromSlots(v, v.shape, lows, highs, new int[1]));
        }
    }

    private int leafDouble(int words) {
        if (!nativeDoubles) return words;
        final int id = fresh();
        emit(OP_BITCAST, f64, id, words);
        return id;
    }

    private int fromSlots(VertexInput v, int type, int lows, int highs, int[] next) {
        if (isDouble(type) || isDvec(type)) {
            final int slot = next[0]++;
            final int first = v.arrayed ? extract(v.lowElement, lows, slot) : lows;
            if (isDouble(type)) return leafDouble(first);
            final int size = vectorSize(type);
            final int second = size > 2 ? (v.arrayed ? extract(v.highElement, highs, slot) : highs) : 0;
            final int[] parts = new int[size];
            for (int k = 0; k < size; k++) parts[k] = leafDouble(k == 2 && size == 3 ? second : doubleOfWords(k < 2 ? first : second, 2 * (k & 1)));
            return construct(type, parts);
        }
        final int[] t = orig.get(type);
        final int count = op(t) == OP_TYPE_MATRIX ? t[3] : intConstant(t[3]);
        final int[] parts = new int[count];
        for (int k = 0; k < count; k++) parts[k] = fromSlots(v, t[2], lows, highs, next);
        return construct(type, parts);
    }

    private void mergeLibrary() {
        final int[] lib = SoftDouble.words();
        final Map<Integer, Integer> map = new HashMap<>();
        int libImport = 0, libMain = 0;
        final Map<Integer, String> libNames = new HashMap<>();
        List<int[]> current = null;
        for (int i = 5; i < lib.length; ) {
            final int count = lib[i] >>> 16;
            final int[] in = Arrays.copyOfRange(lib, i, i + count);
            i += count;
            final int op = op(in);
            if (current != null) {
                current.add(in);
                if (op == OP_FUNCTION_END) current = null;
                continue;
            }
            switch (op) {
                case OP_EXT_INST_IMPORT -> libImport = in[1];
                case OP_ENTRY_POINT -> libMain = in[2];
                case OP_NAME -> libNames.put(in[1], words(in, 2));
                case OP_FUNCTION -> {
                    current = new ArrayList<>();
                    current.add(in);
                    libBodies.put(in[2], current);
                }
                case OP_CAPABILITY -> {
                    if (in[1] == CAP_INT64 && capabilities.stream().noneMatch(c -> c[1] == CAP_INT64)) capabilities.add(in);
                }
                case OP_EXTENSION, OP_MEMORY_MODEL, OP_EXECUTION_MODE, OP_SOURCE, OP_SOURCE_CONTINUED, OP_SOURCE_EXTENSION, OP_STRING, OP_MEMBER_NAME, OP_DECORATE, OP_MEMBER_DECORATE, OP_MODULE_PROCESSED, OP_LINE, OP_NO_LINE -> {
                }
                case OP_VARIABLE -> throw new IllegalStateException("the double library has a global");
                default -> mergeGlobal(in, map);
            }
        }
        if (glsl == 0) {
            glsl = fresh();
            final byte[] name = "GLSL.std.450\0\0\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            final int[] operands = new int[5];
            operands[0] = glsl;
            for (int w = 0; w < 4; w++) operands[w + 1] = (name[w * 4] & 0xFF) | (name[w * 4 + 1] & 0xFF) << 8 | (name[w * 4 + 2] & 0xFF) << 16 | (name[w * 4 + 3] & 0xFF) << 24;
            imports.add(inst(OP_EXT_INST_IMPORT, operands));
        }
        map.put(libImport, glsl);
        libBodies.remove(libMain);
        // Every id local to a function gets one of ours, and a function's own id is known before anything calls it.
        for (Map.Entry<Integer, List<int[]>> entry : new ArrayList<>(libBodies.entrySet())) map.put(entry.getKey(), fresh());
        final Map<Integer, List<int[]>> remapped = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<int[]>> entry : libBodies.entrySet()) {
            final List<int[]> body = new ArrayList<>();
            final int[] params = entry.getValue().stream().filter(in -> op(in) == OP_FUNCTION_PARAMETER).mapToInt(in -> in[1]).toArray();
            for (int[] in : entry.getValue()) {
                final int[] copy = in.clone();
                mapIds(copy, id -> map.computeIfAbsent(id, x -> fresh()));
                body.add(copy);
            }
            final int mapped = map.get(entry.getKey());
            remapped.put(mapped, body);
            final String name = libNames.get(entry.getKey());
            final int[] signature = new int[params.length + 1];
            signature[0] = body.get(0)[1];
            for (int p = 0; p < params.length; p++) signature[p + 1] = map.get(params[p]);
            final int[] pointeeTypes = new int[params.length];
            for (int p = 0; p < params.length; p++) pointeeTypes[p] = defined.get(signature[p + 1])[3];
            final int[] described = new int[params.length + 2];
            described[0] = mapped;
            described[1] = signature[0];
            System.arraycopy(pointeeTypes, 0, described, 2, params.length);
            libFunction.put(name.substring(0, name.indexOf('(')), described);
        }
        libBodies.clear();
        libBodies.putAll(remapped);
    }

    private void mergeGlobal(int[] in, Map<Integer, Integer> map) {
        final int op = op(in);
        final int[] copy = in.clone();
        final boolean typed = hasType(op);
        if (typed) copy[1] = map.get(copy[1]);
        final int result = copy[typed ? 2 : 1];
        final int from = typed ? 3 : 2;
        switch (op) {
            case OP_TYPE_VECTOR, OP_TYPE_MATRIX -> copy[from] = map.get(copy[from]);
            case OP_TYPE_ARRAY -> {
                copy[from] = map.get(copy[from]);
                copy[from + 1] = map.get(copy[from + 1]);
            }
            case OP_TYPE_POINTER -> copy[from + 1] = map.get(copy[from + 1]);
            case OP_TYPE_FUNCTION, OP_TYPE_STRUCT, OP_CONSTANT_COMPOSITE -> {
                for (int k = from; k < copy.length; k++) copy[k] = map.get(copy[k]);
            }
            default -> {
            }
        }
        final int[] operands = Arrays.copyOfRange(copy, typed ? 1 : 2, copy.length);
        final int[] keyed = typed ? concat(copy[1], Arrays.copyOfRange(copy, 3, copy.length)) : operands;
        final String k = key(op, keyed);
        final Integer known = interned.get(k);
        if (known != null) {
            map.put(result, known);
            return;
        }
        final int id = fresh();
        map.put(result, id);
        copy[typed ? 2 : 1] = id;
        libGlobals.add(copy);
        defined.put(id, copy);
        if (typed) valueType.put(id, copy[1]);
        interned.put(k, id);
    }

    private static int[] concat(int first, int[] rest) {
        final int[] out = new int[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static String words(int[] in, int from) {
        final StringBuilder out = new StringBuilder();
        for (int k = from; k < in.length; k++) {
            for (int b = 0; b < 4; b++) {
                final int c = in[k] >>> (b * 8) & 0xFF;
                if (c == 0) return out.toString();
                out.append((char) c);
            }
        }
        return out.toString();
    }

    private void emit(int op, int... operands) {
        out.add(inst(op, operands));
    }

    private int temp(int type) {
        final int taken = tempsTaken.merge(type, 1, Integer::sum) - 1;
        final List<Integer> list = temps.computeIfAbsent(type, t -> new ArrayList<>());
        if (taken < list.size()) return list.get(taken);
        final int id = fresh();
        tempVariables.add(inst(OP_VARIABLE, pointerTo(SC_FUNCTION, type), id, SC_FUNCTION));
        list.add(id);
        return id;
    }

    private void releaseTemps() {
        tempsTaken.clear();
    }

    /** Calls the library, each argument through a variable as the function's parameters are pointers. */
    private int call(String function, int... args) {
        final int[] described = libFunction.get(function);
        if (described == null) throw new IllegalStateException("no " + function);
        usedLib.add(described[0]);
        final int[] operands = new int[args.length + 3];
        operands[0] = described[1];
        operands[1] = fresh();
        operands[2] = described[0];
        for (int a = 0; a < args.length; a++) {
            final int variable = temp(described[a + 2]);
            emit(OP_STORE, variable, args[a]);
            operands[a + 3] = variable;
        }
        emit(OP_FUNCTION_CALL, operands);
        releaseTemps();
        return operands[1];
    }

    private int extract(int type, int composite, int... indices) {
        final int id = fresh();
        final int[] operands = new int[indices.length + 3];
        operands[0] = type;
        operands[1] = id;
        operands[2] = composite;
        System.arraycopy(indices, 0, operands, 3, indices.length);
        emit(OP_COMPOSITE_EXTRACT, operands);
        return id;
    }

    private int construct(int type, int... parts) {
        final int id = fresh();
        final int[] operands = new int[parts.length + 2];
        operands[0] = type;
        operands[1] = id;
        System.arraycopy(parts, 0, operands, 2, parts.length);
        emit(OP_COMPOSITE_CONSTRUCT, operands);
        return id;
    }

    private int typeOf(int value) {
        final Integer type = valueType.get(value);
        if (type == null) throw new IllegalStateException("no type for " + value);
        return type;
    }

    /** A double or dvec value, a component at a time. */
    private int[] components(int value) {
        final int type = typeOf(value);
        if (isDouble(type)) return new int[] {value};
        final int size = vectorSize(type);
        final int[] out = new int[size];
        for (int c = 0; c < size; c++) out[c] = extract(uvec2, value, c);
        return out;
    }

    private int[] componentsOr(int value, int size) {
        final int[] c = components(value);
        if (c.length == size) return c;
        final int[] smeared = new int[size];
        Arrays.fill(smeared, c[0]);
        return smeared;
    }

    /** The result of an operation with this double or dvec type, from its components. */
    private void finish(int type, int result, int[] parts) {
        if (isDouble(type)) {
            emit(OP_COPY_OBJECT, uvec2, result, parts[0]);
        } else {
            final int[] operands = new int[parts.length + 2];
            operands[0] = type;
            operands[1] = result;
            System.arraycopy(parts, 0, operands, 2, parts.length);
            emit(OP_COMPOSITE_CONSTRUCT, operands);
        }
    }

    private int matrixEntry(int matrix, int column, int row) {
        return extract(uvec2, matrix, column, row);
    }

    /** A buffer's matrix as shaders hold one, a word at a time as Metal cannot build arrays from the ones a buffer holds. */
    private int fromBuffer(int type, int stored) {
        final int columns = matrixColumns(type), rows = matrixRows(type);
        final int[] parts = new int[columns];
        for (int c = 0; c < columns; c++) {
            final int[] column = new int[rows];
            for (int r = 0; r < rows; r++) column[r] = rows == 3 ? extract(uvec2, stored, c, 0, r) : extract(uvec2, stored, c, r);
            parts[c] = construct(matrixColumnType(type), column);
        }
        return construct(type, parts);
    }

    /** A buffer's matrix read a word at a time, since its nested arrays cannot be copied whole. */
    private int loadFromBuffer(int type, int pointer) {
        return loadFromBuffer(orig.get(typeOf(pointer))[2], type, pointer);
    }

    private int loadFromBuffer(int storage, int type, int pointer) {
        final int wordPointer = pointerTo(storage, uvec2);
        final int columns = matrixColumns(type), rows = matrixRows(type);
        final int[] parts = new int[columns];
        for (int c = 0; c < columns; c++) {
            final int[] column = new int[rows];
            for (int r = 0; r < rows; r++) {
                final int entry = fresh();
                if (rows == 3) emit(OP_ACCESS_CHAIN, wordPointer, entry, pointer, constUint(c), zeroInt(), constUint(r));
                else emit(OP_ACCESS_CHAIN, wordPointer, entry, pointer, constUint(c), constUint(r));
                final int word = fresh();
                emit(OP_LOAD, uvec2, word, entry);
                column[r] = word;
            }
            parts[c] = construct(matrixColumnType(type), column);
        }
        return construct(type, parts);
    }

    private void storeToBuffer(int type, int pointer, int value) {
        final int[] orig0 = orig.get(typeOf(pointer));
        final int wordPointer = pointerTo(orig0[2], uvec2);
        final int columns = matrixColumns(type), rows = matrixRows(type);
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                final int entry = fresh();
                if (rows == 3) emit(OP_ACCESS_CHAIN, wordPointer, entry, pointer, constUint(c), zeroInt(), constUint(r));
                else emit(OP_ACCESS_CHAIN, wordPointer, entry, pointer, constUint(c), constUint(r));
                emit(OP_STORE, entry, extract(uvec2, value, c, r));
            }
        }
    }

    private int toBuffer(int type, int value) {
        final int columns = matrixColumns(type);
        final int[] parts = new int[columns];
        for (int c = 0; c < columns; c++) parts[c] = construct(wrap3(), extract(matrixColumnType(type), value, c));
        return construct(bufferType(type), parts);
    }

    private boolean bufferDiffers(int type) {
        return isDmat(type);
    }

    private int intConstant(int id) {
        final int[] c = orig.get(id);
        if (c == null) {
            final int[] made = defined.get(id);
            if (made != null && op(made) == OP_CONSTANT) return made[3];
            throw new IllegalStateException("not a constant: " + id);
        }
        if (op(c) != OP_CONSTANT) throw new IllegalStateException("not a constant: " + id);
        return c[3];
    }

    /** Fills in {@code into} the indices reaching the same place through what buffers hold, and returns the type reached. */
    private int walk(int type, boolean byConstant, int[] indices, List<Integer> into, int zero) {
        int current = type;
        for (int index : indices) {
            into.add(index);
            final int[] t = orig.get(current);
            final boolean buffer = isBufferContext(current);
            switch (op(t)) {
                case OP_TYPE_STRUCT -> {
                    final int member = byConstant ? intConstant(index) : index;
                    current = t[2 + member];
                    continue;
                }
                case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> {
                    current = t[2];
                    if (buffer && isDvec(current) && vectorSize(current) == 3) into.add(zero);
                    continue;
                }
                case OP_TYPE_MATRIX -> {
                    current = t[2];
                    if (isDouble(orig.get(current)[2]) && inBuffer && vectorSize(current) == 3) into.add(zero);
                    continue;
                }
                default -> current = t[2];
            }
        }
        return current;
    }

    private boolean inBuffer;

    private List<int[]> lowerFunction(List<int[]> function) {
        final List<int[]> lowered = new ArrayList<>();
        out = lowered;
        temps = new HashMap<>();
        tempVariables = new ArrayList<>();
        tempsTaken = new HashMap<>();
        final boolean isMain = function.get(0)[2] == mainFunction;
        for (int[] in : function) if (hasTypeAndResult(op(in))) valueType.put(in[2], in[1]);
        findTakenApart(function);
        int firstLabel = -1, afterVariables = -1;
        for (int[] in : function) {
            final int op = op(in);
            if (op == OP_LABEL && firstLabel < 0) {
                out.add(in);
                firstLabel = out.size();
                continue;
            }
            if (firstLabel >= 0 && afterVariables < 0 && op != OP_VARIABLE) {
                afterVariables = out.size();
                if (isMain) copyInputs();
            }
            lowerInstruction(in, isMain);
        }
        if (firstLabel >= 0) lowered.addAll(firstLabel, tempVariables);
        for (int[] in : lowered) {
            final int op = op(in);
            if (hasTypeAndResult(op) && in[1] == f64) in[1] = uvec2;
        }
        return lowered;
    }

    private void copyInputs() {
        copyVertexInputs();
        for (Map.Entry<Integer, Integer> e : crossing.entrySet()) {
            if (defined.get(e.getValue())[3] != SC_INPUT) continue;
            final int shape = crossingType.get(e.getKey());
            final int words = fresh();
            emit(OP_LOAD, wordType(shape), words, e.getValue());
            emit(OP_STORE, e.getKey(), fromWords(shape, words));
        }
    }

    private void copyOutputs() {
        for (Map.Entry<Integer, Integer> e : crossing.entrySet()) {
            if (defined.get(e.getValue())[3] != SC_OUTPUT) continue;
            final int shape = crossingType.get(e.getKey());
            if (perInvocation.contains(e.getKey())) {
                final int element = orig.get(shape)[2], index = fresh();
                emit(OP_LOAD, sint, index, invocationId());
                final int from = fresh(), to = fresh(), held = fresh();
                emit(OP_ACCESS_CHAIN, pointerTo(SC_PRIVATE, plainType(element)), from, e.getKey(), index);
                emit(OP_LOAD, plainType(element), held, from);
                final int words = toWords(element, held);
                final int[] inner = interfaceShapes.get(wordType(element));
                if (inner == null) {
                    emit(OP_ACCESS_CHAIN, pointerTo(SC_OUTPUT, wordType(element)), to, e.getValue(), index);
                    emit(OP_STORE, to, words);
                    continue;
                }
                // The vertex's words are a run of the one array its stage writes.
                final int unsigned = fresh(), first = fresh();
                emit(OP_BITCAST, uint, unsigned, index);
                emit(OP_IMUL, uint, first, unsigned, constUint(inner[1]));
                for (int j = 0; j < inner[1]; j++) {
                    final int at = fresh(), slot = fresh();
                    emit(OP_IADD, uint, at, first, constUint(j));
                    emit(OP_ACCESS_CHAIN, pointerTo(SC_OUTPUT, inner[0]), slot, e.getValue(), at);
                    emit(OP_STORE, slot, extract(inner[0], words, j));
                }
                continue;
            }
            final int held = fresh();
            emit(OP_LOAD, plainType(shape), held, e.getKey());
            emit(OP_STORE, e.getValue(), toWords(shape, held));
        }
    }

    private void lowerInstruction(int[] in, boolean isMain) {
        final int op = op(in);
        switch (op) {
            case OP_RETURN -> {
                if (isMain) copyOutputs();
                out.add(in);
            }
            case OP_EMIT_VERTEX, OP_EMIT_STREAM_VERTEX -> {
                copyOutputs();
                out.add(in);
            }
            case OP_LOAD -> lowerLoad(in);
            case OP_STORE -> lowerStore(in);
            case OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN -> lowerChain(in);
            case OP_COMPOSITE_EXTRACT -> {
                if (members.containsKey(in[3])) extractMember(in);
                else lowerExtract(in);
            }
            case OP_COMPOSITE_CONSTRUCT -> lowerConstruct(in);
            case OP_VECTOR_SHUFFLE -> lowerShuffle(in);
            case OP_VECTOR_EXTRACT_DYNAMIC -> lowerExtractDynamic(in);
            case OP_VECTOR_INSERT_DYNAMIC -> lowerInsertDynamic(in);
            case OP_TRANSPOSE -> lowerTranspose(in);
            case OP_F_NEGATE, OP_F_ADD, OP_F_SUB, OP_F_MUL, OP_F_DIV, OP_F_REM, OP_F_MOD -> lowerArithmetic(in);
            case OP_VECTOR_TIMES_SCALAR, OP_MATRIX_TIMES_SCALAR, OP_VECTOR_TIMES_MATRIX, OP_MATRIX_TIMES_VECTOR, OP_MATRIX_TIMES_MATRIX, OP_OUTER_PRODUCT, OP_DOT -> lowerLinear(in);
            case OP_IS_NAN, OP_IS_INF -> lowerTest(in);
            case OP_CONVERT_F_TO_U, OP_CONVERT_F_TO_S, OP_CONVERT_S_TO_F, OP_CONVERT_U_TO_F, OP_F_CONVERT -> lowerConvert(in);
            case OP_SELECT -> lowerSelect(in);
            case OP_EXT_INST -> lowerExtended(in);
            default -> {
                if (op >= OP_F_ORD_EQUAL && op <= OP_F_UNORD_GREATER_EQUAL) {
                    lowerComparison(in);
                    return;
                }
                switch (op) {
                    case OP_FUNCTION, OP_FUNCTION_PARAMETER, OP_FUNCTION_END, OP_FUNCTION_CALL, OP_VARIABLE, OP_PHI, OP_COPY_OBJECT, OP_COPY_LOGICAL, OP_UNDEF, OP_RETURN_VALUE, OP_LABEL, OP_BRANCH, OP_BRANCH_CONDITIONAL,
                         OP_SWITCH, OP_SELECTION_MERGE, OP_LOOP_MERGE, OP_KILL, OP_UNREACHABLE, OP_LINE, OP_NO_LINE, OP_COPY_MEMORY, OP_COMPOSITE_INSERT -> out.add(in);
                    default -> {
                        refuseDoubles(in);
                        out.add(in);
                    }
                }
            }
        }
    }

    private void refuseDoubles(int[] in) {
        if (hasTypeAndResult(op(in)) && containsDouble(in[1])) throw new IllegalArgumentException("SPIR-V operation " + op(in) + " on doubles");
        for (int k = hasTypeAndResult(op(in)) ? 3 : 1; k < in.length; k++) {
            final Integer type = valueType.get(in[k]);
            if (type != null && containsDouble(type) && !isPointerClass(type)) throw new IllegalArgumentException("SPIR-V operation " + op(in) + " on doubles");
        }
    }

    private boolean isPointerClass(int type) {
        final int[] t = orig.get(type);
        return t != null && op(t) == OP_TYPE_POINTER;
    }

    private void lowerLoad(int[] in) {
        final int type = in[1];
        final int[] pointer = orig.get(typeOf(in[3]));
        if (pointer != null && isBufferClass(pointer[2]) && bufferDiffers(type)) {
            emit(OP_COPY_OBJECT, type, in[2], loadFromBuffer(type, in[3]));
            return;
        }
        if (pointer != null && isBufferClass(pointer[2]) && containsDouble(pointer[3])) {
            if (takenApart.contains(in[2])) {
                members.put(in[2], loadParts(pointer[2], type, in[3]));
                return;
            }
            emit(OP_COPY_OBJECT, type, in[2], loadPiecewise(pointer[2], type, in[3]));
            return;
        }
        out.add(in);
    }

    /**
     * A struct from a buffer that is only ever taken apart, as glslang does to copy one into a variable of its own, is never put together: Metal
     * pads a struct with offsets that the shader holds, and SPIRV-Cross then builds one wrongly.
     */
    private final Set<Integer> takenApart = new HashSet<>();
    private final Map<Integer, int[]> members = new HashMap<>();

    /** A struct or an array of more than doubles: what is taken apart rather than built. */
    private boolean partsType(int type) {
        final int[] t = orig.get(type);
        return t != null && (op(t) == OP_TYPE_STRUCT || op(t) == OP_TYPE_ARRAY) && containsDouble(type) && !isDvec(type) && !isDmat(type);
    }

    private void findTakenApart(List<int[]> function) {
        takenApart.clear();
        members.clear();
        final Map<Integer, Integer> parent = new HashMap<>();
        for (int[] in : function) {
            if (op(in) == OP_LOAD && partsType(in[1])) {
                final int[] pointer = orig.get(typeOf(in[3]));
                if (pointer != null && isBufferClass(pointer[2])) takenApart.add(in[2]);
            } else if (op(in) == OP_COMPOSITE_EXTRACT && takenApart.contains(in[3]) && partsType(in[1])) {
                // A member taken out whole and then apart in turn.
                takenApart.add(in[2]);
                parent.put(in[2], in[3]);
            }
        }
        if (takenApart.isEmpty()) return;
        for (int[] in : function) {
            final int skip = op(in) == OP_COMPOSITE_EXTRACT && in.length > 4 ? in[3] : -1;
            final int result = hasTypeAndResult(op(in)) && in.length > 2 ? in[2] : -1;
            final int[] copy = in.clone();
            mapIds(copy, id -> {
                if (id != skip && id != result) takenApart.remove(id);
                return id;
            });
        }
        for (boolean changed = true; changed; ) {
            changed = false;
            for (Map.Entry<Integer, Integer> e : parent.entrySet()) {
                if (takenApart.contains(e.getKey()) && !takenApart.contains(e.getValue())) changed |= takenApart.remove(e.getKey());
            }
        }
    }

    /**
     * Loads each element or member of what a buffer holds a piece at a time, going on into those that are taken apart too. An element of a dvec3
     * array is read from inside the struct that pads it, as the dvec3 it is.
     */
    private int[] loadParts(int storage, int type, int pointer) {
        final int[] shape = orig.get(type), lowered = defined.get(type);
        final boolean array = op(shape) == OP_TYPE_ARRAY;
        final int count = array ? intConstant(shape[3]) : shape.length - 2;
        final int[] parts = new int[count];
        for (int k = 0; k < count; k++) {
            final int element = array ? shape[2] : shape[2 + k], held = array ? lowered[2] : lowered[2 + k];
            final int at = fresh();
            emit(OP_ACCESS_CHAIN, pointerTo(storage, held), at, pointer, constUint(k));
            if (held == wrap3 && isDvec(element)) {
                final int inner = fresh();
                emit(OP_ACCESS_CHAIN, pointerTo(storage, plainDvec(3)), inner, at, zeroInt());
                parts[k] = loadPiecewise(storage, plainDvec(3), inner);
                valueType.put(parts[k], plainDvec(3));
            } else if (isDmat(element)) {
                parts[k] = loadFromBuffer(storage, element, at);
                valueType.put(parts[k], element);
            } else if (partsType(element)) {
                parts[k] = fresh();
                members.put(parts[k], loadParts(storage, held, at));
                valueType.put(parts[k], held);
            } else {
                parts[k] = loadPiecewise(storage, held, at);
                valueType.put(parts[k], held);
            }
        }
        return parts;
    }

    /** Puts a value taken apart back together, where something needs it whole after all. */
    private int whole(int type, int value) {
        final int[] parts = members.get(value);
        if (parts == null) return value;
        final int[] lowered = defined.get(type);
        final boolean array = op(lowered) == OP_TYPE_ARRAY;
        final int[] built = new int[parts.length];
        for (int k = 0; k < parts.length; k++) built[k] = sameType(array ? lowered[2] : lowered[2 + k], whole(array ? lowered[2] : lowered[2 + k], parts[k]));
        return construct(type, built);
    }

    /** A value as the type asked for, rebuilt where only the type's id differs. */
    private int sameType(int type, int value) {
        final Integer has = valueType.get(value);
        if (has == null || has == type || members.containsKey(value)) return value;
        if (type == wrap3) return construct(wrap3, value);
        final int[] t = defined.get(type);
        if (t == null || op(t) != OP_TYPE_ARRAY) return value;
        final int[] pieces = new int[intConstant(t[3])];
        for (int k = 0; k < pieces.length; k++) pieces[k] = extract(t[2], value, k);
        return construct(type, pieces);
    }

    private void extractMember(int[] in) {
        int current = in[3], k = 4;
        while (k < in.length && members.containsKey(current)) current = members.get(current)[in[k++]];
        if (k == in.length) {
            if (members.containsKey(current)) {
                if (takenApart.contains(in[2])) members.put(in[2], members.get(current));
                else emit(OP_COPY_OBJECT, in[1], in[2], whole(in[1], current));
                return;
            }
            emit(OP_COPY_OBJECT, in[1], in[2], sameType(in[1], current));
            return;
        }
        final int[] rest = new int[in.length - k + 4];
        rest[0] = (rest.length << 16) | OP_COMPOSITE_EXTRACT;
        rest[1] = in[1];
        rest[2] = in[2];
        rest[3] = current;
        System.arraycopy(in, k, rest, 4, in.length - k);
        lowerExtract(rest);
    }

    /**
     * SPIRV-Cross can't copy an array out of push constants whole, and Metal can't assign one from a buffer to a shader's own, so what holds
     * doubles comes out of a buffer a piece at a time, and goes in the same way.
     */
    private int loadPiecewise(int storage, int type, int pointer) {
        final int[] t = defined.get(type);
        if (t == null || op(t) != OP_TYPE_ARRAY && op(t) != OP_TYPE_STRUCT) {
            final int value = fresh();
            emit(OP_LOAD, type, value, pointer);
            return value;
        }
        final int count = op(t) == OP_TYPE_ARRAY ? intConstant(t[3]) : t.length - 2;
        final int[] parts = new int[count];
        for (int k = 0; k < count; k++) {
            final int element = op(t) == OP_TYPE_ARRAY ? t[2] : t[2 + k];
            final int at = fresh();
            emit(OP_ACCESS_CHAIN, pointerTo(storage, element), at, pointer, constUint(k));
            parts[k] = loadPiecewise(storage, element, at);
        }
        return construct(type, parts);
    }

    private void storePiecewise(int storage, int type, int pointer, int value) {
        final int[] t = defined.get(type);
        if (t == null || op(t) != OP_TYPE_ARRAY && op(t) != OP_TYPE_STRUCT) {
            emit(OP_STORE, pointer, value);
            return;
        }
        final int count = op(t) == OP_TYPE_ARRAY ? intConstant(t[3]) : t.length - 2;
        for (int k = 0; k < count; k++) {
            final int element = op(t) == OP_TYPE_ARRAY ? t[2] : t[2 + k];
            final int at = fresh();
            emit(OP_ACCESS_CHAIN, pointerTo(storage, element), at, pointer, constUint(k));
            storePiecewise(storage, element, at, extract(element, value, k));
        }
    }

    private void lowerStore(int[] in) {
        final Integer valueT = valueType.get(in[2]);
        final int[] pointer = orig.get(typeOf(in[1]));
        if (valueT != null && pointer != null && isBufferClass(pointer[2]) && bufferDiffers(valueT)) {
            storeToBuffer(valueT, in[1], in[2]);
            return;
        }
        if (pointer != null && isBufferClass(pointer[2]) && containsDouble(pointer[3])) {
            storePiecewise(pointer[2], pointer[3], in[1], in[2]);
            return;
        }
        out.add(in);
    }

    private void lowerChain(int[] in) {
        final int[] pointer = orig.get(typeOf(in[3]));
        if (pointer == null || !containsDouble(pointer[3]) || !isBufferClass(pointer[2])) {
            out.add(in);
            return;
        }
        final List<Integer> indices = new ArrayList<>();
        inBuffer = true;
        walk(pointer[3], true, Arrays.copyOfRange(in, 4, in.length), indices, zeroInt());
        final int[] operands = new int[indices.size() + 3];
        operands[0] = in[1];
        operands[1] = in[2];
        operands[2] = in[3];
        for (int k = 0; k < indices.size(); k++) operands[k + 3] = indices.get(k);
        out.add(inst(op(in), operands));
    }

    private int zero;

    private int zeroInt() {
        if (zero == 0) {
            zero = fresh();
            final int[] made = inst(OP_CONSTANT, sint, zero, 0);
            globals.add(made);
            defined.put(zero, made);
            valueType.put(zero, sint);
        }
        return zero;
    }

    private void lowerExtract(int[] in) {
        final int base = typeOf(in[3]);
        if (!containsDouble(base)) {
            out.add(in);
            return;
        }
        final List<Integer> indices = new ArrayList<>();
        inBuffer = isBufferContext(base);
        int current = base;
        for (int k = 4; k < in.length; k++) {
            final int index = in[k];
            indices.add(index);
            final int[] t = orig.get(current);
            if (op(t) == OP_TYPE_STRUCT) {
                inBuffer = offsetStructs.contains(current);
                current = t[2 + index];
            } else if (op(t) == OP_TYPE_ARRAY || op(t) == OP_TYPE_RUNTIME_ARRAY) {
                final boolean wrapped = strided.contains(current) && isDvec(t[2]) && vectorSize(t[2]) == 3;
                inBuffer = strided.contains(current);
                current = t[2];
                if (wrapped) indices.add(0);
            } else if (op(t) == OP_TYPE_MATRIX) {
                current = t[2];
                if (inBuffer && vectorSize(current) == 3) indices.add(0);
                inBuffer = false;
            } else {
                current = t[2];
            }
        }
        final boolean whole = inBuffer && bufferDiffers(current);
        final int result = whole ? fresh() : in[2];
        final int resultType = whole ? bufferType(current) : plainType(in[1]);
        final int[] operands = new int[indices.size() + 3];
        operands[0] = resultType;
        operands[1] = result;
        operands[2] = in[3];
        for (int k = 0; k < indices.size(); k++) operands[k + 3] = indices.get(k);
        out.add(inst(OP_COMPOSITE_EXTRACT, operands));
        if (whole) emit(OP_COPY_OBJECT, plainType(in[1]), in[2], fromBuffer(current, result));
    }

    private void lowerConstruct(int[] in) {
        final int type = in[1];
        if (isDvec(type)) {
            final List<Integer> parts = new ArrayList<>();
            for (int k = 3; k < in.length; k++) {
                final int part = in[k];
                if (isDouble(typeOf(part))) parts.add(part);
                else for (int c : components(part)) parts.add(c);
            }
            finish(type, in[2], parts.stream().mapToInt(Integer::intValue).toArray());
            return;
        }
        if (isBufferContext(type) && containsDouble(type)) {
            final int[] t = orig.get(type);
            final int[] words = in.clone();
            for (int k = 3; k < in.length; k++) {
                final int element = op(t) == OP_TYPE_STRUCT ? t[2 + k - 3] : t[2];
                if (isDmat(element) && matrixRows(element) == 3) words[k] = toBuffer(element, in[k]);
                else if (op(t) != OP_TYPE_STRUCT && isDvec(element) && vectorSize(element) == 3) words[k] = construct(wrap3(), in[k]);
            }
            out.add(words);
            return;
        }
        out.add(in);
    }

    private void lowerShuffle(int[] in) {
        if (!isDvec(in[1])) {
            out.add(in);
            return;
        }
        final int[] a = components(in[3]), b = components(in[4]);
        final int[] parts = new int[in.length - 5];
        for (int k = 5; k < in.length; k++) parts[k - 5] = in[k] < a.length ? a[in[k]] : b[in[k] - a.length];
        finish(in[1], in[2], parts);
    }

    private void lowerExtractDynamic(int[] in) {
        final int vector = typeOf(in[3]);
        if (!isDvec(vector)) {
            out.add(in);
            return;
        }
        final int variable = temp(vector);
        emit(OP_STORE, variable, in[3]);
        final int element = fresh();
        emit(OP_ACCESS_CHAIN, pointerTo(SC_FUNCTION, uvec2), element, variable, in[4]);
        emit(OP_LOAD, uvec2, in[2], element);
        releaseTemps();
    }

    private void lowerInsertDynamic(int[] in) {
        final int vector = in[1];
        if (!isDvec(vector)) {
            out.add(in);
            return;
        }
        final int variable = temp(vector);
        emit(OP_STORE, variable, in[3]);
        final int element = fresh();
        emit(OP_ACCESS_CHAIN, pointerTo(SC_FUNCTION, uvec2), element, variable, in[5]);
        emit(OP_STORE, element, in[4]);
        emit(OP_LOAD, vector, in[2], variable);
        releaseTemps();
    }

    private void lowerTranspose(int[] in) {
        if (!isDmat(in[1])) {
            out.add(in);
            return;
        }
        final int columns = matrixColumns(in[1]), rows = matrixRows(in[1]);
        final int[] parts = new int[columns];
        for (int c = 0; c < columns; c++) {
            final int[] column = new int[rows];
            for (int r = 0; r < rows; r++) column[r] = matrixEntry(in[3], r, c);
            parts[c] = construct(matrixColumnType(in[1]), column);
        }
        final int[] operands = new int[columns + 2];
        operands[0] = in[1];
        operands[1] = in[2];
        System.arraycopy(parts, 0, operands, 2, columns);
        emit(OP_COMPOSITE_CONSTRUCT, operands);
    }

    private static String arithmetic(int op) {
        return switch (op) {
            case OP_F_ADD -> "ad_add";
            case OP_F_SUB -> "ad_sub";
            case OP_F_MUL -> "ad_mul";
            case OP_F_DIV -> "ad_div";
            case OP_F_REM -> "ad_rem";
            case OP_F_MOD -> "ad_mod";
            default -> throw new IllegalStateException();
        };
    }

    private void lowerArithmetic(int[] in) {
        final int type = in[1];
        if (!containsDouble(type)) {
            out.add(in);
            return;
        }
        if (isDmat(type)) {
            // A matrix's own negation, and sums GLSL writes between matrices, go a column at a time.
            final int columns = matrixColumns(type), rows = matrixRows(type);
            final int[] parts = new int[columns];
            for (int c = 0; c < columns; c++) {
                final int[] column = new int[rows];
                for (int r = 0; r < rows; r++) {
                    final int a = matrixEntry(in[3], c, r);
                    column[r] = op(in) == OP_F_NEGATE ? call("ad_neg", a) : call(arithmetic(op(in)), a, matrixEntry(in[4], c, r));
                }
                parts[c] = construct(matrixColumnType(type), column);
            }
            final int[] operands = new int[columns + 2];
            operands[0] = type;
            operands[1] = in[2];
            System.arraycopy(parts, 0, operands, 2, columns);
            emit(OP_COMPOSITE_CONSTRUCT, operands);
            return;
        }
        final int[] a = components(in[3]);
        final int[] parts = new int[a.length];
        if (op(in) == OP_F_NEGATE) {
            for (int c = 0; c < a.length; c++) parts[c] = call("ad_neg", a[c]);
        } else {
            final int[] b = components(in[4]);
            for (int c = 0; c < a.length; c++) parts[c] = call(arithmetic(op(in)), a[c], b[c]);
        }
        finish(type, in[2], parts);
    }

    private void lowerLinear(int[] in) {
        final int type = in[1];
        switch (op(in)) {
            case OP_VECTOR_TIMES_SCALAR -> {
                if (!isDvec(type)) {
                    out.add(in);
                    return;
                }
                final int[] a = components(in[3]);
                final int[] parts = new int[a.length];
                for (int c = 0; c < a.length; c++) parts[c] = call("ad_mul", a[c], in[4]);
                finish(type, in[2], parts);
            }
            case OP_MATRIX_TIMES_SCALAR -> {
                if (!isDmat(type)) {
                    out.add(in);
                    return;
                }
                final int columns = matrixColumns(type), rows = matrixRows(type);
                final int[] parts = new int[columns];
                for (int c = 0; c < columns; c++) {
                    final int[] column = new int[rows];
                    for (int r = 0; r < rows; r++) column[r] = call("ad_mul", matrixEntry(in[3], c, r), in[4]);
                    parts[c] = construct(matrixColumnType(type), column);
                }
                finishMatrix(type, in[2], parts);
            }
            case OP_MATRIX_TIMES_VECTOR -> {
                if (!isDvec(type)) {
                    out.add(in);
                    return;
                }
                final int[] v = components(in[4]);
                final int rows = vectorSize(type);
                final int[] parts = new int[rows];
                for (int r = 0; r < rows; r++) {
                    int sum = call("ad_mul", matrixEntry(in[3], 0, r), v[0]);
                    for (int c = 1; c < v.length; c++) sum = call("ad_add", sum, call("ad_mul", matrixEntry(in[3], c, r), v[c]));
                    parts[r] = sum;
                }
                finish(type, in[2], parts);
            }
            case OP_VECTOR_TIMES_MATRIX -> {
                if (!isDvec(type)) {
                    out.add(in);
                    return;
                }
                final int[] v = components(in[3]);
                final int columns = vectorSize(type);
                final int[] parts = new int[columns];
                for (int c = 0; c < columns; c++) {
                    int sum = call("ad_mul", v[0], matrixEntry(in[4], c, 0));
                    for (int r = 1; r < v.length; r++) sum = call("ad_add", sum, call("ad_mul", v[r], matrixEntry(in[4], c, r)));
                    parts[c] = sum;
                }
                finish(type, in[2], parts);
            }
            case OP_MATRIX_TIMES_MATRIX -> {
                if (!isDmat(type)) {
                    out.add(in);
                    return;
                }
                final int left = typeOf(in[3]), right = typeOf(in[4]);
                final int columns = matrixColumns(right), rows = matrixRows(left), inner = matrixColumns(left);
                final int[] parts = new int[columns];
                for (int c = 0; c < columns; c++) {
                    final int[] column = new int[rows];
                    for (int r = 0; r < rows; r++) {
                        int sum = call("ad_mul", matrixEntry(in[3], 0, r), matrixEntry(in[4], c, 0));
                        for (int k = 1; k < inner; k++) sum = call("ad_add", sum, call("ad_mul", matrixEntry(in[3], k, r), matrixEntry(in[4], c, k)));
                        column[r] = sum;
                    }
                    parts[c] = construct(matrixColumnType(type), column);
                }
                finishMatrix(type, in[2], parts);
            }
            case OP_OUTER_PRODUCT -> {
                if (!isDmat(type)) {
                    out.add(in);
                    return;
                }
                final int[] a = components(in[3]), b = components(in[4]);
                final int[] parts = new int[b.length];
                for (int c = 0; c < b.length; c++) {
                    final int[] column = new int[a.length];
                    for (int r = 0; r < a.length; r++) column[r] = call("ad_mul", a[r], b[c]);
                    parts[c] = construct(matrixColumnType(type), column);
                }
                finishMatrix(type, in[2], parts);
            }
            default -> {
                if (!isDouble(type)) {
                    out.add(in);
                    return;
                }
                final int[] a = components(in[3]), b = components(in[4]);
                int sum = call("ad_mul", a[0], b[0]);
                for (int c = 1; c < a.length; c++) sum = call("ad_add", sum, call("ad_mul", a[c], b[c]));
                emit(OP_COPY_OBJECT, uvec2, in[2], sum);
            }
        }
    }

    private void finishMatrix(int type, int result, int[] columns) {
        final int[] operands = new int[columns.length + 2];
        operands[0] = type;
        operands[1] = result;
        System.arraycopy(columns, 0, operands, 2, columns.length);
        emit(OP_COMPOSITE_CONSTRUCT, operands);
    }

    private void finishBools(int type, int result, int[] parts) {
        if (parts.length == 1) {
            emit(OP_COPY_OBJECT, type, result, parts[0]);
        } else {
            final int[] operands = new int[parts.length + 2];
            operands[0] = type;
            operands[1] = result;
            System.arraycopy(parts, 0, operands, 2, parts.length);
            emit(OP_COMPOSITE_CONSTRUCT, operands);
        }
    }

    private void lowerTest(int[] in) {
        final Integer type = valueType.get(in[3]);
        if (type == null || !containsDouble(type)) {
            out.add(in);
            return;
        }
        final int[] a = components(in[3]);
        final int[] parts = new int[a.length];
        for (int c = 0; c < a.length; c++) parts[c] = call(op(in) == OP_IS_NAN ? "ad_isnan" : "ad_isinf", a[c]);
        finishBools(in[1], in[2], parts);
    }

    private int not(int value) {
        final int id = fresh();
        emit(OP_LOGICAL_NOT, bool, id, value);
        return id;
    }

    private int comparison(int op, int a, int b) {
        // In the order of the opcodes: equal, not equal, less, greater, less or equal, greater or equal, each ordered and then unordered.
        return switch (op - OP_F_ORD_EQUAL) {
            case 0 -> call("ad_eq", a, b);
            case 1 -> or(call("ad_eq", a, b), call("ad_unordered", a, b));
            case 2 -> and(not(call("ad_eq", a, b)), not(call("ad_unordered", a, b)));
            case 3 -> not(call("ad_eq", a, b));
            case 4 -> call("ad_lt", a, b);
            case 5 -> not(call("ad_le", b, a));
            case 6 -> call("ad_lt", b, a);
            case 7 -> not(call("ad_le", a, b));
            case 8 -> call("ad_le", a, b);
            case 9 -> not(call("ad_lt", b, a));
            case 10 -> call("ad_le", b, a);
            default -> not(call("ad_lt", a, b));
        };
    }

    private int or(int a, int b) {
        final int id = fresh();
        emit(OP_LOGICAL_OR, bool, id, a, b);
        return id;
    }

    private int and(int a, int b) {
        final int id = fresh();
        emit(OP_LOGICAL_AND, bool, id, a, b);
        return id;
    }

    private void lowerComparison(int[] in) {
        final Integer type = valueType.get(in[3]);
        if (type == null || !containsDouble(type)) {
            out.add(in);
            return;
        }
        final int[] a = components(in[3]), b = components(in[4]);
        final int[] parts = new int[a.length];
        for (int c = 0; c < a.length; c++) parts[c] = comparison(op(in), a[c], b[c]);
        finishBools(in[1], in[2], parts);
    }

    private int elementOf(int type, int value, int index, int size) {
        if (size == 1) return value;
        final int[] t = orig.get(type);
        return extract(t[2], value, index);
    }

    private void lowerConvert(int[] in) {
        final int op = op(in), result = in[1];
        final int sourceType = typeOf(in[3]);
        final boolean fromDouble = containsDouble(sourceType), toDouble = containsDouble(result);
        if (!fromDouble && !toDouble) {
            out.add(in);
            return;
        }
        if (fromDouble && toDouble) {
            emit(OP_COPY_OBJECT, plainType(result), in[2], in[3]);
            return;
        }
        if (toDouble) {
            final int size = isDouble(result) ? 1 : vectorSize(result);
            final int[] parts = new int[size];
            for (int c = 0; c < size; c++) {
                final int element = elementOf(sourceType, in[3], c, size);
                parts[c] = call(op == OP_CONVERT_S_TO_F ? "ad_fromint" : op == OP_CONVERT_U_TO_F ? "ad_fromuint" : "ad_fromfloat", element);
            }
            finish(result, in[2], parts);
            return;
        }
        final int[] a = components(in[3]);
        final int[] parts = new int[a.length];
        for (int c = 0; c < a.length; c++) parts[c] = call(op == OP_CONVERT_F_TO_S ? "ad_toint" : op == OP_CONVERT_F_TO_U ? "ad_touint" : "ad_tofloat", a[c]);
        if (a.length == 1) {
            emit(OP_COPY_OBJECT, result, in[2], parts[0]);
        } else {
            final int[] operands = new int[a.length + 2];
            operands[0] = result;
            operands[1] = in[2];
            System.arraycopy(parts, 0, operands, 2, a.length);
            emit(OP_COMPOSITE_CONSTRUCT, operands);
        }
    }

    private void lowerSelect(int[] in) {
        final int type = in[1];
        if (!containsDouble(type)) {
            out.add(in);
            return;
        }
        final int condition = in[3];
        final int vector2 = intern(OP_TYPE_VECTOR, bool, 2);
        if (isDouble(type)) {
            final int both = construct(vector2, condition, condition);
            emit(OP_SELECT, uvec2, in[2], both, in[4], in[5]);
            return;
        }
        if (isDvec(type)) {
            final int size = vectorSize(type);
            final boolean scalarCondition = op(orig.getOrDefault(typeOf(condition), defined.get(typeOf(condition)))) == OP_TYPE_BOOL;
            final int[] a = components(in[4]), b = components(in[5]);
            final int[] parts = new int[size];
            for (int c = 0; c < size; c++) {
                final int picked = scalarCondition ? condition : extract(bool, condition, c);
                final int both = construct(vector2, picked, picked);
                final int id = fresh();
                emit(OP_SELECT, uvec2, id, both, a[c], b[c]);
                parts[c] = id;
            }
            finish(type, in[2], parts);
            return;
        }
        throw new IllegalArgumentException("a selection between double matrices or arrays");
    }

    private static String unary(int instruction) {
        return switch (instruction) {
            case GLSL_ROUND, GLSL_ROUND_EVEN -> "ad_roundeven";
            case GLSL_TRUNC -> "ad_trunc";
            case GLSL_F_ABS -> "ad_abs";
            case GLSL_F_SIGN -> "ad_sign";
            case GLSL_FLOOR -> "ad_floor";
            case GLSL_CEIL -> "ad_ceil";
            case GLSL_FRACT -> "ad_fract";
            case GLSL_SQRT -> "ad_sqrt";
            case GLSL_INVERSE_SQRT -> "ad_inversesqrt";
            default -> null;
        };
    }

    private static String binary(int instruction) {
        return switch (instruction) {
            case GLSL_F_MIN, GLSL_N_MIN -> "ad_min";
            case GLSL_F_MAX, GLSL_N_MAX -> "ad_max";
            case GLSL_STEP -> "ad_step";
            default -> null;
        };
    }

    private static String ternary(int instruction) {
        return switch (instruction) {
            case GLSL_F_CLAMP, GLSL_N_CLAMP -> "ad_clamp";
            case GLSL_F_MIX -> "ad_mix";
            case GLSL_SMOOTH_STEP -> "ad_smoothstep";
            case GLSL_FMA -> "ad_fma";
            default -> null;
        };
    }

    private void lowerExtended(int[] in) {
        final int instruction = in[4];
        final int type = in[1];
        final boolean involved = in[3] == glsl && (containsDouble(type) || anyDoubleArgument(in));
        if (!involved) {
            out.add(in);
            return;
        }
        final int count = isDouble(type) ? 1 : isDvec(type) ? vectorSize(type) : 0;
        if (instruction == GLSL_PACK_DOUBLE || instruction == GLSL_UNPACK_DOUBLE) {
            emit(OP_COPY_OBJECT, uvec2, in[2], in[5]);
            return;
        }
        final String one = unary(instruction), two = binary(instruction), three = ternary(instruction);
        if (one != null) {
            final int[] a = components(in[5]);
            final int[] parts = new int[count];
            for (int c = 0; c < count; c++) parts[c] = call(one, a[c]);
            finish(type, in[2], parts);
        } else if (two != null) {
            final int[] a = componentsOr(in[5], count), b = componentsOr(in[6], count);
            final int[] parts = new int[count];
            for (int c = 0; c < count; c++) parts[c] = call(two, a[c], b[c]);
            finish(type, in[2], parts);
        } else if (three != null) {
            final int[] a = componentsOr(in[5], count), b = componentsOr(in[6], count), d = componentsOr(in[7], count);
            final int[] parts = new int[count];
            for (int c = 0; c < count; c++) parts[c] = call(three, a[c], b[c], d[c]);
            finish(type, in[2], parts);
        } else {
            switch (instruction) {
                case GLSL_MODF -> {
                    final int[] a = components(in[5]);
                    final int[] parts = new int[count], wholes = new int[count];
                    final int whole = temp(uvec2);
                    for (int c = 0; c < count; c++) {
                        final int value = a[c];
                        parts[c] = callOut("ad_modf", whole, value);
                        final int loaded = fresh();
                        emit(OP_LOAD, uvec2, loaded, whole);
                        wholes[c] = loaded;
                    }
                    storeParts(in[6], type, wholes);
                    finish(type, in[2], parts);
                }
                case GLSL_MODF_STRUCT, GLSL_FREXP_STRUCT -> lowerStructResult(in, instruction == GLSL_MODF_STRUCT);
                case GLSL_FREXP -> {
                    final int[] a = components(in[5]);
                    final int[] parts = new int[count], exponents = new int[count];
                    final int exponent = temp(sint);
                    for (int c = 0; c < count; c++) {
                        parts[c] = callOut("ad_frexp", exponent, a[c]);
                        final int loaded = fresh();
                        emit(OP_LOAD, sint, loaded, exponent);
                        exponents[c] = loaded;
                    }
                    final int[] pointer = orig.get(typeOf(in[6]));
                    emit(OP_STORE, in[6], count == 1 ? exponents[0] : construct(pointer[3], exponents));
                    finish(type, in[2], parts);
                }
                case GLSL_LDEXP -> {
                    final int[] a = components(in[5]);
                    final int[] parts = new int[count];
                    final int exponents = typeOf(in[6]);
                    for (int c = 0; c < count; c++) parts[c] = call("ad_ldexp", a[c], elementOf(exponents, in[6], c, count));
                    finish(type, in[2], parts);
                }
                case GLSL_LENGTH -> emitCopy(in, call(sized("ad_length", in[5]), in[5]));
                case GLSL_DISTANCE -> emitCopy(in, call(sized("ad_distance", in[5]), in[5], in[6]));
                case GLSL_CROSS -> emitCopy(in, call("ad_cross3", in[5], in[6]));
                case GLSL_NORMALIZE -> emitCopy(in, call(sized("ad_normalize", in[5]), in[5]));
                case GLSL_FACE_FORWARD -> emitCopy(in, call(sized("ad_faceforward", in[5]), in[5], in[6], in[7]));
                case GLSL_REFLECT -> emitCopy(in, call(sized("ad_reflect", in[5]), in[5], in[6]));
                case GLSL_REFRACT -> emitCopy(in, call(sized("ad_refract", in[5]), in[5], in[6], in[7]));
                case GLSL_DETERMINANT -> emitCopy(in, call("ad_determinant" + matrixColumns(typeOf(in[5])), in[5]));
                case GLSL_MATRIX_INVERSE -> emitCopy(in, call("ad_inverse" + matrixColumns(typeOf(in[5])), in[5]));
                default -> throw new IllegalArgumentException("GLSL.std.450 instruction " + instruction + " on doubles");
            }
        }
    }

    /** The forms returning a struct of the two results: the fraction and whole number, or the mantissa and exponent. */
    private void lowerStructResult(int[] in, boolean modf) {
        final int[] struct = orig.get(in[1]);
        final int valueShape = struct[2], otherShape = struct[3];
        final int[] a = components(in[5]);
        final int[] first = new int[a.length], second = new int[a.length];
        final int other = temp(modf ? uvec2 : sint);
        for (int c = 0; c < a.length; c++) {
            first[c] = callOut(modf ? "ad_modf" : "ad_frexp", other, a[c]);
            final int loaded = fresh();
            emit(OP_LOAD, modf ? uvec2 : sint, loaded, other);
            second[c] = loaded;
        }
        releaseTemps();
        final int firstValue = a.length == 1 ? first[0] : construct(valueShape, first);
        final int secondValue = a.length == 1 ? second[0] : construct(modf ? otherShape : otherShape, second);
        emit(OP_COMPOSITE_CONSTRUCT, in[1], in[2], firstValue, secondValue);
    }

    private void storeParts(int pointer, int type, int[] parts) {
        emit(OP_STORE, pointer, parts.length == 1 ? parts[0] : construct(type, parts));
    }

    private int callOut(String function, int outVariable, int value) {
        final int[] described = libFunction.get(function);
        usedLib.add(described[0]);
        final int in = temp(described[2]);
        emit(OP_STORE, in, value);
        final int id = fresh();
        emit(OP_FUNCTION_CALL, described[1], id, described[0], in, outVariable);
        tempsTaken.merge(described[2], -1, Integer::sum);
        return id;
    }

    private void emitCopy(int[] in, int value) {
        emit(OP_COPY_OBJECT, plainType(in[1]), in[2], value);
    }

    private String sized(String function, int value) {
        final int type = typeOf(value);
        return function + (isDouble(type) ? 1 : vectorSize(type));
    }

    private boolean anyDoubleArgument(int[] in) {
        for (int k = 5; k < in.length; k++) {
            final Integer type = valueType.get(in[k]);
            if (type != null && containsDouble(type)) return true;
        }
        return false;
    }

    /** What a global refers to, which it has to be declared after. */
    private void dependencies(int[] g, java.util.function.IntConsumer use) {
        final int op = op(g);
        switch (op) {
            case OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_RUNTIME_ARRAY, OP_TYPE_IMAGE, OP_TYPE_SAMPLED_IMAGE -> use.accept(g[2]);
            case OP_TYPE_ARRAY -> {
                use.accept(g[2]);
                use.accept(g[3]);
            }
            case OP_TYPE_POINTER -> use.accept(g[3]);
            case OP_TYPE_STRUCT, OP_TYPE_FUNCTION -> {
                for (int k = 2; k < g.length; k++) use.accept(g[k]);
            }
            case OP_CONSTANT_COMPOSITE, OP_SPEC_COMPOSITE -> {
                use.accept(g[1]);
                for (int k = 3; k < g.length; k++) use.accept(g[k]);
            }
            case OP_CONSTANT, OP_CONSTANT_TRUE, OP_CONSTANT_FALSE, OP_CONSTANT_NULL, OP_SPEC_TRUE, OP_SPEC_FALSE, OP_SPEC_CONSTANT, OP_UNDEF -> use.accept(g[1]);
            case OP_VARIABLE -> {
                use.accept(g[1]);
                if (g.length > 4) use.accept(g[4]);
            }
            default -> {
                if (hasType(op) && g.length > 2) use.accept(g[1]);
            }
        }
    }

    private void visit(int id, Map<Integer, int[]> all, Set<Integer> done, List<int[]> into) {
        final int[] g = all.get(id);
        if (g == null || !done.add(id)) return;
        dependencies(g, dep -> visit(dep, all, done, into));
        into.add(g);
    }

    private int[] assemble(List<List<int[]>> lowered) {
        // Which of the library's functions are reached, directly or through each other.
        final ArrayDeque<Integer> pending = new ArrayDeque<>(usedLib);
        final Set<Integer> reached = new HashSet<>(usedLib);
        while (!pending.isEmpty()) {
            final List<int[]> body = libBodies.get(pending.poll());
            if (body == null) continue;
            for (int[] in : body) {
                if (op(in) == OP_FUNCTION_CALL && reached.add(in[3])) pending.add(in[3]);
            }
        }
        final List<List<int[]>> kept = new ArrayList<>(lowered);
        for (int f : reached) if (libBodies.containsKey(f)) kept.add(libBodies.get(f));

        // Only the library's globals its reached functions use.
        final Map<Integer, int[]> libById = new HashMap<>();
        for (int[] g : libGlobals) libById.put(g[hasType(op(g)) ? 2 : 1], g);
        final Set<Integer> usedIds = new HashSet<>();
        for (List<int[]> function : kept) for (int[] in : function) mapIds(in.clone(), id -> {
            usedIds.add(id);
            return id;
        });
        final Map<Integer, int[]> all = new LinkedHashMap<>();
        for (int[] g : globals) all.put(g[hasType(op(g)) ? 2 : 1], g);
        final List<int[]> ordered = new ArrayList<>();
        final Set<Integer> done = new HashSet<>();
        final Map<Integer, int[]> everything = new HashMap<>(all);
        everything.putAll(libById);
        for (int id : all.keySet()) visit(id, everything, done, ordered);
        for (int id : usedIds) if (libById.containsKey(id)) visit(id, everything, done, ordered);

        final List<int[]> sections = new ArrayList<>();
        sections.addAll(capabilities);
        sections.addAll(extensions);
        sections.addAll(imports);
        for (int[] h : head) if (op(h) == OP_MEMORY_MODEL) sections.add(h);
        sections.addAll(entryPoints);
        for (int[] h : head) if (op(h) != OP_MEMORY_MODEL) sections.add(h);
        sections.addAll(names);
        sections.addAll(annotations);
        sections.addAll(ordered);
        for (List<int[]> function : kept) sections.addAll(function);
        int size = 5;
        for (int[] in : sections) size += in.length;
        final int[] result = new int[size];
        System.arraycopy(source, 0, result, 0, 5);
        result[3] = bound;
        int at = 5;
        for (int[] in : sections) {
            System.arraycopy(in, 0, result, at, in.length);
            at += in.length;
        }
        return result;
    }
}
