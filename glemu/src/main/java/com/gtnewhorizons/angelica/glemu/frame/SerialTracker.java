package com.gtnewhorizons.angelica.glemu.frame;

import java.util.ArrayDeque;

/** The submission serials of a command stream, and the actions waiting for them to complete. */
public abstract class SerialTracker implements SerialStream {
    private record Deferred(long serial, Runnable action) {}

    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    protected long currentSerial = 1;
    protected long completedSerial = 0;
    public long submits;

    public abstract void poll();

    public abstract void finish();

    public abstract void destroy();

    public abstract long submit();

    public abstract void waitFor(long serial);

    public abstract boolean waitFor(long serial, long timeoutNanos);

    @Override public long currentSerial() {
        return currentSerial;
    }

    public long completedSerial() {
        return completedSerial;
    }

    @Override public boolean isIdle(long lastUseSerial) {
        return lastUseSerial <= completedSerial;
    }

    /** Whether a resource last used at this serial is untouched by the open submission, so an earlier list may still write it. */
    public boolean canHoist(long lastUse) {
        return lastUse < currentSerial;
    }

    @Override public void defer(Runnable action) {
        deferred.addLast(new Deferred(currentSerial, action));
    }

    public void deferAfter(long serial, Runnable action) {
        if (serial <= completedSerial) {
            action.run();
            return;
        }
        deferred.addLast(new Deferred(serial, action));
    }

    protected abstract boolean nothingInFlight();

    /** Ends a submission that has no commands; it completes at once when nothing earlier is still in flight. */
    protected final long closeEmptySerial() {
        final long serial = currentSerial;
        currentSerial++;
        if (nothingInFlight()) {
            completedSerial = serial;
            runDeferred();
        }
        return serial;
    }

    protected final void runDeferred() {
        while (!deferred.isEmpty() && deferred.peekFirst().serial <= completedSerial) {
            deferred.pollFirst().action.run();
        }
    }

    protected final void runAllDeferred() {
        while (!deferred.isEmpty()) deferred.pollFirst().action.run();
    }
}
