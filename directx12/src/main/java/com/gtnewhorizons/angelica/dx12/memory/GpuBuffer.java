package com.gtnewhorizons.angelica.dx12.memory;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

public final class GpuBuffer {
    public static final int USAGE_DEVICE = 0;
    public static final int USAGE_UPLOAD = 1;
    public static final int USAGE_READBACK = 2;
    public static final int USAGE_STREAM = 3;
    public static final int USAGE_UNIFORM = 4;

    public final long buffer;
    public final long mapped;
    public final long size;
    public final long gpuAddress;
    public final int usage;
    public int state;

    private GpuBuffer(long buffer, long mapped, long size, int usage, int state) {
        this.buffer = buffer;
        this.mapped = mapped;
        this.size = size;
        this.usage = usage;
        this.state = state;
        this.gpuAddress = Com.p(buffer, Dx.RES_GPU_VA);
    }

    public static GpuBuffer create(Gpu gpu, long size, int usage) {
        final long alloc = Math.max(size, 16);
        try (Arena a = Arena.ofConfined()) {
            final boolean vram = usage == USAGE_UNIFORM && gpu.gpuUploadHeap;
            final int heapType = usage == USAGE_DEVICE ? Dx.HEAP_DEFAULT : usage == USAGE_READBACK ? Dx.HEAP_READBACK : vram ? Dx.HEAP_GPU_UPLOAD : Dx.HEAP_UPLOAD;
            final int state = usage == USAGE_READBACK ? Dx.STATE_COPY_DEST : vram ? Dx.STATE_COMMON : Dx.STATE_GENERIC_READ;
            final MemorySegment props = a.allocate(20, 4);
            props.set(JAVA_INT, 0, heapType);
            props.set(JAVA_INT, 12, 1);
            props.set(JAVA_INT, 16, 1);
            final MemorySegment desc = a.allocate(56, 8);
            desc.set(JAVA_INT, 0, Dx.DIM_BUFFER);
            desc.set(JAVA_LONG, 16, alloc);
            desc.set(JAVA_INT, 24, 1);
            desc.set(JAVA_SHORT, 28, (short) 1);
            desc.set(JAVA_SHORT, 30, (short) 1);
            desc.set(JAVA_INT, 36, 1);
            desc.set(JAVA_INT, 44, 1);
            desc.set(JAVA_INT, 48, usage == USAGE_DEVICE ? Dx.RF_UNORDERED_ACCESS : Dx.RF_NONE);
            final MemorySegment out = a.allocate(8);
            final int hr = Com.hr(gpu.device, Dx.DEV_CREATE_COMMITTED_RESOURCE, props.address(), 0, desc.address(), state, 0L, Dx.IID_RESOURCE, out.address());
            if (hr < 0) throw new OutOfMemoryError("Direct3D 12 could not allocate a " + size + " byte buffer: 0x" + Integer.toHexString(hr));
            final long resource = out.get(JAVA_LONG, 0);
            long mapped = 0L;
            if (usage != USAGE_DEVICE) {
                final MemorySegment ptr = a.allocate(8);
                Com.check(Com.hr(resource, Dx.RES_MAP, 0, 0L, ptr.address()), "Map");
                mapped = ptr.get(JAVA_LONG, 0);
            }
            return new GpuBuffer(resource, mapped, alloc, usage, state);
        }
    }

    public boolean isHostVisible() {
        return mapped != 0L;
    }

    public boolean isDeviceOnly() {
        return usage == USAGE_DEVICE;
    }

    public void destroy() {
        Com.release(buffer);
        com.gtnewhorizons.angelica.dx12.device.Descriptors.epoch++;
    }
}
