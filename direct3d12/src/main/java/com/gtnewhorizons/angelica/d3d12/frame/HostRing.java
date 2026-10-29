package com.gtnewhorizons.angelica.d3d12.frame;

import com.gtnewhorizons.angelica.d3d12.device.Gpu;
import com.gtnewhorizons.angelica.d3d12.memory.GpuBuffer;
import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;

public final class HostRing extends HostRingBase<GpuBuffer> {
    private final Gpu gpu;
    private final int memoryUsage;

    public HostRing(Gpu gpu, CommandStream stream, long chunkSize, int memoryUsage) {
        super(stream, chunkSize);
        this.gpu = gpu;
        this.memoryUsage = memoryUsage;
    }

    @Override protected GpuBuffer allocate(long size) { return GpuBuffer.create(gpu, size, memoryUsage); }
    @Override protected long handle(GpuBuffer buf) { return buf.buffer; }
    @Override protected long mapped(GpuBuffer buf) { return buf.mapped; }
    @Override protected long gpuAddress(GpuBuffer buf) { return buf.gpuAddress; }
    @Override protected void destroy(GpuBuffer buf) { buf.destroy(); }
}
