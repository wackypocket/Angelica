package com.gtnewhorizons.angelica.dx12.device;

import org.lwjglx.opengl.Display;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.dx12.Dx12Gate;
import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.DrawableGL;

public final class Dx12Drawable extends DrawableGL {

    public Dx12Drawable() {
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
        return Dx12Gate.isActive();
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
