package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.compat.GlFunctions;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** What the Khronos conformance suite, built by glemu/scripts/cts/build_cts.py, calls from inside this JVM. */
final class GlCtsBridge {

    private GlCtsBridge() {}

    static native int run(String[] args);

    static long address(String name) {
        final ByteBuffer text = MemoryUtil.memASCII(name);
        try {
            return GlFunctions.INSTANCE.getFunctionAddress(text);
        } finally {
            MemoryUtil.memFree(text);
        }
    }

    /** Native code cannot be interrupted, so a case that stops making progress ends the process and the runner carries on past it. */
    static void watchForHangs(long seconds) {
        lastProgress = System.nanoTime();
        final Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.nanoTime() - lastProgress > seconds * 1_000_000_000L) {
                    System.out.println("cts: hung");
                    System.out.flush();
                    Runtime.getRuntime().halt(3);
                }
            }
        }, "conformance hang watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static volatile long lastProgress;

    static void frameDone() {
        lastProgress = System.nanoTime();
        GlsmHeadlessRig.endFrame();
        GlsmHeadlessRig.beginFrame();
    }
}
