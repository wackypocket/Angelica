package com.gtnewhorizons.angelica.d3d12.shader;

import com.gtnewhorizons.angelica.d3d12.pipeline.RootSignatures;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Program extends ProgramBase<Program> {
    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    public final Bytecode[] code = new Bytecode[STAGES];
    @SuppressWarnings("unchecked")
    public final CompletableFuture<Bytecode>[] pendingCode = new CompletableFuture[STAGES];
    public long computePipeline;
    public RootSignatures.Layout rootLayout;
    public final int[] srvCount = new int[STAGES], samplerCount = new int[STAGES], cbvCount = new int[STAGES], uavCount = new int[STAGES];
    public boolean writesStorage;
    public int[] vertexWords;
    public List<HlslCompiler.Binding> vertexBindings;
    public final Long2ObjectOpenHashMap<Bytecode> vertexVariants = new Long2ObjectOpenHashMap<>();
    public final Long2ObjectOpenHashMap<CompletableFuture<Bytecode>> pendingVariants = new Long2ObjectOpenHashMap<>();
    public final LongOpenHashSet failedVariants = new LongOpenHashSet();

    public final List<Binding> bindings = new ArrayList<>();

    public Program(int id) {
        super(id);
    }

    public boolean hasStage(int slot) {
        return stageBits[slot] != 0;
    }

    public Bytecode code(int slot) {
        final Bytecode ready = code[slot];
        if (ready != null) return ready;
        final CompletableFuture<Bytecode> pending = pendingCode[slot];
        if (pending == null) return null;
        pendingCode[slot] = null;
        try {
            final Bytecode b = pending.join();
            code[slot] = b;
            return b;
        } catch (CompletionException | CancellationException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            log = "stage " + slot + " could not be compiled for Direct3D 12: " + cause.getMessage();
            linked = false;
            LOG.error("Program {} {}", id, log);
            return null;
        }
    }

    void discardPending() {
        for (int i = 0; i < STAGES; i++) {
            discard(pendingCode[i]);
            pendingCode[i] = null;
        }
        for (CompletableFuture<Bytecode> pending : pendingVariants.values()) discard(pending);
        pendingVariants.clear();
        failedVariants.clear();
    }

    private static void discard(CompletableFuture<Bytecode> pending) {
        if (pending != null) pending.whenComplete((b, e) -> { if (b != null) b.free(); });
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
            Arrays.fill(a, -1);
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
        }
        discardPending();
        computePipeline = 0L;
        writesStorage = false;
        vertexWords = null;
        vertexBindings = null;
        for (Bytecode b : vertexVariants.values()) b.free();
        vertexVariants.clear();
        rootLayout = null;
        Arrays.fill(srvCount, 0);
        Arrays.fill(samplerCount, 0);
        Arrays.fill(cbvCount, 0);
        Arrays.fill(uavCount, 0);
        bindings.clear();
    }
}
