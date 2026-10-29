package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.Drawable;
import org.lwjglx.opengl.DrawableGL;
import org.lwjglx.opengl.SharedDrawable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class NativeDisplayBridge {
    private static final VarHandle DISPLAY_DRAWABLE_FIELD;
    private static final VarHandle SHARED_DRAWABLE_WRAPPED_FIELD;
    static {
        final VarHandle displayDrawableField;
        final VarHandle sharedDrawableWrappedField;
        try {
            displayDrawableField = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup()).findStaticVarHandle(Display.class, "drawable", DrawableGL.class);
            sharedDrawableWrappedField = MethodHandles.privateLookupIn(SharedDrawable.class, MethodHandles.lookup()).findVarHandle(SharedDrawable.class, "drawable", Drawable.class);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
        DISPLAY_DRAWABLE_FIELD = displayDrawableField;
        SHARED_DRAWABLE_WRAPPED_FIELD = sharedDrawableWrappedField;
    }

    private final BooleanSupplier active;
    private final Supplier<? extends DrawableGL> newDrawable;
    private final Class<? extends DrawableGL> drawableType;
    private volatile boolean drawableInstalled;

    public NativeDisplayBridge(BooleanSupplier active, Supplier<? extends DrawableGL> newDrawable, Class<? extends DrawableGL> drawableType) {
        this.active = active;
        this.newDrawable = newDrawable;
        this.drawableType = drawableType;
    }

    public void ensureDrawableInstalled() {
        if (drawableInstalled) return;
        if (!active.getAsBoolean()) return;
        if (DISPLAY_DRAWABLE_FIELD.get() == null) {
            DISPLAY_DRAWABLE_FIELD.set(newDrawable.get());
        }
        drawableInstalled = true;
    }

    public void present() {
        if (active.getAsBoolean()) {
            BackendManager.RENDER_BACKEND.handleSwapBuffers();
        }
    }

    public void releaseRenderThread() {
        if (active.getAsBoolean()) {
            BackendManager.RENDER_BACKEND.onRenderThreadReleased(Thread.currentThread());
        }
    }

    public boolean isDrawable(SharedDrawable sd) {
        return sd != null && drawableType.isInstance(SHARED_DRAWABLE_WRAPPED_FIELD.get(sd));
    }

    public boolean isSharedDrawable(Object drawable) {
        return drawable instanceof SharedDrawable && isDrawable((SharedDrawable) drawable);
    }
}
