package com.gtnewhorizons.angelica.rendering.lighting;

import com.gtnewhorizons.angelica.config.AngelicaConfig;

/**
 * How long the client may spend on {@code Chunk.enqueueRelightChecks} in one tick. Each chunk keeps its own place in
 * the checks, so whatever a tick leaves undone is picked up on a later one.
 */
public final class RelightBudget {

    private static long deadline = Long.MAX_VALUE;

    private RelightBudget() {}

    /** Called as the client world starts its tick, on the client thread. */
    public static void beginTick(long nowNanos) {
        final int micros = AngelicaConfig.clientRelightBudgetMicros;
        deadline = micros <= 0 ? Long.MAX_VALUE : nowNanos + micros * 1000L;
    }

    public static boolean exhausted(long nowNanos) {
        return nowNanos - deadline > 0;
    }
}
