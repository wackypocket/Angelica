package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class DefaultFramebuffer {
    private final VulkanGL gl;
    public Tex color;
    public Tex depth;
    public int width, height;

    public DefaultFramebuffer(VulkanGL gl) {
        this.gl = gl;
    }

    public void ensure(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (color != null && w == width && h == height) return;
        if (color != null) {
            gl.images.release(color);
            gl.images.release(depth);
        }
        width = w;
        height = h;
        color = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, w, h, 1, 1, "defaultColor");
        depth = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, gl.textures.format(GL30.GL_DEPTH24_STENCIL8), w, h, 1, 1, "defaultDepthStencil");
        color.minFilter = GL11.GL_NEAREST;
        depth.minFilter = GL11.GL_NEAREST;
        gl.renderer.targetGen++;
    }

    public void destroy() {
        if (color != null) gl.images.release(color);
        if (depth != null) gl.images.release(depth);
        color = depth = null;
    }
}
