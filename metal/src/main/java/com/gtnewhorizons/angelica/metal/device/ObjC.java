package com.gtnewhorizons.angelica.metal.device;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class ObjC {
    private ObjC() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    private static final SymbolLookup METAL = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", Arena.global());
    @SuppressWarnings("unused")
    private static final SymbolLookup QUARTZ = SymbolLookup.libraryLookup("/System/Library/Frameworks/QuartzCore.framework/QuartzCore", Arena.global());
    @SuppressWarnings("unused")
    private static final SymbolLookup FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", Arena.global());

    private static final MemorySegment MSG_SEND = OBJC.find("objc_msgSend").orElseThrow();
    private static final MethodHandle GET_CLASS = LINKER.downcallHandle(OBJC.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ValueLayout.ADDRESS));
    private static final MethodHandle REGISTER_NAME = LINKER.downcallHandle(OBJC.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ValueLayout.ADDRESS));
    private static final MethodHandle POOL_PUSH = LINKER.downcallHandle(OBJC.find("objc_autoreleasePoolPush").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle POOL_POP = LINKER.downcallHandle(OBJC.find("objc_autoreleasePoolPop").orElseThrow(), FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle CREATE_DEVICE = LINKER.downcallHandle(METAL.find("MTLCreateSystemDefaultDevice").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));

    private static final ConcurrentHashMap<String, Long> SELECTORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> CLASSES = new ConcurrentHashMap<>();

    private static MethodHandle msg(FunctionDescriptor d) {
        return LINKER.downcallHandle(MSG_SEND, d);
    }

    private static MemoryLayout[] longs(int n) {
        final MemoryLayout[] l = new MemoryLayout[n];
        java.util.Arrays.fill(l, JAVA_LONG);
        return l;
    }

    private static final int MAX_ARGS = 10;
    private static final MethodHandle[] SEND = new MethodHandle[MAX_ARGS + 1];
    private static final MethodHandle[] CALL = new MethodHandle[MAX_ARGS + 1];
    static {
        for (int n = 0; n <= MAX_ARGS; n++) {
            SEND[n] = msg(FunctionDescriptor.of(JAVA_LONG, longs(n + 2)));
            CALL[n] = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.ofVoid(longs(n + 2)), Linker.Option.critical(false));
        }
    }
    private static final MethodHandle S0 = SEND[0], S1 = SEND[1], S2 = SEND[2], S3 = SEND[3], S4 = SEND[4], S5 = SEND[5], S6 = SEND[6];
    private static final MethodHandle C0 = CALL[0], C1 = CALL[1], C2 = CALL[2], C3 = CALL[3], C4 = CALL[4], C5 = CALL[5], C6 = CALL[6], C7 = CALL[7], C8 = CALL[8], C9 = CALL[9];
    private static final MethodHandle B0 = msg(FunctionDescriptor.ofVoid(longs(2))), B1 = msg(FunctionDescriptor.ofVoid(longs(3)));
    private static final MethodHandle Z0 = msg(FunctionDescriptor.of(JAVA_BYTE, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle Z1 = msg(FunctionDescriptor.of(JAVA_BYTE, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle D0 = msg(FunctionDescriptor.of(JAVA_DOUBLE, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle CD = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_DOUBLE));
    private static final MethodHandle CF = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT));
    private static final MethodHandle CFFF = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle CFFFF = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));

    public static final StructLayout VIEWPORT = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    public static final StructLayout CLEAR_COLOR = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    public static final StructLayout SIZE2D = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);
    public static final StructLayout RECT = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    public static final StructLayout TRIPLE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);
    public static final StructLayout RANGE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);
    public static final StructLayout SWIZZLE = MemoryLayout.structLayout(JAVA_BYTE, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE);

    private static final MethodHandle C_VIEWPORT = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, VIEWPORT));
    private static final MethodHandle C_RECT = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, RECT));
    private static final MethodHandle C_CLEAR = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, CLEAR_COLOR));
    private static final MethodHandle C_SIZE2D = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, SIZE2D));
    private static final MethodHandle C_DISPATCH = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, TRIPLE, TRIPLE));
    private static final MethodHandle C_DISPATCH_INDIRECT = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, TRIPLE));
    private static final MethodHandle C_BUFFER_TO_TEXTURE = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, TRIPLE, JAVA_LONG, JAVA_LONG, JAVA_LONG, TRIPLE, JAVA_LONG));
    private static final MethodHandle C_TEXTURE_TO_BUFFER = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, TRIPLE, TRIPLE, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle C_TEXTURE_TO_TEXTURE = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, TRIPLE, TRIPLE, JAVA_LONG, JAVA_LONG, JAVA_LONG, TRIPLE));
    private static final MethodHandle C_FILL = msg(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, RANGE, JAVA_LONG));
    private static final MethodHandle S_VIEW = msg(FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, RANGE, RANGE, SWIZZLE));
    private static final MethodHandle S_VIEW_PLAIN = msg(FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, RANGE, RANGE));

    private static final class Scratch {
        final Arena arena = Arena.ofAuto();
        final MemorySegment VIEWPORT_ARG = arena.allocate(VIEWPORT), RECT_ARG = arena.allocate(RECT), CLEAR_ARG = arena.allocate(CLEAR_COLOR);
        final MemorySegment SIZE2D_ARG = arena.allocate(SIZE2D), SWIZZLE_ARG = arena.allocate(SWIZZLE);
        final MemorySegment TRIPLE_A = arena.allocate(TRIPLE), TRIPLE_B = arena.allocate(TRIPLE), TRIPLE_C = arena.allocate(TRIPLE);
        final MemorySegment RANGE_A = arena.allocate(RANGE), RANGE_B = arena.allocate(RANGE);
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    public static long sel(String name) {
        final Long cached = SELECTORS.get(name);
        if (cached != null) return cached;
        try (Arena a = Arena.ofConfined()) {
            final long s = (long) REGISTER_NAME.invokeExact(a.allocateFrom(name));
            SELECTORS.put(name, s);
            return s;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long cls(String name) {
        final Long cached = CLASSES.get(name);
        if (cached != null) return cached;
        try (Arena a = Arena.ofConfined()) {
            final long c = (long) GET_CLASS.invokeExact(a.allocateFrom(name));
            if (c == 0L) throw new IllegalStateException("Objective-C class " + name + " is not loaded");
            CLASSES.put(name, c);
            return c;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long createSystemDefaultDevice() {
        try {
            return (long) CREATE_DEVICE.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long poolPush() {
        try {
            return (long) POOL_PUSH.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void poolPop(long pool) {
        try {
            POOL_POP.invokeExact(pool);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long send(long o, long s) {
        try { return (long) S0.invokeExact(o, s); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long send(long o, long s, long a) {
        try { return (long) S1.invokeExact(o, s, a); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long send(long o, long s, long a, long b) {
        try { return (long) S2.invokeExact(o, s, a, b); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long send(long o, long s, long a, long b, long c) {
        try { return (long) S3.invokeExact(o, s, a, b, c); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long send(long o, long s, long a, long b, long c, long d) {
        try { return (long) S4.invokeExact(o, s, a, b, c, d); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long send(long o, long s, long a, long b, long c, long d, long e) {
        try { return (long) S5.invokeExact(o, s, a, b, c, d, e); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long send(long o, long s, long a, long b, long c, long d, long e, long f) {
        try { return (long) S6.invokeExact(o, s, a, b, c, d, e, f); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s) {
        try { C0.invokeExact(o, s); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a) {
        try { C1.invokeExact(o, s, a); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b) {
        try { C2.invokeExact(o, s, a, b); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c) {
        try { C3.invokeExact(o, s, a, b, c); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c, long d) {
        try { C4.invokeExact(o, s, a, b, c, d); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e) {
        try { C5.invokeExact(o, s, a, b, c, d, e); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e, long f) {
        try { C6.invokeExact(o, s, a, b, c, d, e, f); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e, long f, long g) {
        try { C7.invokeExact(o, s, a, b, c, d, e, f, g); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e, long f, long g, long h) {
        try { C8.invokeExact(o, s, a, b, c, d, e, f, g, h); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e, long f, long g, long h, long i) {
        try { C9.invokeExact(o, s, a, b, c, d, e, f, g, h, i); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callBlocking(long o, long s) {
        try { B0.invokeExact(o, s); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callBlocking(long o, long s, long a) {
        try { B1.invokeExact(o, s, a); } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean sendZ(long o, long s) {
        try { return (byte) Z0.invokeExact(o, s) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean sendZ(long o, long s, long a) {
        try { return (byte) Z1.invokeExact(o, s, a) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static double sendD(long o, long s) {
        try { return (double) D0.invokeExact(o, s); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callD(long o, long s, double a) {
        try { CD.invokeExact(o, s, a); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callF(long o, long s, float a) {
        try { CF.invokeExact(o, s, a); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callF(long o, long s, float a, float b, float c) {
        try { CFFF.invokeExact(o, s, a, b, c); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callF(long o, long s, float a, float b, float c, float d) {
        try { CFFFF.invokeExact(o, s, a, b, c, d); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callViewport(long o, long s, double x, double y, double w, double h, double near, double far) {
        final Scratch sc = SCRATCH.get();
        final MemorySegment v = sc.VIEWPORT_ARG;
        v.set(JAVA_DOUBLE, 0, x);
        v.set(JAVA_DOUBLE, 8, y);
        v.set(JAVA_DOUBLE, 16, w);
        v.set(JAVA_DOUBLE, 24, h);
        v.set(JAVA_DOUBLE, 32, near);
        v.set(JAVA_DOUBLE, 40, far);
        try { C_VIEWPORT.invokeExact(o, s, v); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callRect(long o, long s, long x, long y, long w, long h) {
        final Scratch sc = SCRATCH.get();
        final MemorySegment r = sc.RECT_ARG;
        r.set(JAVA_LONG, 0, x);
        r.set(JAVA_LONG, 8, y);
        r.set(JAVA_LONG, 16, w);
        r.set(JAVA_LONG, 24, h);
        try { C_RECT.invokeExact(o, s, r); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callClearColor(long o, long s, double r, double g, double b, double a) {
        final Scratch sc = SCRATCH.get();
        final MemorySegment c = sc.CLEAR_ARG;
        c.set(JAVA_DOUBLE, 0, r);
        c.set(JAVA_DOUBLE, 8, g);
        c.set(JAVA_DOUBLE, 16, b);
        c.set(JAVA_DOUBLE, 24, a);
        try { C_CLEAR.invokeExact(o, s, c); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void callSize2D(long o, long s, double w, double h) {
        final Scratch sc = SCRATCH.get();
        sc.SIZE2D_ARG.set(JAVA_DOUBLE, 0, w);
        sc.SIZE2D_ARG.set(JAVA_DOUBLE, 8, h);
        try { C_SIZE2D.invokeExact(o, s, sc.SIZE2D_ARG); } catch (Throwable t) { throw rethrow(t); }
    }

    private static MemorySegment triple(MemorySegment m, long a, long b, long c) {
        m.set(JAVA_LONG, 0, a);
        m.set(JAVA_LONG, 8, b);
        m.set(JAVA_LONG, 16, c);
        return m;
    }

    private static MemorySegment range(MemorySegment m, long location, long length) {
        m.set(JAVA_LONG, 0, location);
        m.set(JAVA_LONG, 8, length);
        return m;
    }

    public static void dispatch(long encoder, long sel, long gx, long gy, long gz, long tx, long ty, long tz) {
        final Scratch sc = SCRATCH.get();
        try { C_DISPATCH.invokeExact(encoder, sel, triple(sc.TRIPLE_A, gx, gy, gz), triple(sc.TRIPLE_B, tx, ty, tz)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void dispatchIndirect(long encoder, long sel, long buffer, long offset, long tx, long ty, long tz) {
        final Scratch sc = SCRATCH.get();
        try { C_DISPATCH_INDIRECT.invokeExact(encoder, sel, buffer, offset, triple(sc.TRIPLE_A, tx, ty, tz)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyBufferToTexture(long blit, long sel, long buffer, long offset, long bytesPerRow, long bytesPerImage, long w, long h, long d,
                                           long texture, long slice, long level, long x, long y, long z, long options) {
        try {
            C_BUFFER_TO_TEXTURE.invokeExact(blit, sel, buffer, offset, bytesPerRow, bytesPerImage, triple(SCRATCH.get().TRIPLE_A, w, h, d), texture, slice, level, triple(SCRATCH.get().TRIPLE_B, x, y, z), options);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void copyTextureToBuffer(long blit, long sel, long texture, long slice, long level, long x, long y, long z, long w, long h, long d,
                                           long buffer, long offset, long bytesPerRow, long bytesPerImage, long options) {
        try {
            C_TEXTURE_TO_BUFFER.invokeExact(blit, sel, texture, slice, level, triple(SCRATCH.get().TRIPLE_A, x, y, z), triple(SCRATCH.get().TRIPLE_B, w, h, d), buffer, offset, bytesPerRow, bytesPerImage, options);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void copyTextureToTexture(long blit, long sel, long src, long srcSlice, long srcLevel, long sx, long sy, long sz, long w, long h, long d,
                                            long dst, long dstSlice, long dstLevel, long dx, long dy, long dz) {
        try {
            C_TEXTURE_TO_TEXTURE.invokeExact(blit, sel, src, srcSlice, srcLevel, triple(SCRATCH.get().TRIPLE_A, sx, sy, sz), triple(SCRATCH.get().TRIPLE_B, w, h, d), dst, dstSlice, dstLevel, triple(SCRATCH.get().TRIPLE_C, dx, dy, dz));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void fillBuffer(long blit, long sel, long buffer, long offset, long length, int value) {
        final Scratch sc = SCRATCH.get();
        try { C_FILL.invokeExact(blit, sel, buffer, range(sc.RANGE_A, offset, length), (long) (value & 0xFF)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long newTextureView(long texture, long sel, long pixelFormat, long textureType, long baseLevel, long levelCount, long baseSlice, long sliceCount, int[] swizzle) {
        final Scratch sc = SCRATCH.get();
        try {
            if (swizzle == null) {
                return (long) S_VIEW_PLAIN.invokeExact(texture, sel, pixelFormat, textureType, range(sc.RANGE_A, baseLevel, levelCount), range(sc.RANGE_B, baseSlice, sliceCount));
            }
            for (int i = 0; i < 4; i++) sc.SWIZZLE_ARG.set(JAVA_BYTE, i, (byte) swizzle[i]);
            return (long) S_VIEW.invokeExact(texture, sel, pixelFormat, textureType, range(sc.RANGE_A, baseLevel, levelCount), range(sc.RANGE_B, baseSlice, sliceCount), sc.SWIZZLE_ARG);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static final long ALLOC = sel("alloc"), INIT = sel("init"), RELEASE = sel("release"), RETAIN = sel("retain");
    private static final long INIT_UTF8 = sel("initWithUTF8String:"), UTF8 = sel("UTF8String"), DESCRIPTION = sel("localizedDescription");
    private static final long RESPONDS = sel("respondsToSelector:");

    public static long create(String className) {
        return send(send(cls(className), ALLOC), INIT);
    }

    public static void release(long o) {
        if (o != 0L) callBlocking(o, RELEASE);
    }

    public static long retain(long o) {
        if (o != 0L) send(o, RETAIN);
        return o;
    }

    public static boolean responds(long o, String selector) {
        return o != 0L && sendZ(o, RESPONDS, sel(selector));
    }

    public static long nsString(String s) {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment utf8 = a.allocateFrom(s, StandardCharsets.UTF_8);
            return send(send(cls("NSString"), ALLOC), INIT_UTF8, utf8.address());
        }
    }

    public static String string(long nsString) {
        if (nsString == 0L) return null;
        final long p = send(nsString, UTF8);
        return p == 0L ? null : MemorySegment.ofAddress(p).reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }

    public static String errorDescription(long nsError) {
        return nsError == 0L ? "unknown error" : string(send(nsError, DESCRIPTION));
    }
}
