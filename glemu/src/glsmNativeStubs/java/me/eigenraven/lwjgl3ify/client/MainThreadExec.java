package me.eigenraven.lwjgl3ify.client;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Test stand-in for lwjgl3ify's MainThreadExec: the GLSM rigs have no SDL main thread, so callbacks run inline. */
public final class MainThreadExec {
    private MainThreadExec() {}

    public static void runOnMainThread(Runnable r) { r.run(); }

    public static <R> R runOnMainThread(Callable<R> c) {
        try {
            return c.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean runOnMainThread(BooleanSupplier s) { return s.getAsBoolean(); }

    public static int runOnMainThread(IntSupplier s) { return s.getAsInt(); }
}
