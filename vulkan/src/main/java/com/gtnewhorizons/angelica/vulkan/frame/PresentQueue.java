package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

public final class PresentQueue {
    private static final Tracy.ZoneId Z_PRESENT_SUBMIT = Tracy.zoneId("vkPresentSubmit", Tracy.COLOR_SWAP);
    private static final int MAX_PENDING = 3;

    private final Gpu gpu;
    private final VkQueue queue;
    private final long pool;
    private final long timeline;
    private long index;
    private long completedIndex;

    private record InFlight(long index, long fence, VkCommandBuffer cmd) {}
    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final ArrayDeque<VkCommandBuffer> freeCmds = new ArrayDeque<>();
    private final ArrayDeque<Long> freeFences = new ArrayDeque<>();

    public PresentQueue(Gpu gpu, VkQueue queue) {
        this.gpu = gpu;
        this.queue = queue;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(gpu.presentQueueFamily);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateCommandPool(gpu.device, info, null, p), "vkCreateCommandPool");
            pool = p.get(0);
        }
        timeline = CommandStream.createTimeline(gpu);
    }

    public VkQueue queue() { return queue; }

    public long timeline() { return timeline; }

    public long lastIndex() { return index; }

    public long completedIndex() {
        poll();
        return completedIndex;
    }

    public VkCommandBuffer begin() {
        poll();
        // Blits run on their own queue; the graphics timeline orders them after the frame they present and bounds how many are in flight.
        while (inFlight.size() >= MAX_PENDING) waitFor(inFlight.peekFirst().index);
        VkCommandBuffer c = freeCmds.pollFirst();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (c == null) {
                final VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
                final PointerBuffer p = stack.mallocPointer(1);
                VkResults.check(vkAllocateCommandBuffers(gpu.device, info, p), "vkAllocateCommandBuffers");
                c = new VkCommandBuffer(p.get(0), gpu.device);
            }
            final VkCommandBufferBeginInfo info = VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VkResults.check(vkBeginCommandBuffer(c, info), "vkBeginCommandBuffer");
        }
        return c;
    }

    public long submit(VkCommandBuffer cmd, long renderTimeline, long renderValue, long acquireSemaphore, long presentSemaphore) {
        Tracy.beginZone(Z_PRESENT_SUBMIT);
        try {
            VkResults.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
            final long next = index + 1;
            final long fence = takeFence();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final LongBuffer waits = stack.longs(renderTimeline, acquireSemaphore);
                final IntBuffer stages = stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                final LongBuffer waitValues = stack.longs(renderValue, 0L);
                final LongBuffer signals = stack.longs(timeline, presentSemaphore);
                final LongBuffer signalValues = stack.longs(next, 0L);
                final VkTimelineSemaphoreSubmitInfo values = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                    .pWaitSemaphoreValues(waitValues)
                    .pSignalSemaphoreValues(signalValues);
                final VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pNext(values.address())
                    .waitSemaphoreCount(2)
                    .pWaitSemaphores(waits)
                    .pWaitDstStageMask(stages)
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(signals);
                VkResults.check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit(present)");
            }
            index = next;
            inFlight.addLast(new InFlight(next, fence, cmd));
            poll();
            return next;
        } finally {
            Tracy.endZone();
        }
    }

    private long takeFence() {
        final Long f = freeFences.pollFirst();
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

    public void poll() {
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.peekFirst();
            final int status = vkGetFenceStatus(gpu.device, f.fence);
            if (status == VK_NOT_READY) break;
            VkResults.check(status, "vkGetFenceStatus");
            retire(inFlight.pollFirst());
        }
    }

    private void retire(InFlight f) {
        completedIndex = Math.max(completedIndex, f.index);
        freeFences.addLast(f.fence);
        freeCmds.addLast(f.cmd);
    }

    public void waitFor(long blitIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            while (!inFlight.isEmpty() && inFlight.peekFirst().index <= blitIndex) {
                final InFlight f = inFlight.pollFirst();
                int res;
                do {
                    res = vkWaitForFences(gpu.device, stack.longs(f.fence), true, 1_000_000_000L);
                } while (res == VK_TIMEOUT);
                VkResults.check(res, "vkWaitForFences(present)");
                retire(f);
            }
        }
    }

    public void finish() {
        waitFor(index);
    }

    public void destroy() {
        gpu.waitIdle();
        while (!inFlight.isEmpty()) retire(inFlight.pollFirst());
        for (Long f : freeFences) vkDestroyFence(gpu.device, f, null);
        freeFences.clear();
        vkDestroySemaphore(gpu.device, timeline, null);
        vkDestroyCommandPool(gpu.device, pool, null);
    }
}
