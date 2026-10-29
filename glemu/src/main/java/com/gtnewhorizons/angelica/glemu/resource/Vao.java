package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class Vao {
    public static final int MAX_ATTRIBS = 16;
    public static final int MAX_BINDINGS = 16;

    public final int id;
    public int elementBuffer;

    public final boolean[] enabled = new boolean[MAX_ATTRIBS];
    public final int[] size = new int[MAX_ATTRIBS];
    public final int[] type = new int[MAX_ATTRIBS];
    public final boolean[] normalized = new boolean[MAX_ATTRIBS];
    public final boolean[] integer = new boolean[MAX_ATTRIBS];
    public final int[] relativeOffset = new int[MAX_ATTRIBS];
    public final int[] bindingOf = new int[MAX_ATTRIBS];

    public final int[] bindingBuffer = new int[MAX_BINDINGS];
    public final long[] bindingOffset = new long[MAX_BINDINGS];
    public final int[] bindingStride = new int[MAX_BINDINGS];
    public final int[] bindingDivisor = new int[MAX_BINDINGS];

    public int formatGen;
    public int bufferGen;

    private static final int SIG_FIELDS = 6;
    private static final Map<Signature, Integer> LAYOUTS = new HashMap<>();
    private static final Signature PROBE = new Signature(new int[MAX_ATTRIBS * SIG_FIELDS]);
    private int layoutId = -1, layoutIdGen;

    public Vao(int id) {
        this.id = id;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            size[i] = 4;
            type[i] = GL11.GL_FLOAT;
            bindingOf[i] = i;
        }
    }

    public int stride(int attrib) {
        final int b = bindingOf[attrib];
        final int s = bindingStride[b];
        return s != 0 ? s : packedSize(attrib);
    }

    public int packedSize(int attrib) {
        return AttribSizes.attribBytes(size[attrib], type[attrib]);
    }

    public int layoutId() {
        if (layoutId >= 0 && layoutIdGen == formatGen) return layoutId;
        synchronized (LAYOUTS) {
            final int[] sig = PROBE.values;
            Arrays.fill(sig, 0);
            for (int i = 0; i < MAX_ATTRIBS; i++) {
                if (!enabled[i] || bindingBuffer[bindingOf[i]] == 0) continue;
                final int o = i * SIG_FIELDS;
                sig[o] = 1;
                sig[o + 1] = size[i];
                sig[o + 2] = type[i];
                sig[o + 3] = (normalized[i] ? 1 : 0) | (integer[i] ? 2 : 0);
                sig[o + 4] = stride(i);
                sig[o + 5] = bindingDivisor[bindingOf[i]];
            }
            final Integer known = LAYOUTS.get(PROBE);
            if (known != null) {
                layoutId = known;
            } else {
                layoutId = LAYOUTS.size();
                LAYOUTS.put(new Signature(sig.clone()), layoutId);
            }
        }
        layoutIdGen = formatGen;
        return layoutId;
    }

    private record Signature(int[] values) {
        @Override public boolean equals(Object o) {
            return o instanceof Signature s && Arrays.equals(values, s.values);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(values);
        }
    }
}
