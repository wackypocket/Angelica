package com.gtnewhorizons.angelica.glemu.frame;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Host-visible bump allocator over fixed-size chunks that retire with the submission that last used them. */
public abstract class HostRingBase<B> {
    private final SerialStream stream;
    private final long chunkSize;

    private Chunk current;
    private final ArrayDeque<Chunk> retired = new ArrayDeque<>();
    private final ArrayDeque<Chunk> spare = new ArrayDeque<>();

    public long buffer;
    public long offset;
    public long address;

    private final class Chunk {
        final B buf;
        long head;
        long retireSerial;

        Chunk(B buf) {
            this.buf = buf;
        }
    }

    protected HostRingBase(SerialStream stream, long chunkSize) {
        this.stream = stream;
        this.chunkSize = chunkSize;
    }

    protected abstract B allocate(long size);

    protected abstract long handle(B buf);

    protected abstract long mapped(B buf);

    protected abstract void destroy(B buf);

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
        buffer = handle(c.buf);
        offset = at;
        address = mapped(c.buf) + at;
    }

    private Chunk nextChunk() {
        while (!retired.isEmpty() && stream.isIdle(retired.peekFirst().retireSerial)) {
            final Chunk c = retired.pollFirst();
            c.head = 0;
            spare.addLast(c);
        }
        final Chunk reuse = spare.pollFirst();
        if (reuse != null) return reuse;
        return new Chunk(allocate(chunkSize));
    }

    private final class Big {
        final B buf;
        final long size;
        long retireSerial;

        Big(B buf, long size) {
            this.buf = buf;
            this.size = size;
        }
    }

    private static final int BIG_POOL = 4;
    private final ArrayDeque<Big> bigs = new ArrayDeque<>();

    private void allocDedicated(long size) {
        // Requests over half a chunk get a dedicated buffer from a small pool, reused once the GPU has retired it.
        Big found = null;
        for (Big b : bigs) {
            if (b.size >= size && stream.isIdle(b.retireSerial)) {
                found = b;
                break;
            }
        }
        if (found != null) bigs.remove(found);
        else found = new Big(allocate(size), size);
        found.retireSerial = stream.currentSerial();
        bigs.addLast(found);
        while (bigs.size() > BIG_POOL) destroyBig(bigs.pollFirst());
        buffer = handle(found.buf);
        offset = 0;
        address = mapped(found.buf);
    }

    private void destroyBig(Big b) {
        if (stream.isIdle(b.retireSerial)) destroy(b.buf);
        else stream.defer(() -> destroy(b.buf));
    }

    public void trim(int keep) {
        for (Iterator<Big> it = bigs.iterator(); it.hasNext();) {
            final Big b = it.next();
            if (stream.isIdle(b.retireSerial)) {
                it.remove();
                destroyBig(b);
            }
        }
        while (spare.size() > keep) destroy(spare.pollLast().buf);
    }

    public void destroy() {
        if (current != null) destroy(current.buf);
        current = null;
        for (Chunk c : retired) destroy(c.buf);
        for (Chunk c : spare) destroy(c.buf);
        for (Big b : bigs) destroy(b.buf);
        retired.clear();
        spare.clear();
        bigs.clear();
    }
}
