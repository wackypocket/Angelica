package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;

import java.util.ArrayDeque;

public final class HostRing {
    private final Gpu gpu;
    private final CommandStream stream;
    private final long chunkSize;
    private final int memoryUsage;

    private Chunk current;
    private final ArrayDeque<Chunk> retired = new ArrayDeque<>();
    private final ArrayDeque<Chunk> spare = new ArrayDeque<>();

    public long buffer;
    public long offset;
    public long address;

    private static final class Chunk {
        final MtlBuffer buf;
        long head;
        long retireSerial;

        Chunk(MtlBuffer buf) {
            this.buf = buf;
        }
    }

    public HostRing(Gpu gpu, CommandStream stream, long chunkSize, int memoryUsage) {
        this.gpu = gpu;
        this.stream = stream;
        this.chunkSize = chunkSize;
        this.memoryUsage = memoryUsage;
    }

    public void alloc(long size, long alignment) {
        if (size > chunkSize / 2) {
            allocDedicated(size);
            return;
        }
        if (current != null) {
            final long aligned = (current.head + alignment - 1) / alignment * alignment;
            if (aligned + size <= chunkSize) {
                publish(current, aligned, size);
                return;
            }
            current.retireSerial = stream.currentSerial();
            retired.addLast(current);
            current = null;
        }
        current = nextChunk();
        publish(current, 0, size);
    }

    private void publish(Chunk c, long at, long size) {
        c.head = at + size;
        buffer = c.buf.buffer;
        offset = at;
        address = c.buf.mapped + at;
    }

    private Chunk nextChunk() {
        while (!retired.isEmpty() && stream.isIdle(retired.peekFirst().retireSerial)) {
            final Chunk c = retired.pollFirst();
            c.head = 0;
            spare.addLast(c);
        }
        final Chunk reuse = spare.pollFirst();
        if (reuse != null) return reuse;
        return new Chunk(MtlBuffer.create(gpu, chunkSize, memoryUsage));
    }

    private void allocDedicated(long size) {
        final MtlBuffer big = MtlBuffer.create(gpu, size, memoryUsage);
        stream.defer(big::destroy);
        buffer = big.buffer;
        offset = 0;
        address = big.mapped;
    }

    public void trim(int keep) {
        while (spare.size() > keep) spare.pollLast().buf.destroy();
    }

    public void destroy() {
        if (current != null) current.buf.destroy();
        current = null;
        for (Chunk c : retired) c.buf.destroy();
        for (Chunk c : spare) c.buf.destroy();
        retired.clear();
        spare.clear();
    }
}
