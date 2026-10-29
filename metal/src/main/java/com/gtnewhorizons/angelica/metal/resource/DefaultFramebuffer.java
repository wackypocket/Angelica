package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;

public final class DefaultFramebuffer extends DefaultFramebufferBase<Tex> {
    private final MetalGL gl;

    public DefaultFramebuffer(MetalGL gl) {
        super(gl.textures, true);
        this.gl = gl;
    }

    @Override protected void release(Tex t) { gl.images.release(t); }
    @Override protected void targetsChanged() { gl.renderer.targetGen++; }
    @Override protected void onSwapped(Tex color) { color.contentsUndefined = true; }
}
