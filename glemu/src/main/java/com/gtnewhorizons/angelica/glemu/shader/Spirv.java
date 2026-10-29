package com.gtnewhorizons.angelica.glemu.shader;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

public final class Spirv {
    public static final int MAGIC = 0x07230203;

    static final int OP_NAME = 5, OP_MEMBER_NAME = 6, OP_ENTRY_POINT = 15, OP_EXECUTION_MODE = 16;
    static final int OP_TYPE_VOID = 19, OP_TYPE_BOOL = 20, OP_TYPE_INT = 21, OP_TYPE_FLOAT = 22, OP_TYPE_VECTOR = 23, OP_TYPE_MATRIX = 24;
    static final int OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLER = 26, OP_TYPE_SAMPLED_IMAGE = 27, OP_TYPE_ARRAY = 28, OP_TYPE_RUNTIME_ARRAY = 29;
    static final int OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32;
    static final int OP_CONSTANT = 43, OP_CONSTANT_NULL = 46, OP_SPEC_CONSTANT = 50, OP_FUNCTION = 54;
    static final int OP_FUNCTION_END = 56, OP_LABEL = 248, OP_STORE = 62;
    static final int OP_VARIABLE = 59, OP_LOAD = 61, OP_COPY_OBJECT = 83, OP_IMAGE = 100;
    static final int OP_DECORATE = 71, OP_MEMBER_DECORATE = 72, OP_DECORATE_STRING = 5632;

    public static final int DEC_BLOCK = 2, DEC_BUFFER_BLOCK = 3, DEC_ROW_MAJOR = 4, DEC_COL_MAJOR = 5, DEC_ARRAY_STRIDE = 6, DEC_MATRIX_STRIDE = 7;
    public static final int DEC_BUILTIN = 11, DEC_FLAT = 14, DEC_LOCATION = 30, DEC_COMPONENT = 31, DEC_INDEX = 32, DEC_BINDING = 33, DEC_DESCRIPTOR_SET = 34, DEC_OFFSET = 35;
    public static final int DEC_NON_WRITABLE = 24, DEC_NON_READABLE = 25, DEC_SPEC_ID = 1;

    public static final int SC_UNIFORM_CONSTANT = 0, SC_INPUT = 1, SC_UNIFORM = 2, SC_OUTPUT = 3, SC_PUSH_CONSTANT = 9, SC_STORAGE_BUFFER = 12;

    public final int[] words;

