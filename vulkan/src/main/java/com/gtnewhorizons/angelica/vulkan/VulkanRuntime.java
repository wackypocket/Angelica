package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.NativeDisplayBridge;
import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glemu.NativeRuntime;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.Swapchain;
import com.gtnewhorizons.angelica.vulkan.device.VkDrawable;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import java.io.File;
import net.minecraft.launchwrapper.Launch;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.vulkan.KHRSurface;

import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN;

public final class VulkanRuntime extends NativeRuntime<Gpu, VulkanGL> {
    public static final VulkanRuntime INSTANCE = new VulkanRuntime();

    private VulkanRuntime() {
        super("Vulkan", "Vulkan");
    }

    @Override public NativeGate gate() { return VulkanGate.GATE; }
    @Override protected Gpu newDevice() { return new Gpu(); }
    @Override protected boolean createDevice(Gpu gpu) { return gpu.create(); }
    @Override protected Gpu nextDevice(Gpu failed) { return failed.next(); }
    @Override protected void destroyDevice(Gpu gpu) { gpu.destroy(); }
    @Override protected Class<VulkanLWJGLService> serviceClass() { return VulkanLWJGLService.class; }
    @Override protected NativeDisplayBridge newDisplayBridge() { return new NativeDisplayBridge(VulkanGate.GATE::isActive, VkDrawable::new, VkDrawable.class); }

    @Override protected void configureWindow(int properties) {
        SDLProperties.SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, true);
    }

    @Override protected VulkanGL attach(long window, Gpu chosen) {
        Gpu gpu = chosen;
        long surface = gpu.createSurface(window);
        if (surface == 0L) throw new IllegalStateException("SDL_Vulkan_CreateSurface failed: " + SDLError.SDL_GetError());
        if (!gpu.supportsPresent(surface)) {
            KHRSurface.vkDestroySurfaceKHR(gpu.instance, surface, null);
            log.warn("{} cannot present to this window; trying the devices that can", gpu.deviceName);
            gpu = presentableDevice(window);
            replaceDevice(gpu);
            surface = gpu.takeSurface();
        }
        VulkanGL gl = null;
        try {
            gl = new VulkanGL(gpu, null, new File(Launch.minecraftHome, "angelica"));
            gl.attachWindow(window, surface);
        } catch (Throwable t) {
            // The device is destroyed next on the way back to OpenGL, so the renderer must not outlive this.
            final boolean swapchainOwnsSurface = gl != null && gl.hasWindow();
            try {
                if (gl != null) gl.shutdown();
            } catch (Throwable s) {
                t.addSuppressed(s);
            }
            if (!swapchainOwnsSurface) KHRSurface.vkDestroySurfaceKHR(gpu.instance, surface, null);
            throw t;
        }
        log.info("Vulkan backend owns the window: {} ({}), swapchain format {}", gpu.deviceName, gpu.driverName, Swapchain.formatName(gl.swapchainFormat()));
        return gl;
    }

    /** The best device that can present to the window, each tried on a fresh instance that makes its own surface for it. */
    private static Gpu presentableDevice(long window) {
        Gpu gpu = new Gpu(window, 0);
        while (!gpu.create()) {
            final Gpu next = gpu.next();
            gpu.destroy();
            if (next == null) throw new IllegalStateException("No Vulkan device can present to this window");
            gpu = next;
        }
        return gpu;
    }
}
