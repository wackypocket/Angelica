package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class NativeGate {
    public interface Hooks {
        boolean probe();

        boolean createDisplay(Object pixelFormat, Object attribs);

        void checkCanFallBack();

        void fallBackToGL();

        void ensureDrawableInstalled();
    }

    private final Logger log;
    private final String apiName;
    private final BooleanSupplier requested;
    private final String renderBackendClass;
    private final Hooks hooks;
    private final String missingRequirement;

    private volatile Boolean deviceReady;
    private volatile boolean engaged;
    private ByteBuffer[] windowIcons;

    public NativeGate(String apiName, Class<?> owner, List<Check> requirements, Supplier<String> platformProblem, BooleanSupplier requested,
                      String renderBackendClass, Hooks hooks) {
        this.apiName = apiName;
        this.log = LogManager.getLogger("Angelica/" + apiName);
        this.requested = requested;
        this.renderBackendClass = renderBackendClass;
        this.hooks = hooks;
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
        if (deviceReady != null) return deviceReady;
        if (!isRequested() || !isAvailable()) {
            deviceReady = false;
            return false;
        }
        boolean ok;
        try {
            ok = hooks.probe();
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

    public boolean createDisplay(Object pixelFormat, Object attribs) {
        if (!isDeviceReady()) return false;
        try {
            return hooks.createDisplay(pixelFormat, attribs);
        } catch (LinkageError e) {
            log.error("{} could not be reached while creating the window", apiName, e);
            return false;
        } catch (Throwable t) {
            log.error("{} could not take the window", apiName, t);
            return false;
        }
    }

    public void fallBackToGL() {
        hooks.checkCanFallBack();
        engaged = false;
        deviceReady = false;
        try {
            hooks.fallBackToGL();
        } catch (Throwable t) {
            log.error("{} fallback cleanup failed", apiName, t);
        }
    }

    public void ensureDrawableInstalled() {
        if (!isActive()) return;
        hooks.ensureDrawableInstalled();
    }
}
