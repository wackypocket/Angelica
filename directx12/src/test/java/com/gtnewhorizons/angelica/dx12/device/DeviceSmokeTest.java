package com.gtnewhorizons.angelica.dx12.device;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("dx12-device")
class DeviceSmokeTest {
    static Gpu gpu;

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

    private static long buffer(Arena a, int heapType, long size, int state) {
        final MemorySegment heap = a.allocate(20, 4);
        heap.set(JAVA_INT, 0, heapType);
        heap.set(JAVA_INT, 12, 1);
        heap.set(JAVA_INT, 16, 1);
        final MemorySegment desc = a.allocate(56, 8);
        desc.set(JAVA_INT, 0, Dx.DIM_BUFFER);
        desc.set(JAVA_LONG, 16, size);
        desc.set(JAVA_INT, 24, 1);
        desc.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 28, (short) 1);
        desc.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 30, (short) 1);
        desc.set(JAVA_INT, 36, 1);
        desc.set(JAVA_INT, 44, 1);
        final MemorySegment out = a.allocate(8);
        Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMITTED_RESOURCE, heap.address(), 0, desc.address(), state, 0L, Dx.IID_RESOURCE, out.address()), "CreateCommittedResource");
        return out.get(JAVA_LONG, 0);
    }

    @Test
    void deviceIsUsable() {
        assertTrue(gpu.device != 0L && gpu.queue != 0L);
        assertTrue(gpu.cbvSrvUavIncrement > 0);
        assertTrue(gpu.timestampFrequency > 0);
    }

    @Test
    void bufferRoundTripThroughTheQueue() {
        try (Arena a = Arena.ofConfined()) {
            final int n = 256;
            final long upload = buffer(a, Dx.HEAP_UPLOAD, n, Dx.STATE_GENERIC_READ);
            final long vram = buffer(a, Dx.HEAP_DEFAULT, n, Dx.STATE_COMMON);
            final long readback = buffer(a, Dx.HEAP_READBACK, n, Dx.STATE_COPY_DEST);

            final MemorySegment ptr = a.allocate(8);
            Com.check(Com.hr(upload, Dx.RES_MAP, 0, 0L, ptr.address()), "Map");
            final MemorySegment src = MemorySegment.ofAddress(ptr.get(JAVA_LONG, 0)).reinterpret(n);
            for (int i = 0; i < n / 4; i++) src.setAtIndex(JAVA_INT, i, 0xC0DE0000 + i);
            Com.v(upload, Dx.RES_UNMAP, 0, 0L);

            final MemorySegment out = a.allocate(8);
            Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMAND_ALLOCATOR, Dx.LIST_DIRECT, Dx.IID_COMMAND_ALLOCATOR, out.address()), "CreateCommandAllocator");
            final long allocator = out.get(JAVA_LONG, 0);
            Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMAND_LIST, 0, Dx.LIST_DIRECT, allocator, 0L, Dx.IID_COMMAND_LIST, out.address()), "CreateCommandList");
            final long list = out.get(JAVA_LONG, 0);

            Com.v(list, Dx.CL_COPY_BUFFER, vram, 0, upload, 0, n);
            final MemorySegment barrier = a.allocate(32, 8);
            barrier.set(JAVA_INT, 0, Dx.BARRIER_TRANSITION);
            barrier.set(JAVA_LONG, 8, vram);
            barrier.set(JAVA_INT, 16, (int) Dx.ALL_SUBRESOURCES);
            barrier.set(JAVA_INT, 20, Dx.STATE_COPY_DEST);
            barrier.set(JAVA_INT, 24, Dx.STATE_COPY_SOURCE);
            Com.v(list, Dx.CL_BARRIER, 1, barrier.address());
            Com.v(list, Dx.CL_COPY_BUFFER, readback, 0, vram, 0, n);
            Com.check(Com.hr(list, Dx.CL_CLOSE), "Close");

            final MemorySegment lists = a.allocate(8);
            lists.set(JAVA_LONG, 0, list);
            Com.execute(gpu.queue, 1, lists.address());
            final long value = gpu.fence().signal(gpu.queue);
            assertTrue(gpu.fence().await(value, 5_000_000_000L), "queue did not finish");

            final MemorySegment range = a.allocate(16);
            range.set(JAVA_LONG, 8, n);
            Com.check(Com.hr(readback, Dx.RES_MAP, 0, range.address(), ptr.address()), "Map readback");
            final MemorySegment dst = MemorySegment.ofAddress(ptr.get(JAVA_LONG, 0)).reinterpret(n);
            for (int i = 0; i < n / 4; i++) assertEquals(0xC0DE0000 + i, dst.getAtIndex(JAVA_INT, i), "word " + i);
            Com.v(readback, Dx.RES_UNMAP, 0, 0L);

            Com.release(list);
            Com.release(allocator);
            Com.release(readback);
            Com.release(vram);
            Com.release(upload);
        }
    }
}
