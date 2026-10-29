package com.gtnewhorizons.angelica.metal.shader;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import com.gtnewhorizons.angelica.glemu.shader.ProgramLinkerBase;

import com.gtnewhorizons.angelica.glemu.shader.Spirv;

import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;

import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.glemu.shader.Spirv.*;

public final class ProgramLinker extends ProgramLinkerBase<Program> {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("Angelica/Metal");

    public static final int FIRST_RESOURCE_BINDING = 8;
    public static final int CLIP_MASK_CONSTANT = ShaderCompiler.CLIP_MASK_SPEC_ID;

    private static final long SET_LANGUAGE = sel("setLanguageVersion:"), SET_MATH_MODE = sel("setMathMode:"), SET_FAST_MATH = sel("setFastMathEnabled:");
    private static final long NEW_LIBRARY = sel("newLibraryWithSource:options:error:"), NEW_FUNCTION = sel("newFunctionWithName:");
    private static final long NEW_FUNCTION_CONSTANTS = sel("newFunctionWithName:constantValues:error:"), SET_CONSTANT = sel("setConstantValue:type:atIndex:");
    private static final int MTL_DATA_TYPE_INT = 29;

    private final Gpu gpu;

    @Override protected GpuCaps gpuCaps() {
        return gpu;
    }

    private final int mslVersion;
    private final long languageVersion;

    public ProgramLinker(Gpu gpu) {
        this.gpu = gpu;
        final boolean modern = osMajor() >= 14;
        this.mslVersion = modern ? MslCompiler.MSL_3_1 : MslCompiler.MSL_2_3;
        this.languageVersion = modern ? Mtl.LANGUAGE_3_1 : Mtl.LANGUAGE_2_3;
    }

