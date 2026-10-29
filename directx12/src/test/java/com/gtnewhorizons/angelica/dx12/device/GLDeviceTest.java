package com.gtnewhorizons.angelica.dx12.device;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.dx12.device.Gpu;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("dx12-device")
class GLDeviceTest {

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

    private static int[] readPixel(int x, int y) {
        final ByteBuffer px = MemoryUtil.memAlloc(4);
        try {
            gl.readPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            return new int[] {px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF, px.get(3) & 0xFF};
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    private static void assertColor(int[] expected, int[] actual) {
        for (int i = 0; i < 4; i++) {
            assertTrue(Math.abs(expected[i] - actual[i]) <= 2, "channel " + i + ": expected " + java.util.Arrays.toString(expected) + " got " + java.util.Arrays.toString(actual));
        }
    }

    @Test
    void clearIsReadBack() {
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(1f, 0.5f, 0.25f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        assertColor(new int[] {255, 128, 64, 255}, readPixel(10, 10));
    }

    static int program(String vs, String fs) {
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

    static int quadVao(float z) {
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer data = MemoryUtil.memAllocFloat(12);
        data.put(new float[] {-1, -1, z, 1, -1, z, -1, 1, z, 1, 1, z}).flip();
        gl.bufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);
        gl.vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        gl.enableVertexAttribArray(0);
        return vao;
    }

    @Test
    void uniformColoredQuad() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            uniform vec4 color;
            out vec4 fragColor;
            void main() { fragColor = color; }
            """);
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.uniform4f(gl.getUniformLocation(p, "color"), 0f, 1f, 0f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(32, 32));
        gl.uniform4f(gl.getUniformLocation(p, "color"), 0f, 0f, 1f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 0, 255, 255}, readPixel(5, 60));
        gl.useProgram(0);
        gl.bindVertexArray(0);
    }

    @Test
    void textureSampling() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            out vec2 uv;
            void main() { uv = pos.xy * 0.5 + 0.5; gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            uniform sampler2D tex;
            in vec2 uv;
            out vec4 fragColor;
            void main() { fragColor = texture(tex, uv); }
            """);
        final int vao = quadVao(0f);
        final int tex = gl.genTextures();
        gl.activeTexture(GL13.GL_TEXTURE0 + 3);
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final ByteBuffer px = MemoryUtil.memAlloc(16);
        px.put(new byte[] {0, 0, (byte) 255, (byte) 255, 0, (byte) 255, 0, (byte) 255, (byte) 255, 0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255}).flip();
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, px);
        MemoryUtil.memFree(px);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 3);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {255, 0, 0, 255}, readPixel(8, 8));
        assertColor(new int[] {0, 255, 0, 255}, readPixel(56, 8));
        assertColor(new int[] {0, 0, 255, 255}, readPixel(8, 56));
        assertColor(new int[] {255, 255, 255, 255}, readPixel(56, 56));
        gl.activeTexture(GL13.GL_TEXTURE0);
        gl.useProgram(0);
    }

    @Test
    void moreSamplersThanTheStageLimit() {
        final int fetched = 20, filtered = 4;
        final StringBuilder fs = new StringBuilder("#version 330 core\nout vec4 fragColor;\n");
        final StringBuilder sum = new StringBuilder("vec4 acc = vec4(0.0);\n");
        for (int i = 0; i < fetched; i++) {
            fs.append("uniform sampler2D f").append(i).append(";\n");
            sum.append("acc.r += texelFetch(f").append(i).append(", ivec2(0), 0).r;\n");
        }
        for (int i = 0; i < filtered; i++) {
            fs.append("uniform sampler2D s").append(i).append(";\n");
            sum.append("acc.g += texture(s").append(i).append(", vec2(0.5)).g;\n");
        }
        sum.append("acc.b = texelFetch(f").append(fetched - 1).append(", ivec2(0), 0).b;\n");
        fs.append("void main() {\n").append(sum).append("fragColor = vec4(acc.rgb, 1.0);\n}\n");
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, fs.toString());

        final int vao = quadVao(0f);
        gl.useProgram(p);
        final ByteBuffer px = MemoryUtil.memAlloc(4);
        for (int unit = 0; unit < fetched + filtered; unit++) {
            final boolean isFetched = unit < fetched;
            final int tex = gl.genTextures();
            gl.activeTexture(GL13.GL_TEXTURE0 + unit);
            gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
            gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            px.clear();
            px.put((byte) (isFetched ? 10 : 0)).put((byte) (isFetched ? 0 : 50)).put((byte) (unit == fetched - 1 ? 255 : 0)).put((byte) 255).flip();
            gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            final String name = isFetched ? "f" + unit : "s" + (unit - fetched);
            final int loc = gl.getUniformLocation(p, name);
            assertTrue(loc >= 0, name + " has no location");
            gl.uniform1i(loc, unit);
        }
        MemoryUtil.memFree(px);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {fetched * 10, filtered * 50, 255, 255}, readPixel(32, 32));
        gl.activeTexture(GL13.GL_TEXTURE0);
        gl.useProgram(0);
        gl.bindVertexArray(0);
    }

    private static final String SAMPLE_VS = """
        #version 330 core
        in vec3 pos;
        void main() { gl_Position = vec4(pos, 1.0); }
        """;
    private static final String SAMPLE_FS = """
        #version 330 core
        uniform sampler2D tex;
        out vec4 fragColor;
        void main() { fragColor = texelFetch(tex, ivec2(0), 0); }
        """;

    private static long stat(String name) {
        return statMatching(name + "=(\\d+)");
    }

    private static long statMatching(String regex) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(gl.stats());
        assertTrue(m.find(), regex + " missing from " + gl.stats());
        return Long.parseLong(m.group(1));
    }

    private static int solidTexture(int rgba) {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer px = MemoryUtil.memAlloc(4).putInt(0, rgba);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        MemoryUtil.memFree(px);
        return tex;
    }

    @Test
    void uploadBetweenDrawsRunsAheadWithoutBreakingThePass() {
        final int p = program(SAMPLE_VS, SAMPLE_FS);
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 0);
        final int other = solidTexture(0xFF00FFFF);
        final int updated = solidTexture(0xFF0000FF);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {255, 0, 0, 255}, readPixel(32, 32));

        gl.bindTexture(GL11.GL_TEXTURE_2D, other);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final long passes = stat("passes"), hoisted = statMatching("(\\d+) hoisted\\)");
        gl.bindTexture(GL11.GL_TEXTURE_2D, updated);
        final ByteBuffer green = MemoryUtil.memAlloc(4).putInt(0, 0xFF00FFFF);
        gl.texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, green);
        green.putInt(0, 0xFF00FF00);
        gl.texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, green);
        MemoryUtil.memFree(green);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertEquals(passes, stat("passes"), "the upload ended the render pass");
        assertEquals(hoisted + 2, statMatching("(\\d+) hoisted\\)"), "the uploads were not hoisted");
        assertColor(new int[] {0, 255, 0, 255}, readPixel(32, 32));

        final ByteBuffer blue = MemoryUtil.memAlloc(4).putInt(0, 0xFFFF0000);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, blue);
        MemoryUtil.memFree(blue);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 0, 255, 255}, readPixel(32, 32));
        gl.deleteTextures(other);
        gl.deleteTextures(updated);
        gl.useProgram(0);
    }

    @Test
    void drawingContinuesAfterPresentDiscardsDepth() {
        final int p = program(SAMPLE_VS, SAMPLE_FS);
        final int vao = quadVao(0.5f);
        final int tex = solidTexture(0xFFFFFF00);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 0);
        gl.enable(GL11.GL_DEPTH_TEST);
        final long discards = stat("depthDiscards");
        for (int frame = 0; frame < 3; frame++) {
            gl.clearColor(0f, 0f, 0f, 1f);
            gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            gl.present();
        }
        if (gpu.isApple() && !SystemProperties.VULKAN_DEBUG) assertEquals(discards + 3, stat("depthDiscards"));
        gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 255, 255}, readPixel(32, 32));
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.deleteTextures(tex);
        gl.useProgram(0);
    }

    @Test
    void mappingABusyStaticBufferKeepsPendingWrites() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        final FloatBuffer seven = MemoryUtil.memAllocFloat(1).put(0, 7f);
        gl.bufferSubData(GL15.GL_ARRAY_BUFFER, 0, seven);
        MemoryUtil.memFree(seven);
        final ByteBuffer mapped = gl.mapBufferRange(GL15.GL_ARRAY_BUFFER, 16, 4, GL30.GL_MAP_WRITE_BIT);
        mapped.putFloat(0, 9f);
        gl.unmapBuffer(GL15.GL_ARRAY_BUFFER);

        final FloatBuffer back = MemoryUtil.memAllocFloat(12);
        gl.getBufferSubData(GL15.GL_ARRAY_BUFFER, 0, back);
        assertEquals(7f, back.get(0), "glBufferSubData before the map was lost");
        assertEquals(9f, back.get(4), "the mapped write was lost");
        assertEquals(1f, back.get(3), "untouched data changed");
        MemoryUtil.memFree(back);
        gl.useProgram(0);
    }

    @Test
    void overlappingSubUploadsInOneFrameLandInOrder() {
        overlappingSubUploads(false);
    }

    @Test
    void overlappingSubUploadsAfterAClearLandInOrder() {
        overlappingSubUploads(true);
    }

    private static void overlappingSubUploads(boolean clearFirst) {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int fbo = 0;
        if (clearFirst) {
            fbo = gl.genFramebuffers();
            gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            gl.clearColor(0f, 0f, 0f, 1f);
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
        final ByteBuffer tile = MemoryUtil.memAlloc(2 * 2 * 4);
        for (int[] write : new int[][] {{0, 0, 0xFF0000FF}, {1, 1, 0xFF00FF00}, {3, 3, 0xFFFF0000}}) {
            final int size = write[0] == 3 ? 1 : 2;
            for (int i = 0; i < size * size; i++) tile.putInt(i * 4, write[2]);
            tile.limit(size * size * 4);
            gl.texSubImage2D(GL11.GL_TEXTURE_2D, 0, write[0], write[1], size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, tile);
            tile.clear();
        }
        MemoryUtil.memFree(tile);
        final IntBuffer out = MemoryUtil.memAllocInt(16);
        gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        assertEquals(0xFF0000FF, out.get(0), "first tile");
        assertEquals(0xFF00FF00, out.get(4 + 1), "the later overlapping tile must win");
        assertEquals(0xFF00FF00, out.get(4 * 2 + 2), "second tile");
        assertEquals(0xFFFF0000, out.get(4 * 3 + 3), "disjoint tile");
        MemoryUtil.memFree(out);
        if (fbo != 0) gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void copyIntoABufferAlreadyDrawnThisFrameLandsAfterThatDraw() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            layout(location = 1) in vec4 color;
            out vec4 c;
            void main() { gl_Position = vec4(pos, 1.0); c = color; }
            """, """
            #version 330 core
            in vec4 c;
            out vec4 o;
            void main() { o = c; }
            """);
        final int vao = quadVao(0f);
        final int colors = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, colors);
        final FloatBuffer red = MemoryUtil.memAllocFloat(16);
        for (int i = 0; i < 4; i++) red.put(1f).put(0f).put(0f).put(1f);
        gl.bufferData(GL15.GL_ARRAY_BUFFER, red.flip(), GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(red);
        gl.vertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 16, 0L);
        gl.enableVertexAttribArray(1);
        final int green = gl.genBuffers();
        gl.bindBuffer(GL31.GL_COPY_READ_BUFFER, green);
        final FloatBuffer g = MemoryUtil.memAllocFloat(16);
        for (int i = 0; i < 4; i++) g.put(0f).put(1f).put(0f).put(1f);
        gl.bufferData(GL31.GL_COPY_READ_BUFFER, g.flip(), GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(g);

        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.viewport(0, 0, 32, 64);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.bindBuffer(GL31.GL_COPY_WRITE_BUFFER, colors);
        gl.copyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, 64);
        gl.viewport(32, 0, 32, 64);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.viewport(0, 0, 64, 64);

        assertColor(new int[] {255, 0, 0, 255}, readPixel(10, 32));
        assertColor(new int[] {0, 255, 0, 255}, readPixel(50, 32));
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.deleteVertexArrays(vao);
        gl.deleteBuffers(colors);
        gl.deleteBuffers(green);
        gl.deleteProgram(p);
    }

    @Test
    void subDataOnAPersistentBufferLandsAfterAPendingGpuCopy() {
        final int src = gl.genBuffers();
        gl.bindBuffer(GL31.GL_COPY_READ_BUFFER, src);
        final FloatBuffer three = MemoryUtil.memAllocFloat(4).put(new float[] {3f, 3f, 3f, 3f}).flip();
        gl.bufferData(GL31.GL_COPY_READ_BUFFER, three, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(three);

        final int dst = gl.genBuffers();
        gl.bindBuffer(GL31.GL_COPY_WRITE_BUFFER, dst);
        gl.bufferStorage(GL31.GL_COPY_WRITE_BUFFER, 16, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT | GL44.GL_DYNAMIC_STORAGE_BIT);
        assertTrue(gl.mapBufferRange(GL31.GL_COPY_WRITE_BUFFER, 0, 16, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT) != null);

        gl.copyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, 16);
        final FloatBuffer eight = MemoryUtil.memAllocFloat(1).put(0, 8f);
        gl.bufferSubData(GL31.GL_COPY_WRITE_BUFFER, 4, eight);
        MemoryUtil.memFree(eight);

        final FloatBuffer back = MemoryUtil.memAllocFloat(4);
        gl.getBufferSubData(GL31.GL_COPY_WRITE_BUFFER, 0, back);
        assertEquals(3f, back.get(0), "the copy did not land");
        assertEquals(8f, back.get(1), "glBufferSubData was overwritten by the earlier copy");
        MemoryUtil.memFree(back);
        gl.unmapBuffer(GL31.GL_COPY_WRITE_BUFFER);
        gl.deleteBuffers(src);
        gl.deleteBuffers(dst);
    }

    @Test
    void disabledClipPlanesDoNotClip() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); gl_ClipDistance[0] = -1.0; }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(32, 32));

        gl.enable(GL30.GL_CLIP_DISTANCE0);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 0, 0, 255}, readPixel(32, 32));
        gl.disable(GL30.GL_CLIP_DISTANCE0);
        gl.useProgram(0);
    }

    @Test
    void depthCopiesIntoADifferentDepthFormat() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        final int vao = quadVao(0.5f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.enable(GL11.GL_DEPTH_TEST);
        gl.depthFunc(GL11.GL_ALWAYS);
        gl.clearDepth(1.0);
        gl.clear(GL11.GL_DEPTH_BUFFER_BIT);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.copyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 0, 0, 64, 64, 0);
        final ByteBuffer px = MemoryUtil.memAlloc(64 * 64 * 4);
        gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, px);
        final float depth = px.getFloat((32 * 64 + 32) * 4);
        MemoryUtil.memFree(px);
        assertTrue(Math.abs(depth - 0.75f) < 0.01f, "copied depth " + depth + ", expected 0.75");
        gl.depthFunc(GL11.GL_LESS);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.deleteTextures(tex);
        gl.useProgram(0);
    }

    @Test
    void framebufferObjectAndDepth() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            uniform vec4 color;
            out vec4 fragColor;
            void main() { fragColor = color; }
            """);
        final int color = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, color);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 32, 32, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int rbo = gl.genRenderbuffers();
        gl.bindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
        gl.renderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, 32, 32);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        gl.framebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, rbo);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, gl.checkFramebufferStatus(GL30.GL_FRAMEBUFFER));
        gl.viewport(0, 0, 32, 32);
        gl.clearColor(0f, 0f, 0f, 0f);
        gl.clearDepth(1.0);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        gl.enable(GL11.GL_DEPTH_TEST);
        gl.depthFunc(GL11.GL_LESS);
        gl.useProgram(p);
        final int near = quadVao(-0.5f);
        gl.uniform4f(gl.getUniformLocation(p, "color"), 1f, 0f, 0f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final int far = quadVao(0.5f);
        gl.uniform4f(gl.getUniformLocation(p, "color"), 0f, 1f, 0f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {255, 0, 0, 255}, readPixel(16, 16));
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        gl.blitFramebuffer(0, 0, 32, 32, 0, 0, 64, 64, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        assertColor(new int[] {255, 0, 0, 255}, readPixel(40, 40));
        gl.viewport(0, 0, 64, 64);
        gl.useProgram(0);
    }

    @Test
    void persistentMappedStreamingAndIndexedDraw() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0, 1.0, 0.0, 1.0); }
            """);
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final int flags = GL44.GL_MAP_PERSISTENT_BIT | GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_COHERENT_BIT;
        gl.bufferStorage(GL15.GL_ARRAY_BUFFER, 4096, flags);
        final ByteBuffer mapped = gl.mapBufferRange(GL15.GL_ARRAY_BUFFER, 0, 4096, flags);
        mapped.asFloatBuffer().put(new float[] {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0});
        gl.vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        gl.enableVertexAttribArray(0);
        final int ebo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        final ByteBuffer idx = MemoryUtil.memAlloc(12);
        idx.asShortBuffer().put(new short[] {0, 1, 2, 0, 2, 3});
        gl.bufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(idx);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.drawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_SHORT, 0L);
        assertColor(new int[] {255, 255, 0, 255}, readPixel(32, 32));
        gl.useProgram(0);
        gl.bindVertexArray(0);
    }

    @Test
    void uniformBlocksAndMatrices() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            uniform mat4 mvp;
            layout(std140) uniform Tint { vec4 tint; };
            out vec4 c;
            void main() { c = tint; gl_Position = mvp * vec4(pos, 1.0); }
            """, """
            #version 330 core
            in vec4 c;
            out vec4 fragColor;
            void main() { fragColor = c; }
            """);
        final int vao = quadVao(0f);
        final int ubo = gl.genBuffers();
        gl.bindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        final FloatBuffer tint = MemoryUtil.memAllocFloat(4);
        tint.put(new float[] {0f, 1f, 1f, 1f}).flip();
        gl.bufferData(GL31.GL_UNIFORM_BUFFER, tint, GL15.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(tint);
        gl.uniformBlockBinding(p, gl.getUniformBlockIndex(p, "Tint"), 5);
        gl.bindBufferBase(GL31.GL_UNIFORM_BUFFER, 5, ubo);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        final FloatBuffer m = MemoryUtil.memAllocFloat(16);
        m.put(new float[] {0.5f, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}).flip();
        gl.uniformMatrix4(gl.getUniformLocation(p, "mvp"), false, m);
        MemoryUtil.memFree(m);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 255, 255}, readPixel(32, 32));
        assertColor(new int[] {0, 0, 0, 255}, readPixel(4, 32));
        gl.useProgram(0);
    }

    @Test
    void blendingAndCopyTexSubImage() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            uniform vec4 color;
            out vec4 fragColor;
            void main() { fragColor = color; }
            """);
        quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 1f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.enable(GL11.GL_BLEND);
        gl.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        gl.useProgram(p);
        gl.uniform4f(gl.getUniformLocation(p, "color"), 1f, 0f, 0f, 0.5f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.disable(GL11.GL_BLEND);
        assertColor(new int[] {128, 0, 128, 191}, readPixel(20, 20));
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        gl.copyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, 16, 16);
        final ByteBuffer out = MemoryUtil.memAlloc(16 * 16 * 4);
        gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        assertEquals(128, out.get(0) & 0xFF, 2);
        assertEquals(128, out.get(2) & 0xFF, 2);
        MemoryUtil.memFree(out);
        gl.useProgram(0);
        gl.blendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        gl.depthMask(true);
        gl.blendEquation(GL14.GL_FUNC_ADD);
    }

    @Test
    void mainRenameSurvivesLeadingConditionals() {
        final int p = program("""
            #version 330 core

            #ifdef LEGACY
            #extension GL_ARB_explicit_attrib_location : enable
            #endif

            in vec3 pos;
            void main(){ gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0, 0.0, 1.0, 1.0); }
            """);
        quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {255, 0, 255, 255}, readPixel(32, 32));
        gl.useProgram(0);
    }

    @Test
    void computeWrittenIndirectDraw() {
        final int cs = gl.createShader(org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER);
        gl.shaderSource(cs, """
            #version 430 core
            layout(local_size_x = 64) in;
            struct Cmd { uint count; uint instanceCount; uint firstIndex; int baseVertex; uint baseInstance; };
            layout(std430, binding = 2) writeonly buffer Cmds { Cmd cmds[]; } indirect;
            layout(std140, binding = 1) uniform Params { uvec4 control; } params;
            void main() {
                if (gl_GlobalInvocationID.x >= params.control.x) return;
                indirect.cmds[gl_GlobalInvocationID.x] = Cmd(6u, 1u, 0u, 0, 0u);
            }
            """);
        gl.compileShader(cs);
        assertEquals(GL11.GL_TRUE, gl.getShaderi(cs, GL20.GL_COMPILE_STATUS), gl.getShaderInfoLog(cs, 4096));
        final int cp = gl.createProgram();
        gl.attachShader(cp, cs);
        gl.linkProgram(cp);
        assertEquals(GL11.GL_TRUE, gl.getProgrami(cp, GL20.GL_LINK_STATUS), gl.getProgramInfoLog(cp, 4096));

        final int cmdBuf = gl.genBuffers();
        gl.bindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
        gl.bufferData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 20L * 64, GL15.GL_DYNAMIC_DRAW);
        final int ubo = gl.genBuffers();
        gl.bindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        final ByteBuffer ctl = MemoryUtil.memCalloc(16);
        ctl.putInt(0, 1);
        gl.bufferData(GL31.GL_UNIFORM_BUFFER, ctl, GL15.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(ctl);
        gl.useProgram(cp);
        gl.bindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 2, cmdBuf);
        gl.bindBufferBase(GL31.GL_UNIFORM_BUFFER, 1, ubo);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT);
        final ByteBuffer written = MemoryUtil.memAlloc(20);
        gl.bindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
        gl.getBufferSubData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0, written);
        assertEquals(6, written.getInt(0), "compute shader did not write the command");
        MemoryUtil.memFree(written);

        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0, 0.5, 0.0, 1.0); }
            """);
        final int vao = quadVao(0f);
        final int ebo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        final ByteBuffer idx = MemoryUtil.memAlloc(24);
        idx.asIntBuffer().put(new int[] {0, 1, 2, 2, 1, 3});
        gl.bufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(idx);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.bindBuffer(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
        gl.multiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0L, 1, 0);
        gl.bindBuffer(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        assertColor(new int[] {255, 128, 0, 255}, readPixel(32, 32));
        gl.useProgram(0);
    }

    @Test
    void proxyTextureProbe() {
        gl.texImage2D(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_RGBA, 8192, 8192, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(8192, gl.getTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        gl.texImage2D(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_RGBA, 1 << 20, 1 << 20, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, gl.getTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
    }
}
