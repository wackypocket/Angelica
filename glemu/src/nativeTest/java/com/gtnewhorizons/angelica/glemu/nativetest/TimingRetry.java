package com.gtnewhorizons.angelica.glemu.nativetest;

import org.opentest4j.AssertionFailedError;

/** Runs a test that measures real display timing again when it fails, since a busy machine can miss a deadline once. */
public final class TimingRetry {
    private static final int ATTEMPTS = 3;

    private TimingRetry() {}

    public static void run(Runnable test) {
        for (int attempt = 1; ; attempt++) {
            try {
                test.run();
                return;
            } catch (AssertionFailedError e) {
                if (attempt == ATTEMPTS) throw e;
            }
        }
    }
}
