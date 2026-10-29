package com.gtnewhorizons.angelica.d3d12.resource;

import com.gtnewhorizons.angelica.d3d12.gl.D3d12GL;
import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;

public final class DefaultFramebuffer extends DefaultFramebufferBase<Tex> {
    private final D3d12GL gl;

    public DefaultFramebuffer(D3d12GL gl) {
        super(gl.textures, true);
        this.gl = gl;
    }

    @Override protected void release(Tex t) { gl.images.release(t); }
    @Override protected void targetsChanged() { gl.renderer.targetGen++; }
}
