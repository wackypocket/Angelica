package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glemu.NativeDisplayBridge;
import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glemu.NativeRuntime;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.MtlDrawable;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLProperties;

import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN;

public final class MetalRuntime extends NativeRuntime<Gpu, MetalGL> {
    public static final MetalRuntime INSTANCE = new MetalRuntime();

    private long view;

    private MetalRuntime() {
        super("Metal", "Metal");
    }

    @Override public NativeGate gate() { return MetalGate.GATE; }
    @Override protected Gpu newDevice() { return new Gpu(); }
    @Override protected boolean createDevice(Gpu gpu) { return gpu.create(); }
    @Override protected void destroyDevice(Gpu gpu) { gpu.destroy(); }
    @Override protected Class<MetalLWJGLService> serviceClass() { return MetalLWJGLService.class; }
    @Override protected NativeDisplayBridge newDisplayBridge() { return new NativeDisplayBridge(MetalGate.GATE::isActive, MtlDrawable::new, MtlDrawable.class); }

    @Override protected void configureWindow(int properties) {
        SDLProperties.SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN, true);
    }

    @Override protected MetalGL attach(long window, Gpu gpu) {
        view = SDLMetal.SDL_Metal_CreateView(window);
        if (view == 0L) throw new IllegalStateException("SDL_Metal_CreateView failed: " + SDLError.SDL_GetError());
        final long layer = SDLMetal.SDL_Metal_GetLayer(view);
        if (layer == 0L) throw new IllegalStateException("SDL_Metal_GetLayer returned no CAMetalLayer: " + SDLError.SDL_GetError());
        final MetalGL gl = new MetalGL(gpu, null);
        try {
            gl.attachWindow(window, layer);
        } catch (RuntimeException | Error e) {
            try {
                gl.shutdown();
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        log.info("Metal backend owns the window: {} (Metal {}, {})", gpu.deviceName, gpu.apiVersion, gpu.driverName);
        return gl;
    }

    @Override protected void detach() {
        if (view != 0L) {
            SDLMetal.SDL_Metal_DestroyView(view);
            view = 0L;
        }
    }
}
