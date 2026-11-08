package com.gtnewhorizons.angelica.glemu.gl;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * A GL query spans passes while a native one ends with its pass, so each GL query adds up one native query per pass it was
 * active in. A slot is reused only once the GPU has finished with it.
 */
public final class OcclusionQueries {

    public interface Device {
        /** How many slots there are, 0 when the device cannot count. */
        int capacity();

        void reset(int slot);

        void begin(int slot);

        void end(int slot);

        /** What a slot counted, once the work that wrote it has finished. */
        long read(int slot);
    }

    private final GlBase<?, ?, ?> gl;
    private final Device device;
    private final Int2ObjectOpenHashMap<IntArrayList> slotsOf = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<long[]> lastUse = new Int2ObjectOpenHashMap<>();
    private final IntArrayList free = new IntArrayList();
    private final IntArrayList retired = new IntArrayList();
    private final LongArrayList retiredAt = new LongArrayList();
    private int active;
    private boolean paused;
    private int openSlot = -1;

    public OcclusionQueries(GlBase<?, ?, ?> gl, Device device) {
        this.gl = gl;
        this.device = device;
        for (int i = device.capacity() - 1; i >= 0; i--) free.add(i);
    }

    public boolean counts() {
        return device.capacity() > 0;
    }

    public void begin(int id) {
        forget(id);
        slotsOf.put(id, new IntArrayList());
        lastUse.put(id, new long[] {0L});
        active = id;
        if (gl.rendererBase().passActive) passBegan();
    }

    public void end() {
        passEnding();
        active = 0;
    }

    /** Stops counting around a clear: clearing is not rendering, even when a backend clears by drawing. */
    public void pause() {
        passEnding();
        paused = true;
    }

    public void resume() {
        paused = false;
        if (active != 0 && gl.rendererBase().passActive) passBegan();
    }

    /** Called with a new pass recording, or a query starting inside one. */
    public void passBegan() {
        if (active == 0 || paused) return;
        final int slot = take();
        if (slot < 0) {
            gl.warnOnce("too many occlusion queries at once; some samples are not counted");
            return;
        }
        device.reset(slot);
        device.begin(slot);
        openSlot = slot;
        slotsOf.get(active).add(slot);
    }

    /** Called before a pass stops recording, or a query stopping inside one. */
    public void passEnding() {
        if (openSlot < 0) return;
        device.end(openSlot);
        openSlot = -1;
        lastUse.get(active)[0] = gl.currentSerial();
    }

    private int take() {
        if (free.isEmpty()) reclaim();
        return free.isEmpty() ? -1 : free.removeInt(free.size() - 1);
    }

    private void reclaim() {
        gl.pollStream();
        for (int i = retired.size() - 1; i >= 0; i--) {
            if (gl.isIdle(retiredAt.getLong(i))) {
                free.add(retired.removeInt(i));
                retiredAt.removeLong(i);
            }
        }
    }

    public boolean busy() {
        return active != 0;
    }

    public boolean pending(int id) {
        return slotsOf.containsKey(id);
    }

    public long lastUse(int id) {
        return lastUse.get(id)[0];
    }

    /** Adds up what a finished query counted, and gives its slots back. */
    public long collect(int id) {
        final IntArrayList slots = slotsOf.get(id);
        long sum = 0L;
        for (int i = 0; i < slots.size(); i++) sum += device.read(slots.getInt(i));
        forget(id);
        return sum;
    }

    public void forget(int id) {
        if (active == id) {
            passEnding();
            active = 0;
        }
        final IntArrayList slots = slotsOf.remove(id);
        if (slots == null) return;
        final long serial = lastUse.remove(id)[0];
        for (int i = 0; i < slots.size(); i++) {
            retired.add(slots.getInt(i));
            retiredAt.add(serial);
        }
    }
}
