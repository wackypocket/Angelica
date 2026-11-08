package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFenceCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

final class Fences {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private Fences() {}

    static long take(Gpu gpu, ArrayDeque<Long> free) {
        final Long f = free.pollFirst();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (f != null) {
                vkResetFences(gpu.device, stack.longs(f));
                return f;
            }
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateFence(gpu.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, p), "vkCreateFence");
            return p.get(0);
        }
    }

    static boolean isSignaled(Gpu gpu, long fence) {
        final int status = vkGetFenceStatus(gpu.device, fence);
        if (status == VK_NOT_READY) return false;
        VkResults.check(status, "vkGetFenceStatus");
        return true;
    }

    /** A GPU that has not finished a frame in this long has hung without the driver saying so. */
    private static final int HUNG_AFTER_SECONDS = 120;

    static void await(Gpu gpu, long fence, MemoryStack stack) {
        int res;
        int seconds = 0;
        do {
            res = vkWaitForFences(gpu.device, stack.longs(fence), true, 1_000_000_000L);
            if (res == VK_TIMEOUT && ++seconds % 10 == 0) {
                if (seconds >= HUNG_AFTER_SECONDS) throw new VkResults.DeviceLostException("a fence wait of " + seconds + " s");
                LOG.warn("Still waiting for the GPU after {} s", seconds);
            }
        } while (res == VK_TIMEOUT);
        VkResults.check(res, "vkWaitForFences");
    }
}
