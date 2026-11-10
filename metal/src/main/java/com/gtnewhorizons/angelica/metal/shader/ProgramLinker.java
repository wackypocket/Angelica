package com.gtnewhorizons.angelica.metal.shader;

import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import com.gtnewhorizons.angelica.glemu.shader.TokenProgramLinkerBase;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class ProgramLinker extends TokenProgramLinkerBase<Program, Program.Binding> {
    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static final long SET_LANGUAGE = sel("setLanguageVersion:"), SET_MATH_MODE = sel("setMathMode:"), SET_FAST_MATH = sel("setFastMathEnabled:");
    private static final long NEW_LIBRARY = sel("newLibraryWithSource:options:error:"), NEW_FUNCTION = sel("newFunctionWithName:");
    private static final long NEW_FUNCTION_CONSTANTS = sel("newFunctionWithName:constantValues:error:"), SET_CONSTANT = sel("setConstantValue:type:atIndex:");
    private static final int MTL_DATA_TYPE_INT = 29;

    private final Gpu gpu;

    private final int mslVersion;
    private final long languageVersion;
    private static final long MATH_MODE_SAFE = 0L, MATH_MODE_FAST = 2L;

    public ProgramLinker(Gpu gpu) {
        super(gpu);
        this.gpu = gpu;
        final boolean modern = Gpu.isMacOs14OrLater();
        this.mslVersion = modern ? MslCompiler.MSL_3_1 : MslCompiler.MSL_2_3;
        this.languageVersion = modern ? Mtl.LANGUAGE_3_1 : Mtl.LANGUAGE_2_3;
    }

    @Override protected Program newProgram(int id) {
        return new Program(id);
    }

    @Override protected boolean linkStaged(Program p, List<Shader> shaders, Program staged) {
        final Stages stages = beginLink(p, shaders, staged, "Metal");
        if (stages == null) return false;
        final Shader[] byStage = stages.byStage;
        final Spirv[] spirv = stages.spirv;
        final StageReflection[] refl = stages.refl;
        final boolean compute = stages.compute;

        final String slotProblem = assignMetalSlots(staged, spirv, refl, compute);
        if (slotProblem != null) {
            p.log = slotProblem;
            return false;
        }
        final long[] libraries = new long[Program.STAGES];
        final long[] functions = new long[Program.STAGES];
        final String[] entries = new String[Program.STAGES];
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (spirv[slot] == null || slot == Program.TCS || slot == Program.TES || slot == Program.GS) continue;
            final int[] words = com.gtnewhorizons.angelica.glemu.shader.DoubleLowering.lower(spirv[slot].words);
            final List<MslCompiler.Binding> mb = new ArrayList<>();
            for (Program.Binding b : staged.bindings) {
                if ((b.stageFlags & (1 << slot)) == 0) continue;
                mb.add(new MslCompiler.Binding(b.binding, b.count, b.mtlBuffer[slot], b.mtlTexture[slot], b.mtlSampler[slot]));
            }
            final MslCompiler.Output msl;
            try {
                msl = MslCompiler.compile(words, byStage[slot].type, mb, staged.sizeBuffer[slot], mslVersion);
            } catch (IllegalStateException e) {
                release(libraries, functions);
                p.log = "shader " + byStage[slot].id + " could not be translated to MSL: " + e.getMessage();
                return false;
            }
            if (!msl.needsSizeBuffer()) staged.sizeBuffer[slot] = -1;
            final String[] error = new String[1];
            libraries[slot] = newLibrary(msl.source(), error);
            if (libraries[slot] == 0L) {
                release(libraries, functions);
                p.log = "shader " + byStage[slot].id + " failed to compile as MSL: " + error[0];
                return false;
            }
            entries[slot] = msl.entry();
            if (slot == Program.VS && !compute) {
                staged.vertexWords = words;
                staged.vertexBindings = mb;
            }
            if (slot == Program.VS) staged.vertexWritesPointSize = msl.source().contains("[[point_size]]");
            if (slot == Program.FS) {
                staged.fragmentWords = words;
                staged.fragmentBindings = mb;
                staged.fragmentWritesDepth = msl.source().contains("[[depth(");
            }
            staged.clipMask |= Spirv.hasSpecId(words, ShaderCompiler.CLIP_MASK_SPEC_ID);
            // A vertex shader with clip planes gets its function specialized per enabled-plane mask at draw time instead.
            if (!(slot == Program.VS && staged.clipMask && !compute)) {
                functions[slot] = newFunction(libraries[slot], msl.entry());
                if (functions[slot] == 0L) {
                    release(libraries, functions);
                    p.log = "MSL library for shader " + byStage[slot].id + " has no function " + msl.entry();
                    return false;
                }
            }
            staged.stageBits[slot] = stageBit(byStage[slot].type);
        }

        p.resetLinkResults();
        System.arraycopy(libraries, 0, p.libraries, 0, Program.STAGES);
        System.arraycopy(functions, 0, p.functions, 0, Program.STAGES);
        System.arraycopy(entries, 0, p.entryPoints, 0, Program.STAGES);
        System.arraycopy(staged.sizeBuffer, 0, p.sizeBuffer, 0, Program.STAGES);
        p.vertexBufferBase = staged.vertexBufferBase;
        p.vertexWritesPointSize = staged.vertexWritesPointSize;
        for (Program.Binding b : staged.bindings) p.writesStorage |= b.kind == Program.BindingKind.STORAGE_IMAGE && b.writes || b.kind == Program.BindingKind.STORAGE_BUFFER && !staged.ssbos.get(b.ref).readOnly;
        p.vertexWords = staged.vertexWords;
        p.vertexBindings = staged.vertexBindings;
        p.fragmentWords = staged.fragmentWords;
        p.fragmentBindings = staged.fragmentBindings;
        p.fragmentWritesDepth = staged.fragmentWritesDepth;
        p.bindings.addAll(staged.bindings);
        publishLink(p, staged);
        return true;
    }

    @Override protected List<Program.Binding> bindingsOf(Program program) {
        return program.bindings;
    }

    @Override protected Program.Binding newBinding(int binding, Program.BindingKind kind, int descriptorType, int count, int stageFlags, int ref, int dim, boolean arrayed, boolean shadow, int glType) {
        return new Program.Binding(binding, kind, descriptorType, count, stageFlags, ref, dim, arrayed, shadow, glType);
    }

    @Override protected void copySlots(Program.Binding from, Program.Binding to) {
        to.writes = from.writes;
        to.mtlBuffer = from.mtlBuffer;
        to.mtlTexture = from.mtlTexture;
        to.mtlSampler = from.mtlSampler;
    }

    private String assignMetalSlots(Program out, Spirv[] spirv, StageReflection[] refl, boolean compute) {
        // Metal binds by stage-local index: buffers first (uniform, storage, then the size buffer and vertex buffers), textures and samplers separately.
        final int vertexInputs = Integer.bitCount(out.vertexInputMask);
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (spirv[slot] == null) continue;
            final int bit = 1 << slot;
            int buffers = 0, textures = 0, samplers = 0;
            boolean storage = false;
            for (Program.Binding b : out.bindings) {
                if ((b.stageFlags & bit) == 0) continue;
                switch (b.kind) {
                    case DEFAULT_BLOCK, UNIFORM_BUFFER -> b.mtlBuffer[slot] = buffers++;
                    case STORAGE_BUFFER -> {
                        b.mtlBuffer[slot] = buffers++;
                        storage = true;
                    }
                    case COMBINED_SAMPLER -> {
                        b.mtlTexture[slot] = textures;
                        textures += b.count;
                        b.mtlSampler[slot] = samplers;
                        samplers += b.count;
                    }
                    case STORAGE_IMAGE -> {
                        b.writes = !readOnly(refl, b, StageReflection.Kind.STORAGE_IMAGE, out.uniforms.get(b.ref).name);
                        b.mtlTexture[slot] = textures;
                        textures += b.count;
                    }
                    case SAMPLED_IMAGE -> {
                        b.mtlTexture[slot] = textures;
                        textures += b.count;
                    }
                    case SAMPLER -> {
                        b.mtlSampler[slot] = samplers;
                        samplers += b.count;
                    }
                }
            }
            if (storage) out.sizeBuffer[slot] = buffers++;
            if (slot == Program.VS && !compute) {
                out.vertexBufferBase = buffers;
                buffers += vertexInputs;
            }
            if (buffers > Gpu.MAX_BUFFER_ARGUMENTS) return "stage " + slot + " needs " + buffers + " buffer slots (uniforms, storage and vertex buffers); Metal allows " + Gpu.MAX_BUFFER_ARGUMENTS;
            if (samplers > gpu.maxPerStageDescriptorSamplers) return "stage " + slot + " needs " + samplers + " samplers; Metal allows " + gpu.maxPerStageDescriptorSamplers;
            if (textures > gpu.maxPerStageDescriptorSampledImages) return "stage " + slot + " needs " + textures + " textures; Metal allows " + gpu.maxPerStageDescriptorSampledImages;
        }
        return null;
    }

    private long newLibrary(String rawSource, String[] error) {
        final String source = MslCompiler.bitTestNanInf(com.gtnewhorizons.angelica.glemu.shader.SpvcSupport.withEmptyPadding(rawSource));
        final long pool = ObjC.poolPush();
        final long errorOut = MemoryUtil.nmemCalloc(1, 8);
        final long text = ObjC.nsString(source);
        final long options = ObjC.create("MTLCompileOptions");
        try {
            call(options, SET_LANGUAGE, languageVersion);
            final boolean precise = MslCompiler.needsPreciseMath(source);
            if (ObjC.responds(options, "setMathMode:")) call(options, SET_MATH_MODE, precise ? MATH_MODE_SAFE : MATH_MODE_FAST);
            else call(options, SET_FAST_MATH, precise ? 0L : 1L);
            final long library = send(gpu.device, NEW_LIBRARY, text, options, errorOut);
            if (library == 0L) error[0] = ObjC.errorDescription(MemoryUtil.memGetAddress(errorOut));
            return library;
        } finally {
            ObjC.release(options);
            ObjC.release(text);
            MemoryUtil.nmemFree(errorOut);
            ObjC.poolPop(pool);
        }
    }

    private static long newFunction(long library, String entry) {
        final long name = ObjC.nsString(entry);
        try {
            return send(library, NEW_FUNCTION, name);
        } finally {
            ObjC.release(name);
        }
    }

    public long fragmentWithCoverage(Program p, int mask, boolean depth) {
        final long key = (mask & 0xFFFFFFFFL) << 1 | (depth ? 1L : 0L);
        final long known = p.coverageFunctions.get(key);
        if (known != 0L || p.fragmentWords == null) return known;
        final String[] error = new String[1];
        try {
            final MslCompiler.Output msl = MslCompiler.compile(p.fragmentWords, GL20.GL_FRAGMENT_SHADER, p.fragmentBindings, p.sizeBuffer[Program.FS], mslVersion, depth, false, mask);
            final long library = newLibrary(msl.source(), error);
            if (library == 0L) {
                LOG.error("Program {} has no fragment variant with a coverage mask: {}", p.id, error[0]);
                return 0L;
            }
            final long function = newFunction(library, msl.entry());
            ObjC.release(library);
            p.coverageFunctions.put(key, function);
            return function;
        } catch (IllegalStateException e) {
            LOG.error("Program {} has no fragment variant with a coverage mask: {}", p.id, e.getMessage());
            return 0L;
        }
    }

    public long fragmentWithoutDepth(Program p) {
        if (p.fragmentNoDepthFunction != 0L || p.fragmentWords == null) return p.fragmentNoDepthFunction;
        final MslCompiler.Output msl;
        try {
            msl = MslCompiler.compile(p.fragmentWords, GL20.GL_FRAGMENT_SHADER, p.fragmentBindings, p.sizeBuffer[Program.FS], mslVersion, false, false);
        } catch (IllegalStateException e) {
            LOG.error("Program {} has no fragment variant without depth output: {}", p.id, e.getMessage());
            return 0L;
        }
        final String[] error = new String[1];
        p.fragmentNoDepthLibrary = newLibrary(msl.source(), error);
        if (p.fragmentNoDepthLibrary == 0L) {
            LOG.error("Program {} has no fragment variant without depth output: {}", p.id, error[0]);
            return 0L;
        }
        p.fragmentNoDepthFunction = newFunction(p.fragmentNoDepthLibrary, msl.entry());
        return p.fragmentNoDepthFunction;
    }

    public long vertexWithoutRasterization(Program p, int clipMask) {
        if (p.vertexNoRasterLibrary == 0L && !compileVertexWithoutRasterization(p)) return 0L;
        if (!p.clipMask) return p.vertexNoRasterFunction;
        final long cached = p.noRasterClipFunctions.get(clipMask);
        if (cached != 0L) return cached;
        final long f = specialize(p.vertexNoRasterLibrary, p.entryPoints[Program.VS], clipMask);
        if (f != 0L) p.noRasterClipFunctions.put(clipMask, f);
        return f;
    }

    private boolean compileVertexWithoutRasterization(Program p) {
        if (p.vertexWords == null) return false;
        final MslCompiler.Output msl;
        try {
            msl = MslCompiler.compile(p.vertexWords, GL20.GL_VERTEX_SHADER, p.vertexBindings, p.sizeBuffer[Program.VS], mslVersion, true, true);
        } catch (IllegalStateException e) {
            LOG.error("Program {} has no vertex variant for rasterizer discard: {}", p.id, e.getMessage());
            return false;
        }
        final String[] error = new String[1];
        p.vertexNoRasterLibrary = newLibrary(msl.source(), error);
        if (p.vertexNoRasterLibrary == 0L) {
            LOG.error("Program {} has no vertex variant for rasterizer discard: {}", p.id, error[0]);
            return false;
        }
        if (!p.clipMask) p.vertexNoRasterFunction = newFunction(p.vertexNoRasterLibrary, msl.entry());
        return true;
    }

    public long clipFunction(Program p, int clipMask) {
        final long cached = p.clipFunctions.get(clipMask);
        if (cached != 0L) return cached;
        final long f = specialize(p.libraries[Program.VS], p.entryPoints[Program.VS], clipMask);
        if (f != 0L) p.clipFunctions.put(clipMask, f);
        return f;
    }

    private static long specialize(long library, String entry, int clipMask) {
        final long pool = ObjC.poolPush();
        final long values = ObjC.create("MTLFunctionConstantValues");
        final long name = ObjC.nsString(entry);
        final long scratch = MemoryUtil.nmemCalloc(1, 16);
        try {
            MemoryUtil.memPutInt(scratch, clipMask);
            call(values, SET_CONSTANT, scratch, MTL_DATA_TYPE_INT, ShaderCompiler.CLIP_MASK_SPEC_ID);
            final long function = send(library, NEW_FUNCTION_CONSTANTS, name, values, scratch + 8);
            if (function == 0L) LOG.error("Specializing {} for clip mask {} failed: {}", entry, clipMask, ObjC.errorDescription(MemoryUtil.memGetAddress(scratch + 8)));
            return function;
        } finally {
            MemoryUtil.nmemFree(scratch);
            ObjC.release(name);
            ObjC.release(values);
            ObjC.poolPop(pool);
        }
    }

    private static void release(long[] libraries, long[] functions) {
        for (int i = 0; i < libraries.length; i++) {
            ObjC.release(functions[i]);
            ObjC.release(libraries[i]);
            functions[i] = libraries[i] = 0L;
        }
    }

    public void destroy(Program p) {
        ObjC.release(p.fragmentNoDepthFunction);
        ObjC.release(p.fragmentNoDepthLibrary);
        ObjC.release(p.vertexNoRasterFunction);
        ObjC.release(p.vertexNoRasterLibrary);
        p.fragmentNoDepthFunction = p.fragmentNoDepthLibrary = p.vertexNoRasterFunction = p.vertexNoRasterLibrary = 0L;
        for (long f : p.coverageFunctions.values()) ObjC.release(f);
        p.coverageFunctions.clear();
        for (long f : p.clipFunctions.values()) ObjC.release(f);
        p.clipFunctions.clear();
        for (long f : p.noRasterClipFunctions.values()) ObjC.release(f);
        p.noRasterClipFunctions.clear();
        ObjC.release(p.computePipeline);
        p.computePipeline = 0L;
        for (int i = 0; i < Program.STAGES; i++) {
            ObjC.release(p.functions[i]);
            ObjC.release(p.libraries[i]);
            p.functions[i] = 0L;
            p.libraries[i] = 0L;
        }
    }
}
