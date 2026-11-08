package com.gtnewhorizons.angelica.glemu;

import org.junit.jupiter.api.Test;
import org.lwjglx.opengl.PixelFormat;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeGateTest {

    private static final class FakeHooks implements NativeGate.Hooks {
        boolean probeResult = true, displayResult = true;
        RuntimeException probeThrows, displayThrows, fallBackRefused;
        int probes, displays, fallBacks;

        @Override public boolean probe() {
            probes++;
            if (probeThrows != null) throw probeThrows;
            return probeResult;
        }

        @Override public boolean createDisplay(PixelFormat pixelFormat) {
            displays++;
            if (displayThrows != null) throw displayThrows;
            return displayResult;
        }

        @Override public void checkCanFallBack() {
            if (fallBackRefused != null) throw fallBackRefused;
        }

        @Override public void fallBackToGL() {
            fallBacks++;
        }

        @Override public void ensureDrawableInstalled() {
        }
    }

    private final FakeHooks hooks = new FakeHooks();

    private NativeGate gate(boolean requested, String platformProblem) {
        return new NativeGate("Test", "Test", NativeGateTest.class, List.of(), () -> platformProblem, () -> requested,
            "no.such.Backend", () -> hooks, 1, "native-test");
    }

    @Test
    void aGateNobodyAskedForNeverTouchesTheDevice() {
        assertFalse(gate(false, null).isDeviceReady());
        assertEquals(0, hooks.probes);
    }

    @Test
    void anUnsupportedSystemNamesItsProblemAndNeverTouchesTheDevice() {
        final NativeGate gate = gate(true, "not on this system");
        assertFalse(gate.isAvailable());
        assertEquals("not on this system", gate.missingRequirement());
        assertFalse(gate.isDeviceReady());
        assertEquals(0, hooks.probes);
    }

    @Test
    void theDeviceIsProbedOnce() {
        final NativeGate gate = gate(true, null);
        assertTrue(gate.isDeviceReady());
        assertTrue(gate.isDeviceReady());
        assertEquals(1, hooks.probes);
    }

    @Test
    void aProbeThatThrowsMeansOpenGl() {
        hooks.probeThrows = new IllegalStateException("no driver");
        assertFalse(gate(true, null).isDeviceReady());
    }

    @Test
    void noDeviceLeavesTheWindowToOpenGlWithoutFallingBack() {
        hooks.probeResult = false;
        assertFalse(gate(true, null).takeWindow(8));
        assertEquals(0, hooks.displays);
        assertEquals(0, hooks.fallBacks);
    }

    @Test
    void takingTheWindowLeavesTheBackendInPlace() {
        assertTrue(gate(true, null).takeWindow(8));
        assertEquals(1, hooks.displays);
        assertEquals(0, hooks.fallBacks);
    }

    @Test
    void aWindowThatCannotBeCreatedFallsBack() {
        hooks.displayResult = false;
        final NativeGate gate = gate(true, null);
        gate.setEngaged(true);
        assertFalse(gate.takeWindow(8));
        assertEquals(1, hooks.fallBacks);
        assertFalse(gate.isEngaged());
        assertFalse(gate.isDeviceReady());
    }

    @Test
    void aWindowThatThrowsFallsBack() {
        hooks.displayThrows = new IllegalStateException("no surface");
        assertFalse(gate(true, null).takeWindow(8));
        assertEquals(1, hooks.fallBacks);
    }

    @Test
    void aBackendAlreadyBoundCannotFallBack() {
        hooks.fallBackRefused = new IllegalStateException("already bound");
        final NativeGate gate = gate(true, null);
        gate.setEngaged(true);
        assertSame(hooks.fallBackRefused, assertThrows(IllegalStateException.class, gate::fallBackToGL));
        assertEquals(0, hooks.fallBacks);
        assertTrue(gate.isEngaged());
    }

    @Test
    void aFailingCleanupStillLeavesOpenGlInCharge() {
        final NativeGate.Hooks failing = new NativeGate.Hooks() {
            @Override public boolean probe() { return true; }
            @Override public boolean createDisplay(PixelFormat pixelFormat) { return false; }
            @Override public void checkCanFallBack() {}
            @Override public void fallBackToGL() { throw new IllegalStateException("teardown failed"); }
            @Override public void ensureDrawableInstalled() {}
        };
        final NativeGate gate = new NativeGate("Test", "Test", NativeGateTest.class, List.of(), () -> null, () -> true, "no.such.Backend", () -> failing, 1, "native-test");
        gate.setEngaged(true);
        gate.fallBackToGL();
        assertFalse(gate.isEngaged());
    }
}
