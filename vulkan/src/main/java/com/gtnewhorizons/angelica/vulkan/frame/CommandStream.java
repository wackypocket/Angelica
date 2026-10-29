package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import com.gtnewhorizons.angelica.vulkan.resource.Buffers;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;

import static org.lwjgl.vulkan.VK10.*;

public final class CommandStream {
    private static final Tracy.ZoneId Z_VK_SUBMIT = Tracy.zoneId("vkSubmit", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_VK_FENCE_WAIT = Tracy.zoneId("vkFenceWait", Tracy.COLOR_SWAP);


    private final Gpu gpu;
    private final long pool;

    private VkCommandBuffer cmd;
    private VkCommandBuffer upload;
    private VkCommandBuffer secondary;
    private final ArrayList<VkCommandBuffer> secondaries = new ArrayList<>();
    private final ArrayDeque<VkCommandBuffer> freeSecondaries = new ArrayDeque<>();
    private long currentSerial = 1;
    private long completedSerial = 0;
    private boolean recordedWork;

    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final ArrayDeque<VkCommandBuffer> freeCmds = new ArrayDeque<>();
    private final ArrayDeque<Long> freeFences = new ArrayDeque<>();
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();

    private Runnable beforeSubmit = () -> {};

    private record InFlight(long serial, long fence, VkCommandBuffer cmd, VkCommandBuffer upload, VkCommandBuffer[] secondaries) {}
    private record Deferred(long serial, Runnable action) {}

    public CommandStream(Gpu gpu) {
        this.gpu = gpu;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(gpu.queueFamily);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateCommandPool(gpu.device, info, null, p), "vkCreateCommandPool");
            pool = p.get(0);
        }
    }

    public void setBeforeSubmit(Runnable hook) {
        this.beforeSubmit = hook;
    }

    public long currentSerial() { return currentSerial; }
    public long completedSerial() { return completedSerial; }

    public boolean isIdle(long lastUseSerial) {
        return lastUseSerial <= completedSerial;
    }

    public boolean hasRecording() {
        return cmd != null;
    }

    public VkCommandBuffer cmd() {
        if (secondary != null) return secondary;
        if (cmd == null) cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        recordedWork = true;
        return cmd;
    }

    public boolean canHoist(long lastUse) {
        return lastUse < currentSerial;
    }

    public VkCommandBuffer uploadCmd() {
        if (upload == null) upload = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        return upload;
    }

    public boolean isUploadCmd(VkCommandBuffer c) {
        return c == upload;
    }

    public void beginSecondary(VkCommandBufferInheritanceInfo inheritance) {
        if (secondary != null) throw new IllegalStateException("secondary already recording");
        if (cmd == null) cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        secondary = begin(freeSecondaries, VK_COMMAND_BUFFER_LEVEL_SECONDARY, inheritance);
        secondaries.add(secondary);
        recordedWork = true;
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
        if (c == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(pool)
                    .level(level)
                    .commandBufferCount(1);
                final PointerBuffer p = stack.mallocPointer(1);
                VkResults.check(vkAllocateCommandBuffers(gpu.device, info, p), "vkAllocateCommandBuffers");
                c = new VkCommandBuffer(p.get(0), gpu.device);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandBufferBeginInfo info = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT | (inheritance != null ? VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT : 0))
                .pInheritanceInfo(inheritance);
            VkResults.check(vkBeginCommandBuffer(c, info), "vkBeginCommandBuffer");
        }
        return c;
    }

    private static void memoryBarrier(VkCommandBuffer c, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(c, srcStages, dstStages, 0, b, null, null);
        }
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
        if (cmd == null && upload == null) {
            if (waitSemaphore == 0L && signalSemaphore == 0L) {
                return closeEmptySerial();
            }
        }
        if (cmd == null) cmd = begin(freeCmds, VK_COMMAND_BUFFER_LEVEL_PRIMARY, null);
        VkResults.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        if (upload != null) {
            memoryBarrier(upload, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            VkResults.check(vkEndCommandBuffer(upload), "vkEndCommandBuffer");
        }
        final long fence = takeFence();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .pCommandBuffers(upload != null ? stack.pointers(upload, cmd) : stack.pointers(cmd));
            if (waitSemaphore != 0L) {
                submit.waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(waitSemaphore))
                    .pWaitDstStageMask(stack.ints(waitStage));
            }
            if (signalSemaphore != 0L) submit.pSignalSemaphores(stack.longs(signalSemaphore));
            VkResults.check(vkQueueSubmit(gpu.queue, submit, fence), "vkQueueSubmit");
        }
        final long serial = currentSerial;
        inFlight.addLast(new InFlight(serial, fence, cmd, upload, secondaries.isEmpty() ? null : secondaries.toArray(new VkCommandBuffer[0])));
        secondaries.clear();
        cmd = null;
        upload = null;
        recordedWork = false;
        currentSerial++;
        poll();
        return serial;
    }

    private long closeEmptySerial() {
        final long serial = currentSerial;
        currentSerial++;
        if (inFlight.isEmpty()) {
            completedSerial = serial;
            runDeferred();
        }
        return serial;
    }

    public boolean hasRecordedWork() {
        return recordedWork;
    }

    private long takeFence() {
        final Long f = freeFences.pollFirst();
        if (f != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkResetFences(gpu.device, stack.longs(f));
            }
            return f;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkFenceCreateInfo info = VkFenceCreateInfo.calloc(stack).sType$Default();
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateFence(gpu.device, info, null, p), "vkCreateFence");
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

    public void waitFor(long serial) {
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
                int res;
                do {
                    res = vkWaitForFences(gpu.device, stack.longs(f.fence), true, 1_000_000_000L);
                } while (res == VK_TIMEOUT);
                VkResults.check(res, "vkWaitForFences");
                retire(f);
            }
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    public boolean waitFor(long serial, long timeoutNanos) {
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

    public void throttle(int maxPending) {
        poll();
        while (inFlight.size() > maxPending) {
            waitFor(inFlight.peekFirst().serial);
        }
    }

    public int pendingSubmissions() {
        return inFlight.size();
    }

    public void finish() {
        waitFor(currentSerial);
    }

    public void defer(Runnable action) {
        deferred.addLast(new Deferred(currentSerial, action));
    }

    public void deferAfter(long serial, Runnable action) {
        if (serial <= completedSerial) {
            action.run();
            return;
        }
        deferred.addLast(new Deferred(serial, action));
    }

    private void runDeferred() {
        while (!deferred.isEmpty() && deferred.peekFirst().serial <= completedSerial) {
            deferred.pollFirst().action.run();
        }
    }

    public void destroy() {
        gpu.waitIdle();
        while (!inFlight.isEmpty()) retire(inFlight.pollFirst());
        completedSerial = currentSerial;
        while (!deferred.isEmpty()) deferred.pollFirst().action.run();
        for (Long f : freeFences) vkDestroyFence(gpu.device, f, null);
        freeFences.clear();
        vkDestroyCommandPool(gpu.device, pool, null);
    }
}
