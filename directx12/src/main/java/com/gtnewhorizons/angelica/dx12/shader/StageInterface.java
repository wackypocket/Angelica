package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.DEC_FLAT;
import static com.gtnewhorizons.angelica.glemu.shader.Spirv.DEC_LOCATION;

final class StageInterface {
    private StageInterface() {}

    private static final int OP_ENTRY_POINT = 15, OP_TYPE_POINTER = 32, OP_FUNCTION = 54, OP_VARIABLE = 59;
    private static final int OP_ACCESS_CHAIN = 65, OP_IN_BOUNDS_ACCESS_CHAIN = 66, OP_PTR_ACCESS_CHAIN = 67, OP_COPY_OBJECT = 83;
    private static final int OP_DECORATE = 71, OP_MEMBER_DECORATE = 72, OP_DECORATION_GROUP = 73, OP_DECORATE_STRING = 5632;
    private static final int SC_PRIVATE = 6;
    private static final int[] INTERPOLATION = {13, DEC_FLAT, 16, 17};

    static int[] alignVertexOutputs(StageReflection vs, StageReflection fs) {
        final IntOpenHashSet read = new IntOpenHashSet();
        for (StageReflection.Io in : fs.inputs) read.add(fs.spirv.decoration(in.varId, DEC_LOCATION, -1));
        final IntOpenHashSet demote = new IntOpenHashSet();
        final Object2IntOpenHashMap<String> outputs = new Object2IntOpenHashMap<>();
        outputs.defaultReturnValue(-1);
        for (StageReflection.Io out : vs.outputs) {
            final int loc = vs.spirv.decoration(out.varId, DEC_LOCATION, -1);
            if (loc >= 0 && !read.contains(loc)) demote.add(out.varId);
            else outputs.put(out.key, out.varId);
        }
        final IntArrayList decorations = new IntArrayList();
        for (StageReflection.Io in : fs.inputs) {
            final int out = outputs.getInt(in.key);
            if (out < 0) continue;
            for (int dec : INTERPOLATION) {
                if (fs.spirv.hasDecoration(in.varId, dec) && !vs.spirv.hasDecoration(out, dec)) decorations.addElements(decorations.size(), new int[] {3 << 16 | OP_DECORATE, out, dec});
            }
        }
        if (demote.isEmpty() && decorations.isEmpty()) return vs.spirv.words;
        return rewrite(vs.spirv, demote, decorations);
    }

    private static boolean chain(int op) {
        return op == OP_ACCESS_CHAIN || op == OP_IN_BOUNDS_ACCESS_CHAIN || op == OP_PTR_ACCESS_CHAIN || op == OP_COPY_OBJECT;
    }

    private static int[] rewrite(Spirv spirv, IntOpenHashSet demote, IntArrayList decorations) {
        final int[] w = spirv.words;
        int start = w.length;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == OP_FUNCTION) {
                start = i;
                break;
            }
        }

        final IntOpenHashSet rooted = new IntOpenHashSet(demote);
        final Int2IntOpenHashMap pointee = new Int2IntOpenHashMap();
        for (Spirv.Variable v : spirv.variables) if (demote.contains(v.id)) pointee.put(v.id, spirv.pointee(v).id);
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = start; i < w.length; i += w[i] >>> 16) {
                if (!chain(w[i] & 0xFFFF) || !rooted.contains(w[i + 3]) || rooted.contains(w[i + 2])) continue;
                pointee.put(w[i + 2], spirv.type(w[i + 1]).operands[1]);
                rooted.add(w[i + 2]);
                grew = true;
            }
        }

        final Int2IntOpenHashMap privatePointer = new Int2IntOpenHashMap();
        for (int i = 5; i < start; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == OP_TYPE_POINTER && w[i + 2] == SC_PRIVATE) privatePointer.putIfAbsent(w[i + 3], w[i + 1]);
        }
        int bound = w[3];
        final IntArrayList globals = new IntArrayList();
        for (int type : pointee.values()) {
            if (privatePointer.containsKey(type)) continue;
            privatePointer.put(type, bound);
            globals.addElements(globals.size(), new int[] {4 << 16 | OP_TYPE_POINTER, bound++, SC_PRIVATE, type});
        }

        final boolean interfaceListsAll = w[1] >= 0x00010400;
        final IntArrayList out = new IntArrayList(w.length + globals.size() + decorations.size());
        for (int i = 0; i < 5; i++) out.add(w[i]);
        out.set(3, bound);
        boolean decorated = false;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            final boolean annotation = op == OP_DECORATE || op == OP_MEMBER_DECORATE || op == OP_DECORATION_GROUP || op == OP_DECORATE_STRING;
            if (annotation && !decorated) {
                out.addAll(decorations);
                decorated = true;
            }
            if ((op == OP_DECORATE || op == OP_DECORATE_STRING) && demote.contains(w[i + 1])) {
                i += wc;
                continue;
            }
            if (op == OP_ENTRY_POINT && !interfaceListsAll) {
                int k = i + 3;
                while ((w[k] & 0xFF000000) != 0 && (w[k] & 0xFF0000) != 0 && (w[k] & 0xFF00) != 0 && (w[k] & 0xFF) != 0) k++;
                k++;
                final int at = out.size();
                for (int j = i; j < k; j++) out.add(w[j]);
                for (int j = k; j < i + wc; j++) if (!demote.contains(w[j])) out.add(w[j]);
                out.set(at, (out.size() - at) << 16 | op);
            } else if (op == OP_VARIABLE && demote.contains(w[i + 2])) {
                final int at = globals.size();
                for (int j = 0; j < wc; j++) globals.add(w[i + j]);
                globals.set(at + 1, privatePointer.get(pointee.get(w[i + 2])));
                globals.set(at + 3, SC_PRIVATE);
            } else if (i == start) {
                out.addAll(globals);
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            } else if (i > start && chain(op) && rooted.contains(w[i + 2])) {
                final int at = out.size();
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
                out.set(at + 1, privatePointer.get(pointee.get(w[i + 2])));
            } else {
                for (int j = 0; j < wc; j++) out.add(w[i + j]);
            }
            i += wc;
        }
        return out.toIntArray();
    }
}
