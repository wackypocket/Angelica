package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

final class DescriptorPools {
    private static final int SETS_PER_POOL = 1024;

    private final VulkanGL gl;
    private long current;
    private final ArrayDeque<long[]> retired = new ArrayDeque<>();
    private final ArrayDeque<Long> spare = new ArrayDeque<>();
    long epoch;
    long allocations;

    DescriptorPools(VulkanGL gl) {
        this.gl = gl;
    }

    private long newPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(10, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(SETS_PER_POOL * 4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SETS_PER_POOL * 2);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL * 12);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(SETS_PER_POOL * 2);
            sizes.get(4).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(SETS_PER_POOL);
            sizes.get(5).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SETS_PER_POOL * 2);
            sizes.get(6).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(SETS_PER_POOL * 4);
            sizes.get(7).type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(SETS_PER_POOL);
            sizes.get(8).type(VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER).descriptorCount(SETS_PER_POOL);
            sizes.get(9).type(VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT).descriptorCount(16);
            final VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(SETS_PER_POOL)
                .pPoolSizes(sizes);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateDescriptorPool(gl.gpu.device, info, null, p), "vkCreateDescriptorPool");
            return p.get(0);
        }
    }

    private void nextPool() {
        if (current != 0L) retired.addLast(new long[] {current, gl.stream.currentSerial()});
        while (!retired.isEmpty() && gl.stream.isIdle(retired.peekFirst()[1])) {
            final long pool = retired.pollFirst()[0];
            vkResetDescriptorPool(gl.gpu.device, pool, 0);
            spare.addLast(pool);
        }
        final Long s = spare.pollFirst();
        current = s != null ? s : newPool();
        epoch++;
    }

    long allocate(long layout) {
        if (current == 0L) nextPool();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final LongBuffer out = stack.mallocLong(1);
            final VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(current)
                .pSetLayouts(stack.longs(layout));
            int res = vkAllocateDescriptorSets(gl.gpu.device, info, out);
            if (res != VK_SUCCESS) {
                nextPool();
                info.descriptorPool(current);
                res = vkAllocateDescriptorSets(gl.gpu.device, info, out);
                VkResults.check(res, "vkAllocateDescriptorSets");
            }
            allocations++;
            return out.get(0);
        }
    }

    void markUsed() {
    }

    void destroy() {
        if (current != 0L) vkDestroyDescriptorPool(gl.gpu.device, current, null);
        for (long[] r : retired) vkDestroyDescriptorPool(gl.gpu.device, r[0], null);
        for (long p : spare) vkDestroyDescriptorPool(gl.gpu.device, p, null);
        current = 0L;
        retired.clear();
        spare.clear();
    }
}
