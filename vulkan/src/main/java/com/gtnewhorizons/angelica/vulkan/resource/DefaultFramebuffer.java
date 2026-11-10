package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

public final class DefaultFramebuffer extends DefaultFramebufferBase<Tex> {
    private final VulkanGL gl;

    public DefaultFramebuffer(VulkanGL gl) {
        super(gl.textures, false);
        this.gl = gl;
    }

    @Override protected void configureColorBuffer(Tex t) {
        t.presentShared = true;
    }

    @Override protected void release(Tex t) { gl.images.release(t); }
    @Override protected void targetsChanged() { gl.renderer.targetGen++; }

    @Override protected void onSwapped(Tex color) {
        // The next frame renders into this back buffer while the present blit still reads the other, so its old contents can be discarded.
        color.discardedFrom = color.layout;
        color.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    }
}
