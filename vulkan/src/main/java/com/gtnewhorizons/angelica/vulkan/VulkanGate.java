package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;

public final class VulkanGate {

    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private VulkanGate() {}

    private static volatile Boolean deviceReady;
    private static volatile boolean engaged;
    private static ByteBuffer[] windowIcons;

    public static final String LWJGL3IFY_MIN_VERSION = "3.0.25";

    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLVulkan.class", "no lwjgl-sdl on the classpath"),
        new Check("/me/eigenraven/lwjgl3ify/api/DisplayEvents.class", "lwjgl3ify older than " + LWJGL3IFY_MIN_VERSION + ", update the mod: https://modrinth.com/mod/lwjgl3ify"),
        new Check("/org/lwjgl/util/shaderc/Shaderc.class", "no lwjgl-shaderc on the classpath; re-apply the lwjgl3ify instance zip"));

    private static final Check MISSING_REQUIREMENT = DependencyVerifier.firstMissing(VulkanGate.class, REQUIREMENTS);

    public static boolean isRequested() {
        return BackendOptions.vulkanRequested();
    }

    public static boolean isVulkanAvailable() {
        return MISSING_REQUIREMENT == null;
    }

    public static String missingRequirement() {
        return MISSING_REQUIREMENT == null ? null : MISSING_REQUIREMENT.errorMessage();
    }

    public static void rememberIcons(ByteBuffer[] icons) {
        windowIcons = icons;
    }

    static ByteBuffer[] icons() {
        return windowIcons;
    }

    public static boolean isDeviceReady() {
        final Boolean ready = deviceReady;
        return ready != null ? ready : probe();
    }

    private static synchronized boolean probe() {
        if (deviceReady != null) return deviceReady;
        if (!isRequested() || !isVulkanAvailable()) {
            deviceReady = false;
            return false;
        }
        boolean ok;
        try {
            ok = VulkanRuntime.probe();
        } catch (LinkageError e) {
            LOG.error("Vulkan bindings could not be loaded ({}); continuing on OpenGL", e.toString());
            ok = false;
        } catch (Throwable t) {
            LOG.error("Vulkan device probe failed; continuing on OpenGL", t);
            ok = false;
        }
        deviceReady = ok;
        return ok;
    }

    public static boolean isEngaged() {
        return engaged;
    }

    static void setEngaged(boolean value) {
        engaged = value;
    }

    public static boolean isActive() {
        if (!engaged) return false;
        final RenderBackend rb = BackendManager.RENDER_BACKEND;
        return rb != null && rb.getClass().getName().equals("com.gtnewhorizons.angelica.vulkan.VulkanRenderBackend");
    }

    public static boolean createDisplay(Object pixelFormat, Object attribs) {
        if (!isDeviceReady()) return false;
        try {
            return VulkanRuntime.createDisplay(pixelFormat, attribs);
        } catch (LinkageError e) {
            LOG.error("Vulkan bindings could not be loaded while creating the window", e);
            return false;
        } catch (Throwable t) {
            LOG.error("Vulkan could not take the window", t);
            return false;
        }
    }

    public static void fallBackToGL() {
        engaged = false;
        deviceReady = false;
        try {
            VulkanRuntime.fallBackToGL();
        } catch (Throwable t) {
            LOG.error("Vulkan fallback cleanup failed", t);
        }
    }

    public static void ensureDrawableInstalled() {
        if (!isActive()) return;
        VulkanRuntime.ensureDrawableInstalled();
    }
}
