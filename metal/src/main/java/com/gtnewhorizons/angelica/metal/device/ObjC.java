package com.gtnewhorizons.angelica.metal.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.macosx.MacOSXLibraryDL;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetDouble;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutDouble;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.system.MemoryUtil.nmemCallocChecked;
import static org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI;
import static org.lwjgl.system.libffi.LibFFI.FFI_OK;
import static org.lwjgl.system.libffi.LibFFI.FFI_TYPE_STRUCT;
import static org.lwjgl.system.libffi.LibFFI.ffi_prep_cif;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_double;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_float;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_uint64;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_uint8;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;
import static org.lwjgl.system.libffi.LibFFI.nffi_call;

// Objective-C bridge on LWJGL's JNI entry points and libffi, so the backend runs on every Java version lwjgl3ify supports.
// Messages whose operands are all pointer-sized go through the direct JNI call shapes; the few with struct, float or
// double operands go through libffi, which handles the by-value struct ABI on both arm64 and x86_64.
public final class ObjC {
    private ObjC() {}

    private static final SharedLibrary OBJC = ObjCRuntime.getLibrary();
    private static final SharedLibrary METAL = new MacOSXLibraryDL("/System/Library/Frameworks/Metal.framework/Metal");

    private static final long MSG_SEND = fn(OBJC, "objc_msgSend");
    private static final long POOL_PUSH = fn(OBJC, "objc_autoreleasePoolPush");
    private static final long POOL_POP = fn(OBJC, "objc_autoreleasePoolPop");
    private static final long CREATE_DEVICE = fn(METAL, "MTLCreateSystemDefaultDevice");

    private static long fn(SharedLibrary lib, String name) {
        final long address = lib.getFunctionAddress(name);
        if (address == 0L) throw new UnsatisfiedLinkError(name + " not found in " + lib.getName());
        return address;
    }

    private static final ConcurrentHashMap<String, Long> SELECTORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> CLASSES = new ConcurrentHashMap<>();

    private static final FFIType T_VIEWPORT = struct(ffi_type_double, 6), T_CLEAR_COLOR = struct(ffi_type_double, 4), T_SIZE2D = struct(ffi_type_double, 2);
    private static final FFIType T_RECT = struct(ffi_type_uint64, 4), T_TRIPLE = struct(ffi_type_uint64, 3), T_RANGE = struct(ffi_type_uint64, 2);
    private static final FFIType T_SWIZZLE = struct(ffi_type_uint8, 4);

    private static FFIType struct(FFIType element, int count) {
        final PointerBuffer elements = memAllocPointer(count + 1); // libffi wants a NULL-terminated element list
        for (int i = 0; i < count; i++) elements.put(i, element.address());
        elements.put(count, 0L);
        return FFIType.malloc().size(0).alignment((short) 0).type(FFI_TYPE_STRUCT).elements(elements); // size and alignment are filled by ffi_prep_cif
    }

    private static FFICIF cif(FFIType ret, FFIType... args) {
        final PointerBuffer types = memAllocPointer(args.length);
        for (int i = 0; i < args.length; i++) types.put(i, args[i].address());
        final FFICIF cif = FFICIF.malloc();
        final int status = ffi_prep_cif(cif, FFI_DEFAULT_ABI, ret, types);
        if (status != FFI_OK) throw new IllegalStateException("ffi_prep_cif failed: " + status);
        return cif;
    }

    private static final FFIType P = ffi_type_pointer, D = ffi_type_double, F = ffi_type_float, V = ffi_type_void;
    private static final FFICIF CIF_D0 = cif(D, P, P);
    private static final FFICIF CIF_CD = cif(V, P, P, D);
    private static final FFICIF CIF_CF = cif(V, P, P, F);
    private static final FFICIF CIF_CFFF = cif(V, P, P, F, F, F);
    private static final FFICIF CIF_CFFFF = cif(V, P, P, F, F, F, F);
    private static final FFICIF CIF_VIEWPORT = cif(V, P, P, T_VIEWPORT);
    private static final FFICIF CIF_RECT = cif(V, P, P, T_RECT);
    private static final FFICIF CIF_CLEAR = cif(V, P, P, T_CLEAR_COLOR);
    private static final FFICIF CIF_SIZE2D = cif(V, P, P, T_SIZE2D);
    private static final FFICIF CIF_DISPATCH = cif(V, P, P, T_TRIPLE, T_TRIPLE);
    private static final FFICIF CIF_DISPATCH_INDIRECT = cif(V, P, P, P, P, T_TRIPLE);
    private static final FFICIF CIF_BUFFER_TO_TEXTURE = cif(V, P, P, P, P, P, P, T_TRIPLE, P, P, P, T_TRIPLE, P);
    private static final FFICIF CIF_TEXTURE_TO_BUFFER = cif(V, P, P, P, P, P, T_TRIPLE, T_TRIPLE, P, P, P, P, P);
    private static final FFICIF CIF_TEXTURE_TO_TEXTURE = cif(V, P, P, P, P, P, T_TRIPLE, T_TRIPLE, P, P, P, T_TRIPLE);
    private static final FFICIF CIF_FILL = cif(V, P, P, P, T_RANGE, P);
    private static final FFICIF CIF_VIEW = cif(P, P, P, P, P, T_RANGE, T_RANGE, T_SWIZZLE);
    private static final FFICIF CIF_VIEW_PLAIN = cif(P, P, P, P, P, T_RANGE, T_RANGE);

