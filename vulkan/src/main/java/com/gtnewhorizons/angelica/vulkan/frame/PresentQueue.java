package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import static org.lwjgl.vulkan.VK10.*;

public final class PresentQueue {
    private static final Tracy.ZoneId Z_PRESENT_SUBMIT = Tracy.zoneId("vkPresentSubmit", Tracy.COLOR_SWAP);

    private final Gpu gpu;
    private final VkQueue queue;
    private final long pool;
    private long index;
    private long completedIndex;

    private record InFlight(long index, long fence, VkCommandBuffer cmd) {}
    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final ArrayDeque<VkCommandBuffer> freeCmds = new ArrayDeque<>();
    private final ArrayDeque<Long> freeFences = new ArrayDeque<>();

    public PresentQueue(Gpu gpu, VkQueue queue) {
        this.gpu = gpu;
        this.queue = queue;
        pool = CommandBuffers.createPool(gpu, gpu.presentQueueFamily);
    }

    public VkQueue queue() { return queue; }

    public long lastIndex() { return index; }

    public long completedIndex() {
        poll();
        return completedIndex;
    }

    public VkCommandBuffer begin() {
        poll();
        // Blits run on their own queue; the graphics timeline orders them after the frame they present.
        VkCommandBuffer c = freeCmds.pollFirst();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (c == null) c = CommandBuffers.allocate(gpu, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
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
            final long fence = Fences.take(gpu, freeFences);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final LongBuffer waits = stack.longs(renderTimeline, acquireSemaphore);
                final IntBuffer stages = stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                final LongBuffer waitValues = stack.longs(renderValue, 0L);
                final LongBuffer signals = stack.longs(presentSemaphore);
                final LongBuffer signalValues = stack.longs(0L);
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

    public void poll() {
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.peekFirst();
            if (!Fences.isSignaled(gpu, f.fence)) break;
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
                Fences.await(gpu, f.fence, stack);
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
        vkDestroyCommandPool(gpu.device, pool, null);
    }
}
