package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.dx12.device.Gpu;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class Dx12TestRig implements AutoCloseable {

    public final Gpu gpu;
    public final Dx12GL gl;

    private Dx12TestRig(Gpu gpu, Dx12GL gl) {
        this.gpu = gpu;
        this.gl = gl;
    }

    public static Dx12TestRig create() {
        final Gpu gpu = new Gpu();
        assumeTrue(gpu.create(), "no Direct3D 12 device");
        final Dx12GL gl = new Dx12GL(gpu, Thread.currentThread(), null);
        gl.attachOffscreen(64, 64);
        gl.viewport(0, 0, 64, 64);
        return new Dx12TestRig(gpu, gl);
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
