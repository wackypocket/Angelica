package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.MetalLayer;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.pipeline.BlitPass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.callBlocking;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Presenter {
    private static final Tracy.ZoneId Z_HANDOFF_WAIT = Tracy.zoneId("mtlPresentHandoffWait", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_DRAWABLE_WAIT = Tracy.zoneId("mtlDrawableWait", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static final long COMMAND_BUFFER = sel("commandBuffer"), PRESENT = sel("presentDrawable:"), COMMIT = sel("commit");

    private final Gpu gpu;
    private final MetalLayer layer;
    private final BlitPass blit;
    private final Semaphore pending = new Semaphore(1);
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Angelica Metal presenter");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastDrawableWaitNanos;
    private boolean failed;

    public Presenter(Gpu gpu, MetalLayer layer, BlitPass blit) {
        this.gpu = gpu;
        this.layer = layer;
        this.blit = blit;
    }

    public boolean present(long texture, int srcW, int srcH, int drawW, int drawH, boolean wait) {
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
            final long t0 = System.nanoTime();
            Tracy.beginZone(Z_DRAWABLE_WAIT);
            final long drawable;
            try {
                drawable = layer.acquire();
            } finally {
                Tracy.endZone();
            }
            lastDrawableWaitNanos = System.nanoTime() - t0;
            if (drawable == 0L) return;
            try {
                final long cb = send(gpu.queue, COMMAND_BUFFER);
                blit.encodePresent(cb, texture, srcW, srcH, MetalLayer.texture(drawable), MetalLayer.FORMAT, layer.width, layer.height);
                callBlocking(cb, PRESENT, drawable);
                callBlocking(cb, COMMIT);
            } finally {
                ObjC.release(drawable);
            }
        } finally {
            ObjC.poolPop(pool);
        }
    }

    public long lastDrawableWaitNanos() {
        return lastDrawableWaitNanos;
    }

    public void drain() {
        pending.acquireUninterruptibly();
        pending.release();
    }

    public void shutdown() {
        drain();
        thread.shutdown();
    }
}
