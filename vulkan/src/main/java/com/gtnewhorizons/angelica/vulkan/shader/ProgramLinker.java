package com.gtnewhorizons.angelica.vulkan.shader;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.shader.ProgramLinkerBase;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.*;
import static org.lwjgl.vulkan.VK10.*;

public final class ProgramLinker extends ProgramLinkerBase<Program> {

    private final Gpu gpu;

    private boolean pushDefaultBlocks;
    private int pushOffset;
    private boolean computeProgram;

    @Override protected GpuCaps gpuCaps() {
        return gpu;
    }

    public ProgramLinker(Gpu gpu) {
        this.gpu = gpu;
    }

    @Override protected int stageBit(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> VK_SHADER_STAGE_VERTEX_BIT;
            case GL40.GL_TESS_CONTROL_SHADER -> VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
            case GL40.GL_TESS_EVALUATION_SHADER -> VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
            case GL32.GL_GEOMETRY_SHADER -> VK_SHADER_STAGE_GEOMETRY_BIT;
            case GL43.GL_COMPUTE_SHADER -> VK_SHADER_STAGE_COMPUTE_BIT;
            default -> VK_SHADER_STAGE_FRAGMENT_BIT;
        };
    }

    @Override protected Program newProgram(int id) {
        return new Program(id);
    }

    @Override protected boolean linkStaged(Program p, List<Shader> shaders, Program staged) {
        final Stages stages = collectStages(p, shaders);
        if (stages == null) return false;
        final Shader[] byStage = stages.byStage;
        if (byStage[Program.GS] != null && !gpu.geometryShader || (byStage[Program.TCS] != null || byStage[Program.TES] != null) && !gpu.tessellationShader) {
            p.log = "geometry or tessellation shaders are not supported by this device";
            return false;
        }

        if (!compileStages(p, stages)) return false;
        final List<String> problems = assignInterfaces(p, staged, stages);
        final Spirv[] spirv = stages.spirv;
        final StageReflection[] refl = stages.refl;

        computeProgram = staged.compute;
        assignResources(staged, stages, problems);
        if (!problems.isEmpty()) {
            p.log = String.join("\n", problems);
            return false;
        }

        for (int slot = 0; slot < Program.STAGES; slot++) {
            final Program.StageBlock block = staged.blocks[slot];
            if (block == null || block.pushOffset < 0) continue;
            final byte[] moved = spirv[slot].toPushConstant(refl[slot].defaultBlockVar, block.pushOffset);
            if (moved == null) {
                p.log = "stage " + slot + " default uniform block could not move to push constants";
                return false;
            }
            spirv[slot] = new Spirv(ByteBuffer.wrap(moved).order(ByteOrder.LITTLE_ENDIAN));
        }

        final long[] modules = new long[Program.STAGES];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int slot = 0; slot < Program.STAGES; slot++) {
                if (spirv[slot] == null) continue;
                final int[] words = com.gtnewhorizons.angelica.glemu.shader.DoubleLowering.lower(spirv[slot].words, gpu.shaderFloat64);
                final ByteBuffer code = MemoryUtil.memAlloc(words.length * 4);
                try {
                    code.asIntBuffer().put(words);
                    final VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                    final LongBuffer pm = stack.mallocLong(1);
                    VkResults.check(vkCreateShaderModule(gpu.device, info, null, pm), "vkCreateShaderModule");
                    modules[slot] = pm.get(0);
                } finally {
                    MemoryUtil.memFree(code);
                }
                staged.stageBits[slot] = stageBit(byStage[slot].type);
                staged.clipMask |= Spirv.hasSpecId(words, ShaderCompiler.CLIP_MASK_SPEC_ID);
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
            int ranges = 0;
            for (Program.StageBlock block : staged.blocks) if (block != null && block.pushOffset >= 0) ranges++;
            if (ranges > 0) {
                final VkPushConstantRange.Buffer pr = VkPushConstantRange.calloc(ranges, stack);
                int i = 0;
                for (int slot = 0; slot < Program.STAGES; slot++) {
                    final Program.StageBlock block = staged.blocks[slot];
                    if (block == null || block.pushOffset < 0) continue;
                    pr.get(i++).stageFlags(staged.stageBits[slot]).offset(block.pushOffset).size(block.size);
                }
                pli.pPushConstantRanges(pr);
            }
            VkResults.check(vkCreatePipelineLayout(gpu.device, pli, null, pl), "vkCreatePipelineLayout");
            staged.setLayout = setLayout;
            staged.pipelineLayout = pl.get(0);
        }

        p.resetLinkResults();
        System.arraycopy(modules, 0, p.modules, 0, Program.STAGES);
        p.setLayout = staged.setLayout;
        p.pipelineLayout = staged.pipelineLayout;
        p.bindings.addAll(staged.bindings);
        p.dynamicOffsetCount = staged.dynamicOffsetCount;
        publishLink(p, staged);
        return true;
    }

    @Override protected int bindingCount(Program out) {
        return out.bindings.size();
    }

    @Override protected void beginResources(StageReflection[] refl) {
        int pushTotal = 0;
        for (int slot = 0; slot < Program.STAGES; slot++) {
            if (refl[slot] != null && refl[slot].defaultBlockVar >= 0) pushTotal += (refl[slot].defaultBlockSize + 15) & ~15;
        }
        // Small default uniform blocks travel as push constants (linkStaged rewrites their SPIR-V); larger ones use a dynamic uniform buffer.
        pushDefaultBlocks = !computeProgram && pushTotal > 0 && pushTotal <= gpu.maxPushConstantsSize;
        pushOffset = 0;
    }

    @Override protected void placeDefaultBlock(Program out, Program.StageBlock block, StageReflection r, int slot, int stageBit) {
        if (pushDefaultBlocks) {
            block.pushOffset = pushOffset;
            pushOffset += (r.defaultBlockSize + 15) & ~15;
            return;
        }
        r.spirv.setDecoration(r.defaultBlockVar, DEC_BINDING, slot);
        r.spirv.setDecoration(r.defaultBlockVar, DEC_DESCRIPTOR_SET, 0);
        out.bindings.add(new Program.Binding(slot, Program.BindingKind.DEFAULT_BLOCK, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, stageBit, slot, true, 0, false, false, 0));
        out.dynamicOffsetCount++;
    }

    @Override protected void addBufferBinding(Program out, boolean storage, int binding, int stageFlags, int blockIndex) {
        if (storage) {
            out.bindings.add(new Program.Binding(binding, Program.BindingKind.STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, stageFlags, blockIndex, false, 0, false, false, 0));
            return;
        }
        final boolean dynamic = out.dynamicOffsetCount < Math.min(gpu.maxDescriptorSetUniformBuffersDynamic, Program.MAX_DYNAMIC_OFFSETS);
        out.bindings.add(new Program.Binding(binding, Program.BindingKind.UNIFORM_BUFFER, dynamic ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, stageFlags, blockIndex, dynamic, 0, false, false, 0));
        if (dynamic) out.dynamicOffsetCount++;
    }

    @Override protected void addOpaqueBinding(Program out, Program.BindingKind kind, Res res, int binding, int stageFlags) {
        final int type = switch (kind) {
            case COMBINED_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case SAMPLED_IMAGE -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case STORAGE_IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            default -> VK_DESCRIPTOR_TYPE_SAMPLER;
        };
        out.bindings.add(new Program.Binding(binding, kind, type, res.count(), stageFlags, -1, false, res.dim(), res.arrayed(), res.shadow(), res.glType()));
    }

    @Override protected void rebindOpaque(Program out, int bindingIndex, int uniformIndex) {
        final Program.Binding b = out.bindings.get(bindingIndex);
        out.bindings.set(bindingIndex, new Program.Binding(b.binding, b.kind, b.descriptorType, b.count, b.stageFlags, uniformIndex, false, b.dim, b.arrayed, b.shadow, b.glType));
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
