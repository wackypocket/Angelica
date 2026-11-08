package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;
import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.glemu.frame.PresentSurface;
import com.gtnewhorizons.angelica.glemu.resource.AllocBuf;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.SerialBuffersBase;
import com.gtnewhorizons.angelica.glemu.resource.TokenImagesBase;
import com.gtnewhorizons.angelica.glemu.resource.TokenTex;
import com.gtnewhorizons.angelica.glemu.resource.TokenTexturesBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glemu.pipeline.ImagePass;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/** Metal's draw calls: it has no fans, quads or byte indices, so those modes are converted. */
public abstract class TokenGlBase<T extends TokenTex, P extends ProgramBase<P>, B extends AllocBuf<A>, A> extends GlBase<T, P, B> {

    public abstract TokenImagesBase<T> images();

    protected final B indirectBuffer(GlState st) {
        final B ib = buffers().get(st.drawIndirect);
        if (ib != null && ib.alloc != null) return ib;
        warnOnce("indirect draw without a GL_DRAW_INDIRECT_BUFFER");
        recordError(GL11.GL_INVALID_OPERATION);
        return null;
    }

    @Override public abstract SerialBuffersBase<B, A> buffers();

    protected abstract void bindBufferIndices(B ebo, long offset, int indexType);

    @Override protected void bindPatternIndices(B buffer) {
        if (buffer == null) return;
        buffers().use(buffer);
        bindBufferIndices(buffer, 0L, NativeTokens.INDEX_UINT32);
    }
    private long statsFrames, statsWait, statsSubmits, statsPasses, statsDraws, statsInline;

    protected abstract DefaultFramebufferBase<T> defaultFramebuffer();

    protected abstract HostRingBase<?> stagingRing();

    protected abstract boolean hasPresentTarget();

    /** Gives the submitted frame to the window, answering whether it will appear. */
    protected abstract boolean handOffFrame(T color, int windowWidth, int windowHeight);

    protected void endFrameRecording() {
        rendererBase().endPass();
    }

    @Override protected void presentFrame() {
        frames++;
        endFrameRecording();
        final long px = windowPixels();
        final int ww = (int) (px >>> 32), wh = (int) px;
        final long t0 = System.nanoTime();
        awaitFrameSlot();
        final long t1 = System.nanoTime();
        if (paceSink != null) paceSink.gpuWait(t1 - t0);
        final DefaultFramebufferBase<T> fb = defaultFramebuffer();
        boolean handed = false;
        final boolean shown = hasPresentTarget() && ww > 0 && wh > 0;
        if (shown) rendererBase().materialize(fb.color);
        presentSerials.enqueue(stream().submit());
        if (shown) {
            final long tSubmitted = System.nanoTime();
            handed = handOffFrame(fb.color, ww, wh);
            final long tEnd = System.nanoTime();
            if (paceSink != null) paceSink.gate(tEnd - tSubmitted, tEnd);
            if (handed) fb.swap();
        }
        if (handed) {
            presented++;
        } else {
            presentSkips++;
        }
        final long now = System.nanoTime();
        if (statsDue(now)) {
            log().info("{} frame {} on {}: window {}x{}, back buffer {}x{}, {}", apiName(), frames, Thread.currentThread().getName(), ww, wh, fb.width, fb.height, stats());
            log().info("{} per frame: {}", apiName(), windowStats());
        }
        if (ww > 0 && wh > 0 && (ww != fb.width || wh != fb.height)) fb.ensure(ww, wh);
        if ((frames & 255) == 0) {
            stagingRing().trim(2);
            buffers().trim();
        }
    }

    @Override public void releaseImage(T t) {
        images().release(t);
    }

    @Override public void createImage(T t, boolean storage) {
        images().create(t, storage);
    }

    @Override protected void clearImage(T t) {
        images().clearImage(t, new float[4], 0f, 0);
    }

    @Override protected void submitNow() {
        stream().submit();
    }

    @Override public abstract TokenRendererBase<T, P, ?, B, A> rendererBase();

