package com.gtnewhorizons.angelica.vulkan.shader;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class Program {
    public static final int STAGES = 5;
    public static final int VS = 0, TCS = 1, TES = 2, GS = 3, FS = 4, CS = 0;

    public final int id;
    public final IntArrayList attached = new IntArrayList();
    public final Object2IntOpenHashMap<String> attribBindings = new Object2IntOpenHashMap<>();
    public final Object2IntOpenHashMap<String> fragDataBindings = new Object2IntOpenHashMap<>();

    public boolean linked;
    public boolean linkAttempted;
    public String log = "";
    public int gen;
    public boolean compute;
    public boolean deletePending;
    public String label;

    public final long[] modules = new long[STAGES];
    public final int[] stageBits = new int[STAGES];
    public long setLayout;
    public long pipelineLayout;
    public int localX = 1, localY = 1, localZ = 1;

    public final List<Uniform> uniforms = new ArrayList<>();
    public final Object2IntOpenHashMap<String> locations = new Object2IntOpenHashMap<>();
    public int[] locationUniform = new int[0];
    public int[] locationElement = new int[0];

    public final StageBlock[] blocks = new StageBlock[STAGES];
    public final List<Binding> bindings = new ArrayList<>();
    public final List<BufferBlock> ubos = new ArrayList<>();
    public final List<BufferBlock> ssbos = new ArrayList<>();
    public final List<Attrib> attribs = new ArrayList<>();
    public int vertexInputMask;
    public final int[] inputKind = new int[16];
    public int dynamicOffsetCount;
    public int colorOutputs = 1;
    public boolean writesDepth;

    // descriptor cache
    public long cachedSet;
    public long cachedEpoch = -1;
    public long[] cachedKey = new long[0];
    public long[] scratchKey = new long[0];

    public Program(int id) {
        this.id = id;
        attribBindings.defaultReturnValue(-1);
        fragDataBindings.defaultReturnValue(-1);
        locations.defaultReturnValue(-1);
    }

    public static final class Uniform {
        public final String name;
        public final int glType;
        public final int arraySize;
        public final int[] offset = filled(-1);
        public final int[] arrayStride = new int[STAGES];
        public final int[] matrixStride = new int[STAGES];
        public final boolean[] rowMajor = new boolean[STAGES];
        public int baseLocation;
        public int bindingIndex = -1;
        public int[] units;

        public Uniform(String name, int glType, int arraySize) {
            this.name = name;
            this.glType = glType;
            this.arraySize = arraySize;
        }

        public boolean isOpaque() {
            return units != null;
        }

        private static int[] filled(int v) {
            final int[] a = new int[STAGES];
            java.util.Arrays.fill(a, v);
            return a;
        }
    }

    public static final class StageBlock {
        public final int stage;
        public final int size;
        public final ByteBuffer data;
        public final long address;
        public boolean dirty = true;
        public int binding;
        // last upload
        public long ringBuffer;
        public long ringOffset;
        public long ringSerial = -1;

        StageBlock(int stage, int size) {
            this.stage = stage;
            this.size = size;
            this.data = MemoryUtil.memCalloc(Math.max(16, size));
            this.address = MemoryUtil.memAddress(data);
        }

        void free() {
            MemoryUtil.memFree(data);
        }
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

    public static final class BufferBlock {
        public final String name;
        public final int size;
        public int glBinding;
        public int bindingIndex;

        public BufferBlock(String name, int size, int glBinding) {
            this.name = name;
            this.size = size;
            this.glBinding = glBinding;
        }
    }

    public static final class Attrib {
        public final String name;
        public final int location;
        public final int glType;
        public final int locations;

        public Attrib(String name, int location, int glType, int locations) {
            this.name = name;
            this.location = location;
            this.glType = glType;
            this.locations = locations;
        }
    }

    public void resetLinkResults() {
        uniforms.clear();
        locations.clear();
        locationUniform = new int[0];
        locationElement = new int[0];
        for (int i = 0; i < STAGES; i++) {
            if (blocks[i] != null) blocks[i].free();
            blocks[i] = null;
            modules[i] = 0L;
            stageBits[i] = 0;
        }
        bindings.clear();
        ubos.clear();
        ssbos.clear();
        attribs.clear();
        vertexInputMask = 0;
        java.util.Arrays.fill(inputKind, 0);
        dynamicOffsetCount = 0;
        colorOutputs = 1;
        writesDepth = false;
        compute = false;
        setLayout = 0L;
        pipelineLayout = 0L;
        cachedSet = 0L;
        cachedEpoch = -1;
        cachedKey = new long[0];
        linked = false;
    }

    StageBlock newBlock(int stage, int size) {
        final StageBlock b = new StageBlock(stage, size);
        blocks[stage] = b;
        return b;
    }
}
