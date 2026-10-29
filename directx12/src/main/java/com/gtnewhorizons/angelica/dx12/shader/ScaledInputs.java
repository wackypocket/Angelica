package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.DEC_LOCATION;
import static com.gtnewhorizons.angelica.glemu.shader.Spirv.SC_INPUT;

final class ScaledInputs {
    private ScaledInputs() {}

    private static final int OP_TYPE_INT = 21, OP_TYPE_FLOAT = 22, OP_TYPE_VECTOR = 23, OP_TYPE_POINTER = 32;
    private static final int OP_FUNCTION = 54, OP_VARIABLE = 59, OP_LOAD = 61, OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66;
    private static final int OP_CONVERT_S_TO_F = 111, OP_CONVERT_U_TO_F = 112;

    static int[] rewrite(int[] w, int scaledMask, int signedMask) {
        if (scaledMask == 0) return w;
        final Spirv spirv = new Spirv(ByteBuffer.wrap(toBytes(w)).order(ByteOrder.LITTLE_ENDIAN));
        int start = w.length;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == OP_FUNCTION) {
                start = i;
                break;
            }
        }

        final Int2IntOpenHashMap existingInt = new Int2IntOpenHashMap(), existingVector = new Int2IntOpenHashMap(), existingPointer = new Int2IntOpenHashMap();
        existingInt.defaultReturnValue(-1);
        existingVector.defaultReturnValue(-1);
        existingPointer.defaultReturnValue(-1);
        for (int i = 5; i < start; i += w[i] >>> 16) {
            final int op = w[i] & 0xFFFF;
            if (op == OP_TYPE_INT && w[i + 2] == 32) existingInt.putIfAbsent(w[i + 3], w[i + 1]);
            else if (op == OP_TYPE_VECTOR) existingVector.putIfAbsent(w[i + 2] << 3 | w[i + 3], w[i + 1]);
            else if (op == OP_TYPE_POINTER && w[i + 2] == SC_INPUT) existingPointer.putIfAbsent(w[i + 3], w[i + 1]);
        }

        final int[] bound = {w[3]};
        final IntArrayList decls = new IntArrayList();
        final java.util.function.IntUnaryOperator intType = signed -> {
            int id = existingInt.get(signed);
            if (id < 0) {
                id = bound[0]++;
                existingInt.put(signed, id);
                decls.addElements(decls.size(), new int[] {4 << 16 | OP_TYPE_INT, id, 32, signed});
            }
            return id;
        };
        final java.util.function.IntBinaryOperator vectorType = (scalar, n) -> {
            int id = existingVector.get(scalar << 3 | n);
            if (id < 0) {
                id = bound[0]++;
                existingVector.put(scalar << 3 | n, id);
                decls.addElements(decls.size(), new int[] {4 << 16 | OP_TYPE_VECTOR, id, scalar, n});
            }
            return id;
        };
        final java.util.function.IntUnaryOperator inputPointer = type -> {
            int id = existingPointer.get(type);
            if (id < 0) {
                id = bound[0]++;
                existingPointer.put(type, id);
                decls.addElements(decls.size(), new int[] {4 << 16 | OP_TYPE_POINTER, id, SC_INPUT, type});
            }
            return id;
        };

        final Int2IntOpenHashMap varPointer = new Int2IntOpenHashMap();
        final Int2IntOpenHashMap loadType = new Int2IntOpenHashMap();
        final Int2IntOpenHashMap scalarOf = new Int2IntOpenHashMap();
        final IntOpenHashSet signedVars = new IntOpenHashSet();
        for (Spirv.Variable v : spirv.variables) {
            if (v.storageClass != SC_INPUT || spirv.isBuiltinVariable(v)) continue;
            final int loc = spirv.decoration(v.id, DEC_LOCATION, -1);
            if (loc < 0 || loc >= 32 || (scaledMask & 1 << loc) == 0) continue;
            final Spirv.Type t = spirv.pointee(v);
            final boolean vector = t.op == OP_TYPE_VECTOR;
            final Spirv.Type scalar = vector ? spirv.type(t.operands[0]) : t;
            if (scalar == null || scalar.op != OP_TYPE_FLOAT || scalar.operands[0] != 32) continue;
            final int signed = (signedMask & 1 << loc) != 0 ? 1 : 0;
            final int s = intType.applyAsInt(signed);
            final int value = vector ? vectorType.applyAsInt(s, t.operands[1]) : s;
            varPointer.put(v.id, inputPointer.applyAsInt(value));
            loadType.put(v.id, value);
            scalarOf.put(v.id, s);
            if (signed == 1) signedVars.add(v.id);
        }
        if (varPointer.isEmpty()) return w;

        final Int2IntOpenHashMap rootOf = new Int2IntOpenHashMap();
        for (int id : varPointer.keySet()) rootOf.put(id, id);
        rootOf.defaultReturnValue(-1);
        final Int2IntOpenHashMap chainPointer = new Int2IntOpenHashMap();
        for (int i = start; i < w.length; i += w[i] >>> 16) {
            final int op = w[i] & 0xFFFF;
            if ((op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && rootOf.get(w[i + 3]) >= 0) {
                final int root = rootOf.get(w[i + 3]);
                rootOf.put(w[i + 2], root);
                chainPointer.put(w[i + 2], inputPointer.applyAsInt(scalarOf.get(root)));
                loadType.put(w[i + 2], scalarOf.get(root));
            }
        }

        final IntArrayList moved = new IntArrayList();
        final IntArrayList out = new IntArrayList(w.length + decls.size() + 64);
        for (int i = 0; i < 5; i++) out.add(w[i]);
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (op == OP_VARIABLE && varPointer.containsKey(w[i + 2])) {
                final int at = moved.size();
                for (int j = 0; j < wc; j++) moved.add(w[i + j]);
                moved.set(at + 1, varPointer.get(w[i + 2]));
            } else if (i == start) {
                out.addAll(decls);
                out.addAll(moved);
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            } else if (i > start && (op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN) && chainPointer.containsKey(w[i + 2])) {
                final int at = out.size();
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
                out.set(at + 1, chainPointer.get(w[i + 2]));
            } else if (i > start && op == OP_LOAD && rootOf.get(w[i + 3]) >= 0) {
                final int raw = bound[0]++;
                final int at = out.size();
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
                out.set(at + 1, loadType.get(w[i + 3]));
                out.set(at + 2, raw);
                final int convert = signedVars.contains(rootOf.get(w[i + 3])) ? OP_CONVERT_S_TO_F : OP_CONVERT_U_TO_F;
                out.addElements(out.size(), new int[] {4 << 16 | convert, w[i + 1], w[i + 2], raw});
            } else {
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            }
            i += wc;
        }
        out.set(3, bound[0]);
        return out.toIntArray();
    }

    private static byte[] toBytes(int[] w) {
        final ByteBuffer b = ByteBuffer.allocate(w.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(w);
        return b.array();
    }
}
