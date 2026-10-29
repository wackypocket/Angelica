package com.gtnewhorizons.angelica.dx12.resource;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class DefaultFramebuffer {
    private final Dx12GL gl;
    public Tex color;
    private Tex spare;
    public Tex depth;
    public int width, height;

    public DefaultFramebuffer(Dx12GL gl) {
        this.gl = gl;
    }

    public void ensure(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (color != null && w == width && h == height) return;
        if (color != null) {
            gl.images.release(color);
            gl.images.release(spare);
            gl.images.release(depth);
        }
        width = w;
        height = h;
        color = colorBuffer(w, h);
        spare = colorBuffer(w, h);
        depth = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, gl.textures.format(GL30.GL_DEPTH24_STENCIL8), w, h, 1, 1, "defaultDepthStencil");
        depth.minFilter = GL11.GL_NEAREST;
        gl.renderer.targetGen++;
    }

    private Tex colorBuffer(int w, int h) {
        final Tex t = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, w, h, 1, 1, "defaultColor");
        t.minFilter = GL11.GL_NEAREST;
        return t;
    }

    public void swap() {
        final Tex t = color;
        color = spare;
        spare = t;
        gl.renderer.targetGen++;
    }

    public void destroy() {
        if (color != null) gl.images.release(color);
        if (spare != null) gl.images.release(spare);
        if (depth != null) gl.images.release(depth);
        color = spare = depth = null;
    }
}
