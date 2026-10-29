package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class DxcCompiler {
    private static final long CLSID_DXC_COMPILER = Dx.guid(0x73e22d93, 0xe6ce, 0x47f3, 0xb5, 0xbf, 0xf0, 0x66, 0x4f, 0x39, 0xc1, 0xb0);
    private static final long IID_DXC_COMPILER3 = Dx.guid(0x228b4687, 0x5a6a, 0x4730, 0x90, 0x0c, 0x97, 0x02, 0xb2, 0x20, 0x3f, 0x54);
    private static final long IID_DXC_RESULT = Dx.guid(0x58346cda, 0xdde7, 0x4497, 0x94, 0x61, 0x6f, 0x87, 0xaf, 0x5e, 0x06, 0x59);

    private static final int COMPILE = 3;
    private static final int RESULT_GET_STATUS = 3, RESULT_GET_RESULT = 4, RESULT_GET_ERROR_BUFFER = 5;
    private static final int BLOB_GET_POINTER = 3, BLOB_GET_SIZE = 4;
    private static final int CP_UTF8 = 65001;

    private static final String[] ARGS = {"-E", "main", "-O3", "-HV", "2018"};

    private final MethodHandle createInstance;
    private final Charset wide;
    private final int wcharBytes;
    private final ThreadLocal<Long> compilers;
    public final String id;

    private DxcCompiler(MethodHandle createInstance, int wcharBytes, String id) {
        this.createInstance = createInstance;
        this.wcharBytes = wcharBytes;
        this.wide = wcharBytes == 2 ? StandardCharsets.UTF_16LE : Charset.forName("UTF-32LE");
        this.id = id;
        this.compilers = ThreadLocal.withInitial(this::newCompiler);
    }

    public static DxcCompiler load(Path library) {
        final int wcharBytes = System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? 2 : 4;
        final SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        final MethodHandle create = Com.downcall(lib, "DxcCreateInstance", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        final DxcCompiler dxc = new DxcCompiler(create, wcharBytes, "dxc-" + fingerprint(library) + "-" + String.join(" ", ARGS).hashCode());
        dxc.compilers.get();
        return dxc;
    }

    private static String fingerprint(Path library) {
        try {
            final CRC32 crc = new CRC32();
            final byte[] bytes = Files.readAllBytes(library);
            crc.update(bytes);
            return bytes.length + "-" + Long.toHexString(crc.getValue());
        } catch (java.io.IOException e) {
            return "unknown";
        }
    }

    private Long newCompiler() {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            final int hr = (int) createInstance.invokeExact(CLSID_DXC_COMPILER, IID_DXC_COMPILER3, out.address());
            if (hr < 0) throw new IllegalStateException("DxcCreateInstance failed: 0x" + Integer.toHexString(hr));
            return out.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new IllegalStateException(t);
        }
    }

    public byte[] compile(String hlsl, String target) {
        final long compiler = compilers.get();
        try (Arena a = Arena.ofConfined()) {
            final byte[] src = hlsl.getBytes(StandardCharsets.UTF_8);
            final MemorySegment code = a.allocate(src.length);
            MemorySegment.copy(src, 0, code, JAVA_BYTE, 0, src.length);
            final MemorySegment buffer = a.allocate(24, 8);
            buffer.set(JAVA_LONG, 0, code.address());
            buffer.set(JAVA_LONG, 8, src.length);
            buffer.set(JAVA_INT, 16, CP_UTF8);

            final String[] args = new String[ARGS.length + 2];
            System.arraycopy(ARGS, 0, args, 0, ARGS.length);
            args[ARGS.length] = "-T";
            args[ARGS.length + 1] = target;
            final MemorySegment argv = a.allocate(8L * args.length, 8);
            for (int i = 0; i < args.length; i++) argv.set(JAVA_LONG, 8L * i, wideString(a, args[i]).address());

            final MemorySegment out = a.allocate(8);
            Com.check(Com.hr(compiler, COMPILE, buffer.address(), argv.address(), args.length, 0L, IID_DXC_RESULT, out.address()), "IDxcCompiler3::Compile");
            final long result = out.get(JAVA_LONG, 0);
            try {
                final MemorySegment status = a.allocate(4);
                Com.check(Com.hr(result, RESULT_GET_STATUS, status.address()), "IDxcResult::GetStatus");
                if (status.get(JAVA_INT, 0) < 0) {
                    throw new IllegalStateException("DXC(" + target + ") failed: 0x" + Integer.toHexString(status.get(JAVA_INT, 0)) + "\n" + blobText(a, result, RESULT_GET_ERROR_BUFFER));
                }
                Com.check(Com.hr(result, RESULT_GET_RESULT, out.address()), "IDxcResult::GetResult");
                final long blob = out.get(JAVA_LONG, 0);
                try {
                    return bytes(blob);
                } finally {
                    Com.release(blob);
                }
            } finally {
                Com.release(result);
            }
        }
    }

    private MemorySegment wideString(Arena a, String s) {
        final byte[] b = s.getBytes(wide);
        final MemorySegment seg = a.allocate(b.length + wcharBytes);
        MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
        return seg;
    }

    private static String blobText(Arena a, long result, int slot) {
        final MemorySegment out = a.allocate(8);
        if (Com.hr(result, slot, out.address()) < 0 || out.get(JAVA_LONG, 0) == 0L) return "";
        final long blob = out.get(JAVA_LONG, 0);
        try {
            return new String(bytes(blob), StandardCharsets.UTF_8).replace("\0", "").trim();
        } finally {
            Com.release(blob);
        }
    }

    private static byte[] bytes(long blob) {
        final long size = Com.p(blob, BLOB_GET_SIZE);
        final long ptr = Com.p(blob, BLOB_GET_POINTER);
        final byte[] out = new byte[(int) size];
        MemorySegment.copy(Com.RAW, JAVA_BYTE, ptr, out, 0, out.length);
        return out;
    }
}
