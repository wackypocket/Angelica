package com.gtnewhorizons.angelica.rendering;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend.GateSample;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramePacerTest {

    private static final long CLOCK_TICK_NANOS = 1_000L;

    private static class FakeBackend implements FramePacer.Backend {
        VSyncMode mode = VSyncMode.ON;
        long refreshPeriodNanos;
        boolean vsyncHonored = true;
        int displayGeneration;
        long gpuWaitNanos;
        long gateDurationNanos;
        long gateEndNanos;
        long now;

        int awaitPresentCalls;
        int awaitFrameSlotCalls;
        int probeLeadPeriods = PacerCore.DEFAULT_PROBE_LEAD_PERIODS;
        int readGateSampleCalls;
        int parkCalls;
        int pumpCalls;
        boolean awaitedBeforeRead;
        boolean slotAwaitedBeforeRead;
        boolean readBeforePump;
        boolean parkedBeforePump;

        @Override
        public VSyncMode effectiveVSyncMode() {
            return mode;
        }

        @Override
        public long refreshPeriodNanos() {
            return refreshPeriodNanos;
        }

        @Override
        public boolean vsyncHonored() {
            return vsyncHonored;
        }

        @Override
        public int displayGeneration() {
            return displayGeneration;
        }

        @Override
        public void awaitPresent() {
            awaitPresentCalls++;
        }

        @Override
        public int probeLeadPeriods() {
            return probeLeadPeriods;
        }

        @Override
        public void awaitFrameSlot() {
            awaitFrameSlotCalls++;
        }

        @Override
        public void readGateSample(GateSample out) {
            readGateSampleCalls++;
            awaitedBeforeRead = awaitPresentCalls > 0;
            slotAwaitedBeforeRead = awaitFrameSlotCalls > 0;
            out.durationNanos = gateDurationNanos;
            out.endNanos = gateEndNanos;
            out.gpuWaitNanos = gpuWaitNanos;
        }

        @Override
        public void pumpDisplayMessages() {
            pumpCalls++;
            readBeforePump = readGateSampleCalls > 0;
            parkedBeforePump = parkCalls > 0;
        }

        @Override
        public void parkNanos(long nanos) {
            parkCalls++;
            now += nanos;
        }

        @Override
        public long nanoTime() {
            final long t = now;
            now += CLOCK_TICK_NANOS;
            return t;
        }
    }

    @BeforeEach
    void freshPacerState() {
        final PacerSleeper previous = Reflect.getStatic(FramePacer.class, "sleeper");
        final LongSupplier clock = Reflect.get(previous, "clock");
        final LongConsumer parker = Reflect.get(previous, "parker");
        final PacerSleeper sleeper = new PacerSleeper(clock, parker, true);
        Reflect.setStatic(FramePacer.class, "sleeper", sleeper);
        Reflect.setStatic(FramePacer.class, "core", new PacerCore(clock, sleeper));
        Reflect.setStatic(FramePacer.class, "lastDisplayGeneration", 0);
        Reflect.setStatic(FramePacer.class, "warnedUncapped", false);
        Reflect.setStatic(FramePacer.class, "loggedMode", null);
        Reflect.setStatic(FramePacer.class, "loggedRefreshHz", 0);
        Reflect.setStatic(FramePacer.class, "loggedCapHz", 0);
        Reflect.setStatic(FramePacer.class, "loggedEffectiveCapHz", 0);
        Reflect.setStatic(FramePacer.class, "loggedState", null);
    }

    private static void withBackend(FramePacer.Backend fake, Runnable body) {
        final FramePacer.Backend original = Reflect.getStatic(FramePacer.class, "BACKEND");
        Reflect.setStatic(FramePacer.class, "BACKEND", fake);
        try {
            body.run();
        } finally {
            Reflect.setStatic(FramePacer.class, "BACKEND", original);
        }
    }

    @ParameterizedTest(name = "{0} refresh={1} cap={2}->{3} state={4}")
    @CsvSource(nullValues = "null", value = {
        "ON, 60, 0, 0, LOCKED, ' [vsync 60]'",
        "ON, 60, 30, 30, LOCKED, ' [vsync 60, cap 30 -> 30]'",
        "ON, 144, 50, 48, LOCKED, ' [vsync 144, cap 50 -> 48]'",
        "MAILBOX, 144, 0, 0, NONE, ' [mailbox 144]'",
        "OFF, 144, 100, 100, NONE, ' [cap 100]'",
        "ON, 60, 0, 0, PROBING, ' [vsync 60, probing]'",
        "ON, 60, 0, 0, TIMER, ' [vsync 60, time]'",
        "ON, 60, 0, 0, TIMER_FALLBACK, ' [vsync 60, time fallback]'",
        "OFF, 144, 0, 0, NONE, null",
    })
    void theIndicatorNamesEveryPacingState(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, FramePacer.PaceState state, String expected) {
        assertEquals(expected, FramePacer.debugIndicator(mode, refreshHz, capHz, effectiveCapHz, state));
    }

    @ParameterizedTest(name = "{0} refresh={1} cap={2}->{3} state={4}")
    @CsvSource({
        "ON, 60, 0, 0, LOCKED, 'Frame pacing: vsync 60Hz, hardware paced'",
        "ON, 60, 0, 0, PROBING, 'Frame pacing: vsync 60Hz, probing'",
        "ON, 60, 0, 0, TIMER, 'Frame pacing: vsync 60Hz, paced by time'",
        "ON, 60, 0, 0, TIMER_FALLBACK, 'Frame pacing: vsync 60Hz, driver is not holding frames to vblank, falling back to time pacing'",
        "MAILBOX, 144, 0, 0, NONE, 'Frame pacing: mailbox 144Hz'",
        "OFF, 144, 100, 100, NONE, 'Frame pacing: vsync off, cap 100'",
        "ON, 60, 30, 30, LOCKED, 'Frame pacing: vsync 60Hz, hardware paced, cap 30'",
        "ON, 144, 50, 48, LOCKED, 'Frame pacing: vsync 144Hz, hardware paced, cap 50 -> 48'",
    })
    void thePacingLineNamesEveryPacingState(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, FramePacer.PaceState state, String expected) {
        assertEquals(expected, FramePacer.pacingLine(mode, refreshHz, capHz, effectiveCapHz, state));
    }

    @ParameterizedTest(name = "{0} honored={1} locked={2} probing={3} expectsDriver={4} -> {5}")
    @CsvSource({
        "ON, true, true, false, true, LOCKED",
        "ON, true, false, true, true, PROBING",
        "ON, true, false, false, true, TIMER_FALLBACK",
        "ON, true, false, false, false, TIMER",
        "ON, false, false, false, true, TIMER",
        "MAILBOX, true, false, false, false, NONE",
        "OFF, false, false, false, false, NONE",
    })
    void thePaceStateSeparatesADesignedTimerFromAFailedLock(VSyncMode mode, boolean honored, boolean locked, boolean probing, boolean expectsDriver, FramePacer.PaceState expected) {
        assertEquals(expected, FramePacer.paceState(mode, honored, locked, probing, expectsDriver));
    }

    private static FramePacer.PaceState currentState() {
        return Reflect.getStatic(FramePacer.class, "currentState");
    }

    @Test
    void aDividedCapUnderVsyncIsADesignedTimerNotAFallback() {
        final FakeBackend backend = new FakeBackend();
        backend.mode = VSyncMode.ON;
        backend.vsyncHonored = true;
        backend.refreshPeriodNanos = 16_666_666L;
        withBackend(backend, () -> {
            for (int i = 0; i < 60; i++) FramePacer.endFrame(30, null);
            assertFalse(FramePacer.core.probing(), "a cadence off the refresh period is never probed");
            assertFalse(FramePacer.core.locked());
            assertEquals(FramePacer.PaceState.TIMER, currentState(), "a cap of 30 on a 60Hz panel is paced by time on purpose");
            assertEquals(" [vsync 60, time, cap 30 -> 30]", FramePacer.debugIndicator());

            for (int i = 0; i < 60; i++) FramePacer.endFrame(0, null);
            assertEquals(FramePacer.PaceState.TIMER_FALLBACK, currentState(), "at the refresh rate a driver that never blocks is a failed lock");
        });
    }

    private static final class QueuedDriverBackend extends FakeBackend {
        final int depth;
        final long period;
        final ArrayDeque<Long> flips = new ArrayDeque<>();
        long lastFlip = Long.MIN_VALUE;
        int blockedFrames;

        QueuedDriverBackend(int depth, long period) {
            this.depth = depth;
            this.period = period;
            mode = VSyncMode.ON;
            vsyncHonored = true;
            refreshPeriodNanos = period;
        }

        @Override
        public void awaitFrameSlot() {
            awaitFrameSlotCalls++;
            final long presented = now;
            while (!flips.isEmpty() && flips.peekFirst() <= presented) flips.pollFirst();
            if (flips.size() >= depth) {
                final long freeAt = flips.pollFirst();
                now = freeAt;
                gateDurationNanos = freeAt - presented;
                gateEndNanos = freeAt;
                blockedFrames++;
            } else {
                gateDurationNanos = 0L;
                gateEndNanos = 0L;
            }
            final long vblank = Math.floorDiv(presented + period - 1, period) * period;
            final long flip = Math.max(vblank, lastFlip + period);
            flips.addLast(flip);
            lastFlip = flip;
        }
    }

    @ParameterizedTest(name = "present queue depth {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void theProbeReachesTheDriverBlockBehindAPresentQueue(int depth) {
        final QueuedDriverBackend backend = new QueuedDriverBackend(depth, 16_666_666L);
        if (depth > PacerCore.DEFAULT_PROBE_LEAD_PERIODS) backend.probeLeadPeriods = 6;
        withBackend(backend, () -> {
            final long work = 4_000_000L;
            boolean fellBack = false;
            int lockFrame = -1;
            for (int i = 0; i < 300; i++) {
                FramePacer.endFrame(0, null);
                if (currentState() == FramePacer.PaceState.TIMER_FALLBACK) fellBack = true;
                if (lockFrame < 0 && FramePacer.locked()) lockFrame = i;
                backend.now += work;
                backend.now = FramePacer.core.beforePresent(backend.now, 0L);
            }
            assertTrue(backend.blockedFrames > 0, "the probe must run far enough ahead to fill a " + depth + "-deep present queue");
            assertFalse(fellBack, "the pacer must not give up on driver pacing while the queue is still filling");
            assertTrue(lockFrame >= 0 && lockFrame <= 150, "must lock inside the probe window, locked at frame " + lockFrame);
            assertEquals(FramePacer.PaceState.LOCKED, currentState());
        });
    }

    @Test
    void settersAreAppliedBeforeBeginFrame() {
        final FakeBackend backend = new FakeBackend();
        backend.mode = VSyncMode.OFF;
        backend.vsyncHonored = false;
        withBackend(backend, () -> {
            FramePacer.endFrame(60, null);
            assertTrue(FramePacer.pacedLastFrame(), "a nonzero cap must pace even under vsync off");
        });
    }

    @Test
    void frameSlotIsAwaitedOnceBeforeTheGateSampleRegardlessOfVsync() {
        for (VSyncMode mode : VSyncMode.values()) {
            final FakeBackend backend = new FakeBackend();
            backend.mode = mode;
            withBackend(backend, () -> {
                FramePacer.endFrame(0, null);
                assertEquals(1, backend.awaitFrameSlotCalls, mode.name());
                assertTrue(backend.slotAwaitedBeforeRead, mode.name());
            });
        }
    }

    @Test
    void awaitPresentPrecedesReadGateSampleOnlyWhenVsyncOnAndHonored() {
        final FakeBackend honored = new FakeBackend();
        honored.mode = VSyncMode.ON;
        honored.vsyncHonored = true;
        withBackend(honored, () -> {
            FramePacer.endFrame(0, null);
            assertEquals(1, honored.awaitPresentCalls);
            assertTrue(honored.awaitedBeforeRead);
        });

        final FakeBackend notHonored = new FakeBackend();
        notHonored.mode = VSyncMode.ON;
        notHonored.vsyncHonored = false;
        withBackend(notHonored, () -> {
            FramePacer.endFrame(60, null);
            assertEquals(0, notHonored.awaitPresentCalls);
            assertEquals(1, notHonored.readGateSampleCalls);
        });

        final FakeBackend off = new FakeBackend();
        off.mode = VSyncMode.OFF;
        withBackend(off, () -> {
            FramePacer.endFrame(60, null);
            assertEquals(0, off.awaitPresentCalls);
            assertEquals(1, off.readGateSampleCalls);
        });
    }

    @Test
    void endFramePumpsExactlyOnceAfterTheGateReadAndThePacingSleep() {
        final FakeBackend backend = new FakeBackend();
        backend.mode = VSyncMode.OFF;
        withBackend(backend, () -> {
            FramePacer.endFrame(60, null);
            assertEquals(1, backend.pumpCalls);
            assertTrue(backend.readBeforePump, "the gate sample must be read before the window is pumped");

            FramePacer.endFrame(60, null);
            assertEquals(2, backend.pumpCalls);
            assertTrue(backend.parkedBeforePump, "the pump must follow the pacing sleep, not precede it");
        });
    }

    @Test
    void gpuWaitFedToTheCoreEqualsBackendGpuWaitPlusTheFenceWait() {
        final FakeBackend backend = new FakeBackend();
        backend.mode = VSyncMode.OFF;
        backend.gpuWaitNanos = 500_000L;
        final long fenceNanos = 5_000_000L;
        withBackend(backend, () -> {
            FramePacer.endFrame(0, () -> backend.now += fenceNanos);
            final long fed = FramePacer.core.lastGpuWaitNanos();
            assertEquals(backend.gpuWaitNanos + fenceNanos + CLOCK_TICK_NANOS, fed);
        });
    }

    @Test
    void endFrameReturnsThePresentInterval() {
        final FakeBackend backend = new FakeBackend();
        backend.mode = VSyncMode.OFF;
        withBackend(backend, () -> {
            FramePacer.endFrame(0, null);
            final long firstPresent = backend.nanoTime();
            FramePacer.core.beforePresent(firstPresent, 0L);
            FramePacer.endFrame(0, null);
            backend.now += 7_000_000L;
            final long secondPresent = backend.nanoTime();
            FramePacer.core.beforePresent(secondPresent, 0L);
            final long returned = FramePacer.endFrame(0, null);
            assertEquals(secondPresent - firstPresent, returned);
        });
    }

    @Test
    void aDisplayGenerationChangeReArmsTheProbe() {
        final FakeBackend backend = new FakeBackend();
        backend.mode = VSyncMode.ON;
        backend.vsyncHonored = true;
        backend.refreshPeriodNanos = 16_666_666L;
        withBackend(backend, () -> {
            for (int i = 0; i < 60; i++) FramePacer.endFrame(0, null);
            assertFalse(FramePacer.core.probing(), "the probe must exhaust its lead without ever locking");

            backend.displayGeneration++;
            FramePacer.endFrame(0, null);
            assertTrue(FramePacer.core.probing(), "a display change must re-arm the probe");
        });
    }

    private static final class PresentWaitBackend extends FakeBackend {
        final long period;
        int presentWaits;

        PresentWaitBackend(long period) {
            this.period = period;
            mode = VSyncMode.ON;
            vsyncHonored = true;
            refreshPeriodNanos = period;
        }

        @Override
        public void awaitPresent() {
            awaitPresentCalls++;
            final long presented = now;
            final long vblank = Math.floorDiv(presented + period - 1, period) * period;
            now = vblank;
            gateDurationNanos = vblank - presented;
            gateEndNanos = vblank;
            presentWaits++;
        }
    }

    @Test
    void aPresentWaitGateLocksWithinTheFirstFramesAndNeverBlocksElsewhere() {
        final PresentWaitBackend backend = new PresentWaitBackend(16_666_666L);
        withBackend(backend, () -> {
            int lockFrame = -1;
            boolean fellBack = false;
            for (int i = 0; i < 120; i++) {
                FramePacer.endFrame(0, null);
                if (currentState() == FramePacer.PaceState.TIMER_FALLBACK) fellBack = true;
                if (lockFrame < 0 && FramePacer.locked()) lockFrame = i;
                backend.now += 4_000_000L;
                backend.now = FramePacer.core.beforePresent(backend.now, 0L);
            }
            assertEquals(120, backend.presentWaits, "every vsync frame must wait for its predecessor to reach the display");
            assertFalse(fellBack);
            assertTrue(lockFrame >= 0 && lockFrame <= PacerCore.LOCK_PAIRS + 2, "a gate present from the first frame must lock at once, locked at frame " + lockFrame);
            assertEquals(FramePacer.PaceState.LOCKED, currentState());
            assertEquals(0L, FramePacer.slackNanos(), "once the driver paces, the frame must start right after the gate");
        });
    }
}
