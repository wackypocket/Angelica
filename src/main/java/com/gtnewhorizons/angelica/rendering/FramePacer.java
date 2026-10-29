package com.gtnewhorizons.angelica.rendering;

import com.gtnewhorizons.angelica.AngelicaMod;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend.GateSample;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.glsm.profiling.TracyBackend;

import java.util.function.LongConsumer;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class FramePacer {

    interface Backend {
        VSyncMode effectiveVSyncMode();
        long refreshPeriodNanos();
        boolean vsyncHonored();
        int displayGeneration();
        void awaitPresent();
        void awaitFrameSlot();
        void readGateSample(GateSample out);
        void pumpDisplayMessages();
        void parkNanos(long nanos);
        long nanoTime();
    }

    private static final class RenderBackendAdapter implements Backend {
        @Override public VSyncMode effectiveVSyncMode() { return RENDER_BACKEND.getEffectiveVSyncMode(); }
        @Override public long refreshPeriodNanos() { return RENDER_BACKEND.refreshPeriodNanos(); }
        @Override public boolean vsyncHonored() { return RENDER_BACKEND.vsyncHonored(); }
        @Override public int displayGeneration() { return RENDER_BACKEND.displayGeneration(); }
        @Override public void awaitPresent() { RENDER_BACKEND.awaitPresent(); }
        @Override public void awaitFrameSlot() { RENDER_BACKEND.awaitFrameSlot(); }
        @Override public void readGateSample(GateSample out) { RENDER_BACKEND.readGateSample(out); }
        @Override public void pumpDisplayMessages() { GLStateManager.pumpDisplayMessages(); }
        @Override public void parkNanos(long nanos) { RENDER_BACKEND.parkNanos(nanos); }
        @Override public long nanoTime() { return System.nanoTime(); }
    }

    enum PaceState {
        NONE,
        LOCKED,
        PROBING,
        TIMER,
        TIMER_FALLBACK
    }

    static PaceState paceState(VSyncMode mode, boolean honored, boolean locked, boolean probing) {
        if (mode != VSyncMode.ON) return PaceState.NONE;
        if (!honored) return PaceState.TIMER;
        if (locked) return PaceState.LOCKED;
        return probing ? PaceState.PROBING : PaceState.TIMER_FALLBACK;
    }

    private static final Tracy.ZoneId Z_PACER_WAIT = Tracy.zoneId("pacerBackpressure", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_PACER_BEGIN = Tracy.zoneId("pacerBegin", Tracy.COLOR_CLIENT);
    private static final Tracy.ZoneId Z_PACER_PRESENT_SLEEP = Tracy.zoneId("pacerPresentSleep", Tracy.COLOR_SWAP);


    private static Backend BACKEND = new RenderBackendAdapter();

    private static void parkNanos(long nanos) {
        BACKEND.parkNanos(nanos);
    }

    private static long nanoTime() {
        return BACKEND.nanoTime();
    }

    private static PacerSleeper sleeper = new PacerSleeper(FramePacer::nanoTime, FramePacer::parkNanos, true);
    static PacerCore core = new PacerCore(FramePacer::nanoTime, sleeper);
    private static final GateSample sample = new GateSample();

    private static boolean warnedUncapped;
    private static int lastDisplayGeneration;

    private static VSyncMode currentMode = VSyncMode.ON;
    private static PaceState currentState = PaceState.NONE;
    private static int currentRefreshHz;
    private static int currentCapHz;
    private static int currentEffectiveCapHz;

    private static VSyncMode loggedMode;
    private static int loggedRefreshHz;
    private static int loggedCapHz;
    private static int loggedEffectiveCapHz;
    private static PaceState loggedState;

    private FramePacer() {}

    public static void setIdleWork(LongConsumer work) {
        core.setIdleWork(work);
    }

    public static void invalidate() {
        core.invalidate();
    }

    public static boolean pacedLastFrame() {
        return core.paced();
    }

    public static void beginStats() {
        sleeper.resetStats();
    }

    public static String endStats() {
        final String summary = sleeper.summary(configLine());
        Tracy.message(summary);
        return summary;
    }

    private static String configLine(VSyncMode mode, int refreshHz, int capHz, int ceilingHz) {
        return "cfg=[" + mode + " refresh=" + refreshHz + "Hz cap=" + capHz + " ceiling=" + ceilingHz + "Hz]";
    }

    private static String configLine() {
        return configLine(currentMode, currentRefreshHz, currentCapHz, currentEffectiveCapHz);
    }

    public static long endFrame(int capHz, Runnable renderAheadWait) {
        final VSyncMode mode = BACKEND.effectiveVSyncMode();
        final int cap = Math.max(capHz, 0);
        final long refreshPeriodNanos = BACKEND.refreshPeriodNanos();
        final boolean honored = BACKEND.vsyncHonored();
        final int refreshHz = refreshHz(mode, cap, refreshPeriodNanos);

        core.setRefreshPeriodNanos(refreshPeriodNanos);
        core.setFifo(mode == VSyncMode.ON);
        core.setVsyncOn(mode == VSyncMode.ON && honored);
        core.setCap(cap);

        final int generation = BACKEND.displayGeneration();
        if (generation != lastDisplayGeneration) {
            lastDisplayGeneration = generation;
            core.invalidate();
        }

        if (mode == VSyncMode.ON && honored) {
            BACKEND.awaitPresent();
        }
        BACKEND.awaitFrameSlot();
        BACKEND.readGateSample(sample);
        core.onGateSample(sample.durationNanos, sample.endNanos);
        core.onGpuWait(sample.gpuWaitNanos);

        Tracy.beginZone(Z_PACER_WAIT);
        try {
            if (renderAheadWait != null) {
                final long waitStart = nanoTime();
                renderAheadWait.run();
                core.onGpuWait(nanoTime() - waitStart);
            }
        } finally {
            Tracy.endZone();
        }

        Tracy.beginZone(Z_PACER_BEGIN);
        try {
            core.beginFrame(nanoTime());
        } finally {
            Tracy.endZone();
        }

        BACKEND.pumpDisplayMessages();
        core.markFrameStart(nanoTime());

        final int effectiveCapHz = core.effectiveCapHz();
        final PaceState state = paceState(mode, honored, core.locked(), core.probing());

        currentMode = mode;
        currentState = state;
        currentRefreshHz = refreshHz;
        currentCapHz = cap;
        currentEffectiveCapHz = effectiveCapHz;

        maybeLog(mode, refreshHz, cap, effectiveCapHz, state);

        return core.lastPresentIntervalNanos();
    }

    public static void beforePresent() {
        final long firstClearBlockNanos = GLSMFramebuffer.takeFirstClearBlockNanos();
        Tracy.beginZone(Z_PACER_PRESENT_SLEEP);
        try {
            core.beforePresent(nanoTime(), firstClearBlockNanos);
        } finally {
            Tracy.endZone();
        }
    }


    private static void maybeLog(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, PaceState state) {
        if (mode == loggedMode && refreshHz == loggedRefreshHz && capHz == loggedCapHz
            && effectiveCapHz == loggedEffectiveCapHz && state == loggedState) {
            return;
        }
        loggedMode = mode;
        loggedRefreshHz = refreshHz;
        loggedCapHz = capHz;
        loggedEffectiveCapHz = effectiveCapHz;
        loggedState = state;

        final String line = pacingLine(mode, refreshHz, capHz, effectiveCapHz, state);
        final boolean fallback = state == PaceState.TIMER_FALLBACK;
        if (fallback) AngelicaMod.LOGGER.warn(line);
        else AngelicaMod.LOGGER.info(line);
        Tracy.message(line, fallback ? TracyBackend.SEVERITY_WARNING : TracyBackend.SEVERITY_INFO);
    }

    static String pacingLine(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, PaceState state) {
        final StringBuilder sb = new StringBuilder("Frame pacing: ");
        sb.append(switch (mode) {
            case OFF -> "vsync off";
            case MAILBOX -> "mailbox";
            case ON -> "vsync";
            case FIFO_LATEST_READY -> "fifo uncapped";
        });
        if (mode.tearFree() && refreshHz > 0) sb.append(' ').append(refreshHz).append("Hz");
        switch (state) {
            case LOCKED -> sb.append(", hardware paced");
            case PROBING -> sb.append(", probing");
            case TIMER -> sb.append(", paced by time");
            case TIMER_FALLBACK -> sb.append(", driver is not holding frames to vblank, falling back to time pacing");
            case NONE -> { }
        }
        if (capHz > 0) {
            sb.append(", cap ").append(capHz);
            if (effectiveCapHz > 0 && effectiveCapHz != capHz) sb.append(" -> ").append(effectiveCapHz);
        }
        return sb.toString();
    }

    private static int refreshHz(VSyncMode mode, int capHz, long refreshPeriodNanos) {
        final int hz = RenderBackend.hzFromPeriod(refreshPeriodNanos);
        if (hz == 0 && capHz == 0 && mode == VSyncMode.ON && !warnedUncapped) {
            warnedUncapped = true;
            AngelicaMod.LOGGER.warn("Display refresh rate is unknown, so the frame rate cannot be bounded if the driver ignores vsync. Set Max Framerate to bound it.");
        }
        return hz;
    }

    static String debugIndicator(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, PaceState state) {
        final int cap = Math.max(capHz, 0);
        final boolean showCap = cap > 0;
        if (!mode.tearFree() && !showCap) return null;

        final StringBuilder sb = new StringBuilder(" [");
        if (mode.tearFree()) {
            sb.append(switch (mode) {
                case MAILBOX -> "mailbox";
                case FIFO_LATEST_READY -> "fifo uncapped";
                default -> "vsync";
            });
            if (refreshHz > 0) sb.append(' ').append(refreshHz);
            switch (state) {
                case PROBING -> sb.append(", probing");
                case TIMER -> sb.append(", time");
                case TIMER_FALLBACK -> sb.append(", time fallback");
                case LOCKED, NONE -> { }
            }
            if (showCap) sb.append(", cap ").append(cap).append(" -> ").append(effectiveCapHz);
        } else {
            sb.append("cap ").append(cap);
        }
        return sb.append(']').toString();
    }

    public static long gateDurationNanos() { return sample.durationNanos; }

    public static long gpuWaitNanos() { return core.lastGpuWaitNanos(); }

    public static long slackNanos() { return core.slackNanos(); }

    public static long spinNanos() { return sleeper.lastFrameSpinNanos; }

    public static long marginNanos() { return core.marginNanos(); }

    public static long presentIntervalNanos() { return core.lastPresentIntervalNanos(); }

    public static long idleOvershootNanos() { return core.lastIdleOvershootNanos(); }

    public static int effectiveCapHz() { return core.effectiveCapHz(); }

    public static boolean locked() { return core.locked(); }

    public static boolean probing() { return core.probing(); }

    public static String debugIndicator() {
        return debugIndicator(currentMode, currentRefreshHz, currentCapHz, currentEffectiveCapHz, currentState);
    }
}
