package com.gtnewhorizons.angelica.glemu.nativetest.device;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.nativetest.NativeTestRig;
import com.gtnewhorizons.angelica.glemu.nativetest.TestBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBTextureCompressionBPTC;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("native-device")
class GLDeviceTest {

    static NativeTestRig rig;
    static GpuCaps gpu;
    static GlBase<?, ?, ?> gl;

    @BeforeAll
    static void setUp() {
        rig = NativeTestRig.create();
        gpu = rig.gpu;
        gl = rig.gl;
    }

    @AfterAll
    static void tearDown() {
        if (rig != null) rig.close();
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
        return NativeTestRig.program(gl, vs, fs);
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
    void fragmentOnlyProgramUsesFixedFunctionVertexStage() {
        final int f = gl.createShader(GL20.GL_FRAGMENT_SHADER);
        gl.shaderSource(f, """
            #version 330 core
            in vec4 angelica_FrontColor;
            out vec4 fragColor;
            void main() { fragColor = angelica_FrontColor; }
            """);
        gl.compileShader(f);
        assertEquals(GL11.GL_TRUE, gl.getShaderi(f, GL20.GL_COMPILE_STATUS), gl.getShaderInfoLog(f, 4096));
        final int p = gl.createProgram();
        gl.attachShader(p, f);
        gl.linkProgram(p);
        assertEquals(GL11.GL_TRUE, gl.getProgrami(p, GL20.GL_LINK_STATUS), gl.getProgramInfoLog(p, 4096));
        assertTrue(gl.getUniformLocation(p, "angelica_ModelViewMatrix") >= 0, "the generated vertex stage exposes the compat matrices");

        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        final FloatBuffer identity = MemoryUtil.memAllocFloat(16);
        try {
            identity.put(new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}).flip();
            gl.uniformMatrix4(gl.getUniformLocation(p, "angelica_ModelViewMatrix"), false, identity);
            gl.uniformMatrix4(gl.getUniformLocation(p, "angelica_ProjectionMatrix"), false, identity);
        } finally {
            MemoryUtil.memFree(identity);
        }
        gl.vertexAttrib4f(1, 0f, 0f, 1f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 0, 255, 255}, readPixel(32, 32));
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.deleteVertexArrays(vao);
        gl.deleteProgram(p);
        gl.deleteShader(f);
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
        if (TestBackend.discardsDepthOnPresent(gpu)) assertEquals(discards + 3, stat("depthDiscards"));
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
    void copyIntoABufferAlreadyReadThisFrameIsVisibleToThatDraw() {
        // Early uploads hoist the copy ahead of the frame, so both draws see it; GL would leave the first draw red.
        assumeTrue(SystemProperties.NATIVE_EARLY_UPLOADS, "early uploads are off");
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

        assertColor(new int[] {0, 255, 0, 255}, readPixel(10, 32));
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

    private static float depthOf(int texture) {
        final ByteBuffer px = MemoryUtil.memAlloc(16 * 16 * 4);
        try {
            gl.bindTexture(GL11.GL_TEXTURE_2D, texture);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, px);
            return px.getFloat(0);
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    @Test
    void depth32fStencil8UploadsKeepTheirRows() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer in = MemoryUtil.memCalloc(2 * 2 * 8), out = MemoryUtil.memAlloc(2 * 2 * 4);
        try {
            final float[] depths = {0.1f, 0.2f, 0.3f, 0.4f};
            for (int i = 0; i < 4; i++) in.putFloat(i * 8, depths[i]);
            gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH32F_STENCIL8, 2, 2, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, in);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, out);
            for (int i = 0; i < 4; i++) assertEquals(depths[i], out.getFloat(i * 4), 0.0001f, "texel " + i);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(tex);
    }

