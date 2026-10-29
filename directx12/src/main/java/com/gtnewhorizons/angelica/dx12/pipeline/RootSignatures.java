package com.gtnewhorizons.angelica.dx12.pipeline;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class RootSignatures {
    public static final int MAX_SRV = 128, MAX_SAMPLER = 16, MAX_CBV = 14, MAX_UAV = 8;
    public static final int RANGE_SRV = 0, RANGE_UAV = 1, RANGE_CBV = 2, RANGE_SAMPLER = 3;
    public static final int VIS_ALL = 0, VIS_VERTEX = 1, VIS_PIXEL = 5;
    public static final int FLAG_ALLOW_INPUT_ASSEMBLER = 1;

    private static final SymbolLookup D3D12 = Com.lookup("d3d12.dll");
    private static final MethodHandle SERIALIZE = Com.downcall(D3D12, "D3D12SerializeRootSignature", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));

    private static final int BLOB_GET_POINTER = 3, BLOB_GET_SIZE = 4;

    public record Table(int visibility, int rangeType, int count, int space) {}

    public record Layout(long root, int[][] params) {
        public int param(int stage, int kind) {
            return params[stage][kind];
        }
    }

    public static final int K_SRV = 0, K_SAMPLER = 1, K_CBV = 2, K_UAV = 3;

    private static final int PARAM_TABLE = 0, PARAM_CBV = 2;

    private final Gpu gpu;
    private final Map<String, Layout> cache = new HashMap<>();

    public RootSignatures(Gpu gpu) {
        this.gpu = gpu;
    }

    public Layout graphics(int... counts) {
        final String key = "g" + java.util.Arrays.toString(counts);
        final Layout hit = cache.get(key);
        if (hit != null) return hit;
        final int[][] params = {{-1, -1, -1, -1}, {-1, -1, -1, -1}};
        final Table[] tables = new Table[8];
        int n = 0, index = 0;
        final int[] spaces = {0, 0, 1, 4, 2, 2, 3, 5};
        final int[] types = {RANGE_SRV, RANGE_SAMPLER, RANGE_CBV, RANGE_UAV};
        for (int i = 0; i < 8; i++) {
            if (counts[i] == 0) continue;
            final int stage = i / 4, kind = i % 4;
            params[stage][kind] = index;
            tables[n++] = new Table(stage == 0 ? VIS_VERTEX : VIS_PIXEL, types[kind], counts[i], spaces[i]);
            index += types[kind] == RANGE_CBV ? counts[i] : 1;
        }
        final Layout layout = new Layout(create(gpu, FLAG_ALLOW_INPUT_ASSEMBLER, java.util.Arrays.copyOf(tables, n)), params);
        cache.put(key, layout);
        return layout;
    }

    public Layout compute(int srv, int sampler, int cbv, int uav) {
        final String key = "c" + srv + "," + sampler + "," + cbv + "," + uav;
        final Layout hit = cache.get(key);
        if (hit != null) return hit;
        final int[][] params = {{-1, -1, -1, -1}, {-1, -1, -1, -1}};
        final Table[] tables = new Table[4];
        int n = 0, index = 0;
        final int[] counts = {srv, sampler, cbv, uav};
        final int[] spaces = {0, 0, 1, 2};
        final int[] types = {RANGE_SRV, RANGE_SAMPLER, RANGE_CBV, RANGE_UAV};
        for (int kind = 0; kind < 4; kind++) {
            if (counts[kind] == 0) continue;
            params[0][kind] = index;
            tables[n++] = new Table(VIS_ALL, types[kind], counts[kind], spaces[kind]);
            index += types[kind] == RANGE_CBV ? counts[kind] : 1;
        }
        final Layout layout = new Layout(create(gpu, 0, java.util.Arrays.copyOf(tables, n)), params);
        cache.put(key, layout);
        return layout;
    }

    public void destroy() {
        for (Layout l : cache.values()) Com.release(l.root);
        cache.clear();
    }

    public static long create(Gpu gpu, int flags, Table... tables) {
        try (Arena a = Arena.ofConfined()) {
            int paramCount = 0;
            for (Table t : tables) paramCount += t.rangeType == RANGE_CBV ? t.count : 1;
            final MemorySegment ranges = a.allocate(20L * Math.max(1, tables.length), 4);
            final MemorySegment params = a.allocate(32L * Math.max(1, paramCount), 8);
            int p = 0;
            for (int i = 0; i < tables.length; i++) {
                final Table t = tables[i];
                if (t.rangeType == RANGE_CBV) {
                    for (int reg = 0; reg < t.count; reg++, p++) {
                        final long o = 32L * p;
                        params.set(JAVA_INT, o, PARAM_CBV);
                        params.set(JAVA_INT, o + 8, reg);
                        params.set(JAVA_INT, o + 12, t.space);
                        params.set(JAVA_INT, o + 24, t.visibility);
                    }
                    continue;
                }
                final long r = 20L * i;
                ranges.set(JAVA_INT, r, t.rangeType);
                ranges.set(JAVA_INT, r + 4, t.count);
                ranges.set(JAVA_INT, r + 8, 0);
                ranges.set(JAVA_INT, r + 12, t.space);
                ranges.set(JAVA_INT, r + 16, 0);
                final long o = 32L * p++;
                params.set(JAVA_INT, o, PARAM_TABLE);
                params.set(JAVA_INT, o + 8, 1);
                params.set(JAVA_LONG, o + 16, ranges.address() + r);
                params.set(JAVA_INT, o + 24, t.visibility);
            }
            final MemorySegment desc = a.allocate(40, 8);
            desc.set(JAVA_INT, 0, paramCount);
            desc.set(JAVA_LONG, 8, params.address());
            desc.set(JAVA_INT, 32, flags);
            final MemorySegment out = a.allocate(16);
            final int hr = (int) SERIALIZE.invokeExact(desc.address(), 1, out.address(), out.address() + 8);
            final long blob = out.get(JAVA_LONG, 0), errors = out.get(JAVA_LONG, 8);
            try {
                if (hr < 0) throw new IllegalStateException("D3D12SerializeRootSignature failed: 0x" + Integer.toHexString(hr) + (errors != 0L ? " " + text(errors) : ""));
                final MemorySegment result = a.allocate(8);
                Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_ROOT_SIGNATURE, 0, Com.p(blob, BLOB_GET_POINTER), Com.p(blob, BLOB_GET_SIZE), Dx.IID_ROOT_SIGNATURE, result.address()), "CreateRootSignature");
                return result.get(JAVA_LONG, 0);
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

    private static String text(long blob) {
        final byte[] b = new byte[(int) Com.p(blob, BLOB_GET_SIZE)];
        MemorySegment.copy(Com.RAW, JAVA_BYTE, Com.p(blob, BLOB_GET_POINTER), b, 0, b.length);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
    }
}
