package com.gtnewhorizons.angelica.vulkan.pipeline;

import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import com.gtnewhorizons.angelica.vulkan.shader.Program;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.util.IntKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTLineRasterization;
import org.lwjgl.vulkan.EXTProvokingVertex;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationLineStateCreateInfoEXT;
import org.lwjgl.vulkan.VkPipelineRasterizationProvokingVertexStateCreateInfoEXT;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkPipelineTessellationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkStencilOpState;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public final class Pipelines {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private final VulkanGL gl;
    private long cache;
    private final File cacheFile;

    private IntKey[] keys = new IntKey[1024];
    private long[] values = new long[1024];
    private int size;
    private final List<Long> all = new ArrayList<>();
    public final IntKey.Probe probe = new IntKey.Probe();
    long created;

    public static final class Desc {
        public Program program;
        public int topology, primitiveRestart, patchPoints;
        public int polygonMode, cullMode, frontFace, depthBias, depthClamp, discard, provokingLast;
        public int depthTest, depthWrite, depthCompare, stencilTest;
        public final int[] stencilFront = new int[4], stencilBack = new int[4];
        public int logicOpEnable, logicOp, alphaToCoverage;
        public int colorCount;
        public final int[] colorFormat = new int[8];
        public final int[] blend = new int[8 * 8];
        public int depthFormat, stencilFormat;
        public int clipMask;
        public int vertexCount;
        public final int[] vLocation = new int[16], vFormat = new int[16], vStride = new int[16], vRate = new int[16];

        public void encode(IntKey.Probe p) {
            p.reset();
            p.add(program.id);
            p.add(program.gen);
            p.add(topology | primitiveRestart << 8 | patchPoints << 9);
            p.add(polygonMode | cullMode << 4 | frontFace << 6 | depthBias << 7 | depthClamp << 8 | discard << 9 | provokingLast << 10 | alphaToCoverage << 11 | logicOpEnable << 12 | logicOp << 13);
            p.add(depthTest | depthWrite << 1 | depthCompare << 2 | stencilTest << 5);
            if (stencilTest != 0) {
                p.add(stencilFront[0] | stencilFront[1] << 4 | stencilFront[2] << 8 | stencilFront[3] << 12 | stencilBack[0] << 16 | stencilBack[1] << 20 | stencilBack[2] << 24 | stencilBack[3] << 28);
            }
            p.add(colorCount);
            for (int i = 0; i < colorCount; i++) {
                p.add(colorFormat[i]);
                final int b = i * 8;
                p.add(blend[b] | blend[b + 7] << 1 | blend[b + 1] << 5 | blend[b + 2] << 10 | blend[b + 3] << 15 | blend[b + 4] << 18 | blend[b + 5] << 23 | blend[b + 6] << 28);
            }
            p.add(depthFormat);
            p.add(stencilFormat);
            p.add(clipMask);
            p.add(vertexCount);
            for (int i = 0; i < vertexCount; i++) {
                p.add(vLocation[i] | vRate[i] << 8);
                p.add(vFormat[i]);
                p.add(vStride[i]);
            }
            p.seal();
        }
    }

    public Pipelines(VulkanGL gl, File cacheFile) {
        this.gl = gl;
        this.cacheFile = cacheFile;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer initial = null;
            if (cacheFile != null && cacheFile.isFile()) {
                try {
                    final byte[] bytes = Files.readAllBytes(cacheFile.toPath());
                    initial = MemoryUtil.memAlloc(bytes.length);
                    initial.put(bytes).flip();
                } catch (Exception e) {
                    LOG.warn("Could not read the Vulkan pipeline cache {}: {}", cacheFile, e.toString());
                }
            }
            final VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initial != null) info.pInitialData(initial);
            final LongBuffer p = stack.mallocLong(1);
            int res = vkCreatePipelineCache(gl.gpu.device, info, null, p);
            if (res != VK_SUCCESS && initial != null) {
                info.pInitialData(null);
                res = vkCreatePipelineCache(gl.gpu.device, info, null, p);
            }
            if (initial != null) MemoryUtil.memFree(initial);
            cache = res == VK_SUCCESS ? p.get(0) : 0L;
        }
    }

    public long get(Desc d) {
        final IntKey.Probe p = probe;
        int mask = keys.length - 1;
        int i = p.hash & mask;
        while (true) {
            final IntKey k = keys[i];
            if (k == null) break;
            if (p.matches(k)) return values[i];
            i = (i + 1) & mask;
        }
        final long pipeline = create(d);
        if (size * 2 >= keys.length) {
            rehash();
            mask = keys.length - 1;
            i = p.hash & mask;
            while (keys[i] != null) i = (i + 1) & mask;
        }
        keys[i] = p.freeze();
        values[i] = pipeline;
        size++;
        if (pipeline != 0L) all.add(pipeline);
        return pipeline;
    }

    private void rehash() {
        final IntKey[] oldKeys = keys;
        final long[] oldValues = values;
        keys = new IntKey[oldKeys.length * 2];
        values = new long[oldKeys.length * 2];
        final int mask = keys.length - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            final IntKey k = oldKeys[j];
            if (k == null) continue;
            int i = k.hashCode() & mask;
            while (keys[i] != null) i = (i + 1) & mask;
            keys[i] = k;
            values[i] = oldValues[j];
        }
    }

    private long create(Desc d) {
        final Program prog = d.program;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int stageCount = 0;
            for (int s = 0; s < Program.STAGES; s++) if (prog.modules[s] != 0L) stageCount++;
            final VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            final ByteBuffer main = stack.UTF8("main");
            VkSpecializationInfo spec = null;
            if (prog.clipMask) {
                final VkSpecializationMapEntry.Buffer entry = VkSpecializationMapEntry.calloc(1, stack);
                entry.get(0).constantID(ShaderCompiler.CLIP_MASK_SPEC_ID).offset(0).size(4);
                spec = VkSpecializationInfo.calloc(stack).pMapEntries(entry).pData(MemoryUtil.memByteBuffer(stack.ints(d.clipMask)));
            }
            int si = 0;
            for (int s = 0; s < Program.STAGES; s++) {
                if (prog.modules[s] == 0L) continue;
                stages.get(si++).sType$Default().stage(prog.stageBits[s]).module(prog.modules[s]).pName(main).pSpecializationInfo(spec);
            }

            final VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(Math.max(1, d.vertexCount), stack);
            final VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(Math.max(1, d.vertexCount), stack);
            for (int i = 0; i < d.vertexCount; i++) {
                vb.get(i).binding(i).stride(d.vStride[i]).inputRate(d.vRate[i]);
                va.get(i).location(d.vLocation[i]).binding(i).format(d.vFormat[i]).offset(0);
            }
            vb.limit(d.vertexCount);
            va.limit(d.vertexCount);
            final VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
            final VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                .topology(d.topology).primitiveRestartEnable(d.primitiveRestart != 0);
            final VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
            final VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                .depthClampEnable(d.depthClamp != 0)
                .rasterizerDiscardEnable(d.discard != 0)
                .polygonMode(d.polygonMode)
                .cullMode(d.cullMode)
                .frontFace(d.frontFace)
                .depthBiasEnable(d.depthBias != 0)
                .lineWidth(1f);
            if (gl.gpu.provokingVertexLast && d.provokingLast != 0) {
                final VkPipelineRasterizationProvokingVertexStateCreateInfoEXT pv = VkPipelineRasterizationProvokingVertexStateCreateInfoEXT.calloc(stack).sType$Default()
                    .provokingVertexMode(EXTProvokingVertex.VK_PROVOKING_VERTEX_MODE_LAST_VERTEX_EXT);
                rs.pNext(pv.address());
            }
            if (gl.gpu.bresenhamLines) {
                final VkPipelineRasterizationLineStateCreateInfoEXT line = VkPipelineRasterizationLineStateCreateInfoEXT.calloc(stack).sType$Default()
                    .lineRasterizationMode(EXTLineRasterization.VK_LINE_RASTERIZATION_MODE_BRESENHAM_EXT)
                    .pNext(rs.pNext());
                rs.pNext(line.address());
            }
            final VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                .alphaToCoverageEnable(d.alphaToCoverage != 0);
            final VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                .depthTestEnable(d.depthTest != 0)
                .depthWriteEnable(d.depthWrite != 0)
                .depthCompareOp(d.depthCompare)
                .stencilTestEnable(d.stencilTest != 0);
            stencil(ds.front(), d.stencilFront);
            stencil(ds.back(), d.stencilBack);
            final VkPipelineColorBlendAttachmentState.Buffer att = VkPipelineColorBlendAttachmentState.calloc(Math.max(1, d.colorCount), stack);
            for (int i = 0; i < d.colorCount; i++) {
                final int b = i * 8;
                att.get(i).blendEnable(d.blend[b] != 0)
                    .srcColorBlendFactor(d.blend[b + 1]).dstColorBlendFactor(d.blend[b + 2]).colorBlendOp(d.blend[b + 3])
                    .srcAlphaBlendFactor(d.blend[b + 4]).dstAlphaBlendFactor(d.blend[b + 5]).alphaBlendOp(d.blend[b + 6])
                    .colorWriteMask(d.blend[b + 7]);
            }
            att.limit(d.colorCount);
            final VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                .logicOpEnable(d.logicOpEnable != 0)
                .logicOp(d.logicOp)
                .pAttachments(att);
            final VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH, VK_DYNAMIC_STATE_DEPTH_BIAS,
                    VK_DYNAMIC_STATE_BLEND_CONSTANTS, VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK, VK_DYNAMIC_STATE_STENCIL_WRITE_MASK, VK_DYNAMIC_STATE_STENCIL_REFERENCE));
            final VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                .colorAttachmentCount(d.colorCount)
                .pColorAttachmentFormats(d.colorCount == 0 ? null : stack.ints(java.util.Arrays.copyOf(d.colorFormat, d.colorCount)))
                .depthAttachmentFormat(d.depthFormat)
                .stencilAttachmentFormat(d.stencilFormat);
            final VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                .pNext(rendering.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(ia)
                .pViewportState(vp)
                .pRasterizationState(rs)
                .pMultisampleState(ms)
                .pDepthStencilState(ds)
                .pColorBlendState(cb)
                .pDynamicState(dyn)
                .layout(prog.pipelineLayout);
            if (d.patchPoints > 0) {
                info.pTessellationState(VkPipelineTessellationStateCreateInfo.calloc(stack).sType$Default().patchControlPoints(d.patchPoints));
            }
            final LongBuffer p = stack.mallocLong(1);
            final long t0 = System.nanoTime();
            final int res = vkCreateGraphicsPipelines(gl.gpu.device, cache, info, null, p);
            if (res != VK_SUCCESS) {
                LOG.error("vkCreateGraphicsPipelines failed for program {}: {}", prog.id, VkResults.name(res));
                return 0L;
            }
            created++;
            final long ms1 = (System.nanoTime() - t0) / 1_000_000L;
            if (ms1 > 20) LOG.debug("Pipeline for program {} took {} ms", prog.id, ms1);
            return p.get(0);
        }
    }

    private static void stencil(VkStencilOpState s, int[] ops) {
        s.failOp(ops[0]).passOp(ops[1]).depthFailOp(ops[2]).compareOp(ops[3]).compareMask(0xFF).writeMask(0xFF).reference(0);
    }

    public long compute(Program prog) {
        final IntKey.Probe p = probe;
        p.reset();
        p.add(-1);
        p.add(prog.id);
        p.add(prog.gen);
        p.seal();
        int mask = keys.length - 1;
        int i = p.hash & mask;
        while (keys[i] != null) {
            if (p.matches(keys[i])) return values[i];
            i = (i + 1) & mask;
        }
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(prog.pipelineLayout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(prog.modules[Program.CS]).pName(stack.UTF8("main"));
            final LongBuffer out = stack.mallocLong(1);
            final int res = vkCreateComputePipelines(gl.gpu.device, cache, info, null, out);
            if (res == VK_SUCCESS) pipeline = out.get(0);
            else LOG.error("vkCreateComputePipelines failed for program {}: {}", prog.id, VkResults.name(res));
        }
        if (size * 2 >= keys.length) {
            rehash();
            mask = keys.length - 1;
            i = p.hash & mask;
            while (keys[i] != null) i = (i + 1) & mask;
        }
        keys[i] = p.freeze();
        values[i] = pipeline;
        size++;
        if (pipeline != 0L) all.add(pipeline);
        return pipeline;
    }

    void save() {
        if (cache == 0L || cacheFile == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(gl.gpu.device, cache, size, null) != VK_SUCCESS) return;
            final ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                if (vkGetPipelineCacheData(gl.gpu.device, cache, size, data) != VK_SUCCESS) return;
                final byte[] bytes = new byte[(int) size.get(0)];
                data.get(bytes);
                Files.createDirectories(cacheFile.toPath().getParent());
                final File tmp = new File(cacheFile.getPath() + ".tmp");
                Files.write(tmp.toPath(), bytes);
                Files.move(tmp.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.warn("Could not save the Vulkan pipeline cache: {}", e.toString());
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    public void destroy() {
        save();
        for (long p : all) vkDestroyPipeline(gl.gpu.device, p, null);
        all.clear();
        java.util.Arrays.fill(keys, null);
        size = 0;
        if (cache != 0L) vkDestroyPipelineCache(gl.gpu.device, cache, null);
        cache = 0L;
    }

    public int count() {
        return all.size();
    }
}
