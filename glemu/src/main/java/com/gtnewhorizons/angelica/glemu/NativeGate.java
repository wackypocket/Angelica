package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjglx.opengl.PixelFormat;

public final class NativeGate {
    public interface Hooks {
        boolean probe();

        boolean createDisplay(PixelFormat pixelFormat);

        void checkCanFallBack();

        void fallBackToGL();

        void ensureDrawableInstalled();
    }

    public static final String LWJGL3IFY_MIN_VERSION = "3.0.31";
    public static final Check LWJGL3IFY = new Check("/me/eigenraven/lwjgl3ify/api/GLCapabilitiesOverride.class", "lwjgl3ify older than " + LWJGL3IFY_MIN_VERSION + ", update the mod: https://modrinth.com/mod/lwjgl3ify"),
        SHADERC = new Check("/org/lwjgl/util/shaderc/Shaderc.class", "no lwjgl-shaderc on the classpath; re-apply the lwjgl3ify instance zip"),
        SPVC = new Check("/org/lwjgl/util/spvc/Spvc.class", "no lwjgl-spvc on the classpath; re-apply the lwjgl3ify instance zip");

    private final Logger log;
    private final String apiName;
    private final BooleanSupplier requested;
    private final String renderBackendClass;
    private final Supplier<? extends Hooks> hooksSupplier;
    private final String missingRequirement;
    private final int requirementsRevision;
    private final String guardKey;

    private volatile Boolean deviceReady;
    private volatile boolean engaged;
    private ByteBuffer[] windowIcons;

    public NativeGate(String apiName, String logLabel, Class<?> owner, List<Check> requirements, Supplier<String> platformProblem, BooleanSupplier requested,
                      String renderBackendClass, Supplier<? extends Hooks> hooks, int requirementsRevision, String guardKey) {
        this.apiName = apiName;
        this.requirementsRevision = requirementsRevision;
        this.guardKey = guardKey;
        this.log = LogManager.getLogger("Angelica/" + logLabel);
        this.requested = requested;
        this.renderBackendClass = renderBackendClass;
        this.hooksSupplier = hooks;
        final String platform = platformProblem.get();
        if (platform != null) {
            this.missingRequirement = platform;
        } else {
            final Check check = DependencyVerifier.firstMissing(owner, requirements);
            this.missingRequirement = check == null ? null : check.errorMessage();
        }
    }

    public boolean isRequested() {
        return requested.getAsBoolean();
    }

    public boolean isAvailable() {
        return missingRequirement == null;
    }

    public String missingRequirement() {
        return missingRequirement;
    }

    public String apiName() {
        return apiName;
    }

    /** Keys the start guard, so changing the backend's requirements starts a fresh strike count. */
    public int requirementsRevision() {
        return requirementsRevision;
    }

    public String guardKey() {
        return guardKey;
    }

    public void rememberIcons(ByteBuffer[] icons) {
        windowIcons = icons;
    }

    public ByteBuffer[] icons() {
        return windowIcons;
    }

    public boolean isDeviceReady() {
        final Boolean ready = deviceReady;
        return ready != null ? ready : probe();
    }

    private synchronized boolean probe() {
        // Brings the device up once, early, so a missing API or driver sends startup to OpenGL before any window exists.
        if (deviceReady != null) return deviceReady;
        if (!isRequested() || !isAvailable()) {
            deviceReady = false;
            return false;
        }
        boolean ok;
        try {
            ok = hooksSupplier.get().probe();
        } catch (LinkageError e) {
            log.error("{} could not be reached ({}); continuing on OpenGL", apiName, e.toString());
            ok = false;
        } catch (Throwable t) {
            log.error("{} device probe failed; continuing on OpenGL", apiName, t);
            ok = false;
        }
        deviceReady = ok;
        return ok;
    }

    public boolean isEngaged() {
        return engaged;
    }

    public void setEngaged(boolean value) {
        engaged = value;
    }

    public boolean isActive() {
        if (!engaged) return false;
        final RenderBackend rb = BackendManager.RENDER_BACKEND;
        return rb != null && rb.getClass().getName().equals(renderBackendClass);
    }

    private boolean createDisplay(PixelFormat pixelFormat) {
        try {
            return hooksSupplier.get().createDisplay(pixelFormat);
        } catch (LinkageError e) {
            log.error("{} could not be reached while creating the window", apiName, e);
            return false;
        } catch (Throwable t) {
            log.error("{} could not take the window", apiName, t);
            return false;
        }
    }

    /** Creates the window on this backend, falling back to OpenGL when it cannot. Returns whether the backend took it. */
    public boolean takeWindow(int stencilBits) {
        if (!isDeviceReady()) {
            log.warn("No usable {} device; creating an OpenGL window instead", apiName);
            return false;
        }
        ImageIO.setUseCache(false);
        if (createDisplay(new PixelFormat().withDepthBits(24).withStencilBits(stencilBits))) {
            log.info("Created {} window", apiName);
            return true;
        }
        log.warn("{} could not take the window; falling back to OpenGL", apiName);
        fallBackToGL();
        return false;
    }

    public void fallBackToGL() {
        hooksSupplier.get().checkCanFallBack();
        engaged = false;
        deviceReady = false;
        try {
            hooksSupplier.get().fallBackToGL();
        } catch (Throwable t) {
            log.error("{} fallback cleanup failed", apiName, t);
        }
    }

    public void ensureDrawableInstalled() {
        if (!isActive()) return;
        hooksSupplier.get().ensureDrawableInstalled();
    }
}
