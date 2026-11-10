package com.gtnewhorizons.angelica.metal.gl;

import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.DisplayVblank;
import com.gtnewhorizons.angelica.glemu.frame.PresentSurface;
import com.gtnewhorizons.angelica.glemu.gl.OcclusionQueries;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.gl.TokenGlBase;
import com.gtnewhorizons.angelica.glemu.pipeline.ClearPass;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.MetalLayer;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.frame.CommandStream;
import com.gtnewhorizons.angelica.metal.frame.HostRing;
import com.gtnewhorizons.angelica.metal.frame.Presenter;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;
import com.gtnewhorizons.angelica.metal.pipeline.BlitPass;
import com.gtnewhorizons.angelica.metal.resource.Buf;
import com.gtnewhorizons.angelica.metal.resource.Buffers;
import com.gtnewhorizons.angelica.metal.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.metal.resource.Images;
import com.gtnewhorizons.angelica.metal.resource.Target;
import com.gtnewhorizons.angelica.metal.resource.Tex;
import com.gtnewhorizons.angelica.metal.resource.Textures;
import com.gtnewhorizons.angelica.metal.shader.Program;
import com.gtnewhorizons.angelica.metal.shader.Programs;
import com.gtnewhorizons.angelica.metal.util.DebugNames;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class MetalGL extends TokenGlBase<Tex, Program, Buf, MtlBuffer> {
    @Override protected int vertexStrideAlignment() {
        return 4;
    }

    private static final Tracy.ZoneId Z_PRESENT = Tracy.zoneId("mtlPresentFrame", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_FRAME_SLOT_WAIT = Tracy.zoneId("mtlFrameSlotWait", Tracy.COLOR_SWAP);

    private static final long DRAW = sel("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:");
    private static final long DRAW_INDEXED = sel("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:");
    private static final long DRAW_INDEXED_INDIRECT = sel("drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:indirectBuffer:indirectBufferOffset:");
    private static final long DISPATCH = sel("dispatchThreadgroups:threadsPerThreadgroup:");
    private static final long DISPATCH_INDIRECT = sel("dispatchThreadgroupsWithIndirectBuffer:indirectBufferOffset:threadsPerThreadgroup:");

    public final Gpu gpu;
    public final CommandStream stream;
    public final HostRing uniformRing;
    public final HostRing stagingRing;
    public final Images images;
    public final Textures textures;
    public final Buffers buffers;
    public final Programs programs;
    public final Renderer renderer;
    public final DefaultFramebuffer defaultFb;
    public final ClearPass<Tex> clearPass;
    public final BlitPass blitPass;
    public final DebugNames debug;

    private MetalLayer layer;
    private Presenter presenter;
    private DisplayVblank vblank;
    private long lastSubmitNanos;
    private final int framesInFlight = SystemProperties.METAL_FRAMES_IN_FLIGHT;

    // A pool token belongs to the thread that pushed it, and any thread may hold the emulated context.
    private final ThreadLocal<long[]> framePool = ThreadLocal.withInitial(() -> new long[1]);
    private long statsGpu;
    private long statsFrames;
    private static final Tex PROXY_DEFAULTS = new Tex(-1, false);
    private final Target blitDst = new Target();

    public MetalGL(Gpu gpu, Thread mainThread) {
        this.gpu = gpu;
        gpu.driverMessages = this::postDriverMessage;
        this.mainThread = mainThread;
        this.stream = new CommandStream(gpu);
        this.uniformRing = new HostRing(gpu, stream, 8L << 20, MtlBuffer.USAGE_STREAM);
        this.stagingRing = new HostRing(gpu, stream, 32L << 20, MtlBuffer.USAGE_UPLOAD);
        this.debug = new DebugNames(SystemProperties.METAL_DEBUG);
        this.images = new Images(this);
        this.textures = new Textures(this);
        this.buffers = new Buffers(this);
        this.programs = new Programs(this);
        this.renderer = new Renderer(this);
        this.defaultFb = new DefaultFramebuffer(this);
        this.clearPass = new ClearPass<>(this);
        this.blitPass = new BlitPass(this);
        this.mainState = new GlState();
        stream.setBeforeSubmit(renderer::endPass);
        occlusionDevice = new NativeOcclusion();
        occlusion = new OcclusionQueries(this, occlusionDevice);
        renderer.init();
    }

    public void attachWindow(long window, long metalLayer) {
        this.window = window;
        this.layer = new MetalLayer(gpu, metalLayer, framesInFlight);
        if (!blitPass.preparePresent(MetalLayer.FORMAT)) throw new IllegalStateException("Metal could not build the present pipeline");
        this.presenter = new Presenter(gpu, layer, blitPass);
        this.vblank = DisplayVblank.create(window);
        final long size = windowPixels();
        defaultFb.ensure((int) (size >>> 32), (int) size);
        final VSyncMode v = pendingVSync;
        if (v != null) layer.setVSync(v);
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
        // Getters on this thread autorelease their results; without a drained pool they would pile up until the thread exits
        final long[] pool = framePool.get();
        if (pool[0] != 0L) ObjC.poolPop(pool[0]);
        pool[0] = ObjC.poolPush();
        super.presentFrame();
    }

    @Override protected void endFrameRecording() {
        renderer.endPass(true);
    }

    @Override protected DefaultFramebufferBase<Tex> defaultFramebuffer() {
        return defaultFb;
    }

    @Override protected HostRing stagingRing() {
        return stagingRing;
    }

    @Override public boolean statsEnabled() {
        return SystemProperties.METAL_STATS;
    }

    @Override protected boolean hasPresentTarget() {
        return presenter != null;
    }

    @Override protected boolean handOffFrame(Tex color, int windowWidth, int windowHeight) {
        lastSubmitNanos = System.nanoTime();
        // The presenter thread blits the back buffer to the drawable; with vsync off a frame whose predecessor is still pending is skipped.
        return presenter.present(ObjC.retain(color.image), color.width, color.height, windowWidth, windowHeight, layer.vsyncMode() != VSyncMode.OFF);
    }

    public long committedPresents() {
        return presenter != null ? presenter.committed() : 0L;
    }

    public void drainPresents() {
        if (presenter != null) presenter.drain();
    }

    @Override public void followWindowDisplay() {
        if (vblank != null) vblank.retarget();
    }

    @Override public long awaitPresent() {
        return hasPresentWait() ? vblank.awaitPresented(lastSubmitNanos, 1) : 0L;
    }

    @Override public boolean hasPresentWait() {
        return vblank != null && layer != null && layer.vsyncMode() != VSyncMode.OFF;
    }

    @Override protected void resolveImage(Tex s, int sLayer, Tex d, int dLevel, int dLayer, int x, int y, int dx, int dy, int width, int height) {
        rendererBase().materialize(d);
        rendererBase().endPass();
        final boolean whole = x == 0 && y == 0 && dx == 0 && dy == 0 && width == s.width && width == d.levelWidth(dLevel) && height == s.height && height == d.levelHeight(dLevel);
        if (whole) {
            blitPass.resolveWhole(s, sLayer, d, dLevel, dLayer);
            d.markWritten();
            d.needsClear = false;
            return;
        }
        // Metal only resolves whole attachments, so a part goes through an image of the whole size.
        final Tex middle = textures().internalTexture(GL11.GL_TEXTURE_2D, s.format, s.width, s.height, 1, 1, "resolve");
        blitPass.resolveWhole(s, sLayer, middle, 0, 0);
        blitClipped(middle, 0, 0, d, dLevel, dLayer, s.format.aspect(), x, y, x + width, y + height, dx, dy, dx + width, dy + height, false);
        releaseImage(middle);
    }

    @Override protected void resolveDepthStencil(Tex s, int sLayer, Tex d, int dLevel, int dLayer, int aspect, int x, int y, int dx, int dy, int width, int height) {
        rendererBase().endPass();
        final boolean whole = x == 0 && y == 0 && dx == 0 && dy == 0 && width == s.width && width == d.levelWidth(dLevel) && height == s.height && height == d.levelHeight(dLevel);
        final boolean stencilAlone = s.format.glInternal() == org.lwjgl.opengl.GL30.GL_STENCIL_INDEX8 || s.format.glInternal() == GL11.GL_STENCIL_INDEX;
        if (whole && (aspect == s.format.aspect() || stencilAlone)) {
            blitPass.resolveDepthStencilWhole(s, sLayer, d, dLevel, dLayer);
            d.markWritten();
            d.needsClear = false;
            return;
        }
        final Tex middle = textures().internalTexture(GL11.GL_TEXTURE_2D, s.format, s.width, s.height, 1, 1, "resolve");
        blitPass.resolveDepthStencilWhole(s, sLayer, middle, 0, 0);
        blitClipped(middle, 0, 0, d, dLevel, dLayer, aspect, x, y, x + width, y + height, dx, dy, dx + width, dy + height, false);
        releaseImage(middle);
    }

    void occlusionBegan() {
        occlusionPassBegan();
    }

    void occlusionEnding() {
        occlusionPassEnding();
    }

    long occlusionBuffer() {
        return occlusionDevice.buffer.buffer;
    }

    private NativeOcclusion occlusionDevice;

    private final class NativeOcclusion implements OcclusionQueries.Device {
        private static final int SLOTS = 4096;
        private static final long SET_MODE = sel("setVisibilityResultMode:offset:");
        private static final long MODE_OFF = 0L, MODE_COUNTING = 2L;
        final MtlBuffer buffer = MtlBuffer.create(gpu, SLOTS * 8L, MtlBuffer.USAGE_STREAM);

        @Override public int capacity() {
            return SLOTS;
        }

        @Override public void reset(int slot) {
            MemoryUtil.memPutLong(buffer.mapped + slot * 8L, 0L);
        }

        @Override public void begin(int slot) {
            call(renderer.passEncoder, SET_MODE, MODE_COUNTING, slot * 8L);
        }

        @Override public void end(int slot) {
            call(renderer.passEncoder, SET_MODE, MODE_OFF, 0L);
        }

        @Override public long read(int slot) {
            return MemoryUtil.memGetLong(buffer.mapped + slot * 8L);
        }
    }

    @Override protected void destroyPasses() {
        blitPass.destroy();
    }

    @Override protected void destroyRings() {
        uniformRing.destroy();
        stagingRing.destroy();
    }

    @Override public void shutdown() {
        finishForShutdown();
        final long pool = framePool.get()[0];
        if (pool != 0L) ObjC.poolPop(pool);
        framePool.remove();
        if (presenter != null) presenter.shutdown();
        presenter = null;
        if (vblank != null) vblank.close();
        vblank = null;
        if (layer != null) layer.destroy();
        layer = null;
        destroySharedResources();
    }

    @Override protected String windowStats() {
        final double n = Math.max(1, frames - statsFrames);
        final String gpuTime = String.format("gpu=%.2fms ", (stream.gpuNanos - statsGpu) / n / 1e6);
        statsGpu = stream.gpuNanos;
        statsFrames = frames;
        return gpuTime + super.windowStats();
    }

    @Override protected PresentSurface surface() {
        return layer;
    }

    @Override protected int pipelineCount() {
        return renderer.pipelines.pipelineCount();
    }

    @Override public Images images() {
        return images;
    }

    @Override protected boolean prepareDrawCall(GlState st, int glMode, int primitive, boolean indexed) {
        return renderer.prepareDraw(st, glMode, primitive);
    }

    @Override protected void emitDraw(int primitive, int count, int instances, int first) {
        call(renderer.encoder(), DRAW, primitive, first, count, instances, st().baseInstance);
    }

    @Override protected void emitDrawIndexed(int primitive, int count, int instances, int baseVertex) {
        call(renderer.encoder(), DRAW_INDEXED, primitive, count, renderer.indexType, renderer.indexBuffer, renderer.indexOffset, instances, baseVertex, st().baseInstance);
    }

    @Override protected void bindRingIndices(long bytes, int indexType) {
        renderer.bindIndexBuffer(uniformRing.buffer, uniformRing.offset, indexType);
    }

    @Override protected void bindBufferIndices(Buf ebo, long offset, int indexType) {
        renderer.bindIndexBuffer(ebo.handle(), offset, indexType);
    }

    @Override public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        if (drawcount <= 0) return;
        final GlState st = st();
        final Buf ib = indirectBuffer(st);
        if (ib == null) return;
        if (!indirectInRange(ib, indirect, (drawcount - 1L) * (stride == 0 ? 20 : stride) + 20)) return;
        if (readsIndirectCommands(mode, type)) {
            drawElementsIndirectByReading(mode, type, indirect, drawcount, stride);
            return;
        }
        if (!canDrawIndirect(mode, type)) return;
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) return;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        buffers.use(ebo);
        buffers.use(ib);
        final long enc = renderer.encoder();
        final int s = stride == 0 ? 20 : stride;
        for (int i = 0; i < drawcount; i++) {
            call(enc, DRAW_INDEXED_INDIRECT, topology(mode), tokenIndexType(type), ebo.handle(), 0L, ib.handle(), indirect + (long) i * s);
        }
    }

    @Override public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        multiDrawElementsIndirect(mode, type, indirect, parameterCount(drawcount, maxdrawcount), stride);
    }

    @Override public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        drawArraysIndirectByReading(mode, indirect, parameterCount(drawcount, maxdrawcount), stride);
    }

    @Override protected boolean prepareDispatchCall(GlState st) {
        return renderer.prepareDispatch(st);
    }

    @Override protected void emitDispatch(Program p, int x, int y, int z) {
        ObjC.dispatch(stream.encoder(), DISPATCH, x, y, z, p.localX, p.localY, p.localZ);
    }

    @Override protected void emitDispatchIndirect(Program p, Buf b, long offset) {
        ObjC.dispatchIndirect(stream.encoder(), DISPATCH_INDIRECT, b.handle(), offset, p.localX, p.localY, p.localZ);
    }

    @Override public void blitThroughPass(Tex src, int srcLevel, int srcSlice, int sx0, int sy0, int sx1, int sy1, Tex dst, int dstLevel, int dstSlice, int dx0, int dy0, int dx1, int dy1, boolean linear, boolean depth) {
        blitPass.blit(src, srcLevel, srcSlice, sx0, sy0, sx1, sy1, dst, dstLevel, dstSlice, dx0, dy0, dx1, dy1, linear, depth);
    }

    @Override protected String vendorString() {
        if (gpu.isAmd()) return "AMD";
        if (gpu.isIntel()) return "Intel";
        return "Apple";
    }

    @Override protected long deviceLocalBytes() {
        return gpu.recommendedWorkingSet;
    }

    @Override public Textures textures() {
        return textures;
    }

    @Override protected Programs programs() {
        return programs;
    }

    @Override public Buffers buffers() {
        return buffers;
    }

    @Override public GpuCaps gpuCaps() {
        return gpu;
    }

    @Override public CommandStream stream() {
        return stream;
    }

    @Override public HostRing uniformRing() {
        return uniformRing;
    }

    @Override public Renderer rendererBase() {
        return renderer;
    }

    @Override protected Tex proxyDefaults() {
        return PROXY_DEFAULTS;
    }

    @Override protected String apiName() {
        return "Metal";
    }

    @Override protected String apiLabel() {
        return "Metal";
    }

    @Override protected String apiVersion() {
        return gpu.apiVersion;
    }

    @Override protected void debugNameImage(long image, String label) {
        debug.name(image, label);
    }

    @Override protected long allocatedBytes() {
        return gpu.allocatedBytes();
    }

    @Override protected TargetBase<Tex> blitTarget() {
        return blitDst;
    }

    @Override public void quiesceForSwapchainChange() {
        drainPresents();
        finish();
    }
}
