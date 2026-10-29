package com.gtnewhorizons.angelica.vulkan.device;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTPresentModeFifoLatestReady;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public final class Swapchain {
    private static final Tracy.ZoneId Z_VK_ACQUIRE_WAIT = Tracy.zoneId("vkAcquireWait", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_VK_QUEUE_PRESENT = Tracy.zoneId("vkQueuePresent", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private final Gpu gpu;
    private final long surface;

    public long handle;
    public int format = VK_FORMAT_UNDEFINED;
    public int colorSpace;
    public int width;
    public int height;
    private int requestedW, requestedH;
    public long[] images = new long[0];
    private long[] presentSemaphores = new long[0];
    private int presentMode = VK_PRESENT_MODE_FIFO_KHR;
    private VSyncMode vsync = VSyncMode.ON;
    private boolean dirty = true;

    private final ArrayDeque<long[]> acquireSemaphores = new ArrayDeque<>();

    private record Retired(long swapchain, long[] semaphores, long releaseAtPresentId) {}
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();

    public int imageIndex = -1;
    private long nextPresentId = 1;
    private long lastPresentId;
    public long acquireSemaphore;
    private long[] pendingAcquire;

    private boolean supportsImmediate;
    private boolean supportsMailbox;
    private boolean supportsLatestReady;

    public Swapchain(Gpu gpu, long surface) {
        this.gpu = gpu;
        this.surface = surface;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfacePresentModesKHR(gpu.physical, surface, count, null);
            final IntBuffer modes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(gpu.physical, surface, count, modes);
            for (int i = 0; i < modes.capacity(); i++) {
                if (modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) supportsImmediate = true;
                if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) supportsMailbox = true;
                if (modes.get(i) == EXTPresentModeFifoLatestReady.VK_PRESENT_MODE_FIFO_LATEST_READY_EXT && gpu.fifoLatestReady) supportsLatestReady = true;
            }
        }
        if (SystemProperties.VULKAN_FORCE_IMMEDIATE) vsync = VSyncMode.OFF;
    }

    public long surface() { return surface; }

    public boolean supports(VSyncMode mode) {
        return switch (mode) {
            case OFF -> supportsImmediate;
            case MAILBOX -> supportsMailbox;
            case FIFO_LATEST_READY -> supportsLatestReady;
            default -> true;
        };
    }

    public VSyncMode vsyncMode() { return vsync; }

    public VSyncMode setVSync(VSyncMode preferred) {
        VSyncMode chosen = SystemProperties.VULKAN_FORCE_IMMEDIATE ? VSyncMode.OFF : supports(preferred) ? preferred : (preferred == VSyncMode.OFF && supportsMailbox ? VSyncMode.MAILBOX : VSyncMode.ON);
        if (chosen != vsync) {
            vsync = chosen;
            dirty = true;
        }
        return chosen;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean ensure(int pixelW, int pixelH) {
        if (!dirty && handle != 0L && pixelW == requestedW && pixelH == requestedH) return true;
        if (pixelW <= 0 || pixelH <= 0) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            VkResults.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu.physical, surface, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            int w = caps.currentExtent().width();
            int h = caps.currentExtent().height();
            if (w == 0xFFFFFFFF) {
                w = clamp(pixelW, caps.minImageExtent().width(), caps.maxImageExtent().width());
                h = clamp(pixelH, caps.minImageExtent().height(), caps.maxImageExtent().height());
            }
            if (w <= 0 || h <= 0) return false;
            if (format == VK_FORMAT_UNDEFINED) chooseFormat(stack);

            int imageCount = Math.max(vsync == VSyncMode.ON ? 3 : 4, caps.minImageCount());
            if (caps.maxImageCount() != 0) imageCount = Math.min(imageCount, caps.maxImageCount());

            presentMode = switch (vsync) {
                case OFF -> VK_PRESENT_MODE_IMMEDIATE_KHR;
                case MAILBOX -> VK_PRESENT_MODE_MAILBOX_KHR;
                case FIFO_LATEST_READY -> EXTPresentModeFifoLatestReady.VK_PRESENT_MODE_FIFO_LATEST_READY_EXT;
                default -> VK_PRESENT_MODE_FIFO_KHR;
            };
            int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            if ((caps.supportedCompositeAlpha() & alpha) == 0) alpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            usage &= caps.supportedUsageFlags();

            final long old = handle;
            final VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType$Default()
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(format)
                .imageColorSpace(colorSpace)
                .imageArrayLayers(1)
                .imageUsage(usage)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform((caps.supportedTransforms() & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0 ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform())
                .compositeAlpha(alpha)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(old);
            info.imageExtent().set(w, h);
            final LongBuffer p = stack.mallocLong(1);
            final boolean drained = old == 0L || lastPresentId == 0L
                || gpu.presentWait && org.lwjgl.vulkan.KHRPresentWait.vkWaitForPresentKHR(gpu.device, old, lastPresentId, 100_000_000L) == VK_SUCCESS;
            gpu.waitIdle();
            VkResults.check(vkCreateSwapchainKHR(gpu.device, info, null, p), "vkCreateSwapchainKHR");
            handle = p.get(0);
            final Retired r = new Retired(old, presentSemaphores, nextPresentId + images.length + 1);
            presentSemaphores = new long[0];
            if (drained) destroy(r);
            else retired.addLast(r);

            final IntBuffer count = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(gpu.device, handle, count, null);
            final LongBuffer imgs = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(gpu.device, handle, count, imgs);
            images = new long[count.get(0)];
            presentSemaphores = new long[images.length];
            for (int i = 0; i < images.length; i++) {
                images[i] = imgs.get(i);
                presentSemaphores[i] = createSemaphore();
            }
            width = w;
            height = h;
            requestedW = pixelW;
            requestedH = pixelH;
            dirty = false;
            lastPresentId = 0;
            LOG.info("Swapchain {}x{} format {} colorSpace {} images {} presentMode {} ({})", w, h, formatName(format), colorSpace, images.length, presentModeName(presentMode), vsync);
            return true;
        }
    }

    private void chooseFormat(MemoryStack stack) {
        final IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(gpu.physical, surface, count, null);
        final VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(gpu.physical, surface, count, formats);
        int bestRank = -1;
        for (VkSurfaceFormatKHR f : formats) {
            if (f.colorSpace() != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) continue;
            final int rank = switch (f.format()) {
                case VK_FORMAT_R8G8B8A8_UNORM -> 3;
                case VK_FORMAT_B8G8R8A8_UNORM -> 2;
                case VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> 1;
                default -> 0;
            };
            if (rank > bestRank) {
                bestRank = rank;
                format = f.format();
                colorSpace = f.colorSpace();
            }
        }
        if (bestRank < 0) {
            format = formats.get(0).format();
            colorSpace = formats.get(0).colorSpace();
        }
        if (format == VK_FORMAT_UNDEFINED) {
            format = VK_FORMAT_R8G8B8A8_UNORM;
            colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        }
    }

    public static final int ACQUIRED = 0, REBUILD = 1, SKIP = 2;

    public int acquire(long completedSerial) {
        long[] sem = acquireSemaphores.peekFirst();
        if (sem != null && sem[1] <= completedSerial) {
            acquireSemaphores.pollFirst();
        } else {
            sem = new long[] {createSemaphore(), 0L};
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer idx = stack.mallocInt(1);
            Tracy.beginZone(Z_VK_ACQUIRE_WAIT);
            final int res;
            try {
                res = vkAcquireNextImageKHR(gpu.device, handle, -1L, sem[0], VK_NULL_HANDLE, idx);
            } finally {
                Tracy.endZone();
            }
            if (res == VK_ERROR_OUT_OF_DATE_KHR) {
                acquireSemaphores.addLast(sem);
                dirty = true;
                return REBUILD;
            }
            if (res == VK_ERROR_SURFACE_LOST_KHR) {
                acquireSemaphores.addLast(sem);
                return SKIP;
            }
            if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) VkResults.check(res, "vkAcquireNextImageKHR");
            if (res == VK_SUBOPTIMAL_KHR) dirty = true;
            imageIndex = idx.get(0);
            acquireSemaphore = sem[0];
            pendingAcquire = sem;
            return ACQUIRED;
        }
    }

    public long presentSemaphore() {
        return presentSemaphores[imageIndex];
    }

    public void consumedBy(long serial) {
        if (pendingAcquire != null) {
            pendingAcquire[1] = serial;
            acquireSemaphores.addLast(pendingAcquire);
            pendingAcquire = null;
        }
    }

    public boolean holdsToVblank() {
        return vsync != VSyncMode.FIFO_LATEST_READY;
    }

    public boolean fifo() {
        return presentMode == VK_PRESENT_MODE_FIFO_KHR;
    }

    public void present() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack)
                .sType$Default()
                .pWaitSemaphores(stack.longs(presentSemaphores[imageIndex]))
                .swapchainCount(1)
                .pSwapchains(stack.longs(handle))
                .pImageIndices(stack.ints(imageIndex));
            final long id = nextPresentId++;
            if (gpu.presentWait) {
                info.pNext(org.lwjgl.vulkan.VkPresentIdKHR.calloc(stack).sType$Default().pPresentIds(stack.longs(id)).address());
            }
            Tracy.beginZone(Z_VK_QUEUE_PRESENT);
            final int res;
            try {
                res = vkQueuePresentKHR(gpu.queue, info);
            } finally {
                Tracy.endZone();
            }
            lastPresentId = id;
            if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR) {
                dirty = true;
            } else if (res != VK_SUCCESS && res != VK_ERROR_SURFACE_LOST_KHR) {
                VkResults.check(res, "vkQueuePresentKHR");
            }
        }
        imageIndex = -1;
        while (!retired.isEmpty() && retired.peekFirst().releaseAtPresentId <= nextPresentId) destroy(retired.pollFirst());
    }

    private long createSemaphore() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateSemaphore(gpu.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, p), "vkCreateSemaphore");
            return p.get(0);
        }
    }

    private void destroy(Retired r) {
        for (long s : r.semaphores) vkDestroySemaphore(gpu.device, s, null);
        if (r.swapchain != 0L) vkDestroySwapchainKHR(gpu.device, r.swapchain, null);
    }

    public void destroy() {
        gpu.waitIdle();
        if (handle != 0L && gpu.presentWait && lastPresentId != 0L) {
            org.lwjgl.vulkan.KHRPresentWait.vkWaitForPresentKHR(gpu.device, handle, lastPresentId, 100_000_000L);
        }
        for (long s : presentSemaphores) vkDestroySemaphore(gpu.device, s, null);
        presentSemaphores = new long[0];
        while (!retired.isEmpty()) destroy(retired.pollFirst());
        for (long[] s : acquireSemaphores) vkDestroySemaphore(gpu.device, s[0], null);
        acquireSemaphores.clear();
        if (pendingAcquire != null) vkDestroySemaphore(gpu.device, pendingAcquire[0], null);
        pendingAcquire = null;
        if (handle != 0L) vkDestroySwapchainKHR(gpu.device, handle, null);
        handle = 0L;
        vkDestroySurfaceKHR(gpu.instance, surface, null);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String formatName(int format) {
        return switch (format) {
            case VK_FORMAT_R8G8B8A8_UNORM -> "R8G8B8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_UNORM -> "B8G8R8A8_UNORM";
            case VK_FORMAT_R8G8B8A8_SRGB -> "R8G8B8A8_SRGB";
            case VK_FORMAT_B8G8R8A8_SRGB -> "B8G8R8A8_SRGB";
            case VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> "A2B10G10R10_UNORM";
            default -> Integer.toString(format);
        };
    }

    private static String presentModeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case EXTPresentModeFifoLatestReady.VK_PRESENT_MODE_FIFO_LATEST_READY_EXT -> "FIFO_LATEST_READY";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> Integer.toString(mode);
        };
    }
}
