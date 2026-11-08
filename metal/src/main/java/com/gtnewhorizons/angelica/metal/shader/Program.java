package com.gtnewhorizons.angelica.metal.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.TokenBinding;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;

public final class Program extends ProgramBase<Program> {

    public final long[] libraries = new long[STAGES];
    public final long[] functions = new long[STAGES];
    public final String[] entryPoints = new String[STAGES];
    public final int[] sizeBuffer = {-1, -1, -1, -1, -1};
    public int vertexBufferBase;
    public boolean vertexWritesPointSize;
    public long computePipeline;
    public int computeFailedGen = -1;
    public boolean writesStorage;
    public int[] fragmentWords;
    public List<MslCompiler.Binding> fragmentBindings;
    public boolean fragmentWritesDepth;
    public long fragmentNoDepthLibrary, fragmentNoDepthFunction;
    public final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap coverageFunctions = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    public int[] vertexWords;
    public List<MslCompiler.Binding> vertexBindings;
    public long vertexNoRasterLibrary, vertexNoRasterFunction;
    public final Int2LongOpenHashMap clipFunctions = new Int2LongOpenHashMap();
    public final Int2LongOpenHashMap noRasterClipFunctions = new Int2LongOpenHashMap();

    public final List<Binding> bindings = new ArrayList<>();

    public Program(int id) {
        super(id);
    }

    public static final class Binding extends TokenBinding {
        public boolean writes;
        public int[] mtlBuffer = noSlots(STAGES), mtlTexture = noSlots(STAGES), mtlSampler = noSlots(STAGES);

        public Binding(int binding, BindingKind kind, int descriptorType, int count, int stageFlags, int ref, int dim, boolean arrayed, boolean shadow, int glType) {
            super(binding, kind, descriptorType, count, stageFlags, ref, dim, arrayed, shadow, glType);
        }
    }

    @Override public void resetLinkResults() {
        resetCommonLinkResults();
        for (int i = 0; i < STAGES; i++) {
            libraries[i] = 0L;
            functions[i] = 0L;
            entryPoints[i] = null;
            sizeBuffer[i] = -1;
        }
        vertexBufferBase = 0;
        computePipeline = 0L;
        writesStorage = false;
        fragmentWords = null;
        fragmentBindings = null;
        fragmentWritesDepth = false;
        fragmentNoDepthLibrary = fragmentNoDepthFunction = 0L;
        vertexWords = null;
        vertexBindings = null;
        vertexNoRasterLibrary = vertexNoRasterFunction = 0L;
        vertexWritesPointSize = false;
        clipFunctions.clear();
        noRasterClipFunctions.clear();
        coverageFunctions.clear();
        bindings.clear();
    }
}
