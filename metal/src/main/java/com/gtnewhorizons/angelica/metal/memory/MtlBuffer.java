package com.gtnewhorizons.angelica.metal.memory;

import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;

import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class MtlBuffer {
    public static final int USAGE_DEVICE = 0;
    public static final int USAGE_UPLOAD = 1;
    public static final int USAGE_READBACK = 2;
    public static final int USAGE_STREAM = 3;

    private static final long NEW_BUFFER = sel("newBufferWithLength:options:"), CONTENTS = sel("contents");

    public final long buffer;
    public final long mapped;
    public final long size;

    private MtlBuffer(long buffer, long mapped, long size) {
        this.buffer = buffer;
        this.mapped = mapped;
        this.size = size;
    }

    private static final boolean FORCE_PRIVATE = Boolean.getBoolean("angelica.metal.privateBuffers");

    public static MtlBuffer create(Gpu gpu, long size, int usage) {
        final boolean gpuOnly = usage == USAGE_DEVICE && (!gpu.unifiedMemory || FORCE_PRIVATE);
        final long options = gpuOnly ? Mtl.RESOURCE_STORAGE_PRIVATE : Mtl.RESOURCE_STORAGE_SHARED | (usage == USAGE_UPLOAD ? Mtl.RESOURCE_CPU_WRITE_COMBINED : 0L);
        final long buffer = send(gpu.device, NEW_BUFFER, Math.max(size, 16), options);
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
