package com.gtnewhorizons.angelica.dx12.pipeline;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Dxgi;
import com.gtnewhorizons.angelica.dx12.device.Gfx;
import com.gtnewhorizons.angelica.dx12.device.SwapChain;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import com.gtnewhorizons.angelica.dx12.resource.Tex;
import com.gtnewhorizons.angelica.dx12.shader.D3DCompiler;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class BlitPass {
    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static final String SOURCE = """
        cbuffer Rect : register(b0, space1) { float4 rect; };
        struct VOut { float4 position : SV_Position; float2 uv : TEXCOORD0; };
        VOut blit_vertex(uint vid : SV_VertexID) {
            float2 p = float2((vid << 1) & 2, vid & 2);
            VOut o;
            o.position = float4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
            o.uv = rect.xy + (rect.zw - rect.xy) * p;
            return o;
        }
        SamplerState smp : register(s0, space2);
        Texture2DArray<float4> srcF : register(t0, space2);
        float4 blit_float(VOut i) : SV_Target0 { return srcF.SampleLevel(smp, float3(i.uv, 0), 0); }
        Texture2DArray<uint4> srcU : register(t0, space2);
        uint4 blit_uint(VOut i) : SV_Target0 {
            uint w, h, n;
            srcU.GetDimensions(w, h, n);
            uint2 size = uint2(w, h);
            return srcU.Load(int4(min(uint2(max(i.uv, 0.0) * float2(size)), size - 1), 0, 0));
        }
        Texture2DArray<int4> srcS : register(t0, space2);
        int4 blit_sint(VOut i) : SV_Target0 {
            uint w, h, n;
            srcS.GetDimensions(w, h, n);
            uint2 size = uint2(w, h);
            return srcS.Load(int4(min(uint2(max(i.uv, 0.0) * float2(size)), size - 1), 0, 0));
        }
        Texture2DArray<float> srcD : register(t0, space2);
        struct DOut { float depth : SV_Depth; };
        DOut blit_depth(VOut i) {
            DOut o;
            o.depth = srcD.SampleLevel(smp, float3(i.uv, 0), 0);
            return o;
        }
        """;

    private static final int KIND_FLOAT = 0, KIND_UINT = 1, KIND_SINT = 2, KIND_DEPTH = 3;
    private static final String[] PIXEL_ENTRY = {"blit_float", "blit_uint", "blit_sint", "blit_depth"};

    private final Dx12GL gl;
    private final Arena arena = Arena.global();
    private final MemorySegment scratch = arena.allocate(512, 8);
    private final Int2LongOpenHashMap pipelines = new Int2LongOpenHashMap();
    private final MemorySegment[] pixel = new MemorySegment[4];
    private MemorySegment vertex;
    private RootSignatures.Layout layout;
    private long nearest, linear;
    private long presentPipeline;
    private boolean ready, failed;

    public BlitPass(Dx12GL gl) {
        this.gl = gl;
    }

    private boolean ensure() {
        if (ready) return true;
        if (failed) return false;
        try {
            vertex = code(D3DCompiler.compile(SOURCE, "blit_vertex", "vs_5_1", D3DCompiler.OPTIMIZATION_LEVEL3));
            for (int k = 0; k < 4; k++) pixel[k] = code(D3DCompiler.compile(SOURCE, PIXEL_ENTRY[k], "ps_5_1", D3DCompiler.OPTIMIZATION_LEVEL3));
        } catch (RuntimeException e) {
            LOG.error("The Direct3D 12 blit shaders failed to compile", e);
            failed = true;
            return false;
        }
        layout = gl.rootSignatures.graphics(0, 0, 1, 0, 1, 1, 0, 0);
        nearest = sampler(Gfx.FILTER_NEAREST);
        linear = sampler(Gfx.FILTER_LINEAR);
        ready = true;
        return true;
    }

    private MemorySegment code(byte[] data) {
        final MemorySegment seg = arena.allocate(data.length, 8);
        MemorySegment.copy(data, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
        return seg;
    }

    private long sampler(int filter) {
        final long handle = gl.descriptors.allocSampler();
        final MemorySegment d = scratch;
        for (int i = 0; i < 13; i++) d.set(JAVA_INT, i * 4L, 0);
        d.set(JAVA_INT, 0, filter == Gfx.FILTER_LINEAR ? 0x14 : 0);
        d.set(JAVA_INT, 4, 3);
        d.set(JAVA_INT, 8, 3);
        d.set(JAVA_INT, 12, 3);
        d.set(JAVA_INT, 20, 1);
        d.set(JAVA_INT, 24, 8);
        d.set(JAVA_FLOAT, 48, 1000f);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_SAMPLER, d.address(), handle);
        return handle;
    }

    private long pipeline(int kind, int format) {
        final int key = kind << 16 | format;
        final long cached = pipelines.get(key);
        if (cached != 0L) return cached;
        try (Arena a = Arena.ofConfined()) {
            final PsoDesc pso = new PsoDesc(a).rootSignature(layout.root()).vertexShader(vertex).pixelShader(pixel[kind]);
            if (kind == KIND_DEPTH) {
                pso.renderTargets(0).depthFormat(Dxgi.dsv(format)).depthStencil(true, true, 8, false, 0xFF, 0xFF, 1, 1, 1, 8, 1, 1, 1, 8);
            } else {
                pso.renderTargets(1, Dxgi.view(format));
            }
            final long state = pso.create(gl.gpu, a);
            pipelines.put(key, state);
            return state;
        } catch (RuntimeException e) {
            LOG.error("Blit pipeline for format {} failed", format, e);
            return 0L;
        }
    }

    private static int kindOf(int format, boolean depth) {
        if (depth) return KIND_DEPTH;
        return switch (format) {
            case Gfx.PF_R8_UINT, Gfx.PF_R16_UINT, Gfx.PF_RG8_UINT, Gfx.PF_R32_UINT, Gfx.PF_RG16_UINT, Gfx.PF_RGBA8_UINT, Gfx.PF_RGB10A2_UINT,
                 Gfx.PF_RG32_UINT, Gfx.PF_RGBA16_UINT, Gfx.PF_RGBA32_UINT -> KIND_UINT;
            case Gfx.PF_R8_SINT, Gfx.PF_R16_SINT, Gfx.PF_RG8_SINT, Gfx.PF_R32_SINT, Gfx.PF_RG16_SINT, Gfx.PF_RGBA8_SINT, Gfx.PF_RG32_SINT,
                 Gfx.PF_RGBA16_SINT, Gfx.PF_RGBA32_SINT -> KIND_SINT;
            default -> KIND_FLOAT;
        };
    }

    public void blit(Tex src, int srcLevel, int srcSlice, int sx0, int sy0, int sx1, int sy1,
                     Tex dst, int dstLevel, int dstSlice, int dx0, int dy0, int dx1, int dy1, boolean filterLinear, boolean depth) {
        if (!src.hasImage() || !dst.hasImage() || !ensure()) return;
        if (src.is3D()) {
            gl.warnOnce("blits from 3D textures are not supported on Direct3D 12");
            return;
        }
        if (!gl.images.renderable(dst.format.pf())) {
            gl.warnOnce("blit into non-renderable format " + dst.format.pf());
            return;
        }
        gl.renderer.materialize(src);
        gl.renderer.materialize(dst);
        gl.renderer.releaseFromPass(src);
        gl.renderer.releaseFromPass(dst);
        gl.renderer.endPass();
        final long view = gl.images.sliceView(src, srcLevel, srcSlice, false);
        try {
            gl.images.transition(src, Dx.STATE_PIXEL_SRV | Dx.STATE_NON_PIXEL_SRV);
            gl.images.transition(dst, depth ? Dx.STATE_DEPTH_WRITE : Dx.STATE_RENDER_TARGET);
            final long target = depth ? gl.images.dsv(dst, dstLevel, dstSlice, 1) : gl.images.rtv(dst, dstLevel, dst.is3D() ? 0 : dstSlice, dst.is3D() ? 1 : 1);
            final boolean useLinear = filterLinear && gl.images.filterable(src.format.pf()) && !src.format.isInteger();
            draw(view, src.levelWidth(srcLevel), src.levelHeight(srcLevel), sx0, sy0, sx1, sy1, target, dst.format.pf(), dx0, dy0, dx1, dy1, useLinear, depth);
        } finally {
            gl.descriptors.freeView(view);
        }
        dst.markWritten();
        dst.needsClear = false;
        final long serial = gl.stream.currentSerial();
        src.lastUse = serial;
        dst.lastUse = serial;
    }

    public boolean preparePresent(int format) {
        if (!ensure()) return false;
        presentPipeline = pipeline(KIND_FLOAT, format);
        return presentPipeline != 0L;
    }

    public void present(Tex src, SwapChain chain) {
        if (presentPipeline == 0L || !src.hasImage()) return;
        gl.renderer.endPass();
        final long backBuffer = chain.backBuffer();
        gl.images.transition(src, Dx.STATE_PIXEL_SRV | Dx.STATE_NON_PIXEL_SRV);
        gl.stream.transition(backBuffer, (int) Dx.ALL_SUBRESOURCES, Dx.STATE_PRESENT, Dx.STATE_RENDER_TARGET);
        final long handle = gl.descriptors.nextRtv();
        for (int i = 0; i < 8; i++) scratch.set(JAVA_INT, 256 + i * 4L, 0);
        scratch.set(JAVA_INT, 256, Dxgi.view(SwapChain.FORMAT));
        scratch.set(JAVA_INT, 260, 4);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_RTV, backBuffer, scratch.address() + 256, handle);
        final long view = gl.images.sliceView(src, 0, 0, false);
        try {
            scratch.set(JAVA_FLOAT, 0, 0f);
            scratch.set(JAVA_FLOAT, 4, 1f);
            scratch.set(JAVA_FLOAT, 8, 1f);
            scratch.set(JAVA_FLOAT, 12, 0f);
            drawWith(presentPipeline, view, handle, false, 0, 0, chain.width, chain.height, src.width == chain.width && src.height == chain.height ? nearest : linear, true);
        } finally {
            gl.descriptors.freeView(view);
        }
        gl.stream.transition(backBuffer, (int) Dx.ALL_SUBRESOURCES, Dx.STATE_RENDER_TARGET, Dx.STATE_PRESENT);
        src.lastUse = gl.stream.currentSerial();
    }

    private void draw(long view, int srcW, int srcH, int sx0, int sy0, int sx1, int sy1, long target, int dstFormat,
                      int dx0, int dy0, int dx1, int dy1, boolean filterLinear, boolean depth) {
        final int kind = kindOf(dstFormat, depth);
        final long pipeline = pipeline(kind, dstFormat);
        if (pipeline == 0L) return;
        float u0 = (float) sx0 / srcW, u1 = (float) sx1 / srcW, v0 = (float) sy0 / srcH, v1 = (float) sy1 / srcH;
        if (dx1 < dx0) {
            final float t = u0; u0 = u1; u1 = t;
        }
        if (dy1 < dy0) {
            final float t = v0; v0 = v1; v1 = t;
        }
        scratch.set(JAVA_FLOAT, 0, u0);
        scratch.set(JAVA_FLOAT, 4, v0);
        scratch.set(JAVA_FLOAT, 8, u1);
        scratch.set(JAVA_FLOAT, 12, v1);
        drawWith(pipeline, view, target, depth, Math.min(dx0, dx1), Math.min(dy0, dy1), Math.abs(dx1 - dx0), Math.abs(dy1 - dy0),
            kind == KIND_FLOAT || kind == KIND_DEPTH ? (filterLinear && !depth ? linear : nearest) : nearest, false);
    }

    private void drawWith(long pipeline, long view, long target, boolean depth, int x, int y, int w, int h, long sampler, boolean present) {
        final var ds = gl.descriptors;
        gl.uniformRing.alloc(256, 256);
        com.gtnewhorizons.angelica.dx12.util.Native.copy(scratch.address(), gl.uniformRing.address, 16);
        final long constants = gl.uniformRing.gpuAddress;
        final int srv = ds.ringAlloc(1);
        Com.v(gl.gpu.device, Dx.DEV_COPY_DESCRIPTORS_SIMPLE, 1, ds.ringCpu(srv), view, Dx.DH_CBV_SRV_UAV);
        final long samplers = ds.samplerTable(new long[] {sampler}, 1, sampler);

        final long list = gl.stream.list();
        scratch.set(JAVA_LONG, 64, target);
        if (depth) Com.v(list, Dx.CL_RENDER_TARGETS, 0, 0L, 0, scratch.address() + 64);
        else Com.v(list, Dx.CL_RENDER_TARGETS, 1, scratch.address() + 64, 1, 0L);
        scratch.set(JAVA_FLOAT, 128, x);
        scratch.set(JAVA_FLOAT, 132, y);
        scratch.set(JAVA_FLOAT, 136, w);
        scratch.set(JAVA_FLOAT, 140, h);
        scratch.set(JAVA_FLOAT, 144, 0f);
        scratch.set(JAVA_FLOAT, 148, 1f);
        Com.v(list, Dx.CL_VIEWPORTS, 1, scratch.address() + 128);
        scratch.set(JAVA_INT, 160, x);
        scratch.set(JAVA_INT, 164, y);
        scratch.set(JAVA_INT, 168, x + w);
        scratch.set(JAVA_INT, 172, y + h);
        Com.v(list, Dx.CL_SCISSORS, 1, scratch.address() + 160);
        Com.v(list, Dx.CL_SET_PSO, pipeline);
        Com.v(list, Dx.CL_GFX_ROOT_SIG, layout.root());
        Com.v(list, Dx.CL_GFX_ROOT_CBV, layout.param(0, RootSignatures.K_CBV), constants);
        Com.v(list, Dx.CL_GFX_ROOT_TABLE, layout.param(1, RootSignatures.K_SRV), ds.ringGpu(srv));
        Com.v(list, Dx.CL_GFX_ROOT_TABLE, layout.param(1, RootSignatures.K_SAMPLER), samplers);
        Com.v(list, Dx.CL_TOPOLOGY, 4);
        Com.v(list, Dx.CL_DRAW, 3, 1, 0, 0);
    }

    public void destroy() {
        for (long p : pipelines.values()) Com.release(p);
        pipelines.clear();
        gl.descriptors.freeSampler(nearest);
        gl.descriptors.freeSampler(linear);
    }
}
