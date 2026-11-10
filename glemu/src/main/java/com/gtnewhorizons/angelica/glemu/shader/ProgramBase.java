package com.gtnewhorizons.angelica.glemu.shader;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

public abstract class ProgramBase<P extends ProgramBase<P>> {
    /** Added to vertex shaders for glPointSize. It stays out of what the program enumerates, since it never declared it. */
    public static final String POINT_SIZE_UNIFORM = "angelica_pointSize";
    public static final String BASE_VERTEX_UNIFORM = "angelica_draw_baseVertex", BASE_INSTANCE_UNIFORM = "angelica_draw_baseInstance", DRAW_ID_UNIFORM = "angelica_draw_id";

    public static final String POINT_ORIGIN_UNIFORM = "angelica_pointOrigin";
    /** What glClipControl asks of the vertex position: x to turn it upside down, y to leave its depth at zero to one. Zeroes are GL's defaults. */
    public static final String CLIP_UNIFORM = "angelica_clip";
    public static final String NUM_SAMPLES_UNIFORM = "angelica_numSamples";
    /** Whether a shader takes a value from the provoking vertex, which decides how its triangles must be ordered on a device that has one convention. */
    public boolean flatInputs;

    /** Which a stage's subroutine uniforms are stored under. */
    public static final String STAGE_UNIFORM_PREFIX = "angelica_stage";

    public static boolean undeclared(String name) {
        return name.startsWith(STAGE_UNIFORM_PREFIX) || name.startsWith("angelica_draw_") || name.equals(CLIP_UNIFORM) || name.equals(POINT_SIZE_UNIFORM) || name.equals(POINT_ORIGIN_UNIFORM) || name.equals(NUM_SAMPLES_UNIFORM) || name.startsWith("angelica_cull_") || name.startsWith("angelica_gs_") || name.startsWith("angelica_feedback_") || name.startsWith(LodBiasLowering.PREFIX);
    }

    /** Members of uniform blocks, which GL lists after the others and which have no locations. */
    public final List<Uniform> blockUniforms = new ArrayList<>();

    public final int activeUniformCount() {
        return activeUniforms().size() + blockUniforms.size();
    }

    public final Uniform activeUniform(int index) {
        final int plain = activeUniforms().size();
        return index < plain ? uniforms.get(index) : blockUniforms.get(index - plain);
    }

    public final List<Uniform> activeUniforms() {
        int count = uniforms.size();
        while (count > 0 && undeclared(uniforms.get(count - 1).name)) count--;
        return uniforms.subList(0, count);
    }

    public static final int STAGES = 5;
    public static final int VS = 0, TCS = 1, TES = 2, GS = 3, FS = 4, CS = 0;

    public enum BindingKind { DEFAULT_BLOCK, UNIFORM_BUFFER, STORAGE_BUFFER, COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER, STORAGE_IMAGE }

    public final int id;
    public final IntArrayList attached = new IntArrayList();
    public final Object2IntOpenHashMap<String> attribBindings = new Object2IntOpenHashMap<>();
    public final Object2IntOpenHashMap<String> fragDataBindings = new Object2IntOpenHashMap<>();

    public boolean linked;
    /** Whether a pipeline may use this program for some of its stages. Set before linking, and kept. */
    public boolean separable;
    /** What {@code separable} was when the program last linked, which is all GL ever reports of it. */
    public boolean linkedSeparable;
    /** Linked from the stages of a pipeline, whose programs have no need to agree on what they declare. */
    public boolean pipelineComposite;
    /** Linked from SPIR-V, whose names GL need not report, so the longest-name queries answer as for an empty name. */
    public boolean fromSpirv;
    public String log = "";
    public int gen;
    public boolean compute;
    /** A separable program without a vertex stage: a pipeline can use it, but it cannot draw by itself. */
    public boolean vertexless;
    /** Linked with nothing attached. */
    public boolean noShaders;
    /** Whether a program with emulated cull distances drops the culled primitives' fragments itself. */
    public boolean cullsOnDevice;
    /** What the renderer last chose the program's sampler variant from, and what it chose. */
    public int[] borderPrint;
    public long borderKey;
    /** Whether a sampler's bias reaches its samples through a hidden uniform, as of link generation {@code lodBiasGen}. */
    public boolean biasesSamples;
    public int lodBiasGen = -1;
    public boolean usesGather;
    public boolean usesCubes;
    /** The glClipControl state last sent to this program. */
    public int clipState;
    public boolean deletePending;
    public String label;

