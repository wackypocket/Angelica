package com.gtnewhorizons.angelica.vulkan.gl;

import java.util.Arrays;

final class IntKey {
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

    static final class Probe {
        int[] data = new int[128];
        int length;
        int hash;

        void reset() {
            length = 0;
        }

        void add(int v) {
            if (length == data.length) data = Arrays.copyOf(data, length * 2);
            data[length++] = v;
        }

        void seal() {
            int h = 1;
            for (int i = 0; i < length; i++) h = 31 * h + data[i];
            hash = h;
        }

        boolean matches(IntKey k) {
            return k.hash == hash && k.data.length == length && Arrays.equals(k.data, 0, length, data, 0, length);
        }

        IntKey freeze() {
            return new IntKey(data, length);
        }
    }
}
