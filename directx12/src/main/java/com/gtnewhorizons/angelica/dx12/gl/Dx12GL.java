package com.gtnewhorizons.angelica.dx12.gl;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;

import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;

import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;

import com.gtnewhorizons.angelica.glemu.gl.GlState;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.dx12.device.Gpu;
import com.gtnewhorizons.angelica.dx12.device.SwapChain;
import com.gtnewhorizons.angelica.dx12.device.Gfx;
import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Descriptors;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.pipeline.RootSignatures;
import com.gtnewhorizons.angelica.dx12.frame.CommandStream;
import com.gtnewhorizons.angelica.dx12.frame.HostRing;
import com.gtnewhorizons.angelica.dx12.memory.GpuBuffer;
import com.gtnewhorizons.angelica.dx12.pipeline.BlitPass;
import com.gtnewhorizons.angelica.dx12.pipeline.ClearPass;
import com.gtnewhorizons.angelica.dx12.resource.Buf;
import com.gtnewhorizons.angelica.dx12.resource.Buffers;
import com.gtnewhorizons.angelica.dx12.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.dx12.resource.Images;
import com.gtnewhorizons.angelica.dx12.resource.Target;
import com.gtnewhorizons.angelica.dx12.resource.Tex;
import com.gtnewhorizons.angelica.dx12.resource.Textures;
import com.gtnewhorizons.angelica.dx12.shader.Program;
import com.gtnewhorizons.angelica.dx12.shader.Programs;
import com.gtnewhorizons.angelica.dx12.util.DebugNames;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.memAddress;