    // Per-thread operand storage for libffi: each operand gets an 8-byte slot (or a struct buffer) and a pointer to it.
    // Allocated once per thread and kept for the life of the process; only the client, presenter and test threads use the bridge.
    private static final class Scratch {
        private static final int SLOTS = 16;
        final long args = nmemCallocChecked(SLOTS, 8);
        final long avalues = nmemCallocChecked(SLOTS, 8);
        final long ret = nmemCallocChecked(1, 8);
        final long viewport = nmemCallocChecked(1, 48), rect = nmemCallocChecked(1, 32), clear = nmemCallocChecked(1, 32);
        final long size2d = nmemCallocChecked(1, 16), swizzle = nmemCallocChecked(1, 8);
        final long tripleA = nmemCallocChecked(1, 24), tripleB = nmemCallocChecked(1, 24), tripleC = nmemCallocChecked(1, 24);
        final long rangeA = nmemCallocChecked(1, 16), rangeB = nmemCallocChecked(1, 16);
        private int n;

        Scratch arg(long v) {
            memPutLong(args + 8L * n, v);
            return slot(args + 8L * n);
        }

        Scratch arg(double v) {
            memPutDouble(args + 8L * n, v);
            return slot(args + 8L * n);
        }

        Scratch arg(float v) {
            memPutFloat(args + 8L * n, v);
            return slot(args + 8L * n);
        }

        Scratch struct(long address) {
            return slot(address);
        }

        private Scratch slot(long address) {
            memPutAddress(avalues + 8L * n, address);
            n++;
            return this;
        }

        void call(FFICIF cif) {
            n = 0;
            nffi_call(cif.address(), MSG_SEND, ret, avalues);
        }

        long triple(long m, long a, long b, long c) {
            memPutLong(m, a);
            memPutLong(m + 8, b);
            memPutLong(m + 16, c);
            return m;
        }

