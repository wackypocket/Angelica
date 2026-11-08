package com.gtnewhorizons.angelica.glemu.resource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;

public final class Vao {
    public static final int MAX_ATTRIBS = 16;
    public static final int MAX_BINDINGS = 16;

    public final int id;
    /** Whether the name has been bound, which is what makes a generated name an object. */
    public boolean named;
    public int elementBuffer;

    public final boolean[] enabled = new boolean[MAX_ATTRIBS];
    public final int[] size = new int[MAX_ATTRIBS];
    public final int[] type = new int[MAX_ATTRIBS];
    public final boolean[] normalized = new boolean[MAX_ATTRIBS];
    public final boolean[] integer = new boolean[MAX_ATTRIBS];
    public final boolean[] isLong = new boolean[MAX_ATTRIBS];
    public final int[] relativeOffset = new int[MAX_ATTRIBS];
    /** The stride and pointer glVertexAttribPointer was given, which GL keeps apart from those of the binding it set up. */
    public final int[] pointerStride = new int[MAX_ATTRIBS];
    public final long[] pointerOffset = new long[MAX_ATTRIBS];
    public final int[] bindingOf = new int[MAX_ATTRIBS];

    public final int[] bindingBuffer = new int[MAX_BINDINGS];
    public final long[] bindingOffset = new long[MAX_BINDINGS];
    public final int[] bindingStride = new int[MAX_BINDINGS];
    public final int[] bindingDivisor = new int[MAX_BINDINGS];

    /** The buffer an attribute the device cannot fetch is read from once converted, or 0; the fields below describe it. */
    public final int[] convertedBuffer = new int[MAX_ATTRIBS];
    private final int[] convertedStride = new int[MAX_ATTRIBS];
    public int convertedMask, convertedGen = -1;
    public boolean needsConversion;

    public int formatGen;

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
            bindingStride[i] = 16;
        }
    }

    public int stride(int attrib) {
        return bindingStride[bindingOf[attrib]];
    }

    public int readSize(int attrib) {
        return convertedBuffer[attrib] != 0 ? AttribConvert.components(size[attrib], type[attrib]) : size[attrib];
    }

    public int readType(int attrib) {
        if (convertedBuffer[attrib] == 0) return type[attrib];
        return !integer[attrib] ? GL11.GL_FLOAT : AttribConvert.unsigned(type[attrib]) ? GL11.GL_UNSIGNED_INT : GL11.GL_INT;
    }

    public boolean readNormalized(int attrib) {
        return convertedBuffer[attrib] == 0 && normalized[attrib];
    }

    public int readStride(int attrib) {
        return convertedBuffer[attrib] != 0 ? convertedStride[attrib] : stride(attrib);
    }

    public int readBuffer(int attrib) {
        return convertedBuffer[attrib] != 0 ? convertedBuffer[attrib] : bindingBuffer[bindingOf[attrib]];
    }

    public long readOffset(int attrib) {
        return convertedBuffer[attrib] != 0 ? 0L : bindingOffset[bindingOf[attrib]] + relativeOffset[attrib];
    }

    public int convertedStride(int attrib) {
        return convertedStride[attrib];
    }

    public void readConverted(int attrib, int buffer, int stride) {
        convertedBuffer[attrib] = buffer;
        convertedStride[attrib] = stride;
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
                sig[o + 1] = readSize(i);
                sig[o + 2] = readType(i);
                sig[o + 3] = (readNormalized(i) ? 1 : 0) | (integer[i] ? 2 : 0);
                sig[o + 4] = readStride(i);
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