    private static int osMajor() {
        try {
            return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int stageBit(int glType) {
        return 1 << stageSlot(glType);
    }

    public boolean link(Program p, List<Shader> shaders) {
        final Shader[] byStage = new Shader[Program.STAGES];
        boolean compute = false;
        for (Shader s : shaders) {
            if (s.type == GL43.GL_COMPUTE_SHADER) compute = true;
            final int slot = stageSlot(s.type);
            if (byStage[slot] != null) {
                p.log = "multiple shaders of one stage attached; only one per stage is supported";
                return false;
            }
            byStage[slot] = s;
        }
        if (!compute && (byStage[Program.VS] == null)) {
            p.log = "no vertex shader attached";
            return false;
        }
        if (byStage[Program.GS] != null || byStage[Program.TCS] != null || byStage[Program.TES] != null) {
            p.log = "geometry and tessellation shaders are not supported on Metal";
            return false;
        }
        final boolean hasGs = byStage[Program.GS] != null;
        final boolean hasTes = byStage[Program.TES] != null;

        final Spirv[] spirv = new Spirv[Program.STAGES];
        final StageReflection[] refl = new StageReflection[Program.STAGES];
        final java.util.Set<String>[] explicit = new java.util.Set[Program.STAGES];
        final ShaderCompiler.Compiled[] compiled = new ShaderCompiler.Compiled[Program.STAGES];
        for (int slot = 0; slot < Program.STAGES; slot++) {
            final Shader s = byStage[slot];
            if (s == null) continue;
            final int fixup = compute ? ShaderCompiler.DEPTH_NONE : depthFixupFor(s.type, hasGs, hasTes);
            ShaderCompiler.Compiled c = s.result;
            if (c == null || s.resultFixup != fixup) {
                final ShaderCompiler.Result r = ShaderCompiler.compile(s.source, s.type, "shader" + s.id, fixup);
                if (r.compiled() == null) {
                    p.log = "shader " + s.id + " failed to compile: " + r.log();
                    return false;
                }
                c = r.compiled();
            }
            spirv[slot] = new Spirv(ByteBuffer.wrap(c.spirv()).order(ByteOrder.LITTLE_ENDIAN));
            refl[slot] = new StageReflection(spirv[slot], c.boolUniforms(), c.bindings());
            explicit[slot] = c.explicitInputs();
            compiled[slot] = c;
        }

        final List<String> problems = new ArrayList<>();
        relieveSamplerPressure(spirv, refl, compiled, problems);
        final Program staged = new Program(p.id);
        staged.compute = compute;
        if (compute) {
            refl[Program.CS].readLocalSize();
            staged.localX = refl[Program.CS].localSizeX;
            staged.localY = refl[Program.CS].localSizeY;
            staged.localZ = refl[Program.CS].localSizeZ;
        } else {
            assignVertexInputs(p, staged, refl[Program.VS], explicit[Program.VS]);
            int producer = Program.VS;
            for (int slot : new int[] {Program.TCS, Program.TES, Program.GS, Program.FS}) {
                if (refl[slot] == null) continue;
                matchInterface(refl[producer], refl[slot]);
                producer = slot;
            }
            if (refl[Program.FS] != null) assignFragmentOutputs(p, staged, refl[Program.FS]);
        }

        assignResources(staged, byStage, refl, problems);
        if (!problems.isEmpty()) {
            p.log = String.join("\n", problems);
            return false;
        }

        final String slotProblem = assignMetalSlots(staged, spirv, compute);
        if (slotProblem != null) {
            p.log = slotProblem;
            return false;
        }
        final long[] libraries = new long[Program.STAGES];
        final long[] functions = new long[Program.STAGES];
        final String[] entries = new String[Program.STAGES];
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (spirv[slot] == null) continue;
            final int[] words = spirv[slot].words;
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
            if (slot == Program.FS) {
                staged.fragmentWords = words;
                staged.fragmentBindings = mb;
                staged.fragmentWritesDepth = msl.source().contains("[[depth(");
            }
            staged.clipMask |= Spirv.hasSpecId(words, ShaderCompiler.CLIP_MASK_SPEC_ID);
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
        p.compute = staged.compute;
        p.localX = staged.localX;
        p.localY = staged.localY;
        p.localZ = staged.localZ;
        System.arraycopy(libraries, 0, p.libraries, 0, Program.STAGES);
        System.arraycopy(functions, 0, p.functions, 0, Program.STAGES);
        System.arraycopy(entries, 0, p.entryPoints, 0, Program.STAGES);
        System.arraycopy(staged.stageBits, 0, p.stageBits, 0, Program.STAGES);
        System.arraycopy(staged.sizeBuffer, 0, p.sizeBuffer, 0, Program.STAGES);
        p.vertexBufferBase = staged.vertexBufferBase;
        for (Program.Binding b : staged.bindings) p.writesStorage |= b.kind == Program.BindingKind.STORAGE_IMAGE || b.kind == Program.BindingKind.STORAGE_BUFFER;
        p.vertexWords = staged.vertexWords;
        p.vertexBindings = staged.vertexBindings;
        p.fragmentWords = staged.fragmentWords;
        p.fragmentBindings = staged.fragmentBindings;
        p.fragmentWritesDepth = staged.fragmentWritesDepth;
        p.uniforms.addAll(staged.uniforms);
        p.locations.putAll(staged.locations);
        p.locationUniform = staged.locationUniform;
        p.locationElement = staged.locationElement;
        System.arraycopy(staged.blocks, 0, p.blocks, 0, Program.STAGES);
        p.bindings.addAll(staged.bindings);
        p.ubos.addAll(staged.ubos);
        p.ssbos.addAll(staged.ssbos);
        p.attribs.addAll(staged.attribs);
        p.vertexInputMask = staged.vertexInputMask;
        System.arraycopy(staged.inputKind, 0, p.inputKind, 0, p.inputKind.length);
        p.dynamicOffsetCount = staged.dynamicOffsetCount;
        p.colorOutputs = staged.colorOutputs;
        p.writesDepth = staged.writesDepth;
        p.clipMask = staged.clipMask;
        p.linked = true;
        p.gen++;
        p.log = "";
        return true;
    }

    private record Res(String key, StageReflection.Kind kind, String name, int count, int explicitBinding, int glType, int dim, boolean arrayed, boolean shadow, int blockSize) {}

    private void assignResources(Program out, Shader[] byStage, StageReflection[] refl, List<String> problems) {
        int binding = 0;
        final Object2ObjectLinkedOpenHashMap<String, Program.Uniform> merged = new Object2ObjectLinkedOpenHashMap<>();
        for (int slot = 0; slot < Program.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null || r.defaultBlockVar < 0) continue;
            final Program.StageBlock block = out.newBlock(slot, r.defaultBlockSize);
            block.binding = slot;
            r.spirv.setDecoration(r.defaultBlockVar, DEC_BINDING, slot);
            r.spirv.setDecoration(r.defaultBlockVar, DEC_DESCRIPTOR_SET, 0);
            out.bindings.add(new Program.Binding(slot, Program.BindingKind.DEFAULT_BLOCK, 0, 1, stageBit(byStage[slot].type), slot, true, 0, false, false, 0));
            out.dynamicOffsetCount++;
            for (StageReflection.Member m : r.defaultBlock) {
                Program.Uniform u = merged.get(m.name);
                if (u == null) {
                    u = new Program.Uniform(m.name, m.glType, m.arraySize);
                    merged.put(m.name, u);
                }
                u.offset[slot] = m.offset;
                u.arrayStride[slot] = m.arrayStride;
                u.matrixStride[slot] = m.matrixStride;
                u.rowMajor[slot] = m.rowMajor;
            }
        }

        final Object2ObjectLinkedOpenHashMap<String, Res> resources = new Object2ObjectLinkedOpenHashMap<>();
        final Object2IntLinkedOpenHashMap<String> stageFlags = new Object2IntLinkedOpenHashMap<>();
        for (int slot = 0; slot < Program.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null) continue;
            for (StageReflection.Resource res : r.resources) {
                final String key = res.kind.name() + ":" + res.name;
                resources.putIfAbsent(key, new Res(key, res.kind, res.name, res.count, res.explicitBinding, res.glType, res.dim, res.arrayed, res.shadow, res.blockSize));
                stageFlags.put(key, stageFlags.getInt(key) | stageBit(byStage[slot].type));
            }
        }
        binding = FIRST_RESOURCE_BINDING;
        final Object2IntOpenHashMap<String> bindingOf = new Object2IntOpenHashMap<>();
        for (Res res : resources.values()) {
            final int flags = stageFlags.getInt(res.key);
            final int b = binding++;
            bindingOf.put(res.key, b);
            switch (res.kind) {
                case UNIFORM_BUFFER -> {
                    final boolean dyn = true;
                    final Program.BufferBlock bb = new Program.BufferBlock(res.name, res.blockSize, Math.max(0, res.explicitBinding));
                    bb.bindingIndex = out.bindings.size();
                    out.ubos.add(bb);
                    out.bindings.add(new Program.Binding(b, Program.BindingKind.UNIFORM_BUFFER, 0, 1, flags, out.ubos.size() - 1, dyn, 0, false, false, 0));
                    if (dyn) out.dynamicOffsetCount++;
                    if (res.count > 1) problems.add("uniform block array '" + res.name + "' is not supported");
                }
                case STORAGE_BUFFER -> {
                    final Program.BufferBlock bb = new Program.BufferBlock(res.name, res.blockSize, Math.max(0, res.explicitBinding));
                    bb.bindingIndex = out.bindings.size();
                    out.ssbos.add(bb);
                    out.bindings.add(new Program.Binding(b, Program.BindingKind.STORAGE_BUFFER, 0, 1, flags, out.ssbos.size() - 1, false, 0, false, false, 0));
                    if (res.count > 1) problems.add("storage block array '" + res.name + "' is not supported");
                }
                default -> {
                    final Program.BindingKind kind = switch (res.kind) {
                        case COMBINED_SAMPLER -> Program.BindingKind.COMBINED_SAMPLER;
                        case SAMPLED_IMAGE -> Program.BindingKind.SAMPLED_IMAGE;
                        case STORAGE_IMAGE -> Program.BindingKind.STORAGE_IMAGE;
                        default -> Program.BindingKind.SAMPLER;
                    };
                    final int effectiveType = res.dim == StageReflection.DIM_BUFFER ? TEXEL_BUFFER : 0;
                    final Program.Uniform u = new Program.Uniform(res.name, res.glType, res.count);
                    u.units = new int[res.count];
                    for (int i = 0; i < res.count; i++) u.units[i] = res.explicitBinding >= 0 ? res.explicitBinding + i : 0;
                    u.bindingIndex = out.bindings.size();
                    merged.put(res.name, u);
                    out.bindings.add(new Program.Binding(b, kind, effectiveType, res.count, flags, -1, false, res.dim, res.arrayed, res.shadow, res.glType));
                }
            }
        }
        for (int slot = 0; slot < Program.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null) continue;
            for (StageReflection.Resource res : r.resources) {
                final int b = bindingOf.getInt(res.kind.name() + ":" + res.name);
                r.spirv.setDecoration(res.varId, DEC_BINDING, b);
                r.spirv.setDecoration(res.varId, DEC_DESCRIPTOR_SET, 0);
            }
        }

