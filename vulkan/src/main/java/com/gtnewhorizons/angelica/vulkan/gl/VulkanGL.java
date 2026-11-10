package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.BackendDisplay;
import com.gtnewhorizons.angelica.glemu.DisplayVblank;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.pipeline.ClearPass;
import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;
import com.gtnewhorizons.angelica.glemu.gl.OcclusionQueries;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.Swapchain;
import com.gtnewhorizons.angelica.vulkan.frame.CommandStream;
import com.gtnewhorizons.angelica.vulkan.frame.HostRing;
import com.gtnewhorizons.angelica.vulkan.frame.PresentQueue;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import com.gtnewhorizons.angelica.vulkan.resource.Buffers;
import com.gtnewhorizons.angelica.vulkan.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.vulkan.resource.Images;
import com.gtnewhorizons.angelica.vulkan.resource.Target;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import com.gtnewhorizons.angelica.vulkan.resource.Textures;
import com.gtnewhorizons.angelica.vulkan.resource.VkFormats;
import com.gtnewhorizons.angelica.vulkan.shader.Program;
import com.gtnewhorizons.angelica.vulkan.shader.Programs;
import com.gtnewhorizons.angelica.vulkan.util.DebugNames;
import java.nio.LongBuffer;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.EXTIndexTypeUint8;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageResolve;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

