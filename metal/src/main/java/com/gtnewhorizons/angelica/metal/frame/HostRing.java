package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;

public final class HostRing extends HostRingBase<MtlBuffer> {
    private final Gpu gpu;
    private final int memoryUsage;

    public HostRing(Gpu gpu, CommandStream stream, long chunkSize, int memoryUsage) {
        super(stream, chunkSize);
        this.gpu = gpu;
        this.memoryUsage = memoryUsage;
    }

    @Override protected MtlBuffer allocate(long size) { return MtlBuffer.create(gpu, size, memoryUsage); }
    @Override protected long handle(MtlBuffer buf) { return buf.buffer; }
    @Override protected long mapped(MtlBuffer buf) { return buf.mapped; }
    @Override protected void destroy(MtlBuffer buf) { buf.destroy(); }
}
