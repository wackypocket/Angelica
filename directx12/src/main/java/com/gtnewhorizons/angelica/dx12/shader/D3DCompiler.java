package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.dx12.device.Com;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class D3DCompiler {
    private D3DCompiler() {}

    public static final int OPTIMIZATION_LEVEL3 = 1 << 15, ENABLE_STRICTNESS = 1 << 11, DEBUG = 1, PACK_MATRIX_ROW_MAJOR = 1 << 3;

    private static final int BLOB_GET_POINTER = 3, BLOB_GET_SIZE = 4;

    private static final SymbolLookup LIB = Com.lookup("D3DCOMPILER_47.dll");
    private static final MethodHandle COMPILE = Com.downcall(LIB, "D3DCompile",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));

    public static final String LIBRARY_ID = "d3dcompiler_47";

    public static byte[] compile(String hlsl, String entry, String target, int flags) {
        try (Arena a = Arena.ofConfined()) {
            final byte[] src = hlsl.getBytes(StandardCharsets.UTF_8);
            final MemorySegment code = a.allocate(src.length);
            MemorySegment.copy(src, 0, code, JAVA_BYTE, 0, src.length);
            final MemorySegment out = a.allocate(16);
            final int hr = (int) COMPILE.invokeExact(code.address(), (long) src.length, 0L, 0L, 0L, a.allocateFrom(entry).address(), a.allocateFrom(target).address(), flags, 0, out.address(), out.address() + 8);
            final long blob = out.get(JAVA_LONG, 0), errors = out.get(JAVA_LONG, 8);
            try {
                if (hr < 0) {
                    throw new IllegalStateException("D3DCompile(" + target + ") failed: 0x" + Integer.toHexString(hr) + (errors != 0L ? "\n" + text(errors) : ""));
                }
                return bytes(blob);
            } finally {
                Com.release(blob);
                Com.release(errors);
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new IllegalStateException(t);
        }
    }

    private static byte[] bytes(long blob) {
        final long size = Com.p(blob, BLOB_GET_SIZE);
        final long ptr = Com.p(blob, BLOB_GET_POINTER);
        final byte[] out = new byte[(int) size];
        MemorySegment.copy(Com.RAW, JAVA_BYTE, ptr, out, 0, out.length);
        return out;
    }

    private static String text(long blob) {
        return new String(bytes(blob), StandardCharsets.UTF_8).trim();
    }
}
