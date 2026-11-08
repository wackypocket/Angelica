package com.gtnewhorizons.angelica.glemu.shader;

import java.util.Arrays;

/** One resource a Metal program binds, before its own slots. */
public abstract class TokenBinding {
    public final int binding;
    public final ProgramBase.BindingKind kind;
    public final int descriptorType;
    public final int count;
    public int stageFlags;
    public final int ref;
    public final int dim;
    public final boolean arrayed;
    public final boolean shadow;
    public final int glType;

    protected TokenBinding(int binding, ProgramBase.BindingKind kind, int descriptorType, int count, int stageFlags, int ref, int dim, boolean arrayed, boolean shadow, int glType) {
        this.binding = binding;
        this.kind = kind;
        this.descriptorType = descriptorType;
        this.count = count;
        this.stageFlags = stageFlags;
        this.ref = ref;
        this.dim = dim;
        this.arrayed = arrayed;
        this.shadow = shadow;
        this.glType = glType;
    }

    protected static int[] noSlots(int stages) {
        final int[] a = new int[stages];
        Arrays.fill(a, -1);
        return a;
    }
}