    @Override public abstract TokenTexturesBase<T> textures();

    public abstract void blitThroughPass(T src, int srcLevel, int srcSlice, int sx0, int sy0, int sx1, int sy1, T dst, int dstLevel, int dstSlice, int dx0, int dy0, int dx1, int dy1, boolean linear, boolean depth);

    @Override protected void blitClipped(T s, int sLevel, int sLayer, T d, int dLevel, int dLayer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, boolean linear) {
        if (s != d) rendererBase().materialize(d);
        final boolean ds = aspect != Formats.ASPECT_COLOR;
        // Unscaled, unflipped, same-format blits are plain copies; anything else draws through the blit pass.
        final boolean sameSize = Math.abs(sx1 - sx0) == Math.abs(dx1 - dx0) && Math.abs(sy1 - sy0) == Math.abs(dy1 - dy0) && (sx1 > sx0) == (dx1 > dx0) && (sy1 > sy0) == (dy1 > dy0);
        // A stencil index image is stored with a depth nothing can see, so a copy of both is a copy of its stencil.
        final boolean stencilAlone = s.format.glInternal() == GL30.GL_STENCIL_INDEX8 && d.format.glInternal() == GL30.GL_STENCIL_INDEX8;
        if (sameSize && s.format.pf() == d.format.pf() && s != d && ((aspect & s.format.aspect()) == s.format.aspect() || stencilAlone)) {
            rendererBase().endPass();
            final int w = Math.abs(sx1 - sx0), h = Math.abs(sy1 - sy0);
            textures().copyTextureToTexture(s, s.is3D() ? 0 : sLayer, sLevel, Math.min(sx0, sx1), Math.min(sy0, sy1), s.is3D() ? sLayer : 0, w, h, 1,
                d, d.is3D() ? 0 : dLayer, dLevel, Math.min(dx0, dx1), Math.min(dy0, dy1), d.is3D() ? dLayer : 0);
            d.markWritten();
            d.needsClear = false;
        } else if (ds && s.format.pf() != d.format.pf()) {
            warnOnce("depth blit between different formats");
            return;
        } else if ((aspect & Formats.ASPECT_STENCIL) != 0 && ImagePass.reads(s)) {
            rendererBase().endPass();
            if ((aspect & Formats.ASPECT_DEPTH) != 0) blitThroughPass(s == d ? rendererBase().copyForSampling(s) : s, sLevel, sLayer, sx0, sy0, sx1, sy1, d, dLevel, dLayer, dx0, dy0, dx1, dy1, false, true);
            imagePass().copyStencil(st(), s == d ? rendererBase().copyForSampling(s) : s, sLevel, sLayer, d, dLevel, dLayer, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
            rendererBase().endPass();
        } else if ((aspect & Formats.ASPECT_STENCIL) != 0) {
            warnOnce("stencil blits that cannot copy the whole image are not supported on " + apiName());
            return;
        } else {
            // A texture blitted onto itself is sampled from a copy.
            blitThroughPass(s == d ? rendererBase().copyForSampling(s) : s, sLevel, sLayer, sx0, sy0, sx1, sy1, d, dLevel, dLayer, dx0, dy0, dx1, dy1, linear && !ds, ds);
        }
        d.markCopiedFrom(s, currentSerial());
    }

    protected abstract int pipelineCount();

    protected String windowStats() {
        final double n = Math.max(1, frames - statsFrames);
        final TokenRendererBase<T, P, ?, B, A> renderer = rendererBase();
        final String out = String.format("cpuWaitOnGpu=%.2fms submits=%.1f passes=%.1f draws=%.0f inlineCopies=%.1f",
            (presentWaitNanos - statsWait) / n / 1e6, (stream().submits - statsSubmits) / n,
            (renderer.passes - statsPasses) / n, (renderer.draws - statsDraws) / n, (buffers().inlineCopies - statsInline) / n);
        statsFrames = frames;
        statsWait = presentWaitNanos;
        statsSubmits = stream().submits;
        statsPasses = renderer.passes;
        statsDraws = renderer.draws;
        statsInline = buffers().inlineCopies;
        return out;
    }

    @Override public String stats() {
        final TokenRendererBase<T, P, ?, B, A> renderer = rendererBase();
        final TokenTexturesBase<T> textures = textures();
        final SerialBuffersBase<B, A> buffers = buffers();
        return String.format("presented=%d skipped=%d draws=%d passes=%d depthDiscards=%d bindCalls=%d pipelines=%d uploads=%d (%.1f MiB, %d hoisted) renames=%d inlineCopies=%d hoistedCopies=%d mem=%.1f MiB",
            presented, presentSkips, renderer.draws, renderer.passes, renderer.depthDiscards, renderer.bindCalls, pipelineCount(), textures.uploads, textures.uploadBytes / 1048576.0,
            textures.hoistedUploads, buffers.renames, buffers.inlineCopies, buffers.hoistedCopies, allocatedBytes() / 1048576.0);
    }

    protected abstract PresentSurface surface();

    @Override public boolean hasWindow() {
        return surface() != null;
    }

    @Override public VSyncMode setVSync(VSyncMode preferred) {
        pendingVSync = preferred;
        if (surface() == null) return preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        return surface().setVSync(preferred);
    }

    @Override public boolean holdsToVblank() {
        return true;
    }

    @Override public boolean supportsVSync(VSyncMode mode) {
        return surface() != null && surface().supports(mode);
    }

    protected abstract boolean prepareDispatchCall(GlState st);

    protected abstract void emitDispatch(P program, int x, int y, int z);

    protected abstract void emitDispatchIndirect(P program, B buffer, long offset);

    @Override protected void dispatch(int x, int y, int z) {
        if (!dispatchable(x, y, z)) return;
        final GlState st = st();
        if (!prepareDispatchCall(st)) return;
        emitDispatch(programs().current(st), x, y, z);
    }

    @Override protected void dispatchIndirect(long offset) {
        final GlState st = st();
        final B b = buffers().get(st.dispatchIndirect);
        if (b == null || b.alloc == null) {
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (!indirectInRange(b, offset, 12)) return;
        if (!prepareDispatchCall(st)) return;
        buffers().use(b);
        emitDispatchIndirect(programs().current(st), b, offset);
    }

    @Override protected void debugPush(String message) {
    }

    @Override protected void debugPop() {
    }

    @Override protected int topology(int mode) {
        // Metal fills or outlines triangles and has no way to draw just their corners, so those are drawn as points.
        final int primitive = TokenMaps.primitive(mode);
        final boolean triangles = primitive == NativeTokens.PRIM_TRIANGLE || primitive == NativeTokens.PRIM_TRIANGLE_STRIP;
        return triangles && st().polygonModeFront == GL11.GL_POINT ? NativeTokens.PRIM_POINT : primitive;
    }

    @Override protected final boolean flatShadesFromFirstVertex(GlState st) {
        return true;
    }

    @Override protected boolean needsIndexConversion(int mode) {
        return mode == GL11.GL_LINE_LOOP || mode == GL11.GL_QUADS || mode == GL11.GL_QUAD_STRIP || mode == GL11.GL_TRIANGLE_FAN || mode == GL11.GL_POLYGON
            || adjacencyToDrop(st(), mode) || flatOrderNeedsTurning(st(), mode);
    }

    @Override protected final boolean drawableMode(int mode) {
        if (RendererBase.drawable(mode) || adjacencyToDrop(st(), mode)) return true;
        warnOnce("primitive mode 0x" + Integer.toHexString(mode) + " needs geometry or tessellation shaders, which are not implemented on " + apiName());
        return false;
    }

    protected static int tokenIndexType(int type) {
        return type == GL11.GL_UNSIGNED_SHORT ? NativeTokens.INDEX_UINT16 : NativeTokens.INDEX_UINT32;
    }

    /** A byte index widens to the short it means, except the one that restarts a primitive, which is a different number at each width. */
    private static short widenedIndex(GlState st, byte index) {
        return index == (byte) 0xFF && st.primitiveRestart ? (short) 0xFFFF : (short) (index & 0xFF);
    }

    private boolean bindIndices(GlState st, int count, int type, long offset) {
        final int bytes = indexBytes(type);
        final B ebo = buffers().get(st.vao.elementBuffer);
        final HostRingBase<?> ring = uniformRing();
        // Client-side index arrays are copied into the ring; byte indices, which Metal lacks, are widened to 16 bits on the way.
        if (ebo == null || ebo.alloc == null) {
            if (offset == 0L) return false;
            final boolean widen = type == GL11.GL_UNSIGNED_BYTE;
            final long size = widen ? count * 2L : (long) count * bytes;
            ring.alloc(size, 4);
            if (widen) {
                for (int i = 0; i < count; i++) MemoryUtil.memPutShort(ring.address + i * 2L, widenedIndex(st, MemoryUtil.memGetByte(offset + i)));
            } else {
                MemoryUtil.memCopy(offset, ring.address, (long) count * bytes);
            }
            bindRingIndices(size, widen ? NativeTokens.INDEX_UINT16 : tokenIndexType(type));
            return true;
        }
        buffers().use(ebo);
        if (type == GL11.GL_UNSIGNED_BYTE) {
            final long bytesIn = MemoryUtil.nmemCalloc(1, count);
            try {
                // GL leaves a count past the buffer's end undefined, so the rest draw as index 0 rather than reading memory the buffer does not own.
                final long available = Math.min(count, ebo.size - offset);
                if (offset >= 0 && available > 0) buffers().read(ebo, offset, available, bytesIn);
                ring.alloc(count * 2L, 4);
                for (int i = 0; i < count; i++) MemoryUtil.memPutShort(ring.address + i * 2L, widenedIndex(st, MemoryUtil.memGetByte(bytesIn + i)));
            } finally {
                MemoryUtil.nmemFree(bytesIn);
            }
            bindRingIndices(count * 2L, NativeTokens.INDEX_UINT16);
            return true;
        }
        if ((offset & (bytes - 1)) != 0) {
            warnOnce("misaligned index offset " + offset);
            return false;
        }
        bindBufferIndices(ebo, offset, tokenIndexType(type));
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
        // Byte indices in a buffer are read back, which ends the pass, so bind them before the pass is prepared.
        if (!bindIndices(st, count, type, indices)) return;
        if (!prepareDrawCall(st, mode, topology(mode), true)) return;
        emitDrawIndexed(topology(mode), count, primcount, baseVertex);
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
        final int primitive = topology(mode);
        final B ebo = buffers().get(st.vao.elementBuffer);
        final int bytes = indexBytes(type);
        // Indices a buffer can bind as they are need one prepare for the whole run; others bind per draw, which may end the pass.
        if (ebo != null && ebo.alloc != null && type != GL11.GL_UNSIGNED_BYTE) {
            if (!prepareDrawCall(st, mode, primitive, true)) return;
            buffers().use(ebo);
            for (int i = 0; i < drawcount; i++) {
                final int c = MemoryUtil.memGetInt(pCount + i * 4L);
                if (c <= 0) continue;
                final long off = MemoryUtil.memGetAddress(pIndices + (long) i * Pointer.POINTER_SIZE);
                if ((off & (bytes - 1)) != 0) {
                    warnOnce("misaligned index offset " + off);
                    continue;
                }
                bindBufferIndices(ebo, off, tokenIndexType(type));
                emitDrawIndexed(primitive, c, 1, pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L));
            }
            return;
        }
        for (int i = 0; i < drawcount; i++) {
            final int c = MemoryUtil.memGetInt(pCount + i * 4L);
            if (c <= 0) continue;
            final long off = MemoryUtil.memGetAddress(pIndices + (long) i * Pointer.POINTER_SIZE);
            final int base = pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L);
            if (!bindIndices(st, c, type, off)) continue;
            if (!prepareDrawCall(st, mode, primitive, true)) return;
            emitDrawIndexed(primitive, c, 1, base);
        }
    }
}
