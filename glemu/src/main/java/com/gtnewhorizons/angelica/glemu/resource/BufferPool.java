package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.frame.SerialTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;

/** Recycles retired buffer allocations of the same size and usage once the GPU has finished with them. */
public final class BufferPool<A> {
    public static final long RENAME_LIMIT = 4L << 20;
    private static final long BYTE_LIMIT = 64L << 20;

    public interface Storage<A> {
        A create(long size, int usage);

        void destroy(A alloc);

        long size(A alloc);

        long mapped(A alloc);

        boolean reusable(A alloc, int usage);

        /** Runs after an allocation that outlived its last use has been destroyed. */
        default void onDeferredDestroy() {
        }
    }

    private record Pooled<A>(A alloc, long lastUse, long generation) {}

    private final SerialTracker stream;
    private final Storage<A> storage;
    private final Long2ObjectOpenHashMap<ArrayDeque<Pooled<A>>> pool = new Long2ObjectOpenHashMap<>();
    private long pooledBytes;
    private long trimGeneration;

    public BufferPool(SerialTracker stream, Storage<A> storage) {
        this.stream = stream;
        this.storage = storage;
    }

    public A acquire(long size, int usage) {
        final ArrayDeque<Pooled<A>> q = pool.get(size << 2 | usage);
        if (q != null) {
            final Pooled<A> head = q.peekFirst();
            if (head != null && stream.isIdle(head.lastUse)) {
                q.pollFirst();
                pooledBytes -= storage.size(head.alloc);
                return head.alloc;
            }
        }
        return storage.create(size, usage);
    }

    public void retire(A alloc, long lastUse, int usage) {
        if (alloc == null) return;
        final long size = storage.size(alloc);
        if (size <= RENAME_LIMIT && storage.reusable(alloc, usage) && pooledBytes + size <= BYTE_LIMIT) {
            pool.computeIfAbsent(size << 2 | usage, k -> new ArrayDeque<>()).addLast(new Pooled<>(alloc, lastUse, trimGeneration));
            pooledBytes += size;
            return;
        }
        destroyLater(alloc, lastUse);
    }

    private void destroyLater(A alloc, long lastUse) {
        stream.deferAfter(Math.max(lastUse, 0), () -> {
            storage.destroy(alloc);
            storage.onDeferredDestroy();
        });
    }

    /** Destroys allocations that sat unused since the previous trim. */
    public void trim() {
        final var it = pool.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            final ArrayDeque<Pooled<A>> q = it.next().getValue();
            while (!q.isEmpty() && q.peekFirst().generation < trimGeneration) {
                final Pooled<A> e = q.pollFirst();
                pooledBytes -= storage.size(e.alloc);
                destroyLater(e.alloc, e.lastUse);
            }
            if (q.isEmpty()) it.remove();
        }
        trimGeneration++;
    }

    public void destroy() {
        for (ArrayDeque<Pooled<A>> q : pool.values()) for (Pooled<A> e : q) storage.destroy(e.alloc);
        pool.clear();
        pooledBytes = 0;
    }
}
