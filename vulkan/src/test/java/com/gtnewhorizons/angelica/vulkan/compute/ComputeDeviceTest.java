package com.gtnewhorizons.angelica.vulkan.compute;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("vulkan-device")
class ComputeDeviceTest {

    static final int SIZE = 64;

    static Gpu gpu;
    static VulkanGL gl;

    @BeforeAll
    static void setUp() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        gpu = new Gpu();
        assumeTrue(gpu.create(), "no Vulkan device");
        gl = new VulkanGL(gpu, Thread.currentThread(), null);
        gl.attachOffscreen(SIZE, SIZE);
        gl.viewport(0, 0, SIZE, SIZE);
    }

    @AfterAll
    static void tearDown() {
        if (gl != null) gl.shutdown();
        if (gpu != null) gpu.destroy();
    }

    @AfterEach
    void unbind() {
        gl.useProgram(0);
        gl.bindVertexArray(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.viewport(0, 0, SIZE, SIZE);
    }

    static int computeProgram(String source) {
        final int cs = gl.createShader(GL43.GL_COMPUTE_SHADER);
        gl.shaderSource(cs, source);
        gl.compileShader(cs);
        assertEquals(GL11.GL_TRUE, gl.getShaderi(cs, GL20.GL_COMPILE_STATUS), gl.getShaderInfoLog(cs, 4096));
        final int p = gl.createProgram();
        gl.attachShader(p, cs);
        gl.linkProgram(p);
        assertEquals(GL11.GL_TRUE, gl.getProgrami(p, GL20.GL_LINK_STATUS), gl.getProgramInfoLog(p, 4096));
        return p;
    }

    static int ssbo(long bytes) {
        final int b = gl.genBuffers();
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, b);
        final ByteBuffer zero = MemoryUtil.memCalloc((int) bytes);
        gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, zero, GL15.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(zero);
        return b;
    }

    static int[] readInts(int buffer, int count) {
        final IntBuffer out = MemoryUtil.memAllocInt(count);
        try {
            gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
            gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, out);
            final int[] r = new int[count];
            out.get(r);
            return r;
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    static int storageTexture(int internalFormat, int width, int height, int levels) {
        final int t = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, t);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        gl.texStorage2D(GL11.GL_TEXTURE_2D, levels, internalFormat, width, height);
        return t;
    }

    static int[] readUintTexture(int texture, int level, int texels) {
        final IntBuffer out = MemoryUtil.memAllocInt(texels);
        try {
            gl.bindTexture(GL11.GL_TEXTURE_2D, texture);
            gl.getTexImage(GL11.GL_TEXTURE_2D, level, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, out);
            final int[] r = new int[texels];
            out.get(r);
            return r;
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    static int graphicsProgram(String vs, String fs) {
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

    static final String FULLSCREEN_VS = """
        #version 430 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    static int emptyVao() {
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        return vao;
    }

    static int[] readPixel(int x, int y) {
        final ByteBuffer px = MemoryUtil.memAlloc(4);
        try {
            gl.readPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            return new int[] {px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF, px.get(3) & 0xFF};
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    static void assertColor(int[] expected, int[] actual, String label) {
        for (int i = 0; i < 4; i++) {
            final int c = i;
            assertTrue(Math.abs(expected[i] - actual[i]) <= 2,
                () -> label + " channel " + c + ": expected " + java.util.Arrays.toString(expected) + " got " + java.util.Arrays.toString(actual));
        }
    }

    @Test
    void ssboWritesAreVisibleToReadback() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer Out { uint v[]; } o;
            void main() { o.v[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x * 3u + 1u; }
            """);
        final int buf = ssbo(256 * 4);
        gl.useProgram(p);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.dispatchCompute(4, 1, 1);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        final int[] got = readInts(buf, 256);
        for (int i = 0; i < 256; i++) assertEquals(i * 3 + 1, got[i], "element " + i);
    }

    @Test
    void sharedMemoryReductionHonoursBarrier() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 128) in;
            layout(std430, binding = 0) buffer Out { uint sums[]; } o;
            shared uint scratch[128];
            void main() {
                uint i = gl_LocalInvocationIndex;
                scratch[i] = i + gl_WorkGroupID.x * 1000u;
                barrier();
                for (uint stride = 64u; stride > 0u; stride >>= 1) {
                    if (i < stride) scratch[i] += scratch[i + stride];
                    barrier();
                }
                if (i == 0u) o.sums[gl_WorkGroupID.x] = scratch[0];
            }
            """);
        final int buf = ssbo(3 * 4);
        gl.useProgram(p);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.dispatchCompute(3, 1, 1);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        final int base = 127 * 128 / 2;
        assertArrayEquals(new int[] {base, base + 128_000, base + 256_000}, readInts(buf, 3));
    }

    @Test
    void workGroupSizeIsQueryable() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 8, local_size_y = 4, local_size_z = 2) in;
            layout(std430, binding = 0) buffer Out { uint v; } o;
            void main() { o.v = 1u; }
            """);
        final IntBuffer size = MemoryUtil.memAllocInt(3);
        try {
            gl.getProgramiv(p, GL43.GL_COMPUTE_WORK_GROUP_SIZE, size);
            assertArrayEquals(new int[] {8, 4, 2}, new int[] {size.get(0), size.get(1), size.get(2)});
        } finally {
            MemoryUtil.memFree(size);
        }
    }

    @Test
    void defaultBlockUniformsUboAndSampledTextureReachCompute() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 4) in;
            uniform int scale;
            layout(std140, binding = 3) uniform Params { uvec4 offset; } params;
            uniform sampler2D src;
            layout(std430, binding = 0) buffer Out { uint v[]; } o;
            void main() {
                uint red = uint(texelFetch(src, ivec2(gl_GlobalInvocationID.x, 0), 0).r * 255.0 + 0.5);
                o.v[gl_GlobalInvocationID.x] = red * uint(scale) + params.offset.y;
            }
            """);
        final int tex = gl.genTextures();
        gl.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final ByteBuffer texels = MemoryUtil.memCalloc(16);
        for (int i = 0; i < 4; i++) texels.put(i * 4, (byte) (10 * (i + 1))).put(i * 4 + 3, (byte) 255);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        MemoryUtil.memFree(texels);
        gl.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);

        final int ubo = gl.genBuffers();
        gl.bindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        final ByteBuffer params = MemoryUtil.memCalloc(16);
        params.putInt(4, 7);
        gl.bufferData(GL31.GL_UNIFORM_BUFFER, params, GL15.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(params);

        final int buf = ssbo(4 * 4);
        gl.useProgram(p);
        gl.uniform1i(gl.getUniformLocation(p, "src"), 2);
        gl.uniform1i(gl.getUniformLocation(p, "scale"), 3);
        gl.bindBufferBase(GL31.GL_UNIFORM_BUFFER, 3, ubo);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {37, 67, 97, 127}, readInts(buf, 4));
    }

    @Test
    void uniformChangesBetweenDispatchesApplyToEach() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 1) in;
            uniform int slot;
            uniform int value;
            layout(std430, binding = 0) buffer Out { uint v[]; } o;
            void main() { o.v[slot] = uint(value); }
            """);
        final int buf = ssbo(4 * 4);
        gl.useProgram(p);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        final int slot = gl.getUniformLocation(p, "slot");
        final int value = gl.getUniformLocation(p, "value");
        for (int i = 0; i < 4; i++) {
            gl.uniform1i(slot, i);
            gl.uniform1i(value, 100 + i);
            gl.dispatchCompute(1, 1, 1);
        }
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {100, 101, 102, 103}, readInts(buf, 4));
    }

    @Test
    void imageStoreIsSampledByALaterDraw() {
        final int img = storageTexture(GL11.GL_RGBA8, SIZE, SIZE, 1);
        final int cp = computeProgram("""
            #version 430 core
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(rgba8, binding = 1) writeonly uniform image2D dst;
            void main() {
                ivec2 c = ivec2(gl_GlobalInvocationID.xy);
                imageStore(dst, c, c.x < 32 ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0));
            }
            """);
        gl.useProgram(cp);
        gl.bindImageTexture(1, img, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
        gl.dispatchCompute(SIZE / 8, SIZE / 8, 1);
        gl.memoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        final int p = graphicsProgram(FULLSCREEN_VS, """
            #version 430 core
            uniform sampler2D tex;
            out vec4 fragColor;
            void main() { fragColor = texelFetch(tex, ivec2(gl_FragCoord.xy), 0); }
            """);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        gl.bindTexture(GL11.GL_TEXTURE_2D, img);
        gl.uniform1i(gl.getUniformLocation(p, "tex"), 0);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertColor(new int[] {255, 0, 0, 255}, readPixel(8, 32), "left half");
        assertColor(new int[] {0, 0, 255, 255}, readPixel(56, 32), "right half");
    }

    @Test
    void imageAtomicsAccumulateAcrossInvocations() {
        final int img = storageTexture(GL30.GL_R32UI, 2, 1, 1);
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 64) in;
            layout(r32ui, binding = 0) uniform uimage2D counts;
            void main() {
                imageAtomicAdd(counts, ivec2(0, 0), 1u);
                imageAtomicMax(counts, ivec2(1, 0), gl_GlobalInvocationID.x);
            }
            """);
        gl.useProgram(p);
        gl.bindImageTexture(0, img, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
        gl.dispatchCompute(4, 1, 1);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {256, 255}, readUintTexture(img, 0, 2));
    }

    @Test
    void imageWrittenByOneDispatchIsLoadedByTheNext() {
        final int img = storageTexture(GL30.GL_R32F, 16, 1, 1);
        final int write = computeProgram("""
            #version 430 core
            layout(local_size_x = 16) in;
            layout(r32f, binding = 0) writeonly uniform image2D dst;
            void main() { imageStore(dst, ivec2(gl_GlobalInvocationID.x, 0), vec4(float(gl_GlobalInvocationID.x) + 0.5)); }
            """);
        final int read = computeProgram("""
            #version 430 core
            layout(local_size_x = 16) in;
            layout(r32f, binding = 0) readonly uniform image2D src;
            layout(std430, binding = 0) buffer Out { uint v[]; } o;
            void main() { o.v[gl_GlobalInvocationID.x] = uint(imageLoad(src, ivec2(gl_GlobalInvocationID.x, 0)).r * 2.0); }
            """);
        final int buf = ssbo(16 * 4);
        gl.bindImageTexture(0, img, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32F);
        gl.useProgram(write);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        gl.useProgram(read);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        final int[] got = readInts(buf, 16);
        for (int i = 0; i < 16; i++) assertEquals(i * 2 + 1, got[i], "element " + i);
    }

    @Test
    void imageStoreTargetsTheBoundMipLevel() {
        final int img = storageTexture(GL30.GL_R32UI, 8, 8, 2);
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 4, local_size_y = 4) in;
            layout(r32ui, binding = 0) writeonly uniform uimage2D dst;
            void main() { imageStore(dst, ivec2(gl_GlobalInvocationID.xy), uvec4(gl_GlobalInvocationID.y * 4u + gl_GlobalInvocationID.x + 1u)); }
            """);
        gl.useProgram(p);
        gl.bindImageTexture(0, img, 1, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32UI);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        final int[] level1 = readUintTexture(img, 1, 16);
        for (int i = 0; i < 16; i++) assertEquals(i + 1, level1[i], "level 1 texel " + i);
        final int[] level0 = readUintTexture(img, 0, 64);
        for (int i = 0; i < 64; i++) assertEquals(0, level0[i], "level 0 texel " + i + " must stay untouched");
    }

    @Test
    void dispatchIndirectReadsItsGroupCountFromABuffer() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 32) in;
            layout(std430, binding = 0) buffer Out { uint invocations; uint maxGroup; } o;
            void main() {
                atomicAdd(o.invocations, 1u);
                atomicMax(o.maxGroup, gl_WorkGroupID.y);
            }
            """);
        final int args = gl.genBuffers();
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, args);
        final IntBuffer groups = MemoryUtil.memAllocInt(4);
        groups.put(new int[] {0, 3, 5, 1}).flip();
        gl.bufferData(GL43.GL_DISPATCH_INDIRECT_BUFFER, groups, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(groups);
        final int buf = ssbo(8);
        gl.useProgram(p);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, args);
        gl.dispatchComputeIndirect(4);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {3 * 5 * 32, 4}, readInts(buf, 2));
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
    }

    @Test
    void computeWrittenGroupCountDrivesAnIndirectDispatch() {
        final int writer = computeProgram("""
            #version 430 core
            layout(local_size_x = 1) in;
            layout(std430, binding = 1) writeonly buffer Args { uint x, y, z; } a;
            void main() { a.x = 6u; a.y = 1u; a.z = 1u; }
            """);
        final int counter = computeProgram("""
            #version 430 core
            layout(local_size_x = 16) in;
            layout(std430, binding = 0) buffer Out { uint invocations; } o;
            void main() { atomicAdd(o.invocations, 1u); }
            """);
        final int args = ssbo(12);
        final int buf = ssbo(4);
        gl.useProgram(writer);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, args);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
        gl.useProgram(counter);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, args);
        gl.dispatchComputeIndirect(0);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        assertEquals(6 * 16, readInts(buf, 1)[0]);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
    }

    @Test
    void computeWrittenVerticesFeedADraw() {
        final int writer = computeProgram("""
            #version 430 core
            layout(local_size_x = 3) in;
            layout(std430, binding = 0) writeonly buffer Verts { vec4 v[]; } o;
            void main() {
                vec2 p = vec2((gl_LocalInvocationID.x << 1) & 2u, gl_LocalInvocationID.x & 2u);
                o.v[gl_LocalInvocationID.x] = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """);
        final int vbo = ssbo(3 * 16);
        gl.useProgram(writer);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, vbo);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

        final int p = graphicsProgram("""
            #version 430 core
            in vec4 pos;
            void main() { gl_Position = pos; }
            """, """
            #version 430 core
            out vec4 fragColor;
            void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
            """);
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        gl.vertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0L);
        gl.enableVertexAttribArray(0);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertColor(new int[] {0, 255, 0, 255}, readPixel(32, 32), "centre");
    }

    @Test
    void dispatchBetweenDrawsKeepsEarlierDrawsInTheTarget() {
        final int p = graphicsProgram(FULLSCREEN_VS, """
            #version 430 core
            uniform vec4 color;
            out vec4 fragColor;
            void main() { fragColor = color; }
            """);
        final int cp = computeProgram("""
            #version 430 core
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Out { uint v; } o;
            void main() { o.v = 42u; }
            """);
        final int buf = ssbo(4);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.enable(GL11.GL_SCISSOR_TEST);
        gl.useProgram(p);
        gl.scissor(0, 0, 32, SIZE);
        gl.uniform4f(gl.getUniformLocation(p, "color"), 1f, 0f, 0f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);

        gl.useProgram(cp);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.dispatchCompute(1, 1, 1);

        gl.useProgram(p);
        gl.scissor(32, 0, 32, SIZE);
        gl.uniform4f(gl.getUniformLocation(p, "color"), 0f, 1f, 0f, 1f);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        gl.disable(GL11.GL_SCISSOR_TEST);

        assertColor(new int[] {255, 0, 0, 255}, readPixel(8, 32), "draw before the dispatch");
        assertColor(new int[] {0, 255, 0, 255}, readPixel(56, 32), "draw after the dispatch");
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        assertEquals(42, readInts(buf, 1)[0]);
    }

    @Test
    void computeFilledTextureIsLoadedWhenAttachedAndDrawnOver() {
        final int img = storageTexture(GL11.GL_RGBA8, SIZE, SIZE, 1);
        final int cp = computeProgram("""
            #version 430 core
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(rgba8, binding = 0) writeonly uniform image2D dst;
            void main() { imageStore(dst, ivec2(gl_GlobalInvocationID.xy), vec4(0.0, 0.0, 1.0, 1.0)); }
            """);
        gl.useProgram(cp);
        gl.bindImageTexture(0, img, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
        gl.dispatchCompute(SIZE / 8, SIZE / 8, 1);
        gl.memoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);

        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, img, 0);
        gl.viewport(0, 0, SIZE, SIZE);
        final int p = graphicsProgram(FULLSCREEN_VS, """
            #version 430 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0, 1.0, 0.0, 1.0); }
            """);
        emptyVao();
        gl.enable(GL11.GL_SCISSOR_TEST);
        gl.scissor(0, 0, 32, SIZE);
        gl.useProgram(p);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        gl.disable(GL11.GL_SCISSOR_TEST);

        assertColor(new int[] {255, 255, 0, 255}, readPixel(8, 32), "drawn half");
        assertColor(new int[] {0, 0, 255, 255}, readPixel(56, 32), "compute-filled half");
    }

    @Test
    void fragmentShaderImageStoresLandOutsideTheColorTarget() {
        assumeTrue(gpu.fragmentStoresAndAtomics, "device lacks fragmentStoresAndAtomics");
        final int img = storageTexture(GL30.GL_R32UI, SIZE, SIZE, 1);
        final int p = graphicsProgram(FULLSCREEN_VS, """
            #version 430 core
            layout(r32ui, binding = 2) writeonly uniform uimage2D marks;
            out vec4 fragColor;
            void main() {
                ivec2 c = ivec2(gl_FragCoord.xy);
                imageStore(marks, c, uvec4(uint(c.y * 64 + c.x) + 1u));
                fragColor = vec4(1.0);
            }
            """);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.bindImageTexture(2, img, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32UI);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        final int[] marks = readUintTexture(img, 0, SIZE * SIZE);
        for (int i = 0; i < SIZE * SIZE; i += 97) assertEquals(i + 1, marks[i], "texel " + i);
    }

    @Test
    void fragmentImageAtomicsCountCoveredPixels() {
        assumeTrue(gpu.fragmentStoresAndAtomics, "device lacks fragmentStoresAndAtomics");
        final int img = storageTexture(GL30.GL_R32UI, 1, 1, 1);
        final int p = graphicsProgram(FULLSCREEN_VS, """
            #version 430 core
            layout(r32ui, binding = 0) uniform uimage2D counter;
            out vec4 fragColor;
            void main() {
                imageAtomicAdd(counter, ivec2(0), 1u);
                fragColor = vec4(1.0);
            }
            """);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.bindImageTexture(0, img, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        assertEquals(SIZE * SIZE, readUintTexture(img, 0, 1)[0]);
    }

    @Test
    void vertexShaderImageStoresRunOncePerVertex() {
        assumeTrue(gpu.vertexPipelineStoresAndAtomics, "device lacks vertexPipelineStoresAndAtomics");
        final int img = storageTexture(GL30.GL_R32UI, 8, 1, 1);
        final int p = graphicsProgram("""
            #version 430 core
            layout(r32ui, binding = 1) writeonly uniform uimage2D seen;
            void main() {
                imageStore(seen, ivec2(gl_VertexID, 0), uvec4(uint(gl_VertexID) * 10u + 5u));
                gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                gl_PointSize = 1.0;
            }
            """, """
            #version 430 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.bindImageTexture(1, img, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32UI);
        gl.drawArrays(GL11.GL_POINTS, 0, 8);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {5, 15, 25, 35, 45, 55, 65, 75}, readUintTexture(img, 0, 8));
    }

    @Test
    void rasterizerDiscardKeepsVertexWritesButDrawsNothing() {
        assumeTrue(gpu.vertexPipelineStoresAndAtomics, "device lacks vertexPipelineStoresAndAtomics");
        final int img = storageTexture(GL30.GL_R32UI, 3, 1, 1);
        final int p = graphicsProgram("""
            #version 430 core
            layout(r32ui, binding = 0) writeonly uniform uimage2D seen;
            void main() {
                imageStore(seen, ivec2(gl_VertexID, 0), uvec4(uint(gl_VertexID) + 7u));
                vec2 q = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(q * 2.0 - 1.0, 0.0, 1.0);
            }
            """, """
            #version 430 core
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(GL11.GL_COLOR_BUFFER_BIT);
        gl.useProgram(p);
        gl.bindImageTexture(0, img, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32UI);
        gl.enable(GL30.GL_RASTERIZER_DISCARD);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        gl.disable(GL30.GL_RASTERIZER_DISCARD);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {7, 8, 9}, readUintTexture(img, 0, 3), "the vertex shader still ran");
        assertColor(new int[] {0, 0, 0, 255}, readPixel(32, 32), "no fragment reached the target");
    }

    @Test
    void fragmentShaderSsboWritesAreReadable() {
        assumeTrue(gpu.fragmentStoresAndAtomics, "device lacks fragmentStoresAndAtomics");
        final int p = graphicsProgram(FULLSCREEN_VS, """
            #version 430 core
            layout(std430, binding = 4) buffer Hist { uint rows[]; } h;
            out vec4 fragColor;
            void main() {
                atomicAdd(h.rows[int(gl_FragCoord.y) / 16], 1u);
                fragColor = vec4(1.0);
            }
            """);
        final int buf = ssbo(4 * 4);
        emptyVao();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl.useProgram(p);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, buf);
        gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        assertArrayEquals(new int[] {16 * SIZE, 16 * SIZE, 16 * SIZE, 16 * SIZE}, readInts(buf, 4));
    }

    @Test
    void atomicCounterBufferAccumulates() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 64) in;
            layout(binding = 0, offset = 4) uniform atomic_uint hits;
            void main() { atomicCounterIncrement(hits); }
            """);
        final int buf = gl.genBuffers();
        gl.bindBuffer(GL42.GL_ATOMIC_COUNTER_BUFFER, buf);
        final ByteBuffer zero = MemoryUtil.memCalloc(8);
        gl.bufferData(GL42.GL_ATOMIC_COUNTER_BUFFER, zero, GL15.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(zero);
        gl.useProgram(p);
        gl.bindBufferBase(GL42.GL_ATOMIC_COUNTER_BUFFER, 0, buf);
        gl.dispatchCompute(5, 1, 1);
        gl.memoryBarrier(GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        final IntBuffer out = MemoryUtil.memAllocInt(2);
        try {
            gl.bindBuffer(GL42.GL_ATOMIC_COUNTER_BUFFER, buf);
            gl.getBufferSubData(GL42.GL_ATOMIC_COUNTER_BUFFER, 0, out);
            assertEquals(0, out.get(0), "offset 0 must stay untouched");
            assertEquals(5 * 64, out.get(1));
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void storageBufferRangeBindingOffsetsTheView() {
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 4) in;
            layout(std430, binding = 0) buffer Out { uint v[]; } o;
            void main() { o.v[gl_GlobalInvocationID.x] = 9u; }
            """);
        final int align = Math.max(4, gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        final int buf = ssbo(align + 16);
        gl.useProgram(p);
        gl.bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf, align, 16);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        final int[] all = readInts(buf, align / 4 + 4);
        for (int i = 0; i < align / 4; i++) assertEquals(0, all[i], "before the range, word " + i);
        for (int i = 0; i < 4; i++) assertEquals(9, all[align / 4 + i], "inside the range, word " + i);
    }

    @Test
    void floatImageReadModifyWriteInPlace() {
        final int img = storageTexture(GL30.GL_RGBA32F, 4, 4, 1);
        final FloatBuffer init = MemoryUtil.memAllocFloat(4 * 4 * 4);
        for (int i = 0; i < 16; i++) init.put(i * 4, i).put(i * 4 + 1, 0f).put(i * 4 + 2, 0f).put(i * 4 + 3, 1f);
        gl.bindTexture(GL11.GL_TEXTURE_2D, img);
        gl.texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 4, 4, GL11.GL_RGBA, GL11.GL_FLOAT, MemoryUtil.memByteBuffer(init));
        MemoryUtil.memFree(init);
        final int p = computeProgram("""
            #version 430 core
            layout(local_size_x = 4, local_size_y = 4) in;
            layout(rgba32f, binding = 0) uniform image2D data;
            void main() {
                ivec2 c = ivec2(gl_GlobalInvocationID.xy);
                vec4 v = imageLoad(data, c);
                imageStore(data, c, vec4(v.x * 2.0, v.x + 1.0, 0.0, 1.0));
            }
            """);
        gl.useProgram(p);
        gl.bindImageTexture(0, img, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_RGBA32F);
        gl.dispatchCompute(1, 1, 1);
        gl.memoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        final FloatBuffer out = MemoryUtil.memAllocFloat(4 * 4 * 4);
        try {
            gl.bindTexture(GL11.GL_TEXTURE_2D, img);
            gl.getTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, MemoryUtil.memByteBuffer(out));
            for (int i = 0; i < 16; i++) {
                assertEquals(i * 2f, out.get(i * 4), 1e-6f, "texel " + i + " red");
                assertEquals(i + 1f, out.get(i * 4 + 1), 1e-6f, "texel " + i + " green");
            }
        } finally {
            MemoryUtil.memFree(out);
        }
    }
}
