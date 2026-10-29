package com.gtnewhorizons.angelica.d3d12;

import com.gtnewhorizons.angelica.d3d12.device.Gpu;
import com.gtnewhorizons.angelica.d3d12.gl.D3d12GL;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class D3d12TestRig implements AutoCloseable {

    public final Gpu gpu;
    public final D3d12GL gl;

    private D3d12TestRig(Gpu gpu, D3d12GL gl) {
        this.gpu = gpu;
        this.gl = gl;
    }

    public static D3d12TestRig create() {
        final Gpu gpu = new Gpu();
        assumeTrue(gpu.create(), "no Direct3D 12 device");
        final D3d12GL gl = new D3d12GL(gpu, Thread.currentThread(), null);
        gl.attachOffscreen(64, 64);
        gl.viewport(0, 0, 64, 64);
        return new D3d12TestRig(gpu, gl);
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