    public final int[] stageBits = new int[STAGES];
    public int localX = 1, localY = 1, localZ = 1;
    public StageModes stageModes = new StageModes();
    /** How many cull distances CullDistanceLowering turned into a stored array, for CullDraws to act on. */
    public int cullDistances;
    /** What a device without geometry shaders runs in place of this program's, for GeometryDraws. */
    public EmulatedGeometry geometry;

    public static final class EmulatedGeometry {
        /** Null where the program has no geometry shader. */
        public final ProgramBase<?> compute;
        /** Null where the program has no tessellation shaders. */
        public final ProgramBase<?> control, evaluation;
        public final int verticesIn, maxVertices, invocations, indicesPerRun, drawMode, controlVertices;
        public final boolean declaresControlVertices;
        public final long vertexBytes, emittedBytes, controlBytes, patchBytes, evaluationBytes;
        public final int emittedStride;
        public final boolean evaluationCulls;
        public final java.util.Map<String, Integer> emittedOffsets;

        public EmulatedGeometry(ProgramBase<?> compute, ProgramBase<?> control, ProgramBase<?> evaluation, int verticesIn, int maxVertices, int invocations, int indicesPerRun, int drawMode, int controlVertices,
                                boolean declaresControlVertices, long vertexBytes, long emittedBytes, long controlBytes, long patchBytes, long evaluationBytes, int emittedStride, java.util.Map<String, Integer> emittedOffsets, boolean evaluationCulls) {
            this.evaluationCulls = evaluationCulls;
            this.emittedStride = emittedStride;
            this.emittedOffsets = emittedOffsets;
            this.compute = compute;
            this.control = control;
            this.evaluation = evaluation;
            this.controlVertices = controlVertices;
            this.declaresControlVertices = declaresControlVertices;
            this.controlBytes = controlBytes;
            this.patchBytes = patchBytes;
            this.evaluationBytes = evaluationBytes;
            this.verticesIn = verticesIn;
            this.maxVertices = maxVertices;
            this.invocations = invocations;
            this.indicesPerRun = indicesPerRun;
            this.drawMode = drawMode;
            this.vertexBytes = vertexBytes;
            this.emittedBytes = emittedBytes;
        }
    }

    public final List<Uniform> uniforms = new ArrayList<>();
    public final Object2IntOpenHashMap<String> locations = new Object2IntOpenHashMap<>();
    public int[] locationUniform = new int[0];
    public int[] locationElement = new int[0];

    public final StageBlock[] blocks = new StageBlock[STAGES];
    public final List<BufferBlock> ubos = new ArrayList<>();
    public final List<BufferBlock> ssbos = new ArrayList<>();
    public final List<Attrib> attribs = new ArrayList<>();
    public int vertexInputMask;
    public final int[] inputKind = new int[32];
    /** The size of each vertex input's double vectors, or 0; those of three or four take a second location from DoubleLowering.HIGH_LOCATIONS on. */
    public final int[] inputDoubles = new int[16];
    /** For each of those second locations in turn, the vertex attribute it is the second half of. */
    public final int[] secondHalfOf = new int[16];
    public int dynamicOffsetCount;
    public int colorOutputs = 1;
    public boolean clipMask;

    public final Long2ObjectOpenHashMap<P> logicVariants = new Long2ObjectOpenHashMap<>();
    public final LongOpenHashSet logicFailed = new LongOpenHashSet();
    public Shader[] linkedShaders = new Shader[0];
    public int[] logicBaseUniform = new int[0];

    protected ProgramBase(int id) {
        this.id = id;
        attribBindings.defaultReturnValue(-1);
        fragDataBindings.defaultReturnValue(-1);
        locations.defaultReturnValue(-1);
    }

    public static final class StageBlock {
        public final int stage;
        public final int size;
        public final ByteBuffer data;
        public final long address;
        public boolean dirty = true;
        public int binding;
        public long ringBuffer;
        public long ringOffset;
        public long ringSerial = -1;
        public int pushOffset = -1;

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

