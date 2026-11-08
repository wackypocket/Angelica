package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import java.util.List;
import java.util.Locale;

public final class MetalGate {

    private MetalGate() {}

    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLMetal.class", "no lwjgl-sdl on the classpath"),
        NativeGate.LWJGL3IFY,
        NativeGate.SHADERC,
        NativeGate.SPVC);

    public static final NativeGate GATE = new NativeGate("Metal", "Metal", MetalGate.class, REQUIREMENTS,
        () -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac") ? null : "Metal exists only on macOS",
        BackendOptions::metalRequested, "com.gtnewhorizons.angelica.metal.MetalRenderBackend",
        () -> MetalRuntime.INSTANCE, 1, "native-metal");
}
