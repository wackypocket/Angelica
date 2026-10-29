package com.gtnewhorizons.angelica.client;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.Display;

public final class LaunchEventPump {
    private static final Logger LOGGER = LogManager.getLogger("Angelica");
    private static final long INTERVAL_MS = 50L;
    private static final long STOP_TIMEOUT_MS = 1000L;

    private static LaunchEventPump active;

    private final Runnable pump;
    private final long intervalMs;
    private final Thread thread;
    private volatile boolean running = true;

    LaunchEventPump(Runnable pump, long intervalMs) {
        this.pump = pump;
        this.intervalMs = intervalMs;
        this.thread = new Thread(this::run, "Angelica Launch Event Pump");
        this.thread.setDaemon(true);
    }

    public static synchronized void start() {
        if (active != null) return;
        final RenderBackend backend = BackendManager.RENDER_BACKEND;
        if (!backend.canPumpDisplayMessagesOffThread()) return;
        if (!Display.isCreated()) {
            LOGGER.warn("FML splash is disabled but the window does not exist yet; window events will not be processed while the game loads");
            return;
        }
        LOGGER.info("FML splash is disabled; processing window events every {} ms while the game loads", INTERVAL_MS);
        active = new LaunchEventPump(backend::pumpDisplayMessages, INTERVAL_MS);
        active.begin();
    }

    public static synchronized void stop() {
        final LaunchEventPump p = active;
        active = null;
        if (p != null) p.shutdown(STOP_TIMEOUT_MS);
    }

    void begin() {
        thread.start();
    }

    void shutdown(long timeoutMs) {
        running = false;
        thread.interrupt();
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) LOGGER.warn("Launch event pump did not stop within {} ms", timeoutMs);
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    private void run() {
        while (running) {
            try {
                pump.run();
            } catch (RuntimeException e) {
                LOGGER.error("Launch event pump failed; window events will not be processed until the game finishes loading", e);
                return;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
