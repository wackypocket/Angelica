package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.sdl.SDLInit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class VulkanTestRig implements AutoCloseable {

    public final Gpu gpu;
    public final VulkanGL gl;

    private VulkanTestRig(Gpu gpu, VulkanGL gl) {
        this.gpu = gpu;
        this.gl = gl;
    }

    public static VulkanTestRig create() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        final Gpu gpu = new Gpu();
        assumeTrue(gpu.create(), "no Vulkan device");
        final VulkanGL gl = new VulkanGL(gpu, Thread.currentThread(), null);
        gl.attachOffscreen(64, 64);
        gl.viewport(0, 0, 64, 64);
        return new VulkanTestRig(gpu, gl);
    }

    public void resetBindings() {
        gl.activeTexture(GL13.GL_TEXTURE0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.bindVertexArray(0);
    }

    @Override
    public void close() {
        gl.shutdown();
        gpu.destroy();
    }
}
