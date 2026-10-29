package com.gtnewhorizons.angelica.metal.device;

import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class MetalLayer {
    public static final int FORMAT = Mtl.PF_BGRA8_UNORM;

    private static final long SET_DEVICE = sel("setDevice:"), SET_PIXEL_FORMAT = sel("setPixelFormat:"), SET_FRAMEBUFFER_ONLY = sel("setFramebufferOnly:");
    private static final long SET_DRAWABLE_SIZE = sel("setDrawableSize:"), SET_DISPLAY_SYNC = sel("setDisplaySyncEnabled:");
    private static final long SET_MAX_DRAWABLES = sel("setMaximumDrawableCount:"), NEXT_DRAWABLE = sel("nextDrawable"), TEXTURE = sel("texture");
    private static final long SET_ALLOWS_TIMEOUT = sel("setAllowsNextDrawableTimeout:");

    private final long layer;
    public int width, height;
    private VSyncMode vsync = VSyncMode.ON;
    private boolean syncDirty = true;

    public MetalLayer(Gpu gpu, long layer, int framesInFlight) {
        this.layer = ObjC.retain(layer);
        call(layer, SET_DEVICE, gpu.device);
        call(layer, SET_PIXEL_FORMAT, FORMAT);
        call(layer, SET_FRAMEBUFFER_ONLY, 1L);
        call(layer, SET_MAX_DRAWABLES, Math.max(2, Math.min(3, framesInFlight + 1)));
        if (ObjC.responds(layer, "setAllowsNextDrawableTimeout:")) call(layer, SET_ALLOWS_TIMEOUT, 1L);
    }

    public boolean supports(VSyncMode mode) {
        return mode == VSyncMode.OFF || mode == VSyncMode.ON;
    }

    public VSyncMode setVSync(VSyncMode preferred) {
        final VSyncMode chosen = preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        if (chosen != vsync) {
            vsync = chosen;
            syncDirty = true;
        }
        return chosen;
    }

    public VSyncMode vsyncMode() {
        return vsync;
    }

    public boolean ensure(int pixelW, int pixelH) {
        if (pixelW <= 0 || pixelH <= 0) return false;
        if (syncDirty) {
            call(layer, SET_DISPLAY_SYNC, vsync == VSyncMode.OFF ? 0L : 1L);
            syncDirty = false;
        }
        if (pixelW != width || pixelH != height) {
            ObjC.callSize2D(layer, SET_DRAWABLE_SIZE, pixelW, pixelH);
            width = pixelW;
            height = pixelH;
        }
        return true;
    }

    public long acquire() {
        final long pool = ObjC.poolPush();
        try {
            return ObjC.retain(send(layer, NEXT_DRAWABLE));
        } finally {
            ObjC.poolPop(pool);
        }
    }

    public static long texture(long drawable) {
        return send(drawable, TEXTURE);
    }

    public void destroy() {
        ObjC.release(layer);
    }
}
