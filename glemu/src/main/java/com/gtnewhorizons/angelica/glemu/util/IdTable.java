package com.gtnewhorizons.angelica.glemu.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;

import java.util.Arrays;

public final class IdTable<T> {
    private static final int DENSE_LIMIT = 1 << 18;

    private final Int2ObjectOpenHashMap<T> map = new Int2ObjectOpenHashMap<>();
    private Object[] dense = new Object[256];

    @SuppressWarnings("unchecked")
    public T get(int id) {
        final Object[] d = dense;
        if (id >= 0 && id < d.length) return (T) d[id];
        return id >= DENSE_LIMIT || id < 0 ? map.get(id) : null;
    }

    public boolean containsKey(int id) {
        return map.containsKey(id);
    }

    public T put(int id, T value) {
        if (id >= 0 && id < DENSE_LIMIT) {
            if (id >= dense.length) dense = Arrays.copyOf(dense, Math.min(DENSE_LIMIT, Math.max(id + 1, dense.length * 2)));
            dense[id] = value;
        }
        return map.put(id, value);
    }

    public T remove(int id) {
        if (id >= 0 && id < dense.length) dense[id] = null;
        return map.remove(id);
    }

    public ObjectCollection<T> values() {
        return map.values();
    }

    public void clear() {
        map.clear();
        Arrays.fill(dense, null);
    }
}
