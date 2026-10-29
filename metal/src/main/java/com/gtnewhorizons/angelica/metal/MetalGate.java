package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

public final class MetalGate {

    private MetalGate() {}

    public static final String LWJGL3IFY_MIN_VERSION = "3.0.25";
    public static final int REQUIREMENTS_REVISION = 1;
    public static final String GUARD_KEY = "native-metal";

    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLMetal.class", "no lwjgl-sdl on the classpath"),
        new Check("/me/eigenraven/lwjgl3ify/api/DisplayEvents.class", "lwjgl3ify older than " + LWJGL3IFY_MIN_VERSION + ", update the mod: https://modrinth.com/mod/lwjgl3ify"),
        new Check("/org/lwjgl/util/shaderc/Shaderc.class", "no lwjgl-shaderc on the classpath; re-apply the lwjgl3ify instance zip"),
        new Check("/org/lwjgl/util/spvc/Spvc.class", "no lwjgl-spvc on the classpath; re-apply the lwjgl3ify instance zip"));

    private static final NativeGate GATE = new NativeGate("Metal", MetalGate.class, REQUIREMENTS,
        () -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac") ? null : "Metal exists only on macOS",
        BackendOptions::metalRequested, "com.gtnewhorizons.angelica.metal.MetalRenderBackend",
        new NativeGate.Hooks() {
            @Override public boolean probe() {
                return MetalRuntime.probe();
            }

            @Override public boolean createDisplay(Object pixelFormat, Object attribs) {
                return MetalRuntime.createDisplay(pixelFormat, attribs);
            }

            @Override public void checkCanFallBack() {
                MetalRuntime.checkCanFallBack();
            }

            @Override public void fallBackToGL() {
                MetalRuntime.fallBackToGL();
            }

            @Override public void ensureDrawableInstalled() {
                MetalRuntime.ensureDrawableInstalled();
            }
        });

    public static boolean isRequested() {
        return GATE.isRequested();
    }

    public static boolean isMetalAvailable() {
        return GATE.isAvailable();
    }

    public static String missingRequirement() {
        return GATE.missingRequirement();
    }

    public static void rememberIcons(ByteBuffer[] icons) {
        GATE.rememberIcons(icons);
    }

    static ByteBuffer[] icons() {
        return GATE.icons();
    }

    public static boolean isDeviceReady() {
        return GATE.isDeviceReady();
    }

    public static boolean isEngaged() {
        return GATE.isEngaged();
    }

    public static void setEngaged(boolean value) {
        GATE.setEngaged(value);
    }

    public static boolean isActive() {
        return GATE.isActive();
    }

    public static boolean createDisplay(Object pixelFormat, Object attribs) {
        return GATE.createDisplay(pixelFormat, attribs);
    }

    public static void fallBackToGL() {
        GATE.fallBackToGL();
    }

    public static void ensureDrawableInstalled() {
        GATE.ensureDrawableInstalled();
    }
}
