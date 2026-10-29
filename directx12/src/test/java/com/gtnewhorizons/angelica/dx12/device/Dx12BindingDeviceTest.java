package com.gtnewhorizons.angelica.dx12.device;

import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import com.gtnewhorizons.angelica.dx12.pipeline.RootSignatures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("dx12-device")
class Dx12BindingDeviceTest {

    static Gpu gpu;
    static Dx12GL gl;

    @BeforeAll
    static void setUp() {
        gpu = new Gpu();
        assumeTrue(gpu.create(), "no Direct3D 12 device");
        gl = new Dx12GL(gpu, Thread.currentThread(), null);
        gl.attachOffscreen(64, 64);
        gl.viewport(0, 0, 64, 64);
    }

    @AfterAll
    static void tearDown() {
        if (gl != null) gl.shutdown();
        if (gpu != null) gpu.destroy();
    }

    @Test
    void renderTargetViewsStayInsideTheirHeap() {
        final Descriptors d = gl.descriptors;
        final long first = d.nextRtv();
        long lowest = first, highest = first;
        for (int i = 0; i < 1000; i++) {
            final long run = d.nextRtvRun(1 + i % 8);
            final long single = d.nextRtv();
            lowest = Math.min(lowest, Math.min(run, single));
            highest = Math.max(highest, Math.max(d.rtvAt(run, i % 8), single));
        }
        assertTrue(highest - lowest < 128L * gpu.rtvIncrement, "RTV handles spread over " + (highest - lowest) / gpu.rtvIncrement + " slots of a 128-slot heap");
    }

    @Test
    void constantBuffersAreRootDescriptorsAfterTheirTables() {
        final RootSignatures.Layout l = gl.rootSignatures.graphics(2, 1, 3, 0, 1, 1, 2, 0);
        assertEquals(0, l.param(0, RootSignatures.K_SRV));
        assertEquals(1, l.param(0, RootSignatures.K_SAMPLER));
        assertEquals(2, l.param(0, RootSignatures.K_CBV), "the vertex constant buffers start after the vertex tables");
        assertEquals(5, l.param(1, RootSignatures.K_SRV), "three vertex root CBVs take one parameter each");
        assertEquals(6, l.param(1, RootSignatures.K_SAMPLER));
        assertEquals(7, l.param(1, RootSignatures.K_CBV));
        assertEquals(-1, l.param(0, RootSignatures.K_UAV));
    }

    @Test
    void theLargestLayoutFitsTheRootSignatureBudget() {
        final int max = RootSignatures.MAX_CBV;
        final RootSignatures.Layout l = gl.rootSignatures.graphics(RootSignatures.MAX_SRV, RootSignatures.MAX_SAMPLER, max, RootSignatures.MAX_UAV,
            RootSignatures.MAX_SRV, RootSignatures.MAX_SAMPLER, max, RootSignatures.MAX_UAV);
        assertNotEquals(0L, l.root(), "2 x 14 root CBVs and 8 tables must serialize within 64 DWORDs");
    }

    private static int program(String vs, String fs) {
        final int v = gl.createShader(GL20.GL_VERTEX_SHADER);
        gl.shaderSource(v, vs);
        gl.compileShader(v);
        final int f = gl.createShader(GL20.GL_FRAGMENT_SHADER);
        gl.shaderSource(f, fs);
        gl.compileShader(f);
        final int p = gl.createProgram();
        gl.attachShader(p, v);
        gl.attachShader(p, f);
        gl.bindAttribLocation(p, 0, "pos");
        gl.linkProgram(p);
        assertEquals(GL11.GL_TRUE, gl.getProgrami(p, GL20.GL_LINK_STATUS), gl.getProgramInfoLog(p, 4096));
        return p;
    }

