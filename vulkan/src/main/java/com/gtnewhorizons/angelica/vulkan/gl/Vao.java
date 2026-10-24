package com.gtnewhorizons.angelica.vulkan.gl;

import org.lwjgl.opengl.GL11;

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
        return VertexFormats.attribBytes(size[attrib], type[attrib]);
    }
}
