package com.gtnewhorizons.angelica.dx12.device;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Com {
    private Com() {}

    public static final Linker LINKER = Linker.nativeLinker();
    public static final MemorySegment RAW = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private static final MethodHandle CLEAR_DSV = LINKER.downcallHandle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_BYTE, JAVA_INT, JAVA_LONG), Linker.Option.critical(false));

    public static void clearDsv(long list, long dsv, int flags, float depth, int stencil) {
        clearDsv(list, dsv, flags, depth, stencil, 0, 0L);
    }

    public static void clearDsv(long list, long dsv, int flags, float depth, int stencil, int rectCount, long rects) {
        try {
            CLEAR_DSV.invokeExact(MemorySegment.ofAddress(fn(list, Dx.CL_CLEAR_DSV)), list, dsv, flags, depth, (byte) stencil, rectCount, rects);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static final int MAX_ARGS = 10;
    private static final MethodHandle[] HR = new MethodHandle[MAX_ARGS + 1], V = new MethodHandle[MAX_ARGS + 1], P = new MethodHandle[MAX_ARGS + 1];

    private static final MethodHandle EXECUTE = LINKER.downcallHandle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG));

    static {
        for (int n = 0; n <= MAX_ARGS; n++) {
            final var args = new java.lang.foreign.MemoryLayout[n + 1];
            java.util.Arrays.fill(args, JAVA_LONG);
            HR[n] = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, args));
            V[n] = LINKER.downcallHandle(FunctionDescriptor.ofVoid(args), Linker.Option.critical(false));
            P[n] = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_LONG, args));
        }
    }

    public static void execute(long queue, int count, long lists) {
        try {
            EXECUTE.invokeExact(MemorySegment.ofAddress(fn(queue, Dx.Q_EXECUTE)), queue, (long) count, lists);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

public static SymbolLookup lookup(String dll) {
        return SymbolLookup.libraryLookup(dll, Arena.global());
    }

    public static MethodHandle downcall(SymbolLookup lib, String symbol, FunctionDescriptor d) {
        return LINKER.downcallHandle(lib.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(symbol)), d);
    }

    public static long fn(long obj, int slot) {
        return RAW.get(JAVA_LONG, RAW.get(JAVA_LONG, obj) + slot * 8L);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    public static long addRef(long obj) {
        return p(obj, 1) & 0xFFFFFFFFL;
    }

    public static long release(long obj) {
        return obj == 0L ? 0L : p(obj, 2) & 0xFFFFFFFFL;
    }

    public static void check(int hr, String what) {
        if (hr < 0) throw new Dx12Exception(hr, what);
    }

    public static boolean ok(int hr) {
        return hr >= 0;
    }

    public static int hr(long obj, int slot) {
        try {
            return (int) HR[0].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot) {
        try {
            V[0].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot) {
        try {
            return (long) P[0].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1) {
        try {
            return (int) HR[1].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1) {
        try {
            V[1].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1) {
        try {
            return (long) P[1].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2) {
        try {
            return (int) HR[2].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2) {
        try {
            V[2].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2) {
        try {
            return (long) P[2].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3) {
        try {
            return (int) HR[3].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3) {
        try {
            V[3].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3) {
        try {
            return (long) P[3].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4) {
        try {
            return (int) HR[4].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4) {
        try {
            V[4].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4) {
        try {
            return (long) P[4].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4, long a5) {
        try {
            return (int) HR[5].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4, long a5) {
        try {
            V[5].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4, long a5) {
        try {
            return (long) P[5].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6) {
        try {
            return (int) HR[6].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6) {
        try {
            V[6].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6) {
        try {
            return (long) P[6].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7) {
        try {
            return (int) HR[7].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7) {
        try {
            V[7].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7) {
        try {
            return (long) P[7].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8) {
        try {
            return (int) HR[8].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8) {
        try {
            V[8].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8) {
        try {
            return (long) P[8].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9) {
        try {
            return (int) HR[9].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8, a9);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9) {
        try {
            V[9].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8, a9);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9) {
        try {
            return (long) P[9].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8, a9);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int hr(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9, long a10) {
        try {
            return (int) HR[10].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void v(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9, long a10) {
        try {
            V[10].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long p(long obj, int slot, long a1, long a2, long a3, long a4, long a5, long a6, long a7, long a8, long a9, long a10) {
        try {
            return (long) P[10].invokeExact(MemorySegment.ofAddress(fn(obj, slot)), obj, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}