public final class Dx12GL extends GlBase<Tex, Program, Buf> {
    private static final Tracy.ZoneId Z_PRESENT = Tracy.zoneId("dx12PresentFrame", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_FRAME_SLOT_WAIT = Tracy.zoneId("dx12FrameSlotWait", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    public final Gpu gpu;
    public final CommandStream stream;
    public final Descriptors descriptors;
    public final RootSignatures rootSignatures;
    public final HostRing uniformRing;
    public final HostRing stagingRing;
    public final Images images;
    public final Textures textures;
    public final Buffers buffers;
    public final Programs programs;
    public final Renderer renderer;
    public final DefaultFramebuffer defaultFb;
    public final ClearPass clearPass;
    public final BlitPass blitPass;
    public final DebugNames debug;

    private SwapChain layer;
    private final int framesInFlight = SystemProperties.DX12_FRAMES_IN_FLIGHT;

    private long presentWaitNanos;
    private long statsFrames, statsGpu, statsWait, statsSubmits, statsPasses, statsDraws, statsInline;

    public Dx12GL(Gpu gpu, Thread mainThread, File dataDir) {
        this.gpu = gpu;
        this.dataDir = dataDir;
        this.mainThread = mainThread;
        this.stream = new CommandStream(gpu);
        this.descriptors = new Descriptors(gpu, stream);
        stream.bindDescriptors(descriptors);
        this.rootSignatures = new RootSignatures(gpu);
        this.uniformRing = new HostRing(gpu, stream, 8L << 20, GpuBuffer.USAGE_UNIFORM);
        this.stagingRing = new HostRing(gpu, stream, 32L << 20, GpuBuffer.USAGE_UPLOAD);
        this.debug = new DebugNames(this, SystemProperties.DX12_DEBUG);
        this.images = new Images(this);
        this.textures = new Textures(this);
        this.buffers = new Buffers(this);
        this.programs = new Programs(this);
        this.renderer = new Renderer(this);
        this.defaultFb = new DefaultFramebuffer(this);
        this.clearPass = new ClearPass(this);
        this.blitPass = new BlitPass(this);
        this.mainState = new GlState(mainThread);
        stream.setBeforeSubmit(() -> {
            renderer.endPass();
            debug.closeAll();
        });
        textures.pickDepthFormats();
        renderer.init();
    }

    @Override public void warnOnce(String msg) {
        synchronized (warned) {
            if (!warned.add(msg)) return;
        }
        LOG.warn("{} GL: {}", apiName(), msg);
    }

    @Override public void flushAndWait(long serial) {
        renderer.endPass();
        stream.waitFor(serial);
    }

    public void attachWindow(long window, long hwnd) {
        this.window = window;
        this.layer = new SwapChain(gpu, stream, hwnd, framesInFlight);
        if (!blitPass.preparePresent(SwapChain.FORMAT)) throw new IllegalStateException("Direct3D 12 could not build the present pipeline");
        final long size = windowPixels();
        defaultFb.ensure((int) (size >>> 32), (int) size);
        final VSyncMode v = pendingVSync;
        if (v != null) layer.setVSync(v);
    }
    public boolean hasWindow() {
        return layer != null;
    }

    public VSyncMode setVSync(VSyncMode preferred) {
        pendingVSync = preferred;
        if (layer == null) return preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        return layer.setVSync(preferred);
    }

    public boolean holdsToVblank() {
        return true;
    }

    public boolean supportsVSync(VSyncMode mode) {
        return layer != null && layer.supports(mode);
    }

    public int swapchainFormat() {
        return layer == null ? Gfx.PF_INVALID : SwapChain.FORMAT;
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
        presentWaitNanos += waited;
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
        final long dump = SystemProperties.DX12_DUMP_FRAME;
        if (frames == dump) dumpBackBuffer();
        renderer.endPass(true);
        final long px = windowPixels();
        final int ww = (int) (px >>> 32), wh = (int) px;
        final long t0 = System.nanoTime();
        awaitFrameSlot();
        final long t1 = System.nanoTime();
        if (paceSink != null) paceSink.gpuWait(t1 - t0);
        boolean handed = false;
        if (layer != null && ww > 0 && wh > 0) {
            final Tex color = defaultFb.color;
            renderer.materialize(color);
            if (layer.ensure(ww, wh)) {
                blitPass.present(color, layer);
                presentSerials.enqueue(stream.submit());
                final long tSubmitted = System.nanoTime();
                layer.present();
                final long tEnd = System.nanoTime();
                if (paceSink != null) paceSink.gate(tEnd - tSubmitted, tEnd);
                handed = true;
                defaultFb.swap();
            } else {
                presentSerials.enqueue(stream.submit());
            }
        } else {
            presentSerials.enqueue(stream.submit());
        }        if (handed) {
            this.presented++;
        } else {
            presentSkips++;
        }
        final long now = System.nanoTime();
        if (SystemProperties.DX12_STATS && now - lastStatsNanos > 10_000_000_000L) {
            lastStatsNanos = now;
            LOG.info("Direct3D 12 frame {} on {}: window {}x{}, back buffer {}x{}, {}", frames, Thread.currentThread().getName(), ww, wh, defaultFb.width, defaultFb.height, stats());
            LOG.info("Direct3D 12 per frame: {}", windowStats());
        }
        if (ww > 0 && wh > 0 && (ww != defaultFb.width || wh != defaultFb.height)) defaultFb.ensure(ww, wh);
        if ((frames & 255) == 0) {
            stagingRing.trim(2);
            buffers.trim();
        }
    }

    public void drainPresents() {
    }

    public long awaitPresent() {
        return 0L;
    }

    public boolean hasPresentWait() {
        return false;
    }

    public void shutdown() {
        try {
            renderer.endPass();
            stream.finish();
        } catch (Throwable t) {
            LOG.warn("Direct3D 12 shutdown wait failed", t);
        }
        if (layer != null) layer.destroy();
        layer = null;
        renderer.destroy();
        blitPass.destroy();
        programs.destroy();
        textures.destroy();
        buffers.destroy();
        defaultFb.destroy();
        stream.destroy();
        uniformRing.destroy();
        stagingRing.destroy();
        rootSignatures.destroy();
        descriptors.destroy();
    }

    private String windowStats() {
        final double n = Math.max(1, frames - statsFrames);
        final String out = String.format("gpu=%.2fms cpuWaitOnGpu=%.2fms submits=%.1f passes=%.1f draws=%.0f inlineCopies=%.1f",
            (stream.gpuNanos - statsGpu) / n / 1e6, (presentWaitNanos - statsWait) / n / 1e6, (stream.submits - statsSubmits) / n,
            (renderer.passes - statsPasses) / n, (renderer.draws - statsDraws) / n, (buffers.inlineCopies - statsInline) / n);
        statsFrames = frames;
        statsGpu = stream.gpuNanos;
        statsWait = presentWaitNanos;
        statsSubmits = stream.submits;
        statsPasses = renderer.passes;
        statsDraws = renderer.draws;
        statsInline = buffers.inlineCopies;
        return out;
    }

    @Override public String stats() {
        return String.format("presented=%d skipped=%d draws=%d passes=%d depthDiscards=%d bindCalls=%d pipelines=%d uploads=%d (%.1f MiB, %d hoisted) renames=%d inlineCopies=%d hoistedCopies=%d mem=%.1f MiB",
            presented, presentSkips, renderer.draws, renderer.passes, renderer.depthDiscards, renderer.bindCalls, renderer.pipelines.count(), textures.uploads, textures.uploadBytes / 1048576.0,
            textures.hoistedUploads, buffers.renames, buffers.inlineCopies, buffers.hoistedCopies, gpu.allocatedBytes() / 1048576.0);
    }

    private int topology(int mode) {
        return Renderer.primitive(mode);
    }

    private boolean needsIndexConversion(int mode) {
        return mode == GL11.GL_LINE_LOOP || mode == GL11.GL_QUADS || mode == GL11.GL_QUAD_STRIP || mode == GL11.GL_TRIANGLE_FAN || mode == GL11.GL_POLYGON;
    }

    private boolean drawableMode(int mode) {
        if (Renderer.drawable(mode)) return true;
        warnOnce("primitive mode 0x" + Integer.toHexString(mode) + " needs geometry or tessellation shaders, which Direct3D 12 does not have");
        return false;
    }

    public void drawArraysInstanced(int mode, int first, int count, int primcount) {
        if (count <= 0 || primcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            drawConvertedArrays(st, mode, first, count, primcount);
            return;
        }
        renderer.indexedDraw = false;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        renderer.drawArrays(count, primcount, first, 0);
    }

    public void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {
        final int n = Math.min(firsts.remaining(), counts.remaining());
        if (n == 0 || !drawableMode(mode)) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            for (int i = 0; i < n; i++) drawConvertedArrays(st, mode, firsts.get(firsts.position() + i), counts.get(counts.position() + i), 1);
            return;
        }
        renderer.indexedDraw = false;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        for (int i = 0; i < n; i++) {
            final int c = counts.get(counts.position() + i);
            if (c > 0) renderer.drawArrays(c, 1, firsts.get(firsts.position() + i), 0);
        }
    }

    private void drawConvertedArrays(GlState st, int mode, int first, int count, int instances) {
        final int[] idx = convertedIndices(mode, count);
        if (idx.length == 0) return;
        uniformRing.alloc(idx.length * 4L, 4);
        for (int i = 0; i < idx.length; i++) MemoryUtil.memPutInt(uniformRing.address + i * 4L, idx[i]);
        final int convMode = convertedMode(mode);
        renderer.bindIndexBuffer(uniformRing.gpuAddress, idx.length * 4L, Gfx.INDEX_UINT32);
        renderer.indexedDraw = true;
        if (!renderer.prepareDraw(st, convMode, topology(convMode))) return;
        renderer.drawIndexed(idx.length, instances, 0, first, 0);
    }

    private static int gfxIndexType(int type) {
        return type == GL11.GL_UNSIGNED_SHORT ? Gfx.INDEX_UINT16 : Gfx.INDEX_UINT32;
    }

    private boolean bindIndices(GlState st, int count, int type, long offset) {
        final int bytes = indexBytes(type);
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) {
            if (offset == 0L) return false;
            final boolean widen = type == GL11.GL_UNSIGNED_BYTE;
            uniformRing.alloc(widen ? count * 2L : (long) count * bytes, 4);
            if (widen) {
                for (int i = 0; i < count; i++) MemoryUtil.memPutShort(uniformRing.address + i * 2L, (short) (MemoryUtil.memGetByte(offset + i) & 0xFF));
            } else {
                MemoryUtil.memCopy(offset, uniformRing.address, (long) count * bytes);
            }
            return renderer.bindIndexBuffer(uniformRing.gpuAddress, widen ? count * 2L : (long) count * bytes, widen ? Gfx.INDEX_UINT16 : gfxIndexType(type));
        }
        buffers.use(ebo);
        if (type == GL11.GL_UNSIGNED_BYTE) {
            final long bytesIn = MemoryUtil.nmemAlloc(count);
            try {
                buffers.read(ebo, offset, count, bytesIn);
                uniformRing.alloc(count * 2L, 4);
                for (int i = 0; i < count; i++) MemoryUtil.memPutShort(uniformRing.address + i * 2L, (short) (MemoryUtil.memGetByte(bytesIn + i) & 0xFF));
            } finally {
                MemoryUtil.nmemFree(bytesIn);
            }
            return renderer.bindIndexBuffer(uniformRing.gpuAddress, count * 2L, Gfx.INDEX_UINT16);
        }
        if ((offset & (bytes - 1)) != 0) {
            warnOnce("misaligned index offset " + offset);
            return false;
        }
        buffers.toRead(ebo.alloc);
        return renderer.bindIndexBuffer(ebo.alloc.gpuAddress + offset, ebo.alloc.size - offset, gfxIndexType(type));
    }