        int location = 0;
        final List<Program.Uniform> ordered = new ArrayList<>(merged.values());
        for (Program.Uniform u : ordered) {
            u.baseLocation = location;
            location += Math.max(1, u.arraySize);
        }
        out.locationUniform = new int[location];
        out.locationElement = new int[location];
        for (int i = 0; i < ordered.size(); i++) {
            final Program.Uniform u = ordered.get(i);
            out.uniforms.add(u);
            for (int e = 0; e < Math.max(1, u.arraySize); e++) {
                out.locationUniform[u.baseLocation + e] = i;
                out.locationElement[u.baseLocation + e] = e;
            }
            out.locations.put(u.name, u.baseLocation);
            if (u.arraySize > 1 || u.isOpaque() && u.arraySize >= 1) {
                for (int e = 0; e < u.arraySize; e++) out.locations.put(u.name + "[" + e + "]", u.baseLocation + e);
            }
            if (u.isOpaque()) {
                final Program.Binding b = out.bindings.get(u.bindingIndex);
                final Program.Binding rebound = new Program.Binding(b.binding, b.kind, b.descriptorType, b.count, b.stageFlags, i, false, b.dim, b.arrayed, b.shadow, b.glType);
                rebound.mtlBuffer = b.mtlBuffer;
                rebound.mtlTexture = b.mtlTexture;
                rebound.mtlSampler = b.mtlSampler;
                out.bindings.set(u.bindingIndex, rebound);
            }
        }
    }

    public static final int TEXEL_BUFFER = 1;

    private String assignMetalSlots(Program out, Spirv[] spirv, boolean compute) {
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
                    case SAMPLED_IMAGE, STORAGE_IMAGE -> {
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
            if (buffers > gpu.maxBufferArguments) return "stage " + slot + " needs " + buffers + " buffer slots (uniforms, storage and vertex buffers); Metal allows " + gpu.maxBufferArguments;
            if (samplers > gpu.maxPerStageDescriptorSamplers) return "stage " + slot + " needs " + samplers + " samplers; Metal allows " + gpu.maxPerStageDescriptorSamplers;
            if (textures > gpu.maxPerStageDescriptorSampledImages) return "stage " + slot + " needs " + textures + " textures; Metal allows " + gpu.maxPerStageDescriptorSampledImages;
        }
        return null;
    }

    private long newLibrary(String rawSource, String[] error) {
        final String source = MslCompiler.bitTestNanInf(rawSource);
        final long pool = ObjC.poolPush();
        final long errorOut = MemoryUtil.nmemCalloc(1, 8);
        final long text = ObjC.nsString(source);
        final long options = ObjC.create("MTLCompileOptions");
        try {
            call(options, SET_LANGUAGE, languageVersion);
            final boolean precise = needsPreciseMath(source);
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

    private static final long MATH_MODE_SAFE = 0L, MATH_MODE_FAST = 2L;
    private static final String[] PRECISE_MARKERS = {"isunordered(", "isordered(", "precise::", "fma("};

    static boolean needsPreciseMath(String msl) {
        for (String marker : PRECISE_MARKERS) if (msl.contains(marker)) return true;
        return false;
    }

    private static long newFunction(long library, String entry) {
        final long name = ObjC.nsString(entry);
        try {
            return send(library, NEW_FUNCTION, name);
        } finally {
            ObjC.release(name);
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

    public long vertexWithoutRasterization(Program p) {
        if (p.vertexNoRasterFunction != 0L || p.vertexWords == null) return p.vertexNoRasterFunction;
        final MslCompiler.Output msl;
        try {
            msl = MslCompiler.compile(p.vertexWords, GL20.GL_VERTEX_SHADER, p.vertexBindings, p.sizeBuffer[Program.VS], mslVersion, true, true);
        } catch (IllegalStateException e) {
            LOG.error("Program {} has no vertex variant for rasterizer discard: {}", p.id, e.getMessage());
            return 0L;
        }
        final String[] error = new String[1];
        p.vertexNoRasterLibrary = newLibrary(msl.source(), error);
        if (p.vertexNoRasterLibrary == 0L) {
            LOG.error("Program {} has no vertex variant for rasterizer discard: {}", p.id, error[0]);
            return 0L;
        }
        p.vertexNoRasterFunction = newFunction(p.vertexNoRasterLibrary, msl.entry());
        return p.vertexNoRasterFunction;
    }

    public long clipFunction(Program p, int clipMask) {
        final long cached = p.clipFunctions.get(clipMask);
        if (cached != 0L) return cached;
        final long pool = ObjC.poolPush();
        final long values = ObjC.create("MTLFunctionConstantValues");
        final long name = ObjC.nsString(p.entryPoints[Program.VS]);
        final long scratch = MemoryUtil.nmemCalloc(1, 16);
        try {
            MemoryUtil.memPutInt(scratch, clipMask);
            call(values, SET_CONSTANT, scratch, MTL_DATA_TYPE_INT, CLIP_MASK_CONSTANT);
            final long f = send(p.libraries[Program.VS], NEW_FUNCTION_CONSTANTS, name, values, scratch + 8);
            if (f != 0L) p.clipFunctions.put(clipMask, f);
            return f;
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
        for (long f : p.clipFunctions.values()) ObjC.release(f);
        p.clipFunctions.clear();
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
