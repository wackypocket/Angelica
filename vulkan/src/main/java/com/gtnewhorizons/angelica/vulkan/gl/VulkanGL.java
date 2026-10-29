package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;

import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;

import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;

import com.gtnewhorizons.angelica.glemu.gl.GlState;

import com.gtnewhorizons.angelica.vulkan.resource.VkFormats;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.Swapchain;
import com.gtnewhorizons.angelica.vulkan.frame.CommandStream;
import com.gtnewhorizons.angelica.vulkan.frame.HostRing;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.pipeline.ClearPass;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import com.gtnewhorizons.angelica.vulkan.resource.Buffers;
import com.gtnewhorizons.angelica.vulkan.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.vulkan.resource.Images;
import com.gtnewhorizons.angelica.vulkan.resource.Target;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import com.gtnewhorizons.angelica.vulkan.resource.Textures;
import com.gtnewhorizons.angelica.vulkan.shader.Program;
import com.gtnewhorizons.angelica.vulkan.shader.Programs;
import com.gtnewhorizons.angelica.vulkan.util.DebugNames;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.io.File;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanGL extends GlBase<Tex, Program, Buf> {
    private static final Tracy.ZoneId Z_PRESENT = Tracy.zoneId("vkPresentFrame", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_FRAME_SLOT_WAIT = Tracy.zoneId("vkFrameSlotWait", Tracy.COLOR_SWAP);

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
    final ClearPass clearPass;
    public final DebugNames debug;

    private Swapchain swapchain;
    private final int framesInFlight = SystemProperties.VULKAN_FRAMES_IN_FLIGHT;

    public VulkanGL(Gpu gpu, Thread mainThread, File dataDir) {
        this.gpu = gpu;
        this.dataDir = dataDir;
        this.mainThread = mainThread;
        this.allocator = new MemoryAllocator(gpu);
        this.stream = new CommandStream(gpu);
        this.uniformRing = new HostRing(gpu, allocator, stream, 8L << 20, MemoryAllocator.USAGE_STREAM);
        this.stagingRing = new HostRing(gpu, allocator, stream, 32L << 20, MemoryAllocator.USAGE_UPLOAD);
        this.images = new Images(this);
        this.debug = new DebugNames(this, SystemProperties.VULKAN_DEBUG);
        this.textures = new Textures(this);
        this.buffers = new Buffers(this);
        this.programs = new Programs(this);
        this.renderer = new Renderer(this, dataDir == null ? null : new File(dataDir, "cache" + File.separator + "vulkan-pipelines-" + Integer.toHexString(gpu.vendorId) + "-" + Integer.toHexString(gpu.driverVersion) + ".bin"));
        this.defaultFb = new DefaultFramebuffer(this);
        this.clearPass = new ClearPass(this);
        this.mainState = new GlState(mainThread);
        stream.setBeforeSubmit(() -> {
            renderer.endPass();
            debug.closeAll();
            textures.flushUploads();
        });
        textures.pickDepthFormats();
        renderer.init();
        allocator.setReclaim(() -> flushAndWait(stream.currentSerial()));
    }

    @Override public void warnOnce(String msg) {
        synchronized (warned) {
            if (!warned.add(msg)) return;
        }
        LOG.warn("{} GL: {}", apiName(), msg);
    }

    int vertexFormatFallback(int vk) {
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
        if (widened != vk && (images.bufferFeatures(widened) & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0) return widened;
        warnOnce("vertex format " + vk + " unsupported by the device");
        return vk;
    }

    @Override public void flushAndWait(long serial) {
        renderer.endPass();
        if (serial >= stream.currentSerial()) {
            images.globalBarrier(stream.cmd(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
        }
        stream.waitFor(serial);
    }

    public void attachWindow(long window, long surface) {
        this.window = window;
        this.swapchain = new Swapchain(gpu, surface);
        final long size = windowPixels();
        defaultFb.ensure((int) (size >>> 32), (int) size);
        final VSyncMode v = pendingVSync;
        if (v != null) swapchain.setVSync(v);
    }

    public boolean hasWindow() {
        return swapchain != null;
    }

    public VSyncMode setVSync(VSyncMode preferred) {
        pendingVSync = preferred;
        if (swapchain == null) return preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        return swapchain.setVSync(preferred);
    }

    public boolean holdsToVblank() {
        return swapchain == null || swapchain.holdsToVblank();
    }

    public boolean supportsVSync(VSyncMode mode) {
        return swapchain != null && swapchain.supports(mode);
    }

    public int swapchainFormat() {
        return swapchain == null ? VK_FORMAT_UNDEFINED : swapchain.format;
    }

    @Override public long awaitFrameSlot() {
        if (presentSerials.size() < framesInFlight) return 0L;
        final long t0 = System.nanoTime();
        Tracy.beginZone(Z_FRAME_SLOT_WAIT);
        try {
            while (presentSerials.size() >= framesInFlight) stream.waitFor(presentSerials.dequeueLong());
        } finally {
            Tracy.endZone();
        }
        final long waited = System.nanoTime() - t0;
        return waited;
    }

    public void present() {
        Tracy.beginZone(Z_PRESENT);
        try {
            presentFrame();
        } finally {
            Tracy.endZone();
        }
    }

    private void presentFrame() {
        frames++;
        final long dump = SystemProperties.VULKAN_DUMP_FRAME;
        if (frames == dump || dump > 0 && SystemProperties.VULKAN_DUMP_REPEAT && frames % dump == 0) dumpBackBuffer();
        renderer.endPass(true);
        final long px = windowPixels();
        final int ww = (int) (px >>> 32), wh = (int) px;
        boolean presented = false;
        final long t0 = System.nanoTime();
        awaitFrameSlot();
        final long t1 = System.nanoTime();
        if (paceSink != null) paceSink.gpuWait(t1 - t0);
        if (swapchain != null && ww > 0 && wh > 0 && swapchain.ensure(ww, wh)) {
            int r = swapchain.acquire(stream.completedSerial());
            if (r == Swapchain.REBUILD && swapchain.ensure(ww, wh)) r = swapchain.acquire(stream.completedSerial());
            final long tAcquired = System.nanoTime();
            if (r == Swapchain.ACQUIRED) {
                if (swapchain.fifo()) submitNow();
                recordPresentBlit();
                final long tBlit = System.nanoTime();
                final long serial = stream.submit(swapchain.acquireSemaphore, VK_PIPELINE_STAGE_TRANSFER_BIT, swapchain.presentSemaphore());
                final long tEnd = System.nanoTime();
                if (paceSink != null) paceSink.gate(tAcquired - t0 + tEnd - tBlit, tEnd);
                swapchain.consumedBy(serial);
                swapchain.present();
                presentSerials.enqueue(serial);
                presented = true;
            }
        }
        if (presented) {
            this.presented++;
        } else {
            presentSkips++;
            presentSerials.enqueue(stream.submit(0L, 0, 0L));
        }
        final long now = System.nanoTime();
        if (SystemProperties.VULKAN_STATS && now - lastStatsNanos > 10_000_000_000L) {
            lastStatsNanos = now;
            LOG.info("Vulkan frame {} on {}: window {}x{}, back buffer {}x{}, {}", frames, Thread.currentThread().getName(), ww, wh, defaultFb.width, defaultFb.height, stats());
        }
        if (ww > 0 && wh > 0 && (ww != defaultFb.width || wh != defaultFb.height)) defaultFb.ensure(ww, wh);
        if ((frames & 255) == 0) {
            stagingRing.trim(2);
            buffers.trim();
            allocator.trim();
        }
    }

    public long awaitPresent() {
        return 0L;
    }

    public boolean hasPresentWait() {
        return false;
    }

    private void recordPresentBlit() {
        final Tex color = defaultFb.color;
        renderer.materialize(color);
        final VkCommandBuffer cmd = stream.cmd();
        images.transition(cmd, color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
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
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            toPresent.subresourceRange().set(Formats.ASPECT_COLOR, 0, 1, 0, 1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, toPresent);
        }
        color.lastUse = stream.currentSerial();
    }

    public void shutdown() {
        try {
            renderer.endPass();
            stream.finish();
        } catch (Throwable t) {
            LOG.warn("Vulkan shutdown wait failed", t);
        }
        gpu.waitIdle();
        if (swapchain != null) swapchain.destroy();
        swapchain = null;
        renderer.destroy();
        programs.destroy();
        textures.destroy();
        buffers.destroy();
        defaultFb.destroy();
        stream.destroy();
        uniformRing.destroy();
        stagingRing.destroy();
        allocator.destroy();
    }

    @Override public String stats() {
        return String.format("presented=%d skipped=%d draws=%d passes=%d depthDiscards=%d setWrites=%d pipelines=%d uploads=%d (%.1f MiB, %d hoisted) renames=%d inlineCopies=%d hoistedCopies=%d mem=%s",
            presented, presentSkips, renderer.draws, renderer.passes, renderer.depthDiscards, renderer.setWrites, renderer.pipelines.count(), textures.uploads, textures.uploadBytes / 1048576.0,
            textures.hoistedUploads, buffers.renames, buffers.inlineCopies, buffers.hoistedCopies, allocator.describe());
    }

    private int topology(int mode) {
        return Renderer.topology(mode, gpu.triangleFans);
    }

    private boolean needsIndexConversion(int mode) {
        return mode == GL11.GL_LINE_LOOP || mode == GL11.GL_QUADS || mode == GL11.GL_QUAD_STRIP
            || (mode == GL11.GL_TRIANGLE_FAN || mode == GL11.GL_POLYGON) && !gpu.triangleFans;
    }

    public void drawArraysInstanced(int mode, int first, int count, int primcount) {
        if (count <= 0 || primcount <= 0) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            drawConvertedArrays(st, mode, first, count, primcount);
            return;
        }
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        vkCmdDraw(stream.cmd(), count, primcount, first, 0);
    }

    public void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {
        final int n = Math.min(firsts.remaining(), counts.remaining());
        if (n == 0) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            for (int i = 0; i < n; i++) drawConvertedArrays(st, mode, firsts.get(firsts.position() + i), counts.get(counts.position() + i), 1);
            return;
        }
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        for (int i = 0; i < n; i++) {
            final int c = counts.get(counts.position() + i);
            if (c > 0) vkCmdDraw(cmd, c, 1, firsts.get(firsts.position() + i), 0);
        }
    }

    private void drawConvertedArrays(GlState st, int mode, int first, int count, int instances) {
        final int[] idx = convertedIndices(mode, count);
        if (idx.length == 0) return;
        uniformRing.alloc(idx.length * 4L, 4);
        for (int i = 0; i < idx.length; i++) MemoryUtil.memPutInt(uniformRing.address + i * 4L, idx[i]);
        final long buffer = uniformRing.buffer, offset = uniformRing.offset;
        final int convMode = convertedMode(mode);
        if (!renderer.prepareDraw(st, convMode, Renderer.topology(convMode, true))) return;
        final VkCommandBuffer cmd = stream.cmd();
        renderer.bindIndexBuffer(cmd, buffer, offset, VK_INDEX_TYPE_UINT32);
        vkCmdDrawIndexed(cmd, idx.length, instances, 0, first, 0);
    }

    private int vkIndexType(int type) {
        return switch (type) {
            case GL11.GL_UNSIGNED_SHORT -> VK_INDEX_TYPE_UINT16;
            case GL11.GL_UNSIGNED_BYTE -> org.lwjgl.vulkan.EXTIndexTypeUint8.VK_INDEX_TYPE_UINT8_EXT;
            default -> VK_INDEX_TYPE_UINT32;
        };
    }

    private boolean bindIndices(GlState st, VkCommandBuffer cmd, int count, int type, long offset) {
        final int bytes = indexBytes(type);
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) {
            if (offset == 0L) return false;
            final long size = (long) count * bytes;
            final boolean widen = type == GL11.GL_UNSIGNED_BYTE && !gpu.indexTypeUint8;
            uniformRing.alloc(widen ? count * 2L : size, 4);
            if (widen) {
                for (int i = 0; i < count; i++) MemoryUtil.memPutShort(uniformRing.address + i * 2L, (short) (MemoryUtil.memGetByte(offset + i) & 0xFF));
            } else {
                MemoryUtil.memCopy(offset, uniformRing.address, size);
            }
            return renderer.bindIndexBuffer(cmd, uniformRing.buffer, uniformRing.offset, widen ? VK_INDEX_TYPE_UINT16 : vkIndexType(type));
        }
        buffers.use(ebo, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
        if (type == GL11.GL_UNSIGNED_BYTE && !gpu.indexTypeUint8) {
            if (!ebo.hostVisible()) {
                warnOnce("byte indices in a device-local buffer without VK_EXT_index_type_uint8");
                return false;
            }
            uniformRing.alloc(count * 2L, 4);
            final long src = ebo.address() + offset;
            for (int i = 0; i < count; i++) MemoryUtil.memPutShort(uniformRing.address + i * 2L, (short) (MemoryUtil.memGetByte(src + i) & 0xFF));
            return renderer.bindIndexBuffer(cmd, uniformRing.buffer, uniformRing.offset, VK_INDEX_TYPE_UINT16);
        }
        if ((offset & (bytes - 1)) != 0) {
            warnOnce("misaligned index offset " + offset);
            return false;
        }
        return renderer.bindIndexBuffer(cmd, ebo.handle(), offset, vkIndexType(type));
    }

    @Override public void drawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int baseVertex) {
        if (count <= 0 || primcount <= 0) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            drawConvertedElements(st, mode, count, type, indices, primcount, baseVertex);
            return;
        }
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        if (!bindIndices(st, cmd, count, type, indices)) return;
        vkCmdDrawIndexed(cmd, count, primcount, 0, baseVertex, 0);
    }

    private void drawConvertedElements(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        final long src;
        if (ebo != null && ebo.alloc != null) {
            if (!ebo.hostVisible()) {
                warnOnce("index conversion for mode " + mode + " needs a host-visible element buffer");
                return;
            }
            src = ebo.address() + indices;
        } else {
            src = indices;
        }
        if (src == 0L) return;
        final int bytes = indexBytes(type);
        final int[] pattern = convertedIndices(mode, count);
        uniformRing.alloc(pattern.length * 4L, 4);
        for (int i = 0; i < pattern.length; i++) {
            final long at = src + (long) pattern[i] * bytes;
            final int v = switch (bytes) {
                case 1 -> MemoryUtil.memGetByte(at) & 0xFF;
                case 2 -> MemoryUtil.memGetShort(at) & 0xFFFF;
                default -> MemoryUtil.memGetInt(at);
            };
            MemoryUtil.memPutInt(uniformRing.address + i * 4L, v);
        }
        final long buffer = uniformRing.buffer, offset = uniformRing.offset;
        final int convMode = convertedMode(mode);
        if (!renderer.prepareDraw(st, convMode, Renderer.topology(convMode, true))) return;
        final VkCommandBuffer cmd = stream.cmd();
        renderer.bindIndexBuffer(cmd, buffer, offset, VK_INDEX_TYPE_UINT32);
        vkCmdDrawIndexed(cmd, pattern.length, instances, 0, baseVertex, 0);
    }

    public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        if (drawcount <= 0) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            for (int i = 0; i < drawcount; i++) {
                drawConvertedElements(st, mode, MemoryUtil.memGetInt(pCount + i * 4L), type, MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE), 1,
                    pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L));
            }
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
                final long off = MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
                final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
                vkCmdDrawIndexed(cmd, c, 1, (int) (off / bytes), base, 0);
            }
            return;
        }
        for (int i = 0; i < drawcount; i++) {
            final int c = MemoryUtil.memGetInt(pCount + i * 4L);
            if (c <= 0) continue;
            final long off = MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
            final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
            if (!bindIndices(st, cmd, c, type, off)) continue;
            vkCmdDrawIndexed(cmd, c, 1, 0, base, 0);
        }
    }

    public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        if (drawcount <= 0) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        if (ib == null || ib.alloc == null) {
            warnOnce("indirect draw without a GL_DRAW_INDIRECT_BUFFER");
            return;
        }
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

    public void multiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) {
        if (drawcount <= 0) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        if (ib == null || ib.alloc == null) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        buffers.use(ib, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        final int s = stride == 0 ? 16 : stride;
        if (gpu.multiDrawIndirect) {
            vkCmdDrawIndirect(cmd, ib.handle(), indirect, drawcount, s);
        } else {
            for (int i = 0; i < drawcount; i++) vkCmdDrawIndirect(cmd, ib.handle(), indirect + (long) i * s, 1, s);
        }
    }

    public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        final Buf cb = buffers.get(st.parameterBuffer);
        if (ib == null || cb == null || ib.alloc == null || cb.alloc == null || !gpu.drawIndirectCount) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) return;
        buffers.use(ebo, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
        buffers.use(ib, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        buffers.use(cb, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        renderer.bindIndexBuffer(cmd, ebo.handle(), 0, vkIndexType(type));
        org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount(cmd, ib.handle(), indirect, cb.handle(), drawcount, maxdrawcount, stride == 0 ? 20 : stride);
    }

    public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        final Buf cb = buffers.get(st.parameterBuffer);
        if (ib == null || cb == null || ib.alloc == null || cb.alloc == null || !gpu.drawIndirectCount) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final VkCommandBuffer cmd = stream.cmd();
        buffers.use(ib, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        buffers.use(cb, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        org.lwjgl.vulkan.VK12.vkCmdDrawIndirectCount(cmd, ib.handle(), indirect, cb.handle(), drawcount, maxdrawcount, stride == 0 ? 16 : stride);
    }

    public void dispatchCompute(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) return;
        if (!renderer.prepareDispatch(st())) return;
        vkCmdDispatch(stream.cmd(), x, y, z);
        renderer.pendingComputeBarrier = true;
    }

    public void dispatchComputeIndirect(long offset) {
        final GlState st = st();
        final Buf b = buffers.get(st.dispatchIndirect);
        if (b == null || b.alloc == null) return;
        if (!renderer.prepareDispatch(st)) return;
        buffers.use(b, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
        vkCmdDispatchIndirect(stream.cmd(), b.handle(), offset);
        renderer.pendingComputeBarrier = true;
    }

    private static final Tex PROXY_DEFAULTS = new Tex(-1, false);

    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                 int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                 int srcWidth, int srcHeight, int srcDepth) {
        final Tex src = srcTarget == GL30.GL_RENDERBUFFER ? textures.attachment(srcName, true) : textures.get(srcName);
        final Tex dst = dstTarget == GL30.GL_RENDERBUFFER ? textures.attachment(dstName, true) : textures.get(dstName);
        textures.copyImage(src, srcLevel, srcX, srcY, srcZ, dst, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, Math.max(1, srcDepth));
    }

    private final Target blitDst = new Target();

    @Override protected void blitImage(Tex s, int sLevel, int sLayer, Tex d, int dLevel, int dLayer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, boolean linear) {
        if (s == null || d == null || !s.hasImage() || !d.hasImage()) return;
        final int sw = s.levelWidth(sLevel), sh = s.levelHeight(sLevel), dw = d.levelWidth(dLevel), dh = d.levelHeight(dLevel);
        final int[] x = {sx0, sx1, dx0, dx1}, y = {sy0, sy1, dy0, dy1};
        if (!clipBlitAxis(x, sw, dw) || !clipBlitAxis(y, sh, dh)) return;
        sx0 = x[0]; sx1 = x[1]; dx0 = x[2]; dx1 = x[3];
        sy0 = y[0]; sy1 = y[1]; dy0 = y[2]; dy1 = y[3];
        renderer.materialize(s);
        if (s != d) {
            final boolean whole = d.levels == 1 && d.layers == 1 && !d.is3D() && dLevel == 0 && dLayer == 0 && aspect == d.format.aspect()
                && Math.min(dx0, dx1) == 0 && Math.max(dx0, dx1) == dw && Math.min(dy0, dy1) == 0 && Math.max(dy0, dy1) == dh;
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
        if (same) {
            images.transition(cmd, s, VK_IMAGE_LAYOUT_GENERAL);
        } else {
            images.transition(cmd, s, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            images.transition(cmd, d, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
        final int srcLayout = same ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        final int dstLayout = same ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (sameSize && (s.format.pf() == d.format.pf() || ds && s.format.pf() == d.format.pf())) {
                final org.lwjgl.vulkan.VkImageCopy.Buffer r = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
                r.srcSubresource().set(aspect, sLevel, sLayer, 1);
                r.srcOffset().set(Math.min(sx0, sx1), Math.min(sy0, sy1), 0);
                r.dstSubresource().set(aspect, dLevel, dLayer, 1);
                r.dstOffset().set(Math.min(dx0, dx1), Math.min(dy0, dy1), 0);
                r.extent().set(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0), 1);
                vkCmdCopyImage(cmd, s.image, srcLayout, d.image, dstLayout, r);
            } else if (ds && s.format.pf() != d.format.pf()) {
                warnOnce("depth blit between different formats");
            } else {
                final VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                b.srcSubresource().set(aspect, sLevel, sLayer, 1);
                b.srcOffsets(0).set(sx0, sy0, 0);
                b.srcOffsets(1).set(sx1, sy1, 1);
                b.dstSubresource().set(aspect, dLevel, dLayer, 1);
                b.dstOffsets(0).set(dx0, dy0, 0);
                b.dstOffsets(1).set(dx1, dy1, 1);
                final boolean canLinear = linear && !ds && images.supports(VkFormats.of(s.format.pf()), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
                vkCmdBlitImage(cmd, s.image, srcLayout, d.image, dstLayout, b, canLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            }
        }
        final long serial = stream.currentSerial();
        s.lastUse = serial;
        d.lastUse = serial;
    }

    @Override protected String vendorString() {
        if (gpu.isNvidia()) return "NVIDIA Corporation";
        if (gpu.isAmd()) return "AMD";
        if (gpu.isIntel()) return "Intel";
        if (gpu.isApple()) return "Apple";
        return "Vulkan";
    }

    @Override protected long deviceLocalBytes() {
        long total = 0;
        for (int i = 0; i < gpu.memoryProperties.memoryHeapCount(); i++) {
            if ((gpu.memoryProperties.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) total = Math.max(total, gpu.memoryProperties.memoryHeaps(i).size());
        }
        return total;
    }

    @Override protected TexturesBase<Tex> textures() {
        return textures;
    }

    @Override protected ProgramsBase<Program> programs() {
        return programs;
    }

    @Override protected BuffersBase<Buf> buffers() {
        return buffers;
    }

    @Override protected void targetsChanged() {
        renderer.targetGen++;
    }

    @Override public GpuCaps gpuCaps() {
        return gpu;
    }

    @Override protected long currentSerial() {
        return stream.currentSerial();
    }

    @Override protected boolean isIdle(long serial) {
        return stream.isIdle(serial);
    }

    @Override protected void pollStream() {
        stream.poll();
    }

    @Override protected void clearTargets(GlState st, int mask) {
        renderer.clear(st, mask);
    }

    @Override protected void computeBarrierPending() {
        renderer.pendingComputeBarrier = true;
    }

    @Override protected void ensureDefaultFramebuffer(int width, int height) {
        defaultFb.ensure(width, height);
    }

    @Override protected int defaultFramebufferWidth() {
        return defaultFb.width;
    }

    @Override protected int defaultFramebufferHeight() {
        return defaultFb.height;
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

    @Override protected String apiTag() {
        return "vulkan";
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

    @Override protected void releaseImage(Tex t) {
        images.release(t);
    }

    @Override protected void createImage(Tex t, boolean storage) {
        images.create(t, storage);
    }

    @Override protected Tex defaultColor() {
        return defaultFb.color;
    }

    @Override protected void releaseFromPass(Tex t) {
        renderer.releaseFromPass(t);
    }

    @Override protected void endPass() {
        renderer.endPass();
    }

    @Override protected TargetBase<Tex> readTarget(GlState st) {
        return renderer.readTarget(st);
    }

    @Override protected void resolveTarget(TargetBase<Tex> into, Fbo draw, GlState st) {
        renderer.resolve((Target) into, draw, st, true);
    }

    @Override protected TargetBase<Tex> blitTarget() {
        return blitDst;
    }

    @Override protected boolean waitForSerial(long serial, long timeout) {
        return stream.waitFor(serial, timeout);
    }

    @Override public void quiesceForSwapchainChange() {
        finish();
        gpu.waitIdle();
    }
}
