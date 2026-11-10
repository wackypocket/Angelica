package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.MetalLayer;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.pipeline.BlitPass;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.gtnewhorizons.angelica.metal.device.ObjC.callBlocking;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class Presenter {
    private static final Tracy.ZoneId Z_HANDOFF_WAIT = Tracy.zoneId("mtlPresentHandoffWait", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_DRAWABLE_WAIT = Tracy.zoneId("mtlDrawableWait", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static final long COMMAND_BUFFER = sel("commandBuffer"), PRESENT = sel("presentDrawable:"), COMMIT = sel("commit");
    private static final long STATUS = sel("status"), WAIT = sel("waitUntilCompleted");

    private final Gpu gpu;
    private final MetalLayer layer;
    private final BlitPass blit;
    private final Semaphore pending = new Semaphore(1);
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Angelica Metal presenter");
        t.setDaemon(true);
        return t;
    });
    private boolean failed;
    /** Nothing waits on a present, so each is held until it has finished and its outcome can be read. */
    private final ArrayDeque<Present> unfinished = new ArrayDeque<>();

    private record Present(long commandBuffer, long number) {}

    private volatile long committed;

    public Presenter(Gpu gpu, MetalLayer layer, BlitPass blit) {
        this.gpu = gpu;
        this.layer = layer;
        this.blit = blit;
    }

    public boolean present(long texture, int srcW, int srcH, int drawW, int drawH, boolean wait) {
        // One present may be pending at a time; with vsync the caller waits for the slot, without it the frame is dropped.
        if (wait) {
            Tracy.beginZone(Z_HANDOFF_WAIT);
            try {
                pending.acquireUninterruptibly();
            } finally {
                Tracy.endZone();
            }
        } else if (!pending.tryAcquire()) {
            ObjC.release(texture);
            return false;
        }
        try {
            thread.execute(() -> {
                try {
                    presentNow(texture, srcW, srcH, drawW, drawH);
                } catch (Throwable t) {
                    if (!failed) {
                        failed = true;
                        LOG.error("Metal present failed", t);
                    }
                } finally {
                    ObjC.release(texture);
                    pending.release();
                }
            });
        } catch (RuntimeException e) {
            ObjC.release(texture);
            pending.release();
            throw e;
        }
        return true;
    }

    private void presentNow(long texture, int srcW, int srcH, int drawW, int drawH) {
        final long pool = ObjC.poolPush();
        try {
            if (!layer.ensure(drawW, drawH)) return;
            Tracy.beginZone(Z_DRAWABLE_WAIT);
            final long drawable;
            try {
                drawable = layer.acquire();
            } finally {
                Tracy.endZone();
            }
            if (drawable == 0L) return;
            try {
                final long cb = ObjC.sendBlocking(gpu.queue, COMMAND_BUFFER);
                blit.encodePresent(cb, texture, srcW, srcH, MetalLayer.texture(drawable), layer.width, layer.height);
                callBlocking(cb, PRESENT, drawable);
                callBlocking(cb, COMMIT);
                retirePresents();
                unfinished.addLast(new Present(ObjC.retain(cb), ++committed));
            } finally {
                ObjC.release(drawable);
            }
        } finally {
            ObjC.poolPop(pool);
        }
    }

    private void retirePresents() {
        while (!unfinished.isEmpty() && send(unfinished.peekFirst().commandBuffer, STATUS) >= Mtl.CB_COMPLETED) {
            final Present p = unfinished.pollFirst();
            gpu.reportIfFailed(p.commandBuffer, "Present", p.number);
            ObjC.release(p.commandBuffer);
        }
    }

    /** Frames committed to a drawable; a present that found none is not one of them. */
    public long committed() {
        return committed;
    }

    public void drain() {
        pending.acquireUninterruptibly();
        pending.release();
    }

    public void shutdown() {
        drain();
        thread.shutdown();
        final long pool = ObjC.poolPush();
        try {
            for (Present p : unfinished) callBlocking(p.commandBuffer, WAIT);
            retirePresents();
        } finally {
            ObjC.poolPop(pool);
        }
    }
}