    public static final class Uniform {
        public final String name;
        public int glType;
        /** For a double, or a cube image that runs as an array, the type the source declared, which queries report; 0 otherwise. */
        public int declaredDouble;
        public int arraySize;
        public final int[] offset = filled(-1);
        /** The stages whose code reaches it, by slot bit; a stage can declare it in its block without. */
        public int reachedSlots;
        public final int[] arrayStride = new int[STAGES];
        public final int[] matrixStride = new int[STAGES];
        public final boolean[] rowMajor = new boolean[STAGES];
        public int baseLocation;
        public int bindingIndex = -1;
        public int[] units;
        /** For a sampler, the location of the hidden uniform holding its GL_TEXTURE_LOD_BIAS, -1 for none or -2 until looked for, and what it was last set to. */
        public int lodBiasLocation = -2;
        public float[] lodBias;
        /** How many blocks in a row hold this member, which is more than one for a block array. */
        public int blockCount = 1;
        public int blockIndex = -1;
        /** Which of the program's atomic counter blocks holds this counter, if it is one. */
        public int atomicBuffer = -1;

        /** An array of one is still an array to GL, which only the stride it has in a block tells. */
        public boolean isArray() {
            if (arraySize > 1) return true;
            for (int stride : arrayStride) if (stride > 0) return true;
            return false;
        }

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
            Arrays.fill(a, v);
            return a;
        }
    }

    public static final class BufferBlock {
        public final String name;
        public final int size;
        public int glBinding;
        public int bindingIndex;
        public final boolean atomicCounters;
        public boolean readOnly;
        /** Which stages declare it, one bit per stage slot. */
        public int stageSlots;
        /** A storage block's variables, for the queries about them. */
        public List<StageReflection.Member> members = List.of();

        public BufferBlock(String name, int size, int glBinding) {
            this.name = name;
            this.size = size;
            this.glBinding = glBinding;
            this.atomicCounters = StageReflection.atomicCounterBinding(name) >= 0;
        }

        /** A block emulation added, which the program does not show the application: transform feedback, culling, stages. */
        public boolean internal() {
            return name.startsWith("Angelica") || name.startsWith("angelica_");
        }
    }

    /** An input of the program's first stage or an output of its last, as the program interface queries name it. */
    public record IoVariable(String name, int glType, int arraySize, int location, int component, int index, boolean perPatch, boolean builtin, int slot) {}

    /** The application's source for each stage as of the last link, by shader type, whatever the stage was rewritten into to run. */
    public final java.util.Map<Integer, String> stageSources = new java.util.HashMap<>();
    /** What each stage's subroutine uniform locations hold, by shader type. Null where the program has none. */
    public java.util.Map<Integer, int[]> subroutineValues;
    public boolean usesSubroutines;
    /** Whether a stage picks its primitives' viewports, which then all have to be set on the device. */
    public boolean writesViewportIndex;
    public final List<IoVariable> programInputs = new ArrayList<>();
    public final List<IoVariable> programOutputs = new ArrayList<>();
    /** What glBindFragDataLocationIndexed asked for, by output. */
    public final Object2IntOpenHashMap<String> fragDataIndices = new Object2IntOpenHashMap<>();

    public static final class Attrib {
        public final String name;
        public final int location;
        public final int glType;
        public final int locations;
        /** How many locations one element of an array takes, and how many elements there are. */
        public int elementLocations = 1, size = 1;

        public Attrib(String name, int location, int glType, int locations) {
            this.name = name;
            this.location = location;
            this.glType = glType;
            this.locations = locations;
        }
    }

    protected void resetCommonLinkResults() {
        uniforms.clear();
        locations.clear();
        locationUniform = new int[0];
        locationElement = new int[0];
        for (int i = 0; i < STAGES; i++) {
            if (blocks[i] != null) blocks[i].free();
            blocks[i] = null;
        }
        Arrays.fill(stageBits, 0);
        noShaders = false;
        ubos.clear();
        blockUniforms.clear();
        ssbos.clear();
        attribs.clear();
        programInputs.clear();
        programOutputs.clear();
        vertexInputMask = 0;
        Arrays.fill(inputKind, 0);
        Arrays.fill(inputDoubles, 0);
        Arrays.fill(secondHalfOf, 0);
        dynamicOffsetCount = 0;
        colorOutputs = 1;
        clipMask = false;
        compute = false;
        linked = false;
    }

    public StageBlock newBlock(int stage, int size) {
        final StageBlock b = new StageBlock(stage, size);
        blocks[stage] = b;
        return b;
    }

    public abstract void resetLinkResults();
}