        long range(long m, long location, long length) {
            memPutLong(m, location);
            memPutLong(m + 8, length);
            return m;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    public static long sel(String name) {
        final Long cached = SELECTORS.get(name);
        if (cached != null) return cached;
        final long s = ObjCRuntime.sel_getUid(name);
        SELECTORS.put(name, s);
        return s;
    }

    public static long cls(String name) {
        final Long cached = CLASSES.get(name);
        if (cached != null) return cached;
        final long c = ObjCRuntime.objc_getClass(name);
        if (c == 0L) throw new IllegalStateException("Objective-C class " + name + " is not loaded");
        CLASSES.put(name, c);
        return c;
    }

    public static long createSystemDefaultDevice() {
        return JNI.callP(CREATE_DEVICE);
    }

    public static long poolPush() {
        return JNI.callP(POOL_PUSH);
    }

    public static void poolPop(long pool) {
        JNI.callPV(pool, POOL_POP);
    }

    public static long send(long o, long s) {
        return JNI.callPPP(o, s, MSG_SEND);
    }

    public static long send(long o, long s, long a) {
        return JNI.callPPPP(o, s, a, MSG_SEND);
    }

    public static long send(long o, long s, long a, long b) {
        return JNI.callPPPPP(o, s, a, b, MSG_SEND);
    }

    public static long send(long o, long s, long a, long b, long c) {
        return JNI.invokePPPPPP(o, s, a, b, c, MSG_SEND);
    }

    public static long send(long o, long s, long a, long b, long c, long d) {
        return JNI.invokePPPPPPP(o, s, a, b, c, d, MSG_SEND);
    }

    public static long send(long o, long s, long a, long b, long c, long d, long e) {
        return JNI.callPPJPPPPP(o, s, a, b, c, d, e, MSG_SEND);
    }

    public static long send(long o, long s, long a, long b, long c, long d, long e, long f) {
        return JNI.invokePPPPPPPPP(o, s, a, b, c, d, e, f, MSG_SEND);
    }

    public static void call(long o, long s) {
        JNI.callPPV(o, s, MSG_SEND);
    }

    public static void call(long o, long s, long a) {
        JNI.callPPPV(o, s, a, MSG_SEND);
    }

    public static void call(long o, long s, long a, long b) {
        JNI.callPPPPV(o, s, a, b, MSG_SEND);
    }

    public static void call(long o, long s, long a, long b, long c) {
        JNI.invokePPPPPV(o, s, a, b, c, MSG_SEND);
    }

    public static void call(long o, long s, long a, long b, long c, long d) {
        JNI.invokePPPPPPV(o, s, a, b, c, d, MSG_SEND);
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e) {
        JNI.invokePPPPPPPV(o, s, a, b, c, d, e, MSG_SEND);
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e, long f) {
        JNI.invokePPPPPPPPV(o, s, a, b, c, d, e, f, MSG_SEND);
    }

    public static void call(long o, long s, long a, long b, long c, long d, long e, long f, long g, long h) {
        JNI.invokePPPPPPPPPPP(o, s, a, b, c, d, e, f, g, h, MSG_SEND); // the only ten-operand shape LWJGL has returns a pointer, which a void message leaves as garbage
    }

    // Messages that may block or run arbitrary code (commit, waitUntilCompleted, a release that deallocs) take LWJGL's
    // invoke shapes, which never run as JNI critical natives and so never hold off a GC safepoint for the length of a GPU wait.
    public static void callBlocking(long o, long s) {
        JNI.invokePPV(o, s, MSG_SEND);
    }

    public static void callBlocking(long o, long s, long a) {
        JNI.invokePPPV(o, s, a, MSG_SEND);
    }

    public static boolean sendZ(long o, long s) {
        return (JNI.callPPI(o, s, MSG_SEND) & 0xFF) != 0; // BOOL only defines the low byte of the return register
    }

    public static boolean sendZ(long o, long s, long a) {
        return (JNI.callPPPI(o, s, a, MSG_SEND) & 0xFF) != 0;
    }

    public static double sendD(long o, long s) {
        final Scratch sc = SCRATCH.get();
        sc.arg(o).arg(s).call(CIF_D0);
        return memGetDouble(sc.ret);
    }

    public static void callD(long o, long s, double a) {
        SCRATCH.get().arg(o).arg(s).arg(a).call(CIF_CD);
    }

    public static void callF(long o, long s, float a) {
        SCRATCH.get().arg(o).arg(s).arg(a).call(CIF_CF);
    }

    public static void callF(long o, long s, float a, float b, float c) {
        SCRATCH.get().arg(o).arg(s).arg(a).arg(b).arg(c).call(CIF_CFFF);
    }

    public static void callF(long o, long s, float a, float b, float c, float d) {
        SCRATCH.get().arg(o).arg(s).arg(a).arg(b).arg(c).arg(d).call(CIF_CFFFF);
    }

    public static void callViewport(long o, long s, double x, double y, double w, double h, double near, double far) {
        final Scratch sc = SCRATCH.get();
        final long v = sc.viewport;
        memPutDouble(v, x);
        memPutDouble(v + 8, y);
        memPutDouble(v + 16, w);
        memPutDouble(v + 24, h);
        memPutDouble(v + 32, near);
        memPutDouble(v + 40, far);
        sc.arg(o).arg(s).struct(v).call(CIF_VIEWPORT);
    }

    public static void callRect(long o, long s, long x, long y, long w, long h) {
        final Scratch sc = SCRATCH.get();
        final long r = sc.rect;
        memPutLong(r, x);
        memPutLong(r + 8, y);
        memPutLong(r + 16, w);
        memPutLong(r + 24, h);
        sc.arg(o).arg(s).struct(r).call(CIF_RECT);
    }

    public static void callClearColor(long o, long s, double r, double g, double b, double a) {
        final Scratch sc = SCRATCH.get();
        final long c = sc.clear;
        memPutDouble(c, r);
        memPutDouble(c + 8, g);
        memPutDouble(c + 16, b);
        memPutDouble(c + 24, a);
        sc.arg(o).arg(s).struct(c).call(CIF_CLEAR);
    }

    public static void callSize2D(long o, long s, double w, double h) {
        final Scratch sc = SCRATCH.get();
        memPutDouble(sc.size2d, w);
        memPutDouble(sc.size2d + 8, h);
        sc.arg(o).arg(s).struct(sc.size2d).call(CIF_SIZE2D);
    }

    public static void dispatch(long encoder, long sel, long gx, long gy, long gz, long tx, long ty, long tz) {
        final Scratch sc = SCRATCH.get();
        sc.arg(encoder).arg(sel).struct(sc.triple(sc.tripleA, gx, gy, gz)).struct(sc.triple(sc.tripleB, tx, ty, tz)).call(CIF_DISPATCH);
    }

    public static void dispatchIndirect(long encoder, long sel, long buffer, long offset, long tx, long ty, long tz) {
        final Scratch sc = SCRATCH.get();
        sc.arg(encoder).arg(sel).arg(buffer).arg(offset).struct(sc.triple(sc.tripleA, tx, ty, tz)).call(CIF_DISPATCH_INDIRECT);
    }

    public static void copyBufferToTexture(long blit, long sel, long buffer, long offset, long bytesPerRow, long bytesPerImage, long w, long h, long d,
                                           long texture, long slice, long level, long x, long y, long z, long options) {
        final Scratch sc = SCRATCH.get();
        sc.arg(blit).arg(sel).arg(buffer).arg(offset).arg(bytesPerRow).arg(bytesPerImage).struct(sc.triple(sc.tripleA, w, h, d))
            .arg(texture).arg(slice).arg(level).struct(sc.triple(sc.tripleB, x, y, z)).arg(options).call(CIF_BUFFER_TO_TEXTURE);
    }

    public static void copyTextureToBuffer(long blit, long sel, long texture, long slice, long level, long x, long y, long z, long w, long h, long d,
                                           long buffer, long offset, long bytesPerRow, long bytesPerImage, long options) {
        final Scratch sc = SCRATCH.get();
        sc.arg(blit).arg(sel).arg(texture).arg(slice).arg(level).struct(sc.triple(sc.tripleA, x, y, z)).struct(sc.triple(sc.tripleB, w, h, d))
            .arg(buffer).arg(offset).arg(bytesPerRow).arg(bytesPerImage).arg(options).call(CIF_TEXTURE_TO_BUFFER);
    }

    public static void copyTextureToTexture(long blit, long sel, long src, long srcSlice, long srcLevel, long sx, long sy, long sz, long w, long h, long d,
                                            long dst, long dstSlice, long dstLevel, long dx, long dy, long dz) {
        final Scratch sc = SCRATCH.get();
        sc.arg(blit).arg(sel).arg(src).arg(srcSlice).arg(srcLevel).struct(sc.triple(sc.tripleA, sx, sy, sz)).struct(sc.triple(sc.tripleB, w, h, d))
            .arg(dst).arg(dstSlice).arg(dstLevel).struct(sc.triple(sc.tripleC, dx, dy, dz)).call(CIF_TEXTURE_TO_TEXTURE);
    }

    public static void fillBuffer(long blit, long sel, long buffer, long offset, long length, int value) {
        final Scratch sc = SCRATCH.get();
        sc.arg(blit).arg(sel).arg(buffer).struct(sc.range(sc.rangeA, offset, length)).arg((long) (value & 0xFF)).call(CIF_FILL);
    }

    public static long newTextureView(long texture, long sel, long pixelFormat, long textureType, long baseLevel, long levelCount, long baseSlice, long sliceCount, int[] swizzle) {
        final Scratch sc = SCRATCH.get();
        sc.arg(texture).arg(sel).arg(pixelFormat).arg(textureType).struct(sc.range(sc.rangeA, baseLevel, levelCount)).struct(sc.range(sc.rangeB, baseSlice, sliceCount));
        if (swizzle == null) {
            sc.call(CIF_VIEW_PLAIN);
        } else {
            for (int i = 0; i < 4; i++) memPutByte(sc.swizzle + i, (byte) swizzle[i]);
            sc.struct(sc.swizzle).call(CIF_VIEW);
        }
        return memGetAddress(sc.ret);
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
        final ByteBuffer utf8 = memUTF8(s); // heap-allocated: shader sources are far larger than the memory stack
        try {
            return send(send(cls("NSString"), ALLOC), INIT_UTF8, memAddress(utf8));
        } finally {
            memFree(utf8);
        }
    }

    public static String string(long nsString) {
        if (nsString == 0L) return null;
        final long p = send(nsString, UTF8);
        return p == 0L ? null : memUTF8(p);
    }

    public static String errorDescription(long nsError) {
        return nsError == 0L ? "unknown error" : string(send(nsError, DESCRIPTION));
    }
}
