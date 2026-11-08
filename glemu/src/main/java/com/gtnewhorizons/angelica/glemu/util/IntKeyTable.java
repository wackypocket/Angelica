package com.gtnewhorizons.angelica.glemu.util;

import java.util.Arrays;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** An open-addressed map from {@link IntKey} to a long, probed without allocating a key on a hit. */
public final class IntKeyTable {
    public static final long MISSING = Long.MIN_VALUE;

    private IntKey[] keys = new IntKey[1024];
    private long[] values = new long[1024];
    private int size;

    public long find(IntKey.Probe p) {
        final int mask = keys.length - 1;
        int i = p.hash & mask;
        while (true) {
            final IntKey k = keys[i];
            if (k == null) return MISSING;
            if (p.matches(k)) return values[i];
            i = (i + 1) & mask;
        }
    }

    /** Stores a value for a probe that {@link #find} just missed. */
    public void put(IntKey.Probe p, long value) {
        if (size * 2 >= keys.length) resize(keys.length * 2);
        insert(p.freeze(), p.hash, value);
    }

    private void insert(IntKey key, int hash, long value) {
        final int mask = keys.length - 1;
        int i = hash & mask;
        while (keys[i] != null) i = (i + 1) & mask;
        keys[i] = key;
        values[i] = value;
        size++;
    }

    private void resize(int capacity) {
        final IntKey[] oldKeys = keys;
        final long[] oldValues = values;
        keys = new IntKey[capacity];
        values = new long[capacity];
        size = 0;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] != null) insert(oldKeys[j], oldKeys[j].hashCode(), oldValues[j]);
        }
    }

    public void clear() {
        Arrays.fill(keys, null);
        size = 0;
    }

    public void removeIf(Predicate<IntKey> stale, LongConsumer removed) {
        if (Arrays.stream(keys).noneMatch(k -> k != null && stale.test(k))) return;
        final IntKey[] oldKeys = keys;
        final long[] oldValues = values;
        keys = new IntKey[oldKeys.length];
        values = new long[oldKeys.length];
        size = 0;
        for (int j = 0; j < oldKeys.length; j++) {
            final IntKey k = oldKeys[j];
            if (k == null) continue;
            if (stale.test(k)) removed.accept(oldValues[j]);
            else insert(k, k.hashCode(), oldValues[j]);
        }
    }
}
