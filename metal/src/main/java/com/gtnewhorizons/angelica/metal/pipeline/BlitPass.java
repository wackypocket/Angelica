package com.gtnewhorizons.angelica.metal.pipeline;

import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.resource.Tex;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class BlitPass {
    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static final String SOURCE = """
        #include <metal_stdlib>
        using namespace metal;
        struct VOut { float4 position [[position]]; float2 uv; };
        vertex VOut blit_vertex(uint vid [[vertex_id]], constant float4& rect [[buffer(0)]]) {
            float2 p = float2((vid << 1) & 2, vid & 2);
            VOut o;
            o.position = float4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
            o.uv = rect.xy + (rect.zw - rect.xy) * p;
            return o;
        }
        fragment float4 blit_float(VOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]]) {
            return src.sample(s, in.uv, level(0));
        }
        fragment uint4 blit_uint(VOut in [[stage_in]], texture2d<uint> src [[texture(0)]]) {
            uint2 size = uint2(src.get_width(), src.get_height());
            return src.read(min(uint2(max(in.uv, 0.0) * float2(size)), size - 1));
        }
        fragment int4 blit_sint(VOut in [[stage_in]], texture2d<int> src [[texture(0)]]) {
            uint2 size = uint2(src.get_width(), src.get_height());
            return src.read(min(uint2(max(in.uv, 0.0) * float2(size)), size - 1));
        }
        struct DOut { float depth [[depth(any)]]; };
        fragment DOut blit_depth(VOut in [[stage_in]], depth2d<float> src [[texture(0)]], sampler s [[sampler(0)]]) {
            DOut o;
            o.depth = src.sample(s, in.uv, level(0));
            return o;
        }
        """;

    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:"), DEPTH_ATTACHMENT = sel("depthAttachment");
    private static final long SET_TEXTURE = sel("setTexture:"), SET_LEVEL = sel("setLevel:"), SET_SLICE = sel("setSlice:"), SET_DEPTH_PLANE = sel("setDepthPlane:");
    private static final long SET_LOAD = sel("setLoadAction:"), SET_STORE = sel("setStoreAction:");
    private static final long SET_PIPELINE = sel("setRenderPipelineState:"), SET_VIEWPORT = sel("setViewport:"), SET_VERTEX_BYTES = sel("setVertexBytes:length:atIndex:");
    private static final long SET_FRAGMENT_TEXTURE = sel("setFragmentTexture:atIndex:"), SET_FRAGMENT_SAMPLER = sel("setFragmentSamplerState:atIndex:");
    private static final long SET_DEPTH_STENCIL = sel("setDepthStencilState:"), DRAW = sel("drawPrimitives:vertexStart:vertexCount:");
    private static final long NEW_LIBRARY = sel("newLibraryWithSource:options:error:"), NEW_FUNCTION = sel("newFunctionWithName:");
    private static final long SET_VERTEX_FUNCTION = sel("setVertexFunction:"), SET_FRAGMENT_FUNCTION = sel("setFragmentFunction:");
    private static final long SET_PIXEL_FORMAT = sel("setPixelFormat:"), SET_DEPTH_FORMAT = sel("setDepthAttachmentPixelFormat:");
    private static final long NEW_PIPELINE = sel("newRenderPipelineStateWithDescriptor:error:"), NEW_SAMPLER = sel("newSamplerStateWithDescriptor:");
    private static final long SET_MIN = sel("setMinFilter:"), SET_MAG = sel("setMagFilter:"), SET_S = sel("setSAddressMode:"), SET_T = sel("setTAddressMode:");
    private static final long RENDER_ENCODER = sel("renderCommandEncoderWithDescriptor:"), END_ENCODING = sel("endEncoding");
    private static final long SET_DEPTH_COMPARE = sel("setDepthCompareFunction:"), SET_DEPTH_WRITE = sel("setDepthWriteEnabled:"), NEW_DEPTH_STENCIL = sel("newDepthStencilStateWithDescriptor:");

    private static final int KIND_FLOAT = 0, KIND_UINT = 1, KIND_SINT = 2, KIND_DEPTH = 3;

    private final MetalGL gl;
    private long library;
    private final long[] functions = new long[5];
    private final Int2LongOpenHashMap pipelines = new Int2LongOpenHashMap();
    private long nearest, linear, depthWrite;
    private final long rect = MemoryUtil.nmemAlloc(16);
    private final long presentRect = MemoryUtil.nmemAlloc(16);
    private volatile long presentPipeline;

    public BlitPass(MetalGL gl) {
        this.gl = gl;
    }

    private boolean ensure() {
        if (library != 0L) return true;
        final long pool = ObjC.poolPush();
        final long error = MemoryUtil.nmemCalloc(1, 8);
        final long source = ObjC.nsString(SOURCE);
        try {
            library = send(gl.gpu.device, NEW_LIBRARY, source, 0L, error);
            if (library == 0L) {
                LOG.error("The Metal blit shaders failed to compile: {}", ObjC.errorDescription(MemoryUtil.memGetAddress(error)));
                return false;
            }
        } finally {
            ObjC.release(source);
            MemoryUtil.nmemFree(error);
            ObjC.poolPop(pool);
        }
        final String[] names = {"blit_float", "blit_uint", "blit_sint", "blit_depth", "blit_vertex"};
        for (int i = 0; i < names.length; i++) {
            final long n = ObjC.nsString(names[i]);
            functions[i] = send(library, NEW_FUNCTION, n);
            ObjC.release(n);
        }
        nearest = sampler(Mtl.FILTER_NEAREST);
        linear = sampler(Mtl.FILTER_LINEAR);
        final long ds = ObjC.create("MTLDepthStencilDescriptor");
        call(ds, SET_DEPTH_COMPARE, Mtl.COMPARE_ALWAYS);
        call(ds, SET_DEPTH_WRITE, 1L);
        depthWrite = send(gl.gpu.device, NEW_DEPTH_STENCIL, ds);
        ObjC.release(ds);
        return true;
    }

    private long sampler(int filter) {
        final long d = ObjC.create("MTLSamplerDescriptor");
        call(d, SET_MIN, filter);
        call(d, SET_MAG, filter);
        call(d, SET_S, Mtl.ADDRESS_CLAMP_TO_EDGE);
        call(d, SET_T, Mtl.ADDRESS_CLAMP_TO_EDGE);
        final long s = send(gl.gpu.device, NEW_SAMPLER, d);
        ObjC.release(d);
        return s;
    }

    private long pipeline(int kind, int format) {
        final int key = kind << 16 | format;
        final long cached = pipelines.get(key);
        if (cached != 0L) return cached;
        final long pool = ObjC.poolPush();
        final long desc = ObjC.create("MTLRenderPipelineDescriptor");
        final long error = MemoryUtil.nmemCalloc(1, 8);
        try {
            call(desc, SET_VERTEX_FUNCTION, functions[4]);
            call(desc, SET_FRAGMENT_FUNCTION, functions[kind]);
            if (kind == KIND_DEPTH) {
                call(desc, SET_DEPTH_FORMAT, format);
            } else {
                call(send(send(desc, COLOR_ATTACHMENTS), OBJECT_AT, 0), SET_PIXEL_FORMAT, format);
            }
            final long state = send(gl.gpu.device, NEW_PIPELINE, desc, error);
            if (state == 0L) {
                LOG.error("Blit pipeline for format {} failed: {}", format, ObjC.errorDescription(MemoryUtil.memGetAddress(error)));
                return 0L;
            }
            pipelines.put(key, state);
            return state;
        } finally {
            MemoryUtil.nmemFree(error);
            ObjC.release(desc);
            ObjC.poolPop(pool);
        }
    }

    private static int kindOf(int format, boolean depth) {
        if (depth) return KIND_DEPTH;
        return switch (format) {
            case Mtl.PF_R8_UINT, Mtl.PF_R16_UINT, Mtl.PF_RG8_UINT, Mtl.PF_R32_UINT, Mtl.PF_RG16_UINT, Mtl.PF_RGBA8_UINT, Mtl.PF_RGB10A2_UINT,
                 Mtl.PF_RG32_UINT, Mtl.PF_RGBA16_UINT, Mtl.PF_RGBA32_UINT -> KIND_UINT;
            case Mtl.PF_R8_SINT, Mtl.PF_R16_SINT, Mtl.PF_RG8_SINT, Mtl.PF_R32_SINT, Mtl.PF_RG16_SINT, Mtl.PF_RGBA8_SINT, Mtl.PF_RG32_SINT,
                 Mtl.PF_RGBA16_SINT, Mtl.PF_RGBA32_SINT -> KIND_SINT;
            default -> KIND_FLOAT;
        };
    }

    public void blit(Tex src, int srcLevel, int srcSlice, int sx0, int sy0, int sx1, int sy1,
                     Tex dst, int dstLevel, int dstSlice, int dx0, int dy0, int dx1, int dy1, boolean filterLinear, boolean depth) {
        if (!src.hasImage() || !dst.hasImage() || !ensure()) return;
        if (src.is3D()) {
            gl.warnOnce("blits from 3D textures are not supported on Metal");
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
            final boolean whole = Math.min(dx0, dx1) == 0 && Math.min(dy0, dy1) == 0 && Math.abs(dx1 - dx0) == dst.levelWidth(dstLevel) && Math.abs(dy1 - dy0) == dst.levelHeight(dstLevel);
            final boolean useLinear = filterLinear && gl.images.filterable(src.format.pf()) && !src.format.isInteger();
            draw(view, src.levelWidth(srcLevel), src.levelHeight(srcLevel), sx0, sy0, sx1, sy1, dst.image, dst.format.pf(), dstLevel, dst.is3D() ? 0 : dstSlice, dst.is3D() ? dstSlice : 0,
                dx0, dy0, dx1, dy1, useLinear, depth, whole || dst.contentsUndefined ? Mtl.LOAD_DONT_CARE : Mtl.LOAD_LOAD, false);
        } finally {
            ObjC.release(view);
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

    public void encodePresent(long commandBuffer, long srcTexture, int srcW, int srcH, long drawableTexture, int format, int width, int height) {
        final long pipeline = presentPipeline;
        if (pipeline == 0L) return;
        MemoryUtil.memPutFloat(presentRect, 0f);
        MemoryUtil.memPutFloat(presentRect + 4, 1f);
        MemoryUtil.memPutFloat(presentRect + 8, 1f);
        MemoryUtil.memPutFloat(presentRect + 12, 0f);
        final long pass = ObjC.create("MTLRenderPassDescriptor");
        try {
            final long a = send(send(pass, COLOR_ATTACHMENTS), OBJECT_AT, 0);
            call(a, SET_TEXTURE, drawableTexture);
            call(a, SET_LOAD, Mtl.LOAD_DONT_CARE);
            call(a, SET_STORE, Mtl.STORE_STORE);
            final long enc = send(commandBuffer, RENDER_ENCODER, pass);
            call(enc, SET_PIPELINE, pipeline);
            ObjC.callViewport(enc, SET_VIEWPORT, 0, 0, width, height, 0.0, 1.0);
            call(enc, SET_VERTEX_BYTES, presentRect, 16, 0);
            call(enc, SET_FRAGMENT_TEXTURE, srcTexture, 0);
            call(enc, SET_FRAGMENT_SAMPLER, srcW == width && srcH == height ? nearest : linear, 0);
            call(enc, DRAW, Mtl.PRIM_TRIANGLE, 0, 3);
            call(enc, END_ENCODING);
        } finally {
            ObjC.release(pass);
        }
    }

    private void draw(long srcTexture, int srcW, int srcH, int sx0, int sy0, int sx1, int sy1, long dstTexture, int dstFormat, int dstLevel, int dstSlice, int dstPlane,
                      int dx0, int dy0, int dx1, int dy1, boolean filterLinear, boolean depth, int load, boolean present) {
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
        MemoryUtil.memPutFloat(rect, u0);
        MemoryUtil.memPutFloat(rect + 4, v0);
        MemoryUtil.memPutFloat(rect + 8, u1);
        MemoryUtil.memPutFloat(rect + 12, v1);
        final long pass = ObjC.create("MTLRenderPassDescriptor");
        try {
            final long a = depth ? send(pass, DEPTH_ATTACHMENT) : send(send(pass, COLOR_ATTACHMENTS), OBJECT_AT, 0);
            call(a, SET_TEXTURE, dstTexture);
            call(a, SET_LEVEL, dstLevel);
            call(a, SET_SLICE, dstSlice);
            call(a, SET_DEPTH_PLANE, dstPlane);
            call(a, SET_LOAD, load);
            call(a, SET_STORE, Mtl.STORE_STORE);
            final long enc = gl.stream.render(pass);
            call(enc, SET_PIPELINE, pipeline);
            ObjC.callViewport(enc, SET_VIEWPORT, Math.min(dx0, dx1), Math.min(dy0, dy1), Math.abs(dx1 - dx0), Math.abs(dy1 - dy0), 0.0, 1.0);
            call(enc, SET_VERTEX_BYTES, rect, 16, 0);
            call(enc, SET_FRAGMENT_TEXTURE, srcTexture, 0);
            if (kind == KIND_FLOAT || kind == KIND_DEPTH) call(enc, SET_FRAGMENT_SAMPLER, filterLinear && !depth ? linear : nearest, 0);
            if (depth) call(enc, SET_DEPTH_STENCIL, depthWrite);
            call(enc, DRAW, Mtl.PRIM_TRIANGLE, 0, 3);
            gl.stream.endEncoder();
        } finally {
            ObjC.release(pass);
        }
    }

    public void destroy() {
        for (long p : pipelines.values()) ObjC.release(p);
        pipelines.clear();
        for (int i = 0; i < functions.length; i++) {
            ObjC.release(functions[i]);
            functions[i] = 0L;
        }
        ObjC.release(nearest);
        ObjC.release(linear);
        ObjC.release(depthWrite);
        ObjC.release(library);
        library = nearest = linear = depthWrite = 0L;
        MemoryUtil.nmemFree(rect);
        MemoryUtil.nmemFree(presentRect);
    }
}