public final class VulkanGL extends GlBase<Tex, Program, Buf> {
    private static final Tracy.ZoneId Z_PRESENT = Tracy.zoneId("vkPresentFrame", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_FRAME_SLOT_WAIT = Tracy.zoneId("vkFrameSlotWait", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_PRESENT_WAIT = Tracy.zoneId("vkPresentWait", Tracy.COLOR_SWAP);
    private static final long PRESENT_WAIT_TIMEOUT_NANOS = 100_000_000L;

    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    public final Gpu gpu;
    public final MemoryAllocator allocator;
    public final CommandStream stream;
    final HostRing uniformRing;
    public final HostRing stagingRing;
    public final Images images;
    public final Textures textures;
    public final Buffers buffers;
    final Programs programs;
    public final Renderer renderer;
    final DefaultFramebuffer defaultFb;
    final ClearPass<Tex> clearPass;
    public final DebugNames debug;

    private Swapchain swapchain;
    private PresentQueue presentQueue;
    private DisplayVblank vblank;
    private long lastFrameStartNanos;
    private long ringIndexBuffer, ringIndexOffset;
    private final int framesInFlight = SystemProperties.VULKAN_FRAMES_IN_FLIGHT;
    private static final Tex PROXY_DEFAULTS = new Tex(-1, false);
    private final Target blitDst = new Target();

    @Override public boolean statsEnabled() {
        return SystemProperties.VULKAN_STATS;
    }

    public VulkanGL(Gpu gpu, Thread mainThread, File dataDir) {
        this.gpu = gpu;
        this.mainThread = mainThread;
        this.allocator = new MemoryAllocator(gpu);
        this.stream = new CommandStream(gpu);
        this.uniformRing = new HostRing(gpu, allocator, stream, 8L << 20, BuffersBase.USAGE_STREAM);
        this.stagingRing = new HostRing(gpu, allocator, stream, 32L << 20, BuffersBase.USAGE_UPLOAD);
        this.images = new Images(this);
        this.debug = new DebugNames(this, SystemProperties.VULKAN_DEBUG);
        this.textures = new Textures(this);
        this.buffers = new Buffers(this);
        this.programs = new Programs(this);
        this.renderer = new Renderer(this, dataDir == null ? null : new File(dataDir, "cache" + File.separator + "vulkan-pipelines-" + Integer.toHexString(gpu.vendorId) + "-" + Integer.toHexString(gpu.deviceId) + "-" + Integer.toHexString(gpu.driverVersion) + ".bin"));
        this.defaultFb = new DefaultFramebuffer(this);
        this.clearPass = new ClearPass<>(this);
        this.mainState = new GlState();
        gpu.driverMessages = this::postDriverMessage;
        stream.setBeforeSubmit(() -> {
            renderer.endPass();
            debug.closeAll();
            textures.flushUploads();
            // A fence alone does not make GPU writes visible to the host, and a read may wait on any finished submit.
            if (stream.hasRecording()) CommandStream.memoryBarrier(stream.cmd(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
        });
        try {
            textures.pickDepthFormats();
            renderer.init();
            occlusionDevice = new NativeOcclusion(VK_QUERY_TYPE_OCCLUSION, 0, true);
            occlusion = new OcclusionQueries(this, occlusionDevice);
            // What a geometry shader the device runs emits is only known to the device.
            geometryDevice = new NativeOcclusion(VK_QUERY_TYPE_PIPELINE_STATISTICS, VK_QUERY_PIPELINE_STATISTIC_GEOMETRY_SHADER_PRIMITIVES_BIT, gpu.pipelineStatisticsQuery && gpu.geometryShader);
            geometryCount = new OcclusionQueries(this, geometryDevice);
        } catch (RuntimeException | Error e) {
            // The caller destroys the device next, which every object made from it has to be gone for.
            releasePartial(e);
            throw e;
        }
        // Allocations happen while a draw is being recorded, so reclaiming only waits for what is already submitted.
        allocator.setReclaim(() -> stream.waitFor(stream.currentSerial() - 1));
    }

    int vertexFormatFallback(int vk) {
        // Three-component vertex formats are often unsupported. The four-component form reads the next byte as w, where GL gives 1.
        if ((images.bufferFeatures(vk) & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0) return vk;
        final int widened = switch (vk) {
            case VK_FORMAT_R8G8B8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case VK_FORMAT_R8G8B8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case VK_FORMAT_R8G8B8_UINT -> VK_FORMAT_R8G8B8A8_UINT;
            case VK_FORMAT_R8G8B8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
            case VK_FORMAT_R8G8B8_USCALED -> VK_FORMAT_R8G8B8A8_USCALED;
            case VK_FORMAT_R8G8B8_SSCALED -> VK_FORMAT_R8G8B8A8_SSCALED;
            case VK_FORMAT_R16G16B16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM;
            case VK_FORMAT_R16G16B16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
            case VK_FORMAT_R16G16B16_UINT -> VK_FORMAT_R16G16B16A16_UINT;
            case VK_FORMAT_R16G16B16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
            case VK_FORMAT_R16G16B16_SFLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case VK_FORMAT_R16G16B16_USCALED -> VK_FORMAT_R16G16B16A16_USCALED;
            case VK_FORMAT_R16G16B16_SSCALED -> VK_FORMAT_R16G16B16A16_SSCALED;
            default -> vk;
        };
        if (widened != vk && (images.bufferFeatures(widened) & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0) {
            warnOnce("vertex format " + vk + " is widened to " + widened + " on this device, so a vec4 attribute reads w from the next component");
            return widened;
        }
        warnOnce("vertex format " + vk + " unsupported by the device");
        return vk;
    }

    public void attachWindow(long window, long surface) {
        this.window = window;
        this.swapchain = new Swapchain(gpu, surface);
        if (gpu.isMoltenVkOrApple()) this.vblank = DisplayVblank.create(window);
        if (gpu.presentQueue != null && gpu.supportsPresent(surface, gpu.presentQueueFamily)) {
            presentQueue = new PresentQueue(gpu, gpu.presentQueue);
            defaultFb.setDoubleBuffered(true);
            LOG.info("Presenting from a second queue");
        }
        final long size = windowPixels();
        defaultFb.ensure((int) (size >>> 32), (int) size);
        final VSyncMode v = pendingVSync;
        if (v != null) swapchain.setVSync(v);
        if ((int) (size >>> 32) > 0 && (int) size > 0) swapchain.ensure((int) (size >>> 32), (int) size);
    }

    /** The window's surface was lost and a new one is not made yet; frames go unpresented until it is. */
    private boolean surfacePending;
    private long nextSurfaceAttemptFrame;

    /**
     * A lost surface, as after a display or compositor reset, never presents again: its swapchain and it are destroyed and a new
     * surface is made for the window, tried again every 60 frames until one can be presented to.
     */
    private void replaceLostSurface() {
        if (frames < nextSurfaceAttemptFrame) return;
        nextSurfaceAttemptFrame = frames + 60;
        if (swapchain != null) {
            LOG.warn("The window's Vulkan surface was lost; making a new one");
            if (presentQueue != null) presentQueue.finish();
            gpu.waitIdle();
            swapchain.destroy();
            swapchain = null;
            surfacePending = true;
        }
        final long surface = gpu.createSurface(window);
        if (surface == 0L) return;
        if (!gpu.supportsPresent(surface)) {
            org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(gpu.instance, surface, null);
            LOG.warn("{} cannot present to the window's new surface yet", gpu.deviceName);
            return;
        }
        if (presentQueue != null && !gpu.supportsPresent(surface, gpu.presentQueueFamily)) {
            presentQueue.destroy();
            presentQueue = null;
            defaultFb.setDoubleBuffered(false);
            LOG.info("Presenting from the render queue: the second queue cannot present to the new surface");
        }
        try {
            swapchain = new Swapchain(gpu, surface);
        } catch (RuntimeException e) {
            org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(gpu.instance, surface, null);
            LOG.warn("Could not set up the window's new surface yet: {}", e.toString());
            return;
        }
        final VSyncMode v = pendingVSync;
        if (v != null) swapchain.setVSync(v);
        surfacePending = false;
        LOG.info("Made a new Vulkan surface for the window");
    }

    @Override public boolean hasWindow() {
        return swapchain != null || surfacePending;
    }

    @Override public VSyncMode setVSync(VSyncMode preferred) {
        pendingVSync = preferred;
        if (swapchain == null) return preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        return swapchain.setVSync(preferred);
    }

    @Override public boolean holdsToVblank() {
        return swapchain == null || swapchain.holdsToVblank();
    }

    @Override public boolean supportsVSync(VSyncMode mode) {
        return swapchain != null && swapchain.supports(mode);
    }

    public int swapchainFormat() {
        return swapchain == null ? VK_FORMAT_UNDEFINED : swapchain.format;
    }

    @Override protected int framesInFlight() {
        return framesInFlight;
    }

    @Override protected Tracy.ZoneId frameSlotZone() {
        return Z_FRAME_SLOT_WAIT;
    }

    @Override protected Tracy.ZoneId presentZone() {
        return Z_PRESENT;
    }

    @Override protected void presentFrame() {
        frames++;
        renderer.endPass(true);
        final long px = windowPixels();
        final int ww = (int) (px >>> 32), wh = (int) px;
        boolean didPresent = false;
        final long t0 = System.nanoTime();
        awaitFrameSlot();
        final long t1 = System.nanoTime();
        if (paceSink != null) paceSink.gpuWait(t1 - t0);
        if (surfacePending || swapchain != null && swapchain.surfaceLost()) replaceLostSurface();
        // The present blit's drawable wait paces at the display rate under MoltenVK, so with vsync off a frame whose previous blit
        // is held up by the display is skipped rather than throttling the game loop, as the Metal presenter does. A blit still
        // waiting for its own frame to finish rendering says nothing about the display, so once a refresh has passed since it was
        // submitted the next one goes in behind it; skipping there would drop every other frame when the GPU is slower than the display.
        // Sooner than that the frame is skipped, since queueing blits far faster than the display takes them crashed inside MoltenVK.
        boolean presentReady = true;
        if (presentQueue != null && presentQueue.completedIndex() < presentQueue.lastIndex()) {
            if (swapchain != null && swapchain.fifo()) presentQueue.waitFor(presentQueue.lastIndex());
            else {
                stream.poll();
                presentReady = lastBlitPeriodNanos > 0L && t1 - lastBlitNanos >= lastBlitPeriodNanos && !stream.isIdle(lastBlitRenderSerial);
            }
        }
        if (presentReady && swapchain != null && ww > 0 && wh > 0 && swapchain.ensure(ww, wh)) {
            final long acquireCompleted = presentQueue != null ? presentQueue.completedIndex() : stream.completedSerial();
            int r = swapchain.acquire(acquireCompleted);
            if (r == Swapchain.REBUILD && swapchain.ensure(ww, wh)) r = swapchain.acquire(acquireCompleted);
            final long tAcquired = System.nanoTime();
            if (r == Swapchain.ACQUIRED) {
                if (presentQueue != null) {
                    presentFromQueue(tAcquired - t0);
                } else {
                    if (swapchain.fifo()) submitNow();
                    recordPresentBlit();
                    final long tBlit = System.nanoTime();
                    final long serial = stream.submit(swapchain.acquireSemaphore, VK_PIPELINE_STAGE_TRANSFER_BIT, swapchain.presentSemaphore());
                    swapchain.consumedBy(serial);
                    swapchain.present();
                    final long tEnd = System.nanoTime();
                    if (paceSink != null) paceSink.gate(tAcquired - t0 + tEnd - tBlit, tEnd);
                    presentSerials.enqueue(serial);
                }
                didPresent = true;
            }
        }
        if (didPresent) {
            lastFrameStartNanos = t1;
            presented++;
        } else {
            presentSkips++;
            presentSerials.enqueue(stream.submit(0L, 0, 0L));
        }
        final long now = System.nanoTime();
        if (statsDue(now)) {
            LOG.info("Vulkan frame {} on {}: window {}x{}, back buffer {}x{}, {}", frames, Thread.currentThread().getName(), ww, wh, defaultFb.width, defaultFb.height, stats());
        }
        if (ww > 0 && wh > 0 && (ww != defaultFb.width || wh != defaultFb.height)) {
            // The present queue's blit may still read the old back buffer, and the main stream's serial says nothing about it.
            if (presentQueue != null) presentQueue.finish();
            defaultFb.ensure(ww, wh);
        }
        if ((frames & 255) == 0) {
            stagingRing.trim(2);
            buffers.trim();
            allocator.trim();
        }
    }

    @Override public void memoryBarrier(int barriers) {
        // Atomics through MoltenVK's buffer alias can't be ordered against a copy (see Textures.aliasedForAtomics), so they are drained before
        // the barrier copies a buffer texture's image back to its buffer.
        if (gpu.isMoltenVkOrApple()) {
            final GlState st = st();
            for (int u = 0; u < GlState.MAX_IMAGE_UNITS; u++) {
                final Tex t = st.imageTex[u] != 0 ? textures.get(st.imageTex[u]) : null;
                if (t != null && textures.aliasedForAtomics(t, st.imageFormat[u]) && !stream.isIdle(t.lastGpuWrite)) {
                    textures.drain();
                    break;
                }
            }
        }
        super.memoryBarrier(barriers);
    }

    @Override public void followWindowDisplay() {
        if (vblank != null) vblank.retarget();
    }

    @Override public long awaitPresent() {
        if (!hasPresentWait()) return 0L;
        final long t0 = System.nanoTime();
        boolean waited;
        Tracy.beginZone(Z_PRESENT_WAIT);
        try {
            waited = swapchain.hasPresentWait() && swapchain.waitForPresent(SystemProperties.VULKAN_PRESENT_WAIT_DEPTH - 1, PRESENT_WAIT_TIMEOUT_NANOS);
            if (vblank != null && vblank.awaitPresented(lastFrameStartNanos, SystemProperties.VULKAN_PRESENT_WAIT_DEPTH - 1) > 0L) waited = true;
        } finally {
            Tracy.endZone();
        }
        return waited ? System.nanoTime() - t0 : 0L;
    }

    @Override public boolean hasPresentWait() {
        return swapchain != null && swapchain.fifo() && (swapchain.hasPresentWait() || vblank != null);
    }

    private long lastBlitRenderSerial;
    private long lastBlitNanos;
    private long lastBlitPeriodNanos;

    private void presentFromQueue(long acquireNanos) {
        final Tex color = defaultFb.color;
        renderer.materialize(color);
        images.transition(stream.cmd(), color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        stream.signalTimelineOnNextSubmit();
        presentSerials.enqueue(stream.submit(0L, 0, 0L));
        final long tBlit = System.nanoTime();
        final VkCommandBuffer cmd = presentQueue.begin();
        recordBlitToSwapchain(cmd, color);
        lastBlitRenderSerial = stream.lastSignaledSerial();
        lastBlitNanos = tBlit;
        lastBlitPeriodNanos = BackendDisplay.refreshPeriodNanos(window);
        final long index = presentQueue.submit(cmd, stream.timeline(), lastBlitRenderSerial, swapchain.acquireSemaphore, swapchain.presentSemaphore());
        swapchain.consumedBy(index);
        swapchain.present(presentQueue.queue());
        final long tEnd = System.nanoTime();
        if (paceSink != null) paceSink.gate(acquireNanos + tEnd - tBlit, tEnd);
        defaultFb.swap();
    }

    private void recordPresentBlit() {
        final Tex color = defaultFb.color;
        renderer.materialize(color);
        final VkCommandBuffer cmd = stream.cmd();
        images.transition(cmd, color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        recordBlitToSwapchain(cmd, color);
        color.lastUse = stream.currentSerial();
    }

    private void recordBlitToSwapchain(VkCommandBuffer cmd, Tex color) {
        final long image = swapchain.images[swapchain.imageIndex];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageMemoryBarrier.Buffer toDst = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            toDst.subresourceRange().set(Formats.ASPECT_COLOR, 0, 1, 0, 1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toDst);
            final VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().set(Formats.ASPECT_COLOR, 0, 0, 1);
            blit.srcOffsets(0).set(0, 0, 0);
            blit.srcOffsets(1).set(defaultFb.width, defaultFb.height, 1);
            blit.dstSubresource().set(Formats.ASPECT_COLOR, 0, 0, 1);
            blit.dstOffsets(0).set(0, swapchain.height, 0);
            blit.dstOffsets(1).set(swapchain.width, 0, 1);
            final boolean sameSize = defaultFb.width == swapchain.width && defaultFb.height == swapchain.height;
            vkCmdBlitImage(cmd, color.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
            final VkImageMemoryBarrier.Buffer toPresent = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            toPresent.subresourceRange().set(Formats.ASPECT_COLOR, 0, 1, 0, 1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, toPresent);
        }
    }

    @Override protected void destroyRings() {
        uniformRing.destroy();
        stagingRing.destroy();
    }

    private void widenByteIndices(long src, int count) {
        for (int i = 0; i < count; i++) MemoryUtil.memPutShort(uniformRing.address + i * 2L, (short) (MemoryUtil.memGetByte(src + i) & 0xFF));
    }

    void occlusionBegan() {
        occlusionPassBegan();
    }

    void occlusionEnding() {
        occlusionPassEnding();
    }

    private NativeOcclusion occlusionDevice, geometryDevice;

    /** A pool of one kind of native query: occlusion, or one pipeline statistic. */
    private final class NativeOcclusion implements OcclusionQueries.Device {
        private static final int SLOTS = 4096;
        private final long pool;
        private final boolean occlusion;

        NativeOcclusion(int type, int statistics, boolean available) {
            occlusion = type == VK_QUERY_TYPE_OCCLUSION;
            long created = 0L;
            if (gpu.hostQueryReset && available) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    final LongBuffer p = stack.mallocLong(1);
                    final VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(type).pipelineStatistics(statistics).queryCount(SLOTS);
                    if (vkCreateQueryPool(gpu.device, info, null, p) == VK_SUCCESS) created = p.get(0);
                }
            }
            pool = created;
        }

        @Override public int capacity() {
            return pool == 0L ? 0 : SLOTS;
        }

        @Override public void reset(int slot) {
            VK12.vkResetQueryPool(gpu.device, pool, slot, 1);
        }

        @Override public void begin(int slot) {
            vkCmdBeginQuery(stream.cmd(), pool, slot, occlusion && gpu.occlusionQueryPrecise ? VK_QUERY_CONTROL_PRECISE_BIT : 0);
        }

        @Override public void end(int slot) {
            vkCmdEndQuery(stream.cmd(), pool, slot);
        }

        @Override public long read(int slot) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final LongBuffer result = stack.mallocLong(1);
                vkGetQueryPoolResults(gpu.device, pool, slot, 1, result, 8L, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                return result.get(0);
            }
        }

        void destroy() {
            if (pool != 0L) vkDestroyQueryPool(gpu.device, pool, null);
        }
    }

    private void releasePartial(Throwable failure) {
        try {
            gpu.waitIdle();
            if (occlusionDevice != null) occlusionDevice.destroy();
            if (geometryDevice != null) geometryDevice.destroy();
            destroySharedResources();
        } catch (RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
        try {
            allocator.destroy();
        } catch (RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
    }

    @Override public void shutdown() {
        finishForShutdown();
        gpu.waitIdle();
        occlusionDevice.destroy();
        geometryDevice.destroy();
        if (presentQueue != null) presentQueue.destroy();
        presentQueue = null;
        if (swapchain != null) swapchain.destroy();
        swapchain = null;
        if (vblank != null) vblank.close();
        vblank = null;
        destroySharedResources();
        allocator.destroy();
    }

    @Override public String stats() {
        return String.format("presented=%d skipped=%d presentQueue=%b draws=%d passes=%d depthDiscards=%d setWrites=%d pipelines=%d uploads=%d (%.1f MiB, %d hoisted) renames=%d inlineCopies=%d hoistedCopies=%d mem=%s",
            presented, presentSkips, presentQueue != null, renderer.draws, renderer.passes, renderer.depthDiscards, renderer.setWrites, renderer.pipelines.pipelineCount(), textures.uploads, textures.uploadBytes / 1048576.0,
            textures.hoistedUploads, buffers.renames, buffers.inlineCopies, buffers.hoistedCopies, allocator.describe());
    }

    @Override protected int topology(int mode) {
        return Renderer.topology(mode);
    }

    @Override protected boolean flatShadesFromFirstVertex(GlState st) {
        return st.provokingFirst || !gpu.provokingVertexLast;
    }

    @Override protected boolean drawableMode(int mode) {
        final boolean adjacency = mode == GL32.GL_LINES_ADJACENCY || mode == GL32.GL_LINE_STRIP_ADJACENCY || mode == GL32.GL_TRIANGLES_ADJACENCY || mode == GL32.GL_TRIANGLE_STRIP_ADJACENCY;
        if (adjacency ? gpu.geometryShader || adjacencyToDrop(st(), mode) : mode != GL40.GL_PATCHES || gpu.tessellationShader) return true;
        warnOnce("primitive mode 0x" + Integer.toHexString(mode) + " needs a shader stage this device does not have");
        return false;
    }

    @Override protected boolean needsIndexConversion(int mode) {
        // A native fan would flat-shade a polygon from each triangle's own vertex rather than the polygon's first, and
        // without VK_EXT_provoking_vertex a fan's triangles from their first vertex rather than the last GL asks for.
        return mode == GL11.GL_LINE_LOOP || mode == GL11.GL_QUADS || mode == GL11.GL_QUAD_STRIP || mode == GL11.GL_POLYGON
            || mode == GL11.GL_TRIANGLE_FAN && (!gpu.triangleFans || !gpu.provokingVertexLast)
            || adjacencyToDrop(st(), mode) || flatOrderNeedsTurning(st(), mode);
    }

    @Override protected boolean prepareDrawCall(GlState st, int glMode, int primitive, boolean indexed) {
        return renderer.prepareDraw(st, glMode, primitive);
    }

    @Override protected void emitDraw(int primitive, int count, int instances, int first) {
        vkCmdDraw(stream.cmd(), count, instances, first, st().baseInstance);
    }

    @Override protected void emitDrawIndexed(int primitive, int count, int instances, int baseVertex) {
        final VkCommandBuffer cmd = stream.cmd();
        if (patternIndices != null) {
            buffers.use(patternIndices, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
            renderer.bindIndexBuffer(cmd, patternIndices.handle(), 0L, VK_INDEX_TYPE_UINT32);
            patternIndices = null;
        } else {
            renderer.bindIndexBuffer(cmd, ringIndexBuffer, ringIndexOffset, VK_INDEX_TYPE_UINT32);
        }
        vkCmdDrawIndexed(cmd, count, instances, 0, baseVertex, st().baseInstance);
    }

    /** A buffer of stored indices the next indexed draw reads, in place of the ring. */
    private Buf patternIndices;

    @Override protected void bindPatternIndices(Buf buffer) {
        patternIndices = buffer;
    }

    @Override protected void bindRingIndices(long bytes, int indexType) {
        patternIndices = null;
        // Preparing the draw may allocate from the ring and start a pass, so the binding waits for the draw itself.
        ringIndexBuffer = uniformRing.buffer;
        ringIndexOffset = uniformRing.offset;
    }

    @Override public HostRingBase<?> uniformRing() {
        return uniformRing;
    }

    private int vkIndexType(int type) {
        return switch (type) {
            case GL11.GL_UNSIGNED_SHORT -> VK_INDEX_TYPE_UINT16;
            case GL11.GL_UNSIGNED_BYTE -> EXTIndexTypeUint8.VK_INDEX_TYPE_UINT8_EXT;
            default -> VK_INDEX_TYPE_UINT32;
        };
    }

    private boolean bindIndices(GlState st, VkCommandBuffer cmd, int count, int type, long offset) {
        final int bytes = indexBytes(type);
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        // Client-memory indices go through the per-frame ring; byte indices are widened to 16-bit when the device cannot take them.
        if (ebo == null || ebo.alloc == null) {
            if (offset == 0L) return false;
            final long size = (long) count * bytes;
            final boolean widen = type == GL11.GL_UNSIGNED_BYTE && !gpu.indexTypeUint8;
            uniformRing.alloc(widen ? count * 2L : size, 4);
            if (widen) {
                widenByteIndices(offset, count);
            } else {
                MemoryUtil.memCopy(offset, uniformRing.address, size);
            }
            renderer.bindIndexBuffer(cmd, uniformRing.buffer, uniformRing.offset, widen ? VK_INDEX_TYPE_UINT16 : vkIndexType(type));
            return true;
        }
        buffers.use(ebo, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
        if (type == GL11.GL_UNSIGNED_BYTE && !gpu.indexTypeUint8) {
            if (!ebo.hostVisible()) {
                warnOnce("byte indices in a device-local buffer without VK_EXT_index_type_uint8");
                return false;
            }
            uniformRing.alloc(count * 2L, 4);
            widenByteIndices(ebo.address() + offset, count);
            renderer.bindIndexBuffer(cmd, uniformRing.buffer, uniformRing.offset, VK_INDEX_TYPE_UINT16);
            return true;
        }
        if ((offset & (bytes - 1)) != 0) {
            warnOnce("misaligned index offset " + offset);
            return false;
        }
        renderer.bindIndexBuffer(cmd, ebo.handle(), offset, vkIndexType(type));
        return true;
    }

    @Override public void drawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int baseVertex) {
        st().drawBaseVertex = baseVertex;
        noteElementStatistics(st(), mode, count, type, indices, primcount);
        if (culledElements(st(), mode, count, type, indices, primcount, baseVertex)) return;
        GlTransformFeedback.noteDraw(mode, 0, count, primcount, st().baseInstance, st().conditionalSkip);
        if (count <= 0 || primcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        count = indicesInBuffer(st, count, type, indices);
        if (count <= 0) return;
        checkRestartIndex(st, type);
        if (restartNeedsReading(st, mode, type)) {
            drawElementsRestarting(st, mode, count, type, indices, primcount, baseVertex);
            return;
        }
        if (needsIndexConversion(mode)) {
            drawConvertedElements(st, mode, count, type, indices, primcount, baseVertex);
            return;
        }
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        if (!bindIndices(st, cmd, count, type, indices)) return;
        vkCmdDrawIndexed(cmd, count, primcount, 0, baseVertex, st.baseInstance);
    }

    @Override public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        if (drawcount <= 0) return;
        final GlState st = st();
        if (culling(st)) {
            for (int i = 0; i < drawcount; i++) {
                st.drawId = i;
                drawElementsInstancedBaseVertex(mode, MemoryUtil.memGetInt(pCount + i * 4L), type, MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE), 1,
                    pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L));
            }
            st.drawId = 0;
            return;
        }
        if (!drawableMode(mode)) return;
        checkRestartIndex(st, type);
        if (needsIndexConversion(mode)) {
            drawEachConverted(st, mode, pCount, type, pIndices, drawcount, pBaseVertex);
            return;
        }
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        final int bytes = indexBytes(type);
        if (ebo != null && ebo.alloc != null && (type != GL11.GL_UNSIGNED_BYTE || gpu.indexTypeUint8)) {
            buffers.use(ebo, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
            renderer.bindIndexBuffer(cmd, ebo.handle(), 0, vkIndexType(type));
            for (int i = 0; i < drawcount; i++) {
                final int c = MemoryUtil.memGetInt(pCount + i * 4L);
                if (c <= 0) continue;
                final long off = MemoryUtil.memGetAddress(pIndices + (long) i * Pointer.POINTER_SIZE);
                if ((off & (bytes - 1)) != 0) {
                    warnOnce("misaligned index offset " + off);
                    continue;
                }
                final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
                vkCmdDrawIndexed(cmd, c, 1, (int) (off / bytes), base, 0);
            }
            return;
        }
        for (int i = 0; i < drawcount; i++) {
            final int c = MemoryUtil.memGetInt(pCount + i * 4L);
            if (c <= 0) continue;
            final long off = MemoryUtil.memGetAddress(pIndices + (long) i * Pointer.POINTER_SIZE);
            final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
            if (!bindIndices(st, cmd, c, type, off)) continue;
            vkCmdDrawIndexed(cmd, c, 1, 0, base, 0);
        }
    }

    @Override public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        if (drawcount <= 0) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        if (ib == null || ib.alloc == null) {
            warnOnce("indirect draw without a GL_DRAW_INDIRECT_BUFFER");
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (!indirectInRange(ib, indirect, (drawcount - 1L) * (stride == 0 ? 20 : stride) + 20)) return;
        if (readsIndirectCommands(mode, type)) {
            drawElementsIndirectByReading(mode, type, indirect, drawcount, stride);
            return;
        }
        if (!canDrawIndirect(mode, type)) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) return;
        buffers.use(ebo, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
        buffers.use(ib, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        renderer.bindIndexBuffer(cmd, ebo.handle(), 0, vkIndexType(type));
        final int s = stride == 0 ? 20 : stride;
        if (gpu.multiDrawIndirect) {
            vkCmdDrawIndexedIndirect(cmd, ib.handle(), indirect, drawcount, s);
        } else {
            for (int i = 0; i < drawcount; i++) vkCmdDrawIndexedIndirect(cmd, ib.handle(), indirect + (long) i * s, 1, s);
        }
    }

    @Override public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        final GlState st = st();
        if (!gpu.drawIndirectCount || readsIndirectCommands(mode, type)) {
            multiDrawElementsIndirect(mode, type, indirect, parameterCount(drawcount, maxdrawcount), stride);
            return;
        }
        final Buf ib = buffers.get(st.drawIndirect);
        final Buf cb = buffers.get(st.parameterBuffer);
        if (ib == null || cb == null || ib.alloc == null || cb.alloc == null || !gpu.drawIndirectCount) return;
        if (!canDrawIndirect(mode, type)) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) return;
        buffers.use(ebo, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
        buffers.use(ib, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        buffers.use(cb, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        renderer.bindIndexBuffer(cmd, ebo.handle(), 0, vkIndexType(type));
        VK12.vkCmdDrawIndexedIndirectCount(cmd, ib.handle(), indirect, cb.handle(), drawcount, maxdrawcount, stride == 0 ? 20 : stride);
    }

    @Override public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        final GlState st = st();
        if (!gpu.drawIndirectCount || readsIndirectCommands(mode, 0)) {
            drawArraysIndirectByReading(mode, indirect, parameterCount(drawcount, maxdrawcount), stride);
            return;
        }
        final Buf ib = buffers.get(st.drawIndirect);
        final Buf cb = buffers.get(st.parameterBuffer);
        if (ib == null || cb == null || ib.alloc == null || cb.alloc == null || !gpu.drawIndirectCount) return;
        if (!canDrawIndirect(mode, 0)) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        buffers.use(ib, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        buffers.use(cb, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        VK12.vkCmdDrawIndirectCount(cmd, ib.handle(), indirect, cb.handle(), drawcount, maxdrawcount, stride == 0 ? 16 : stride);
    }

    @Override protected void dispatch(int x, int y, int z) {
        if (!dispatchable(x, y, z)) return;
        if (!renderer.prepareDispatch(st())) return;
        vkCmdDispatch(stream.cmd(), x, y, z);
        renderer.pendingComputeBarrier = true;
    }

    @Override protected void dispatchIndirect(long offset) {
        final GlState st = st();
        final Buf b = buffers.get(st.dispatchIndirect);
        if (b == null || b.alloc == null) {
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (!indirectInRange(b, offset, 12)) return;
        if (!renderer.prepareDispatch(st)) return;
        buffers.use(b, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        vkCmdDispatchIndirect(stream.cmd(), b.handle(), offset);
        renderer.pendingComputeBarrier = true;
    }

    @Override protected void resolveImage(Tex s, int sLayer, Tex d, int dLevel, int dLayer, int x, int y, int dx, int dy, int width, int height) {
        final VkCommandBuffer cmd = stream.cmd();
        images.transitionForCopy(cmd, s, d);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageResolve.Buffer r = VkImageResolve.calloc(1, stack);
            r.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, sLayer, 1);
            r.srcOffset().set(x, y, 0);
            r.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, dLevel, d.is3D() ? 0 : dLayer, 1);
            r.dstOffset().set(dx, dy, d.is3D() ? dLayer : 0);
            r.extent().set(width, height, 1);
            vkCmdResolveImage(cmd, s.image, Images.copySourceLayout(false), d.image, Images.copyDestinationLayout(false), r);
        }
        d.needsClear = false;
    }

    @Override protected void resolveDepthStencil(Tex s, int sLayer, Tex d, int dLevel, int dLayer, int aspect, int x, int y, int dx, int dy, int width, int height) {
        if (x != dx || y != dy) {
            warnOnce("a resolve of depth or stencil that moves the image is not supported");
            return;
        }
        final VkCommandBuffer cmd = stream.cmd();
        final int layout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        images.transition(cmd, s, layout);
        images.transition(cmd, d, layout);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(images.attachmentView(s, 0, sLayer, 1)).imageLayout(layout)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .resolveMode(VK12.VK_RESOLVE_MODE_SAMPLE_ZERO_BIT).resolveImageView(images.attachmentView(d, dLevel, dLayer, 1)).resolveImageLayout(layout);
            final VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1);
            info.renderArea().offset().set(x, y);
            info.renderArea().extent().set(width, height);
            if ((s.format.aspect() & Formats.ASPECT_DEPTH) != 0) info.pDepthAttachment(depth);
            if ((s.format.aspect() & Formats.ASPECT_STENCIL) != 0) info.pStencilAttachment(depth);
            renderer.renderEmptyPass(cmd, info);
        }
        d.needsClear = false;
    }

    @Override protected void blitClipped(Tex s, int sLevel, int sLayer, Tex d, int dLevel, int dLayer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, boolean linear) {
        if (s != d) {
            final boolean whole = d.levels == 1 && d.layers == 1 && !d.is3D() && dLevel == 0 && dLayer == 0 && aspect == d.format.aspect()
                && Math.min(dx0, dx1) == 0 && Math.max(dx0, dx1) == d.levelWidth(dLevel) && Math.min(dy0, dy1) == 0 && Math.max(dy0, dy1) == d.levelHeight(dLevel);
            if (whole) {
                d.pendingClear = false;
                d.needsClear = false;
            } else {
                renderer.materialize(d);
            }
        }
        final VkCommandBuffer cmd = stream.cmd();
        final boolean same = s == d;
        final boolean ds = aspect != Formats.ASPECT_COLOR;
        final boolean sameSize = Math.abs(sx1 - sx0) == Math.abs(dx1 - dx0) && Math.abs(sy1 - sy0) == Math.abs(dy1 - dy0) && (sx1 > sx0) == (dx1 > dx0) && (sy1 > sy0) == (dy1 > dy0);
        images.transitionForCopy(cmd, s, d);
        final int srcLayout = Images.copySourceLayout(same);
        final int dstLayout = Images.copyDestinationLayout(same);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (sameSize && s.format.pf() == d.format.pf()) {
                final VkImageCopy.Buffer r = VkImageCopy.calloc(1, stack);
                r.srcSubresource().set(aspect, sLevel, s.is3D() ? 0 : sLayer, 1);
                r.srcOffset().set(Math.min(sx0, sx1), Math.min(sy0, sy1), s.is3D() ? sLayer : 0);
                r.dstSubresource().set(aspect, dLevel, d.is3D() ? 0 : dLayer, 1);
                r.dstOffset().set(Math.min(dx0, dx1), Math.min(dy0, dy1), d.is3D() ? dLayer : 0);
                r.extent().set(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0), 1);
                vkCmdCopyImage(cmd, s.image, srcLayout, d.image, dstLayout, r);
            } else if (ds && s.format.pf() != d.format.pf()) {
                warnOnce("depth blit between different formats");
            } else {
                final VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                final int sz = s.is3D() ? sLayer : 0, dz = d.is3D() ? dLayer : 0;
                b.srcSubresource().set(aspect, sLevel, s.is3D() ? 0 : sLayer, 1);
                b.srcOffsets(0).set(sx0, sy0, sz);
                b.srcOffsets(1).set(sx1, sy1, sz + 1);
                b.dstSubresource().set(aspect, dLevel, d.is3D() ? 0 : dLayer, 1);
                b.dstOffsets(0).set(dx0, dy0, dz);
                b.dstOffsets(1).set(dx1, dy1, dz + 1);
                final boolean canLinear = linear && !ds && images.supports(VkFormats.of(s.format.pf()), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
                vkCmdBlitImage(cmd, s.image, srcLayout, d.image, dstLayout, b, canLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            }
        }
        final long serial = stream.currentSerial();
        s.lastUse = serial;
        d.markGpuWrite(serial);
    }

    @Override protected String vendorString() {
        if (gpu.isNvidia()) return "NVIDIA Corporation";
        if (gpu.isAmd()) return "AMD";
        if (gpu.isIntel()) return "Intel";
        if (gpu.isMoltenVkOrApple()) return "Apple";
        return "Vulkan";
    }

    @Override protected long deviceLocalBytes() {
        long total = 0;
        for (int i = 0; i < gpu.memoryProperties.memoryHeapCount(); i++) {
            if ((gpu.memoryProperties.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) total = Math.max(total, gpu.memoryProperties.memoryHeaps(i).size());
        }
        return total;
    }

    @Override public TexturesBase<Tex> textures() {
        return textures;
    }

    @Override protected ProgramsBase<Program> programs() {
        return programs;
    }

    @Override public BuffersBase<Buf> buffers() {
        return buffers;
    }

    @Override public GpuCaps gpuCaps() {
        return gpu;
    }

    @Override public CommandStream stream() {
        return stream;
    }

    @Override public Renderer rendererBase() {
        return renderer;
    }

    @Override protected void debugPush(String message) {
        debug.push(message);
    }

    @Override protected void debugPop() {
        debug.pop();
    }

    @Override protected Tex proxyDefaults() {
        return PROXY_DEFAULTS;
    }

    @Override protected String apiName() {
        return "Vulkan";
    }

    @Override protected String apiLabel() {
        return "Vulkan";
    }

    @Override protected String apiVersion() {
        return Gpu.versionString(gpu.apiVersion);
    }

    @Override protected void submitNow() {
        stream.submit(0L, 0, 0L);
    }

    @Override protected void debugNameImage(long image, String label) {
        debug.name(VK_OBJECT_TYPE_IMAGE, image, label);
    }

    @Override protected void clearImage(Tex t) {
        images.clearImage(stream.cmd(), t, new float[4], 0f, 0);
    }

    @Override protected long allocatedBytes() {
        return allocator.allocatedBytes();
    }

    @Override public void releaseImage(Tex t) {
        images.release(t);
    }

    @Override public void createImage(Tex t, boolean storage) {
        images.create(t, storage);
    }

    @Override protected TargetBase<Tex> blitTarget() {
        return blitDst;
    }

    @Override public void quiesceForSwapchainChange() {
        finish();
        gpu.waitIdle();
        if (presentQueue != null) presentQueue.poll();
    }
}
