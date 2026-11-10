package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import java.util.function.BooleanSupplier;
import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.DrawableGL;

/** The drawable that stands in for a GL context on a backend that owns the window itself. */
public abstract class NativeDrawable extends DrawableGL {
    private final BooleanSupplier active;

    protected NativeDrawable(BooleanSupplier active) {
        super((ContextGL) null);
        this.active = active;
    }

    @Override
    public void makeCurrent() {
    }

    @Override
    public void releaseContext() {
        BackendManager.RENDER_BACKEND.onRenderThreadReleased(Thread.currentThread());
    }

    @Override
    public boolean isCurrent() {
        return active.getAsBoolean();
    }

    @Override
    public ContextGL createSharedContext() {
        return null;
    }

    @Override
    public void destroy() {
        BackendManager.RENDER_BACKEND.onRenderThreadReleased(Thread.currentThread());
    }

    @Override
    public void checkGLError() {
    }

    @Override
    public void setSwapInterval(int swap_interval) {
    }

    @Override
    public void swapBuffers() {
        BackendManager.RENDER_BACKEND.handleSwapBuffers();
    }

    @Override
    public void initContext(float r, float g, float b) {
    }

    @Override
    public long getSdlWindowId() {
        return Display.getWindow();
    }
}
