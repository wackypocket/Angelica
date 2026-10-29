package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public abstract class DefaultFramebufferBase<T extends TexBase> {
    private final TexturesBase<T> textures;
    public T color;
    protected T spare;
    public T depth;
    public int width, height;
    private boolean doubleBuffered;

    protected DefaultFramebufferBase(TexturesBase<T> textures, boolean doubleBuffered) {
        this.textures = textures;
        this.doubleBuffered = doubleBuffered;
    }

    protected abstract void release(T t);

    protected abstract void targetsChanged();

    // Runs after a swap with the buffer the next frame renders into; its old contents may be discarded.
    protected void onSwapped(T color) {}

    protected T newColorBuffer(int w, int h) {
        final T t = textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, w, h, 1, 1, "defaultColor");
        t.minFilter = GL11.GL_NEAREST;
        return t;
    }

    public void ensure(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (color != null && w == width && h == height) return;
        if (color != null) {
            release(color);
            if (spare != null) release(spare);
            release(depth);
        }
        width = w;
        height = h;
        color = newColorBuffer(w, h);
        spare = doubleBuffered ? newColorBuffer(w, h) : null;
        depth = textures.internalTexture(GL11.GL_TEXTURE_2D, textures.format(GL30.GL_DEPTH24_STENCIL8), w, h, 1, 1, "defaultDepthStencil");
        depth.minFilter = GL11.GL_NEAREST;
        targetsChanged();
    }

    public void setDoubleBuffered(boolean on) {
        if (doubleBuffered == on) return;
        doubleBuffered = on;
        if (color == null) return;
        if (on) {
            spare = newColorBuffer(width, height);
        } else if (spare != null) {
            release(spare);
            spare = null;
        }
    }

    public void swap() {
        if (spare == null) return;
        final T t = color;
        color = spare;
        spare = t;
        onSwapped(color);
        targetsChanged();
    }

    public void destroy() {
        if (color != null) release(color);
        if (spare != null) release(spare);
        if (depth != null) release(depth);
        color = spare = depth = null;
    }
}
