package com.gtnewhorizons.angelica.dx12.device;

import com.gtnewhorizons.angelica.dx12.pipeline.PsoDesc;
import com.gtnewhorizons.angelica.dx12.pipeline.RootSignatures;
import com.gtnewhorizons.angelica.dx12.shader.D3DCompiler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("dx12-device")
class TriangleTest {
    static Gpu gpu;

    private static final String VS = """
        struct VSOut { float4 pos : SV_Position; };
        VSOut main(uint id : SV_VertexID) {
            VSOut o;
            float2 p = float2((id << 1) & 2, id & 2);
            o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
            return o;
        }
        """;
    private static final String PS = """
        cbuffer Tint : register(b0, space3) { float4 tint; };
        float4 main(float4 pos : SV_Position) : SV_Target0 { return tint; }
        """;

    @BeforeAll
    static void setUp() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "Direct3D 12 exists only on Windows");
        gpu = new Gpu();
        assumeTrue(gpu.create(), "no Direct3D 12 device");
    }

    @AfterAll
    static void tearDown() {
        if (gpu != null) gpu.destroy();
    }

    private static long heap(Arena a, int type, int count, boolean visible) {
        final MemorySegment desc = a.allocate(16, 4);
        desc.set(JAVA_INT, 0, type);
        desc.set(JAVA_INT, 4, count);
        desc.set(JAVA_INT, 8, visible ? Dx.DH_SHADER_VISIBLE : 0);
        final MemorySegment out = a.allocate(8);
        Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_DESCRIPTOR_HEAP, desc.address(), Dx.IID_DESCRIPTOR_HEAP, out.address()), "CreateDescriptorHeap");
        return out.get(JAVA_LONG, 0);
    }

    private static long cpuStart(Arena a, long heap) {
        final MemorySegment out = a.allocate(8);
        Com.p(heap, Dx.HEAP_CPU_START, out.address());
        return out.get(JAVA_LONG, 0);
    }

    private static long gpuStart(Arena a, long heap) {
        final MemorySegment out = a.allocate(8);
        Com.p(heap, Dx.HEAP_GPU_START, out.address());
        return out.get(JAVA_LONG, 0);
    }

    private static long committed(Arena a, int heapType, MemorySegment desc, int state) {
        final MemorySegment props = a.allocate(20, 4);
        props.set(JAVA_INT, 0, heapType);
        props.set(JAVA_INT, 12, 1);
        props.set(JAVA_INT, 16, 1);
        final MemorySegment out = a.allocate(8);
        Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMITTED_RESOURCE, props.address(), 0, desc.address(), state, 0L, Dx.IID_RESOURCE, out.address()), "CreateCommittedResource");
        return out.get(JAVA_LONG, 0);
    }

    private static MemorySegment bufferDesc(Arena a, long size) {
        final MemorySegment desc = a.allocate(56, 8);
        desc.set(JAVA_INT, 0, Dx.DIM_BUFFER);
        desc.set(JAVA_LONG, 16, size);
        desc.set(JAVA_INT, 24, 1);
        desc.set(JAVA_SHORT, 28, (short) 1);
        desc.set(JAVA_SHORT, 30, (short) 1);
        desc.set(JAVA_INT, 36, 1);
        desc.set(JAVA_INT, 44, 1);
        return desc;
    }

    @Test
    void drawsATintedTriangleThroughTheSharedRootSignature() {
        final int w = 64, h = 64;
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment texDesc = a.allocate(56, 8);
            texDesc.set(JAVA_INT, 0, Dx.DIM_TEX2D);
            texDesc.set(JAVA_LONG, 16, w);
            texDesc.set(JAVA_INT, 24, h);
            texDesc.set(JAVA_SHORT, 28, (short) 1);
            texDesc.set(JAVA_SHORT, 30, (short) 1);
            texDesc.set(JAVA_INT, 32, Dx.FMT_R8G8B8A8_UNORM);
            texDesc.set(JAVA_INT, 36, 1);
            texDesc.set(JAVA_INT, 44, 0);
            texDesc.set(JAVA_INT, 48, Dx.RF_RENDER_TARGET);
            final long target = committed(a, Dx.HEAP_DEFAULT, texDesc, Dx.STATE_RENDER_TARGET);

            final long rtvHeap = heap(a, Dx.DH_RTV, 1, false);
            final long rtv = cpuStart(a, rtvHeap);
            Com.v(gpu.device, Dx.DEV_CREATE_RTV, target, 0L, rtv);

            final long cpuHeap = heap(a, Dx.DH_CBV_SRV_UAV, 16, false);
            final long gpuHeap = heap(a, Dx.DH_CBV_SRV_UAV, 64, true);
            final long constants = committed(a, Dx.HEAP_UPLOAD, bufferDesc(a, 256), Dx.STATE_GENERIC_READ);
            final MemorySegment mapped = a.allocate(8);
            Com.check(Com.hr(constants, Dx.RES_MAP, 0, 0L, mapped.address()), "Map");
            final MemorySegment cb = MemorySegment.ofAddress(mapped.get(JAVA_LONG, 0)).reinterpret(256);
            cb.set(JAVA_FLOAT, 0, 0.25f);
            cb.set(JAVA_FLOAT, 4, 0.5f);
            cb.set(JAVA_FLOAT, 8, 1.0f);
            cb.set(JAVA_FLOAT, 12, 1.0f);
            Com.v(constants, Dx.RES_UNMAP, 0, 0L);


            final RootSignatures roots = new RootSignatures(gpu);
            final RootSignatures.Layout layout = roots.graphics(0, 0, 0, 0, 0, 0, 1, 0);
            final long root = layout.root();
            final MemorySegment vs = bytes(a, D3DCompiler.compile(VS, "main", "vs_5_1", D3DCompiler.OPTIMIZATION_LEVEL3));
            final MemorySegment ps = bytes(a, D3DCompiler.compile(PS, "main", "ps_5_1", D3DCompiler.OPTIMIZATION_LEVEL3));
            final long pso = new PsoDesc(a).rootSignature(root).vertexShader(vs).pixelShader(ps).renderTargets(1, Dx.FMT_R8G8B8A8_UNORM).create(gpu, a);

            final long readback = committed(a, Dx.HEAP_READBACK, bufferDesc(a, 256L * h), Dx.STATE_COPY_DEST);

            final MemorySegment out = a.allocate(8);
            Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMAND_ALLOCATOR, Dx.LIST_DIRECT, Dx.IID_COMMAND_ALLOCATOR, out.address()), "CreateCommandAllocator");
            final long allocator = out.get(JAVA_LONG, 0);
            Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMAND_LIST, 0, Dx.LIST_DIRECT, allocator, 0L, Dx.IID_COMMAND_LIST, out.address()), "CreateCommandList");
            final long list = out.get(JAVA_LONG, 0);

            final MemorySegment heaps = a.allocate(8);
            heaps.set(JAVA_LONG, 0, gpuHeap);
            Com.v(list, Dx.CL_SET_HEAPS, 1, heaps.address());
            Com.v(list, Dx.CL_GFX_ROOT_SIG, root);
            Com.v(list, Dx.CL_SET_PSO, pso);
            Com.v(list, Dx.CL_GFX_ROOT_CBV, layout.param(1, RootSignatures.K_CBV), Com.p(constants, Dx.RES_GPU_VA));

            final MemorySegment rtvs = a.allocate(8);
            rtvs.set(JAVA_LONG, 0, rtv);
            Com.v(list, Dx.CL_RENDER_TARGETS, 1, rtvs.address(), 0, 0L);
            final MemorySegment clear = a.allocate(16);
            Com.v(list, Dx.CL_CLEAR_RTV, rtv, clear.address(), 0, 0L);
            final MemorySegment vp = a.allocate(24, 4);
            vp.set(JAVA_FLOAT, 8, w);
            vp.set(JAVA_FLOAT, 12, h);
            vp.set(JAVA_FLOAT, 20, 1f);
            Com.v(list, Dx.CL_VIEWPORTS, 1, vp.address());
            final MemorySegment sc = a.allocate(16, 4);
            sc.set(JAVA_INT, 8, w);
            sc.set(JAVA_INT, 12, h);
            Com.v(list, Dx.CL_SCISSORS, 1, sc.address());
            Com.v(list, Dx.CL_TOPOLOGY, 4);
            Com.v(list, Dx.CL_DRAW, 3, 1, 0, 0);

            final MemorySegment barrier = a.allocate(32, 8);
            barrier.set(JAVA_INT, 0, Dx.BARRIER_TRANSITION);
            barrier.set(JAVA_LONG, 8, target);
            barrier.set(JAVA_INT, 16, (int) Dx.ALL_SUBRESOURCES);
            barrier.set(JAVA_INT, 20, Dx.STATE_RENDER_TARGET);
            barrier.set(JAVA_INT, 24, Dx.STATE_COPY_SOURCE);
            Com.v(list, Dx.CL_BARRIER, 1, barrier.address());

            final MemorySegment dst = a.allocate(48, 8);
            dst.set(JAVA_LONG, 0, readback);
            dst.set(JAVA_INT, 8, 1);
            dst.set(JAVA_INT, 24, Dx.FMT_R8G8B8A8_UNORM);
            dst.set(JAVA_INT, 28, w);
            dst.set(JAVA_INT, 32, h);
            dst.set(JAVA_INT, 36, 1);
            dst.set(JAVA_INT, 40, 256);
            final MemorySegment src = a.allocate(48, 8);
            src.set(JAVA_LONG, 0, target);
            src.set(JAVA_INT, 8, 0);
            src.set(JAVA_INT, 16, 0);
            Com.v(list, Dx.CL_COPY_TEXTURE, dst.address(), 0, 0, 0, src.address(), 0L);
            Com.check(Com.hr(list, Dx.CL_CLOSE), "Close");

            final MemorySegment lists = a.allocate(8);
            lists.set(JAVA_LONG, 0, list);
            Com.execute(gpu.queue, 1, lists.address());
            gpu.fence().await(gpu.fence().signal(gpu.queue));

            final MemorySegment range = a.allocate(16);
            range.set(JAVA_LONG, 8, 256L * h);
            Com.check(Com.hr(readback, Dx.RES_MAP, 0, range.address(), mapped.address()), "Map readback");
            final MemorySegment px = MemorySegment.ofAddress(mapped.get(JAVA_LONG, 0)).reinterpret(256L * h);
            for (int y : new int[] {0, h / 2, h - 1}) {
                for (int x : new int[] {0, w / 2, w - 1}) {
                    final long o = 256L * y + 4L * x;
                    assertEquals(64, px.get(JAVA_BYTE, o) & 0xFF, 1.0, "r at " + x + "," + y);
                    assertEquals(128, px.get(JAVA_BYTE, o + 1) & 0xFF, 1.0, "g at " + x + "," + y);
                    assertEquals(255, px.get(JAVA_BYTE, o + 2) & 0xFF, "b at " + x + "," + y);
                    assertEquals(255, px.get(JAVA_BYTE, o + 3) & 0xFF, "a at " + x + "," + y);
                }
            }
            Com.v(readback, Dx.RES_UNMAP, 0, 0L);
        }
    }

    private static MemorySegment bytes(Arena a, byte[] data) {
        final MemorySegment seg = a.allocate(data.length, 8);
        MemorySegment.copy(data, 0, seg, JAVA_BYTE, 0, data.length);
        return seg;
    }
}
