package com.gtnewhorizons.angelica.dx12.device;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Fence {
    private static final SymbolLookup KERNEL32 = Com.lookup("kernel32.dll");
    private static final MethodHandle CREATE_EVENT = Com.downcall(KERNEL32, "CreateEventW", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle WAIT = Com.downcall(KERNEL32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle CLOSE = Com.downcall(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    private static final int INFINITE = -1, WAIT_OBJECT_0 = 0;

    private long fence;
    private long event;
    private long lastSignaled;

    public void create(long device) {
        try (Arena a = Arena.ofConfined()) {
            final var out = a.allocate(8);
            Com.check(Com.hr(device, Dx.DEV_CREATE_FENCE, 0L, 0, Dx.IID_FENCE, out.address()), "CreateFence");
            fence = out.get(JAVA_LONG, 0);
            event = (long) CREATE_EVENT.invokeExact(0L, 0, 0, 0L);
            if (event == 0L) throw new IllegalStateException("CreateEventW failed");
        } catch (Throwable t) {
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new IllegalStateException(t);
        }
    }

    public long handle() {
        return fence;
    }

    public long signal(long queue) {
        final long value = ++lastSignaled;
        Com.check(Com.hr(queue, Dx.Q_SIGNAL, fence, value), "Signal");
        return value;
    }

    public void recordSignal(long value) {
        if (value > lastSignaled) lastSignaled = value;
    }

    public long completed() {
        return Com.p(fence, Dx.FENCE_COMPLETED);
    }

    public boolean isDone(long value) {
        return completed() >= value;
    }

    public boolean await(long value, long timeoutNanos) {
        if (completed() >= value) return true;
        Com.check(Com.hr(fence, Dx.FENCE_SET_EVENT, value, event), "SetEventOnCompletion");
        final int ms = timeoutNanos < 0 ? INFINITE : (int) Math.min(Integer.MAX_VALUE - 1, (timeoutNanos + 999_999L) / 1_000_000L);
        try {
            return (int) WAIT.invokeExact(event, ms) == WAIT_OBJECT_0;
        } catch (Throwable t) {
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new IllegalStateException(t);
        }
    }

    public void await(long value) {
        await(value, -1L);
    }

    public void destroy() {
        try {
            if (event != 0L) {
                final int ignored = (int) CLOSE.invokeExact(event);
            }
        } catch (Throwable ignored) {
        }
        event = 0L;
        Com.release(fence);
        fence = 0L;
    }
}
