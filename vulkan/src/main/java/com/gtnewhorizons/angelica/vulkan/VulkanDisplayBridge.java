package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.vulkan.device.VkDrawable;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.Drawable;
import org.lwjglx.opengl.DrawableGL;
import org.lwjglx.opengl.SharedDrawable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class VulkanDisplayBridge {
    private VulkanDisplayBridge() {}

    private static volatile boolean drawableInstalled;

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

    public static void ensureDrawableInstalled() {
        if (drawableInstalled) return;
        if (!VulkanGate.isActive()) return;
        if (DISPLAY_DRAWABLE_FIELD.get() == null) {
            DISPLAY_DRAWABLE_FIELD.set(new VkDrawable());
        }
        drawableInstalled = true;
    }

    public static void present() {
        if (VulkanGate.isActive()) {
            BackendManager.RENDER_BACKEND.handleSwapBuffers();
        }
    }

    public static void releaseRenderThread() {
        if (VulkanGate.isActive()) {
            BackendManager.RENDER_BACKEND.onRenderThreadReleased(Thread.currentThread());
        }
    }

    public static boolean isVkDrawable(SharedDrawable sd) {
        return sd != null && SHARED_DRAWABLE_WRAPPED_FIELD.get(sd) instanceof VkDrawable;
    }

    public static boolean isVkSharedDrawable(Object drawable) {
        return drawable instanceof SharedDrawable && isVkDrawable((SharedDrawable) drawable);
    }
}
