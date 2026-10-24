package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.Swapchain;
import com.gtnewhorizons.angelica.vulkan.frame.CommandStream;
import com.gtnewhorizons.angelica.vulkan.frame.HostRing;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import com.gtnewhorizons.angelica.vulkan.resource.Formats;
import com.gtnewhorizons.angelica.vulkan.resource.PixelConvert;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import com.gtnewhorizons.angelica.vulkan.shader.Program;
import com.gtnewhorizons.angelica.vulkan.shader.Shader;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.opengl.NVXGPUMemoryInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanGL {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    public final Gpu gpu;
    final MemoryAllocator allocator;
    final CommandStream stream;
    final HostRing uniformRing;
    final HostRing stagingRing;
    final Images images;
    final Textures textures;
    final Buffers buffers;
    final Programs programs;
    final Framebuffers framebuffers;
    final Renderer renderer;
    final DefaultFramebuffer defaultFb;
    final ClearPass clearPass;
    final DebugNames debug;
    final Int2ObjectOpenHashMap<SamplerObj> samplerObjects = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Vao> vaos = new Int2ObjectOpenHashMap<>();
    private int nextVao = 1, nextSampler = 1;
    int patchVertices = 3;

    private Swapchain swapchain;
    private long window;
    private final LongArrayFIFOQueue presentSerials = new LongArrayFIFOQueue();
    private final int framesInFlight = Math.max(1, Math.min(3, SystemProperties.VULKAN_FRAMES_IN_FLIGHT));
    private volatile VSyncMode pendingVSync;

    // per-thread GL state
    private volatile Thread mainThread;
    private final GlState mainState;
    private final ThreadLocal<GlState> threadStates = new ThreadLocal<>();
    private final java.util.Set<GlState> otherStates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ObjectOpenHashSet<String> warned = new ObjectOpenHashSet<>();
    long frames, presented, presentSkips, lastStatsNanos;

    public VulkanGL(Gpu gpu, Thread mainThread, File cacheDir) {
        this.gpu = gpu;
        this.mainThread = mainThread;
        this.allocator = new MemoryAllocator(gpu);
        this.stream = new CommandStream(gpu);
        this.uniformRing = new HostRing(gpu, allocator, stream, 8L << 20, MemoryAllocator.USAGE_STREAM);
        this.stagingRing = new HostRing(gpu, allocator, stream, 32L << 20, MemoryAllocator.USAGE_UPLOAD);
        this.images = new Images(this);
        this.debug = new DebugNames(this, SystemProperties.VULKAN_DEBUG || SystemProperties.LWJGL_DEBUG);
        this.textures = new Textures(this);
        this.buffers = new Buffers(this);
        this.programs = new Programs(this);
        this.framebuffers = new Framebuffers(this);
        this.renderer = new Renderer(this, cacheDir == null ? null : new File(cacheDir, "vulkan-pipelines-" + Integer.toHexString(gpu.vendorId) + "-" + Integer.toHexString(gpu.driverVersion) + ".bin"));
        this.defaultFb = new DefaultFramebuffer(this);
        this.clearPass = new ClearPass(this);
        this.mainState = new GlState(mainThread);
        stream.setBeforeSubmit(() -> {
            renderer.endPass();
            debug.closeAll();
        });
        textures.pickDepthFormats();
        renderer.init();
    }


    public GlState st() {
        final Thread t = Thread.currentThread();
        if (t == mainThread) return mainState;
        if (mainThread == null) {
            mainThread = t;
            return mainState;
        }
        GlState s = threadStates.get();
        if (s == null) {
            s = new GlState(Thread.currentThread());
            threadStates.set(s);
            otherStates.add(s);
        }
        return s;
    }

    public void setMainThread(Thread t) {
        mainThread = t;
    }

    public Thread mainThread() {
        return mainThread;
    }

    public boolean hasStateOnThread() {
        return Thread.currentThread() == mainThread || threadStates.get() != null;
    }

    public boolean releaseThreadState() {
        final GlState s = threadStates.get();
        if (s != null) {
            threadStates.remove();
            otherStates.remove(s);
        }
        return !otherStates.isEmpty();
    }

    public boolean otherThreadsHaveState() {
        return !otherStates.isEmpty();
    }

    boolean anyStateUsesProgram(int id) {
        if (mainState.program == id) return true;
        for (GlState s : otherStates) if (s.program == id) return true;
        return false;
    }

    void warnOnce(String msg) {
        synchronized (warned) {
            if (!warned.add(msg)) return;
        }
        LOG.warn("Vulkan GL: {}", msg);
    }

    int vertexFormatFallback(int vk) {
        if ((images.bufferFeatures(vk) & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0) return vk;
        // 3-component 8/16-bit formats are missing on several drivers; widen to 4
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

    void flushAndWait(long serial) {
        renderer.endPass();
        if (serial >= stream.currentSerial()) {
            // GPU writes become visible to host reads only through an explicit HOST dependency
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

    public void attachOffscreen(int width, int height) {
        defaultFb.ensure(width, height);
    }

    public boolean hasWindow() {
        return swapchain != null;
    }

    private long windowPixels() {
        if (window == 0L) return 1L << 32 | 1L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels(window, w, h);
            return ((long) Math.max(0, w.get(0)) << 32) | (Math.max(0, h.get(0)) & 0xFFFFFFFFL);
        }
    }

    public VSyncMode setVSync(VSyncMode preferred) {
        pendingVSync = preferred;
        if (swapchain == null) return preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        return swapchain.setVSync(preferred);
    }

    public boolean supportsVSync(VSyncMode mode) {
        return swapchain != null && swapchain.supports(mode);
    }

    public int swapchainFormat() {
        return swapchain == null ? VK_FORMAT_UNDEFINED : swapchain.format;
    }

    public interface PaceSink {
        void gpuWait(long nanos);

        void gate(long durationNanos, long endNanos);
    }

    public PaceSink paceSink;

    private static final long DUMP_FRAME = Long.getLong("angelica.vulkan.dumpFrame", -1L);

    public void present() {
        frames++;
        if (frames == DUMP_FRAME || DUMP_FRAME > 0 && frames % DUMP_FRAME == 0 && Boolean.getBoolean("angelica.vulkan.dumpRepeat")) dumpBackBuffer();
        renderer.endPass();
        final long px = windowPixels();
        final int ww = (int) (px >>> 32), wh = (int) px;
        boolean presented = false;
        // frames-in-flight throttle, then the swapchain acquire (where FIFO blocks): the pacer's gate sample
        final long t0 = System.nanoTime();
        while (presentSerials.size() >= framesInFlight) stream.waitFor(presentSerials.dequeueLong());
        final long t1 = System.nanoTime();
        if (paceSink != null) paceSink.gpuWait(t1 - t0);
        if (swapchain != null && ww > 0 && wh > 0 && swapchain.ensure(ww, wh)) {
            int r = swapchain.acquire(stream.completedSerial());
            if (r == Swapchain.REBUILD && swapchain.ensure(ww, wh)) r = swapchain.acquire(stream.completedSerial());
            final long tEnd = System.nanoTime();
            if (r == Swapchain.ACQUIRED && paceSink != null) paceSink.gate(tEnd - t0, tEnd);
            if (r == Swapchain.ACQUIRED) {
                recordPresentBlit();
                final long serial = stream.submit(swapchain.acquireSemaphore, VK_PIPELINE_STAGE_TRANSFER_BIT, swapchain.presentSemaphore());
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
            allocator.trim();
        }
    }

    public long awaitPresent() {
        if (swapchain == null) return 0L;
        return swapchain.waitForPreviousPresent(100_000_000L);
    }

    public boolean hasPresentWait() {
        return gpu.presentWait;
    }

    private void dumpBackBuffer() {
        final int w = defaultFb.width, h = defaultFb.height;
        final ByteBuffer px = MemoryUtil.memAlloc(w * h * 4);
        try {
            final PixelConvert.Store pack = new PixelConvert.Store();
            textures.readImage(defaultFb.color, VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pack, memAddress(px));
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int i = ((h - 1 - y) * w + x) * 4;
                    img.setRGB(x, y, (px.get(i) & 0xFF) << 16 | (px.get(i + 1) & 0xFF) << 8 | (px.get(i + 2) & 0xFF));
                }
            }
            final File out = new File("angelica", "vulkan-frame-" + frames + ".png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);
            LOG.info("Dumped back buffer to {} ({})", out.getAbsolutePath(), stats());
        } catch (Throwable t) {
            LOG.warn("Back buffer dump failed", t);
        } finally {
            MemoryUtil.memFree(px);
        }
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
            toDst.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toDst);
            final VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            blit.srcOffsets(0).set(0, 0, 0);
            blit.srcOffsets(1).set(defaultFb.width, defaultFb.height, 1);
            blit.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            // GL's bottom-up rows become the swapchain's top-down rows
            blit.dstOffsets(0).set(0, swapchain.height, 0);
            blit.dstOffsets(1).set(swapchain.width, 0, 1);
            final boolean sameSize = defaultFb.width == swapchain.width && defaultFb.height == swapchain.height;
            vkCmdBlitImage(cmd, color.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
            final VkImageMemoryBarrier.Buffer toPresent = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            toPresent.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
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

    public String stats() {
        return String.format("presented=%d skipped=%d draws=%d passes=%d setWrites=%d pipelines=%d uploads=%d (%.1f MiB) renames=%d inlineCopies=%d mem=%s",
            presented, presentSkips, renderer.draws, renderer.passes, renderer.setWrites, renderer.pipelines.count(), textures.uploads, textures.uploadBytes / 1048576.0,
            buffers.renames, buffers.inlineCopies, allocator.describe());
    }


    public void flush() {
        // GL flush only promises eventual execution; the frame submit provides that
    }

    public void finish() {
        flushAndWait(stream.currentSerial());
    }


    public void enable(int cap) {
        setCap(cap, true);
    }

    public void disable(int cap) {
        setCap(cap, false);
    }

    public void enablei(int cap, int index) {
        final GlState st = st();
        if (cap == GL11.GL_BLEND && index >= 0 && index < GlState.MAX_DRAW_BUFFERS) {
            if (!st.blend[index]) {
                st.blend[index] = true;
                st.pipelineGen++;
            }
            return;
        }
        setCap(cap, true);
    }

    public void disablei(int cap, int index) {
        final GlState st = st();
        if (cap == GL11.GL_BLEND && index >= 0 && index < GlState.MAX_DRAW_BUFFERS) {
            if (st.blend[index]) {
                st.blend[index] = false;
                st.pipelineGen++;
            }
            return;
        }
        setCap(cap, false);
    }

    private void setCap(int cap, boolean on) {
        final GlState st = st();
        switch (cap) {
            case GL11.GL_BLEND -> {
                boolean changed = false;
                for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
                    if (st.blend[i] != on) changed = true;
                    st.blend[i] = on;
                }
                if (changed) st.pipelineGen++;
            }
            case GL11.GL_DEPTH_TEST -> { if (st.depthTest != on) { st.depthTest = on; st.pipelineGen++; } }
            case GL11.GL_CULL_FACE -> { if (st.cull != on) { st.cull = on; st.pipelineGen++; } }
            case GL11.GL_STENCIL_TEST -> { if (st.stencilTest != on) { st.stencilTest = on; st.pipelineGen++; } }
            case GL11.GL_SCISSOR_TEST -> { if (st.scissorTest != on) { st.scissorTest = on; st.dynamicGen++; } }
            case GL11.GL_POLYGON_OFFSET_FILL -> { if (st.polyOffsetFill != on) { st.polyOffsetFill = on; st.pipelineGen++; } }
            case GL11.GL_POLYGON_OFFSET_LINE -> { if (st.polyOffsetLine != on) { st.polyOffsetLine = on; st.pipelineGen++; } }
            case GL11.GL_POLYGON_OFFSET_POINT -> { if (st.polyOffsetPoint != on) { st.polyOffsetPoint = on; st.pipelineGen++; } }
            case GL32.GL_DEPTH_CLAMP -> { if (st.depthClamp != on) { st.depthClamp = on; st.pipelineGen++; } }
            case GL30.GL_RASTERIZER_DISCARD -> { if (st.rasterizerDiscard != on) { st.rasterizerDiscard = on; st.pipelineGen++; } }
            case GL11.GL_COLOR_LOGIC_OP -> { if (st.logicOpEnabled != on) { st.logicOpEnabled = on; st.pipelineGen++; } }
            case GL31.GL_PRIMITIVE_RESTART -> { if (st.primitiveRestart != on) { st.primitiveRestart = on; st.pipelineGen++; } }
            case GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX -> { if (st.primitiveRestartFixed != on) { st.primitiveRestartFixed = on; st.pipelineGen++; } }
            case GL32.GL_PROGRAM_POINT_SIZE -> st.programPointSize = on;
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> st.sampleAlphaToCoverage = on;
            case GL30.GL_FRAMEBUFFER_SRGB -> st.framebufferSrgb = on;
            case GL13.GL_MULTISAMPLE -> st.multisample = on;
            default -> {
                if (cap >= GL30.GL_CLIP_DISTANCE0 && cap < GL30.GL_CLIP_DISTANCE0 + 8) {
                    final int bit = 1 << (cap - GL30.GL_CLIP_DISTANCE0);
                    st.clipDistances = on ? st.clipDistances | bit : st.clipDistances & ~bit;
                }
            }
        }
    }

    boolean isEnabled(int cap) {
        final GlState st = st();
        return switch (cap) {
            case GL11.GL_BLEND -> st.blend[0];
            case GL11.GL_DEPTH_TEST -> st.depthTest;
            case GL11.GL_CULL_FACE -> st.cull;
            case GL11.GL_STENCIL_TEST -> st.stencilTest;
            case GL11.GL_SCISSOR_TEST -> st.scissorTest;
            case GL11.GL_POLYGON_OFFSET_FILL -> st.polyOffsetFill;
            case GL11.GL_POLYGON_OFFSET_LINE -> st.polyOffsetLine;
            case GL11.GL_POLYGON_OFFSET_POINT -> st.polyOffsetPoint;
            case GL32.GL_DEPTH_CLAMP -> st.depthClamp;
            case GL30.GL_RASTERIZER_DISCARD -> st.rasterizerDiscard;
            case GL11.GL_COLOR_LOGIC_OP -> st.logicOpEnabled;
            case GL31.GL_PRIMITIVE_RESTART -> st.primitiveRestart;
            case GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX -> st.primitiveRestartFixed;
            case GL32.GL_PROGRAM_POINT_SIZE -> st.programPointSize;
            case GL30.GL_FRAMEBUFFER_SRGB -> st.framebufferSrgb;
            case GL13.GL_MULTISAMPLE -> st.multisample;
            case GL11.GL_DEPTH_WRITEMASK -> st.depthMask;
            default -> cap >= GL30.GL_CLIP_DISTANCE0 && cap < GL30.GL_CLIP_DISTANCE0 + 8 && (st.clipDistances & (1 << (cap - GL30.GL_CLIP_DISTANCE0))) != 0;
        };
    }


    public void blendFunc(int sfactor, int dfactor) {
        blendFuncSeparate(sfactor, dfactor, sfactor, dfactor);
    }

    public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        final GlState st = st();
        boolean changed = false;
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
            if (st.blendSrcRgb[i] != srcRGB || st.blendDstRgb[i] != dstRGB || st.blendSrcA[i] != srcAlpha || st.blendDstA[i] != dstAlpha) changed = true;
            st.blendSrcRgb[i] = srcRGB;
            st.blendDstRgb[i] = dstRGB;
            st.blendSrcA[i] = srcAlpha;
            st.blendDstA[i] = dstAlpha;
        }
        if (changed) st.pipelineGen++;
    }

    public void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (buf < 0 || buf >= GlState.MAX_DRAW_BUFFERS) return;
        final GlState st = st();
        st.blendSrcRgb[buf] = srcRGB;
        st.blendDstRgb[buf] = dstRGB;
        st.blendSrcA[buf] = srcAlpha;
        st.blendDstA[buf] = dstAlpha;
        st.pipelineGen++;
    }

    public void blendEquation(int mode) {
        blendEquationSeparate(mode, mode);
    }

    public void blendEquationSeparate(int modeRGB, int modeAlpha) {
        final GlState st = st();
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
            st.blendEqRgb[i] = modeRGB;
            st.blendEqA[i] = modeAlpha;
        }
        st.pipelineGen++;
    }

    public void blendColor(float red, float green, float blue, float alpha) {
        final GlState st = st();
        st.blendColor[0] = red;
        st.blendColor[1] = green;
        st.blendColor[2] = blue;
        st.blendColor[3] = alpha;
        st.dynamicGen++;
    }

    public void depthFunc(int func) {
        final GlState st = st();
        if (st.depthFunc != func) {
            st.depthFunc = func;
            st.pipelineGen++;
        }
    }

    public void depthMask(boolean flag) {
        final GlState st = st();
        if (st.depthMask != flag) {
            st.depthMask = flag;
            st.pipelineGen++;
        }
    }

    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        final GlState st = st();
        boolean changed = false;
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
            final boolean[] m = st.colorMask[i];
            if (m[0] != red || m[1] != green || m[2] != blue || m[3] != alpha) changed = true;
            m[0] = red;
            m[1] = green;
            m[2] = blue;
            m[3] = alpha;
        }
        if (changed) st.pipelineGen++;
    }

    public void colorMaski(int buf, boolean red, boolean green, boolean blue, boolean alpha) {
        if (buf < 0 || buf >= GlState.MAX_DRAW_BUFFERS) return;
        final GlState st = st();
        final boolean[] m = st.colorMask[buf];
        m[0] = red;
        m[1] = green;
        m[2] = blue;
        m[3] = alpha;
        st.pipelineGen++;
    }

    public void cullFace(int mode) {
        final GlState st = st();
        if (st.cullFace != mode) {
            st.cullFace = mode;
            st.pipelineGen++;
        }
    }

    public void frontFace(int mode) {
        final GlState st = st();
        if (st.frontFace != mode) {
            st.frontFace = mode;
            st.pipelineGen++;
        }
    }

    public void polygonMode(int face, int mode) {
        final GlState st = st();
        if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) st.polygonModeFront = mode;
        if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) st.polygonModeBack = mode;
        st.pipelineGen++;
    }

    public void polygonOffset(float factor, float units) {
        final GlState st = st();
        st.polyFactor = factor;
        st.polyUnits = units;
        st.polyClamp = 0f;
        st.dynamicGen++;
    }

    public void polygonOffsetClamp(float factor, float units, float clamp) {
        final GlState st = st();
        st.polyFactor = factor;
        st.polyUnits = units;
        st.polyClamp = clamp;
        st.dynamicGen++;
    }

    public void stencilFunc(int func, int ref, int mask) {
        stencilFuncSeparate(GL11.GL_FRONT_AND_BACK, func, ref, mask);
    }

    public void stencilOp(int sfail, int dpfail, int dppass) {
        stencilOpSeparate(GL11.GL_FRONT_AND_BACK, sfail, dpfail, dppass);
    }

    public void stencilMask(int mask) {
        stencilMaskSeparate(GL11.GL_FRONT_AND_BACK, mask);
    }

    public void stencilFuncSeparate(int face, int func, int ref, int mask) {
        final GlState st = st();
        for (int f = 0; f < 2; f++) {
            if (!faceMatches(face, f)) continue;
            if (st.stencilFunc[f] != func) st.pipelineGen++;
            st.stencilFunc[f] = func;
            st.stencilRef[f] = ref;
            st.stencilValueMask[f] = mask;
        }
        st.dynamicGen++;
    }

    public void stencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        final GlState st = st();
        for (int f = 0; f < 2; f++) {
            if (!faceMatches(face, f)) continue;
            st.stencilFail[f] = sfail;
            st.stencilDepthFail[f] = dpfail;
            st.stencilPass[f] = dppass;
        }
        st.pipelineGen++;
    }

    public void stencilMaskSeparate(int face, int mask) {
        final GlState st = st();
        for (int f = 0; f < 2; f++) if (faceMatches(face, f)) st.stencilWriteMask[f] = mask;
        st.dynamicGen++;
    }

    private static boolean faceMatches(int face, int f) {
        return face == GL11.GL_FRONT_AND_BACK || face == GL11.GL_FRONT && f == 0 || face == GL11.GL_BACK && f == 1;
    }

    public void viewport(int x, int y, int width, int height) {
        final GlState st = st();
        if (st.vpX == x && st.vpY == y && st.vpW == width && st.vpH == height) return;
        st.vpX = x;
        st.vpY = y;
        st.vpW = width;
        st.vpH = height;
        st.dynamicGen++;
    }

    public void depthRange(double nearVal, double farVal) {
        final GlState st = st();
        st.depthNear = nearVal;
        st.depthFar = farVal;
        st.dynamicGen++;
    }

    public void scissor(int x, int y, int width, int height) {
        final GlState st = st();
        if (st.scX == x && st.scY == y && st.scW == width && st.scH == height) return;
        st.scX = x;
        st.scY = y;
        st.scW = width;
        st.scH = height;
        st.dynamicGen++;
    }

    public void clearColor(float red, float green, float blue, float alpha) {
        final GlState st = st();
        st.clearColor[0] = red;
        st.clearColor[1] = green;
        st.clearColor[2] = blue;
        st.clearColor[3] = alpha;
    }

    public void clearDepth(double depth) {
        st().clearDepth = depth;
    }

    public void clearStencil(int s) {
        st().clearStencil = s;
    }

    public void clear(int mask) {
        renderer.clear(st(), mask);
    }

    public void lineWidth(float width) {
        final GlState st = st();
        st.lineWidth = width;
        st.dynamicGen++;
    }

    public void pointSize(float size) {
        st().pointSize = size;
    }

    public void logicOp(int opcode) {
        final GlState st = st();
        st.logicOp = opcode;
        st.pipelineGen++;
    }

    public void hint(int target, int mode) {
    }

    public void provokingVertex(int provokeMode) {
        final GlState st = st();
        st.provokingFirst = provokeMode == GL32.GL_FIRST_VERTEX_CONVENTION;
        st.pipelineGen++;
    }

    public void primitiveRestartIndex(int index) {
        st().primitiveRestartIndex = index;
    }

    public void pointParameterf(int pname, float param) {
    }

    public void pointParameteri(int pname, int param) {
    }

    public void sampleCoverage(float value, boolean invert) {
    }

    public void patchParameteri(int pname, int value) {
        if (pname == GL40.GL_PATCH_VERTICES) patchVertices = value;
    }


    private int topology(int mode) {
        return Renderer.topology(mode, gpu.triangleFans);
    }

    private boolean needsIndexConversion(int mode) {
        return mode == GL11.GL_LINE_LOOP || mode == GL11.GL_QUADS || mode == GL11.GL_QUAD_STRIP
            || (mode == GL11.GL_TRIANGLE_FAN || mode == GL11.GL_POLYGON) && !gpu.triangleFans;
    }

    public void drawArrays(int mode, int first, int count) {
        drawArraysInstanced(mode, first, count, 1);
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

    private static int convertedMode(int mode) {
        return mode == GL11.GL_LINE_LOOP ? GL11.GL_LINE_STRIP : GL11.GL_TRIANGLES;
    }

    static int[] convertedIndices(int mode, int count) {
        switch (mode) {
            case GL11.GL_LINE_LOOP -> {
                if (count < 2) return new int[0];
                final int[] out = new int[count + 1];
                for (int i = 0; i < count; i++) out[i] = i;
                out[count] = 0;
                return out;
            }
            case GL11.GL_TRIANGLE_FAN, GL11.GL_POLYGON -> {
                if (count < 3) return new int[0];
                final int[] out = new int[(count - 2) * 3];
                for (int i = 0; i < count - 2; i++) {
                    out[i * 3] = 0;
                    out[i * 3 + 1] = i + 1;
                    out[i * 3 + 2] = i + 2;
                }
                return out;
            }
            case GL11.GL_QUADS -> {
                final int quads = count / 4;
                final int[] out = new int[quads * 6];
                for (int q = 0; q < quads; q++) {
                    final int b = q * 4;
                    // both triangles keep v3 as the provoking vertex, like GL_QUADS with last-vertex convention
                    out[q * 6] = b;
                    out[q * 6 + 1] = b + 1;
                    out[q * 6 + 2] = b + 3;
                    out[q * 6 + 3] = b + 1;
                    out[q * 6 + 4] = b + 2;
                    out[q * 6 + 5] = b + 3;
                }
                return out;
            }
            case GL11.GL_QUAD_STRIP -> {
                if (count < 4) return new int[0];
                final int quads = (count - 2) / 2;
                final int[] out = new int[quads * 6];
                for (int q = 0; q < quads; q++) {
                    final int b = q * 2;
                    out[q * 6] = b;
                    out[q * 6 + 1] = b + 1;
                    out[q * 6 + 2] = b + 3;
                    out[q * 6 + 3] = b;
                    out[q * 6 + 4] = b + 3;
                    out[q * 6 + 5] = b + 2;
                }
                return out;
            }
            default -> {
                return new int[0];
            }
        }
    }

    private static int indexBytes(int type) {
        return switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> 1;
            case GL11.GL_UNSIGNED_SHORT -> 2;
            default -> 4;
        };
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
            // client-side indices
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
        ebo.lastUse = stream.currentSerial();
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

    public void drawElements(int mode, int count, int type, long indices) {
        drawElementsInstancedBaseVertex(mode, count, type, indices, 1, 0);
    }

    public void drawElementsInstanced(int mode, int count, int type, long indices, int primcount) {
        drawElementsInstancedBaseVertex(mode, count, type, indices, primcount, 0);
    }

    public void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex) {
        drawElementsInstancedBaseVertex(mode, count, type, indices, 1, baseVertex);
    }

    public void drawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int baseVertex) {
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
            // one binding, offsets become firstIndex
            ebo.lastUse = stream.currentSerial();
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
        ebo.lastUse = ib.lastUse = stream.currentSerial();
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
        ib.lastUse = stream.currentSerial();
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
        ebo.lastUse = ib.lastUse = cb.lastUse = stream.currentSerial();
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
        ib.lastUse = cb.lastUse = stream.currentSerial();
        org.lwjgl.vulkan.VK12.vkCmdDrawIndirectCount(cmd, ib.handle(), indirect, cb.handle(), drawcount, maxdrawcount, stride == 0 ? 16 : stride);
    }

    public void drawBuffer(int mode) {
        final GlState st = st();
        final Fbo f = framebuffers.get(st.drawFbo);
        if (f == null) return;
        if (f.id == 0) {
            st.defaultDrawNone = mode == GL11.GL_NONE;
            renderer.targetGen++;
            return;
        }
        f.drawBuffers = new int[] {mode == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(mode)};
        f.gen++;
    }

    public void drawBuffers(int buffer) {
        drawBuffer(buffer);
    }

    public void drawBuffers(IntBuffer bufs) {
        final GlState st = st();
        namedDrawBuffers(framebuffers.get(st.drawFbo), bufs, st);
    }

    private void namedDrawBuffers(Fbo f, IntBuffer bufs, GlState st) {
        if (f == null) return;
        final int n = bufs.remaining();
        if (f.id == 0) {
            st.defaultDrawNone = n == 0 || bufs.get(bufs.position()) == GL11.GL_NONE;
            renderer.targetGen++;
            return;
        }
        final int[] map = new int[Math.min(n, Fbo.MAX_COLOR)];
        for (int i = 0; i < map.length; i++) {
            final int b = bufs.get(bufs.position() + i);
            map[i] = b == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(b);
        }
        f.drawBuffers = map;
        f.gen++;
    }

    public void readBuffer(int mode) {
        final GlState st = st();
        final Fbo f = framebuffers.get(st.readFbo);
        if (f == null) return;
        if (f.id == 0) {
            st.defaultReadNone = mode == GL11.GL_NONE;
            return;
        }
        f.readBuffer = mode == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(mode);
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
        b.lastUse = stream.currentSerial();
        vkCmdDispatchIndirect(stream.cmd(), b.handle(), offset);
        renderer.pendingComputeBarrier = true;
    }

    public void memoryBarrier(int barriers) {
        renderer.pendingComputeBarrier = true;
    }


    public int genTextures() {
        return textures.gen();
    }

    public void genTextures(IntBuffer out) {
        for (int i = out.position(); i < out.limit(); i++) out.put(i, textures.gen());
    }

    public int createTextures(int target) {
        final int id = textures.gen();
        textures.getOrCreate(id, target);
        return id;
    }

    public void deleteTextures(int texture) {
        if (texture == 0) return;
        textures.delete(texture);
        unbindTextureEverywhere(texture);
    }

    private void unbindTextureEverywhere(int id) {
        unbindTexture(mainState, id);
        for (GlState s : otherStates) unbindTexture(s, id);
    }

    private static void unbindTexture(GlState s, int id) {
        for (int u = 0; u < GlState.MAX_UNITS; u++) {
            for (int t = 0; t < GlState.T_COUNT; t++) if (s.textures[u][t] == id) s.textures[u][t] = 0;
        }
        for (int i = 0; i < GlState.MAX_IMAGE_UNITS; i++) if (s.imageTex[i] == id) s.imageTex[i] = 0;
    }

    public boolean isTexture(int texture) {
        return textures.exists(texture);
    }

    public void bindTexture(int target, int texture) {
        final GlState st = st();
        final int slot = GlState.targetIndex(target);
        if (slot < 0) return;
        if (texture != 0) textures.getOrCreate(texture, target);
        st.textures[st.activeUnit][slot] = texture;
        st.textureGen++;
    }

    public void bindTextureUnit(int unit, int texture) {
        final GlState st = st();
        if (unit < 0 || unit >= GlState.MAX_UNITS) return;
        final Tex t = textures.get(texture);
        if (texture == 0) {
            for (int i = 0; i < GlState.T_COUNT; i++) st.textures[unit][i] = 0;
            return;
        }
        if (t == null) return;
        final int slot = GlState.targetIndex(t.target == 0 ? GL11.GL_TEXTURE_2D : t.target);
        if (slot >= 0) st.textures[unit][slot] = texture;
        st.textureGen++;
    }

    public void activeTexture(int texture) {
        final int unit = texture - GL13.GL_TEXTURE0;
        if (unit >= 0 && unit < GlState.MAX_UNITS) st().activeUnit = unit;
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        final GlState st = st();
        final boolean pbo = pixels == null && st.pixelUnpack != 0;
        textures.texImage(st, target, level, internalFormat, width, height, 1, format, type, pixels == null ? 0L : memAddress(pixels), pbo);
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        textures.texImage(st(), target, level, internalFormat, width, height, 1, format, type, pixels == null ? 0L : memAddress(pixels), false);
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        textures.texImage(st(), target, level, internalFormat, width, height, 1, format, type, pixels == null ? 0L : memAddress(pixels), false);
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        textures.texImage(st(), target, level, internalFormat, width, height, 1, format, type, pixels == null ? 0L : memAddress(pixels), false);
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixelBufferOffset) {
        final GlState st = st();
        if (st.pixelUnpack != 0) textures.texImage(st, target, level, internalFormat, width, height, 1, format, type, pixelBufferOffset, true);
        else textures.texImage(st, target, level, internalFormat, width, height, 1, format, type, pixelBufferOffset, false);
    }

    public void texImage1D(int target, int level, int internalFormat, int width, int border, int format, int type, ByteBuffer pixels) {
        textures.texImage(st(), target, level, internalFormat, width, 1, 1, format, type, pixels == null ? 0L : memAddress(pixels), false);
    }

    public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {
        textures.texImage(st(), target, level, internalFormat, width, height, depth, format, type, pixels == null ? 0L : memAddress(pixels), false);
    }

    public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, IntBuffer pixels) {
        textures.texImage(st(), target, level, internalFormat, width, height, depth, format, type, pixels == null ? 0L : memAddress(pixels), false);
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        textures.texSubImage(st(), target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        if (pixels == null) return;
        textures.texSubImage(st(), target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pboOffset) {
        final GlState st = st();
        textures.texSubImage(st, target, level, xoffset, yoffset, 0, width, height, 1, format, type, pboOffset, st.pixelUnpack != 0);
    }

    public void texSubImage1D(int target, int level, int xoffset, int width, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        textures.texSubImage(st(), target, level, xoffset, 0, 0, width, 1, 1, format, type, memAddress(pixels), false);
    }

    public void texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        textures.texSubImage(st(), target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, memAddress(pixels), false);
    }

    public void texStorage2D(int target, int levels, int internalFormat, int width, int height) {
        textures.texStorage(st(), target, levels, internalFormat, width, height, 1);
    }

    public void texStorage3D(int target, int levels, int internalFormat, int width, int height, int depth) {
        textures.texStorage(st(), target, levels, internalFormat, width, height, depth);
    }

    public void compressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, ByteBuffer data) {
        textures.compressedTexImage(st(), target, level, internalFormat, width, height, 1, data == null ? 0L : memAddress(data), data == null ? 0 : data.remaining());
    }

    public void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        final GlState st = st();
        textures.copyFromFramebuffer(st, textures.get(st.boundTexture(target)), target, level, xoffset, yoffset, 0, x, y, width, height);
    }

    public void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        final GlState st = st();
        final Tex t = textures.getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        textures.specify(t, textures.format(internalFormat), level, width, height, 1, t.isCube() ? 6 : 1);
        textures.copyFromFramebuffer(st, t, target, level, 0, 0, 0, x, y, width, height);
    }

    public void copyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        final GlState st = st();
        textures.copyFromFramebuffer(st, textures.get(st.boundTexture(target)), target, level, xoffset, yoffset, zoffset, x, y, width, height);
    }

    public void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        textures.copyFromFramebuffer(st(), textures.get(texture), target, level, xoffset, yoffset, 0, x, y, width, height);
    }

    public void texParameteri(int target, int pname, int param) {
        texParam(textures.get(st().boundTexture(target)), pname, param, param);
    }

    public void texParameterf(int target, int pname, float param) {
        texParam(textures.get(st().boundTexture(target)), pname, (int) param, param);
    }

    public void texParameteriv(int target, int pname, IntBuffer params) {
        texParamv(textures.get(st().boundTexture(target)), pname, params, null);
    }

    public void texParameterfv(int target, int pname, FloatBuffer params) {
        texParamv(textures.get(st().boundTexture(target)), pname, null, params);
    }

    public void textureParameteri(int texture, int target, int pname, int param) {
        texParam(textures.getOrCreate(texture, target), pname, param, param);
    }

    public void textureParameterf(int texture, int target, int pname, float param) {
        texParam(textures.getOrCreate(texture, target), pname, (int) param, param);
    }

    public void textureParameteriv(int texture, int target, int pname, IntBuffer params) {
        texParamv(textures.getOrCreate(texture, target), pname, params, null);
    }

    private void texParamv(Tex t, int pname, IntBuffer ip, FloatBuffer fp) {
        if (t == null) return;
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) {
            for (int i = 0; i < 4; i++) t.border[i] = fp != null ? fp.get(fp.position() + i) : ip.get(ip.position() + i) / (float) Integer.MAX_VALUE;
            t.samplerDirty = true;
            return;
        }
        if (pname == GL33.GL_TEXTURE_SWIZZLE_RGBA) {
            for (int i = 0; i < 4; i++) t.swizzle[i] = ip != null ? ip.get(ip.position() + i) : (int) fp.get(fp.position() + i);
            t.viewDirty = true;
            return;
        }
        final int iv = ip != null ? ip.get(ip.position()) : (int) fp.get(fp.position());
        final float fv = fp != null ? fp.get(fp.position()) : ip.get(ip.position());
        texParam(t, pname, iv, fv);
    }

    private void texParam(Tex t, int pname, int iv, float fv) {
        if (t == null) return;
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> {
                final boolean mipsBefore = t.usesMipmaps();
                t.minFilter = iv;
                t.samplerDirty = true;
                if (mipsBefore != t.usesMipmaps()) t.viewDirty = true;
            }
            case GL11.GL_TEXTURE_MAG_FILTER -> { t.magFilter = iv; t.samplerDirty = true; }
            case GL11.GL_TEXTURE_WRAP_S -> { t.wrapS = iv; t.samplerDirty = true; }
            case GL11.GL_TEXTURE_WRAP_T -> { t.wrapT = iv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_WRAP_R -> { t.wrapR = iv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_MIN_LOD -> { t.minLod = fv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_MAX_LOD -> { t.maxLod = fv; t.samplerDirty = true; }
            case GL14.GL_TEXTURE_LOD_BIAS -> { t.lodBias = fv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_BASE_LEVEL -> { t.baseLevel = iv; t.viewDirty = true; }
            case GL12.GL_TEXTURE_MAX_LEVEL -> { t.maxLevel = iv; t.viewDirty = true; }
            case GL14.GL_TEXTURE_COMPARE_MODE -> { t.compareMode = iv; t.samplerDirty = true; }
            case GL14.GL_TEXTURE_COMPARE_FUNC -> { t.compareFunc = iv; t.samplerDirty = true; }
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> { t.anisotropy = fv; t.samplerDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_R -> { t.swizzle[0] = iv; t.viewDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_G -> { t.swizzle[1] = iv; t.viewDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_B -> { t.swizzle[2] = iv; t.viewDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_A -> { t.swizzle[3] = iv; t.viewDirty = true; }
            case GL43.GL_DEPTH_STENCIL_TEXTURE_MODE -> { t.sampleStencil = iv == GL11.GL_STENCIL_INDEX; t.viewDirty = true; }
            default -> { }
        }
    }

    public int getTexParameteri(int target, int pname) {
        return texParamGet(textures.get(st().boundTexture(target)), pname);
    }

    public float getTexParameterf(int target, int pname) {
        final Tex t = textures.get(st().boundTexture(target));
        if (t == null) return 0f;
        return switch (pname) {
            case GL12.GL_TEXTURE_MIN_LOD -> t.minLod;
            case GL12.GL_TEXTURE_MAX_LOD -> t.maxLod;
            case GL14.GL_TEXTURE_LOD_BIAS -> t.lodBias;
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> t.anisotropy;
            default -> texParamGet(t, pname);
        };
    }

    public int getTextureParameteri(int texture, int target, int pname) {
        return texParamGet(textures.get(texture), pname);
    }

    public float getTextureParameterf(int texture, int target, int pname) {
        final Tex t = textures.get(texture);
        if (t == null) return 0f;
        if (pname == EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT) return t.anisotropy;
        return texParamGet(t, pname);
    }

    private int texParamGet(Tex t, int pname) {
        if (t == null) return 0;
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> t.minFilter;
            case GL11.GL_TEXTURE_MAG_FILTER -> t.magFilter;
            case GL11.GL_TEXTURE_WRAP_S -> t.wrapS;
            case GL11.GL_TEXTURE_WRAP_T -> t.wrapT;
            case GL12.GL_TEXTURE_WRAP_R -> t.wrapR;
            case GL12.GL_TEXTURE_BASE_LEVEL -> t.baseLevel;
            case GL12.GL_TEXTURE_MAX_LEVEL -> t.maxLevel;
            case GL14.GL_TEXTURE_COMPARE_MODE -> t.compareMode;
            case GL14.GL_TEXTURE_COMPARE_FUNC -> t.compareFunc;
            case GL42.GL_TEXTURE_IMMUTABLE_FORMAT -> t.immutable ? 1 : 0;
            case GL43.GL_TEXTURE_IMMUTABLE_LEVELS -> t.immutable ? t.levels : 0;
            case GL33.GL_TEXTURE_SWIZZLE_R -> t.swizzle[0];
            case GL33.GL_TEXTURE_SWIZZLE_G -> t.swizzle[1];
            case GL33.GL_TEXTURE_SWIZZLE_B -> t.swizzle[2];
            case GL33.GL_TEXTURE_SWIZZLE_A -> t.swizzle[3];
            case GL45.GL_TEXTURE_TARGET -> t.target;
            default -> 0;
        };
    }

    public int getTexLevelParameteri(int target, int level, int pname) {
        if (Textures.isProxy(target)) return Textures.proxyParam(st(), target, level, pname);
        return levelParam(textures.get(st().boundTexture(target)), level, pname);
    }

    public int getTextureLevelParameteri(int texture, int level, int pname) {
        return levelParam(textures.get(texture), level, pname);
    }

    private int levelParam(Tex t, int level, int pname) {
        if (t == null || !t.hasImage() || level >= t.levels) return 0;
        final Formats.Format f = t.format;
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> t.levelWidth(level);
            case GL11.GL_TEXTURE_HEIGHT -> t.target == GL30.GL_TEXTURE_1D_ARRAY ? t.layers : t.levelHeight(level);
            case GL12.GL_TEXTURE_DEPTH -> t.is3D() ? t.levelDepth(level) : t.layers;
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> f.glInternal();
            case GL30.GL_TEXTURE_RED_SIZE -> f.r();
            case GL30.GL_TEXTURE_GREEN_SIZE -> f.g();
            case GL30.GL_TEXTURE_BLUE_SIZE -> f.b();
            case GL30.GL_TEXTURE_ALPHA_SIZE -> f.a();
            case GL14.GL_TEXTURE_DEPTH_SIZE -> f.depthBits();
            case GL30.GL_TEXTURE_STENCIL_SIZE -> f.stencilBits();
            case GL13.GL_TEXTURE_COMPRESSED -> f.isCompressed() ? 1 : 0;
            case GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE -> (int) Formats.imageBytes(f, t.levelWidth(level), t.levelHeight(level));
            case GL32.GL_TEXTURE_SAMPLES -> 0;
            default -> 0;
        };
    }

    public void generateMipmap(int target) {
        textures.generateMipmap(textures.get(st().boundTexture(target)));
    }

    public void generateTextureMipmap(int texture) {
        textures.generateMipmap(textures.get(texture));
    }

    public void pixelStorei(int pname, int param) {
        final GlState st = st();
        switch (pname) {
            case GL11.GL_UNPACK_ROW_LENGTH -> st.unpack.rowLength = param;
            case GL11.GL_UNPACK_SKIP_PIXELS -> st.unpack.skipPixels = param;
            case GL11.GL_UNPACK_SKIP_ROWS -> st.unpack.skipRows = param;
            case GL11.GL_UNPACK_ALIGNMENT -> st.unpack.alignment = param;
            case GL12.GL_UNPACK_IMAGE_HEIGHT -> st.unpack.imageHeight = param;
            case GL12.GL_UNPACK_SKIP_IMAGES -> st.unpack.skipImages = param;
            case GL11.GL_UNPACK_SWAP_BYTES -> st.unpack.swapBytes = param != 0;
            case GL11.GL_PACK_ROW_LENGTH -> st.pack.rowLength = param;
            case GL11.GL_PACK_SKIP_PIXELS -> st.pack.skipPixels = param;
            case GL11.GL_PACK_SKIP_ROWS -> st.pack.skipRows = param;
            case GL11.GL_PACK_ALIGNMENT -> st.pack.alignment = param;
            case GL12.GL_PACK_IMAGE_HEIGHT -> st.pack.imageHeight = param;
            case GL12.GL_PACK_SKIP_IMAGES -> st.pack.skipImages = param;
            case GL11.GL_PACK_SWAP_BYTES -> st.pack.swapBytes = param != 0;
            default -> { }
        }
    }

    public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        withTexture(texture, target, () -> texImage2D(target, level, internalformat, width, height, border, format, type, pixels));
    }

    public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        withTexture(texture, target, () -> texImage2D(target, level, internalformat, width, height, border, format, type, pixels));
    }

    public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        final Tex t = textures.get(texture);
        if (t == null || !t.hasImage() || pixels == null) return;
        textures.subImage(st(), t, t.target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        final Tex t = textures.get(texture);
        if (t == null || !t.hasImage() || pixels == null) return;
        textures.subImage(st(), t, t.target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    private void withTexture(int texture, int target, Runnable r) {
        final GlState st = st();
        final int slot = GlState.targetIndex(target);
        if (slot < 0) return;
        final int prev = st.textures[st.activeUnit][slot];
        textures.getOrCreate(texture, target);
        st.textures[st.activeUnit][slot] = texture;
        try {
            r.run();
        } finally {
            st.textures[st.activeUnit][slot] = prev;
        }
    }

    public void getTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        getTexImageImpl(target, level, format, type, memAddress(pixels));
    }

    public void getTexImage(int target, int level, int format, int type, IntBuffer pixels) {
        if (pixels == null) return;
        getTexImageImpl(target, level, format, type, memAddress(pixels));
    }

    public void getTexImage(int target, int level, int format, int type, long pixelBufferOffset) {
        final GlState st = st();
        final Buf pbo = buffers.get(st.pixelPack);
        if (pbo == null || !pbo.hostVisible()) {
            warnOnce("glGetTexImage into a non-host-visible PBO");
            return;
        }
        getTexImageImpl(target, level, format, type, pbo.address() + pixelBufferOffset);
        pbo.lastUse = stream.currentSerial();
    }

    private void getTexImageImpl(int target, int level, int format, int type, long dst) {
        final GlState st = st();
        final Tex t = textures.get(st.boundTexture(target));
        if (t == null || !t.hasImage() || level >= t.levels) return;
        final int aspect = format == GL11.GL_STENCIL_INDEX ? VK_IMAGE_ASPECT_STENCIL_BIT : t.format.isDepth() ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        final int layer = t.isCube() ? Textures.cubeFace(target) : 0;
        textures.readImage(t, aspect, level, layer, 0, 0, t.levelWidth(level), t.levelHeight(level), format, type, st.pack, dst);
    }

    public void clearTexImage(int texture, int level, int format, int type) {
        final Tex t = textures.get(texture);
        if (t == null || !t.hasImage()) return;
        renderer.releaseFromPass(t);
        renderer.endPass();
        images.clearImage(stream.cmd(), t, new float[4], 0f, 0);
        t.pendingClear = false;
        t.lastUse = stream.currentSerial();
    }

    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                 int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                 int srcWidth, int srcHeight, int srcDepth) {
        final Tex src = srcTarget == GL30.GL_RENDERBUFFER ? textures.attachment(srcName, true) : textures.get(srcName);
        final Tex dst = dstTarget == GL30.GL_RENDERBUFFER ? textures.attachment(dstName, true) : textures.get(dstName);
        textures.copyImage(src, srcLevel, srcX, srcY, srcZ, dst, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, Math.max(1, srcDepth));
    }

    public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (unit < 0 || unit >= GlState.MAX_IMAGE_UNITS) return;
        final GlState st = st();
        st.imageTex[unit] = texture;
        st.imageLevel[unit] = level;
        st.imageLayered[unit] = layered;
        st.imageLayer[unit] = layer;
        st.imageAccess[unit] = access;
        st.imageFormat[unit] = format;
        st.textureGen++;
    }


    public int genSamplers() {
        int id = nextSampler++;
        while (samplerObjects.containsKey(id)) id = nextSampler++;
        samplerObjects.put(id, new SamplerObj(id));
        return id;
    }

    public void deleteSamplers(int sampler) {
        samplerObjects.remove(sampler);
        for (int u = 0; u < GlState.MAX_UNITS; u++) if (mainState.samplers[u] == sampler) mainState.samplers[u] = 0;
    }

    public boolean isSampler(int sampler) {
        return samplerObjects.containsKey(sampler);
    }

    public void bindSampler(int unit, int sampler) {
        if (unit < 0 || unit >= GlState.MAX_UNITS) return;
        final GlState st = st();
        st.samplers[unit] = sampler;
        st.textureGen++;
    }

    public void samplerParameteri(int sampler, int pname, int param) {
        samplerParam(samplerObjects.get(sampler), pname, param, param);
    }

    public void samplerParameterf(int sampler, int pname, float param) {
        samplerParam(samplerObjects.get(sampler), pname, (int) param, param);
    }

    private void samplerParam(SamplerObj s, int pname, int iv, float fv) {
        if (s == null) return;
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> s.minFilter = iv;
            case GL11.GL_TEXTURE_MAG_FILTER -> s.magFilter = iv;
            case GL11.GL_TEXTURE_WRAP_S -> s.wrapS = iv;
            case GL11.GL_TEXTURE_WRAP_T -> s.wrapT = iv;
            case GL12.GL_TEXTURE_WRAP_R -> s.wrapR = iv;
            case GL12.GL_TEXTURE_MIN_LOD -> s.minLod = fv;
            case GL12.GL_TEXTURE_MAX_LOD -> s.maxLod = fv;
            case GL14.GL_TEXTURE_LOD_BIAS -> s.lodBias = fv;
            case GL14.GL_TEXTURE_COMPARE_MODE -> s.compareMode = iv;
            case GL14.GL_TEXTURE_COMPARE_FUNC -> s.compareFunc = iv;
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> s.anisotropy = fv;
            default -> { }
        }
        s.dirty = true;
    }


    public int genFramebuffers() {
        return framebuffers.gen();
    }

    public int createFramebuffers() {
        return framebuffers.gen();
    }

    public void deleteFramebuffers(int framebuffer) {
        if (framebuffer == 0) return;
        framebuffers.delete(framebuffer);
        if (mainState.drawFbo == framebuffer) mainState.drawFbo = 0;
        if (mainState.readFbo == framebuffer) mainState.readFbo = 0;
        for (GlState s : otherStates) {
            if (s.drawFbo == framebuffer) s.drawFbo = 0;
            if (s.readFbo == framebuffer) s.readFbo = 0;
        }
    }

    public boolean isFramebuffer(int framebuffer) {
        return framebuffers.exists(framebuffer);
    }

    public void bindFramebuffer(int target, int framebuffer) {
        final GlState st = st();
        if (framebuffer != 0) framebuffers.getOrCreate(framebuffer);
        if (target == GL30.GL_FRAMEBUFFER || target == GL30.GL_DRAW_FRAMEBUFFER) st.drawFbo = framebuffer;
        if (target == GL30.GL_FRAMEBUFFER || target == GL30.GL_READ_FRAMEBUFFER) st.readFbo = framebuffer;
    }

    private Fbo fboFor(int target) {
        final GlState st = st();
        return framebuffers.get(target == GL30.GL_READ_FRAMEBUFFER ? st.readFbo : st.drawFbo);
    }

    public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (texture != 0) textures.getOrCreate(texture, textarget);
        final int layer = Textures.cubeFace(textarget);
        framebuffers.attach(fboFor(target), attachment, texture, false, level, layer, false);
    }

    public void framebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        framebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public void framebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {
        framebuffers.attach(fboFor(target), attachment, texture, false, level, zoffset, false);
    }

    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        framebuffers.attach(fboFor(target), attachment, texture, false, level, layer, false);
    }

    public void framebufferTexture(int target, int attachment, int texture, int level) {
        final Tex t = textures.get(texture);
        final boolean layered = t != null && (t.layers > 1 || t.is3D());
        framebuffers.attach(fboFor(target), attachment, texture, false, level, 0, layered);
    }

    public void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        final Tex t = textures.get(texture);
        final boolean layered = t != null && (t.layers > 1 || t.is3D());
        framebuffers.attach(framebuffers.getOrCreate(framebuffer), attachment, texture, false, level, 0, layered);
    }

    public void namedFramebufferReadBuffer(int framebuffer, int mode) {
        final Fbo f = framebuffers.getOrCreate(framebuffer);
        if (f.id == 0) {
            st().defaultReadNone = mode == GL11.GL_NONE;
            return;
        }
        f.readBuffer = mode == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(mode);
    }

    public void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs) {
        namedDrawBuffers(framebuffers.getOrCreate(framebuffer), bufs, st());
    }

    public int genRenderbuffers() {
        return textures.genRenderbuffer();
    }

    public void deleteRenderbuffers(int renderbuffer) {
        textures.deleteRenderbuffer(renderbuffer);
    }

    public boolean isRenderbuffer(int renderbuffer) {
        return textures.renderbufferExists(renderbuffer);
    }

    public void bindRenderbuffer(int target, int renderbuffer) {
        if (renderbuffer != 0) textures.renderbuffer(renderbuffer);
        st().renderbuffer = renderbuffer;
    }

    public void renderbufferStorage(int target, int internalformat, int width, int height) {
        final Tex t = textures.renderbuffer(st().renderbuffer);
        if (t == null) return;
        final Formats.Format f = textures.format(internalformat);
        if (t.hasImage() && t.format.vk() == f.vk() && t.width == width && t.height == height) return;
        images.release(t);
        t.format = f;
        t.width = Math.max(1, width);
        t.height = Math.max(1, height);
        t.depth = 1;
        t.layers = 1;
        t.levels = 1;
        images.create(t, false);
        renderer.targetGen++;
    }

    public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
        if (samples > 1) warnOnce("multisampled renderbuffers are single-sampled on Vulkan");
        renderbufferStorage(target, internalformat, width, height);
    }

    public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (renderbuffer != 0) textures.renderbuffer(renderbuffer);
        framebuffers.attach(fboFor(target), attachment, renderbuffer, true, 0, 0, false);
    }

    public int checkFramebufferStatus(int target) {
        return framebuffers.status(fboFor(target));
    }

    public int getFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        final Fbo f = fboFor(target);
        if (f == null) return 0;
        if (f.id == 0) {
            return switch (pname) {
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE -> GL30.GL_FRAMEBUFFER_DEFAULT;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE -> attachment == GL11.GL_DEPTH || attachment == GL30.GL_DEPTH_ATTACHMENT ? 24 : 0;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE -> attachment == GL11.GL_STENCIL || attachment == GL30.GL_STENCIL_ATTACHMENT ? 8 : 0;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE, GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE, GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE, GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE -> 8;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE -> GL31.GL_UNSIGNED_NORMALIZED;
                default -> 0;
            };
        }
        int tex;
        boolean rbo;
        int level;
        final int idx = attachment - GL30.GL_COLOR_ATTACHMENT0;
        if (idx >= 0 && idx < Fbo.MAX_COLOR) {
            tex = f.colorTex[idx];
            rbo = f.colorRbo[idx];
            level = f.colorLevel[idx];
        } else if (attachment == GL30.GL_DEPTH_ATTACHMENT || attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT) {
            tex = f.depthTex;
            rbo = f.depthRbo;
            level = f.depthLevel;
        } else if (attachment == GL30.GL_STENCIL_ATTACHMENT) {
            tex = f.stencilTex;
            rbo = f.stencilRbo;
            level = f.stencilLevel;
        } else {
            return 0;
        }
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) return tex == 0 ? GL11.GL_NONE : rbo ? GL30.GL_RENDERBUFFER : GL11.GL_TEXTURE;
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) return tex;
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) return level;
        final Tex t = textures.attachment(tex, rbo);
        if (t == null || t.format == null) return 0;
        return switch (pname) {
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE -> t.format.r();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE -> t.format.g();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE -> t.format.b();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE -> t.format.a();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE -> t.format.depthBits();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE -> t.format.stencilBits();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE -> switch (t.format.kind()) {
                case Formats.K_FLOAT, Formats.K_HALF -> GL11.GL_FLOAT;
                case Formats.K_UINT -> GL11.GL_UNSIGNED_INT;
                case Formats.K_SINT -> GL11.GL_INT;
                case Formats.K_SNORM -> GL31.GL_SIGNED_NORMALIZED;
                default -> GL31.GL_UNSIGNED_NORMALIZED;
            };
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING -> t.format.srgb() ? GL21.GL_SRGB : GL11.GL_LINEAR;
            default -> 0;
        };
    }

    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        final GlState st = st();
        blit(st, framebuffers.get(st.readFbo), framebuffers.get(st.drawFbo), srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        final GlState st = st();
        final int prevRead = st.readFbo, prevDraw = st.drawFbo;
        st.readFbo = readFramebuffer;
        st.drawFbo = drawFramebuffer;
        try {
            blit(st, framebuffers.get(readFramebuffer), framebuffers.get(drawFramebuffer), srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } finally {
            st.readFbo = prevRead;
            st.drawFbo = prevDraw;
        }
    }

    private final Target blitDst = new Target();

    private void blit(GlState st, Fbo read, Fbo draw, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        final Target src = renderer.readTarget(st);
        blitDst.fboId = Integer.MIN_VALUE;
        renderer.resolve(blitDst, draw, st, true);
        final Target dst = blitDst;
        renderer.endPass();
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0 && src.color[0] != null) {
            for (int i = 0; i < dst.colorCount; i++) {
                if (dst.color[i] == null) continue;
                blitImage(src.color[0], src.colorLevel[0], src.colorLayer[0], dst.color[i], dst.colorLevel[i], dst.colorLayer[i], VK_IMAGE_ASPECT_COLOR_BIT,
                    sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, filter == GL11.GL_LINEAR);
            }
        }
        final int dsMask = mask & (GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        if (dsMask != 0 && src.depth != null && dst.depth != null) {
            int aspect = 0;
            if ((dsMask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && src.hasDepth && dst.hasDepth) aspect |= VK_IMAGE_ASPECT_DEPTH_BIT;
            if ((dsMask & GL11.GL_STENCIL_BUFFER_BIT) != 0 && src.hasStencil && dst.hasStencil) aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;
            if (aspect != 0) {
                blitImage(src.depth, src.depthLevel, src.depthLayer, dst.depth, dst.depthLevel, dst.depthLayer, aspect, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, false);
            }
        }
    }

    private void blitImage(Tex s, int sLevel, int sLayer, Tex d, int dLevel, int dLayer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, boolean linear) {
        if (s == null || d == null || !s.hasImage() || !d.hasImage()) return;
        // clamp to image bounds; GL clips proportionally, which only matters for out-of-range blits
        final int sw = s.levelWidth(sLevel), sh = s.levelHeight(sLevel), dw = d.levelWidth(dLevel), dh = d.levelHeight(dLevel);
        sx0 = clamp(sx0, 0, sw); sx1 = clamp(sx1, 0, sw); sy0 = clamp(sy0, 0, sh); sy1 = clamp(sy1, 0, sh);
        dx0 = clamp(dx0, 0, dw); dx1 = clamp(dx1, 0, dw); dy0 = clamp(dy0, 0, dh); dy1 = clamp(dy1, 0, dh);
        if (sx0 == sx1 || sy0 == sy1 || dx0 == dx1 || dy0 == dy1) return;
        renderer.materialize(s);
        if (s != d) renderer.materialize(d);
        final VkCommandBuffer cmd = stream.cmd();
        final boolean same = s == d;
        final boolean ds = aspect != VK_IMAGE_ASPECT_COLOR_BIT;
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
            if (sameSize && (s.format.vk() == d.format.vk() || ds && s.format.vk() == d.format.vk())) {
                final org.lwjgl.vulkan.VkImageCopy.Buffer r = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
                r.srcSubresource().set(aspect, sLevel, sLayer, 1);
                r.srcOffset().set(Math.min(sx0, sx1), Math.min(sy0, sy1), 0);
                r.dstSubresource().set(aspect, dLevel, dLayer, 1);
                r.dstOffset().set(Math.min(dx0, dx1), Math.min(dy0, dy1), 0);
                r.extent().set(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0), 1);
                vkCmdCopyImage(cmd, s.image, srcLayout, d.image, dstLayout, r);
            } else if (ds && s.format.vk() != d.format.vk()) {
                warnOnce("depth blit between different formats");
            } else {
                final VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                b.srcSubresource().set(aspect, sLevel, sLayer, 1);
                b.srcOffsets(0).set(sx0, sy0, 0);
                b.srcOffsets(1).set(sx1, sy1, 1);
                b.dstSubresource().set(aspect, dLevel, dLayer, 1);
                b.dstOffsets(0).set(dx0, dy0, 0);
                b.dstOffsets(1).set(dx1, dy1, 1);
                final boolean canLinear = linear && !ds && images.supports(s.format.vk(), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
                vkCmdBlitImage(cmd, s.image, srcLayout, d.image, dstLayout, b, canLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            }
        }
        final long serial = stream.currentSerial();
        s.lastUse = serial;
        d.lastUse = serial;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        if (pixels != null) readPixelsImpl(x, y, width, height, format, type, memAddress(pixels));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        if (pixels != null) readPixelsImpl(x, y, width, height, format, type, memAddress(pixels));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        if (pixels != null) readPixelsImpl(x, y, width, height, format, type, memAddress(pixels));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {
        final GlState st = st();
        final Buf pbo = buffers.get(st.pixelPack);
        if (pbo == null) {
            if (pixelBufferOffset != 0L) readPixelsImpl(x, y, width, height, format, type, pixelBufferOffset);
            return;
        }
        if (!pbo.hostVisible()) {
            final ByteBuffer tmp = MemoryUtil.memAlloc((int) PixelConvert.clientBytes(width, height, 1, format, type, st.pack));
            try {
                readPixelsImpl(x, y, width, height, format, type, memAddress(tmp));
                buffers.write(pbo, pixelBufferOffset, tmp.remaining(), memAddress(tmp));
            } finally {
                MemoryUtil.memFree(tmp);
            }
            return;
        }
        if (!stream.isIdle(pbo.lastUse)) flushAndWait(pbo.lastUse);
        readPixelsImpl(x, y, width, height, format, type, pbo.address() + pixelBufferOffset);
    }

    private void readPixelsImpl(int x, int y, int w, int h, int format, int type, long dst) {
        final GlState st = st();
        final Target src = renderer.readTarget(st);
        final boolean depth = format == GL11.GL_DEPTH_COMPONENT || format == GL30.GL_DEPTH_STENCIL;
        final boolean stencil = format == GL11.GL_STENCIL_INDEX;
        final Tex t = depth || stencil ? src.depth : src.color[0];
        if (t == null) return;
        final int level = depth || stencil ? src.depthLevel : src.colorLevel[0];
        final int layer = depth || stencil ? src.depthLayer : src.colorLayer[0];
        final int x0 = Math.max(0, x), y0 = Math.max(0, y);
        final int x1 = Math.min(t.levelWidth(level), x + w), y1 = Math.min(t.levelHeight(level), y + h);
        if (x1 <= x0 || y1 <= y0) return;
        final int aspect = stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        // pixels outside the framebuffer are left untouched, as GL leaves them undefined
        final PixelConvert.Store pack = new PixelConvert.Store();
        pack.copyFrom(st.pack);
        pack.rowLength = st.pack.rowLength > 0 ? st.pack.rowLength : w;
        pack.skipPixels += x0 - x;
        pack.skipRows += y0 - y;
        textures.readImage(t, aspect, level, layer, x0, y0, x1 - x0, y1 - y0, format, type, pack, dst);
    }


    public int createShader(int type) {
        return programs.createShader(type);
    }

    public void deleteShader(int shader) {
        programs.deleteShader(shader);
    }

    public boolean isShader(int obj) {
        return programs.isShader(obj);
    }

    public void shaderSource(int shader, CharSequence source) {
        final Shader s = programs.shader(shader);
        if (s != null) s.source = source.toString();
    }

    public void compileShader(int shader) {
        final Shader s = programs.shader(shader);
        if (s != null) programs.compile(s);
    }

    public String getShaderSource(int shader, int maxLength) {
        final Shader s = programs.shader(shader);
        if (s == null) return "";
        return s.source.length() > maxLength && maxLength > 0 ? s.source.substring(0, maxLength) : s.source;
    }

    public void getShaderSource(int shader, IntBuffer length, ByteBuffer source) {
        final Shader s = programs.shader(shader);
        writeString(s == null ? "" : s.source, length, source);
    }

    public int createProgram() {
        return programs.createProgram();
    }

    public void deleteProgram(int program) {
        programs.deleteProgram(program, st());
    }

    public boolean isProgram(int obj) {
        return programs.isProgram(obj);
    }

    public void attachShader(int program, int shader) {
        programs.attach(programs.program(program), programs.shader(shader));
    }

    public void detachShader(int program, int shader) {
        programs.detach(programs.program(program), programs.shader(shader));
    }

    public void bindAttribLocation(int program, int index, CharSequence name) {
        final Program p = programs.program(program);
        if (p != null) p.attribBindings.put(name.toString(), index);
    }

    public void bindFragDataLocation(int program, int colorNumber, CharSequence name) {
        final Program p = programs.program(program);
        if (p != null) p.fragDataBindings.put(name.toString(), colorNumber);
    }

    public void linkProgram(int program) {
        final Program p = programs.program(program);
        if (p != null) programs.link(p);
    }

    public void validateProgram(int program) {
    }

    public void useProgram(int program) {
        final GlState st = st();
        if (st.program == program) return;
        final int prev = st.program;
        st.program = program;
        final Program old = programs.program(prev);
        if (old != null && old.deletePending && !anyStateUsesProgram(prev)) programs.deleteProgram(prev, st);
    }

    public String getShaderInfoLog(int shader, int maxLength) {
        final Shader s = programs.shader(shader);
        return truncate(s == null ? "" : s.log, maxLength);
    }

    public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {
        final Shader s = programs.shader(shader);
        writeString(s == null ? "" : s.log, length, infoLog);
    }

    public String getProgramInfoLog(int program, int maxLength) {
        final Program p = programs.program(program);
        return truncate(p == null ? "" : p.log, maxLength);
    }

    public void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {
        final Program p = programs.program(program);
        writeString(p == null ? "" : p.log, length, infoLog);
    }

    private static String truncate(String s, int max) {
        return max > 0 && s.length() >= max ? s.substring(0, Math.max(0, max - 1)) : s;
    }

    private static void writeString(String s, IntBuffer length, ByteBuffer out) {
        if (out == null) {
            if (length != null) length.put(length.position(), 0);
            return;
        }
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        final int n = Math.max(0, Math.min(bytes.length, out.remaining() - 1));
        for (int i = 0; i < n; i++) out.put(out.position() + i, bytes[i]);
        if (out.remaining() > 0) out.put(out.position() + n, (byte) 0);
        if (length != null) length.put(length.position(), n);
    }

    public int getShaderi(int shader, int pname) {
        return programs.shaderi(programs.shader(shader), pname);
    }

    public int getProgrami(int program, int pname) {
        return programs.programi(programs.program(program), pname);
    }

    public void getProgramiv(int program, int pname, IntBuffer params) {
        final Program p = programs.program(program);
        if (params == null || params.remaining() == 0) return;
        if (pname == GL43.GL_COMPUTE_WORK_GROUP_SIZE && p != null && params.remaining() >= 3) {
            params.put(params.position(), p.localX).put(params.position() + 1, p.localY).put(params.position() + 2, p.localZ);
            return;
        }
        params.put(params.position(), programs.programi(p, pname));
    }

    public String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) {
        final Program p = programs.program(program);
        if (p == null || index < 0 || index >= p.uniforms.size()) return "";
        final Program.Uniform u = p.uniforms.get(index);
        if (sizeType != null && sizeType.remaining() >= 2) {
            sizeType.put(sizeType.position(), Math.max(1, u.arraySize));
            sizeType.put(sizeType.position() + 1, u.glType);
        }
        return truncate(Programs.activeName(u), maxLength);
    }

    public void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        final Program p = programs.program(program);
        if (p == null || index < 0 || index >= p.uniforms.size()) return;
        final Program.Uniform u = p.uniforms.get(index);
        if (size != null) size.put(size.position(), Math.max(1, u.arraySize));
        if (type != null) type.put(type.position(), u.glType);
        writeString(Programs.activeName(u), length, name);
    }

    public String getActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) {
        final Program p = programs.program(program);
        if (p == null || index < 0 || index >= p.attribs.size()) return "";
        final Program.Attrib a = p.attribs.get(index);
        if (sizeType != null && sizeType.remaining() >= 2) {
            sizeType.put(sizeType.position(), 1);
            sizeType.put(sizeType.position() + 1, a.glType);
        }
        return truncate(a.name, maxLength);
    }

    public void getActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        final Program p = programs.program(program);
        if (p == null || index < 0 || index >= p.attribs.size()) return;
        final Program.Attrib a = p.attribs.get(index);
        if (size != null) size.put(size.position(), 1);
        if (type != null) type.put(type.position(), a.glType);
        writeString(a.name, length, name);
    }

    public int getAttribLocation(int program, CharSequence name) {
        final Program p = programs.program(program);
        if (p == null || !p.linked) return -1;
        final String n = name.toString();
        for (Program.Attrib a : p.attribs) if (a.name.equals(n)) return a.location;
        return -1;
    }

    public int getAttribLocation(int program, ByteBuffer name) {
        return getAttribLocation(program, MemoryUtil.memASCII(name));
    }

    public int getUniformLocation(int program, CharSequence name) {
        final Program p = programs.program(program);
        if (p == null || !p.linked) return -1;
        return p.locations.getInt(name.toString());
    }

    public int getUniformLocation(int program, ByteBuffer name) {
        return getUniformLocation(program, MemoryUtil.memASCII(name));
    }

    public int getUniformBlockIndex(int program, CharSequence name) {
        final Program p = programs.program(program);
        if (p == null) return GL31.GL_INVALID_INDEX;
        final String n = name.toString();
        for (int i = 0; i < p.ubos.size(); i++) if (p.ubos.get(i).name.equals(n)) return i;
        return GL31.GL_INVALID_INDEX;
    }

    public void uniformBlockBinding(int program, int blockIndex, int binding) {
        final Program p = programs.program(program);
        if (p == null || blockIndex < 0 || blockIndex >= p.ubos.size()) return;
        p.ubos.get(blockIndex).glBinding = binding;
    }

    public void shaderStorageBlockBinding(int program, int blockIndex, int binding) {
        final Program p = programs.program(program);
        if (p == null || blockIndex < 0 || blockIndex >= p.ssbos.size()) return;
        p.ssbos.get(blockIndex).glBinding = binding;
    }

    private Program currentProgram() {
        return programs.current(st());
    }

    private final long scratch = MemoryUtil.nmemAlloc(64);

    public void uniform1i(int location, int v0) {
        MemoryUtil.memPutInt(scratch, v0);
        programs.uniform(currentProgram(), location, 1, 1, false, scratch);
    }

    public void uniform2i(int location, int v0, int v1) {
        MemoryUtil.memPutInt(scratch, v0);
        MemoryUtil.memPutInt(scratch + 4, v1);
        programs.uniform(currentProgram(), location, 1, 2, false, scratch);
    }

    public void uniform3i(int location, int v0, int v1, int v2) {
        MemoryUtil.memPutInt(scratch, v0);
        MemoryUtil.memPutInt(scratch + 4, v1);
        MemoryUtil.memPutInt(scratch + 8, v2);
        programs.uniform(currentProgram(), location, 1, 3, false, scratch);
    }

    public void uniform4i(int location, int v0, int v1, int v2, int v3) {
        MemoryUtil.memPutInt(scratch, v0);
        MemoryUtil.memPutInt(scratch + 4, v1);
        MemoryUtil.memPutInt(scratch + 8, v2);
        MemoryUtil.memPutInt(scratch + 12, v3);
        programs.uniform(currentProgram(), location, 1, 4, false, scratch);
    }

    public void uniform1f(int location, float v0) {
        MemoryUtil.memPutFloat(scratch, v0);
        programs.uniform(currentProgram(), location, 1, 1, true, scratch);
    }

    public void uniform2f(int location, float v0, float v1) {
        MemoryUtil.memPutFloat(scratch, v0);
        MemoryUtil.memPutFloat(scratch + 4, v1);
        programs.uniform(currentProgram(), location, 1, 2, true, scratch);
    }

    public void uniform3f(int location, float v0, float v1, float v2) {
        MemoryUtil.memPutFloat(scratch, v0);
        MemoryUtil.memPutFloat(scratch + 4, v1);
        MemoryUtil.memPutFloat(scratch + 8, v2);
        programs.uniform(currentProgram(), location, 1, 3, true, scratch);
    }

    public void uniform4f(int location, float v0, float v1, float v2, float v3) {
        MemoryUtil.memPutFloat(scratch, v0);
        MemoryUtil.memPutFloat(scratch + 4, v1);
        MemoryUtil.memPutFloat(scratch + 8, v2);
        MemoryUtil.memPutFloat(scratch + 12, v3);
        programs.uniform(currentProgram(), location, 1, 4, true, scratch);
    }

    public void uniform1fv(int location, FloatBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining(), 1, true, memAddress(value));
    }

    public void uniform2(int location, FloatBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining() / 2, 2, true, memAddress(value));
    }

    public void uniform3(int location, FloatBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining() / 3, 3, true, memAddress(value));
    }

    public void uniform4(int location, FloatBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining() / 4, 4, true, memAddress(value));
    }

    public void uniform3fv(int location, float[] values) {
        withFloats(values, a -> programs.uniform(currentProgram(), location, values.length / 3, 3, true, a));
    }

    public void uniform4fv(int location, float[] values) {
        withFloats(values, a -> programs.uniform(currentProgram(), location, values.length / 4, 4, true, a));
    }

    private static void withFloats(float[] values, java.util.function.LongConsumer use) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final FloatBuffer b = stack.floats(values);
            use.accept(memAddress(b));
        }
    }

    public void uniform1iv(int location, IntBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining(), 1, false, memAddress(value));
    }

    public void uniform2iv(int location, IntBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining() / 2, 2, false, memAddress(value));
    }

    public void uniform3iv(int location, IntBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining() / 3, 3, false, memAddress(value));
    }

    public void uniform4iv(int location, IntBuffer value) {
        if (value != null) programs.uniform(currentProgram(), location, value.remaining() / 4, 4, false, memAddress(value));
    }

    public void uniformMatrix2(int location, boolean transpose, FloatBuffer value) {
        if (value != null) programs.uniformMatrix(currentProgram(), location, value.remaining() / 4, 2, 2, transpose, memAddress(value));
    }

    public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        if (value != null) programs.uniformMatrix(currentProgram(), location, value.remaining() / 9, 3, 3, transpose, memAddress(value));
    }

    public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        if (value != null) programs.uniformMatrix(currentProgram(), location, value.remaining() / 16, 4, 4, transpose, memAddress(value));
    }

    public void getUniformfv(int program, int location, FloatBuffer params) {
        if (params != null) programs.getUniform(programs.program(program), location, memAddress(params), true);
    }

    public void getUniformiv(int program, int location, IntBuffer params) {
        if (params != null) programs.getUniform(programs.program(program), location, memAddress(params), false);
    }


    public void vertexAttrib2f(int index, float v0, float v1) {
        vertexAttrib4f(index, v0, v1, 0f, 1f);
    }

    public void vertexAttrib3f(int index, float v0, float v1, float v2) {
        vertexAttrib4f(index, v0, v1, v2, 1f);
    }

    public void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final GlState st = st();
        final float[] a = st.attribValue;
        a[index * 4] = v0;
        a[index * 4 + 1] = v1;
        a[index * 4 + 2] = v2;
        a[index * 4 + 3] = v3;
    }

    public int genVertexArrays() {
        int id = nextVao++;
        while (vaos.containsKey(id)) id = nextVao++;
        vaos.put(id, new Vao(id));
        return id;
    }

    public void deleteVertexArrays(int array) {
        if (array == 0) return;
        vaos.remove(array);
        if (mainState.vao.id == array) mainState.vao = mainState.defaultVao;
        for (GlState s : otherStates) if (s.vao.id == array) s.vao = s.defaultVao;
    }

    public boolean isVertexArray(int array) {
        return vaos.containsKey(array);
    }

    public void bindVertexArray(int array) {
        final GlState st = st();
        if (array == 0) {
            st.vao = st.defaultVao;
            return;
        }
        Vao v = vaos.get(array);
        if (v == null) {
            v = new Vao(array);
            vaos.put(array, v);
        }
        st.vao = v;
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        setAttribPointer(index, size, type, normalized, false, stride, pointer);
    }

    public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        setAttribPointer(index, size, type, false, true, stride, pointer);
    }

    private void setAttribPointer(int index, int size, int type, boolean normalized, boolean integer, int stride, long pointer) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final GlState st = st();
        final Vao v = st.vao;
        final int buffer = st.arrayBuffer;
        if (v.size[index] != size || v.type[index] != type || v.normalized[index] != normalized || v.integer[index] != integer
            || v.bindingOf[index] != index || v.bindingStride[index] != stride || v.relativeOffset[index] != 0 || (v.bindingBuffer[index] == 0) != (buffer == 0)) {
            v.formatGen++;
        }
        v.size[index] = size;
        v.type[index] = type;
        v.normalized[index] = normalized;
        v.integer[index] = integer;
        v.relativeOffset[index] = 0;
        v.bindingOf[index] = index;
        v.bindingBuffer[index] = buffer;
        v.bindingOffset[index] = pointer;
        v.bindingStride[index] = stride;
        v.bufferGen++;
    }

    public void enableVertexAttribArray(int index) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        if (!v.enabled[index]) {
            v.enabled[index] = true;
            v.formatGen++;
        }
    }

    public void disableVertexAttribArray(int index) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        if (v.enabled[index]) {
            v.enabled[index] = false;
            v.formatGen++;
        }
    }

    public void vertexAttribDivisor(int index, int divisor) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        final int b = v.bindingOf[index];
        if (v.bindingDivisor[b] != divisor) {
            v.bindingDivisor[b] = divisor;
            v.formatGen++;
        }
        if (divisor > 1) warnOnce("vertex attribute divisor " + divisor + " is treated as 1");
    }

    public void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) {
        if (bindingindex < 0 || bindingindex >= Vao.MAX_BINDINGS) return;
        final Vao v = st().vao;
        if (v.bindingStride[bindingindex] != stride || (v.bindingBuffer[bindingindex] == 0) != (buffer == 0)) v.formatGen++;
        v.bindingBuffer[bindingindex] = buffer;
        v.bindingOffset[bindingindex] = offset;
        v.bindingStride[bindingindex] = stride;
        v.bufferGen++;
    }

    public void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) {
        if (attribindex < 0 || attribindex >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        v.size[attribindex] = size;
        v.type[attribindex] = type;
        v.normalized[attribindex] = normalized;
        v.integer[attribindex] = false;
        v.relativeOffset[attribindex] = relativeoffset;
        v.formatGen++;
    }

    public void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) {
        if (attribindex < 0 || attribindex >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        v.size[attribindex] = size;
        v.type[attribindex] = type;
        v.normalized[attribindex] = false;
        v.integer[attribindex] = true;
        v.relativeOffset[attribindex] = relativeoffset;
        v.formatGen++;
    }

    public void vertexAttribBinding(int attribindex, int bindingindex) {
        if (attribindex < 0 || attribindex >= Vao.MAX_ATTRIBS || bindingindex < 0 || bindingindex >= Vao.MAX_BINDINGS) return;
        final Vao v = st().vao;
        if (v.bindingOf[attribindex] != bindingindex) {
            v.bindingOf[attribindex] = bindingindex;
            v.formatGen++;
        }
    }


    public int genBuffers() {
        return buffers.gen();
    }

    public int createBuffers() {
        return buffers.gen();
    }

    public void deleteBuffers(int buffer) {
        if (buffer == 0) return;
        buffers.delete(buffer);
        unbindBufferEverywhere(buffer);
    }

    public void deleteBuffers(IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) deleteBuffers(ids.get(i));
    }

    private void unbindBufferEverywhere(int id) {
        unbindBuffer(mainState, id);
        for (GlState s : otherStates) unbindBuffer(s, id);
        for (Vao v : vaos.values()) {
            if (v.elementBuffer == id) v.elementBuffer = 0;
            for (int i = 0; i < Vao.MAX_BINDINGS; i++) if (v.bindingBuffer[i] == id) {
                v.bindingBuffer[i] = 0;
                v.formatGen++;
            }
        }
        clearDefaultVao(mainState.defaultVao, id);
        for (GlState s : otherStates) clearDefaultVao(s.defaultVao, id);
    }

    private static void clearDefaultVao(Vao v, int id) {
        if (v.elementBuffer == id) v.elementBuffer = 0;
        for (int i = 0; i < Vao.MAX_BINDINGS; i++) if (v.bindingBuffer[i] == id) {
            v.bindingBuffer[i] = 0;
            v.formatGen++;
        }
    }

    private static void unbindBuffer(GlState s, int id) {
        if (s.arrayBuffer == id) s.arrayBuffer = 0;
        if (s.uniformBuffer == id) s.uniformBuffer = 0;
        if (s.storageBuffer == id) s.storageBuffer = 0;
        if (s.copyRead == id) s.copyRead = 0;
        if (s.copyWrite == id) s.copyWrite = 0;
        if (s.pixelPack == id) s.pixelPack = 0;
        if (s.pixelUnpack == id) s.pixelUnpack = 0;
        if (s.drawIndirect == id) s.drawIndirect = 0;
        if (s.dispatchIndirect == id) s.dispatchIndirect = 0;
        if (s.parameterBuffer == id) s.parameterBuffer = 0;
        for (int i = 0; i < GlState.MAX_BUFFER_BINDINGS; i++) {
            if (s.uboBuffer[i] == id) s.uboBuffer[i] = 0;
            if (s.ssboBuffer[i] == id) s.ssboBuffer[i] = 0;
        }
    }

    public boolean isBuffer(int buffer) {
        return buffers.exists(buffer);
    }

    private int boundBuffer(GlState st, int target) {
        return switch (target) {
            case GL15.GL_ARRAY_BUFFER -> st.arrayBuffer;
            case GL15.GL_ELEMENT_ARRAY_BUFFER -> st.vao.elementBuffer;
            case GL31.GL_UNIFORM_BUFFER -> st.uniformBuffer;
            case GL43.GL_SHADER_STORAGE_BUFFER -> st.storageBuffer;
            case GL31.GL_COPY_READ_BUFFER -> st.copyRead;
            case GL31.GL_COPY_WRITE_BUFFER -> st.copyWrite;
            case GL21.GL_PIXEL_PACK_BUFFER -> st.pixelPack;
            case GL21.GL_PIXEL_UNPACK_BUFFER -> st.pixelUnpack;
            case GL40.GL_DRAW_INDIRECT_BUFFER -> st.drawIndirect;
            case GL43.GL_DISPATCH_INDIRECT_BUFFER -> st.dispatchIndirect;
            case GL31.GL_TEXTURE_BUFFER -> st.textureBuffer;
            case GL44.GL_QUERY_BUFFER -> st.queryBuffer;
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> st.atomicBuffer;
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> st.xfbBuffer;
            case ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB -> st.parameterBuffer;
            default -> 0;
        };
    }

    private Buf target(int target) {
        return buffers.get(boundBuffer(st(), target));
    }

    public void bindBuffer(int target, int buffer) {
        final GlState st = st();
        if (buffer != 0) buffers.getOrCreate(buffer);
        switch (target) {
            case GL15.GL_ARRAY_BUFFER -> st.arrayBuffer = buffer;
            case GL15.GL_ELEMENT_ARRAY_BUFFER -> st.vao.elementBuffer = buffer;
            case GL31.GL_UNIFORM_BUFFER -> st.uniformBuffer = buffer;
            case GL43.GL_SHADER_STORAGE_BUFFER -> st.storageBuffer = buffer;
            case GL31.GL_COPY_READ_BUFFER -> st.copyRead = buffer;
            case GL31.GL_COPY_WRITE_BUFFER -> st.copyWrite = buffer;
            case GL21.GL_PIXEL_PACK_BUFFER -> st.pixelPack = buffer;
            case GL21.GL_PIXEL_UNPACK_BUFFER -> st.pixelUnpack = buffer;
            case GL40.GL_DRAW_INDIRECT_BUFFER -> st.drawIndirect = buffer;
            case GL43.GL_DISPATCH_INDIRECT_BUFFER -> st.dispatchIndirect = buffer;
            case GL31.GL_TEXTURE_BUFFER -> st.textureBuffer = buffer;
            case GL44.GL_QUERY_BUFFER -> st.queryBuffer = buffer;
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> st.atomicBuffer = buffer;
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> st.xfbBuffer = buffer;
            case ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB -> st.parameterBuffer = buffer;
            default -> { }
        }
    }

    public void bindBufferBase(int target, int index, int buffer) {
        bindBufferRange(target, index, buffer, 0L, 0L);
    }

    public void bindBufferRange(int target, int index, int buffer, long offset, long size) {
        if (index < 0 || index >= GlState.MAX_BUFFER_BINDINGS) return;
        final GlState st = st();
        if (buffer != 0) buffers.getOrCreate(buffer);
        switch (target) {
            case GL31.GL_UNIFORM_BUFFER -> {
                st.uboBuffer[index] = buffer;
                st.uboOffset[index] = offset;
                st.uboSize[index] = size;
                st.uniformBuffer = buffer;
            }
            case GL43.GL_SHADER_STORAGE_BUFFER -> {
                st.ssboBuffer[index] = buffer;
                st.ssboOffset[index] = offset;
                st.ssboSize[index] = size;
                st.storageBuffer = buffer;
            }
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> {
                st.atomicBufferAt[index] = buffer;
                st.atomicOffset[index] = offset;
                st.atomicSize[index] = size;
                st.atomicBuffer = buffer;
            }
            default -> bindBuffer(target, buffer);
        }
        st.bufferBindingGen++;
    }

    public int getIndexedBufferBinding(int target, int index) {
        if (index < 0 || index >= GlState.MAX_BUFFER_BINDINGS) return 0;
        final GlState st = st();
        return target == GL43.GL_SHADER_STORAGE_BUFFER ? st.ssboBuffer[index] : st.uboBuffer[index];
    }

    public int getIntegerIndexed(int pname, int index) {
        final GlState st = st();
        if (index < 0) return 0;
        return switch (pname) {
            case GL31.GL_UNIFORM_BUFFER_BINDING -> index < GlState.MAX_BUFFER_BINDINGS ? st.uboBuffer[index] : 0;
            case GL43.GL_SHADER_STORAGE_BUFFER_BINDING -> index < GlState.MAX_BUFFER_BINDINGS ? st.ssboBuffer[index] : 0;
            case GL31.GL_UNIFORM_BUFFER_START -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.uboOffset[index] : 0;
            case GL31.GL_UNIFORM_BUFFER_SIZE -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.uboSize[index] : 0;
            case GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT -> 65535;
            case GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE -> index == 2 ? 64 : 1024;
            case GL30.GL_COLOR_WRITEMASK -> 0xF;
            case GL30.GL_DRAW_BUFFER0 -> GL11.GL_BACK;
            default -> getInteger(pname);
        };
    }

    public void bufferData(int target, long size, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, size, 0L, usage);
    }

    public void bufferData(int target, ByteBuffer data, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, data == null ? 0 : data.remaining(), data == null ? 0L : memAddress(data), usage);
    }

    public void bufferData(int target, FloatBuffer data, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, data.remaining() * 4L, memAddress(data), usage);
    }

    public void bufferData(int target, ShortBuffer data, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, data.remaining() * 2L, memAddress(data), usage);
    }

    public void bufferData(int target, IntBuffer data, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, data.remaining() * 4L, memAddress(data), usage);
    }

    public void bufferData(int target, DoubleBuffer data, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, data.remaining() * 8L, memAddress(data), usage);
    }

    public void bufferData(int target, int[] data, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer b = data.length * 4 < 32768 ? stack.ints(data) : null;
            if (b != null) {
                bufferData(target, b, usage);
                return;
            }
        }
        final IntBuffer heap = MemoryUtil.memAllocInt(data.length);
        try {
            heap.put(data).flip();
            bufferData(target, heap, usage);
        } finally {
            MemoryUtil.memFree(heap);
        }
    }

    public void bufferData(int target, float[] data, int usage) {
        final FloatBuffer heap = MemoryUtil.memAllocFloat(data.length);
        try {
            heap.put(data).flip();
            bufferData(target, heap, usage);
        } finally {
            MemoryUtil.memFree(heap);
        }
    }

    public void nbufferData(int target, long size, long data, int usage) {
        final Buf b = target(target);
        if (b != null) buffers.data(b, size, data, usage);
    }

    public void bufferSubData(int target, long offset, ByteBuffer data) {
        final Buf b = target(target);
        if (b != null && data != null) buffers.write(b, offset, data.remaining(), memAddress(data));
    }

    public void bufferSubData(int target, long offset, ShortBuffer data) {
        final Buf b = target(target);
        if (b != null && data != null) buffers.write(b, offset, data.remaining() * 2L, memAddress(data));
    }

    public void bufferSubData(int target, long offset, IntBuffer data) {
        final Buf b = target(target);
        if (b != null && data != null) buffers.write(b, offset, data.remaining() * 4L, memAddress(data));
    }

    public void bufferSubData(int target, long offset, FloatBuffer data) {
        final Buf b = target(target);
        if (b != null && data != null) buffers.write(b, offset, data.remaining() * 4L, memAddress(data));
    }

    public void bufferSubData(int target, long offset, DoubleBuffer data) {
        final Buf b = target(target);
        if (b != null && data != null) buffers.write(b, offset, data.remaining() * 8L, memAddress(data));
    }

    public void bufferStorage(int target, ByteBuffer data, int flags) {
        final Buf b = target(target);
        if (b != null) buffers.storage(b, data.remaining(), memAddress(data), flags);
    }

    public void bufferStorage(int target, long size, int flags) {
        final Buf b = target(target);
        if (b != null) buffers.storage(b, size, 0L, flags);
    }

    public void getBufferSubData(int target, long offset, ByteBuffer data) {
        if (data != null) buffers.read(target(target), offset, data.remaining(), memAddress(data));
    }

    public void getBufferSubData(int target, long offset, ShortBuffer data) {
        if (data != null) buffers.read(target(target), offset, data.remaining() * 2L, memAddress(data));
    }

    public void getBufferSubData(int target, long offset, IntBuffer data) {
        if (data != null) buffers.read(target(target), offset, data.remaining() * 4L, memAddress(data));
    }

    public void getBufferSubData(int target, long offset, FloatBuffer data) {
        if (data != null) buffers.read(target(target), offset, data.remaining() * 4L, memAddress(data));
    }

    public void getBufferSubData(int target, long offset, DoubleBuffer data) {
        if (data != null) buffers.read(target(target), offset, data.remaining() * 8L, memAddress(data));
    }

    public int getBufferParameteri(int target, int pname) {
        final Buf b = target(target);
        if (b == null) return 0;
        return switch (pname) {
            case GL15.GL_BUFFER_SIZE -> (int) Math.min(Integer.MAX_VALUE, b.size);
            case GL15.GL_BUFFER_USAGE -> b.usageHint == 0 ? GL15.GL_STATIC_DRAW : b.usageHint;
            case GL15.GL_BUFFER_MAPPED -> b.mapped ? 1 : 0;
            case GL15.GL_BUFFER_ACCESS -> GL15.GL_READ_WRITE;
            case GL30.GL_BUFFER_ACCESS_FLAGS -> b.mapAccess;
            case GL44.GL_BUFFER_IMMUTABLE_STORAGE -> b.immutable ? 1 : 0;
            case GL44.GL_BUFFER_STORAGE_FLAGS -> b.storageFlags;
            default -> 0;
        };
    }

    public ByteBuffer mapBuffer(int target, int access) {
        final Buf b = target(target);
        if (b == null) return null;
        final int flags = switch (access) {
            case GL15.GL_READ_ONLY -> GL30.GL_MAP_READ_BIT;
            case GL15.GL_WRITE_ONLY -> GL30.GL_MAP_WRITE_BIT;
            default -> GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT;
        };
        return buffers.map(b, 0, b.size, flags);
    }

    public ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer oldBuffer) {
        return mapBuffer(target, access);
    }

    public ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        return buffers.map(target(target), offset, length, access);
    }

    public boolean unmapBuffer(int target) {
        return buffers.unmap(target(target));
    }

    public void flushMappedBufferRange(int target, long offset, long length) {
        buffers.flushMapped(target(target), offset, length);
    }

    public void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        buffers.copy(target(readTarget), target(writeTarget), readOffset, writeOffset, size);
    }

    public void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data) {
        final Buf b = target(target);
        if (b == null) return;
        final int value = data == null || data.remaining() < 4 ? (data == null || !data.hasRemaining() ? 0 : (data.get(data.position()) & 0xFF) * 0x01010101) : data.getInt(data.position());
        buffers.fill(b, offset, size, value);
    }

    public void clearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) {
        final Buf b = target(target);
        if (b == null) return;
        clearBufferSubData(target, internalformat, 0, b.size, format, type, data);
    }

    public void namedBufferData(int buffer, long size, int usage) {
        final Buf b = buffers.getOrCreate(buffer);
        if (b != null) buffers.data(b, size, 0L, usage);
    }

    public void namedBufferData(int buffer, ByteBuffer data, int usage) {
        final Buf b = buffers.getOrCreate(buffer);
        if (b != null) buffers.data(b, data.remaining(), memAddress(data), usage);
    }

    public void namedBufferData(int buffer, FloatBuffer data, int usage) {
        final Buf b = buffers.getOrCreate(buffer);
        if (b != null) buffers.data(b, data.remaining() * 4L, memAddress(data), usage);
    }

    public void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        final Buf b = buffers.get(buffer);
        if (b != null && data != null) buffers.write(b, offset, data.remaining(), memAddress(data));
    }


    private static final String[] EXTENSIONS = {
        "GL_ARB_framebuffer_object", "GL_ARB_vertex_array_object", "GL_ARB_vertex_buffer_object", "GL_ARB_vertex_shader", "GL_ARB_fragment_shader",
        "GL_ARB_shader_objects", "GL_ARB_shading_language_100", "GL_ARB_texture_non_power_of_two", "GL_ARB_multitexture", "GL_ARB_sampler_objects",
        "GL_ARB_uniform_buffer_object", "GL_ARB_map_buffer_range", "GL_ARB_copy_buffer", "GL_ARB_copy_image", "GL_ARB_explicit_attrib_location",
        "GL_ARB_texture_storage", "GL_ARB_texture_swizzle", "GL_ARB_depth_clamp", "GL_ARB_seamless_cube_map", "GL_ARB_sync",
        "GL_ARB_draw_elements_base_vertex", "GL_ARB_instanced_arrays", "GL_ARB_draw_instanced", "GL_ARB_provoking_vertex", "GL_ARB_compute_shader",
        "GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_image_load_store", "GL_ARB_buffer_storage", "GL_ARB_clear_texture",
        "GL_ARB_clear_buffer_object", "GL_ARB_multi_draw_indirect", "GL_ARB_draw_indirect", "GL_ARB_vertex_attrib_binding", "GL_ARB_texture_rg",
        "GL_ARB_texture_float", "GL_ARB_half_float_vertex", "GL_ARB_depth_buffer_float", "GL_ARB_texture_cube_map_array", "GL_ARB_stencil_texturing",
        "GL_EXT_framebuffer_object", "GL_EXT_framebuffer_blit", "GL_EXT_framebuffer_multisample", "GL_EXT_framebuffer_sRGB",
        "GL_EXT_texture_filter_anisotropic", "GL_EXT_texture_compression_s3tc", "GL_EXT_blend_func_separate", "GL_EXT_blend_equation_separate",
        "GL_EXT_texture_sRGB", "GL_EXT_shader_image_load_store", "GL_EXT_gpu_shader4", "GL_KHR_debug", "GL_ARB_base_instance",
        "GL_ARB_shader_draw_parameters", "GL_ARB_indirect_parameters", "GL_ARB_texture_compression_rgtc", "GL_ARB_texture_compression_bptc",
    };

    public static java.util.Set<String> advertisedCapabilities(Gpu gpu) {
        final java.util.Set<String> caps = new java.util.HashSet<>();
        for (String v : new String[] {"OpenGL11", "OpenGL12", "OpenGL13", "OpenGL14", "OpenGL15", "OpenGL20", "OpenGL21", "OpenGL30", "OpenGL31", "OpenGL32",
            "OpenGL33", "OpenGL40", "OpenGL41", "OpenGL42", "OpenGL43", "OpenGL44", "OpenGL45", "OpenGL46"}) caps.add(v);
        for (String e : extensions(gpu)) caps.add(e);
        return caps;
    }

    static String[] extensions(Gpu gpu) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (String e : EXTENSIONS) {
            if (e.equals("GL_ARB_shader_draw_parameters") && !gpu.shaderDrawParameters) continue;
            if (e.equals("GL_ARB_indirect_parameters") && !gpu.drawIndirectCount) continue;
            if (e.equals("GL_ARB_base_instance") && !gpu.drawIndirectFirstInstance) continue;
            if (e.equals("GL_EXT_texture_compression_s3tc") && !gpu.textureCompressionBC) continue;
            if (e.equals("GL_ARB_texture_compression_bptc") && !gpu.textureCompressionBC) continue;
            if (e.equals("GL_ARB_texture_compression_rgtc") && !gpu.textureCompressionBC) continue;
            if (e.equals("GL_ARB_texture_cube_map_array") && !gpu.imageCubeArray) continue;
            out.add(e);
        }
        if (gpu.geometryShader) out.add("GL_ARB_geometry_shader4");
        if (gpu.tessellationShader) out.add("GL_ARB_tessellation_shader");
        return out.toArray(new String[0]);
    }

    private String[] cachedExtensions;

    private String[] ext() {
        if (cachedExtensions == null) cachedExtensions = extensions(gpu);
        return cachedExtensions;
    }

    public String vendorString() {
        if (gpu.isNvidia()) return "NVIDIA Corporation";
        if (gpu.isAmd()) return "AMD";
        if (gpu.isIntel()) return "Intel";
        if (gpu.isApple()) return "Apple";
        return "Vulkan";
    }

    public String rendererString() {
        return gpu.deviceName + " (Vulkan " + Gpu.versionString(gpu.apiVersion) + ", " + gpu.driverName + ")";
    }

    public String getString(int pname) {
        return switch (pname) {
            case GL11.GL_VENDOR -> vendorString();
            case GL11.GL_RENDERER -> rendererString();
            case GL11.GL_VERSION -> "4.6.0 Core Profile Context (Angelica Vulkan " + Gpu.versionString(gpu.apiVersion) + ")";
            case GL20.GL_SHADING_LANGUAGE_VERSION -> "4.60";
            case GL11.GL_EXTENSIONS -> String.join(" ", ext());
            default -> "";
        };
    }

    public String getStringi(int name, int index) {
        final String[] e = ext();
        return name == GL11.GL_EXTENSIONS && index >= 0 && index < e.length ? e[index] : "";
    }

    public int getError() {
        return GL11.GL_NO_ERROR;
    }

    public int getInteger(int pname) {
        final GlState st = st();
        return switch (pname) {
            case GL30.GL_MAJOR_VERSION -> 4;
            case GL30.GL_MINOR_VERSION -> 6;
            case GL30.GL_NUM_EXTENSIONS -> ext().length;
            case GL32.GL_CONTEXT_PROFILE_MASK -> GL32.GL_CONTEXT_CORE_PROFILE_BIT;
            case GL30.GL_CONTEXT_FLAGS -> 0;
            case GL11.GL_MAX_TEXTURE_SIZE -> Math.min(16384, gpu.maxImageDimension2D);
            case GL12.GL_MAX_3D_TEXTURE_SIZE -> Math.min(2048, gpu.maxImageDimension3D);
            case GL13.GL_MAX_CUBE_MAP_TEXTURE_SIZE -> Math.min(16384, gpu.maxImageDimension2D);
            case GL30.GL_MAX_ARRAY_TEXTURE_LAYERS -> Math.min(2048, gpu.maxImageArrayLayers);
            case GL30.GL_MAX_RENDERBUFFER_SIZE -> Math.min(16384, gpu.maxImageDimension2D);
            case GL20.GL_MAX_TEXTURE_IMAGE_UNITS, GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, GL32.GL_MAX_GEOMETRY_TEXTURE_IMAGE_UNITS -> Math.min(32, gpu.maxPerStageDescriptorSamplers);
            case GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> GlState.MAX_UNITS;
            case GL20.GL_MAX_VERTEX_ATTRIBS -> Vao.MAX_ATTRIBS;
            case GL20.GL_MAX_DRAW_BUFFERS, GL30.GL_MAX_COLOR_ATTACHMENTS -> Math.min(Fbo.MAX_COLOR, gpu.maxColorAttachments);
            case GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS -> GlState.MAX_BUFFER_BINDINGS;
            case GL31.GL_MAX_UNIFORM_BLOCK_SIZE -> 65536;
            case GL31.GL_MAX_VERTEX_UNIFORM_BLOCKS, GL31.GL_MAX_FRAGMENT_UNIFORM_BLOCKS, GL31.GL_MAX_GEOMETRY_UNIFORM_BLOCKS -> Math.min(12, gpu.maxPerStageDescriptorUniformBuffers - 1);
            case GL31.GL_MAX_COMBINED_UNIFORM_BLOCKS -> 36;
            case GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT -> (int) Math.max(16, gpu.minUniformBufferOffsetAlignment);
            case GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT -> (int) Math.max(16, gpu.minStorageBufferOffsetAlignment);
            case GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS -> Math.min(GlState.MAX_BUFFER_BINDINGS, 16);
            case GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE -> 1 << 27;
            case GL43.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS -> Math.min(16, gpu.maxPerStageDescriptorStorageBuffers);
            case GL42.GL_MAX_IMAGE_UNITS, GL42.GL_MAX_COMBINED_IMAGE_UNIFORMS -> GlState.MAX_IMAGE_UNITS;
            case GL42.GL_MAX_FRAGMENT_IMAGE_UNIFORMS, GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS -> Math.min(8, gpu.maxPerStageDescriptorStorageImages);
            case GL43.GL_MAX_COMPUTE_IMAGE_UNIFORMS -> Math.min(8, gpu.maxPerStageDescriptorStorageImages);
            case GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS -> gpu.maxComputeWorkGroupInvocations;
            case GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS, GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS, GL32.GL_MAX_GEOMETRY_UNIFORM_COMPONENTS -> 4096;
            case GL41.GL_MAX_VERTEX_UNIFORM_VECTORS, GL41.GL_MAX_FRAGMENT_UNIFORM_VECTORS -> 1024;
            case GL20.GL_MAX_VARYING_FLOATS -> 124;
            case GL41.GL_MAX_VARYING_VECTORS -> 31;
            case GL32.GL_MAX_VERTEX_OUTPUT_COMPONENTS, GL32.GL_MAX_FRAGMENT_INPUT_COMPONENTS -> 124;
            case GL30.GL_MAX_SAMPLES, GL32.GL_MAX_COLOR_TEXTURE_SAMPLES, GL32.GL_MAX_DEPTH_TEXTURE_SAMPLES -> 1;
            case GL13.GL_SAMPLES, GL13.GL_SAMPLE_BUFFERS -> 0;
            case GL12.GL_MAX_ELEMENTS_VERTICES, GL12.GL_MAX_ELEMENTS_INDICES -> 1 << 20;
            case GL15.GL_ARRAY_BUFFER_BINDING -> st.arrayBuffer;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING -> st.vao.elementBuffer;
            case GL30.GL_VERTEX_ARRAY_BINDING -> st.vao.id;
            case GL20.GL_CURRENT_PROGRAM -> st.program;
            case GL13.GL_ACTIVE_TEXTURE -> GL13.GL_TEXTURE0 + st.activeUnit;
            case GL11.GL_TEXTURE_BINDING_1D -> st.textures[st.activeUnit][GlState.T_1D];
            case GL11.GL_TEXTURE_BINDING_2D -> st.textures[st.activeUnit][GlState.T_2D];
            case GL12.GL_TEXTURE_BINDING_3D -> st.textures[st.activeUnit][GlState.T_3D];
            case GL13.GL_TEXTURE_BINDING_CUBE_MAP -> st.textures[st.activeUnit][GlState.T_CUBE];
            case GL30.GL_TEXTURE_BINDING_2D_ARRAY -> st.textures[st.activeUnit][GlState.T_2D_ARRAY];
            case GL30.GL_TEXTURE_BINDING_1D_ARRAY -> st.textures[st.activeUnit][GlState.T_1D_ARRAY];
            case GL31.GL_TEXTURE_BINDING_RECTANGLE -> st.textures[st.activeUnit][GlState.T_RECT];
            case GL31.GL_TEXTURE_BINDING_BUFFER -> st.textures[st.activeUnit][GlState.T_BUFFER];
            case GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY -> st.textures[st.activeUnit][GlState.T_CUBE_ARRAY];
            case GL33.GL_SAMPLER_BINDING -> st.samplers[st.activeUnit];
            case GL30.GL_DRAW_FRAMEBUFFER_BINDING -> st.drawFbo;
            case GL30.GL_READ_FRAMEBUFFER_BINDING -> st.readFbo;
            case GL30.GL_RENDERBUFFER_BINDING -> st.renderbuffer;
            case GL31.GL_UNIFORM_BUFFER_BINDING -> st.uniformBuffer;
            case GL43.GL_SHADER_STORAGE_BUFFER_BINDING -> st.storageBuffer;
            case GL21.GL_PIXEL_PACK_BUFFER_BINDING -> st.pixelPack;
            case GL21.GL_PIXEL_UNPACK_BUFFER_BINDING -> st.pixelUnpack;
            case GL42.GL_COPY_READ_BUFFER_BINDING -> st.copyRead;
            case GL42.GL_COPY_WRITE_BUFFER_BINDING -> st.copyWrite;
            case GL40.GL_DRAW_INDIRECT_BUFFER_BINDING -> st.drawIndirect;
            case GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING -> st.dispatchIndirect;
            case GL11.GL_BLEND_SRC, GL14.GL_BLEND_SRC_RGB -> st.blendSrcRgb[0];
            case GL11.GL_BLEND_DST, GL14.GL_BLEND_DST_RGB -> st.blendDstRgb[0];
            case GL14.GL_BLEND_SRC_ALPHA -> st.blendSrcA[0];
            case GL14.GL_BLEND_DST_ALPHA -> st.blendDstA[0];
            case GL14.GL_BLEND_EQUATION, GL20.GL_BLEND_EQUATION_ALPHA -> pname == GL14.GL_BLEND_EQUATION ? st.blendEqRgb[0] : st.blendEqA[0];
            case GL11.GL_DEPTH_FUNC -> st.depthFunc;
            case GL11.GL_CULL_FACE_MODE -> st.cullFace;
            case GL11.GL_FRONT_FACE -> st.frontFace;
            case GL11.GL_STENCIL_FUNC -> st.stencilFunc[0];
            case GL11.GL_STENCIL_REF -> st.stencilRef[0];
            case GL11.GL_STENCIL_VALUE_MASK -> st.stencilValueMask[0];
            case GL11.GL_STENCIL_WRITEMASK -> st.stencilWriteMask[0];
            case GL11.GL_STENCIL_FAIL -> st.stencilFail[0];
            case GL11.GL_STENCIL_PASS_DEPTH_FAIL -> st.stencilDepthFail[0];
            case GL11.GL_STENCIL_PASS_DEPTH_PASS -> st.stencilPass[0];
            case GL20.GL_STENCIL_BACK_FUNC -> st.stencilFunc[1];
            case GL20.GL_STENCIL_BACK_REF -> st.stencilRef[1];
            case GL20.GL_STENCIL_BACK_VALUE_MASK -> st.stencilValueMask[1];
            case GL20.GL_STENCIL_BACK_WRITEMASK -> st.stencilWriteMask[1];
            case GL11.GL_STENCIL_CLEAR_VALUE -> st.clearStencil;
            case GL11.GL_UNPACK_ALIGNMENT -> st.unpack.alignment;
            case GL11.GL_UNPACK_ROW_LENGTH -> st.unpack.rowLength;
            case GL11.GL_UNPACK_SKIP_ROWS -> st.unpack.skipRows;
            case GL11.GL_UNPACK_SKIP_PIXELS -> st.unpack.skipPixels;
            case GL11.GL_PACK_ALIGNMENT -> st.pack.alignment;
            case GL11.GL_PACK_ROW_LENGTH -> st.pack.rowLength;
            case GL11.GL_PACK_SKIP_ROWS -> st.pack.skipRows;
            case GL11.GL_PACK_SKIP_PIXELS -> st.pack.skipPixels;
            case GL11.GL_DRAW_BUFFER -> drawBufferEnum(st, 0);
            case GL11.GL_READ_BUFFER -> readBufferEnum(st);
            case GL11.GL_POLYGON_MODE -> st.polygonModeFront;
            case GL11.GL_LOGIC_OP_MODE -> st.logicOp;
            case GL11.GL_DEPTH_BITS -> 24;
            case GL11.GL_STENCIL_BITS -> 8;
            case GL11.GL_RED_BITS, GL11.GL_GREEN_BITS, GL11.GL_BLUE_BITS, GL11.GL_ALPHA_BITS -> 8;
            case GL32.GL_PROVOKING_VERTEX -> st.provokingFirst ? GL32.GL_FIRST_VERTEX_CONVENTION : GL32.GL_LAST_VERTEX_CONVENTION;
            case GL31.GL_PRIMITIVE_RESTART_INDEX -> st.primitiveRestartIndex;
            case EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT -> (int) gpu.maxSamplerAnisotropy;
            case KHRDebug.GL_MAX_DEBUG_GROUP_STACK_DEPTH -> 64;
            case KHRDebug.GL_MAX_LABEL_LENGTH -> 256;
            case KHRDebug.GL_MAX_DEBUG_MESSAGE_LENGTH -> 1024;
            case KHRDebug.GL_MAX_DEBUG_LOGGED_MESSAGES -> 64;
            case NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX -> (int) Math.min(Integer.MAX_VALUE, deviceLocalBytes() / 1024);
            case NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX -> (int) Math.min(Integer.MAX_VALUE, Math.max(0, deviceLocalBytes() - allocator.allocatedBytes()) / 1024);
            case GL43.GL_MAX_FRAMEBUFFER_WIDTH, GL43.GL_MAX_FRAMEBUFFER_HEIGHT -> Math.min(16384, gpu.maxImageDimension2D);
            case GL43.GL_MAX_FRAMEBUFFER_LAYERS -> 256;
            case GL32.GL_MAX_GEOMETRY_OUTPUT_VERTICES -> 256;
            case GL32.GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS -> 1024;
            case GL40.GL_PATCH_VERTICES -> patchVertices;
            case GL40.GL_MAX_PATCH_VERTICES -> 32;
            case GL30.GL_MAX_PROGRAM_TEXEL_OFFSET -> 7;
            case GL30.GL_MIN_PROGRAM_TEXEL_OFFSET -> -8;
            case GL43.GL_MAX_UNIFORM_LOCATIONS -> 4096;
            case GL41.GL_NUM_PROGRAM_BINARY_FORMATS, GL41.GL_NUM_SHADER_BINARY_FORMATS -> 0;
            case GL46.GL_NUM_SPIR_V_EXTENSIONS -> 0;
            default -> {
                if (pname >= GL20.GL_DRAW_BUFFER0 && pname < GL20.GL_DRAW_BUFFER0 + Fbo.MAX_COLOR) yield drawBufferEnum(st, pname - GL20.GL_DRAW_BUFFER0);
                warnOnce("glGetInteger(0x" + Integer.toHexString(pname) + ") is not tracked, returning 0");
                yield 0;
            }
        };
    }

    private long deviceLocalBytes() {
        long total = 0;
        for (int i = 0; i < gpu.memoryProperties.memoryHeapCount(); i++) {
            if ((gpu.memoryProperties.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) total = Math.max(total, gpu.memoryProperties.memoryHeaps(i).size());
        }
        return total;
    }

    private int drawBufferEnum(GlState st, int i) {
        final Fbo f = framebuffers.get(st.drawFbo);
        if (f == null || f.id == 0) return i == 0 && !st.defaultDrawNone ? GL11.GL_BACK : GL11.GL_NONE;
        if (i >= f.drawBuffers.length || f.drawBuffers[i] < 0) return GL11.GL_NONE;
        return GL30.GL_COLOR_ATTACHMENT0 + f.drawBuffers[i];
    }

    private int readBufferEnum(GlState st) {
        final Fbo f = framebuffers.get(st.readFbo);
        if (f == null || f.id == 0) return st.defaultReadNone ? GL11.GL_NONE : GL11.GL_BACK;
        return f.readBuffer < 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0 + f.readBuffer;
    }

    public void getInteger(int pname, IntBuffer params) {
        if (params == null || params.remaining() == 0) return;
        final GlState st = st();
        final int p = params.position();
        switch (pname) {
            case GL11.GL_VIEWPORT -> {
                if (params.remaining() >= 4) params.put(p, st.vpX).put(p + 1, st.vpY).put(p + 2, st.vpW).put(p + 3, st.vpH);
            }
            case GL11.GL_SCISSOR_BOX -> {
                if (params.remaining() >= 4) params.put(p, st.scX).put(p + 1, st.scY).put(p + 2, st.scW).put(p + 3, st.scH);
            }
            case GL11.GL_MAX_VIEWPORT_DIMS -> {
                if (params.remaining() >= 2) params.put(p, gpu.maxImageDimension2D).put(p + 1, gpu.maxImageDimension2D);
            }
            case GL11.GL_COLOR_WRITEMASK -> {
                if (params.remaining() >= 4) for (int i = 0; i < 4; i++) params.put(p + i, st.colorMask[0][i] ? 1 : 0);
            }
            case GL11.GL_POLYGON_MODE -> {
                params.put(p, st.polygonModeFront);
                if (params.remaining() >= 2) params.put(p + 1, st.polygonModeBack);
            }
            default -> params.put(p, getInteger(pname));
        }
    }

    public float getFloat(int pname) {
        final GlState st = st();
        return switch (pname) {
            case GL11.GL_LINE_WIDTH -> st.lineWidth;
            case GL11.GL_POINT_SIZE -> st.pointSize;
            case GL11.GL_DEPTH_CLEAR_VALUE -> (float) st.clearDepth;
            case GL11.GL_POLYGON_OFFSET_FACTOR -> st.polyFactor;
            case GL11.GL_POLYGON_OFFSET_UNITS -> st.polyUnits;
            case EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT -> gpu.maxSamplerAnisotropy;
            case GL14.GL_MAX_TEXTURE_LOD_BIAS -> 15f;
            default -> getInteger(pname);
        };
    }

    public void getFloat(int pname, FloatBuffer params) {
        if (params == null || params.remaining() == 0) return;
        final GlState st = st();
        final int p = params.position();
        switch (pname) {
            case GL11.GL_COLOR_CLEAR_VALUE -> {
                if (params.remaining() >= 4) for (int i = 0; i < 4; i++) params.put(p + i, st.clearColor[i]);
            }
            case GL14.GL_BLEND_COLOR -> {
                if (params.remaining() >= 4) for (int i = 0; i < 4; i++) params.put(p + i, st.blendColor[i]);
            }
            case GL11.GL_DEPTH_RANGE -> {
                if (params.remaining() >= 2) params.put(p, (float) st.depthNear).put(p + 1, (float) st.depthFar);
            }
            case GL12.GL_ALIASED_LINE_WIDTH_RANGE, GL11.GL_LINE_WIDTH_RANGE -> {
                if (params.remaining() >= 2) params.put(p, gpu.lineWidthMin).put(p + 1, gpu.lineWidthMax);
            }
            case GL12.GL_ALIASED_POINT_SIZE_RANGE, GL11.GL_POINT_SIZE_RANGE -> {
                if (params.remaining() >= 2) params.put(p, 1f).put(p + 1, gpu.pointSizeMax);
            }
            case GL11.GL_VIEWPORT -> {
                if (params.remaining() >= 4) params.put(p, st.vpX).put(p + 1, st.vpY).put(p + 2, st.vpW).put(p + 3, st.vpH);
            }
            default -> params.put(p, getFloat(pname));
        }
    }

    public boolean getBoolean(int pname) {
        final GlState st = st();
        return switch (pname) {
            case GL11.GL_DEPTH_WRITEMASK -> st.depthMask;
            case GL11.GL_UNPACK_SWAP_BYTES -> st.unpack.swapBytes;
            case GL11.GL_PACK_SWAP_BYTES -> st.pack.swapBytes;
            default -> isEnabled(pname);
        };
    }

    public void getBoolean(int pname, ByteBuffer params) {
        if (params == null || params.remaining() == 0) return;
        final GlState st = st();
        if (pname == GL11.GL_COLOR_WRITEMASK && params.remaining() >= 4) {
            for (int i = 0; i < 4; i++) params.put(params.position() + i, (byte) (st.colorMask[0][i] ? 1 : 0));
            return;
        }
        params.put(params.position(), (byte) (getBoolean(pname) ? 1 : 0));
    }


    private static final int SIGNALED = GL32.GL_SIGNALED, UNSIGNALED = GL32.GL_UNSIGNALED;

    public long fenceSync(int condition, int flags) {
        return stream.currentSerial();
    }

    public int clientWaitSync(long sync, int flags, long timeout) {
        if (sync <= 0L) return GL32.GL_WAIT_FAILED;
        stream.poll();
        if (stream.isIdle(sync)) return GL32.GL_ALREADY_SIGNALED;
        if (sync >= stream.currentSerial()) {
            if ((flags & GL32.GL_SYNC_FLUSH_COMMANDS_BIT) == 0 && timeout == 0L) return GL32.GL_TIMEOUT_EXPIRED;
            renderer.endPass();
            stream.submit(0L, 0, 0L);
        }
        if (timeout == 0L) {
            stream.poll();
            return stream.isIdle(sync) ? GL32.GL_ALREADY_SIGNALED : GL32.GL_TIMEOUT_EXPIRED;
        }
        return stream.waitFor(sync, timeout) ? GL32.GL_CONDITION_SATISFIED : GL32.GL_TIMEOUT_EXPIRED;
    }

    public void waitSync(long sync, int flags, long timeout) {
        // one queue executes in order; server waits are implicit
    }

    public void deleteSync(long sync) {
    }

    public int getSyncStatus(long sync) {
        stream.poll();
        return stream.isIdle(sync) ? SIGNALED : UNSIGNALED;
    }

    public int getSynci(long sync, int pname, IntBuffer length) {
        final int v = switch (pname) {
            case GL32.GL_OBJECT_TYPE -> GL32.GL_SYNC_FENCE;
            case GL32.GL_SYNC_CONDITION -> GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
            case GL32.GL_SYNC_FLAGS -> 0;
            default -> getSyncStatus(sync);
        };
        if (length != null && length.remaining() > 0) length.put(length.position(), v);
        return v;
    }


    public void objectLabel(int identifier, int name, CharSequence label) {
        final String l = label == null ? null : label.toString();
        switch (identifier) {
            case GL11.GL_TEXTURE -> {
                final Tex t = textures.get(name);
                if (t != null) {
                    t.label = l;
                    if (t.image != 0L) debug.name(VK_OBJECT_TYPE_IMAGE, t.image, l);
                }
            }
            case KHRDebug.GL_BUFFER -> {
                final Buf b = buffers.get(name);
                if (b != null) b.label = l;
            }
            case KHRDebug.GL_PROGRAM -> {
                final Program p = programs.program(name);
                if (p != null) p.label = l;
            }
            case KHRDebug.GL_SHADER -> {
                final Shader s = programs.shader(name);
                if (s != null) s.label = l;
            }
            default -> { }
        }
    }

    public void pushDebugGroup(int source, int id, CharSequence message) {
        debug.push(message.toString());
    }

    public void popDebugGroup() {
        debug.pop();
    }


    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap queryResults = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap activeQueries = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap queryStart = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    private int nextQuery = 1;

    public int genQueries() {
        final int id = nextQuery++;
        queryResults.put(id, 0L);
        return id;
    }

    public void genQueries(IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) ids.put(i, genQueries());
    }

    public void deleteQueries(int id) {
        queryResults.remove(id);
    }

    public boolean isQuery(int query) {
        return queryResults.containsKey(query);
    }

    public void beginQuery(int target, int id) {
        activeQueries.put(target, id);
        queryStart.put(id, System.nanoTime());
        queryResults.put(id, 0L);
    }

    public void endQuery(int target) {
        if (!activeQueries.containsKey(target)) return;
        final int id = activeQueries.remove(target);
        final long result = switch (target) {
            case GL33.GL_TIME_ELAPSED -> System.nanoTime() - queryStart.get(id);
            case GL15.GL_SAMPLES_PASSED -> Integer.MAX_VALUE;
            default -> 1L;
        };
        queryResults.put(id, result);
    }

    public void queryCounter(int id, int target) {
        queryResults.put(id, System.nanoTime());
    }

    private long queryValue(int id, int pname) {
        if (pname == GL15.GL_QUERY_RESULT_AVAILABLE) return 1L;
        return queryResults.get(id);
    }

    public void getQueryObjectui(int id, int pname, IntBuffer params) {
        if (params != null && params.remaining() > 0) params.put(params.position(), (int) Math.min(Integer.MAX_VALUE, queryValue(id, pname)));
    }

    public int getQueryObjecti(int id, int pname) {
        return (int) Math.min(Integer.MAX_VALUE, queryValue(id, pname));
    }

    public long getQueryObjectui64(int id, int pname) {
        return queryValue(id, pname);
    }
}
