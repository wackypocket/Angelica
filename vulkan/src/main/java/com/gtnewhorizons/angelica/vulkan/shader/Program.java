package com.gtnewhorizons.angelica.vulkan.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.ArrayList;
import java.util.List;

public final class Program extends ProgramBase<Program> {

    public final long[] modules = new long[STAGES];
    public long setLayout;
    public long pipelineLayout;

    public final List<Binding> bindings = new ArrayList<>();

    public static final int SET_CACHE_WAYS = 2;
    public static final int SET_CACHE_BUCKETS = 32;
    public static final int SET_CACHE_SIZE = SET_CACHE_WAYS * SET_CACHE_BUCKETS;
    public final long[][] cachedKeys = new long[SET_CACHE_SIZE][];
    public final long[] cachedSets = new long[SET_CACHE_SIZE];
    public final long[] cachedEpochs = new long[SET_CACHE_SIZE];
    public final long[] cachedHashes = new long[SET_CACHE_SIZE];
    private final long[] cachedUse = new long[SET_CACHE_SIZE];
    private long useClock;
    public long[] scratchKey = new long[0];

    public Program(int id) {
        super(id);
    }

    public static int bucketStart(long hash) {
        return (int) (HashCommon.mix(hash) & (SET_CACHE_BUCKETS - 1)) * SET_CACHE_WAYS;
    }

    public void touchCached(int slot) {
        cachedUse[slot] = ++useClock;
    }

    public int evictionSlot(int bucketStart, long epoch) {
        int victim = bucketStart;
        for (int i = bucketStart; i < bucketStart + SET_CACHE_WAYS; i++) {
            if (cachedEpochs[i] != epoch) return i;
            if (cachedUse[i] < cachedUse[victim]) victim = i;
        }
        return victim;
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
            modules[i] = 0L;
        }
        bindings.clear();
        setLayout = 0L;
        pipelineLayout = 0L;
        java.util.Arrays.fill(cachedKeys, null);
        java.util.Arrays.fill(cachedSets, 0L);
        java.util.Arrays.fill(cachedUse, 0L);
    }

}
