package com.gtnewhorizons.angelica.dx12.shader;

import org.lwjgl.system.MemoryUtil;

public final class Bytecode {
    public final long address;
    public final int size;

    public Bytecode(byte[] data) {
        this.size = data.length;
        this.address = MemoryUtil.nmemAlloc(data.length);
        final java.nio.ByteBuffer view = MemoryUtil.memByteBuffer(address, data.length);
        view.put(data);
    }

    public void free() {
        MemoryUtil.nmemFree(address);
    }
}