    final Int2ObjectOpenHashMap<String> names = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<String>> memberNames = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Type> types = new Int2ObjectOpenHashMap<>();
    final Int2IntOpenHashMap constants = new Int2IntOpenHashMap();
    final Int2ObjectOpenHashMap<Int2IntOpenHashMap> decorations = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<Int2IntOpenHashMap>> memberDecorations = new Int2ObjectOpenHashMap<>();
    public final List<Variable> variables = new ArrayList<>();
    public int executionModel = -1;
    int idBound;

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
        idBound = words[3];
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
                case OP_MEMBER_NAME -> memberNames.computeIfAbsent(words[i + 1], k -> new Int2ObjectOpenHashMap<>()).put(words[i + 2], string(i + 3, i + wc));
                case OP_ENTRY_POINT -> {
                    if (executionModel < 0) executionModel = words[i + 1];
                }
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
                    if (sc != 7) variables.add(new Variable(words[i + 2], words[i + 1], sc));
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
        return switch (t.op) {
            case OP_TYPE_ARRAY -> Math.max(1, arrayLength(t)) * locationCount(types.get(t.operands[0]));
            case OP_TYPE_MATRIX -> t.operands[1] * locationCount(types.get(t.operands[0]));
            case OP_TYPE_VECTOR -> {
                final Type comp = types.get(t.operands[0]);
                final boolean wide = comp.op == OP_TYPE_FLOAT && comp.operands[0] == 64 || comp.op == OP_TYPE_INT && comp.operands[0] == 64;
                yield wide && t.operands[1] > 2 ? 2 : 1;
            }
            case OP_TYPE_STRUCT -> {
                int n = 0;
                for (int m : t.operands) n += locationCount(types.get(m));
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

    public ByteBuffer toByteBuffer(ByteBuffer dst) {
        dst.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words);
        return dst;
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

    public int componentCount(Type t) {
        final Type e = elementType(t);
        if (e == null) return 0;
        return switch (e.op) {
            case OP_TYPE_VECTOR -> e.operands[1];
            case OP_TYPE_MATRIX -> e.operands[1] * componentCount(types.get(e.operands[0]));
            default -> 1;
        };
    }

    public static byte[] zeroInitLocals(byte[] spirv) {
        final IntBuffer ib = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final int[] w = new int[ib.remaining()];
        ib.get(w);
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
            } else if (op == OP_VARIABLE && wc == 4 && w[i + 3] == 7) {
                pending.add(i);
            }
            i += wc;
        }
        if (pending.isEmpty()) return spirv;
        final it.unimi.dsi.fastutil.ints.IntOpenHashSet storedFirst = storedBeforeUse(w);

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
        final ByteBuffer bb = ByteBuffer.allocate(out.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asIntBuffer().put(out.toIntArray());
        return bb.array();
    }

    static it.unimi.dsi.fastutil.ints.IntOpenHashSet storedBeforeUse(int[] w) {
        final it.unimi.dsi.fastutil.ints.IntOpenHashSet out = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        final Int2IntOpenHashMap undecided = new Int2IntOpenHashMap();
        boolean inEntry = false;
        int labels = 0;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
            if (op == OP_FUNCTION) {
                labels = 0;
                undecided.clear();
            } else if (op == OP_LABEL) {
                inEntry = ++labels == 1;
            } else if (op == OP_FUNCTION_END) {
                inEntry = false;
            } else if (inEntry) {
                if (op == OP_VARIABLE && w[i + 3] == 7) {
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
        final IntBuffer ib = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final int[] w = new int[ib.remaining()];
        ib.get(w);
        if (w.length < 5 || w[0] != MAGIC) return spirv;

        final it.unimi.dsi.fastutil.ints.IntOpenHashSet candidates = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
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
        final ByteBuffer bb = ByteBuffer.allocate(out.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asIntBuffer().put(out.toIntArray());
        return bb.array();
    }

    public java.util.Set<String> fetchOnlySamplers() {
        final Int2IntOpenHashMap candidates = new Int2IntOpenHashMap();
        for (Variable v : variables) {
            if (v.storageClass != SC_UNIFORM_CONSTANT) continue;
            final Type t = pointee(v);
            if (t == null || t.op != OP_TYPE_SAMPLED_IMAGE) continue;
            final Type img = types.get(t.operands[0]);
            if (img != null && img.operands[1] != StageReflection.DIM_BUFFER) candidates.put(v.id, 0);
        }
        if (candidates.isEmpty()) return java.util.Set.of();

        final int start = firstFunction(words);
        final Int2IntOpenHashMap loads = new Int2IntOpenHashMap();
        for (int i = start; i < words.length; i += words[i] >>> 16) {
            if ((words[i] & 0xFFFF) == OP_LOAD && candidates.containsKey(words[i + 3])) loads.put(words[i + 2], words[i + 3]);
        }
        for (int i = start; i < words.length; ) {
            final int wc = words[i] >>> 16, op = words[i] & 0xFFFF;
            for (int j = 1; j < wc; j++) {
                final int id = words[i + j];
                if (candidates.containsKey(id) && !(op == OP_LOAD && j == 3)) candidates.remove(id);
                if (loads.containsKey(id) && !(op == OP_LOAD && j == 2) && !(op == OP_IMAGE && j == 3)) candidates.remove(loads.get(id));
            }
            i += wc;
        }
        final java.util.Set<String> out = new java.util.HashSet<>();
        for (int id : candidates.keySet()) out.add(name(id));
        return out;
    }

    public byte[] demoteSamplers(java.util.Set<String> names) {
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
        final ByteBuffer bb = ByteBuffer.allocate(out.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asIntBuffer().put(out.toIntArray());
        return bb.array();
    }

    static final int OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66, OP_PTR_ACCESS_CHAIN = 67;

    public byte[] toPushConstant(int varId, int offsetShift) {
        Variable var = null;
        for (Variable v : variables) if (v.id == varId && v.storageClass == SC_UNIFORM) var = v;
        if (var == null) return null;
        final int blockType = pointee(var).id;
        final int start = firstFunction(words);

        final it.unimi.dsi.fastutil.ints.IntOpenHashSet rooted = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        rooted.add(varId);
        final Int2IntOpenHashMap chainPointee = new Int2IntOpenHashMap();
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = start; i < words.length; i += words[i] >>> 16) {
                final int op = words[i] & 0xFFFF;
                final boolean chain = op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN || op == OP_PTR_ACCESS_CHAIN || op == OP_COPY_OBJECT;
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

        final boolean interfaceListsAll = words[1] >= 0x00010400;
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
            if (op == OP_ENTRY_POINT && interfaceListsAll) {
                out.add(varId);
                out.set(at, (wc + 1) << 16 | op);
            } else if (op == OP_MEMBER_DECORATE && words[i + 1] == blockType && words[i + 3] == DEC_OFFSET) {
                out.set(at + 4, words[i + 4] + offsetShift);
            } else if (op == OP_TYPE_POINTER && words[i + 1] == var.typeId) {
                out.addAll(decls);
            } else if (op == OP_VARIABLE && words[i + 2] == varId) {
                out.set(at + 1, blockPointer);
                out.set(at + 3, SC_PUSH_CONSTANT);
            } else if (i >= start && (op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN || op == OP_PTR_ACCESS_CHAIN || op == OP_COPY_OBJECT) && chainPointee.containsKey(words[i + 2])) {
                out.set(at + 1, pushPointer.get(chainPointee.get(words[i + 2])));
            }
            i += wc;
        }
        final ByteBuffer bb = ByteBuffer.allocate(out.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asIntBuffer().put(out.toIntArray());
        return bb.array();
    }

    public static int[] specialize(int[] words, int specId, int value) {
        final int[] w = words.clone();
        int target = -1;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            final int wc = w[i] >>> 16;
            if (wc == 0) break;
            final int op = w[i] & 0xFFFF;
            if (op == OP_DECORATE && wc >= 4 && w[i + 2] == DEC_SPEC_ID && w[i + 3] == specId) target = w[i + 1];
            else if (op == 50 && target >= 0 && w[i + 2] == target) {
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

    private static int firstFunction(int[] w) {
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

    public IntArrayList variableIds(int storageClass) {
        final IntArrayList out = new IntArrayList();
        for (Variable v : variables) if (v.storageClass == storageClass) out.add(v.id);
        return out;
    }
}
