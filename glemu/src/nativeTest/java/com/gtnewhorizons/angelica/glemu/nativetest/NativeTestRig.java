package com.gtnewhorizons.angelica.glemu.nativetest;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.DeviceRequirement;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class NativeTestRig implements AutoCloseable {

    public final GpuCaps gpu;
    public final GlBase<?, ?, ?> gl;

    private NativeTestRig(GpuCaps gpu, GlBase<?, ?, ?> gl) {
        this.gpu = gpu;
        this.gl = gl;
    }

    public static int program(GlBase<?, ?, ?> gl, String vs, String fs) {
        final int v = gl.createShader(GL20.GL_VERTEX_SHADER);
        gl.shaderSource(v, vs);
        gl.compileShader(v);
        assertEquals(GL11.GL_TRUE, gl.getShaderi(v, GL20.GL_COMPILE_STATUS), gl.getShaderInfoLog(v, 4096));
        final int f = gl.createShader(GL20.GL_FRAGMENT_SHADER);
        gl.shaderSource(f, fs);
        gl.compileShader(f);
        assertEquals(GL11.GL_TRUE, gl.getShaderi(f, GL20.GL_COMPILE_STATUS), gl.getShaderInfoLog(f, 4096));
        final int p = gl.createProgram();
        gl.attachShader(p, v);
        gl.attachShader(p, f);
        gl.bindAttribLocation(p, 0, "pos");
        gl.linkProgram(p);
        assertEquals(GL11.GL_TRUE, gl.getProgrami(p, GL20.GL_LINK_STATUS), gl.getProgramInfoLog(p, 4096));
        return p;
    }

    public static NativeTestRig create() {
        DeviceRequirement.require(TestBackend.initPlatform(), "windowing system unavailable");
        final GpuCaps gpu = TestBackend.newDevice();
        DeviceRequirement.require(gpu != null, "no " + TestBackend.NAME + " device");
        final GlBase<?, ?, ?> gl = TestBackend.newGl(gpu);
        gl.attachOffscreen(64, 64);
        gl.viewport(0, 0, 64, 64);
        return new NativeTestRig(gpu, gl);
    }

    public void resetBindings() {
        gl.activeTexture(GL13.GL_TEXTURE0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.bindVertexArray(0);
    }

    @Override
    public void close() {
        gl.shutdown();
        TestBackend.destroy(gpu);
    }
}
