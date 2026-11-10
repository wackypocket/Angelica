package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glemu.frame.SerialTracker;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import static org.lwjgl.vulkan.VK10.*;

public final class CommandStream extends SerialTracker {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("Angelica/Vulkan");
    private static final Tracy.ZoneId Z_VK_SUBMIT = Tracy.zoneId("vkSubmit", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_VK_FENCE_WAIT = Tracy.zoneId("vkFenceWait", Tracy.COLOR_SWAP);

    private final Gpu gpu;
    private final long pool;

    private VkCommandBuffer cmd;
    private VkCommandBuffer upload;
    private VkCommandBuffer secondary;
    private final ArrayList<VkCommandBuffer> secondaries = new ArrayList<>();
    private final ArrayDeque<VkCommandBuffer> freeSecondaries = new ArrayDeque<>();

    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final ArrayDeque<VkCommandBuffer> freeCmds = new ArrayDeque<>();
    private final ArrayDeque<Long> freeFences = new ArrayDeque<>();

    private Runnable beforeSubmit = () -> {};

    private final long timeline;
    private long lastSignaled;

    private record InFlight(long serial, long fence, VkCommandBuffer cmd, VkCommandBuffer upload, VkCommandBuffer[] secondaries) {}
    private boolean signalTimelineNext;

    public CommandStream(Gpu gpu) {
        this.gpu = gpu;
        pool = CommandBuffers.createPool(gpu, gpu.queueFamily);
        timeline = gpu.timelineSemaphores ? createTimeline(gpu) : 0L;
    }

    private static long createTimeline(Gpu gpu) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                .initialValue(0L);
            final VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address());
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateSemaphore(gpu.device, info, null, p), "vkCreateSemaphore(timeline)");
            return p.get(0);
        }
    }

    public long timeline() { return timeline; }

    public long lastSignaledSerial() { return lastSignaled; }

    public void signalTimelineOnNextSubmit() { signalTimelineNext = true; }

    public void setBeforeSubmit(Runnable hook) {
        this.beforeSubmit = hook;
    }

    public boolean hasRecording() {
        return cmd != null;
    }

    public VkCommandBuffer cmd() {
        if (secondary != null) return secondary;
        if (cmd == null) cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        return cmd;
    }

    public VkCommandBuffer uploadCmd() {
        if (upload == null) upload = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        return upload;
    }

    public boolean isUploadCmd(VkCommandBuffer c) {
        return c == upload;
    }

    public void beginSecondary(VkCommandBufferInheritanceInfo inheritance) {
        // Pass contents go to a secondary buffer so the primary can receive barriers in front of the pass after the fact.
        if (secondary != null) throw new IllegalStateException("secondary already recording");
        if (cmd == null) cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        secondary = begin(freeSecondaries, VK_COMMAND_BUFFER_LEVEL_SECONDARY, inheritance);
        secondaries.add(secondary);
    }

    public VkCommandBuffer endSecondary() {
        final VkCommandBuffer s = secondary;
        VkResults.check(vkEndCommandBuffer(s), "vkEndCommandBuffer");
        secondary = null;
        return s;
    }

    public boolean inSecondary() {
        return secondary != null;
    }

    private VkCommandBuffer begin(ArrayDeque<VkCommandBuffer> free, int level, VkCommandBufferInheritanceInfo inheritance) {
        VkCommandBuffer c = free.pollFirst();
        if (c == null) c = CommandBuffers.allocate(gpu, pool, level);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandBufferBeginInfo info = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT | (inheritance != null ? VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT : 0))
                .pInheritanceInfo(inheritance);
            VkResults.check(vkBeginCommandBuffer(c, info), "vkBeginCommandBuffer");
        }
        return c;
    }

    public static void memoryBarrier(VkCommandBuffer c, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(c, srcStages, dstStages, 0, b, null, null);
        }
    }

    @Override public long submit() {
        return submit(0L, 0, 0L);
    }

    public long submit(long waitSemaphore, int waitStage, long signalSemaphore) {
        Tracy.beginZone(Z_VK_SUBMIT);
        try {
            return submitUnzoned(waitSemaphore, waitStage, signalSemaphore);
        } finally {
            Tracy.endZone();
        }
    }

    private long submitUnzoned(long waitSemaphore, int waitStage, long signalSemaphore) {
        beforeSubmit.run();
        if (secondary != null) throw new IllegalStateException("submit while a secondary command buffer is recording");
        if (cmd == null && upload == null && waitSemaphore == 0L && signalSemaphore == 0L) {
            return closeEmptySerial();
        }
        if (cmd == null) cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
            // A command the device refused while recording spoils the whole buffer; what it held is lost, but rendering can carry on.
            LOG.error("Dropping a command buffer the device could not finish");
            vkResetCommandBuffer(cmd, 0);
            freeCmds.addFirst(cmd);
            cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
            VkResults.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        }
        if (upload != null) {
            memoryBarrier(upload, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_HOST_READ_BIT);
            VkResults.check(vkEndCommandBuffer(upload), "vkEndCommandBuffer");
        }
        final long fence = Fences.take(gpu, freeFences);
        final boolean signalTimeline = timeline != 0L && signalTimelineNext;
        final long serial = currentSerial;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .pCommandBuffers(upload != null ? stack.pointers(upload, cmd) : stack.pointers(cmd));
            final int nw = waitSemaphore != 0L ? 1 : 0;
            if (nw > 0) submit.waitSemaphoreCount(nw).pWaitSemaphores(stack.longs(waitSemaphore)).pWaitDstStageMask(stack.ints(waitStage));
            final LongBuffer signals = stack.mallocLong(2), signalValues = stack.mallocLong(2);
            int ns = 0;
            if (signalTimeline) {
                signals.put(ns, timeline);
                signalValues.put(ns, serial);
                ns++;
            }
            if (signalSemaphore != 0L) {
                signals.put(ns, signalSemaphore);
                signalValues.put(ns, 0L);
                ns++;
            }
            if (ns > 0) submit.pSignalSemaphores(signals.limit(ns));
            if (timeline != 0L) {
                final VkTimelineSemaphoreSubmitInfo values = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                    .pWaitSemaphoreValues(stack.callocLong(nw))
                    .pSignalSemaphoreValues(signalValues.limit(ns));
                submit.pNext(values.address());
            }
            VkResults.check(vkQueueSubmit(gpu.queue, submit, fence), "vkQueueSubmit");
        }
        if (signalTimeline) {
            lastSignaled = serial;
            signalTimelineNext = false;
        }
        inFlight.addLast(new InFlight(serial, fence, cmd, upload, secondaries.isEmpty() ? null : secondaries.toArray(new VkCommandBuffer[0])));
        secondaries.clear();
        cmd = null;
        upload = null;
        currentSerial++;
        poll();
        return serial;
    }

    @Override protected boolean nothingInFlight() {
        return inFlight.isEmpty();
    }

    @Override public void poll() {
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.peekFirst();
            if (!Fences.isSignaled(gpu, f.fence)) break;
            retire(inFlight.pollFirst());
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    private void retire(InFlight f) {
        completedSerial = Math.max(completedSerial, f.serial);
        freeFences.addLast(f.fence);
        freeCmds.addLast(f.cmd);
        if (f.upload != null) freeCmds.addLast(f.upload);
        if (f.secondaries != null) for (VkCommandBuffer s : f.secondaries) freeSecondaries.addLast(s);
    }

    @Override public void waitFor(long serial) {
        Tracy.beginZone(Z_VK_FENCE_WAIT);
        try {
            waitForUnzoned(serial);
        } finally {
            Tracy.endZone();
        }
    }

    private void waitForUnzoned(long serial) {
        if (serial <= completedSerial) return;
        if (serial >= currentSerial) submit(0L, 0, 0L);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            while (!inFlight.isEmpty() && inFlight.peekFirst().serial <= serial) {
                final InFlight f = inFlight.pollFirst();
                Fences.await(gpu, f.fence, stack);
                retire(f);
            }
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    @Override public boolean waitFor(long serial, long timeoutNanos) {
        if (serial <= completedSerial) return true;
        if (serial >= currentSerial) return false;
        final long deadline = System.nanoTime() + Math.max(0L, timeoutNanos);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            while (!inFlight.isEmpty() && inFlight.peekFirst().serial <= serial) {
                final long left = deadline - System.nanoTime();
                if (left <= 0L && timeoutNanos > 0L) return false;
                final InFlight f = inFlight.peekFirst();
                final int res = vkWaitForFences(gpu.device, stack.longs(f.fence), true, Math.max(0L, left));
                if (res == VK_TIMEOUT) return false;
                VkResults.check(res, "vkWaitForFences");
                retire(inFlight.pollFirst());
            }
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
        return serial <= completedSerial;
    }

    @Override public void finish() {
        waitFor(currentSerial);
    }

    @Override public void destroy() {
        gpu.waitIdle();
        while (!inFlight.isEmpty()) retire(inFlight.pollFirst());
        completedSerial = currentSerial;
        runAllDeferred();
        for (Long f : freeFences) vkDestroyFence(gpu.device, f, null);
        freeFences.clear();
        if (timeline != 0L) vkDestroySemaphore(gpu.device, timeline, null);
        vkDestroyCommandPool(gpu.device, pool, null);
    }
}
