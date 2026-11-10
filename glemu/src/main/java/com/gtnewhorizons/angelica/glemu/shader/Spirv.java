package com.gtnewhorizons.angelica.glemu.shader;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Spirv {
    public static final int MAGIC = 0x07230203;

    public static final int OP_CONVERT_S_TO_F = 111;
    public static final int EXECUTION_MODE_LOCAL_SIZE = 17, IMAGE_SAMPLED_STORAGE = 2;
    public static final int OP_NAME = 5, OP_MEMBER_NAME = 6, OP_ENTRY_POINT = 15, OP_EXECUTION_MODE = 16;
    public static final int OP_TYPE_VOID = 19, OP_TYPE_BOOL = 20, OP_TYPE_INT = 21, OP_TYPE_FLOAT = 22, OP_TYPE_VECTOR = 23, OP_TYPE_MATRIX = 24;
    public static final int OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLER = 26, OP_TYPE_SAMPLED_IMAGE = 27, OP_TYPE_ARRAY = 28, OP_TYPE_RUNTIME_ARRAY = 29;
    public static final int OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32;
    public static final int OP_CONSTANT = 43, OP_CONSTANT_NULL = 46, OP_SPEC_CONSTANT = 50, OP_FUNCTION = 54;
    public static final int OP_FUNCTION_END = 56, OP_LABEL = 248, OP_STORE = 62;
    public static final int OP_VARIABLE = 59, OP_LOAD = 61, OP_COPY_OBJECT = 83, OP_IMAGE = 100;
    public static final int OP_CAPABILITY = 17, OP_COMPOSITE_CONSTRUCT = 80, OP_IMAGE_SAMPLE_FIRST = 87, OP_IMAGE_FETCH = 95, OP_IMAGE_DREF_GATHER = 97;
    public static final int OP_IMAGE_QUERY_SIZE_LOD = 103, OP_IMAGE_QUERY_SIZE = 104, OP_F_DIV = 136;
    public static final int CAP_IMAGE_RECT = 36, CAP_SAMPLED_RECT = 37, CAP_IMAGE_QUERY = 50, DIM_2D = 1, DIM_RECT = 4, IMAGE_OPERAND_LOD = 2;
    public static final String RECT_IMAGE_NAME = "angelica_rect_image";
    public static final String BUFFER_IMAGE_NAME = "angelica_buffer_image";
    /** Texels per row of the image behind a buffer texture, which the lowered shader and {@code GlBase} must agree on. */
    public static final int BUFFER_TEXTURE_ROW_BITS = 12;
    private static final int CAP_SAMPLED_BUFFER = 46, CAP_IMAGE_BUFFER = 47, DIM_BUFFER = 5;
    private static final int OP_IMAGE_TEXEL_POINTER = 60, OP_IMAGE_READ = 98, OP_IMAGE_WRITE = 99;
    private static final int OP_COMPOSITE_EXTRACT = 81, OP_I_MUL = 132, OP_SHIFT_RIGHT_ARITHMETIC = 195, OP_BITWISE_AND = 199;
    public static final int OP_DECORATE = 71, OP_MEMBER_DECORATE = 72, OP_DECORATE_STRING = 5632;

    public static final int DEC_BLOCK = 2, DEC_BUFFER_BLOCK = 3, DEC_ROW_MAJOR = 4, DEC_ARRAY_STRIDE = 6, DEC_MATRIX_STRIDE = 7;
    public static final int DEC_BUILTIN = 11, DEC_LOCATION = 30, DEC_BINDING = 33, DEC_DESCRIPTOR_SET = 34, DEC_OFFSET = 35;
    public static final int DEC_NON_WRITABLE = 24, DEC_SPEC_ID = 1;

    public static final int SC_FUNCTION = 7, SC_UNIFORM_CONSTANT = 0, SC_INPUT = 1, SC_UNIFORM = 2, SC_OUTPUT = 3, SC_PUSH_CONSTANT = 9, SC_STORAGE_BUFFER = 12;

    public final int[] words;

    final Int2ObjectOpenHashMap<String> names = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<String>> memberNames = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Type> types = new Int2ObjectOpenHashMap<>();
    final Int2IntOpenHashMap constants = new Int2IntOpenHashMap();
    final Int2ObjectOpenHashMap<Int2IntOpenHashMap> decorations = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<Int2IntOpenHashMap>> memberDecorations = new Int2ObjectOpenHashMap<>();
    public final List<Variable> variables = new ArrayList<>();
    /** The first entry point's execution model, 0 for a vertex shader. */
    public int executionModel = -1;

    public static final class Type {
        public final int id;
        public final int op;
        public final int[] operands;

        Type(int id, int op, int[] operands) {
            this.id = id;
            this.op = op;
            this.operands = operands;
        }
    }

    public static final class Variable {
        public final int id;
        public final int typeId;
        public final int storageClass;

        Variable(int id, int typeId, int storageClass) {
            this.id = id;
            this.typeId = typeId;
            this.storageClass = storageClass;
        }
    }

    public Spirv(ByteBuffer spirv) {
        final IntBuffer ib = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        words = new int[ib.remaining()];
        ib.get(words);
        if (words.length < 5 || words[0] != MAGIC) throw new IllegalArgumentException("not SPIR-V");
        parse();
    }

    private void parse() {
        int i = 5;
        while (i < words.length) {
            final int wc = words[i] >>> 16;
            final int op = words[i] & 0xFFFF;
            if (wc == 0) throw new IllegalArgumentException("bad SPIR-V word count at " + i);
            switch (op) {
                case OP_NAME -> names.put(words[i + 1], string(i + 2, i + wc));
                case OP_ENTRY_POINT -> {
                    if (executionModel < 0) executionModel = words[i + 1];
                }
                case OP_MEMBER_NAME -> memberNames.computeIfAbsent(words[i + 1], k -> new Int2ObjectOpenHashMap<>()).put(words[i + 2], string(i + 3, i + wc));
                case OP_TYPE_VOID, OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_IMAGE, OP_TYPE_SAMPLER,
                     OP_TYPE_SAMPLED_IMAGE, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY, OP_TYPE_STRUCT -> {
                    final int[] ops = new int[wc - 2];
                    System.arraycopy(words, i + 2, ops, 0, ops.length);
                    types.put(words[i + 1], new Type(words[i + 1], op, ops));
                }
                case OP_TYPE_POINTER -> types.put(words[i + 1], new Type(words[i + 1], op, new int[] {words[i + 2], words[i + 3]}));
                case OP_CONSTANT, OP_SPEC_CONSTANT -> {
                    if (wc >= 4) constants.put(words[i + 2], words[i + 3]);
                }
                case OP_VARIABLE -> {
                    final int sc = words[i + 3];
                    if (sc != SC_FUNCTION) variables.add(new Variable(words[i + 2], words[i + 1], sc));
                }
                case OP_DECORATE -> decorations.computeIfAbsent(words[i + 1], k -> new Int2IntOpenHashMap()).put(words[i + 2], wc > 3 ? i + 3 : -1);
                case OP_MEMBER_DECORATE -> memberDecorations.computeIfAbsent(words[i + 1], k -> new Int2ObjectOpenHashMap<>())
                    .computeIfAbsent(words[i + 2], k -> new Int2IntOpenHashMap()).put(words[i + 3], wc > 4 ? i + 4 : -1);
                default -> { }
            }
            i += wc;
        }
    }

    private String string(int from, int to) {
        final StringBuilder sb = new StringBuilder();
        for (int w = from; w < to; w++) {
            final int v = words[w];
            for (int b = 0; b < 4; b++) {
                final int c = (v >>> (b * 8)) & 0xFF;
                if (c == 0) return sb.toString();
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    /** Whether any function uses a global, which GL calls active, as opposed to only declaring it. */
    public boolean referenced(int id) {
        final int first = firstFunction(words);
        for (int i = first; i < words.length; ) {
            final int count = words[i] >>> 16;
            if (count == 0) return false;
            for (int w = i + 1; w < i + count && w < words.length; w++) if (words[w] == id) return true;
            i += count;
        }
        return false;
    }

    public String name(int id) {
        return names.getOrDefault(id, "");
    }

    public String memberName(int structId, int member) {
        final Int2ObjectOpenHashMap<String> m = memberNames.get(structId);
        return m == null ? "" : m.getOrDefault(member, "");
    }

    public Type type(int id) {
        return types.get(id);
    }

    public boolean hasDecoration(int id, int decoration) {
        final Int2IntOpenHashMap d = decorations.get(id);
        return d != null && d.containsKey(decoration);
    }

    public int decoration(int id, int decoration, int fallback) {
        final Int2IntOpenHashMap d = decorations.get(id);
        if (d == null || !d.containsKey(decoration)) return fallback;
        final int at = d.get(decoration);
        return at < 0 ? fallback : words[at];
    }

    public boolean setDecoration(int id, int decoration, int value) {
        final Int2IntOpenHashMap d = decorations.get(id);
        if (d == null || !d.containsKey(decoration)) return false;
        final int at = d.get(decoration);
        if (at < 0) return false;
        words[at] = value;
        return true;
    }

    public boolean hasMemberDecoration(int structId, int member, int decoration) {
        final Int2ObjectOpenHashMap<Int2IntOpenHashMap> m = memberDecorations.get(structId);
        if (m == null) return false;
        final Int2IntOpenHashMap d = m.get(member);
        return d != null && d.containsKey(decoration);
    }

    public int memberDecoration(int structId, int member, int decoration, int fallback) {
        final Int2ObjectOpenHashMap<Int2IntOpenHashMap> m = memberDecorations.get(structId);
        if (m == null) return fallback;
        final Int2IntOpenHashMap d = m.get(member);
        if (d == null || !d.containsKey(decoration)) return fallback;
        final int at = d.get(decoration);
        return at < 0 ? fallback : words[at];
    }

    public Type pointee(Variable v) {
        final Type ptr = types.get(v.typeId);
        return types.get(ptr.operands[1]);
    }

    public int arrayLength(Type t) {
        if (t.op != OP_TYPE_ARRAY) return 0;
        return constants.getOrDefault(t.operands[1], 1);
    }

    public Type elementType(Type t) {
        while (t != null && (t.op == OP_TYPE_ARRAY || t.op == OP_TYPE_RUNTIME_ARRAY)) t = types.get(t.operands[0]);
        return t;
    }

    public int flatArraySize(Type t) {
        int n = 1;
        while (t != null && t.op == OP_TYPE_ARRAY) {
            n *= Math.max(1, arrayLength(t));
            t = types.get(t.operands[0]);
        }
        return n;
    }

    public int locationCount(Type t) {
        return locationCount(t, false);
    }

    /** A vertex shader's inputs take one location for every vector, however wide; everywhere else a dvec3 or dvec4 takes two. */
    public int locationCount(Type t, boolean vertexInput) {
        return switch (t.op) {
            case OP_TYPE_ARRAY -> Math.max(1, arrayLength(t)) * locationCount(types.get(t.operands[0]), vertexInput);
            case OP_TYPE_MATRIX -> t.operands[1] * locationCount(types.get(t.operands[0]), vertexInput);
            case OP_TYPE_VECTOR -> {
                final Type comp = types.get(t.operands[0]);
                final boolean wide = comp.op == OP_TYPE_FLOAT && comp.operands[0] == 64 || comp.op == OP_TYPE_INT && comp.operands[0] == 64;
                yield wide && !vertexInput && t.operands[1] > 2 ? 2 : 1;
            }
            case OP_TYPE_STRUCT -> {
                int n = 0;
                for (int m : t.operands) n += locationCount(types.get(m), vertexInput);
                yield n;
            }
            default -> 1;
        };
    }

    public boolean isBuiltinVariable(Variable v) {
        if (hasDecoration(v.id, DEC_BUILTIN)) return true;
        final Type t = elementType(pointee(v));
        if (t != null && t.op == OP_TYPE_STRUCT && t.operands.length > 0) {
            return hasMemberDecoration(t.id, 0, DEC_BUILTIN);
        }
        return false;
    }

    public byte[] toBytes() {
        final ByteBuffer bb = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asIntBuffer().put(words);
        return bb.array();
    }

    public int scalarKind(Type t) {
        Type s = elementType(t);
        while (s != null && (s.op == OP_TYPE_VECTOR || s.op == OP_TYPE_MATRIX)) s = types.get(s.operands[0]);
        if (s == null) return -1;
        return switch (s.op) {
            case OP_TYPE_FLOAT -> 0;
            case OP_TYPE_INT -> s.operands[1] != 0 ? 1 : 2;
            case OP_TYPE_BOOL -> 3;
            default -> -1;
        };
    }

    /** The size of the vectors a type is made of when they hold doubles, or 0 when it holds none. */
    public int doubleVectorSize(Type t) {
        Type e = elementType(t);
        if (e == null) return 0;
        if (e.op == OP_TYPE_MATRIX) e = types.get(e.operands[0]);
        final Type component = e.op == OP_TYPE_VECTOR ? types.get(e.operands[0]) : e;
        if (component == null || component.op != OP_TYPE_FLOAT || component.operands[0] != 64) return 0;
        return e.op == OP_TYPE_VECTOR ? e.operands[1] : 1;
    }

    public int componentCount(Type t) {
        final Type e = elementType(t);
        if (e == null) return 0;
        return switch (e.op) {
            case OP_TYPE_VECTOR -> e.operands[1];
            case OP_TYPE_MATRIX -> e.operands[1] * componentCount(types.get(e.operands[0]));
            default -> 1;
        };
    }

    /** Marks a variable split from a block array: followed by its flat element number, then its GL subscripts. */
    public static final String BLOCK_ELEMENT = "$element";
    private static final int OP_ARRAY_LENGTH = 68;

    /**
     * Puts each value an atomic takes straight from a load into a copy of its own. SPIRV-Cross keeps a scalar array of a uniform block as padded vectors in Metal,
     * reads the element out of one everywhere but in an atomic's operands, and what it writes there then does not compile.
     */
    public static byte[] copyAtomicOperands(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;
        final Int2IntOpenHashMap loaded = new Int2IntOpenHashMap();
        boolean any = false;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op == OP_LOAD) loaded.put(w[i + 2], w[i + 1]);
            any |= op >= OP_ATOMIC_STORE && op <= OP_ATOMIC_XOR;
            i += wc;
        }
        if (!any) return spirv;
        final IntArrayList out = new IntArrayList(w.length + 32);
        int bound = w[3];
        for (int i = 0; i < 5; i++) out.add(w[i]);
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            final int firstValue = op == OP_ATOMIC_STORE ? 4 : op == OP_ATOMIC_EXCHANGE || op >= OP_ATOMIC_I_ADD && op <= OP_ATOMIC_XOR ? 6 : op == OP_ATOMIC_COMPARE_EXCHANGE ? 7 : 0;
            final int lastValue = op == OP_ATOMIC_COMPARE_EXCHANGE ? 8 : firstValue;
            final int[] instruction = java.util.Arrays.copyOfRange(w, i, i + wc);
            for (int at = firstValue; firstValue != 0 && at <= lastValue; at++) {
                if (!loaded.containsKey(instruction[at])) continue;
                final int copy = bound++;
                out.add(4 << 16 | OP_COPY_OBJECT);
                out.add(loaded.get(instruction[at]));
                out.add(copy);
                out.add(instruction[at]);
                instruction[at] = copy;
            }
            out.addElements(out.size(), instruction);
            i += wc;
        }
        out.set(3, bound);
        return toBytes(out);
    }

    /**
     * GL gives each element of a block array its own block and binding point, which native arrays of buffers do not map onto everywhere, so each element becomes its
     * own variable. A subscript only known at run time becomes a switch around each load, store or atomic through it, as a pointer cannot choose between variables.
     * An array used any other way stays as it was, for the linker to refuse.
     */
    public static byte[] splitBlockArrays(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;
        final Int2ObjectOpenHashMap<int[]> pointers = new Int2ObjectOpenHashMap<>(), arrays = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap constants = new Int2IntOpenHashMap();
        final Int2ObjectOpenHashMap<int[]> blockArrays = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap structOf = new Int2IntOpenHashMap(), storageOf = new Int2IntOpenHashMap();
        // Where each pointer type is declared: one declared after a variable cannot be that variable's type.
        final Int2IntOpenHashMap pointerDeclaredAt = new Int2IntOpenHashMap();
        final int first = firstFunction(w);
        for (int i = 5; i < first; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op == OP_TYPE_POINTER) {
                pointers.put(w[i + 1], new int[] {w[i + 2], w[i + 3]});
                pointerDeclaredAt.put(w[i + 1], i);
            } else if (op == OP_TYPE_ARRAY) arrays.put(w[i + 1], new int[] {w[i + 2], w[i + 3]});
            else if (op == OP_CONSTANT && wc == 4) constants.put(w[i + 2], w[i + 3]);
            else if (op == OP_VARIABLE && (w[i + 3] == SC_UNIFORM || w[i + 3] == SC_STORAGE_BUFFER) && pointers.containsKey(w[i + 1])) {
                int type = pointers.get(w[i + 1])[1];
                final IntArrayList dims = new IntArrayList();
                while (arrays.containsKey(type)) {
                    final int[] array = arrays.get(type);
                    if (!constants.containsKey(array[1])) {
                        dims.clear();
                        break;
                    }
                    dims.add(constants.get(array[1]));
                    type = array[0];
                }
                if (!dims.isEmpty()) {
                    blockArrays.put(w[i + 2], dims.toIntArray());
                    structOf.put(w[i + 2], type);
                    storageOf.put(w[i + 2], w[i + 3]);
                }
            }
            i += wc;
        }
        if (blockArrays.isEmpty()) return spirv;
        // Pointers into an element that is only known at run time, which exist from here on just as what they would have pointed at.
        final Int2ObjectOpenHashMap<DynamicChain> dynamic = new Int2ObjectOpenHashMap<>();
        final IntOpenHashSet kept = new IntOpenHashSet();
        for (int i = first; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            final boolean chain = op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN;
            for (int a = 1; a < wc; a++) {
                if (!takesPointer(op, a)) continue;
                final int word = w[i + a];
                if (blockArrays.containsKey(word)) {
                    final int dimensions = blockArrays.get(word).length;
                    if (!chain || wc < 4 + dimensions) {
                        kept.add(word);
                        continue;
                    }
                    boolean constant = true;
                    for (int d = 0; d < dimensions; d++) constant &= constants.containsKey(w[i + 4 + d]);
                    if (!constant) dynamic.put(w[i + 2], new DynamicChain(word, Arrays.copyOfRange(w, i + 4, i + 4 + dimensions), Arrays.copyOfRange(w, i + 4 + dimensions, i + wc), w[i + 1]));
                } else if (dynamic.containsKey(word)) {
                    final DynamicChain through = dynamic.get(word);
                    if (chain) dynamic.put(w[i + 2], through.then(Arrays.copyOfRange(w, i + 4, i + wc), w[i + 1]));
                    else if (a != pointerOperand(op) || op == OP_ARRAY_LENGTH && through.rest.length > 0) kept.add(through.variable);
                }
            }
            i += wc;
        }
        for (int v : kept) {
            blockArrays.remove(v);
            structOf.remove(v);
        }
        dynamic.values().removeIf(d -> kept.contains(d.variable));
        if (blockArrays.isEmpty()) return spirv;

        int bound = w[3];
        final Int2ObjectOpenHashMap<int[]> elements = new Int2ObjectOpenHashMap<>();
        for (int v : blockArrays.keySet().toIntArray()) {
            int count = 1;
            for (int d : blockArrays.get(v)) count *= d;
            final int[] ids = new int[count];
            for (int e = 0; e < count; e++) ids[e] = bound++;
            elements.put(v, ids);
        }
        final Int2IntOpenHashMap wholeBlock = new Int2IntOpenHashMap();
        final IntArrayList out = new IntArrayList(w.length + 64);
        final IntArrayList declarations = new IntArrayList();
        final Int2IntOpenHashMap uintConstants = new Int2IntOpenHashMap();
        // A block's last part after being split, which is where the branches out of it now come from.
        final Int2IntOpenHashMap lastPart = new Int2IntOpenHashMap();
        int uintType = 0, declareAt = -1, block = 0;
        for (int i = 5; i < first; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == OP_TYPE_INT && w[i + 2] == 32 && w[i + 3] == 0) uintType = w[i + 1];
        }
        for (int i = 0; i < 5; i++) out.add(w[i]);
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (op == OP_FUNCTION && declareAt < 0) declareAt = out.size();
            if (op == OP_LABEL) block = w[i + 1];
            final int pointerAt = pointerOperand(op);
            if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && dynamic.containsKey(w[i + 2])) {
                // Emitted at each use instead.
            } else if (pointerAt > 0 && dynamic.containsKey(w[i + pointerAt])) {
                final DynamicChain chain = dynamic.get(w[i + pointerAt]);
                final int[] dims = blockArrays.get(chain.variable), ids = elements.get(chain.variable);
                int selector = chain.subscripts[0];
                if (dims.length > 1 && uintType == 0) {
                    uintType = bound++;
                    declarations.addElements(declarations.size(), new int[] {4 << 16 | OP_TYPE_INT, uintType, 32, 0});
                }
                for (int d = 1; d < dims.length; d++) {
                    if (!uintConstants.containsKey(dims[d])) {
                        uintConstants.put(dims[d], bound);
                        declarations.addElements(declarations.size(), new int[] {4 << 16 | OP_CONSTANT, uintType, bound++, dims[d]});
                    }
                    final int product = bound++, sum = bound++;
                    out.addElements(out.size(), new int[] {5 << 16 | OP_I_MUL, uintType, product, selector, uintConstants.get(dims[d])});
                    out.addElements(out.size(), new int[] {5 << 16 | OP_I_ADD, uintType, sum, product, chain.subscripts[d]});
                    selector = sum;
                }
                final int merge = bound++;
                final int[] labels = new int[ids.length], results = new int[ids.length];
                for (int e = 0; e < ids.length; e++) labels[e] = bound++;
                out.addElements(out.size(), new int[] {3 << 16 | OP_SELECTION_MERGE, merge, 0});
                out.addElements(out.size(), new int[] {(3 + 2 * (ids.length - 1)) << 16 | OP_SWITCH, selector, labels[0]});
                for (int e = 1; e < ids.length; e++) out.addElements(out.size(), new int[] {e, labels[e]});
                final boolean hasResult = op != OP_STORE && op != OP_ATOMIC_STORE;
                for (int e = 0; e < ids.length; e++) {
                    out.addElements(out.size(), new int[] {2 << 16 | OP_LABEL, labels[e]});
                    int pointer = ids[e];
                    if (chain.rest.length > 0) {
                        pointer = bound++;
                        out.addElements(out.size(), new int[] {(4 + chain.rest.length) << 16 | OP_ACCESS_CHAIN, chain.pointerType, pointer, ids[e]});
                        out.addElements(out.size(), chain.rest);
                    }
                    final int at = out.size();
                    for (int k = i; k < i + wc; k++) out.add(w[k]);
                    out.set(at + pointerAt, pointer);
                    if (hasResult) {
                        results[e] = bound++;
                        out.set(at + 2, results[e]);
                    }
                    out.addElements(out.size(), new int[] {2 << 16 | OP_BRANCH, merge});
                }
                out.addElements(out.size(), new int[] {2 << 16 | OP_LABEL, merge});
                if (hasResult) {
                    out.addElements(out.size(), new int[] {(3 + 2 * ids.length) << 16 | OP_PHI, w[i + 1], w[i + 2]});
                    for (int e = 0; e < ids.length; e++) out.addElements(out.size(), new int[] {results[e], labels[e]});
                }
                lastPart.put(block, merge);
            } else if (op == OP_ARRAY_LENGTH && wholeBlock.containsKey(w[i + 3])) {
                out.addElements(out.size(), new int[] {w[i], w[i + 1], w[i + 2], wholeBlock.get(w[i + 3]), w[i + 4]});
            } else if (op == OP_ENTRY_POINT) {
                final int start = out.size();
                int a = i + 3;
                while (a < i + wc) {
                    final int word = w[a++];
                    if ((word >>> 24 & 0xFF) == 0 || (word >>> 16 & 0xFF) == 0 || (word >>> 8 & 0xFF) == 0 || (word & 0xFF) == 0) break;
                }
                for (int k = i; k < a; k++) out.add(w[k]);
                for (; a < i + wc; a++) {
                    if (elements.containsKey(w[a])) out.addAll(IntArrayList.wrap(elements.get(w[a])));
                    else out.add(w[a]);
                }
                out.set(start, (out.size() - start) << 16 | OP_ENTRY_POINT);
            } else if (op == OP_NAME && elements.containsKey(w[i + 1])) {
                final int[] dims = blockArrays.get(w[i + 1]), ids = elements.get(w[i + 1]);
                for (int e = 0; e < ids.length; e++) {
                    final StringBuilder name = new StringBuilder(BLOCK_ELEMENT).append(e).append(BLOCK_ELEMENT);
                    int stride = ids.length;
                    for (int d : dims) {
                        stride /= d;
                        name.append('[').append(e / stride % d).append(']');
                    }
                    final int[] encoded = encodeName(name.toString());
                    out.add((2 + encoded.length) << 16 | OP_NAME);
                    out.add(ids[e]);
                    out.addElements(out.size(), encoded);
                }
            } else if (op == OP_DECORATE && elements.containsKey(w[i + 1])) {
                final int[] ids = elements.get(w[i + 1]);
                for (int e = 0; e < ids.length; e++) {
                    final int at = out.size();
                    for (int k = i; k < i + wc; k++) out.add(w[k]);
                    out.set(at + 1, ids[e]);
                    if (w[i + 2] == DEC_BINDING) out.set(at + 3, w[i + 3] + e);
                }
            } else if (op == OP_VARIABLE && elements.containsKey(w[i + 2])) {
                final int struct = structOf.get(w[i + 2]);
                int pointer = 0;
                for (int t : pointers.keySet().toIntArray()) {
                    final int[] p = pointers.get(t);
                    if (p[0] == w[i + 3] && p[1] == struct && pointerDeclaredAt.getOrDefault(t, -1) < i) pointer = t;
                }
                if (pointer == 0) {
                    pointer = bound++;
                    pointers.put(pointer, new int[] {w[i + 3], struct});
                    pointerDeclaredAt.put(pointer, -1);
                    out.addElements(out.size(), new int[] {4 << 16 | OP_TYPE_POINTER, pointer, w[i + 3], struct});
                }
                for (int id : elements.get(w[i + 2])) out.addElements(out.size(), new int[] {4 << 16 | OP_VARIABLE, pointer, id, w[i + 3]});
            } else if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && elements.containsKey(w[i + 3])) {
                final int[] dims = blockArrays.get(w[i + 3]);
                int element = 0;
                for (int d = 0; d < dims.length; d++) element = element * dims[d] + constants.get(w[i + 4 + d]);
                final int rest = wc - 4 - dims.length;
                if (rest == 0) wholeBlock.put(w[i + 2], elements.get(w[i + 3])[element]);
                out.addElements(out.size(), new int[] {(4 + rest) << 16 | op, w[i + 1], w[i + 2], elements.get(w[i + 3])[element]});
                for (int k = 0; k < rest; k++) out.add(w[i + 4 + dims.length + k]);
            } else {
                for (int k = i; k < i + wc; k++) out.add(w[k]);
            }
            i += wc;
        }
        if (!lastPart.isEmpty()) {
            for (int i = 5; i < out.size(); ) {
                final int wc = out.getInt(i) >>> 16;
                if ((out.getInt(i) & 0xFFFF) == OP_PHI) {
                    for (int a = 4; a < wc; a += 2) {
                        if (lastPart.containsKey(out.getInt(i + a))) out.set(i + a, lastPart.get(out.getInt(i + a)));
                    }
                }
                i += wc;
            }
        }
        if (!declarations.isEmpty()) out.addElements(declareAt, declarations.toIntArray());
        out.set(3, bound);
        return toBytes(out);
    }

    private static final int OP_FUNCTION_CALL = 57, OP_COPY_MEMORY = 63, OP_COPY_MEMORY_SIZED = 64, OP_I_ADD = 128, OP_SELECT = 169, OP_ATOMIC_LOAD = 227, OP_ATOMIC_STORE = 228, OP_ATOMIC_EXCHANGE = 229, OP_ATOMIC_COMPARE_EXCHANGE = 230, OP_ATOMIC_I_ADD = 234, OP_ATOMIC_XOR = 242;
    private static final int OP_ATOMIC_F_MIN = 5614, OP_ATOMIC_F_MAX = 5615, OP_ATOMIC_F_ADD = 6035, OP_PHI = 245, OP_SELECTION_MERGE = 247, OP_BRANCH = 249, OP_SWITCH = 251;

    /** Which word of an instruction is the pointer it goes through, for those that a block array's element can stand under. */
    private static int pointerOperand(int op) {
        if (op == OP_STORE || op == OP_ATOMIC_STORE) return 1;
        if (op == OP_LOAD || op == OP_ARRAY_LENGTH || op >= OP_ATOMIC_LOAD && op <= OP_ATOMIC_XOR || op == OP_ATOMIC_F_MIN || op == OP_ATOMIC_F_MAX || op == OP_ATOMIC_F_ADD) return 3;
        return 0;
    }

    /** Whether a word of an instruction can be a pointer. Others can hold literals, which may equal a variable's id. */
    private static boolean takesPointer(int op, int word) {
        final int through = pointerOperand(op);
        if (through != 0) return word == through || op == OP_STORE && word == 2;
        return switch (op) {
            case OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN, OP_PTR_ACCESS_CHAIN, OP_COPY_OBJECT -> word == 3;
            case OP_COPY_MEMORY, OP_COPY_MEMORY_SIZED -> word <= 2;
            case OP_FUNCTION_CALL -> word >= 4;
            case OP_SELECT -> word == 4 || word == 5;
            case OP_PHI -> word >= 3 && word % 2 == 1;
            default -> false;
        };
    }

    /** A pointer into a block array chosen at run time: the variable, its subscripts, and the rest of the way in. */
    private record DynamicChain(int variable, int[] subscripts, int[] rest, int pointerType) {
        DynamicChain then(int[] more, int type) {
            final int[] all = Arrays.copyOf(rest, rest.length + more.length);
            System.arraycopy(more, 0, all, rest.length, more.length);
            return new DynamicChain(variable, subscripts, all, type);
        }
    }

    /**
     * Lowers samplerBuffer and imageBuffer to 2D images, which is what every backend can bind without a texel buffer of its own: an
     * index becomes a row and column, and a size query the image's area. The debug name keeps it a buffer texture for reflection.
     */
    public static byte[] lowerBufferImages(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;
        final IntOpenHashSet bufferImages = new IntOpenHashSet(), storageImageIds = new IntOpenHashSet(), types = new IntOpenHashSet();
        final Int2IntOpenHashMap typeOf = new Int2IntOpenHashMap(), pointee = new Int2IntOpenHashMap();
        int intType = 0, ivec2 = 0, intZero = 0, rowMask = 0, rowBits = 0;
        boolean hasImageQuery = false;
        final int first = firstFunction(w);
        final int mask = (1 << BUFFER_TEXTURE_ROW_BITS) - 1;
        for (int i = 5; i < first; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op >= OP_TYPE_VOID && op <= OP_TYPE_POINTER) types.add(w[i + 1]);
            else if (wc >= 3 && types.contains(w[i + 1])) typeOf.put(w[i + 2], w[i + 1]);
            if (op == OP_CAPABILITY && w[i + 1] == CAP_IMAGE_QUERY) hasImageQuery = true;
            if (op == OP_TYPE_IMAGE && w[i + 3] == DIM_BUFFER) {
                bufferImages.add(w[i + 1]);
                if (w[i + 7] == 2) storageImageIds.add(w[i + 1]);
            }
            if (op == OP_TYPE_POINTER) pointee.put(w[i + 1], w[i + 3]);
            if (op == OP_TYPE_INT && w[i + 2] == 32 && w[i + 3] == 1) intType = w[i + 1];
            if (op == OP_TYPE_VECTOR && w[i + 3] == 2 && w[i + 2] == intType) ivec2 = w[i + 1];
            if (op == OP_CONSTANT && w[i + 1] == intType) {
                if (w[i + 3] == 0) intZero = w[i + 2];
                if (w[i + 3] == mask) rowMask = w[i + 2];
                if (w[i + 3] == BUFFER_TEXTURE_ROW_BITS) rowBits = w[i + 2];
            }
            i += wc;
        }
        if (bufferImages.isEmpty()) return spirv;

        int bound = w[3];
        final IntArrayList declared = new IntArrayList();
        if (intType == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_TYPE_INT, intType = bound++, 32, 1});
        if (ivec2 == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_TYPE_VECTOR, ivec2 = bound++, intType, 2});
        if (intZero == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_CONSTANT, intType, intZero = bound++, 0});
        if (rowMask == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_CONSTANT, intType, rowMask = bound++, mask});
        if (rowBits == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_CONSTANT, intType, rowBits = bound++, BUFFER_TEXTURE_ROW_BITS});

        final IntArrayList out = new IntArrayList(w.length + 16);
        for (int i = 0; i < 5; i++) out.add(w[i]);
        boolean named = false;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (op == OP_CAPABILITY) {
                if (w[i + 1] == CAP_SAMPLED_BUFFER || w[i + 1] == CAP_IMAGE_BUFFER) {
                    i += wc;
                    continue;
                }
                if (!hasImageQuery) {
                    out.addElements(out.size(), new int[] {2 << 16 | OP_CAPABILITY, CAP_IMAGE_QUERY});
                    hasImageQuery = true;
                }
            }
            if (!named && !precedesAnnotations(op)) {
                nameImages(out, bufferImages, BUFFER_IMAGE_NAME);
                named = true;
            }
            if (i == first) out.addAll(declared);
            if (i >= first && wc >= 3 && types.contains(w[i + 1])) typeOf.put(w[i + 2], w[i + 1]);
            final int coordinateAt = i < first ? 0 : switch (op) {
                case OP_IMAGE_FETCH, OP_IMAGE_READ -> bufferImages.contains(typeOf.get(w[i + 3])) ? 4 : 0;
                case OP_IMAGE_WRITE -> bufferImages.contains(typeOf.get(w[i + 1])) ? 2 : 0;
                case OP_IMAGE_TEXEL_POINTER -> bufferImages.contains(pointee.get(typeOf.get(w[i + 3]))) ? 4 : 0;
                default -> 0;
            };
            if (coordinateAt != 0) {
                final int x = bound++, y = bound++, coord = bound++;
                out.addElements(out.size(), new int[] {
                    5 << 16 | OP_BITWISE_AND, intType, x, w[i + coordinateAt], rowMask,
                    5 << 16 | OP_SHIFT_RIGHT_ARITHMETIC, intType, y, w[i + coordinateAt], rowBits,
                    5 << 16 | OP_COMPOSITE_CONSTRUCT, ivec2, coord, x, y});
                if (op == OP_IMAGE_FETCH) {
                    out.addElements(out.size(), new int[] {7 << 16 | OP_IMAGE_FETCH, w[i + 1], w[i + 2], w[i + 3], coord, IMAGE_OPERAND_LOD, intZero});
                } else {
                    final int at = out.size();
                    for (int j = 0; j < wc; j++) out.add(w[i + j]);
                    out.set(at + coordinateAt, coord);
                }
            } else if (i >= first && op == OP_IMAGE_QUERY_SIZE && bufferImages.contains(typeOf.get(w[i + 3]))) {
                final int size = bound++, columns = bound++, rows = bound++;
                out.addElements(out.size(), storageImageIds.contains(typeOf.get(w[i + 3]))
                    ? new int[] {4 << 16 | OP_IMAGE_QUERY_SIZE, ivec2, size, w[i + 3]}
                    : new int[] {5 << 16 | OP_IMAGE_QUERY_SIZE_LOD, ivec2, size, w[i + 3], intZero});
                out.addElements(out.size(), new int[] {
                    5 << 16 | OP_COMPOSITE_EXTRACT, intType, columns, size, 0,
                    5 << 16 | OP_COMPOSITE_EXTRACT, intType, rows, size, 1,
                    5 << 16 | OP_I_MUL, w[i + 1], w[i + 2], columns, rows});
            } else {
                final int at = out.size();
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
                if (op == OP_TYPE_IMAGE && bufferImages.contains(w[i + 1])) {
                    out.set(at + 3, DIM_2D);
                }
            }
            i += wc;
        }
        out.set(3, bound);
        return toBytes(out);
    }

    /**
     * Lowers rectangle images to 2D, which every native API has: sample coordinates are divided by the image's size, and size
     * queries and fetches take the level operand 2D requires. A debug name keeps the rectangle visible to reflection, since a
     * rectangle sampler still reads the unit's rectangle binding.
     */
    public static byte[] lowerRectImages(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;
        final IntOpenHashSet rectImages = new IntOpenHashSet(), storageRectImages = new IntOpenHashSet(), types = new IntOpenHashSet();
        final Int2IntOpenHashMap rectSampled = new Int2IntOpenHashMap(), vectorSize = new Int2IntOpenHashMap(), typeOf = new Int2IntOpenHashMap();
        int floatType = 0, intType = 0, vec2 = 0, ivec2 = 0, intZero = 0, floatOne = 0;
        boolean hasImageQuery = false;
        final int first = firstFunction(w);
        for (int i = 5; i < first; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op >= OP_TYPE_VOID && op <= OP_TYPE_POINTER) types.add(w[i + 1]);
            else if (wc >= 3 && types.contains(w[i + 1])) typeOf.put(w[i + 2], w[i + 1]);
            if (op == OP_CAPABILITY && w[i + 1] == CAP_IMAGE_QUERY) hasImageQuery = true;
            if (op == OP_TYPE_IMAGE && w[i + 3] == DIM_RECT) rectImages.add(w[i + 1]);
            if (op == OP_TYPE_IMAGE && w[i + 3] == DIM_RECT && w[i + 7] == 2) storageRectImages.add(w[i + 1]);
            if (op == OP_TYPE_SAMPLED_IMAGE && rectImages.contains(w[i + 2])) rectSampled.put(w[i + 1], w[i + 2]);
            if (op == OP_TYPE_FLOAT && w[i + 2] == 32) floatType = w[i + 1];
            if (op == OP_TYPE_INT && w[i + 2] == 32 && w[i + 3] == 1) intType = w[i + 1];
            if (op == OP_TYPE_VECTOR) {
                vectorSize.put(w[i + 1], w[i + 3]);
                if (w[i + 3] == 2 && w[i + 2] == floatType) vec2 = w[i + 1];
                if (w[i + 3] == 2 && w[i + 2] == intType) ivec2 = w[i + 1];
            }
            if (op == OP_CONSTANT && w[i + 1] == intType && w[i + 3] == 0) intZero = w[i + 2];
            if (op == OP_CONSTANT && w[i + 1] == floatType && w[i + 3] == Float.floatToRawIntBits(1f)) floatOne = w[i + 2];
            i += wc;
        }
        if (rectImages.isEmpty()) return spirv;

        int bound = w[3];
        final IntArrayList declared = new IntArrayList();
        if (floatType == 0) declared.addElements(declared.size(), new int[] {3 << 16 | OP_TYPE_FLOAT, floatType = bound++, 32});
        if (intType == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_TYPE_INT, intType = bound++, 32, 1});
        if (vec2 == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_TYPE_VECTOR, vec2 = bound++, floatType, 2});
        if (ivec2 == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_TYPE_VECTOR, ivec2 = bound++, intType, 2});
        if (intZero == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_CONSTANT, intType, intZero = bound++, 0});
        if (floatOne == 0) declared.addElements(declared.size(), new int[] {4 << 16 | OP_CONSTANT, floatType, floatOne = bound++, Float.floatToRawIntBits(1f)});

        final IntArrayList out = new IntArrayList(w.length + 16);
        for (int i = 0; i < 5; i++) out.add(w[i]);
        boolean named = false;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (op == OP_CAPABILITY) {
                if (w[i + 1] == CAP_IMAGE_RECT || w[i + 1] == CAP_SAMPLED_RECT) {
                    i += wc;
                    continue;
                }
                if (!hasImageQuery) {
                    out.addElements(out.size(), new int[] {2 << 16 | OP_CAPABILITY, CAP_IMAGE_QUERY});
                    hasImageQuery = true;
                }
            }
            if (!named && !precedesAnnotations(op)) {
                nameImages(out, rectImages, RECT_IMAGE_NAME);
                named = true;
            }
            if (i == first) out.addAll(declared);
            if (i >= first && wc >= 3 && types.contains(w[i + 1])) typeOf.put(w[i + 2], w[i + 1]);
            final int at = out.size();
            for (int j = 0; j < wc; j++) out.add(w[i + j]);
            if (op == OP_TYPE_IMAGE && w[i + 3] == DIM_RECT) {
                out.set(at + 3, DIM_2D);
            } else if (i >= first && op >= OP_IMAGE_SAMPLE_FIRST && op <= OP_IMAGE_DREF_GATHER && op != OP_IMAGE_FETCH && rectSampled.containsKey(typeOf.get(w[i + 3]))) {
                final int coordType = typeOf.get(w[i + 4]), comps = vectorSize.get(coordType);
                if (comps < 2) throw new IllegalArgumentException("rectangle image coordinate of unknown type at " + i);
                final int[] scale = new int[20];
                final int image = bound++, size = bound++, floatSize = bound++;
                int k = 0;
                scale[k++] = 4 << 16 | OP_IMAGE;
                scale[k++] = rectSampled.get(typeOf.get(w[i + 3]));
                scale[k++] = image;
                scale[k++] = w[i + 3];
                scale[k++] = 5 << 16 | OP_IMAGE_QUERY_SIZE_LOD;
                scale[k++] = ivec2;
                scale[k++] = size;
                scale[k++] = image;
                scale[k++] = intZero;
                scale[k++] = 4 << 16 | OP_CONVERT_S_TO_F;
                scale[k++] = vec2;
                scale[k++] = floatSize;
                scale[k++] = size;
                int divisor = floatSize;
                if (comps != 2) {
                    // A projective coordinate's last components keep their scale; only x and y are in texels.
                    divisor = bound++;
                    k = appendConstruct(scale, k, coordType, divisor, floatSize, floatOne, comps);
                }
                final int coord = bound++;
                final int[] divide = {5 << 16 | OP_F_DIV, coordType, coord, w[i + 4], divisor};
                out.size(at);
                out.addElements(out.size(), scale, 0, k);
                out.addElements(out.size(), divide);
                for (int j = 0; j < wc; j++) out.add(j == 4 ? coord : w[i + j]);
            } else if (i >= first && op == OP_IMAGE_QUERY_SIZE && rectImages.contains(typeOf.get(w[i + 3])) && !storageRectImages.contains(typeOf.get(w[i + 3]))) {
                out.set(at, 5 << 16 | OP_IMAGE_QUERY_SIZE_LOD);
                out.add(intZero);
            } else if (i >= first && op == OP_IMAGE_FETCH && rectImages.contains(typeOf.get(w[i + 3]))) {
                out.set(at, (wc == 5 ? wc + 2 : wc + 1) << 16 | OP_IMAGE_FETCH);
                out.size(at + 5);
                out.add((wc == 5 ? 0 : w[i + 5]) | IMAGE_OPERAND_LOD);
                out.add(intZero);
                for (int j = 6; j < wc; j++) out.add(w[i + j]);
            }
            i += wc;
        }
        out.set(3, bound);
        return toBytes(out);
    }

    private static int appendConstruct(int[] code, int k, int type, int id, int xy, int one, int comps) {
        code[k++] = (4 + comps - 2) << 16 | OP_COMPOSITE_CONSTRUCT;
        code[k++] = type;
        code[k++] = id;
        code[k++] = xy;
        for (int c = 2; c < comps; c++) code[k++] = one;
        return k;
    }

    /** Names images whose dimension a lowering rewrites, so reflection can still tell what they were. */
    private static void nameImages(IntArrayList out, IntOpenHashSet images, String imageName) {
        final int[] name = encodeName(imageName);
        for (int image : images) {
            out.add((2 + name.length) << 16 | OP_NAME);
            out.add(image);
            out.addElements(out.size(), name);
        }
    }

    private static boolean precedesAnnotations(int op) {
        return switch (op) {
            case OP_CAPABILITY, OP_ENTRY_POINT, OP_EXECUTION_MODE, OP_NAME, OP_MEMBER_NAME, 2, 3, 4, 7, 10, 11, 14 -> true;
            default -> false;
        };
    }

    private static int[] encodeName(String name) {
        final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        final int[] words = new int[bytes.length / 4 + 1];
        for (int i = 0; i < bytes.length; i++) words[i / 4] |= (bytes[i] & 0xFF) << (i % 4 * 8);
        return words;
    }

    public static byte[] zeroInitLocals(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;

        final Int2ObjectOpenHashMap<int[]> types = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap nulls = new Int2IntOpenHashMap();
        nulls.defaultReturnValue(0);
        final IntArrayList pending = new IntArrayList();
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op >= OP_TYPE_VOID && op <= OP_TYPE_POINTER) {
                final int[] t = new int[wc - 1];
                t[0] = op;
                System.arraycopy(w, i + 2, t, 1, wc - 2);
                types.put(w[i + 1], t);
            } else if (op == OP_CONSTANT_NULL) {
                nulls.putIfAbsent(w[i + 1], w[i + 2]);
            } else if (op == OP_VARIABLE && wc == 4 && w[i + 3] == SC_FUNCTION) {
                pending.add(i);
            }
            i += wc;
        }
        if (pending.isEmpty()) return spirv;
        // Function-scope variables (storage class 7) that may be read before any store get an OpConstantNull initializer.
        final IntOpenHashSet storedFirst = storedBeforeUse(w);

        int bound = w[3];
        final IntArrayList newNulls = new IntArrayList();
        final Int2IntOpenHashMap initFor = new Int2IntOpenHashMap();
        for (int k = 0; k < pending.size(); k++) {
            final int at = pending.getInt(k);
            if (storedFirst.contains(at)) continue;
            final int[] ptr = types.get(w[at + 1]);
            if (ptr == null || ptr[0] != OP_TYPE_POINTER) continue;
            final int pointee = ptr[2];
            if (!nullable(types, pointee)) continue;
            int id = nulls.get(pointee);
            if (id == 0) {
                id = bound++;
                nulls.put(pointee, id);
                newNulls.add(pointee);
                newNulls.add(id);
            }
            initFor.put(at, id);
        }
        if (initFor.isEmpty()) return spirv;

        final IntArrayList out = new IntArrayList(w.length + initFor.size() + newNulls.size() * 3 / 2);
        for (int i = 0; i < 5; i++) out.add(w[i]);
        out.set(3, bound);
        boolean emitted = false;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            // New null constants must be declared before the first function; the header's id bound was raised for them.
            if (op == OP_FUNCTION && !emitted) {
                for (int n = 0; n < newNulls.size(); n += 2) {
                    out.add(3 << 16 | OP_CONSTANT_NULL);
                    out.add(newNulls.getInt(n));
                    out.add(newNulls.getInt(n + 1));
                }
                emitted = true;
            }
            if (initFor.containsKey(i)) {
                out.add(5 << 16 | OP_VARIABLE);
                out.add(w[i + 1]);
                out.add(w[i + 2]);
                out.add(w[i + 3]);
                out.add(initFor.get(i));
            } else {
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            }
            i += wc;
        }
        return toBytes(out);
    }

    private static final int SC_PRIVATE = 6, OP_EMIT_VERTEX = 218, OP_EMIT_STREAM_VERTEX = 220, EXECUTION_MODEL_GEOMETRY = 3;

    /**
     * A geometry shader's outputs, held in private variables and copied out before each vertex is emitted. GLSL leaves an output undefined
     * after EmitVertex, but GL drivers keep it, and shaders (the Khronos suite's among them) set a colour once for every vertex they emit;
     * NVIDIA's Vulkan driver does not keep it. Built-ins are left alone, and so is a shader that hands an output to a function by pointer.
     */
    public static byte[] holdGeometryOutputs(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;
        boolean geometry = false;
        final IntOpenHashSet builtins = new IntOpenHashSet(), builtinStructs = new IntOpenHashSet();
        final Int2ObjectOpenHashMap<int[]> pointers = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap privatePointer = new Int2IntOpenHashMap();
        final Int2IntOpenHashMap outputs = new Int2IntOpenHashMap();
        int firstFunction = -1;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op == OP_ENTRY_POINT && w[i + 1] == EXECUTION_MODEL_GEOMETRY) geometry = true;
            else if (op == OP_DECORATE && w[i + 2] == DEC_BUILTIN) builtins.add(w[i + 1]);
            else if (op == OP_MEMBER_DECORATE && w[i + 3] == DEC_BUILTIN) builtinStructs.add(w[i + 1]);
            else if (op == OP_TYPE_POINTER) {
                pointers.put(w[i + 1], new int[] {w[i + 2], w[i + 3]});
                if (w[i + 2] == SC_PRIVATE) privatePointer.putIfAbsent(w[i + 3], w[i + 1]);
            } else if (op == OP_VARIABLE && w[i + 3] == SC_OUTPUT && firstFunction < 0) {
                outputs.put(w[i + 2], w[i + 1]);
            } else if (op == OP_FUNCTION && firstFunction < 0) firstFunction = i;
            i += wc;
        }
        if (!geometry || firstFunction < 0) return spirv;
        outputs.keySet().removeIf(v -> {
            final int[] ptr = pointers.get(outputs.get(v));
            return builtins.contains(v) || ptr == null || builtinStructs.contains(ptr[1]);
        });
        if (outputs.isEmpty()) return spirv;

        int bound = w[3];
        final IntArrayList declarations = new IntArrayList();
        // Every output pointer type a held variable or a chain into it uses gets its private twin.
        final Int2IntOpenHashMap held = new Int2IntOpenHashMap(), heldType = new Int2IntOpenHashMap();
        for (int v : outputs.keySet()) {
            final int pointee = pointers.get(outputs.get(v))[1];
            int pp = privatePointer.get(pointee);
            if (pp == 0) {
                pp = bound++;
                privatePointer.put(pointee, pp);
                declarations.addElements(declarations.size(), new int[] {4 << 16 | OP_TYPE_POINTER, pp, SC_PRIVATE, pointee});
            }
            final int copy = bound++;
            declarations.addElements(declarations.size(), new int[] {4 << 16 | OP_VARIABLE, pp, copy, SC_PRIVATE});
            held.put(v, copy);
            heldType.put(v, pointee);
        }
        // Chains into a held output, and what they chain to, are found in order, as each is defined before it is used.
        final IntOpenHashSet derived = new IntOpenHashSet(held.keySet());
        for (int i = firstFunction; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if ((isPointerChain(op) || op == OP_COPY_OBJECT) && derived.contains(w[i + 3])) {
                if (op == OP_PTR_ACCESS_CHAIN) return spirv;
                derived.add(w[i + 2]);
                final int[] ptr = pointers.get(w[i + 1]);
                if (ptr == null) return spirv;
                if (privatePointer.get(ptr[1]) == 0) {
                    final int pp = bound++;
                    privatePointer.put(ptr[1], pp);
                    declarations.addElements(declarations.size(), new int[] {4 << 16 | OP_TYPE_POINTER, pp, SC_PRIVATE, ptr[1]});
                }
            } else if (op == OP_FUNCTION_CALL) {
                for (int k = 4; k < wc; k++) if (derived.contains(w[i + k])) return spirv;
            }
            i += wc;
        }

        final boolean interfaceListsPrivates = (w[1] & 0xFFFF00) >= 0x010400;
        final IntArrayList out = new IntArrayList(w.length + declarations.size() + 64);
        for (int i = 0; i < 5; i++) out.add(w[i]);
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (i == firstFunction) out.addElements(out.size(), declarations.toIntArray());
            if (op == OP_ENTRY_POINT && interfaceListsPrivates) {
                final int start = out.size();
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
                for (int copy : held.values()) out.add(copy);
                out.set(start, (out.size() - start) << 16 | OP_ENTRY_POINT);
            } else if (i < firstFunction) {
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            } else if (op == OP_EMIT_VERTEX || op == OP_EMIT_STREAM_VERTEX) {
                for (int v : held.keySet()) {
                    final int value = bound++;
                    out.addElements(out.size(), new int[] {4 << 16 | OP_LOAD, heldType.get(v), value, held.get(v)});
                    out.addElements(out.size(), new int[] {3 << 16 | OP_STORE, v, value});
                }
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            } else {
                final int at = out.size();
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
                if ((isPointerChain(op) || op == OP_COPY_OBJECT) && derived.contains(w[i + 2])) {
                    out.set(at + 1, privatePointer.get(pointers.get(w[i + 1])[1]));
                    if (held.containsKey(w[i + 3])) out.set(at + 3, held.get(w[i + 3]));
                } else if (op == OP_LOAD && held.containsKey(w[i + 3])) {
                    out.set(at + 3, held.get(w[i + 3]));
                } else if (op == OP_STORE && held.containsKey(w[i + 1])) {
                    out.set(at + 1, held.get(w[i + 1]));
                } else if (op == OP_COPY_MEMORY) {
                    if (held.containsKey(w[i + 1])) out.set(at + 1, held.get(w[i + 1]));
                    if (held.containsKey(w[i + 2])) out.set(at + 2, held.get(w[i + 2]));
                }
            }
            i += wc;
        }
        out.set(3, bound);
        return toBytes(out);
    }

    /** The word offsets of function variables whose first mention in the entry block is a store, so they can never be read undefined. */
    static IntOpenHashSet storedBeforeUse(int[] w) {
        final IntOpenHashSet out = new IntOpenHashSet();
        final Int2IntOpenHashMap undecided = new Int2IntOpenHashMap();
        boolean inEntry = false;
        int labels = 0;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return new IntOpenHashSet();
            if (op == OP_FUNCTION) {
                labels = 0;
                undecided.clear();
            } else if (op == OP_LABEL) {
                inEntry = ++labels == 1;
            } else if (op == OP_FUNCTION_END) {
                inEntry = false;
            } else if (inEntry) {
                if (op == OP_VARIABLE && w[i + 3] == SC_FUNCTION) {
                    undecided.put(w[i + 2], i);
                } else if (!undecided.isEmpty()) {
                    if (op == OP_STORE && undecided.containsKey(w[i + 1])) {
                        out.add(undecided.remove(w[i + 1]));
                    }
                    for (int j = op == OP_STORE ? 2 : 1; j < wc && !undecided.isEmpty(); j++) undecided.remove(w[i + j]);
                }
            }
            i += wc;
        }
        return out;
    }

    public static byte[] stripUnusedOpaque(byte[] spirv) {
        final int[] w = readWords(spirv);
        if (w.length < 5 || w[0] != MAGIC) return spirv;

        final IntOpenHashSet candidates = new IntOpenHashSet();
        int firstFunction = -1;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return spirv;
            if (op == OP_FUNCTION) {
                firstFunction = i;
                break;
            }
            if (op == OP_VARIABLE && w[i + 3] == SC_UNIFORM_CONSTANT) candidates.add(w[i + 2]);
            i += wc;
        }
        if (candidates.isEmpty() || firstFunction < 0) return spirv;

        // A uniform-constant variable that no instruction after the first function mentions is unused: drop it and its entry-point interface slot.
        for (int i = firstFunction; i < w.length; ) {
            final int wc = w[i] >>> 16;
            if (wc == 0) return spirv;
            for (int j = 1; j < wc; j++) candidates.remove(w[i + j]);
            if (candidates.isEmpty()) return spirv;
            i += wc;
        }

        final IntArrayList out = new IntArrayList(w.length);
        for (int i = 0; i < 5; i++) out.add(w[i]);
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (i >= firstFunction) {
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            } else if (op == OP_VARIABLE && candidates.contains(w[i + 2])
                || (op == OP_NAME || op == OP_DECORATE || op == OP_DECORATE_STRING) && candidates.contains(w[i + 1])) {
                // stripped along with the variable
            } else if (op == OP_ENTRY_POINT) {
                int nameEnd = i + 3;
                while (nameEnd < i + wc && (w[nameEnd] >>> 24) != 0) nameEnd++;
                nameEnd++;
                final int start = out.size();
                out.add(0);
                for (int j = i + 1; j < nameEnd; j++) out.add(w[j]);
                for (int j = nameEnd; j < i + wc; j++) if (!candidates.contains(w[j])) out.add(w[j]);
                out.set(start, (out.size() - start) << 16 | OP_ENTRY_POINT);
            } else {
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            }
            i += wc;
        }
        return toBytes(out);
    }

    public Set<String> fetchOnlySamplers() {
        final Int2IntOpenHashMap candidates = new Int2IntOpenHashMap();
        for (Variable v : variables) {
            if (v.storageClass != SC_UNIFORM_CONSTANT) continue;
            final Type t = pointee(v);
            if (t == null || t.op != OP_TYPE_SAMPLED_IMAGE) continue;
            candidates.put(v.id, 0);
        }
        if (candidates.isEmpty()) return Set.of();

        final int start = firstFunction(words);
        final Int2IntOpenHashMap loads = new Int2IntOpenHashMap();
        for (int i = start; i < words.length; i += words[i] >>> 16) {
            if ((words[i] & 0xFFFF) == OP_LOAD && candidates.containsKey(words[i + 3])) loads.put(words[i + 2], words[i + 3]);
        }
        // A sampled image stays a candidate only while every use of its loads is an OpImage (texelFetch), which needs no sampler.
        for (int i = start; i < words.length; ) {
            final int wc = words[i] >>> 16, op = words[i] & 0xFFFF;
            for (int j = 1; j < wc; j++) {
                final int id = words[i + j];
                if (candidates.containsKey(id) && !(op == OP_LOAD && j == 3)) candidates.remove(id);
                if (loads.containsKey(id) && !(op == OP_LOAD && j == 2) && !(op == OP_IMAGE && j == 3)) candidates.remove(loads.get(id));
            }
            i += wc;
        }
        final Set<String> out = new HashSet<>();
        for (int id : candidates.keySet()) out.add(name(id));
        return out;
    }

    public byte[] demoteSamplers(Set<String> names) {
        final Int2IntOpenHashMap imageOf = new Int2IntOpenHashMap();
        for (Variable v : variables) {
            if (v.storageClass == SC_UNIFORM_CONSTANT && names.contains(name(v.id)) && pointee(v).op == OP_TYPE_SAMPLED_IMAGE) {
                imageOf.put(v.id, pointee(v).operands[0]);
            }
        }
        if (imageOf.isEmpty()) return null;

        int bound = words[3];
        final Int2IntOpenHashMap pointerTo = new Int2IntOpenHashMap();
        for (Type t : types.values()) {
            if (t.op == OP_TYPE_POINTER && t.operands[0] == SC_UNIFORM_CONSTANT) pointerTo.putIfAbsent(t.operands[1], t.id);
        }
        final Int2ObjectOpenHashMap<IntArrayList> insertAfter = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap newPointer = new Int2IntOpenHashMap();
        for (Variable v : variables) {
            if (!imageOf.containsKey(v.id)) continue;
            final int image = imageOf.get(v.id);
            int ptr = pointerTo.getOrDefault(image, 0);
            if (ptr == 0) {
                ptr = bound++;
                pointerTo.put(image, ptr);
                final IntArrayList decl = insertAfter.computeIfAbsent(v.typeId, k -> new IntArrayList());
                decl.add(4 << 16 | OP_TYPE_POINTER);
                decl.add(ptr);
                decl.add(SC_UNIFORM_CONSTANT);
                decl.add(image);
            }
            newPointer.put(v.id, ptr);
        }

        // The variable becomes a plain image pointer: its OpLoad now yields the image, so the OpImage that followed turns into a copy.
        final int start = firstFunction(words);
        final Int2IntOpenHashMap loads = new Int2IntOpenHashMap();
        final IntArrayList out = new IntArrayList(words.length + insertAfter.size() * 4);
        for (int i = 0; i < 5; i++) out.add(words[i]);
        out.set(3, bound);
        for (int i = 5; i < words.length; ) {
            final int wc = words[i] >>> 16, op = words[i] & 0xFFFF;
            final int at = out.size();
            for (int j = 0; j < wc; j++) out.add(words[i + j]);
            if (i < start) {
                if (op == OP_TYPE_POINTER && insertAfter.containsKey(words[i + 1])) out.addAll(insertAfter.get(words[i + 1]));
                if (op == OP_VARIABLE && newPointer.containsKey(words[i + 2])) out.set(at + 1, newPointer.get(words[i + 2]));
            } else if (op == OP_LOAD && imageOf.containsKey(words[i + 3])) {
                out.set(at + 1, imageOf.get(words[i + 3]));
                loads.put(words[i + 2], 0);
            } else if (op == OP_IMAGE && loads.containsKey(words[i + 3])) {
                out.set(at, 4 << 16 | OP_COPY_OBJECT);
            }
            i += wc;
        }
        return toBytes(out);
    }

    public static final int OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66, OP_PTR_ACCESS_CHAIN = 67;

    public byte[] toPushConstant(int varId, int offsetShift) {
        Variable var = null;
        for (Variable v : variables) if (v.id == varId && v.storageClass == SC_UNIFORM) var = v;
        if (var == null) return null;
        final int blockType = pointee(var).id;
        final int start = firstFunction(words);

        final IntOpenHashSet rooted = new IntOpenHashSet();
        rooted.add(varId);
        // Every access chain rooted at the block needs its pointer type moved to the PushConstant storage class as well.
        final Int2IntOpenHashMap chainPointee = new Int2IntOpenHashMap();
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = start; i < words.length; i += words[i] >>> 16) {
                final int op = words[i] & 0xFFFF;
                final boolean chain = isPointerChain(op) || op == OP_COPY_OBJECT;
                if (!chain || !rooted.contains(words[i + 3]) || rooted.contains(words[i + 2])) continue;
                final Type rt = types.get(words[i + 1]);
                if (rt == null || rt.op != OP_TYPE_POINTER) return null;
                rooted.add(words[i + 2]);
                chainPointee.put(words[i + 2], rt.operands[1]);
                grew = true;
            }
        }

        int bound = words[3];
        final Int2IntOpenHashMap pushPointer = new Int2IntOpenHashMap();
        final IntArrayList decls = new IntArrayList();
        final int blockPointer = bound++;
        pushPointer.put(blockType, blockPointer);
        decls.addElements(decls.size(), new int[] {4 << 16 | OP_TYPE_POINTER, blockPointer, SC_PUSH_CONSTANT, blockType});
        for (int pointeeType : chainPointee.values()) {
            if (pushPointer.containsKey(pointeeType)) continue;
            final int ptr = bound++;
            pushPointer.put(pointeeType, ptr);
            decls.addElements(decls.size(), new int[] {4 << 16 | OP_TYPE_POINTER, ptr, SC_PUSH_CONSTANT, pointeeType});
        }

        final IntArrayList out = new IntArrayList(words.length + decls.size() + 4);
        for (int i = 0; i < 5; i++) out.add(words[i]);
        out.set(3, bound);
        for (int i = 5; i < words.length; ) {
            final int wc = words[i] >>> 16, op = words[i] & 0xFFFF;
            if (op == OP_DECORATE && words[i + 1] == varId && (words[i + 2] == DEC_BINDING || words[i + 2] == DEC_DESCRIPTOR_SET)) {
                i += wc;
                continue;
            }
            final int at = out.size();
            for (int j = 0; j < wc; j++) out.add(words[i + j]);
            if (op == OP_MEMBER_DECORATE && words[i + 1] == blockType && words[i + 3] == DEC_OFFSET) {
                out.set(at + 4, words[i + 4] + offsetShift);
            } else if (op == OP_TYPE_POINTER && words[i + 1] == var.typeId) {
                out.addAll(decls);
            } else if (op == OP_VARIABLE && words[i + 2] == varId) {
                out.set(at + 1, blockPointer);
                out.set(at + 3, SC_PUSH_CONSTANT);
            } else if (i >= start && (isPointerChain(op) || op == OP_COPY_OBJECT) && chainPointee.containsKey(words[i + 2])) {
                out.set(at + 1, pushPointer.get(chainPointee.get(words[i + 2])));
            }
            i += wc;
        }
        return toBytes(out);
    }

    public static int[] specialize(int[] words, int specId, int value) {
        // Patches the OpSpecConstant (opcode 50) carrying this SpecId in place, so no specialization info is needed at pipeline creation.
        final int[] w = words.clone();
        int target = -1;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            final int wc = w[i] >>> 16;
            if (wc == 0) break;
            final int op = w[i] & 0xFFFF;
            if (op == OP_DECORATE && wc >= 4 && w[i + 2] == DEC_SPEC_ID && w[i + 3] == specId) target = w[i + 1];
            else if (op == OP_SPEC_CONSTANT && target >= 0 && w[i + 2] == target) {
                w[i + 3] = value;
                return w;
            } else if (op == OP_FUNCTION) break;
        }
        return w;
    }

    public static boolean hasSpecId(int[] w, int id) {
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            final int wc = w[i] >>> 16;
            if (wc == 0) return false;
            if ((w[i] & 0xFFFF) == OP_DECORATE && wc >= 4 && w[i + 2] == DEC_SPEC_ID && w[i + 3] == id) return true;
            if ((w[i] & 0xFFFF) == OP_FUNCTION) return false;
        }
        return false;
    }

    public static int firstFunction(int[] w) {
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == OP_FUNCTION) return i;
        }
        return w.length;
    }

    private static boolean nullable(Int2ObjectOpenHashMap<int[]> types, int id) {
        final int[] t = types.get(id);
        if (t == null) return false;
        return switch (t[0]) {
            case OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT -> true;
            case OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_ARRAY -> nullable(types, t[1]);
            case OP_TYPE_STRUCT -> {
                for (int m = 1; m < t.length; m++) if (!nullable(types, t[m])) yield false;
                yield true;
            }
            default -> false;
        };
    }

    private static byte[] toBytes(IntArrayList words) {
        final ByteBuffer bb = ByteBuffer.allocate(words.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asIntBuffer().put(words.toIntArray());
        return bb.array();
    }

    private static int[] readWords(byte[] spirv) {
        final IntBuffer ib = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final int[] w = new int[ib.remaining()];
        ib.get(w);
        return w;
    }

    private static boolean isPointerChain(int op) {
        return op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN || op == OP_PTR_ACCESS_CHAIN;
    }
}