    @Test
    void textureParametersReadBackByNameAndBinding() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, 2, 2, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
        gl.texParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 1.5f);
        gl.texParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 2.5f);
        gl.texParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.5f);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_STENCIL_INDEX);
        assertEquals(1.5f, gl.getTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD));
        assertEquals(1.5f, gl.getTextureParameterf(tex, GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD));
        assertEquals(2.5f, gl.getTextureParameterf(tex, GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD));
        assertEquals(0.5f, gl.getTextureParameterf(tex, GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS));
        assertEquals(GL11.GL_STENCIL_INDEX, gl.getTextureParameteri(tex, GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE));
        gl.deleteTextures(tex);
    }

    @Test
    void indexedQueriesFollowTheCurrentState() {
        gl.colorMask(false, true, true, true);
        assertEquals(GL11.GL_FALSE, gl.getIntegerIndexed(GL30.GL_COLOR_WRITEMASK, 0), "one value is the red flag");
        gl.colorMask(true, true, true, true);
        assertEquals(GL11.GL_TRUE, gl.getIntegerIndexed(GL30.GL_COLOR_WRITEMASK, 0));
        assertEquals(GL11.GL_BACK, gl.getIntegerIndexed(GL20.GL_DRAW_BUFFER0, 0));
        final int color = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, color);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        assertEquals(GL30.GL_COLOR_ATTACHMENT0, gl.getIntegerIndexed(GL20.GL_DRAW_BUFFER0, 0));
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(color);
    }

    @Test
    void aDeferredStencilClearLeavesFreshDepthAtItsDefault() {
        final int ds = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, ds);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, 16, 16, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, ds, 0);
        gl.clearStencil(3);
        gl.clear(GL11.GL_STENCIL_BUFFER_BIT);
        assertEquals(1f, depthOf(ds), 0.001f);
        gl.clearStencil(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(ds);
    }

    @Test
    void aDeferredDepthClearKeepsTheStencilContents() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int color = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, color);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int ds = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, ds);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, 16, 16, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, ds, 0);
        gl.viewport(0, 0, 16, 16);
        gl.clearStencil(3);
        gl.clear(GL11.GL_STENCIL_BUFFER_BIT);
        assertEquals(1f, depthOf(ds), 0.001f);
        gl.clearDepth(0.25);
        gl.clear(GL11.GL_DEPTH_BUFFER_BIT);
        assertEquals(0.25f, depthOf(ds), 0.001f);

        gl.clearColor(0f, 0f, 0f, 0f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.enable(GL11.GL_STENCIL_TEST);
        gl.stencilFunc(GL11.GL_EQUAL, 3, 0xFF);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(8, 8));

        gl.disable(GL11.GL_STENCIL_TEST);
        gl.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        gl.clearStencil(0);
        gl.clearDepth(1.0);
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.viewport(0, 0, 64, 64);
        gl.deleteVertexArrays(vao);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(ds);
        gl.deleteTextures(color);
    }

    @Test
    void aFreshAttachmentStartsAsTransparentBlack() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int color = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, color);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        gl.viewport(0, 0, 16, 16);
        gl.enable(GL11.GL_SCISSOR_TEST);
        gl.scissor(0, 0, 4, 4);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.disable(GL11.GL_SCISSOR_TEST);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(2, 2));
        assertColor(new int[] {0, 0, 0, 0}, readPixel(8, 8));
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.viewport(0, 0, 64, 64);
        gl.deleteVertexArrays(vao);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(color);
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
    void byteIndicesFromAnElementBufferDraw() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 1.0, 1.0); }
            """);
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final ByteBuffer vertices = MemoryUtil.memAlloc(48);
        vertices.asFloatBuffer().put(new float[] {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0});
        gl.bufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(vertices);
        gl.vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        gl.enableVertexAttribArray(0);
        final int ebo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        final ByteBuffer indices = MemoryUtil.memAlloc(6);
        indices.put(new byte[] {0, 1, 2, 0, 2, 3}).flip();
        gl.bufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(indices);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);

        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.drawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_BYTE, 0L);
        assertColor(new int[] {0, 255, 255, 255}, readPixel(32, 32));

        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer counts = stack.ints(3, 3);
            final PointerBuffer offsets = stack.pointers(0L, 3L);
            gl.multiDrawElementsBaseVertex(GL11.GL_TRIANGLES, MemoryUtil.memAddress(counts), GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(offsets), 2, 0L);
        }
        assertColor(new int[] {0, 255, 255, 255}, readPixel(48, 16));
        assertColor(new int[] {0, 255, 255, 255}, readPixel(16, 48));
        gl.useProgram(0);
        gl.bindVertexArray(0);
    }

    @Test
    void theDefaultFramebufferIgnoresTheLastFboAttachmentLevel() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 8, 8, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 1);
        gl.clearColor(1f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 1f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(32, 32));
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void aScaledBlitBetweenLevelsOfOneTextureReadsTheSource() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 2, GL11.GL_RGBA8, 4, 4);
        final int read = gl.genFramebuffers(), draw = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, read);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.clearColor(1f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        gl.framebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 1);
        gl.blitFramebuffer(0, 0, 4, 4, 0, 0, 2, 2, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer out = MemoryUtil.memAlloc(2 * 2 * 4);
        try {
            gl.getTexImage(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(255, out.get(0) & 0xFF, "level 1 is red");
            assertEquals(255, out.get(3) & 0xFF, "level 1 is opaque");
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.deleteFramebuffers(read);
        gl.deleteFramebuffers(draw);
        gl.deleteTextures(tex);
    }

    @Test
    void aFailedRelinkLeavesTheProgramSafeToRelinkAndDelete() {
        final int p = program(SAMPLE_VS, SAMPLE_FS);
        final int broken = gl.createShader(GL20.GL_FRAGMENT_SHADER);
        gl.shaderSource(broken, "#version 330 core\nvoid main() { undefinedName = 1; }\n");
        gl.compileShader(broken);
        gl.attachShader(p, broken);
        gl.linkProgram(p);
        assertEquals(GL11.GL_FALSE, gl.getProgrami(p, GL20.GL_LINK_STATUS));
        gl.detachShader(p, broken);
        gl.linkProgram(p);
        assertEquals(GL11.GL_TRUE, gl.getProgrami(p, GL20.GL_LINK_STATUS), gl.getProgramInfoLog(p, 4096));
        gl.deleteProgram(p);
        gl.deleteShader(broken);
    }

    @Test
    void aCopyBetweenLevelsOfOneTextureKeepsBoth() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 2, GL11.GL_RGBA8, 4, 4);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.clearColor(1f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.copyImageSubData(tex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, tex, GL11.GL_TEXTURE_2D, 1, 0, 0, 0, 2, 2, 1);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4 * 4);
        try {
            for (int level = 0; level < 2; level++) {
                gl.getTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
                assertEquals(255, out.get(0) & 0xFF, "level " + level + " is red");
                assertEquals(255, out.get(3) & 0xFF, "level " + level + " is opaque");
            }
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void specifyingALevelOfAnOddSizedTextureKeepsTheImage() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer base = MemoryUtil.memAlloc(5 * 5 * 4);
        final ByteBuffer out = MemoryUtil.memAlloc(5 * 5 * 4);
        try {
            for (int i = 0; i < 5 * 5; i++) base.putInt(i * 4, 0xFF0000FF);
            gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 5, 5, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, base);
            gl.texImage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, base);
            assertEquals(5, gl.getTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(0xFF0000FF, out.getInt(4 * 4 * 5 + 4 * 4), "the last texel of level 0");
        } finally {
            MemoryUtil.memFree(base);
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(tex);
    }

    @Test
    void aCubeMapArrayKeepsEveryLayerItWasGiven() {
        final int layers = 12;
        final int tex = gl.genTextures();
        gl.bindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, tex);
        final ByteBuffer in = MemoryUtil.memAlloc(layers * 4).order(ByteOrder.nativeOrder());
        final ByteBuffer out = MemoryUtil.memAlloc(layers * 4).order(ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < layers; i++) in.putInt(i * 4, 0xFF000000 | i * 20);
            gl.texImage3D(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, GL11.GL_RGBA8, 1, 1, layers, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, in);
            gl.getTexImage(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            for (int i = 0; i < layers; i++) assertEquals(in.getInt(i * 4), out.getInt(i * 4), "layer " + i);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(tex);
    }

    @Test
    void aRectangleTextureIsSampledInTexels() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            uniform sampler2DRect tex;
            out vec4 fragColor;
            void main() {
                float red = min(texture(tex, vec2(2.5, 0.5)).r, textureProj(tex, vec3(5.0, 1.0, 2.0)).r);
                fragColor = vec4(red, texelFetch(tex, ivec2(0, 1)).g, textureSize(tex) == ivec2(4, 2) ? 1.0 : 0.0, 1.0);
            }
            """);
        final int tex = gl.genTextures();
        gl.bindTexture(GL31.GL_TEXTURE_RECTANGLE, tex);
        gl.texParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.texParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final ByteBuffer px = MemoryUtil.memCalloc(4 * 2 * 4);
        try {
            px.put(2 * 4, (byte) 255).put(2 * 4 + 3, (byte) 255);
            px.put((4 + 0) * 4 + 1, (byte) 255);
            gl.texImage2D(GL31.GL_TEXTURE_RECTANGLE, 0, GL11.GL_RGBA8, 4, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        } finally {
            MemoryUtil.memFree(px);
        }
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.disable(GL11.GL_BLEND);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.useProgram(p);
        gl.bindVertexArray(vao);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {255, 255, 255, 255}, readPixel(32, 32));
        gl.useProgram(0);
        gl.bindTexture(GL31.GL_TEXTURE_RECTANGLE, 0);
        gl.deleteTextures(tex);
    }

    @Test
    void anSrgbAttachmentOnlyEncodesWhileFramebufferSrgbIsOn() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.5, 0.5, 0.5, 1.0); }
            """);
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL21.GL_SRGB8_ALPHA8, 4, 4);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.viewport(0, 0, 4, 4);
        gl.disable(GL11.GL_BLEND);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4 * 4);
        try {
            gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(128, out.get(0) & 0xFF, 2, "off writes the shader's value as it is");

            gl.enable(GL30.GL_FRAMEBUFFER_SRGB);
            gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(188, out.get(0) & 0xFF, 2, "on encodes it");
        } finally {
            gl.disable(GL30.GL_FRAMEBUFFER_SRGB);
            MemoryUtil.memFree(out);
        }
        gl.useProgram(0);
        gl.viewport(0, 0, 64, 64);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void aDrawnClearKeepsACurrentProgramThatIsPendingDeletion() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.disable(GL11.GL_BLEND);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.useProgram(p);
        gl.deleteProgram(p);
        gl.clearColor(1f, 0f, 0f, 1f);
        gl.colorMask(true, true, true, false);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.colorMask(true, true, true, true);
        gl.bindVertexArray(vao);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final int[] center = readPixel(32, 32);
        assertEquals(0, center[0], "the program still draws");
        assertEquals(255, center[1], "the program still draws");
        gl.useProgram(0);
    }

    @Test
    void aOneElementArrayUniformIsFoundByItsElementName() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            uniform vec4 values[1];
            out vec4 fragColor;
            void main() { fragColor = values[0]; }
            """);
        assertTrue(gl.getUniformLocation(p, "values") >= 0);
        assertEquals(gl.getUniformLocation(p, "values"), gl.getUniformLocation(p, "values[0]"));
        gl.deleteProgram(p);
    }

    @Test
    void changingDrawBuffersDrawsToTheNewAttachment() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.25, 0.5, 0.75, 1.0); }
            """);
        final int plain = gl.genTextures(), wide = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, plain);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 4, 4);
        gl.bindTexture(GL11.GL_TEXTURE_2D, wide);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, 4, 4);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, plain, 0);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, wide, 0);
        gl.viewport(0, 0, 4, 4);
        gl.disable(GL11.GL_BLEND);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        gl.drawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.drawBuffer(GL30.GL_COLOR_ATTACHMENT1);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4 * 16).order(ByteOrder.nativeOrder());
        try {
            gl.bindTexture(GL11.GL_TEXTURE_2D, wide);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, out);
            assertEquals(0.5f, out.getFloat(4), 0.01f, "the second attachment was drawn");
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.useProgram(0);
        gl.viewport(0, 0, 64, 64);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(plain);
        gl.deleteTextures(wide);
    }

    @Test
    void blendingOnAFormatThatCannotBlendStillDraws() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.25, 0.5, 0.75, 1.0); }
            """);
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA32F, 4, 4);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.viewport(0, 0, 4, 4);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.enable(GL11.GL_BLEND);
        gl.blendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4 * 16).order(ByteOrder.nativeOrder());
        try {
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, out);
            assertEquals(0.5f, out.getFloat(4), 0.01f);
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.disable(GL11.GL_BLEND);
        gl.useProgram(0);
        gl.viewport(0, 0, 64, 64);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void packedDepthStencilUploadsKeepEveryRow() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer in = MemoryUtil.memAlloc(4 * 4).order(ByteOrder.nativeOrder());
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4).order(ByteOrder.nativeOrder());
        try {
            final float[] depths = {0.25f, 0.25f, 0.75f, 0.75f};
            for (int i = 0; i < 4; i++) in.putInt(i * 4, Math.round(depths[i] * 0xFFFFFF) << 8);
            gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, 2, 2, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, in);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, out);
            for (int i = 0; i < 4; i++) assertEquals(depths[i], out.getFloat(i * 4), 0.001f, "texel " + i);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(tex);
    }

    /** Four corners with the flat values 0, 0.25, 0.5 and 1, drawn as the given mode, checked at each edge of the target. */
    private void assertFlatQuad(int mode, float[] xy, int expectedRed, String what) {
        final int[][] points = {{12, 32}, {52, 32}, {32, 12}, {32, 52}};
        final int[] expected = new int[points.length];
        Arrays.fill(expected, expectedRed);
        assertFlatValues(mode, xy, points, expected, what);
    }

    private void assertFlatValues(int mode, float[] xy, int[][] points, int[] expectedRed, String what) {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec2 pos;
            layout(location = 1) in float value;
            flat out float flatValue;
            void main() { flatValue = value; gl_Position = vec4(pos, 0.0, 1.0); }
            """, """
            #version 330 core
            flat in float flatValue;
            out vec4 fragColor;
            void main() { fragColor = vec4(flatValue, 0.0, 0.0, 1.0); }
            """);
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer data = MemoryUtil.memAllocFloat(12);
        final float[] values = {0f, 0.25f, 0.5f, 1f};
        for (int i = 0; i < 4; i++) data.put(xy[i * 2]).put(xy[i * 2 + 1]).put(values[i]);
        data.flip();
        gl.bufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);
        gl.vertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 12, 0L);
        gl.vertexAttribPointer(1, 1, GL11.GL_FLOAT, false, 12, 8L);
        gl.enableVertexAttribArray(0);
        gl.enableVertexAttribArray(1);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.drawArrays(mode, 0, 4);
        for (int i = 0; i < points.length; i++) {
            assertEquals(expectedRed[i], readPixel(points[i][0], points[i][1])[0], 1, what + " at " + points[i][0] + "," + points[i][1]);
        }
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.deleteVertexArrays(vao);
        gl.deleteBuffers(vbo);
    }

    private static final float[] AROUND = {-1, -1, 1, -1, 1, 1, -1, 1};
    private static final float[] STRIP = {-1, -1, 1, -1, -1, 1, 1, 1};

    private static int smallTarget(int internalFormat) {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, internalFormat, 8, 8);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.viewport(0, 0, 8, 8);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        return fbo;
    }

    private static void closeSmallTarget(int fbo) {
        gl.useProgram(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        final int tex = gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.viewport(0, 0, 64, 64);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    private static int solidProgram(String color) {
        return program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(%s, 1.0); }
            """.formatted(color));
    }

    private static int smallDepthTexture() {
        final int depth = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, depth);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_DEPTH_COMPONENT32F, 8, 8);
        return depth;
    }

    private static void assertQuadDraws(int program, int... expected) {
        final int fbo = smallTarget(GL11.GL_RGBA8);
        final int vao = quadVao(0f);
        gl.useProgram(program);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(expected, readPixel(4, 4));
        closeSmallTarget(fbo);
        gl.deleteVertexArrays(vao);
    }

    private static int pointVao() {
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer data = MemoryUtil.memAllocFloat(3);
        data.put(new float[] {0f, 0f, 0f}).flip();
        gl.bufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);
        gl.vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        gl.enableVertexAttribArray(0);
        return vao;
    }

    @Test
    void aClearThroughAnotherTargetStaysBehindTheNextDraw() {
        final int p = solidProgram("0.0, 1.0, 0.0");
        final int vao = quadVao(0f);
        final int withDepth = smallTarget(GL11.GL_RGBA8);
        final int color = gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        final int depth = smallDepthTexture();
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        gl.useProgram(p);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        final int colorOnly = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, colorOnly);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        gl.clearColor(1f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.clearColor(0f, 0f, 0f, 1f);

        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, withDepth);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(4, 4));
        gl.deleteFramebuffers(colorOnly);
        closeSmallTarget(withDepth);
        gl.deleteTextures(depth);
        gl.deleteVertexArrays(vao);
    }

    @Test
    void aDepthClearThroughAnotherTargetStaysBehindTheNextDraw() {
        final int near = solidProgram("0.0, 1.0, 0.0");
        final int far = solidProgram("0.0, 0.0, 1.0");
        final int nearVao = quadVao(-0.5f);
        final int farVao = quadVao(0.5f);
        final int first = smallTarget(GL11.GL_RGBA8);
        final int depth = smallDepthTexture();
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        gl.enable(GL11.GL_DEPTH_TEST);
        gl.clear(GL11.GL_DEPTH_BUFFER_BIT);
        gl.useProgram(near);
        gl.bindVertexArray(nearVao);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        final int second = smallTarget(GL11.GL_RGBA8);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        gl.clear(GL11.GL_DEPTH_BUFFER_BIT);

        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, first);
        gl.useProgram(far);
        gl.bindVertexArray(farVao);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 0, 255, 255}, readPixel(4, 4));
        gl.disable(GL11.GL_DEPTH_TEST);
        closeSmallTarget(first);
        closeSmallTarget(second);
        gl.deleteTextures(depth);
        gl.deleteVertexArrays(nearVao);
        gl.deleteVertexArrays(farVao);
    }

    @Test
    void varyingsWithExplicitLocationsMatchWhateverTheyAreCalled() {
        final int p = program("""
            #version 410 core
            layout(location = 0) in vec3 pos;
            layout(location = 0) out vec4 first;
            layout(location = 1) out vec4 second;
            void main() {
                first = vec4(0.0, 1.0, 0.0, 1.0);
                second = vec4(0.0, 0.0, 1.0, 1.0);
                gl_Position = vec4(pos, 1.0);
            }
            """, """
            #version 410 core
            layout(location = 1) in vec4 fromSecond;
            layout(location = 0) in vec4 fromFirst;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, fromFirst.g, fromSecond.b, 1.0); }
            """);
        assertQuadDraws(p, 0, 255, 255, 255);
    }

    @Test
    void varyingsSharingALocationMatchByComponent() {
        final int p = program("""
            #version 440 core
            layout(location = 0) in vec3 pos;
            layout(location = 0, component = 0) out float first;
            layout(location = 0, component = 1) out float second;
            void main() {
                first = 1.0;
                second = 0.0;
                gl_Position = vec4(pos, 1.0);
            }
            """, """
            #version 440 core
            layout(location = 0, component = 1) in float fromSecond;
            layout(location = 0, component = 0) in float fromFirst;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, fromFirst, fromSecond, 1.0); }
            """);
        assertQuadDraws(p, 0, 255, 0, 255);
    }

    @Test
    void aVaryingLocationIsFoundWhereverItsQualifiersStand() {
        final int p = program("""
            #version 440 core
            layout(location = 0) in vec3 pos;
            layout(location = 1) out flat int sent;
            layout(location = 2) out flat int other;
            void main() {
                sent = 1;
                other = 0;
                gl_Position = vec4(pos, 1.0);
            }
            """, """
            #version 440 core
            layout(location = 2) flat in int theOther;
            layout(location = 1) flat in int got;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, float(got), float(theOther), 1.0); }
            """);
        assertQuadDraws(p, 0, 255, 0, 255);
    }

    @Test
    void aUniformKeepsTheLocationItsSourceDeclares() {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 430 core
            uniform vec4 implied;
            layout(location = 8) uniform vec4 declared;
            out vec4 fragColor;
            void main() { fragColor = declared + implied; }
            """);
        assertEquals(8, gl.getUniformLocation(p, "declared"));
        final int implied = gl.getUniformLocation(p, "implied");
        assertTrue(implied >= 0 && implied != 8, "at " + implied);
        final int fbo = smallTarget(GL11.GL_RGBA8);
        final int vao = quadVao(0f);
        gl.useProgram(p);
        gl.uniform4f(8, 0f, 1f, 0f, 1f);
        gl.uniform4f(implied, 0f, 0f, 0f, 0f);
        gl.uniform4f(5, 1f, 0f, 0f, 1f); // a location nothing holds
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(4, 4));
        closeSmallTarget(fbo);
        gl.deleteVertexArrays(vao);
    }

    @Test
    void varyingsWithoutLocationsStillMatchByName() {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            out vec4 passedOn;
            void main() { passedOn = vec4(0.0, 1.0, 0.0, 1.0); gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            in vec4 passedOn;
            out vec4 fragColor;
            void main() { fragColor = passedOn; }
            """);
        assertQuadDraws(p, 0, 255, 0, 255);
    }

    @Test
    void pointSizeReachesTheDrawAndStaysHidden() {
        assumeTrue(gl.gpuCaps().pointSizeMax >= 6f, "this device draws points of one size");
        final int p = solidProgram("0.0, 1.0, 0.0");
        assertEquals(0, gl.getProgrami(p, GL20.GL_ACTIVE_UNIFORMS), "the size is not the program's own uniform");
        final int fbo = smallTarget(GL11.GL_RGBA8);
        final int vao = pointVao();
        gl.useProgram(p);
        gl.pointSize(6f);
        gl.drawArrays(GL11.GL_POINTS, 0, 1);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(2, 2));
        gl.pointSize(1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.drawArrays(GL11.GL_POINTS, 0, 1);
        assertColor(new int[] {0, 0, 0, 255}, readPixel(2, 2));
        closeSmallTarget(fbo);
        gl.deleteVertexArrays(vao);
    }

    @Test
    void aShaderWritingItsOwnPointSizeKeepsIt() {
        assumeTrue(gl.gpuCaps().pointSizeMax >= 6f, "this device draws points of one size");
        assertLargePoint("gl_PointSize = 6.0;", 1f);
    }

    @Test
    void aCommentNamingPointBuiltinsLeavesGlPointSizeAlone() {
        assumeTrue(gl.gpuCaps().pointSizeMax >= 6f, "this device draws points of one size");
        assertLargePoint("// neither gl_PointSize nor gl_Layer", 6f);
    }

    private static void assertLargePoint(String extraVertexLine, float pointSize) {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            void main() {
                gl_Position = vec4(pos, 1.0);
                %s
            }
            """.formatted(extraVertexLine), """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int fbo = smallTarget(GL11.GL_RGBA8);
        final int vao = pointVao();
        gl.useProgram(p);
        gl.pointSize(pointSize);
        gl.drawArrays(GL11.GL_POINTS, 0, 1);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(2, 2));
        gl.pointSize(1f);
        closeSmallTarget(fbo);
        gl.deleteVertexArrays(vao);
    }

    @Test
    void aDoubleUniformReachesTheShader() {
        final int p = program("""
            #version 400 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 400 core
            uniform double green;
            uniform dvec2 blueAlpha;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, float(green * 1.0lf), float(blueAlpha.x), float(blueAlpha.y)); }
            """);
        final int fbo = smallTarget(GL11.GL_RGBA8);
        final int vao = quadVao(0f);
        gl.useProgram(p);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            gl.uniformDoubles(gl.getUniformLocation(p, "green"), 1, 1, MemoryUtil.memAddress(stack.doubles(1.0)));
            gl.uniformDoubles(gl.getUniformLocation(p, "blueAlpha"), 1, 2, MemoryUtil.memAddress(stack.doubles(0.0, 1.0)));
        }
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(4, 4));
        closeSmallTarget(fbo);
        gl.deleteVertexArrays(vao);
    }

    @Test
    void aStructsMembersCountUpFromTheLocationItDeclares() {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 430 core
            struct Pair { vec4 a; vec4 b; };
            layout(location = 3) uniform Pair pair;
            uniform vec4 other;
            out vec4 fragColor;
            void main() { fragColor = pair.a + pair.b + other; }
            """);
        assertEquals(3, gl.getUniformLocation(p, "pair.a"));
        assertEquals(4, gl.getUniformLocation(p, "pair.b"));
        assertTrue(gl.getUniformLocation(p, "other") < 3, "the free location below them");
    }

    @Test
    void aDeclaredLocationSurvivesSamplerDemotion() {
        final StringBuilder fragment = new StringBuilder("#version 430 core\nlayout(location = 3) uniform vec4 declared;\nout vec4 fragColor;\n");
        // Desktop drivers allow about a million samplers a stage, where a shader past the limit is too big to parse.
        assumeTrue(gpu.maxPerStageDescriptorSamplers <= 4096, "the device's sampler limit is out of a shader's reach");
        final int samplers = gpu.maxPerStageDescriptorSamplers + 2;
        for (int i = 0; i < samplers; i++) fragment.append("uniform sampler2D fetched").append(i).append(";\n");
        fragment.append("void main() { fragColor = declared");
        for (int i = 0; i < samplers; i++) fragment.append(" + texelFetch(fetched").append(i).append(", ivec2(0), 0)");
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, fragment.append("; }\n").toString());
        assertEquals(3, gl.getUniformLocation(p, "declared"));
    }

    @Test
    void doublesLeaveNamesAndDivisionsAlone() {
        final int p = program("""
            #version 400 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 400 core
            uniform float x1lf;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, x1lf * float(1.0lf / 2.0lf) * 2.0, 0.0, 1.0); }
            """);
        final int location = gl.getUniformLocation(p, "x1lf");
        assertTrue(location >= 0, "the uniform kept its name");
        final int fbo = smallTarget(GL11.GL_RGBA8);
        final int vao = quadVao(0f);
        gl.useProgram(p);
        gl.uniform1f(location, 1f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(4, 4));
        closeSmallTarget(fbo);
        gl.deleteVertexArrays(vao);
    }

    @Test
    void aMatrixReadsBackAsItWasSet() {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            uniform mat3 shape;
            void main() { gl_Position = vec4(shape * pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        gl.useProgram(p);
        final int location = gl.getUniformLocation(p, "shape");
        final float[] values = {1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f};
        final FloatBuffer in = MemoryUtil.memAllocFloat(9);
        final FloatBuffer out = MemoryUtil.memAllocFloat(9);
        try {
            in.put(values).flip();
            gl.uniformMatrix3(location, false, in);
            gl.getUniformfv(p, location, out);
            for (int i = 0; i < 9; i++) assertEquals(values[i], out.get(i), 0f, "element " + i);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.useProgram(0);
    }

    @Test
    void anUnboundShadowArraySamplerStillDraws() {
        final int p = program("""
            #version 330 core
            layout(location = 0) in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            uniform sampler2DArrayShadow depths;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, texture(depths, vec4(0.5, 0.5, 0.0, 1.0)) + 1.0, 0.0, 1.0); }
            """);
        assertQuadDraws(p, 0, 255, 0, 255);
    }

    @Test
    void stencilReadsLandWherePackSkipsSay() {
        final int color = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, color);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 8, 8);
        final int depth = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, depth);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_DEPTH24_STENCIL8, 8, 8);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        gl.viewport(0, 0, 8, 8);
        gl.clearStencil(0x55);
        gl.clear(GL11.GL_STENCIL_BUFFER_BIT);
        final ByteBuffer out = MemoryUtil.memCalloc(16);
        try {
            gl.pixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            gl.pixelStorei(GL11.GL_PACK_SKIP_PIXELS, 3);
            gl.readPixels(0, 0, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(0, out.get(0), "skipped");
            assertEquals(0x55, out.get(3), "where the skip puts it");
            MemoryUtil.memSet(out, 0);
            gl.readPixels(0, 0, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_INT, out);
            assertEquals(0, out.getInt(0), "skipped");
            assertEquals(0x55, out.getInt(12), "a whole int where the skip puts it");
        } finally {
            gl.pixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            gl.pixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            gl.clearStencil(0);
            MemoryUtil.memFree(out);
        }
        closeSmallTarget(fbo);
        gl.deleteTextures(color);
        gl.deleteTextures(depth);
    }

    @Test
    void aLuminanceImageReadsAsLuminanceOnce() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer in = MemoryUtil.memAlloc(1);
        final ByteBuffer out = MemoryUtil.memAlloc(1);
        try {
            in.put(0, (byte) 64);
            gl.pixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            gl.pixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_LUMINANCE8, 1, 1, 0, GL11.GL_LUMINANCE, GL11.GL_UNSIGNED_BYTE, in);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_LUMINANCE, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(64, out.get(0) & 0xFF);
        } finally {
            gl.pixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            gl.pixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(tex);
    }

    @Test
    void aFlatQuadTakesOneValueFromItsLastVertex() {
        assertFlatQuad(GL11.GL_QUADS, AROUND, 255, "quad");
    }

    @Test
    void aFlatQuadStripTakesOneValueFromItsLastVertex() {
        assertFlatQuad(GL11.GL_QUAD_STRIP, STRIP, 255, "quad strip");
    }

    @Test
    void aFlatQuadFollowsTheFirstVertexConvention() {
        gl.provokingVertex(GL32.GL_FIRST_VERTEX_CONVENTION);
        try {
            assertFlatQuad(GL11.GL_QUADS, AROUND, 0, "quad");
            assertFlatQuad(GL11.GL_QUAD_STRIP, STRIP, 0, "quad strip");
        } finally {
            gl.provokingVertex(GL32.GL_LAST_VERTEX_CONVENTION);
        }
    }

    @Test
    void eachFlatFanTriangleTakesItsLastVertex() {
        assertFlatValues(GL11.GL_TRIANGLE_FAN, AROUND, new int[][] {{52, 12}, {12, 52}}, new int[] {128, 255}, "fan");
    }

    @Test
    void aFlatPolygonTakesOneValueFromItsFirstVertex() {
        assertFlatQuad(GL11.GL_POLYGON, AROUND, 0, "polygon");
    }

    @Test
    void anAttachmentThatCannotBeRenderedToIsSkipped() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB, 4, 4);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.finish();
        gl.useProgram(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
        gl.clearColor(0f, 1f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(0, 0));
    }

    @Test
    void aFramebufferCopyIsClippedToTheDestination() {
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 1f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, 4, 4);
        gl.copyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, 64, 64);
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4 * 4);
        try {
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(255, out.get(1) & 0xFF, "the part that fits was copied");
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(tex);
    }

    @Test
    void aSmallWriteToALargeBusyBufferIsNotARename() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        gl.bufferData(GL15.GL_ARRAY_BUFFER, 1L << 20, GL15.GL_STREAM_DRAW);
        gl.vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        gl.enableVertexAttribArray(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.finish();
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final long renames = stat("renames");
        final ByteBuffer data = MemoryUtil.memCalloc(16);
        gl.bufferSubData(GL15.GL_ARRAY_BUFFER, 0L, data);
        MemoryUtil.memFree(data);
        assertEquals(renames, stat("renames"), "a 16 byte write copied the whole buffer");
        gl.useProgram(0);
    }

    @Test
    void aCopyFrom3DToAnArrayTakesEachPlane() {
        final ByteBuffer in = MemoryUtil.memAlloc(3 * 4);
        final ByteBuffer out = MemoryUtil.memAlloc(3 * 4);
        final int volume = gl.genTextures(), array = gl.genTextures();
        try {
            for (int i = 0; i < 3; i++) in.putInt(i * 4, 0xFF000000 | i * 40);
            gl.bindTexture(GL12.GL_TEXTURE_3D, volume);
            gl.texImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, 1, 1, 3, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, in);
            gl.bindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
            gl.texImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, 1, 1, 3, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            gl.copyImageSubData(volume, GL12.GL_TEXTURE_3D, 0, 0, 0, 0, array, GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, 1, 1, 3);
            gl.getTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            for (int i = 0; i < 3; i++) assertEquals(in.getInt(i * 4), out.getInt(i * 4), "layer " + i);
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.deleteTextures(volume);
        gl.deleteTextures(array);
    }

    @Test
    void unalignedBufferWritesKeepTheBytesAroundThem() {
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final ByteBuffer data = MemoryUtil.memAlloc(8);
        final ByteBuffer out = MemoryUtil.memAlloc(8);
        try {
            for (int i = 0; i < 8; i++) data.put(i, (byte) (i + 1));
            gl.bufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            data.limit(3);
            for (int i = 0; i < 3; i++) data.put(i, (byte) 100);
            gl.bufferSubData(GL15.GL_ARRAY_BUFFER, 3L, data);
            gl.getBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, out);
            final byte[] expected = {1, 2, 3, 100, 100, 100, 7, 8};
            for (int i = 0; i < 8; i++) assertEquals(expected[i], out.get(i), "byte " + i);
        } finally {
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(out);
        }
        gl.deleteBuffers(vbo);
    }

    @Test
    void getTexImageReachesAPackBufferHoweverItIsHinted() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer in = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder()).putInt(0, 0xFF0000FF);
        final ByteBuffer out = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder());
        final int pbo = gl.genBuffers();
        try {
            gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, in);
            gl.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            gl.bufferData(GL21.GL_PIXEL_PACK_BUFFER, 4L, GL15.GL_STATIC_DRAW);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            gl.getBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, out);
            assertEquals(in.getInt(0), out.getInt(0));
        } finally {
            gl.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
        gl.deleteBuffers(pbo);
        gl.deleteTextures(tex);
    }

    @Test
    void clearingABufferRepeatsAWholeElement() {
        final int ssbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, ssbo);
        gl.bufferData(GL15.GL_ARRAY_BUFFER, 32L, GL15.GL_STATIC_DRAW);
        final ByteBuffer value = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        final ByteBuffer out = MemoryUtil.memAlloc(32).order(ByteOrder.nativeOrder());
        try {
            value.putFloat(0, 1f).putFloat(4, 2f).putFloat(8, 3f).putFloat(12, 4f);
            gl.clearBufferData(GL15.GL_ARRAY_BUFFER, GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT, value);
            gl.getBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, out);
            for (int i = 0; i < 8; i++) assertEquals(i % 4 + 1f, out.getFloat(i * 4), "float " + i);
        } finally {
            MemoryUtil.memFree(value);
            MemoryUtil.memFree(out);
        }
        gl.deleteBuffers(ssbo);
    }

    @Test
    void changingABoundSamplerAppliesToTheNextDraw() {
        final int p = program(SAMPLE_VS, """
            #version 330 core
            uniform sampler2D tex;
            out vec4 fragColor;
            void main() { fragColor = texture(tex, vec2(0.5, 0.5)); }
            """);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 0);
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer px = MemoryUtil.memAlloc(8);
        px.put(0, (byte) 0).put(1, (byte) 0).put(2, (byte) 0).put(3, (byte) 255);
        px.put(4, (byte) 255).put(5, (byte) 255).put(6, (byte) 255).put(7, (byte) 255);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        MemoryUtil.memFree(px);
        final int sampler = gl.genSamplers();
        gl.bindSampler(0, sampler);
        gl.samplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.samplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final int vao = quadVao(0f);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.disable(GL11.GL_BLEND);
        gl.disable(GL11.GL_DEPTH_TEST);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final int nearest = readPixel(32, 32)[0];
        assertTrue(nearest == 0 || nearest == 255, "nearest read " + nearest);
        gl.samplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        gl.samplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        final int linear = readPixel(32, 32)[0];
        assertTrue(linear > 100 && linear < 155, "linear read " + linear);
        gl.bindSampler(0, 0);
        gl.deleteSamplers(sampler);
        gl.deleteTextures(tex);
        gl.useProgram(0);
    }

    @Test
    void attachingOneLevelInitialisesTheOthers() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            void main() { gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, 2, GL11.GL_RGBA8, 4, 4);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        gl.viewport(0, 0, 4, 4);
        gl.useProgram(p);
        final int vao = quadVao(0f);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.viewport(0, 0, 64, 64);
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        final ByteBuffer out = MemoryUtil.memAlloc(2 * 2 * 4);
        try {
            gl.getTexImage(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            for (int i = 0; i < 16; i++) assertEquals(0, out.get(i) & 0xFF, "level 1 byte " + i);
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.deleteVertexArrays(vao);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void aSliceOfA3DTextureIsAttachable() {
        assumeTrue(TestBackend.canRenderToA3DSlice(gpu), "no way to render to one slice of a 3D texture");
        final int tex = gl.genTextures();
        gl.bindTexture(GL12.GL_TEXTURE_3D, tex);
        gl.texImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, 4, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, tex, 0, 0);
        gl.clearColor(1f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.framebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, tex, 0, 2);
        gl.clearColor(0f, 1f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        final ByteBuffer out = MemoryUtil.memAlloc(4 * 4 * 4 * 4);
        try {
            gl.getTexImage(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            assertEquals(0xFF, out.get(2 * 64 + 1) & 0xFF, "slice 2 is green");
            assertEquals(0, out.get(2 * 64) & 0xFF, "slice 2 is not red");
            assertEquals(0xFF, out.get(0) & 0xFF, "slice 0 stays red");
            assertEquals(0, out.get(64) & 0xFF, "slice 1 stays untouched");
            assertEquals(0, out.get(64 + 1) & 0xFF, "slice 1 stays untouched");
        } finally {
            MemoryUtil.memFree(out);
        }
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void integerAttributesReadAsFloatsAreNotNormalized() {
        final int p = program("""
            #version 330 core
            in vec3 pos;
            in vec4 color;
            out vec4 c;
            void main() { c = color; gl_Position = vec4(pos, 1.0); }
            """, """
            #version 330 core
            in vec4 c;
            out vec4 fragColor;
            void main() { fragColor = c; }
            """);
        final int vao = quadVao(0f);
        gl.bindVertexArray(vao);
        final int vbo = gl.genBuffers();
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final ByteBuffer colors = MemoryUtil.memAlloc(16);
        for (int i = 0; i < 4; i++) colors.put((byte) 1).put((byte) 0).put((byte) 1).put((byte) 1);
        colors.flip();
        gl.bufferData(GL15.GL_ARRAY_BUFFER, colors, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(colors);
        gl.useProgram(p);
        final int loc = gl.getAttribLocation(p, "color");
        gl.vertexAttribPointer(loc, 4, GL11.GL_UNSIGNED_BYTE, false, 4, 0L);
        gl.enableVertexAttribArray(loc);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.drawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        assertColor(new int[] {255, 0, 255, 255}, readPixel(32, 32));
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
    void anotherThreadHasItsOwnStateButSharesObjects() throws Exception {
        final int texture = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, texture);
        gl.disable(GL30.GL_FRAMEBUFFER_SRGB);
        final Throwable[] failure = new Throwable[1];
        final Thread thread = new Thread(() -> {
            try {
                assertFalse(gl.hasStateOnThread());
                gl.enable(GL30.GL_FRAMEBUFFER_SRGB);
                assertTrue(gl.hasStateOnThread());
                assertTrue(gl.st().framebufferSrgb);
                assertTrue(gl.isTexture(texture), "objects are shared");
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        thread.start();
        thread.join();
        if (failure[0] != null) throw new AssertionError(failure[0]);
        assertFalse(gl.st().framebufferSrgb, "state is not");
        assertTrue(gl.otherThreadsHaveState());
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
        gl.deleteTextures(texture);
    }
}
