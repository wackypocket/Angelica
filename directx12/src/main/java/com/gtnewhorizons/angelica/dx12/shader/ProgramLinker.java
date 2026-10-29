package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import com.gtnewhorizons.angelica.glemu.shader.ProgramLinkerBase;

import com.gtnewhorizons.angelica.glemu.shader.Spirv;

import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;

import com.gtnewhorizons.angelica.dx12.device.Gpu;
import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.pipeline.RootSignatures;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.*;

public final class ProgramLinker extends ProgramLinkerBase<Program> {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("Angelica/D3D12");

    public static final int FIRST_RESOURCE_BINDING = 8;
    public static final int CLIP_MASK_CONSTANT = ShaderCompiler.CLIP_MASK_SPEC_ID;

    private final Gpu gpu;

    @Override protected GpuCaps gpuCaps() {
        return gpu;
    }

    private final RootSignatures roots;

    private final java.util.function.Consumer<Runnable> deferrer;

    public ProgramLinker(Gpu gpu, RootSignatures roots, java.util.function.Consumer<Runnable> deferrer) {
        this.gpu = gpu;
        this.roots = roots;
        this.deferrer = deferrer;
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
            p.log = "geometry and tessellation shaders are not supported on Direct3D 12";
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

        final String slotProblem = assignDx12Slots(staged, refl, compute);
        if (slotProblem != null) {
            p.log = slotProblem;
            return false;
        }
        final int[][] stageWords = new int[Program.STAGES][];
        for (int slot = 0; slot < Program.STAGES; slot++) if (spirv[slot] != null) stageWords[slot] = spirv[slot].words;
        if (!compute && refl[Program.FS] != null) stageWords[Program.VS] = StageInterface.alignVertexOutputs(refl[Program.VS], refl[Program.FS]);
        final Bytecode[] code = new Bytecode[Program.STAGES];
        final List<HlslCompiler.Binding>[] bindings = new List[Program.STAGES];
        final List<java.util.function.Supplier<Object>> tasks = new ArrayList<>();
        final int[] taskSlot = new int[Program.STAGES];
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (spirv[slot] == null) continue;
            final int s = slot;
            bindings[s] = hlslBindings(staged, s);
            taskSlot[tasks.size()] = s;
            tasks.add(() -> {
                try {
                    return new Bytecode(HlslCompiler.compile(stageWords[s], byStage[s].type, bindings[s]));
                } catch (RuntimeException e) {
                    return e;
                }
            });
        }
        final List<Object> built = AngelicaWorkers.invokeAll(tasks);
        RuntimeException failed = null;
        int failedSlot = -1;
        for (int i = 0; i < built.size(); i++) {
            if (built.get(i) instanceof Bytecode b) {
                code[taskSlot[i]] = b;
            } else if (failed == null) {
                failed = (RuntimeException) built.get(i);
                failedSlot = taskSlot[i];
            }
        }
        if (failed != null) {
            free(code);
            p.log = "shader " + byStage[failedSlot].id + " could not be compiled for Direct3D 12: " + failed.getMessage();
            return false;
        }
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (spirv[slot] == null) continue;
            final int[] words = stageWords[slot];
            final List<HlslCompiler.Binding> hb = bindings[slot];
            if (slot == Program.VS && !compute) {
                staged.vertexWords = words;
                staged.vertexBindings = hb;
            }
            if (slot == Program.FS) {
                staged.fragmentWords = words;
                staged.fragmentBindings = hb;
            }
            staged.clipMask |= Spirv.hasSpecId(words, ShaderCompiler.CLIP_MASK_SPEC_ID);
            staged.stageBits[slot] = stageBit(byStage[slot].type);
        }
        if (compute) {
            staged.rootLayout = roots.compute(staged.srvCount[Program.CS], staged.samplerCount[Program.CS], staged.cbvCount[Program.CS], staged.uavCount[Program.CS]);
        } else {
            staged.rootLayout = roots.graphics(staged.srvCount[Program.VS], staged.samplerCount[Program.VS], staged.cbvCount[Program.VS], staged.uavCount[Program.VS],
                staged.srvCount[Program.FS], staged.samplerCount[Program.FS], staged.cbvCount[Program.FS], staged.uavCount[Program.FS]);
        }
        p.resetLinkResults();
        p.compute = staged.compute;
        p.localX = staged.localX;
        p.localY = staged.localY;
        p.localZ = staged.localZ;
        for (int i = 0; i < Program.STAGES; i++) {
            p.code[i] = code[i];
            p.functions[i] = code[i] == null ? 0L : code[i].address;
            p.entryPoints[i] = code[i] == null ? null : "main";
        }
        p.rootLayout = staged.rootLayout;
        System.arraycopy(staged.srvCount, 0, p.srvCount, 0, Program.STAGES);
        System.arraycopy(staged.samplerCount, 0, p.samplerCount, 0, Program.STAGES);
        System.arraycopy(staged.cbvCount, 0, p.cbvCount, 0, Program.STAGES);
        System.arraycopy(staged.uavCount, 0, p.uavCount, 0, Program.STAGES);
        System.arraycopy(staged.stageBits, 0, p.stageBits, 0, Program.STAGES);
        System.arraycopy(staged.sizeBuffer, 0, p.sizeBuffer, 0, Program.STAGES);
        p.vertexBufferBase = staged.vertexBufferBase;
        for (Program.Binding b : staged.bindings) p.writesStorage |= b.kind == Program.BindingKind.STORAGE_IMAGE || b.kind == Program.BindingKind.STORAGE_BUFFER;
        p.vertexWords = staged.vertexWords;
        p.vertexBindings = staged.vertexBindings;
        p.fragmentWords = staged.fragmentWords;
        p.fragmentBindings = staged.fragmentBindings;
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
                rebound.bufferReg = b.bufferReg;
                rebound.textureReg = b.textureReg;
                rebound.samplerReg = b.samplerReg;
                rebound.uav = b.uav;
                out.bindings.set(u.bindingIndex, rebound);
            }
        }
    }

    public static final int TEXEL_BUFFER = 1;

    private static boolean readOnly(StageReflection refl, StageReflection.Kind kind, String name) {
        if (refl == null) return false;
        for (StageReflection.Resource r : refl.resources) if (r.kind == kind && r.name.equals(name)) return r.readOnly;
        return false;
    }

    private String assignDx12Slots(Program out, StageReflection[] refl, boolean compute) {
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (refl[slot] == null) continue;
            final int bit = 1 << slot;
            int cbv = 0, srv = 0, uav = 0, samplers = 0;
            for (Program.Binding b : out.bindings) {
                if ((b.stageFlags & bit) == 0) continue;
                switch (b.kind) {
                    case DEFAULT_BLOCK, UNIFORM_BUFFER -> b.bufferReg[slot] = cbv++;
                    case STORAGE_BUFFER -> {
                        final boolean ro = readOnly(refl[slot], StageReflection.Kind.STORAGE_BUFFER, out.ssbos.get(b.ref).name) && !out.ssbos.get(b.ref).atomicCounters;
                        b.uav[slot] = !ro;
                        b.bufferReg[slot] = ro ? srv++ : uav++;
                    }
                    case COMBINED_SAMPLER -> {
                        b.textureReg[slot] = srv;
                        srv += b.count;
                        b.samplerReg[slot] = samplers;
                        samplers += b.count;
                    }
                    case SAMPLED_IMAGE -> {
                        b.textureReg[slot] = srv;
                        srv += b.count;
                    }
                    case STORAGE_IMAGE -> {
                        final boolean ro = readOnly(refl[slot], StageReflection.Kind.STORAGE_IMAGE, out.uniforms.get(b.ref).name);
                        b.uav[slot] = !ro;
                        if (ro) {
                            b.textureReg[slot] = srv;
                            srv += b.count;
                        } else {
                            b.textureReg[slot] = uav;
                            uav += b.count;
                        }
                    }
                    case SAMPLER -> {
                        b.samplerReg[slot] = samplers;
                        samplers += b.count;
                    }
                }
            }
            if (cbv > RootSignatures.MAX_CBV) return "stage " + slot + " needs " + cbv + " constant buffers; Direct3D 12 allows " + RootSignatures.MAX_CBV;
            if (samplers > RootSignatures.MAX_SAMPLER) return "stage " + slot + " needs " + samplers + " samplers; this backend allows " + RootSignatures.MAX_SAMPLER;
            if (srv > RootSignatures.MAX_SRV) return "stage " + slot + " needs " + srv + " shader resources; this backend allows " + RootSignatures.MAX_SRV;
            if (uav > RootSignatures.MAX_UAV) return "stage " + slot + " needs " + uav + " storage views; Direct3D 12 allows " + RootSignatures.MAX_UAV;
            out.srvCount[slot] = srv;
            out.samplerCount[slot] = samplers;
            out.cbvCount[slot] = cbv;
            out.uavCount[slot] = uav;
        }
        return null;
    }

    private static List<HlslCompiler.Binding> hlslBindings(Program staged, int slot) {
        final List<HlslCompiler.Binding> list = new ArrayList<>();
        for (Program.Binding b : staged.bindings) {
            if ((b.stageFlags & (1 << slot)) == 0) continue;
            list.add(new HlslCompiler.Binding(b.binding, b.kind, b.count, b.bufferReg[slot], b.textureReg[slot], b.samplerReg[slot], b.uav[slot]));
        }
        return list;
    }
    private static void free(Bytecode[] code) {
        for (int i = 0; i < code.length; i++) {
            if (code[i] != null) code[i].free();
            code[i] = null;
        }
    }

    private static long variantKey(int clipMask, int scaledMask, int signedMask) {
        return (clipMask & 0xFFL) | (scaledMask & 0xFFFFL) << 8 | (signedMask & 0xFFFFL) << 24;
    }

    public Bytecode vertexVariant(Program p, int clipMask, int scaledMask, int signedMask) {
        final long key = variantKey(p.clipMask ? clipMask : 0, scaledMask, signedMask & scaledMask);
        final Bytecode cached = p.vertexVariants.get(key);
        if (cached != null) return cached;
        if (p.vertexWords == null) return null;
        int[] words = p.clipMask ? Spirv.specialize(p.vertexWords, ShaderCompiler.CLIP_MASK_SPEC_ID, clipMask) : p.vertexWords;
        words = ScaledInputs.rewrite(words, scaledMask, signedMask);
        try {
            final Bytecode code = new Bytecode(HlslCompiler.compile(words, GL20.GL_VERTEX_SHADER, p.vertexBindings));
            p.vertexVariants.put(key, code);
            return code;
        } catch (RuntimeException e) {
            LOG.error("Program {} has no vertex variant for clip mask {} / integer inputs {}: {}", p.id, clipMask, Integer.toBinaryString(scaledMask), e.getMessage());
            return null;
        }
    }

    public void destroy(Program p) {
        for (Bytecode b : p.vertexVariants.values()) b.free();
        p.vertexVariants.clear();
        final long pso = p.computePipeline;
        if (pso != 0L) deferrer.accept(() -> Com.release(pso));
        p.computePipeline = 0L;
        for (int i = 0; i < Program.STAGES; i++) {
            if (p.code[i] != null) p.code[i].free();
            p.code[i] = null;
            p.functions[i] = 0L;
        }
    }
}