    private static int quadVao() {
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer data = MemoryUtil.memAllocFloat(8);
        data.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).flip();
        gl.bufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);
        gl.vertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
        gl.enableVertexAttribArray(0);
        return vao;
    }

    private static int solidTexture(int r, int g, int b) {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final ByteBuffer px = MemoryUtil.memAlloc(4);
        px.put((byte) r).put((byte) g).put((byte) b).put((byte) 255).flip();
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        MemoryUtil.memFree(px);
        return tex;
    }

    private static int[] center() {
        final ByteBuffer px = MemoryUtil.memAlloc(4);
        try {
            gl.readPixels(32, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            return new int[] {px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF};
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    @Test
    void aRecycledViewHandleIsNotMistakenForTheOldTexture() {
        final int p = program("""
            #version 330 core
            in vec2 pos;
            void main() { gl_Position = vec4(pos, 0.0, 1.0); }
            """, """
            #version 330 core
            uniform sampler2D tex;
            out vec4 color;
            void main() { color = texture(tex, vec2(0.5)); }
            """);
        final int vao = quadVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 0);
        final int red = solidTexture(255, 0, 0);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.deleteTextures(red);
        final int green = solidTexture(0, 255, 0);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final int[] got = center();
        assertEquals(0, got[0], "red channel");
        assertEquals(255, got[1], "green channel");
        gl.deleteTextures(green);
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.deleteVertexArrays(vao);
    }

    private static int[] pixel(int x, int y) {
        final ByteBuffer px = MemoryUtil.memAlloc(4);
        try {
            gl.readPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            return new int[] {px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF};
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    @Test
    void indirectCountDrawsOnlyTheCountedCommands() {
        final int p = program("""
            #version 330 core
            in vec2 pos;
            void main() { gl_Position = vec4(pos, 0.0, 1.0); }
            """, """
            #version 330 core
            out vec4 color;
            void main() { color = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer quads = MemoryUtil.memAllocFloat(16);
        quads.put(new float[] {-1, -1, 0, -1, -1, 1, 0, 1, 0, -1, 1, -1, 0, 1, 1, 1}).flip();
        gl.bufferData(GL15.GL_ARRAY_BUFFER, quads, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(quads);
        gl.vertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
        gl.enableVertexAttribArray(0);

        final int commands = gl.genBuffers();
        gl.bindBuffer(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, commands);
        final java.nio.IntBuffer cmd = MemoryUtil.memAllocInt(8);
        cmd.put(new int[] {4, 1, 0, 0, 4, 1, 4, 0}).flip();
        gl.bufferData(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, cmd, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(cmd);
        final int count = gl.genBuffers();
        gl.bindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, count);
        final java.nio.IntBuffer one = MemoryUtil.memAllocInt(1);
        one.put(1).flip();
        gl.bufferData(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, one, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(one);

        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.multiDrawArraysIndirectCount(GL11.GL_TRIANGLE_STRIP, 0L, 0L, 2, 0);
        assertEquals(255, pixel(16, 32)[1], "the first command (left half) runs");
        assertEquals(0, pixel(48, 32)[1], "the count buffer says 1, so the second command (right half) must not run");

        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.deleteVertexArrays(vao);
        gl.deleteBuffers(vbo);
        gl.deleteBuffers(commands);
        gl.deleteBuffers(count);
    }

    @Test
    void aChangedUniformReachesTheNextDrawThroughTheCachedTables() {
        final int p = program("""
            #version 330 core
            in vec2 pos;
            void main() { gl_Position = vec4(pos, 0.0, 1.0); }
            """, """
            #version 330 core
            uniform sampler2D tex;
            uniform vec4 tint;
            out vec4 color;
            void main() { color = texture(tex, vec2(0.5)) * tint; }
            """);
        final int vao = quadVao();
        final int white = solidTexture(255, 255, 255);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 0);
        final int tint = gl.getUniformLocation(p, "tint");
        gl.uniform4f(tint, 1f, 0f, 0f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.uniform4f(tint, 0f, 0f, 1f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final int[] got = center();
        assertEquals(0, got[0], "red channel");
        assertEquals(255, got[2], "blue channel");
        gl.deleteTextures(white);
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.deleteVertexArrays(vao);
    }
}
