package com.gtnewhorizons.angelica.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class LaunchEventPumpTest {

    @Test
    void pumpsRepeatedlyUntilShutdown() throws InterruptedException {
        final CountDownLatch pumped = new CountDownLatch(3);
        final AtomicInteger calls = new AtomicInteger();
        final LaunchEventPump pump = new LaunchEventPump(() -> {
            calls.incrementAndGet();
            pumped.countDown();
        }, 1L);
        pump.begin();
        assertTrue(pumped.await(5, TimeUnit.SECONDS), "the pump runs on its own thread");
        pump.shutdown(5000L);
        assertFalse(pump.isAlive());
        final int after = calls.get();
        Thread.sleep(20L);
        assertEquals(after, calls.get(), "nothing is pumped after shutdown returns");
    }

    @Test
    void shutdownWaitsForAPumpThatIgnoresInterrupts() throws InterruptedException {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        final LaunchEventPump pump = new LaunchEventPump(() -> {
            entered.countDown();
            final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() < until) {
                Thread.interrupted();
            }
            finished.set(true);
        }, 1L);
        pump.begin();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        pump.shutdown(5000L);
        assertTrue(finished.get(), "shutdown returns only after the pump in flight completes");
        assertFalse(pump.isAlive());
    }

    @Test
    void aFailingPumpStopsItsThread() throws InterruptedException {
        final AtomicInteger calls = new AtomicInteger();
        final LaunchEventPump pump = new LaunchEventPump(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("window destroyed");
        }, 1L);
        pump.begin();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pump.isAlive() && System.nanoTime() < deadline) Thread.sleep(5L);
        assertFalse(pump.isAlive(), "an exception ends the pump instead of killing it with an uncaught error");
        assertEquals(1, calls.get());
        pump.shutdown(1000L);
    }
}