    @Override public void drawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int baseVertex) {
        if (count <= 0 || primcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            drawConvertedElements(st, mode, count, type, indices, primcount, baseVertex);
            return;
        }
        if (!bindIndices(st, count, type, indices)) return;
        renderer.indexedDraw = true;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        renderer.drawIndexed(count, primcount, 0, baseVertex, 0);
    }

    private void drawConvertedElements(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        final int bytes = indexBytes(type);
        final int[] pattern = convertedIndices(mode, count);
        if (pattern.length == 0) return;
        long src;
        long copied = 0L;
        if (ebo != null && ebo.alloc != null) {
            if (ebo.hostVisible()) {
                if (!stream.isIdle(ebo.lastGpuWrite)) flushAndWait(ebo.lastGpuWrite);
                src = ebo.address() + indices;
            } else {
                copied = MemoryUtil.nmemAlloc((long) count * bytes);
                buffers.read(ebo, indices, (long) count * bytes, copied);
                src = copied;
            }
        } else {
            src = indices;
        }
        if (src == 0L) return;
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
        if (copied != 0L) MemoryUtil.nmemFree(copied);
        final int convMode = convertedMode(mode);
        renderer.bindIndexBuffer(uniformRing.gpuAddress, pattern.length * 4L, Gfx.INDEX_UINT32);
        renderer.indexedDraw = true;
        if (!renderer.prepareDraw(st, convMode, topology(convMode))) return;
        renderer.drawIndexed(pattern.length, instances, 0, baseVertex, 0);
    }

    public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        if (drawcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            for (int i = 0; i < drawcount; i++) {
                drawConvertedElements(st, mode, MemoryUtil.memGetInt(pCount + i * 4L), type, MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE), 1,
                    pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L));
            }
            return;
        }
        renderer.indexedDraw = true;
        renderer.indexType = type == GL11.GL_UNSIGNED_INT ? Gfx.INDEX_UINT32 : Gfx.INDEX_UINT16;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        final int bytes = indexBytes(type);
        if (ebo != null && ebo.alloc != null && type != GL11.GL_UNSIGNED_BYTE) {
            buffers.use(ebo);
            for (int i = 0; i < drawcount; i++) {
                final int c = MemoryUtil.memGetInt(pCount + i * 4L);
                if (c <= 0) continue;
                final long off = MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
                if ((off & (bytes - 1)) != 0) {
                    warnOnce("misaligned index offset " + off);
                    continue;
                }
                final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
                buffers.toRead(ebo.alloc);
                renderer.bindIndexBuffer(ebo.alloc.gpuAddress + off, ebo.alloc.size - off, gfxIndexType(type));
                renderer.drawIndexed(c, 1, 0, base, 0);
            }
            return;
        }
        for (int i = 0; i < drawcount; i++) {
            final int c = MemoryUtil.memGetInt(pCount + i * 4L);
            if (c <= 0) continue;
            final long off = MemoryUtil.memGetAddress(pIndices + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
            final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
            if (!bindIndices(st, c, type, off)) continue;
            renderer.drawIndexed(c, 1, 0, base, 0);
        }
    }

    public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        if (drawcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        if (ib == null || ib.alloc == null) {
            warnOnce("indirect draw without a GL_DRAW_INDIRECT_BUFFER");
            return;
        }
        if (needsIndexConversion(mode) || type == GL11.GL_UNSIGNED_BYTE) {
            warnOnce("indirect draws of mode 0x" + Integer.toHexString(mode) + " with index type 0x" + Integer.toHexString(type) + " are not supported on Direct3D 12");
            return;
        }
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) return;
        buffers.toRead(ebo.alloc);
        renderer.bindIndexBuffer(ebo.alloc.gpuAddress, ebo.alloc.size, gfxIndexType(type));
        renderer.indexedDraw = true;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        buffers.use(ebo);
        buffers.use(ib);
        buffers.toRead(ib.alloc);
        renderer.executeIndirect(Renderer.INDIRECT_DRAW_INDEXED, stride == 0 ? 20 : stride, drawcount, ib.handle(), indirect);
    }

    public void multiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) {
        if (drawcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        if (ib == null || ib.alloc == null) return;
        if (needsIndexConversion(mode)) {
            warnOnce("indirect draws of mode 0x" + Integer.toHexString(mode) + " are not supported on Direct3D 12");
            return;
        }
        renderer.indexedDraw = false;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        buffers.use(ib);
        buffers.toRead(ib.alloc);
        renderer.executeIndirect(Renderer.INDIRECT_DRAW, stride == 0 ? 16 : stride, drawcount, ib.handle(), indirect);
    }

    public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        if (maxdrawcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        final Buf cb = buffers.get(st.parameterBuffer);
        if (ib == null || cb == null || ib.alloc == null || cb.alloc == null) return;
        if (needsIndexConversion(mode) || type == GL11.GL_UNSIGNED_BYTE) {
            warnOnce("indirect draws of mode 0x" + Integer.toHexString(mode) + " with index type 0x" + Integer.toHexString(type) + " are not supported on Direct3D 12");
            return;
        }
        final Buf ebo = buffers.get(st.vao.elementBuffer);
        if (ebo == null || ebo.alloc == null) return;
        buffers.toRead(ebo.alloc);
        renderer.bindIndexBuffer(ebo.alloc.gpuAddress, ebo.alloc.size, gfxIndexType(type));
        renderer.indexedDraw = true;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        buffers.use(ebo);
        buffers.use(ib);
        buffers.use(cb);
        buffers.toRead(ib.alloc);
        buffers.toRead(cb.alloc);
        renderer.executeIndirect(Renderer.INDIRECT_DRAW_INDEXED, stride == 0 ? 20 : stride, maxdrawcount, ib.handle(), indirect, cb.handle(), drawcount);
    }

    public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        if (maxdrawcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        final Buf ib = buffers.get(st.drawIndirect);
        final Buf cb = buffers.get(st.parameterBuffer);
        if (ib == null || cb == null || ib.alloc == null || cb.alloc == null) return;
        if (needsIndexConversion(mode)) {
            warnOnce("indirect draws of mode 0x" + Integer.toHexString(mode) + " are not supported on Direct3D 12");
            return;
        }
        renderer.indexedDraw = false;
        if (!renderer.prepareDraw(st, mode, topology(mode))) return;
        buffers.use(ib);
        buffers.use(cb);
        buffers.toRead(ib.alloc);
        buffers.toRead(cb.alloc);
        renderer.executeIndirect(Renderer.INDIRECT_DRAW, stride == 0 ? 16 : stride, maxdrawcount, ib.handle(), indirect, cb.handle(), drawcount);
    }

    public void dispatchCompute(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) return;
        final GlState st = st();
        if (!renderer.prepareDispatch(st)) return;
        Com.v(stream.list(), Dx.CL_DISPATCH, x, y, z);
        renderer.pendingComputeBarrier = true;
    }

    public void dispatchComputeIndirect(long offset) {
        final GlState st = st();
        final Buf b = buffers.get(st.dispatchIndirect);
        if (b == null || b.alloc == null) return;
        if (!renderer.prepareDispatch(st)) return;
        buffers.use(b);
        buffers.toRead(b.alloc);
        renderer.executeIndirect(Renderer.INDIRECT_DISPATCH, 12, 1, b.handle(), offset);
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
        if (s != d) renderer.materialize(d);
        final boolean ds = aspect != Formats.ASPECT_COLOR;
        final boolean sameSize = Math.abs(sx1 - sx0) == Math.abs(dx1 - dx0) && Math.abs(sy1 - sy0) == Math.abs(dy1 - dy0) && (sx1 > sx0) == (dx1 > dx0) && (sy1 > sy0) == (dy1 > dy0);
        if (sameSize && s.format.pf() == d.format.pf() && s != d) {
            renderer.releaseFromPass(s);
            renderer.releaseFromPass(d);
            renderer.endPass();
            final int w = Math.abs(sx1 - sx0), h = Math.abs(sy1 - sy0);
            textures.copyTextureToTexture(s, s.is3D() ? 0 : sLayer, sLevel, Math.min(sx0, sx1), Math.min(sy0, sy1), s.is3D() ? sLayer : 0, w, h, 1,
                d, d.is3D() ? 0 : dLayer, dLevel, Math.min(dx0, dx1), Math.min(dy0, dy1), d.is3D() ? dLayer : 0);
            d.markWritten();
            d.needsClear = false;
        } else if (ds && (s.format.pf() != d.format.pf() || (aspect & Formats.ASPECT_STENCIL) != 0)) {
            warnOnce(s.format.pf() != d.format.pf() ? "depth blit between different formats" : "scaled stencil blits are not supported on Direct3D 12");
            return;
        } else {
            blitPass.blit(s, sLevel, sLayer, sx0, sy0, sx1, sy1, d, dLevel, dLayer, dx0, dy0, dx1, dy1, linear && !ds, ds);
        }
        final long serial = stream.currentSerial();
        s.lastUse = serial;
        d.lastUse = serial;
    }

    @Override protected String vendorString() {
        if (gpu.isAmd()) return "AMD";
        if (gpu.isIntel()) return "Intel";
        return "Apple";
    }

    @Override protected long deviceLocalBytes() {
        return gpu.recommendedWorkingSet;
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
        return "Direct3D 12";
    }

    @Override protected String apiLabel() {
        return "D3D12";
    }

    @Override protected String apiTag() {
        return "dx12";
    }

    @Override protected String apiVersion() {
        return String.valueOf(gpu.apiVersion);
    }

    @Override protected void submitNow() {
        stream.submit();
    }

    @Override protected void debugNameImage(long image, String label) {
        debug.name(image, label);
    }

    @Override protected void clearImage(Tex t) {
        images.clearImage(t, new float[4], 0f, 0);
    }

    @Override protected long allocatedBytes() {
        return gpu.allocatedBytes();
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
        drainPresents();
        finish();
    }
}
