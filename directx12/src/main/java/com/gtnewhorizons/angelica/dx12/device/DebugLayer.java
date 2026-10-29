package com.gtnewhorizons.angelica.dx12.device;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class DebugLayer {
    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static final long IID_DEBUG = Dx.guid(0x344488b7, 0x6846, 0x474e, 0xb8, 0x29, 0xf9, 0x61, 0x41, 0x2a, 0x0b, 0x0e);
    private static final long IID_INFO_QUEUE = Dx.guid(0x0742a90b, 0xc387, 0x483f, 0xb9, 0x46, 0x30, 0xa7, 0xe4, 0xe6, 0x14, 0x58);

    private static final int INFO_GET_MESSAGE = 5, INFO_NUM_STORED = 8, INFO_CLEAR = 4;

    private static long infoQueue;

    private DebugLayer() {}

    public static void enable() {
        try {
            final SymbolLookup d3d12 = Com.lookup("d3d12.dll");
            final MethodHandle get = Com.downcall(d3d12, "D3D12GetDebugInterface", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
            try (Arena a = Arena.ofConfined()) {
                final MemorySegment out = a.allocate(8);
                final int hr = (int) get.invokeExact(IID_DEBUG, out.address());
                if (hr < 0) {
                    System.err.println("The D3D12 debug layer is not available: 0x" + Integer.toHexString(hr));
                    return;
                }
                Com.v(out.get(JAVA_LONG, 0), 3);
            }
        } catch (Throwable t) {
            LOG.warn("Could not enable the D3D12 debug layer: {}", t.toString());
        }
    }

    public static void attach(long device) {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            final int hr = Com.hr(device, 0, IID_INFO_QUEUE, out.address());
            if (hr >= 0) infoQueue = out.get(JAVA_LONG, 0);
            else System.err.println("No D3D12 info queue: 0x" + Integer.toHexString(hr));
        }
    }

    public static void dump(String context) {
        if (infoQueue == 0L) return;
        try (Arena a = Arena.ofConfined()) {
            final long n = Com.p(infoQueue, INFO_NUM_STORED);
            final MemorySegment len = a.allocate(8);
            for (long i = 0; i < n; i++) {
                len.set(JAVA_LONG, 0, 0);
                Com.hr(infoQueue, INFO_GET_MESSAGE, i, 0L, len.address());
                final long size = len.get(JAVA_LONG, 0);
                if (size <= 0) continue;
                final MemorySegment msg = a.allocate(size, 8);
                if (Com.hr(infoQueue, INFO_GET_MESSAGE, i, msg.address(), len.address()) < 0) continue;
                final int severity = msg.get(JAVA_INT, 4);
                final long text = msg.get(JAVA_LONG, 16);
                final long bytes = msg.get(JAVA_LONG, 24);
                final byte[] b = new byte[(int) Math.max(0, bytes - 1)];
                MemorySegment.copy(Com.RAW, JAVA_BYTE, text, b, 0, b.length);
                System.err.println("D3D12 [" + severity + "] " + context + ": " + new String(b, StandardCharsets.UTF_8));
            }
            Com.v(infoQueue, INFO_CLEAR);
        }
    }
}