package com.gtnewhorizons.angelica.vulkan.shader;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizons.angelica.vulkan.shader.Spirv.*;
import static org.lwjgl.vulkan.VK10.*;

public final class ProgramLinker {
    public static final int FIRST_RESOURCE_BINDING = 8;

    private final Gpu gpu;

    public ProgramLinker(Gpu gpu) {
        this.gpu = gpu;
    }

    public static int stageSlot(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER, GL43.GL_COMPUTE_SHADER -> Program.VS;
            case GL40.GL_TESS_CONTROL_SHADER -> Program.TCS;
            case GL40.GL_TESS_EVALUATION_SHADER -> Program.TES;
            case GL32.GL_GEOMETRY_SHADER -> Program.GS;
            default -> Program.FS;
        };
    }

    public static int stageBit(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> VK_SHADER_STAGE_VERTEX_BIT;
            case GL40.GL_TESS_CONTROL_SHADER -> VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
            case GL40.GL_TESS_EVALUATION_SHADER -> VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
            case GL32.GL_GEOMETRY_SHADER -> VK_SHADER_STAGE_GEOMETRY_BIT;
            case GL43.GL_COMPUTE_SHADER -> VK_SHADER_STAGE_COMPUTE_BIT;
            default -> VK_SHADER_STAGE_FRAGMENT_BIT;
        };
    }

    public static int depthFixupFor(int glType, boolean hasGeometry, boolean hasTessEval) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> hasGeometry || hasTessEval ? ShaderCompiler.DEPTH_NONE : ShaderCompiler.DEPTH_AT_END_OF_MAIN;
            case GL40.GL_TESS_EVALUATION_SHADER -> hasGeometry ? ShaderCompiler.DEPTH_NONE : ShaderCompiler.DEPTH_AT_END_OF_MAIN;
            case GL32.GL_GEOMETRY_SHADER -> ShaderCompiler.DEPTH_AT_EMIT;
            default -> ShaderCompiler.DEPTH_NONE;
        };
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
        final boolean hasGs = byStage[Program.GS] != null;
        final boolean hasTes = byStage[Program.TES] != null;

        final Spirv[] spirv = new Spirv[Program.STAGES];
        final StageReflection[] refl = new StageReflection[Program.STAGES];
        final java.util.Set<String>[] explicit = new java.util.Set[Program.STAGES];
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
        }

        final List<String> problems = new ArrayList<>();
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

        // Vulkan objects
        final long[] modules = new long[Program.STAGES];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int slot = 0; slot < Program.STAGES; slot++) {
                if (spirv[slot] == null) continue;
                final int[] words = spirv[slot].words;
                final ByteBuffer code = org.lwjgl.system.MemoryUtil.memAlloc(words.length * 4);
                try {
                    code.asIntBuffer().put(words);
                    final VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                    final LongBuffer pm = stack.mallocLong(1);
                    VkResults.check(vkCreateShaderModule(gpu.device, info, null, pm), "vkCreateShaderModule");
                    modules[slot] = pm.get(0);
                } finally {
                    org.lwjgl.system.MemoryUtil.memFree(code);
                }
                staged.stageBits[slot] = stageBit(byStage[slot].type);
            }
            final VkDescriptorSetLayoutBinding.Buffer lb = VkDescriptorSetLayoutBinding.calloc(Math.max(1, staged.bindings.size()), stack);
            for (int i = 0; i < staged.bindings.size(); i++) {
                final Program.Binding b = staged.bindings.get(i);
                lb.get(i).binding(b.binding).descriptorType(b.descriptorType).descriptorCount(b.count).stageFlags(b.stageFlags);
            }
            lb.limit(staged.bindings.size());
            final VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(lb);
            final LongBuffer pl = stack.mallocLong(1);
            VkResults.check(vkCreateDescriptorSetLayout(gpu.device, li, null, pl), "vkCreateDescriptorSetLayout");
            final long setLayout = pl.get(0);
            final VkPipelineLayoutCreateInfo pli = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
            VkResults.check(vkCreatePipelineLayout(gpu.device, pli, null, pl), "vkCreatePipelineLayout");
            staged.setLayout = setLayout;
            staged.pipelineLayout = pl.get(0);
        }

        // publish into the live program object
        p.resetLinkResults();
        p.compute = staged.compute;
        p.localX = staged.localX;
        p.localY = staged.localY;
        p.localZ = staged.localZ;
        System.arraycopy(modules, 0, p.modules, 0, Program.STAGES);
        System.arraycopy(staged.stageBits, 0, p.stageBits, 0, Program.STAGES);
        p.setLayout = staged.setLayout;
        p.pipelineLayout = staged.pipelineLayout;
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
        p.linked = true;
        p.gen++;
        p.log = "";
        return true;
    }

    private void assignVertexInputs(Program glProgram, Program out, StageReflection vs, java.util.Set<String> explicitInputs) {
        int used = 0;
        final List<StageReflection.Io> pending = new ArrayList<>();
        for (StageReflection.Io in : vs.inputs) {
            int loc = -1;
            if (explicitInputs.contains(in.name) && in.location >= 0) loc = in.location;
            else if (glProgram.attribBindings.getInt(in.name) >= 0) loc = glProgram.attribBindings.getInt(in.name);
            if (loc < 0) {
                pending.add(in);
                continue;
            }
            place(out, vs, in, loc);
            used |= mask(loc, in.locations);
        }
        for (StageReflection.Io in : pending) {
            int loc = 0;
            while (loc < 32 && (used & mask(loc, in.locations)) != 0) loc++;
            place(out, vs, in, loc);
            used |= mask(loc, in.locations);
        }
    }

    private static int mask(int loc, int n) {
        int m = 0;
        for (int i = 0; i < n && loc + i < 32; i++) m |= 1 << (loc + i);
        return m;
    }

    private void place(Program out, StageReflection vs, StageReflection.Io in, int loc) {
        vs.spirv.setDecoration(in.varId, DEC_LOCATION, loc);
        for (int i = 0; i < in.locations && loc + i < 16; i++) {
            out.vertexInputMask |= 1 << (loc + i);
            out.inputKind[loc + i] = in.scalarKind < 0 ? 0 : (in.scalarKind == 3 ? 1 : in.scalarKind);
        }
        out.attribs.add(new Program.Attrib(in.name, loc, attribGlType(in), in.locations));
    }

    private static int attribGlType(StageReflection.Io in) {
        final int n = in.locations > 1 ? in.components / in.locations : in.components;
        if (in.locations > 1 && in.scalarKind == 0) {
            return switch (in.locations) {
                case 2 -> GL20.GL_FLOAT_MAT2;
                case 3 -> GL20.GL_FLOAT_MAT3;
                default -> GL20.GL_FLOAT_MAT4;
            };
        }
        return switch (in.scalarKind) {
            case 1, 3 -> n == 1 ? GL11.GL_INT : n == 2 ? GL20.GL_INT_VEC2 : n == 3 ? GL20.GL_INT_VEC3 : GL20.GL_INT_VEC4;
            case 2 -> n == 1 ? GL11.GL_UNSIGNED_INT : n == 2 ? org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC2 : n == 3 ? org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC3 : org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC4;
            default -> n == 1 ? GL11.GL_FLOAT : n == 2 ? GL20.GL_FLOAT_VEC2 : n == 3 ? GL20.GL_FLOAT_VEC3 : GL20.GL_FLOAT_VEC4;
        };
    }

    private void matchInterface(StageReflection producer, StageReflection consumer) {
        final Object2IntOpenHashMap<String> byKey = new Object2IntOpenHashMap<>();
        byKey.defaultReturnValue(-1);
        int next = 0;
        for (StageReflection.Io out : producer.outputs) {
            // arrayed per-vertex outputs (TCS) count their element's locations
            final int count = Math.max(1, out.locations);
            producer.spirv.setDecoration(out.varId, DEC_LOCATION, next);
            byKey.put(out.key, next);
            next += count;
        }
        for (StageReflection.Io in : consumer.inputs) {
            int loc = byKey.getInt(in.key);
            if (loc < 0) {
                loc = next;
                next += Math.max(1, in.locations);
            }
            consumer.spirv.setDecoration(in.varId, DEC_LOCATION, loc);
        }
    }

    private void assignFragmentOutputs(Program glProgram, Program out, StageReflection fs) {
        int max = 0;
        for (StageReflection.Io o : fs.outputs) {
            int loc = glProgram.fragDataBindings.getInt(o.name);
            if (loc >= 0) fs.spirv.setDecoration(o.varId, DEC_LOCATION, loc);
            else loc = Math.max(0, o.location);
            max = Math.max(max, loc + Math.max(1, o.locations));
        }
        out.colorOutputs = Math.max(1, max);
    }

    private record Res(String key, StageReflection.Kind kind, String name, int count, int explicitBinding, int glType, int dim, boolean arrayed, boolean shadow, int blockSize) {}

    private void assignResources(Program out, Shader[] byStage, StageReflection[] refl, List<String> problems) {
        int binding = 0;
        // default uniform blocks, one dynamic UBO per stage
        final Object2ObjectLinkedOpenHashMap<String, Program.Uniform> merged = new Object2ObjectLinkedOpenHashMap<>();
        for (int slot = 0; slot < Program.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null || r.defaultBlockVar < 0) continue;
            final Program.StageBlock block = out.newBlock(slot, r.defaultBlockSize);
            block.binding = slot;
            r.spirv.setDecoration(r.defaultBlockVar, DEC_BINDING, slot);
            r.spirv.setDecoration(r.defaultBlockVar, DEC_DESCRIPTOR_SET, 0);
            out.bindings.add(new Program.Binding(slot, Program.BindingKind.DEFAULT_BLOCK, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, stageBit(byStage[slot].type), slot, true, 0, false, false, 0));
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

        // opaque and buffer resources, merged across stages by name
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
        int dynamicBudget = Math.max(0, gpu.maxDescriptorSetUniformBuffersDynamic - out.dynamicOffsetCount);
        for (Res res : resources.values()) {
            final int flags = stageFlags.getInt(res.key);
            final int b = binding++;
            bindingOf.put(res.key, b);
            switch (res.kind) {
                case UNIFORM_BUFFER -> {
                    final boolean dyn = dynamicBudget > 0;
                    if (dyn) dynamicBudget--;
                    final Program.BufferBlock bb = new Program.BufferBlock(res.name, res.blockSize, Math.max(0, res.explicitBinding));
                    bb.bindingIndex = out.bindings.size();
                    out.ubos.add(bb);
                    out.bindings.add(new Program.Binding(b, Program.BindingKind.UNIFORM_BUFFER, dyn ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, flags, out.ubos.size() - 1, dyn, 0, false, false, 0));
                    if (dyn) out.dynamicOffsetCount++;
                    if (res.count > 1) problems.add("uniform block array '" + res.name + "' is not supported");
                }
                case STORAGE_BUFFER -> {
                    final Program.BufferBlock bb = new Program.BufferBlock(res.name, res.blockSize, Math.max(0, res.explicitBinding));
                    bb.bindingIndex = out.bindings.size();
                    out.ssbos.add(bb);
                    out.bindings.add(new Program.Binding(b, Program.BindingKind.STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, flags, out.ssbos.size() - 1, false, 0, false, false, 0));
                    if (res.count > 1) problems.add("storage block array '" + res.name + "' is not supported");
                }
                default -> {
                    final Program.BindingKind kind = switch (res.kind) {
                        case COMBINED_SAMPLER -> Program.BindingKind.COMBINED_SAMPLER;
                        case SAMPLED_IMAGE -> Program.BindingKind.SAMPLED_IMAGE;
                        case STORAGE_IMAGE -> Program.BindingKind.STORAGE_IMAGE;
                        default -> Program.BindingKind.SAMPLER;
                    };
                    final int type = switch (kind) {
                        case COMBINED_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                        case SAMPLED_IMAGE -> res.dim == StageReflection.DIM_BUFFER ? VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                        case STORAGE_IMAGE -> res.dim == StageReflection.DIM_BUFFER ? VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                        default -> VK_DESCRIPTOR_TYPE_SAMPLER;
                    };
                    final int effectiveType = kind == Program.BindingKind.COMBINED_SAMPLER && res.dim == StageReflection.DIM_BUFFER ? VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER : type;
                    final Program.Uniform u = new Program.Uniform(res.name, res.glType, res.count);
                    u.units = new int[res.count];
                    for (int i = 0; i < res.count; i++) u.units[i] = res.explicitBinding >= 0 ? res.explicitBinding + i : 0;
                    u.bindingIndex = out.bindings.size();
                    merged.put(res.name, u);
                    out.bindings.add(new Program.Binding(b, kind, effectiveType, res.count, flags, -1, false, res.dim, res.arrayed, res.shadow, res.glType));
                }
            }
        }
        // patch every stage's resource bindings
        for (int slot = 0; slot < Program.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null) continue;
            for (StageReflection.Resource res : r.resources) {
                final int b = bindingOf.getInt(res.kind.name() + ":" + res.name);
                r.spirv.setDecoration(res.varId, DEC_BINDING, b);
                r.spirv.setDecoration(res.varId, DEC_DESCRIPTOR_SET, 0);
            }
        }

        // uniform locations
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
                out.bindings.set(u.bindingIndex, new Program.Binding(b.binding, b.kind, b.descriptorType, b.count, b.stageFlags, i, false, b.dim, b.arrayed, b.shadow, b.glType));
            }
        }
        // the block index of a buffer binding is its ref; nothing else to patch
    }

    public void destroy(Program p) {
        for (int i = 0; i < Program.STAGES; i++) {
            if (p.modules[i] != 0L) vkDestroyShaderModule(gpu.device, p.modules[i], null);
            p.modules[i] = 0L;
        }
        if (p.pipelineLayout != 0L) vkDestroyPipelineLayout(gpu.device, p.pipelineLayout, null);
        if (p.setLayout != 0L) vkDestroyDescriptorSetLayout(gpu.device, p.setLayout, null);
        p.pipelineLayout = 0L;
        p.setLayout = 0L;
    }
}
