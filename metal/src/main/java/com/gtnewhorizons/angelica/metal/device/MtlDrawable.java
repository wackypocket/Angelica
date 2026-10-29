package com.gtnewhorizons.angelica.metal.device;

import org.lwjglx.opengl.Display;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.metal.MetalGate;
import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.DrawableGL;

public final class MtlDrawable extends DrawableGL {

    public MtlDrawable() {
        super((ContextGL) null);
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
        return MetalGate.isActive();
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
