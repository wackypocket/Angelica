package com.gtnewhorizons.angelica.glemu.util;

import java.util.Arrays;

public final class IntKey {
    final int[] data;
    private final int hash;

    IntKey(int[] data, int length) {
        this.data = Arrays.copyOf(data, length);
        this.hash = Arrays.hashCode(this.data);
    }

    @Override public int hashCode() {
        return hash;
    }

    @Override public boolean equals(Object o) {
        return o instanceof IntKey k && k.hash == hash && Arrays.equals(k.data, data);
    }

    public static final class Probe {
        public int[] data = new int[128];
        public int length;
        public int hash;

        public void reset() {
            length = 0;
        }

        public void add(int v) {
            if (length == data.length) data = Arrays.copyOf(data, length * 2);
            data[length++] = v;
        }

        public void seal() {
            int h = 1;
            for (int i = 0; i < length; i++) h = 31 * h + data[i];
            hash = h;
        }

        public boolean matches(IntKey k) {
            return k.hash == hash && k.data.length == length && Arrays.equals(k.data, 0, length, data, 0, length);
        }

        public IntKey freeze() {
            return new IntKey(data, length);
        }
    }
}
