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

    @Override protected Tex newColorBuffer(int w, int h) {
        gl.images.nextPresentShared = true;
        try {
            return super.newColorBuffer(w, h);
        } finally {
            gl.images.nextPresentShared = false;
        }
    }

    @Override protected void release(Tex t) { gl.images.release(t); }
    @Override protected void targetsChanged() { gl.renderer.targetGen++; }

    @Override protected void onSwapped(Tex color) {
        // The next frame renders into the other back buffer while the present blit still reads this one; its old contents are discarded.
        color.discardedFrom = color.layout;
        color.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    }
}
