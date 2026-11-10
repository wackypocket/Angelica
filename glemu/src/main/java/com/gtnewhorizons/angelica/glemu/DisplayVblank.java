package com.gtnewhorizons.angelica.glemu;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntSupplier;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.MacOSXLibraryDL;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.sdl.SDLInit.SDL_IsMainThread;
import static org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER;
import static org.lwjgl.system.APIUtil.apiClosureRet;
import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.JNI.invokeD;
import static org.lwjgl.system.JNI.invokeI;
import static org.lwjgl.system.JNI.invokeJ;
import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.JNI.invokePI;
import static org.lwjgl.system.JNI.invokePPI;
import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPI;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.JNI.invokePV;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.Pointer.POINTER_SIZE;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_sint32;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_uint64;

/** Holds frames to the display's vblanks on macOS, predicted from the timing a CVDisplayLink reports. */
public final class DisplayVblank implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("Angelica/GPU");

    private static final long SPIN_NANOS = 500_000L;
    private static final long RETARGET_POLL_NANOS = 500_000_000L;
    private static final int SLOT_MASK = 3;

    private static final int TS_TIME_SCALE = 4, TS_HOST_TIME = 16, TS_REFRESH_PERIOD = 32;

    private static final class CV {
        static final SharedLibrary CORE_VIDEO = new MacOSXLibraryDL("/System/Library/Frameworks/CoreVideo.framework/CoreVideo");
        static final SharedLibrary CORE_GRAPHICS = new MacOSXLibraryDL("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
        static final SharedLibrary OBJC = ObjCRuntime.getLibrary();

        static final long CREATE = fn(CORE_VIDEO, "CVDisplayLinkCreateWithCGDisplay");
        static final long SET_DISPLAY = fn(CORE_VIDEO, "CVDisplayLinkSetCurrentCGDisplay");
        static final long SET_CALLBACK = fn(CORE_VIDEO, "CVDisplayLinkSetOutputCallback");
        static final long START = fn(CORE_VIDEO, "CVDisplayLinkStart");
        static final long STOP = fn(CORE_VIDEO, "CVDisplayLinkStop");
        static final long RELEASE = fn(CORE_VIDEO, "CVDisplayLinkRelease");
        static final long HOST_TIME = fn(CORE_VIDEO, "CVGetCurrentHostTime");
        static final double HOST_HZ = invokeD(fn(CORE_VIDEO, "CVGetHostClockFrequency"));
        static final long MAIN_DISPLAY = fn(CORE_GRAPHICS, "CGMainDisplayID");

        static final long MSG_SEND = fn(OBJC, "objc_msgSend");
        static final long POOL_PUSH = fn(OBJC, "objc_autoreleasePoolPush");
        static final long POOL_POP = fn(OBJC, "objc_autoreleasePoolPop");
        static final ByteBuffer SCREEN_NUMBER = memASCII("NSScreenNumber");

        private static long fn(SharedLibrary lib, String name) {
            final long address = lib.getFunctionAddress(name);
            if (address == 0L) throw new UnsatisfiedLinkError(name + " not found in " + lib.getName());
            return address;
        }
    }

    @FunctionalInterface
    interface OutputCallbackI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(OutputCallbackI.class, MethodHandles.lookup(),
            apiCreateCIF(ffi_type_sint32, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_uint64, ffi_type_pointer, ffi_type_pointer));

        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }

        @Override default void callback(long ret, long args) {
            final int status = invoke(memGetAddress(memGetAddress(args)), memGetAddress(memGetAddress(args + POINTER_SIZE)),
                memGetAddress(memGetAddress(args + 2L * POINTER_SIZE)), memGetLong(memGetAddress(args + 3L * POINTER_SIZE)),
                memGetAddress(memGetAddress(args + 4L * POINTER_SIZE)), memGetAddress(memGetAddress(args + 5L * POINTER_SIZE)));
            apiClosureRet(ret, status);
        }

        int invoke(long displayLink, long inNow, long inOutputTime, long flagsIn, long flagsOut, long context);
    }

    private record Grid(long anchorNanos, long periodNanos) {}

    private final long window;
    private final long link;
    private final long callback;
    private volatile Grid grid;
    private final long[] slots = new long[SLOT_MASK + 1];
    private long frames;
    private long lastSubmittedNanos;
    private int sdlDisplay;
    private long retargetPollNanos;

    private DisplayVblank(long window, long link, int sdlDisplay) {
        this.window = window;
        this.link = link;
        this.sdlDisplay = sdlDisplay;
        this.callback = ((OutputCallbackI) (displayLink, inNow, inOutputTime, flagsIn, flagsOut, context) -> {
            onOutput(inOutputTime);
            return 0;
        }).address();
    }

    public static DisplayVblank create(long window) {
        if (Platform.get() != Platform.MACOSX || window == 0L) return null;
        try {
            final int cgDisplay = cgDisplayOf(window);
            final long link;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final LongBuffer out = stack.mallocLong(1);
                final int status = invokePI(cgDisplay, memAddress(out), CV.CREATE);
                if (status != 0 || out.get(0) == 0L) {
                    LOG.warn("CVDisplayLinkCreateWithCGDisplay({}) failed: {}", cgDisplay, status);
                    return null;
                }
                link = out.get(0);
            }
            final DisplayVblank vblank;
            try {
                vblank = new DisplayVblank(window, link, SDL_GetDisplayForWindow(window));
            } catch (Throwable t) {
                invokePV(link, CV.RELEASE);
                throw t;
            }
            final int set = invokePPPI(link, vblank.callback, 0L, CV.SET_CALLBACK);
            final int started = set == 0 ? invokePI(link, CV.START) : set;
            if (started != 0) {
                LOG.warn("Display link on display {} did not start: {}", cgDisplay, started);
                vblank.close();
                return null;
            }
            LOG.info("Holding frames to the vblank of display {} through a display link", cgDisplay);
            return vblank;
        } catch (Throwable t) {
            LOG.warn("No display link; vsync falls back to the present path's own blocking", t);
            return null;
        }
    }

    private void onOutput(long outputTime) {
        final int scale = memGetInt(outputTime + TS_TIME_SCALE);
        final long period = memGetLong(outputTime + TS_REFRESH_PERIOD);
        final long periodNanos = scale > 0 && period > 0L ? Math.round(period * 1e9 / scale) : 0L;
        if (periodNanos == 0L) return;
        final long hostNow = invokeJ(CV.HOST_TIME);
        final long nanoNow = System.nanoTime();
        final long anchor = nanoNow + Math.round((memGetLong(outputTime + TS_HOST_TIME) - hostNow) * 1e9 / CV.HOST_HZ);
        grid = new Grid(anchor, periodNanos);
    }

    /**
     * Blocks until the vblank that shows the frame {@code framesBehind} frames ago, returning the nanoseconds waited. A new frame
     * takes the first vblank a full period after the previous frame's, so two frames never share one.
     */
    public long awaitPresented(long submittedNanos, int framesBehind) {
        final Grid g = grid;
        if (g == null || submittedNanos == 0L) return 0L;
        if (submittedNanos != lastSubmittedNanos) {
            lastSubmittedNanos = submittedNanos;
            final long after = frames > 0L ? slots[(int) ((frames - 1L) & SLOT_MASK)] + g.periodNanos / 2L : submittedNanos;
            slots[(int) (frames & SLOT_MASK)] = nextVblank(g, after - submittedNanos > 0L ? after : submittedNanos);
            frames++;
        }
        final int behind = Math.min(Math.max(framesBehind, 0), SLOT_MASK);
        if (frames <= behind) return 0L;
        final long deadline = slots[(int) ((frames - 1L - behind) & SLOT_MASK)];
        final long start = System.nanoTime();
        // A deadline further off than the queue can explain means the display timing moved, so the history restarts.
        if (deadline - start > (behind + 2L) * g.periodNanos) {
            frames = 0L;
            return 0L;
        }
        long now = start;
        while (deadline - now > SPIN_NANOS) {
            LockSupport.parkNanos(deadline - now - SPIN_NANOS);
            now = System.nanoTime();
        }
        while (deadline - now > 0L) {
            Thread.onSpinWait();
            now = System.nanoTime();
        }
        return now - start;
    }

    private static long nextVblank(Grid g, long afterNanos) {
        return g.anchorNanos + (Math.floorDiv(afterNanos - g.anchorNanos, g.periodNanos) + 1L) * g.periodNanos;
    }

    /** Follows the window to another display. Takes the main thread, so callers must not hold a lock the main thread can wait on. */
    public void retarget() {
        final long now = System.nanoTime();
        if (now - retargetPollNanos < RETARGET_POLL_NANOS) return;
        retargetPollNanos = now;
        final int display = SDL_GetDisplayForWindow(window);
        if (display == 0 || display == sdlDisplay) return;
        sdlDisplay = display;
        final int cgDisplay = cgDisplayOf(window);
        final int status = invokePI(link, cgDisplay, CV.SET_DISPLAY);
        if (status != 0) LOG.warn("CVDisplayLinkSetCurrentCGDisplay({}) failed: {}", cgDisplay, status);
    }

    private static int onMainThread(IntSupplier task) {
        return SDL_IsMainThread() ? task.getAsInt() : MainThreadExec.runOnMainThread(task);
    }

    private static int cgDisplayOf(long window) {
        final int id = onMainThread(() -> {
            final long nsWindow = SDL_GetPointerProperty(SDL_GetWindowProperties(window), SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
            if (nsWindow == 0L) return 0;
            final long pool = invokeP(CV.POOL_PUSH);
            try {
                final long screen = invokePPP(nsWindow, ObjCRuntime.sel_getUid("screen"), CV.MSG_SEND);
                if (screen == 0L) return 0;
                final long description = invokePPP(screen, ObjCRuntime.sel_getUid("deviceDescription"), CV.MSG_SEND);
                final long key = invokePPPP(ObjCRuntime.objc_getClass("NSString"), ObjCRuntime.sel_getUid("stringWithUTF8String:"), memAddress(CV.SCREEN_NUMBER), CV.MSG_SEND);
                final long number = description == 0L ? 0L : invokePPPP(description, ObjCRuntime.sel_getUid("objectForKey:"), key, CV.MSG_SEND);
                return number == 0L ? 0 : invokePPI(number, ObjCRuntime.sel_getUid("unsignedIntValue"), CV.MSG_SEND);
            } finally {
                invokePV(pool, CV.POOL_POP);
            }
        });
        return id != 0 ? id : invokeI(CV.MAIN_DISPLAY);
    }

    @Override public void close() {
        invokePI(link, CV.STOP);
        invokePV(link, CV.RELEASE);
        Callback.free(callback);
        grid = null;
    }
}
