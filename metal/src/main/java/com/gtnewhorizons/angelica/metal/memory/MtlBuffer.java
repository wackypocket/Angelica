package com.gtnewhorizons.angelica.metal.memory;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;

import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class MtlBuffer {
    public static final int USAGE_DEVICE = BuffersBase.USAGE_DEVICE;
    public static final int USAGE_UPLOAD = BuffersBase.USAGE_UPLOAD;
    public static final int USAGE_READBACK = BuffersBase.USAGE_READBACK;
    public static final int USAGE_STREAM = BuffersBase.USAGE_STREAM;

    private static final long NEW_BUFFER = sel("newBufferWithLength:options:"), CONTENTS = sel("contents");

    public final long buffer;
    public final long mapped;
    public final long size;

    private MtlBuffer(long buffer, long mapped, long size) {
        this.buffer = buffer;
        this.mapped = mapped;
        this.size = size;
    }

    public static MtlBuffer create(Gpu gpu, long size, int usage) {
        final boolean gpuOnly = usage == USAGE_DEVICE && !gpu.unifiedMemory;
        final long options = gpuOnly ? Mtl.RESOURCE_STORAGE_PRIVATE : Mtl.RESOURCE_STORAGE_SHARED | (usage == USAGE_UPLOAD ? Mtl.RESOURCE_CPU_WRITE_COMBINED : 0L);
        final long buffer = send(gpu.device, NEW_BUFFER, Math.max((size + 3L) & ~3L, 16), options);
        if (buffer == 0L) throw new OutOfMemoryError("Metal could not allocate a " + size + " byte buffer");
        return new MtlBuffer(buffer, gpuOnly ? 0L : send(buffer, CONTENTS), size);
    }

    public boolean isHostVisible() {
        return mapped != 0L;
    }

    public void destroy() {
        ObjC.release(buffer);
    }
}
