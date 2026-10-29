package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;

import java.util.ArrayList;
import java.util.List;

public final class Program extends ProgramBase<Program> {

    public final Bytecode[] code = new Bytecode[STAGES];
    public final long[] functions = new long[STAGES];
    public final String[] entryPoints = new String[STAGES];
    public final int[] sizeBuffer = {-1, -1, -1, -1, -1};
    public int vertexBufferBase;
    public long computePipeline;
    public com.gtnewhorizons.angelica.dx12.pipeline.RootSignatures.Layout rootLayout;
    public final int[] srvCount = new int[STAGES], samplerCount = new int[STAGES], cbvCount = new int[STAGES], uavCount = new int[STAGES];
    public boolean writesStorage;
    public int[] fragmentWords;
    public java.util.List<HlslCompiler.Binding> fragmentBindings;
    public int[] vertexWords;
    public java.util.List<HlslCompiler.Binding> vertexBindings;
    public final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Bytecode> vertexVariants = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    public final List<Binding> bindings = new ArrayList<>();

    public Program(int id) {
        super(id);
    }

    public enum BindingKind { DEFAULT_BLOCK, UNIFORM_BUFFER, STORAGE_BUFFER, COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER, STORAGE_IMAGE }

    public static final class Binding {
        public final int binding;
        public final BindingKind kind;
        public final int descriptorType;
        public final int count;
        public int stageFlags;
        public final int ref;
        public final boolean dynamic;
        public final int dim;
        public final boolean arrayed;
        public final boolean shadow;
        public final int glType;
        public int[] bufferReg = noSlots(), textureReg = noSlots(), samplerReg = noSlots();
        public boolean[] uav = new boolean[STAGES];

        private static int[] noSlots() {
            final int[] a = new int[STAGES];
            java.util.Arrays.fill(a, -1);
            return a;
        }

        public Binding(int binding, BindingKind kind, int descriptorType, int count, int stageFlags, int ref, boolean dynamic, int dim, boolean arrayed, boolean shadow, int glType) {
            this.binding = binding;
            this.kind = kind;
            this.descriptorType = descriptorType;
            this.count = count;
            this.stageFlags = stageFlags;
            this.ref = ref;
            this.dynamic = dynamic;
            this.dim = dim;
            this.arrayed = arrayed;
            this.shadow = shadow;
            this.glType = glType;
        }
    }

    @Override public void resetLinkResults() {
        resetCommonLinkResults();
        for (int i = 0; i < STAGES; i++) {
            if (code[i] != null) code[i].free();
            code[i] = null;
            functions[i] = 0L;
            entryPoints[i] = null;
            sizeBuffer[i] = -1;
        }
        vertexBufferBase = 0;
        computePipeline = 0L;
        writesStorage = false;
        fragmentWords = null;
        fragmentBindings = null;
        vertexWords = null;
        vertexBindings = null;
        for (Bytecode b : vertexVariants.values()) b.free();
        vertexVariants.clear();
        rootLayout = null;
        java.util.Arrays.fill(srvCount, 0);
        java.util.Arrays.fill(samplerCount, 0);
        java.util.Arrays.fill(cbvCount, 0);
        java.util.Arrays.fill(uavCount, 0);
        bindings.clear();
    }

}
