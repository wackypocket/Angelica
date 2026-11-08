package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL46.*;

/**
 * Whether we draw what a driver draws: whole images, compared pixel by pixel, for each thing that decides what ends up on screen.
 * Geometry stays off pixel centres, where coverage is a matter of a driver's tie breaking, and is lopsided, so an image flipped or turned shows.
 */
final class GlConformanceScenariosDrawing {
    private static final int SIZE = GlConformanceScenarios.SIZE;
    private static final String VS = """
        #version 330 core
        layout(location = 0) in vec4 p;
        layout(location = 1) in vec4 c;
        out vec4 v;
        void main() { gl_Position = p; v = c; }
        """;
    private static final String FS = """
        #version 330 core
        in vec4 v;
        out vec4 f;
        void main() { f = v; }
        """;
    private static final String SCREEN_VS = """
        #version 330 core
        uniform vec2 uvScale;
        out vec2 uv;
        void main() {
            vec2 q = vec2(gl_VertexID == 1 || gl_VertexID == 3 ? 1.0 : -1.0, gl_VertexID >= 2 ? 1.0 : -1.0);
            gl_Position = vec4(q, 0.0, 1.0);
            uv = (q * 0.5 + 0.5) * uvScale;
        }
        """;
    /** A lopsided fan of triangles, in position and colour, for every primitive mode to make something different of. */
    private static final float[][] SHAPE = {
        {-0.83f, -0.77f, 0f, 1f, 0f, 0f, 1f}, {0.71f, -0.63f, 0f, 0f, 1f, 0f, 1f}, {-0.57f, 0.69f, 0f, 0f, 0f, 1f, 1f},
        {0.47f, 0.37f, 0f, 1f, 1f, 0f, 1f}, {-0.13f, -0.29f, 0f, 0f, 1f, 1f, 1f}, {0.87f, 0.81f, 0f, 1f, 0f, 1f, 1f}};

    private final GlConformanceScenarios g;

    GlConformanceScenariosDrawing(GlConformanceScenarios g) {
        this.g = g;
    }

    void define() {
        primitives();
        faces();
        viewports();
        depth();
        stencil();
        blending();
        masksAndLogic();
        attributes();
        fragmentInputs();
        textureSampling();
        textureFormats();
        textureKinds();
        framebuffers();
        blits();
        multisampling();
        occlusion();
        readBack();
        pixelConversions();
        buffersAndQueries();
        uniforms();
        fixedFunction();
    }

    // Drawing helpers.

    private static int program(String vs, String fs) {
        final int p = GlConformanceScenarios.program(vs, fs);
        glUseProgram(p);
        return p;
    }

    private static int colored() {
        return program(VS, FS);
    }

    private static int vertices(float[]... data) {
        final FloatBuffer b = BufferUtils.createFloatBuffer(data.length * 7);
        for (float[] v : data) b.put(v);
        b.flip();
        final int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, b, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12L);
        return vbo;
    }

    private static float[] v(float x, float y, float z, float r, float g, float b, float a) {
        return new float[] {x, y, z, r, g, b, a};
    }

    private static void quad(float x0, float y0, float x1, float y1, float z, float r, float g, float b, float a) {
        vertices(v(x0, y0, z, r, g, b, a), v(x1, y0, z, r, g, b, a), v(x0, y1, z, r, g, b, a), v(x1, y1, z, r, g, b, a));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void screen(String fs, float scale) {
        final int p = program(SCREEN_VS, fs);
        glUniform2f(glGetUniformLocation(p, "uvScale"), scale, scale);
        final int unit = glGetUniformLocation(p, "t");
        if (unit >= 0) glUniform1i(unit, 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int texture2D(int w, int h, int internal, int format, int type, ByteBuffer data) {
        final int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data);
        return t;
    }

    private static ByteBuffer bytes(int... values) {
        final ByteBuffer b = BufferUtils.createByteBuffer(values.length);
        for (int v : values) b.put((byte) v);
        b.flip();
        return b;
    }

    /** Four texels no flip or turn leaves the same: red, green, blue, white. */
    private static ByteBuffer checker() {
        return bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255);
    }

    private static final String SAMPLE = """
        #version 330 core
        in vec2 uv;
        uniform sampler2D t;
        out vec4 f;
        void main() { f = texture(t, uv); }
        """;

    private void primitives() {
        final int[][] modes = {{GL_POINTS}, {GL_LINES}, {GL_LINE_STRIP}, {GL_LINE_LOOP}, {GL_TRIANGLES}, {GL_TRIANGLE_STRIP}, {GL_TRIANGLE_FAN}, {GL_LINES_ADJACENCY}, {GL_TRIANGLES_ADJACENCY}};
        for (int[] mode : modes) {
            g.s("draw: mode 0x" + Integer.toHexString(mode[0]), () -> {
                GlConformanceScenarios.target();
                colored();
                vertices(SHAPE);
                glDrawArrays(mode[0], 0, SHAPE.length);
                g.frame("image");
            });
        }
        g.s("draw: indexed, base vertex and restart", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            final int ebo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            final ByteBuffer indices = bytes(0, 1, 2, 255, 3, 4, 5);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
            glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_BYTE, 0L);
            g.frame("bytes");
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_PRIMITIVE_RESTART);
            glPrimitiveRestartIndex(255);
            glDrawElements(GL_TRIANGLE_STRIP, 7, GL_UNSIGNED_BYTE, 0L);
            g.frame("restart");
            glDisable(GL_PRIMITIVE_RESTART);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawElementsBaseVertex(GL_TRIANGLES, 3, GL_UNSIGNED_BYTE, 0L, 3);
            g.frame("base vertex");
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawRangeElements(GL_TRIANGLES, 0, 2, 3, GL_UNSIGNED_BYTE, 0L);
            g.frame("range");
        });
        g.s("draw: line loop and fan through indices", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            final int ebo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, bytes(5, 3, 1, 0), GL_STATIC_DRAW);
            glDrawElements(GL_LINE_LOOP, 4, GL_UNSIGNED_BYTE, 0L);
            g.frame("loop");
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawElements(GL_TRIANGLE_FAN, 4, GL_UNSIGNED_BYTE, 0L);
            g.frame("fan");
        });
        g.s("draw: point size from the shader and point coordinates", () -> {
            GlConformanceScenarios.target();
            glEnable(GL_PROGRAM_POINT_SIZE);
            program("""
                #version 330 core
                layout(location = 0) in vec4 p;
                void main() { gl_Position = p; gl_PointSize = 6.0; }
                """, """
                #version 330 core
                out vec4 f;
                void main() { f = vec4(gl_PointCoord, 0.0, 1.0); }
                """);
            vertices(SHAPE);
            glDrawArrays(GL_POINTS, 0, 2);
            g.frame("upper left origin");
            glClear(GL_COLOR_BUFFER_BIT);
            glPointParameteri(GL_POINT_SPRITE_COORD_ORIGIN, GL_LOWER_LEFT);
            glDrawArrays(GL_POINTS, 0, 2);
            g.frame("lower left origin");
            glPointParameteri(GL_POINT_SPRITE_COORD_ORIGIN, GL_UPPER_LEFT);
            glDisable(GL_PROGRAM_POINT_SIZE);
        });
        g.s("doubles: signed zeros in min and max", () -> {
            GlConformanceScenarios.target();
            // Uniforms, so the compiler cannot fold min and max; the sign is read from the double's bits, since -0 == 0.
            final int p = program(SCREEN_VS, """
                #version 400 core
                uniform double x;
                uniform double y;
                out vec4 f;
                float sign(double v) { return float(unpackDouble2x32(v).y >> 31u); }
                void main() { f = vec4(sign(min(x, y)), sign(min(y, x)), sign(max(x, y)), sign(max(y, x))); }
                """);
            glUniform2f(glGetUniformLocation(p, "uvScale"), 1f, 1f);
            glUniform1d(glGetUniformLocation(p, "x"), 0.0);
            glUniform1d(glGetUniformLocation(p, "y"), -0.0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("min(0,-0) min(-0,0) max(0,-0) max(-0,0)", 8, 8);
        });
        g.s("draw: fixed point size", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            glPointSize(3f);
            glDrawArrays(GL_POINTS, 0, SHAPE.length);
            g.frame("size 3");
            glPointSize(1f);
        });
    }

    private void faces() {
        for (int front : new int[] {GL_CCW, GL_CW}) {
            for (int cull : new int[] {GL_BACK, GL_FRONT, GL_FRONT_AND_BACK}) {
                g.s("faces: front 0x" + Integer.toHexString(front) + " cull 0x" + Integer.toHexString(cull), () -> {
                    GlConformanceScenarios.target();
                    colored();
                    vertices(SHAPE);
                    glFrontFace(front);
                    glCullFace(cull);
                    glEnable(GL_CULL_FACE);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, SHAPE.length);
                    g.frame("image");
                });
            }
        }
        for (int mode : new int[] {GL_LINE, GL_POINT}) {
            g.s("faces: polygon mode 0x" + Integer.toHexString(mode), () -> {
                GlConformanceScenarios.target();
                colored();
                vertices(SHAPE);
                glPolygonMode(GL_FRONT_AND_BACK, mode);
                glDrawArrays(GL_TRIANGLES, 0, 6);
                g.frame("image");
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            });
        }
        g.s("faces: shader sees which side it is", () -> {
            GlConformanceScenarios.target();
            program(VS, """
                #version 330 core
                out vec4 f;
                void main() { f = gl_FrontFacing ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0); }
                """);
            vertices(SHAPE);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, SHAPE.length);
            g.frame("image");
        });
    }

    private void viewports() {
        g.s("viewport: off centre, scissored and flipped", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            glViewport(3, 5, 9, 7);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            g.frame("viewport");
            glViewport(0, 0, SIZE, SIZE);
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_SCISSOR_TEST);
            glScissor(2, 7, 6, 4);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            g.frame("scissor");
            glClearColor(0.25f, 0.5f, 0.75f, 1f);
            glScissor(9, 1, 3, 3);
            glClear(GL_COLOR_BUFFER_BIT);
            g.frame("scissored clear");
            glDisable(GL_SCISSOR_TEST);
        });
        g.s("viewport: past the framebuffer's edges", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            glViewport(-6, -4, SIZE + 3, SIZE + 9);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            g.frame("image");
        });
        g.s("viewport: clipping against the volume", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(v(-1.5f, -0.5f, 0f, 1f, 0f, 0f, 1f), v(1.5f, -0.5f, 0f, 0f, 1f, 0f, 1f), v(0.1f, 1.7f, 0f, 0f, 0f, 1f, 1f));
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("x and y");
            glClear(GL_COLOR_BUFFER_BIT);
            vertices(v(-0.8f, -0.8f, -1.5f, 1f, 0f, 0f, 1f), v(0.8f, -0.8f, 1.5f, 0f, 1f, 0f, 1f), v(0.1f, 0.8f, 0f, 0f, 0f, 1f, 1f));
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("z");
        });
        g.s("viewport: clip distances", () -> {
            GlConformanceScenarios.target();
            program("""
                #version 330 core
                layout(location = 0) in vec4 p;
                out float gl_ClipDistance[2];
                void main() { gl_Position = p; gl_ClipDistance[0] = p.x + 0.13; gl_ClipDistance[1] = 0.37 - p.y; }
                """, """
                #version 330 core
                out vec4 f;
                void main() { f = vec4(0.0, 1.0, 0.0, 1.0); }
                """);
            vertices(v(-1f, -1f, 0f, 0f, 0f, 0f, 1f), v(1f, -1f, 0f, 0f, 0f, 0f, 1f), v(-1f, 1f, 0f, 0f, 0f, 0f, 1f), v(1f, 1f, 0f, 0f, 0f, 0f, 1f));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.frame("not enabled");
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_CLIP_DISTANCE0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.frame("first");
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_CLIP_DISTANCE1);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.frame("both");
            glDisable(GL_CLIP_DISTANCE0);
            glDisable(GL_CLIP_DISTANCE1);
        });
    }

    private void depth() {
        final int[] funcs = {GL_NEVER, GL_LESS, GL_EQUAL, GL_LEQUAL, GL_GREATER, GL_NOTEQUAL, GL_GEQUAL, GL_ALWAYS};
        for (int func : funcs) {
            g.s("depth: func 0x" + Integer.toHexString(func), () -> {
                GlConformanceScenarios.target();
                colored();
                glEnable(GL_DEPTH_TEST);
                quad(-0.71f, -0.71f, 0.31f, 0.53f, 0f, 1f, 0f, 0f, 1f);
                glDepthFunc(func);
                quad(-0.29f, -0.43f, 0.77f, 0.87f, 0.25f, 0f, 1f, 0f, 1f);
                quad(-0.91f, 0.11f, 0.13f, 0.93f, -0.25f, 0f, 0f, 1f, 1f);
                quad(-0.47f, -0.13f, -0.09f, 0.27f, 0f, 1f, 1f, 0f, 1f);
                g.frame("image");
                glDepthFunc(GL_LESS);
                glDisable(GL_DEPTH_TEST);
            });
        }
        g.s("depth: mask, range, clamp and values", () -> {
            GlConformanceScenarios.target();
            colored();
            glEnable(GL_DEPTH_TEST);
            glDepthMask(false);
            quad(-0.7f, -0.7f, 0.7f, 0.7f, -0.5f, 1f, 0f, 0f, 1f);
            glDepthMask(true);
            quad(-0.4f, -0.4f, 0.4f, 0.4f, 0.5f, 0f, 1f, 0f, 1f);
            g.frame("mask off writes no depth");
            g.depthAt("written", 8, 8);
            g.depthAt("unwritten", 1, 8);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glDepthRange(0.25, 0.75);
            quad(-0.8f, -0.8f, 0.8f, 0.8f, 0.5f, 0f, 0f, 1f, 1f);
            g.depthAt("through a range", 8, 8);
            glDepthRange(0.75, 0.25);
            glClear(GL_DEPTH_BUFFER_BIT);
            quad(-0.8f, -0.8f, 0.8f, 0.8f, 0.5f, 0f, 0f, 1f, 1f);
            g.depthAt("through a reversed range", 8, 8);
            glDepthRange(0.0, 1.0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            vertices(v(-0.8f, -0.8f, 2f, 1f, 0f, 0f, 1f), v(0.8f, -0.8f, 2f, 0f, 1f, 0f, 1f), v(0f, 0.8f, 2f, 0f, 0f, 1f, 1f));
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("past far is clipped");
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_CLAMP);
            glDepthFunc(GL_ALWAYS);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("clamp keeps it");
            g.depthAt("clamped", 8, 6);
            glDisable(GL_DEPTH_CLAMP);
            glDepthFunc(GL_LESS);
            glDisable(GL_DEPTH_TEST);
        });
        g.s("depth: polygon offset", () -> {
            GlConformanceScenarios.target();
            colored();
            glEnable(GL_DEPTH_TEST);
            quad(-0.8f, -0.8f, 0.8f, 0.8f, 0f, 1f, 0f, 0f, 1f);
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(0f, -8f);
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 0f, 1f);
            g.frame("pulled forward wins");
            glPolygonOffset(0f, 8f);
            quad(-0.3f, -0.3f, 0.3f, 0.3f, 0f, 0f, 0f, 1f, 1f);
            g.frame("pushed back loses");
            glDisable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(0f, 0f);
            glDisable(GL_DEPTH_TEST);
        });
        g.s("depth: written by the shader", () -> {
            GlConformanceScenarios.target();
            glEnable(GL_DEPTH_TEST);
            program(VS, """
                #version 330 core
                in vec4 v;
                out vec4 f;
                void main() { f = v; gl_FragDepth = gl_FragCoord.x / 16.0; }
                """);
            quad(-1f, -1f, 1f, 1f, 0f, 1f, 0f, 0f, 1f);
            colored();
            quad(-1f, -1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f);
            g.frame("image");
            glDisable(GL_DEPTH_TEST);
        });
    }

    private void stencil() {
        final int[] funcs = {GL_NEVER, GL_LESS, GL_EQUAL, GL_LEQUAL, GL_GREATER, GL_NOTEQUAL, GL_GEQUAL, GL_ALWAYS};
        for (int func : funcs) {
            g.s("stencil: func 0x" + Integer.toHexString(func), () -> {
                GlConformanceScenarios.target();
                colored();
                glEnable(GL_STENCIL_TEST);
                glStencilFunc(GL_ALWAYS, 5, 0xFF);
                glStencilOp(GL_REPLACE, GL_REPLACE, GL_REPLACE);
                quad(-0.6f, -0.7f, 0.2f, 0.4f, 0f, 0f, 0f, 0f, 0f);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
                glStencilFunc(func, 5, 0xFF);
                quad(-0.9f, -0.9f, 0.9f, 0.9f, 0f, 1f, 0f, 0f, 1f);
                g.frame("image");
                glDisable(GL_STENCIL_TEST);
            });
        }
        final int[] ops = {GL_KEEP, GL_ZERO, GL_REPLACE, GL_INCR, GL_INCR_WRAP, GL_DECR, GL_DECR_WRAP, GL_INVERT};
        for (int op : ops) {
            g.s("stencil: op 0x" + Integer.toHexString(op), () -> {
                GlConformanceScenarios.target();
                colored();
                glEnable(GL_STENCIL_TEST);
                glEnable(GL_DEPTH_TEST);
                glStencilFunc(GL_ALWAYS, 3, 0xFF);
                glStencilOp(GL_REPLACE, GL_REPLACE, GL_REPLACE);
                quad(-1f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f);
                glStencilFunc(GL_ALWAYS, 7, 0xFF);
                glStencilOp(GL_KEEP, GL_KEEP, op);
                quad(-0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0f, 0f, 0f, 0f);
                g.stencilAt("passing", 8, 8);
                g.stencilAt("untouched", 1, 1);
                glStencilOp(GL_KEEP, op, GL_KEEP);
                quad(-0.3f, -0.3f, 0.3f, 0.3f, 0.5f, 0f, 0f, 0f, 0f);
                g.stencilAt("depth failing", 8, 8);
                glStencilFunc(GL_NEVER, 7, 0xFF);
                glStencilOp(op, GL_KEEP, GL_KEEP);
                quad(-0.2f, -0.2f, 0.2f, 0.2f, 0f, 0f, 0f, 0f, 0f);
                g.stencilAt("stencil failing", 8, 8);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_STENCIL_TEST);
            });
        }
        g.s("stencil: masks and two sides", () -> {
            GlConformanceScenarios.target();
            colored();
            glEnable(GL_STENCIL_TEST);
            glStencilMask(0x0F);
            glStencilFunc(GL_ALWAYS, 0xFF, 0xFF);
            glStencilOp(GL_REPLACE, GL_REPLACE, GL_REPLACE);
            quad(-1f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f);
            g.stencilAt("write mask", 8, 8);
            glStencilMask(0xFF);
            glStencilFunc(GL_EQUAL, 0x0F, 0x0F);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            quad(-0.7f, -0.7f, 0.7f, 0.7f, 0f, 0f, 1f, 0f, 1f);
            g.frame("value mask");
            glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            glStencilFuncSeparate(GL_FRONT, GL_ALWAYS, 1, 0xFF);
            glStencilFuncSeparate(GL_BACK, GL_ALWAYS, 2, 0xFF);
            glStencilOpSeparate(GL_FRONT_AND_BACK, GL_KEEP, GL_KEEP, GL_REPLACE);
            vertices(SHAPE);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, SHAPE.length);
            for (int y = 2; y < SIZE; y += 4) {
                for (int x = 2; x < SIZE; x += 4) g.stencilAt("sides " + x + "," + y, x, y);
            }
            glStencilFunc(GL_ALWAYS, 0, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            glDisable(GL_STENCIL_TEST);
        });
    }

    private void blending() {
        final int[] factors = {GL_ZERO, GL_ONE, GL_SRC_COLOR, GL_ONE_MINUS_SRC_COLOR, GL_DST_COLOR, GL_ONE_MINUS_DST_COLOR, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_DST_ALPHA,
            GL_CONSTANT_COLOR, GL_ONE_MINUS_CONSTANT_COLOR, GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA, GL_SRC_ALPHA_SATURATE};
        g.s("blend: every pair of factors", () -> {
            GlConformanceScenarios.target();
            colored();
            glEnable(GL_BLEND);
            glBlendColor(0.2f, 0.4f, 0.6f, 0.8f);
            for (int src : factors) {
                for (int dst : factors) {
                    if (dst == GL_SRC_ALPHA_SATURATE) continue;
                    glClearColor(0.75f, 0.5f, 0.25f, 0.6f);
                    glClear(GL_COLOR_BUFFER_BIT);
                    glBlendFunc(src, dst);
                    quad(-1f, -1f, 1f, 1f, 0f, 0.9f, 0.3f, 0.5f, 0.7f);
                    g.pixelAt(Integer.toHexString(src) + "," + Integer.toHexString(dst), 8, 8);
                }
            }
            glBlendFunc(GL_ONE, GL_ZERO);
            glDisable(GL_BLEND);
        });
        for (int equation : new int[] {GL_FUNC_ADD, GL_FUNC_SUBTRACT, GL_FUNC_REVERSE_SUBTRACT, GL_MIN, GL_MAX}) {
            g.s("blend: equation 0x" + Integer.toHexString(equation), () -> {
                GlConformanceScenarios.target();
                colored();
                glEnable(GL_BLEND);
                glClearColor(0.75f, 0.5f, 0.25f, 0.6f);
                glClear(GL_COLOR_BUFFER_BIT);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glBlendEquation(equation);
                quad(-1f, -1f, 1f, 1f, 0f, 0.9f, 0.3f, 0.5f, 0.7f);
                g.pixelAt("result", 8, 8);
                glBlendEquation(GL_FUNC_ADD);
                glBlendFunc(GL_ONE, GL_ZERO);
                glDisable(GL_BLEND);
            });
        }
        g.s("blend: separate alpha, and per attachment", () -> {
            GlConformanceScenarios.target();
            colored();
            glEnable(GL_BLEND);
            glClearColor(0.75f, 0.5f, 0.25f, 0.6f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_MAX);
            quad(-1f, -1f, 1f, 1f, 0f, 0.9f, 0.3f, 0.5f, 0.7f);
            g.pixelAt("separate", 8, 8);
            glBlendEquation(GL_FUNC_ADD);
            glBlendFunc(GL_ONE, GL_ZERO);
            glDisable(GL_BLEND);
        });
        g.s("blend: dual source", () -> {
            GlConformanceScenarios.target();
            glEnable(GL_BLEND);
            glClearColor(0.5f, 0.5f, 0.5f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            program(VS, """
                #version 330 core
                layout(location = 0, index = 0) out vec4 a;
                layout(location = 0, index = 1) out vec4 b;
                void main() { a = vec4(1.0, 0.0, 0.0, 1.0); b = vec4(0.25, 0.5, 0.75, 1.0); }
                """);
            glBlendFunc(GL_ONE, GL_SRC1_COLOR);
            quad(-1f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f);
            g.pixelAt("result", 8, 8);
            glBlendFunc(GL_ONE, GL_ZERO);
            glDisable(GL_BLEND);
        });
        g.s("blend: into a float target, unclamped", () -> {
            final int f = GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, t);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, SIZE, SIZE, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
            colored();
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            quad(-1f, -1f, 1f, 1f, 0f, 1.5f, 0.5f, 0.25f, 1f);
            quad(-1f, -1f, 1f, 1f, 0f, 1.5f, 0.5f, 0.25f, 1f);
            g.floatsAt("result", 8, 8);
            glBlendFunc(GL_ONE, GL_ZERO);
            glDisable(GL_BLEND);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(f);
        });
    }

    private void masksAndLogic() {
        for (int mask = 0; mask < 16; mask++) {
            final int m = mask;
            g.s("mask: color " + m, () -> {
                GlConformanceScenarios.target();
                colored();
                glClearColor(0.25f, 0.5f, 0.75f, 1f);
                glClear(GL_COLOR_BUFFER_BIT);
                glColorMask((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
                quad(-1f, -1f, 1f, 1f, 0f, 1f, 0f, 0f, 0.5f);
                g.pixelAt("drawn", 8, 8);
                glClearColor(0f, 1f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
                g.pixelAt("cleared", 8, 8);
                glColorMask(true, true, true, true);
            });
        }
        for (int op = GL_CLEAR; op <= GL_SET; op++) {
            final int o = op;
            g.s("logic: op 0x" + Integer.toHexString(o), () -> {
                GlConformanceScenarios.target();
                colored();
                glClearColor(0.25f, 0.5f, 0.75f, 1f);
                glClear(GL_COLOR_BUFFER_BIT);
                glEnable(GL_COLOR_LOGIC_OP);
                glLogicOp(o);
                quad(-1f, -1f, 1f, 1f, 0f, 0.8f, 0.4f, 0.2f, 1f);
                g.pixelAt("result", 8, 8);
                glLogicOp(GL_COPY);
                glDisable(GL_COLOR_LOGIC_OP);
            });
        }
    }

    private void attributes() {
        final String show = """
            #version 330 core
            layout(location = 0) in vec4 p;
            layout(location = 1) in vec4 c;
            out vec4 v;
            void main() { gl_Position = vec4(-1.0 + 2.0 * float(gl_VertexID % 2), -1.0 + float(gl_VertexID & 2), 0.0, 1.0); v = c; }
            """;
        final Object[][] layouts = {
            {"normalized bytes", 4, GL_UNSIGNED_BYTE, true, bytes(255, 128, 0, 64)},
            {"bytes as they are", 4, GL_UNSIGNED_BYTE, false, bytes(1, 0, 0, 1)},
            {"signed normalized", 4, GL_BYTE, true, bytes(127, -127, 64, 127)},
            {"normalized shorts", 4, GL_UNSIGNED_SHORT, true, bytes(0, 128, 255, 255, 0, 64, 255, 255)},
            {"half floats", 4, GL_HALF_FLOAT, false, bytes(0, 56, 0, 60, 0, 52, 0, 60)},
            {"two components", 2, GL_FLOAT, false, floats(0.25f, 0.75f)},
            {"three components", 3, GL_FLOAT, false, floats(0.25f, 0.75f, 0.5f)},
            {"bgra", GL_BGRA, GL_UNSIGNED_BYTE, true, bytes(255, 128, 0, 64)},
            {"packed", 4, GL_UNSIGNED_INT_2_10_10_10_REV, true, bytes(0xFF, 0x03, 0x08, 0x40)},
            {"packed signed", 4, GL_INT_2_10_10_10_REV, true, bytes(0xFF, 0x01, 0x04, 0x40)}};
        for (Object[] layout : layouts) {
            g.s("attrib: " + layout[0], () -> {
                GlConformanceScenarios.target();
                program(show, FS);
                final int vbo = glGenBuffers();
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                final ByteBuffer one = (ByteBuffer) layout[4];
                final ByteBuffer four = BufferUtils.createByteBuffer(one.remaining() * 4);
                for (int i = 0; i < 4; i++) four.put(one.duplicate());
                four.flip();
                glBufferData(GL_ARRAY_BUFFER, four, GL_STATIC_DRAW);
                glEnableVertexAttribArray(1);
                glVertexAttribPointer(1, (int) layout[1], (int) layout[2], (boolean) layout[3], 0, 0L);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                g.pixelAt("colour", 8, 8);
            });
        }
        g.s("attrib: a constant when no array is enabled", () -> {
            GlConformanceScenarios.target();
            program(show, FS);
            glDisableVertexAttribArray(1);
            glVertexAttrib4f(1, 0.25f, 0.5f, 0.75f, 1f);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("colour", 8, 8);
            glVertexAttrib4f(1, 0f, 0f, 0f, 1f);
        });
        g.s("attrib: integers reach an integer input", () -> {
            GlConformanceScenarios.target();
            program("""
                #version 330 core
                layout(location = 1) in ivec2 c;
                flat out ivec2 v;
                void main() { gl_Position = vec4(-1.0 + 2.0 * float(gl_VertexID % 2), -1.0 + float(gl_VertexID & 2), 0.0, 1.0); v = c; }
                """, """
                #version 330 core
                flat in ivec2 v;
                out vec4 f;
                void main() { f = vec4(float(v.x) / 255.0, float(v.y + 100) / 255.0, 0.0, 1.0); }
                """);
            final int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            final ByteBuffer data = BufferUtils.createByteBuffer(32);
            for (int i = 0; i < 4; i++) data.putInt(200).putInt(-50);
            data.flip();
            data.order(java.nio.ByteOrder.nativeOrder());
            final IntBuffer ints = BufferUtils.createIntBuffer(8);
            for (int i = 0; i < 4; i++) ints.put(200).put(-50);
            ints.flip();
            glBufferData(GL_ARRAY_BUFFER, ints, GL_STATIC_DRAW);
            glEnableVertexAttribArray(1);
            glVertexAttribIPointer(1, 2, GL_INT, 0, 0L);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("colour", 8, 8);
        });
        g.s("attrib: stride, offset and instances", () -> {
            GlConformanceScenarios.target();
            program("""
                #version 330 core
                layout(location = 0) in vec2 p;
                layout(location = 1) in vec2 o;
                layout(location = 2) in vec4 c;
                out vec4 v;
                void main() { gl_Position = vec4(p + o, 0.0, 1.0); v = c + vec4(0.0, 0.0, float(gl_InstanceID) * 0.25, 0.0); }
                """, FS);
            final FloatBuffer shared = BufferUtils.createFloatBuffer(100);
            shared.put(new float[] {9f, -0.3f, -0.3f, 9f, 0.3f, -0.3f, 9f, -0.3f, 0.3f, 9f, 0.3f, 0.3f});
            shared.put(new float[] {-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f});
            shared.put(new float[] {1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f});
            shared.flip();
            final int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, shared, GL_STATIC_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 12, 4L);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 48L);
            glVertexAttribDivisor(1, 1);
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, 0, 80L);
            glVertexAttribDivisor(2, 2);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, 4);
            g.frame("image");
        });
    }

    private void fragmentInputs() {
        g.s("fragment: position, discard and derivatives", () -> {
            GlConformanceScenarios.target();
            screen("""
                #version 330 core
                in vec2 uv;
                out vec4 f;
                void main() { f = vec4(gl_FragCoord.x / 16.0, gl_FragCoord.y / 16.0, gl_FragCoord.z, 1.0); }
                """, 1f);
            g.frame("position");
            glClear(GL_COLOR_BUFFER_BIT);
            screen("""
                #version 330 core
                in vec2 uv;
                out vec4 f;
                void main() { if (uv.x < uv.y) discard; f = vec4(0.0, 1.0, 0.0, 1.0); }
                """, 1f);
            g.frame("discard");
            glClear(GL_COLOR_BUFFER_BIT);
            screen("""
                #version 330 core
                in vec2 uv;
                out vec4 f;
                void main() { f = vec4(dFdx(uv.x) * 8.0, dFdy(uv.y) * 8.0, 0.0, 1.0); }
                """, 1f);
            g.frame("derivatives");
        });
        for (int provoking : new int[] {GL_LAST_VERTEX_CONVENTION, GL_FIRST_VERTEX_CONVENTION}) {
            g.s("fragment: flat shading 0x" + Integer.toHexString(provoking), () -> {
                GlConformanceScenarios.target();
                program("""
                    #version 330 core
                    layout(location = 0) in vec4 p;
                    layout(location = 1) in vec4 c;
                    flat out vec4 v;
                    void main() { gl_Position = p; v = c; }
                    """, """
                    #version 330 core
                    flat in vec4 v;
                    out vec4 f;
                    void main() { f = v; }
                    """);
                glProvokingVertex(provoking);
                vertices(SHAPE);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                g.frame("triangles");
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, SHAPE.length);
                g.frame("strip");
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLE_FAN, 0, SHAPE.length);
                g.frame("fan");
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_LINE_LOOP, 0, 4);
                g.frame("loop");
                glProvokingVertex(GL_LAST_VERTEX_CONVENTION);
            });
        }
        g.s("fragment: interpolation", () -> {
            GlConformanceScenarios.target();
            program("""
                #version 330 core
                out vec2 a;
                noperspective out vec2 b;
                out vec2 w;
                void main() {
                    vec2 q = vec2(gl_VertexID == 1 ? 1.0 : -1.0, gl_VertexID == 2 ? 1.0 : -1.0);
                    float d = gl_VertexID == 2 ? 3.0 : 1.0;
                    gl_Position = vec4(q * d, 0.0, d);
                    a = vec2(gl_VertexID == 1 ? 1.0 : 0.0, gl_VertexID == 2 ? 1.0 : 0.0);
                    b = a;
                }
                """, """
                #version 330 core
                in vec2 a;
                noperspective in vec2 b;
                out vec4 f;
                void main() { f = vec4(a, b.y, 1.0); }
                """);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("image");
        });
    }

    private void textureSampling() {
        final int[] minFilters = {GL_NEAREST, GL_LINEAR, GL_NEAREST_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_NEAREST, GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR};
        for (int min : minFilters) {
            g.s("sample: min filter 0x" + Integer.toHexString(min), () -> {
                GlConformanceScenarios.target();
                mipped();
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, min);
                screen(SAMPLE, 3f);
                g.frame("shrunk");
            });
        }
        g.s("sample: mag filter", () -> {
            GlConformanceScenarios.target();
            texture2D(2, 2, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            screen(SAMPLE, 1f);
            g.frame("nearest");
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            screen(SAMPLE, 1f);
            g.frame("linear");
        });
        for (int wrap : new int[] {GL_REPEAT, GL_MIRRORED_REPEAT, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_BORDER}) {
            g.s("sample: wrap 0x" + Integer.toHexString(wrap), () -> {
                GlConformanceScenarios.target();
                texture2D(2, 2, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, checker());
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
                final FloatBuffer border = BufferUtils.createFloatBuffer(4);
                border.put(new float[] {0.25f, 0.5f, 0.75f, 1f}).flip();
                glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, border);
                screen(SAMPLE, 2.5f);
                g.frame("past the edge");
            });
        }
        g.s("sample: levels, bias and limits", () -> {
            GlConformanceScenarios.target();
            mipped();
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
            screen(SAMPLE, 1f);
            g.frame("no minification");
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 1);
            screen(SAMPLE, 1f);
            g.frame("base level");
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            screen(SAMPLE, 3f);
            g.frame("max level");
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 2);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, 1f);
            screen(SAMPLE, 1f);
            g.frame("bias");
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, 0f);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_LOD, 1f);
            screen(SAMPLE, 1f);
            g.frame("min lod");
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_LOD, -1000f);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_LOD, 0f);
            screen(SAMPLE, 3f);
            g.frame("max lod");
        });
        g.s("sample: generated mipmaps", () -> {
            GlConformanceScenarios.target();
            texture2D(4, 4, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, solid(4, 200, 40, 40));
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
            screen(SAMPLE, 3f);
            g.frame("image");
        });
        g.s("sample: an incomplete texture reads as black", () -> {
            GlConformanceScenarios.target();
            final int t = texture2D(4, 4, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, solid(4, 200, 100, 50));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            screen(SAMPLE, 1f);
            g.pixelAt("no mipmaps", 8, 8);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            screen(SAMPLE, 1f);
            g.pixelAt("max level makes one enough", 8, 8);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 1000);
            glGenerateMipmap(GL_TEXTURE_2D);
            screen(SAMPLE, 1f);
            g.pixelAt("generated", 8, 8);
            glDeleteTextures(t);
            glBindTexture(GL_TEXTURE_2D, 0);
            screen(SAMPLE, 1f);
            g.pixelAt("nothing bound", 8, 8);
        });
        g.s("sample: swizzle", () -> {
            GlConformanceScenarios.target();
            texture2D(2, 2, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_G, GL_ONE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, GL_RED);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ZERO);
            screen(SAMPLE, 1f);
            g.frame("image");
        });
        g.s("sample: sampler object beats texture", () -> {
            GlConformanceScenarios.target();
            texture2D(2, 2, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            final int sampler = glGenSamplers();
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glBindSampler(0, sampler);
            screen(SAMPLE, 1.5f);
            g.frame("bound");
            glBindSampler(0, 0);
            glDeleteSamplers(sampler);
            screen(SAMPLE, 1.5f);
            g.frame("unbound");
        });
        final String[][] shaders = {
            {"fetch", "f = texelFetch(t, ivec2(floor(uv * 2.0)), 0);"},
            {"size", "f = vec4(vec2(textureSize(t, 0)) / 8.0, 0.0, 1.0);"},
            {"offset", "f = textureOffset(t, uv, ivec2(1, 0));"},
            {"lod", "f = textureLod(t, uv, 1.0);"},
            {"grad", "f = textureGrad(t, uv, vec2(0.5, 0.0), vec2(0.0, 0.5));"},
            {"projected", "f = textureProj(t, vec3(uv, 2.0));"},
            {"gather red", "f = textureGather(t, uv);"},
            {"gather green", "f = textureGather(t, uv, 1);"}};
        for (String[] s : shaders) {
            g.s("sample: " + s[0], () -> {
                GlConformanceScenarios.target();
                mipped();
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
                screen("#version 400 core\nin vec2 uv;\nuniform sampler2D t;\nout vec4 f;\nvoid main() { " + s[1] + " }\n", 1f);
                g.frame("image");
            });
        }
        final int[] compares = {GL_NEVER, GL_LESS, GL_EQUAL, GL_LEQUAL, GL_GREATER, GL_NOTEQUAL, GL_GEQUAL, GL_ALWAYS};
        for (int func : compares) {
            g.s("sample: shadow 0x" + Integer.toHexString(func), () -> {
                GlConformanceScenarios.target();
                final FloatBuffer depths = BufferUtils.createFloatBuffer(4);
                depths.put(new float[] {0.25f, 0.5f, 0.5f, 0.75f}).flip();
                final int t = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, t);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, 2, 2, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depths);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, func);
                screen("#version 330 core\nin vec2 uv;\nuniform sampler2DShadow t;\nout vec4 f;\nvoid main() { f = vec4(vec3(texture(t, vec3(uv, 0.5))), 1.0); }\n", 1f);
                g.frame("image");
            });
        }
    }

    private static ByteBuffer solid(int size, int r, int g, int b) {
        final ByteBuffer out = BufferUtils.createByteBuffer(size * size * 4);
        for (int i = 0; i < size * size; i++) out.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
        out.flip();
        return out;
    }

    /** A chain where each level is a colour of its own, so which level was sampled shows. */
    private static int mipped() {
        final int t = texture2D(4, 4, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, solid(4, 255, 0, 0));
        glTexImage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, solid(2, 0, 255, 0));
        glTexImage2D(GL_TEXTURE_2D, 2, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, solid(1, 0, 0, 255));
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 2);
        return t;
    }

    private void textureFormats() {
        final ByteBuffer shared = BufferUtils.createByteBuffer(64);
        final Object[][] formats = {
            {GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, new int[] {200, 100, 50, 150}},
            {GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE, new int[] {200, 100, 50}},
            {GL_RG8, GL_RG, GL_UNSIGNED_BYTE, new int[] {200, 100}},
            {GL_R8, GL_RED, GL_UNSIGNED_BYTE, new int[] {200}},
            {GL_RGBA8_SNORM, GL_RGBA, GL_BYTE, new int[] {100, 50, 25, 127}},
            {GL_SRGB8_ALPHA8, GL_RGBA, GL_UNSIGNED_BYTE, new int[] {200, 100, 50, 150}},
            {GL_SRGB8, GL_RGB, GL_UNSIGNED_BYTE, new int[] {200, 100, 50}},
            {GL_RGB565, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, new int[] {0x34, 0x12}},
            {GL_RGBA4, GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, new int[] {0x34, 0x12}},
            {GL_RGB5_A1, GL_RGBA, GL_UNSIGNED_SHORT_5_5_5_1, new int[] {0x35, 0x12}},
            {GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, new int[] {0x34, 0x12, 0x78, 0x56}},
            {GL_R11F_G11F_B10F, GL_RGB, GL_UNSIGNED_INT_10F_11F_11F_REV, new int[] {0x34, 0x12, 0x78, 0x56}},
            {GL_RGB9_E5, GL_RGB, GL_UNSIGNED_INT_5_9_9_9_REV, new int[] {0x34, 0x12, 0x78, 0x76}},
            {GL_RGBA16, GL_RGBA, GL_UNSIGNED_SHORT, new int[] {0, 128, 0, 64, 0, 32, 255, 255}},
            {GL_R16, GL_RED, GL_UNSIGNED_SHORT, new int[] {0, 128}},
            {GL_RG16, GL_RG, GL_UNSIGNED_SHORT, new int[] {0, 128, 0, 64}},
            {GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, new int[] {0, 56, 0, 52, 0, 48, 0, 60}},
            {GL_RG16F, GL_RG, GL_HALF_FLOAT, new int[] {0, 56, 0, 52}},
            {GL_R16F, GL_RED, GL_HALF_FLOAT, new int[] {0, 56}},
            {GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, new int[] {200, 100, 50, 150}},
            {GL_RGB, GL_RGB, GL_UNSIGNED_BYTE, new int[] {200, 100, 50}},
            {GL_RG, GL_RG, GL_UNSIGNED_BYTE, new int[] {200, 100}},
            {GL_RED, GL_RED, GL_UNSIGNED_BYTE, new int[] {200}}};
        for (Object[] format : formats) {
            g.s("format: sample 0x" + Integer.toHexString((int) format[0]), () -> {
                GlConformanceScenarios.target();
                texture2D(1, 1, (int) format[0], (int) format[1], (int) format[2], bytes((int[]) format[3]));
                screen(SAMPLE, 1f);
                g.pixelAt("sampled", 8, 8);
            });
        }
        g.s("format: integer textures", () -> {
            GlConformanceScenarios.target();
            texture2D(1, 1, GL_RGBA8UI, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, bytes(200, 100, 50, 150));
            screen("#version 330 core\nin vec2 uv;\nuniform usampler2D t;\nout vec4 f;\nvoid main() { f = vec4(texture(t, uv)) / 255.0; }\n", 1f);
            g.pixelAt("unsigned", 8, 8);
            texture2D(1, 1, GL_RGBA8I, GL_RGBA_INTEGER, GL_BYTE, bytes(-100, 50, 25, 100));
            screen("#version 330 core\nin vec2 uv;\nuniform isampler2D t;\nout vec4 f;\nvoid main() { f = (vec4(texture(t, uv)) + 128.0) / 255.0; }\n", 1f);
            g.pixelAt("signed", 8, 8);
        });
        g.s("format: compressed", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, t);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glCompressedTexImage2D(GL_TEXTURE_2D, 0, GL_COMPRESSED_RED_RGTC1, 4, 4, 0, bytes(0xFF, 0x00, 0, 0, 0, 0, 0, 0));
            screen(SAMPLE, 1f);
            g.frame("rgtc1");
            glCompressedTexImage2D(GL_TEXTURE_2D, 0, GL_COMPRESSED_RG_RGTC2, 4, 4, 0, bytes(0xFF, 0x00, 0, 0, 0, 0, 0, 0, 0x80, 0x00, 0, 0, 0, 0, 0, 0));
            screen(SAMPLE, 1f);
            g.frame("rgtc2");
        });
        g.s("format: alpha of a target with none", () -> {
            GlConformanceScenarios.target();
            final int t = texture2D(1, 1, GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE, bytes(10, 20, 30));
            final ByteBuffer back = BufferUtils.createByteBuffer(4);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, back);
            g.record("px:read back", (back.get(0) & 0xFF) + "," + (back.get(1) & 0xFF) + "," + (back.get(2) & 0xFF) + "," + (back.get(3) & 0xFF));
            glDeleteTextures(t);
        });
    }

    private void textureKinds() {
        g.s("kind: 1D", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_1D, t);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage1D(GL_TEXTURE_1D, 0, GL_RGBA8, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            screen("#version 330 core\nin vec2 uv;\nuniform sampler1D t;\nout vec4 f;\nvoid main() { f = texture(t, uv.x); }\n", 1f);
            g.frame("image");
        });
        g.s("kind: 1D array", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_1D_ARRAY, t);
            glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_1D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_1D_ARRAY, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            screen("#version 330 core\nin vec2 uv;\nuniform sampler1DArray t;\nout vec4 f;\nvoid main() { f = texture(t, vec2(uv.x, floor(uv.y * 2.0))); }\n", 1f);
            g.frame("image");
        });
        g.s("kind: 2D array", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, t);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 1, 1, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            screen("#version 330 core\nin vec2 uv;\nuniform sampler2DArray t;\nout vec4 f;\nvoid main() { f = texture(t, vec3(uv, floor(uv.x * 4.0))); }\n", 1f);
            g.frame("image");
        });
        g.s("kind: 3D", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, t);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, 1, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            screen("#version 330 core\nin vec2 uv;\nuniform sampler3D t;\nout vec4 f;\nvoid main() { f = texture(t, vec3(0.5, uv)); }\n", 1f);
            g.frame("image");
        });
        g.s("kind: cube", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_CUBE_MAP, t);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            final int[][] sides = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0}, {0, 255, 255}, {255, 0, 255}};
            for (int face = 0; face < 6; face++) {
                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, solid(1, sides[face][0], sides[face][1], sides[face][2]));
            }
            final String[] directions = {"vec3(1.0, q.yx)", "vec3(-1.0, q)", "vec3(q.x, 1.0, q.y)", "vec3(q.x, -1.0, q.y)", "vec3(q, 1.0)", "vec3(q, -1.0)"};
            for (String direction : directions) {
                screen("#version 330 core\nin vec2 uv;\nuniform samplerCube t;\nout vec4 f;\nvoid main() { vec2 q = uv * 2.0 - 1.0; f = texture(t, " + direction + "); }\n", 1f);
                g.pixelAt(direction, 8, 8);
            }
        });
        g.s("kind: rectangle", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_RECTANGLE, t);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_RECTANGLE, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            screen("#version 330 core\nin vec2 uv;\nuniform sampler2DRect t;\nout vec4 f;\nvoid main() { f = texture(t, uv * 2.0); }\n", 1f);
            g.frame("image");
        });
        g.s("kind: buffer", () -> {
            GlConformanceScenarios.target();
            final int b = glGenBuffers();
            glBindBuffer(GL_TEXTURE_BUFFER, b);
            glBufferData(GL_TEXTURE_BUFFER, checker(), GL_STATIC_DRAW);
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_BUFFER, t);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA8, b);
            screen("#version 330 core\nin vec2 uv;\nuniform samplerBuffer t;\nout vec4 f;\nvoid main() { f = texelFetch(t, int(uv.x * 4.0)); }\n", 1f);
            g.frame("image");
        });
        g.s("kind: sub images and pixel store", () -> {
            GlConformanceScenarios.target();
            texture2D(4, 4, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, solid(4, 40, 40, 40));
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 2);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 1);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 1, 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            screen(SAMPLE, 1f);
            g.frame("image");
        });
        g.s("kind: several units", () -> {
            GlConformanceScenarios.target();
            glActiveTexture(GL_TEXTURE1);
            texture2D(1, 1, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, bytes(0, 255, 0, 255));
            glActiveTexture(GL_TEXTURE0);
            texture2D(1, 1, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, bytes(255, 0, 0, 255));
            final int p = program(SCREEN_VS, "#version 330 core\nin vec2 uv;\nuniform sampler2D a;\nuniform sampler2D b;\nout vec4 f;\nvoid main() { f = uv.x < 0.5 ? texture(a, uv) : texture(b, uv); }\n");
            glUniform2f(glGetUniformLocation(p, "uvScale"), 1f, 1f);
            glUniform1i(glGetUniformLocation(p, "a"), 0);
            glUniform1i(glGetUniformLocation(p, "b"), 1);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.frame("image");
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE0);
        });
    }

    private void counted(String key, int target, GlConformanceScenarios.Body body) throws Throwable {
        final int q = glGenQueries();
        glBeginQuery(target, q);
        body.run();
        glEndQuery(target);
        g.record(key, glGetQueryObjecti(q, GL_QUERY_RESULT));
        g.record(key + " then available", glGetQueryObjecti(q, GL_QUERY_RESULT_AVAILABLE));
    }

    private static int passing(GlConformanceScenarios.Body body) throws Throwable {
        final int q = glGenQueries();
        glBeginQuery(GL_SAMPLES_PASSED, q);
        body.run();
        glEndQuery(GL_SAMPLES_PASSED);
        return q;
    }

    private void occlusion() {
        for (int target : new int[] {GL_SAMPLES_PASSED, GL_ANY_SAMPLES_PASSED}) {
            final String name = target == GL_SAMPLES_PASSED ? "samples" : "any";
            g.s("occlusion " + name + ": what passes is counted", () -> {
                GlConformanceScenarios.target();
                colored();
                counted("half of the target", target, () -> quad(-1f, -1f, 1f, 0f, 0f, 1f, 0f, 0f, 1f));
                counted("nothing drawn", target, () -> { });
                counted("off the target", target, () -> quad(2f, 2f, 3f, 3f, 0f, 1f, 0f, 0f, 1f));
                counted("two draws", target, () -> {
                    quad(-1f, -1f, 0f, 1f, 0f, 1f, 0f, 0f, 1f);
                    quad(0f, -1f, 0.5f, 1f, 0f, 1f, 0f, 0f, 1f);
                });
            });
            g.s("occlusion " + name + ": depth and stencil decide", () -> {
                GlConformanceScenarios.target();
                colored();
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(GL_LESS);
                quad(-1f, -1f, 1f, 1f, -0.5f, 1f, 0f, 0f, 1f);
                counted("behind", target, () -> quad(-1f, -1f, 1f, 1f, 0.5f, 0f, 1f, 0f, 1f));
                counted("in front", target, () -> quad(-0.5f, -0.5f, 0.5f, 0.5f, -0.9f, 0f, 1f, 0f, 1f));
                glDisable(GL_DEPTH_TEST);
                glEnable(GL_STENCIL_TEST);
                glStencilFunc(GL_NEVER, 0, 0xFF);
                counted("stencil refuses", target, () -> quad(-1f, -1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f));
                glDisable(GL_STENCIL_TEST);
                glColorMask(false, false, false, false);
                counted("colour masked", target, () -> quad(-1f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f));
                glColorMask(true, true, true, true);
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, 4, 4);
                counted("scissored", target, () -> quad(-1f, -1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f));
                glDisable(GL_SCISSOR_TEST);
                final int discarding = program(VS, """
                    #version 330 core
                    out vec4 f;
                    void main() { if (gl_FragCoord.x < 8.0) discard; f = vec4(1.0); }
                    """);
                counted("half discarded", target, () -> quad(-1f, -1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f));
                glDeleteProgram(discarding);
            });
            g.s("occlusion " + name + ": clears are not counted", () -> {
                GlConformanceScenarios.target();
                counted("whole clear", target, () -> glClear(GL_COLOR_BUFFER_BIT));
                glEnable(GL_SCISSOR_TEST);
                glScissor(2, 2, 4, 4);
                counted("scissored clear", target, () -> glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT));
                glDisable(GL_SCISSOR_TEST);
                glColorMask(true, false, true, true);
                counted("masked clear", target, () -> glClear(GL_COLOR_BUFFER_BIT));
                glColorMask(true, true, true, true);
            });
            g.s("occlusion " + name + ": a query spans targets and blits", () -> {
                final int first = GlConformanceScenarios.target();
                final int second = GlConformanceScenarios.target();
                colored();
                counted("both targets", target, () -> {
                    glBindFramebuffer(GL_FRAMEBUFFER, first);
                    quad(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f);
                    glBindFramebuffer(GL_FRAMEBUFFER, second);
                    quad(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f);
                });
                counted("a blit", target, () -> {
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, first);
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, second);
                    glBlitFramebuffer(0, 0, 8, 8, 8, 8, 16, 16, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                });
                counted("across a finish", target, () -> {
                    glBindFramebuffer(GL_FRAMEBUFFER, first);
                    quad(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f);
                    glFinish();
                    quad(0f, 0f, 1f, 1f, 0f, 1f, 0f, 0f, 1f);
                });
            });
            g.s("occlusion " + name + ": a query object is used again", () -> {
                GlConformanceScenarios.target();
                colored();
                final int q = glGenQueries();
                g.record("before", glIsQuery(q));
                glBeginQuery(target, q);
                quad(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f);
                glEndQuery(target);
                g.record("after", glIsQuery(q));
                g.record("query target", glGetQueryi(target, GL_CURRENT_QUERY));
                g.record("first", glGetQueryObjecti(q, GL_QUERY_RESULT));
                glBeginQuery(target, q);
                g.record("while active", glGetQueryi(target, GL_CURRENT_QUERY) == q);
                glEndQuery(target);
                g.record("second", glGetQueryObjecti(q, GL_QUERY_RESULT));
                glDeleteQueries(q);
                g.record("deleted", glIsQuery(q));
            });
        }
        g.s("occlusion: a sample count on a multisampled target", () -> {
            msTarget();
            colored();
            counted("whole target", GL_SAMPLES_PASSED, () -> quad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f));
        });
        for (int mode : new int[] {GL_QUERY_WAIT, GL_QUERY_NO_WAIT, GL_QUERY_BY_REGION_WAIT, GL_QUERY_BY_REGION_NO_WAIT}) {
            g.s("conditional 0x" + Integer.toHexString(mode) + ": what it covers", () -> {
                GlConformanceScenarios.target();
                colored();
                final int seen = passing(() -> quad(-1f, -1f, 1f, 1f, 0f, 0f, 0f, 1f, 1f));
                final int hidden = passing(() -> { });
                glFinish();
                glBeginConditionalRender(seen, mode);
                quad(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f);
                glEndConditionalRender();
                g.pixelAt("drawn after a seen query", 4, 4);
                glBeginConditionalRender(hidden, mode);
                quad(0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f);
                glEndConditionalRender();
                g.pixelAt("no draw after a hidden query", 12, 12);
                glClearColor(1f, 1f, 0f, 1f);
                glBeginConditionalRender(hidden, mode);
                glClear(GL_COLOR_BUFFER_BIT);
                glEndConditionalRender();
                glClearColor(0f, 0f, 0f, 0f);
                g.pixelAt("no clear after a hidden query", 12, 12);
                g.frame("afterwards");
            });
        }
        g.s("conditional: nothing but what is rendered is held back", () -> {
            final int f = GlConformanceScenarios.target();
            colored();
            final int hidden = passing(() -> { });
            final int other = GlConformanceScenarios.target();
            quad(-1f, -1f, 1f, 1f, 0f, 1f, 0f, 0f, 1f);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, other);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, f);
            glBeginConditionalRender(hidden, GL_QUERY_WAIT);
            glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glEndConditionalRender();
            glBindFramebuffer(GL_FRAMEBUFFER, f);
            g.pixelAt("blit", 8, 8);
            final int tex = texture2D(1, 1, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null);
            glBeginConditionalRender(hidden, GL_QUERY_WAIT);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, bytes(10, 20, 30, 40));
            glEndConditionalRender();
            final ByteBuffer read = BufferUtils.createByteBuffer(4);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, read);
            g.record("a texture upload still happens", (read.get(0) & 0xFF) + " " + (read.get(3) & 0xFF));
            glDeleteTextures(tex);
        });
        g.s("conditional: refusals", () -> {
            GlConformanceScenarios.target();
            final int hidden = passing(() -> { });
            GlConformanceScenarios.drain();
            glBeginConditionalRender(hidden, GL_QUERY_WAIT);
            g.record("begin", glGetError());
            glBeginConditionalRender(hidden, GL_QUERY_WAIT);
            g.record("begin again", glGetError());
            glEndConditionalRender();
            g.record("end", glGetError());
            glEndConditionalRender();
            g.record("end again", glGetError());
            glBeginConditionalRender(0, GL_QUERY_WAIT);
            g.record("no query", glGetError());
            glBeginConditionalRender(hidden, 0x1234);
            g.record("bad mode", glGetError());
            GlConformanceScenarios.drain();
        });
    }

    private static final int MS = 4;

    /** A framebuffer whose colour and depth have four samples per pixel, cleared, on top of the single sampled target. */
    private static int msTarget() {
        GlConformanceScenarios.target();
        final int f = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        for (int[] a : new int[][] {{GL_COLOR_ATTACHMENT0, GL_RGBA8}, {GL_DEPTH_STENCIL_ATTACHMENT, GL_DEPTH24_STENCIL8}}) {
            final int r = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, r);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, MS, a[1], SIZE, SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, a[0], GL_RENDERBUFFER, r);
        }
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        return f;
    }

    /** Resolves into a fresh single sampled framebuffer, which stays bound for reading. */
    private static void resolve(int ms, int mask) {
        final int single = glGenFramebuffers();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, single);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture2D(SIZE, SIZE, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null), 0);
        final int depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, SIZE, SIZE);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, ms);
        glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, mask, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, single);
    }

    private static void triangle() {
        colored();
        vertices(v(-0.8f, -0.9f, 0f, 1f, 1f, 1f, 1f), v(0.9f, -0.3f, 0f, 1f, 1f, 1f, 1f), v(-0.2f, 0.8f, 0f, 1f, 1f, 1f, 1f));
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void msRefused(String name, GlConformanceScenarios.Body body) {
        g.s(name, () -> {
            msTarget();
            GlConformanceScenarios.drain();
            body.run();
            g.record("error", glGetError());
        });
    }

    private void multisampling() {
        g.s("multisample: what a target says it has", () -> {
            msTarget();
            g.record("status", glCheckFramebufferStatus(GL_FRAMEBUFFER));
            g.record("GL_SAMPLES", glGetInteger(GL_SAMPLES));
            g.record("GL_SAMPLE_BUFFERS", glGetInteger(GL_SAMPLE_BUFFERS));
            g.record("renderbuffer samples", glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES));
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            g.record("window GL_SAMPLE_BUFFERS", glGetInteger(GL_SAMPLE_BUFFERS));
        });
        g.s("multisample: a triangle's edges average", () -> {
            final int ms = msTarget();
            triangle();
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.frame("triangle");
        });
        g.s("multisample: a part resolves in place", () -> {
            final int ms = msTarget();
            triangle();
            final int single = glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, single);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture2D(SIZE, SIZE, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null), 0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, ms);
            glBlitFramebuffer(2, 3, 12, 10, 2, 3, 12, 10, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, single);
            g.frame("part");
        });
        g.s("multisample: clears reach every sample", () -> {
            final int ms = msTarget();
            glClearColor(0.25f, 0.5f, 0.75f, 1f);
            glEnable(GL_SCISSOR_TEST);
            glScissor(4, 4, 8, 8);
            glClear(GL_COLOR_BUFFER_BIT);
            glDisable(GL_SCISSOR_TEST);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.frame("scissored clear");
        });
        g.s("multisample: depth is tested per sample", () -> {
            final int ms = msTarget();
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            colored();
            vertices(v(-1f, -1f, -0.5f, 1f, 0f, 0f, 1f), v(1f, -1f, 0.5f, 1f, 0f, 0f, 1f), v(-1f, 1f, -0.5f, 1f, 0f, 0f, 1f), v(1f, 1f, 0.5f, 1f, 0f, 0f, 1f));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            vertices(v(-1f, -1f, 0.5f, 0f, 1f, 0f, 1f), v(1f, -1f, -0.5f, 0f, 1f, 0f, 1f), v(-1f, 1f, 0.5f, 0f, 1f, 0f, 1f), v(1f, 1f, -0.5f, 0f, 1f, 0f, 1f));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glDisable(GL_DEPTH_TEST);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.frame("two planes");
        });
        g.s("multisample: depth and stencil resolve", () -> {
            final int ms = msTarget();
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_ALWAYS, 7, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0.25f, 1f, 1f, 1f, 1f);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
            colored();
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0.25f, 1f, 1f, 1f, 1f);
            resolve(ms, GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            g.depthAt("inside", 8, 8);
            g.depthAt("outside", 1, 1);
            g.stencilAt("inside", 8, 8);
            g.stencilAt("outside", 1, 1);
        });
        g.s("multisample: a single sampled image blits in", () -> {
            final int ms = msTarget();
            final int single = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, single);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture2D(SIZE, SIZE, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null), 0);
            final int depth = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, SIZE, SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
            glClearColor(0f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_ALWAYS, 7, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            colored();
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0.25f, 1f, 0.5f, 0f, 1f);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, single);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, ms);
            glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT, GL_NEAREST);
            g.record("error", glGetError());
            resolve(ms, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            g.pixelAt("inside", 8, 8);
            g.pixelAt("outside", 1, 1);
            g.depthAt("inside", 8, 8);
            g.depthAt("outside", 1, 1);
            g.stencilAt("inside", 8, 8);
            g.stencilAt("outside", 1, 1);
        });
        g.s("multisample: an integer image resolves to a sample of its own", () -> {
            GlConformanceScenarios.target();
            final int ms = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, ms);
            final int r = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, r);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, MS, GL_RGBA16I, SIZE, SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, r);
            glClearBufferiv(GL_COLOR, 0, new int[] {1, -2, 3, 4});
            final int single = glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, single);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture2D(SIZE, SIZE, GL_RGBA16I, GL_RGBA_INTEGER, GL_SHORT, null), 0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, ms);
            glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            g.record("error", glGetError());
            glBindFramebuffer(GL_FRAMEBUFFER, single);
            final int[] read = new int[4];
            glReadPixels(8, 8, 1, 1, GL_RGBA_INTEGER, GL_INT, read);
            for (int c = 0; c < 4; c++) g.record("component " + c, read[c]);
        });
        g.s("blit: depth and stencil stretch to the nearest", () -> {
            GlConformanceScenarios.target();
            final int[] framebuffers = new int[2];
            for (int i = 0; i < 2; i++) {
                framebuffers[i] = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[i]);
                final int r = glGenRenderbuffers();
                glBindRenderbuffer(GL_RENDERBUFFER, r);
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, SIZE, SIZE);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, r);
                glDrawBuffer(GL_NONE);
                glReadBuffer(GL_NONE);
                glClear(GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[0]);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_ALWAYS, 5, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            colored();
            quad(-1f, -1f, 0f, 0f, 0.25f, 1f, 1f, 1f, 1f);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffers[0]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[1]);
            glBlitFramebuffer(0, 0, SIZE / 2, SIZE / 2, 0, 0, SIZE, SIZE, GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT, GL_NEAREST);
            g.record("error", glGetError());
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[1]);
            g.depthAt("stretched", SIZE - 2, SIZE - 2);
            g.stencilAt("stretched", SIZE - 2, SIZE - 2);
        });
        g.s("textures: the default object keeps its parameters", () -> {
            GlConformanceScenarios.target();
            glBindTexture(GL_TEXTURE_2D, 0);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            g.record("min filter", glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER));
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[] {0.25f, 0.5f, 0.75f, 1f});
            final float[] border = new float[4];
            glGetTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, border);
            for (int c = 0; c < 4; c++) g.record("border " + c, border[c]);
            glTexParameterIiv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new int[] {-1, 2, -3, 4});
            final int[] integers = new int[4];
            glGetTexParameterIiv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, integers);
            for (int c = 0; c < 4; c++) g.record("integer border " + c, integers[c]);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[4]);
        });
        g.s("multisample: same sample count copies", () -> {
            final int ms = msTarget();
            triangle();
            final int other = glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, other);
            final int r = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, r);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, MS, GL_RGBA8, SIZE, SIZE);
            glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, r);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, ms);
            glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            resolve(other, GL_COLOR_BUFFER_BIT);
            g.frame("copy");
        });
        g.s("multisample: a texture's samples are read one by one", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, t);
            glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, MS, GL_RGBA8, SIZE, SIZE, true);
            g.record("samples", glGetTexLevelParameteri(GL_TEXTURE_2D_MULTISAMPLE, 0, GL_TEXTURE_SAMPLES));
            g.record("fixed", glGetTexLevelParameteri(GL_TEXTURE_2D_MULTISAMPLE, 0, GL_TEXTURE_FIXED_SAMPLE_LOCATIONS));
            final int ms = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, ms);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D_MULTISAMPLE, t, 0);
            g.record("status", glCheckFramebufferStatus(GL_FRAMEBUFFER));
            glClearColor(0f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            triangle();
            final int single = GlConformanceScenarios.target();
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, t);
            for (int sample = 0; sample < MS; sample++) {
                final int p = program(SCREEN_VS, """
                    #version 330 core
                    uniform sampler2DMS t;
                    uniform int which;
                    uniform int size;
                    in vec2 uv;
                    out vec4 f;
                    void main() { f = texelFetch(t, ivec2(gl_FragCoord.xy), which); }
                    """);
                glUniform1i(glGetUniformLocation(p, "t"), 0);
                glUniform1i(glGetUniformLocation(p, "which"), sample);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                g.frame("sample " + sample);
            }
            final int p = program(SCREEN_VS, """
                #version 330 core
                uniform sampler2DMS t;
                in vec2 uv;
                out vec4 f;
                void main() { f = vec4(float(textureSize(t).x) / 32.0, float(textureSize(t).y) / 32.0, 0.0, 1.0); }
                """);
            glUniform1i(glGetUniformLocation(p, "t"), 0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("textureSize", 8, 8);
        });
        g.s("multisample: an array texture's layers", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, t);
            glTexImage3DMultisample(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, MS, GL_RGBA8, SIZE, SIZE, 3, true);
            final int ms = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, ms);
            for (int layer = 0; layer < 3; layer++) {
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, t, 0, layer);
                glClearColor(0.25f * layer, 0.5f, 0f, 1f);
                glClear(GL_COLOR_BUFFER_BIT);
            }
            GlConformanceScenarios.target();
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, t);
            final int p = program(SCREEN_VS, """
                #version 330 core
                uniform sampler2DMSArray t;
                uniform int layer;
                in vec2 uv;
                out vec4 f;
                void main() { f = texelFetch(t, ivec3(ivec2(gl_FragCoord.xy), layer), 2); }
                """);
            glUniform1i(glGetUniformLocation(p, "t"), 0);
            for (int layer = 0; layer < 3; layer++) {
                glUniform1i(glGetUniformLocation(p, "layer"), layer);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                g.pixelAt("layer " + layer, 8, 8);
            }
        });
        g.s("multisample: positions", () -> {
            msTarget();
            final FloatBuffer p = BufferUtils.createFloatBuffer(2);
            for (int i = 0; i < MS; i++) {
                glGetMultisamplefv(GL_SAMPLE_POSITION, i, p);
                g.record("position " + i, p.get(0) + " " + p.get(1));
            }
        });
        g.s("multisample: alpha to coverage", () -> {
            final int ms = msTarget();
            glEnable(GL_SAMPLE_ALPHA_TO_COVERAGE);
            g.record("on", glIsEnabled(GL_SAMPLE_ALPHA_TO_COVERAGE));
            colored();
            quad(-1f, -1f, 0f, 1f, 0f, 1f, 1f, 1f, 0f);
            quad(0f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f);
            glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.frame("none and all");
        });
        g.s("multisample: alpha to coverage keeps some of a half", () -> {
            final int ms = msTarget();
            glEnable(GL_SAMPLE_ALPHA_TO_COVERAGE);
            colored();
            quad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 0.5f);
            glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            final ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            final int red = pixel.get(0) & 0xFF;
            g.record("between", red > 0 && red < 255);
        });
        for (int mask : new int[] {0x1, 0x6, 0xF, 0}) {
            g.s("multisample: sample mask 0x" + Integer.toHexString(mask), () -> {
                final int ms = msTarget();
                glEnable(GL_SAMPLE_MASK);
                glSampleMaski(0, mask);
                g.record("value", glGetIntegeri(GL_SAMPLE_MASK_VALUE, 0));
                colored();
                quad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f);
                glDisable(GL_SAMPLE_MASK);
                glSampleMaski(0, -1);
                resolve(ms, GL_COLOR_BUFFER_BIT);
                g.pixelAt("covered", 8, 8);
            });
        }
        for (float value : new float[] {0f, 0.25f, 0.5f, 1f}) {
            for (boolean invert : new boolean[] {false, true}) {
                g.s("multisample: coverage " + value + (invert ? " inverted" : ""), () -> {
                    final int ms = msTarget();
                    glEnable(GL_SAMPLE_COVERAGE);
                    glSampleCoverage(value, invert);
                    colored();
                    quad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f);
                    glDisable(GL_SAMPLE_COVERAGE);
                    glSampleCoverage(1f, false);
                    resolve(ms, GL_COLOR_BUFFER_BIT);
                    g.pixelAt("covered", 8, 8);
                });
            }
        }
        g.s("multisample: a shader that reads which sample it is", () -> {
            final int ms = msTarget();
            program(VS, """
                #version 400 core
                in vec4 v;
                out vec4 f;
                void main() { f = vec4(float(gl_SampleID) / 3.0, gl_SamplePosition.x, gl_SamplePosition.y, 1.0); }
                """);
            quad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.pixelAt("average", 8, 8);
        });
        g.s("multisample: a shader that writes its coverage", () -> {
            final int ms = msTarget();
            program(VS, """
                #version 400 core
                in vec4 v;
                out vec4 f;
                void main() { f = vec4(1.0); gl_SampleMask[0] = gl_SampleMaskIn[0] & 3; }
                """);
            quad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.pixelAt("half", 8, 8);
        });
        g.s("multisample: sample shading", () -> {
            final int ms = msTarget();
            glEnable(GL_SAMPLE_SHADING);
            glMinSampleShading(1f);
            g.record("on", glIsEnabled(GL_SAMPLE_SHADING));
            g.record("value", glGetFloat(GL_MIN_SAMPLE_SHADING_VALUE));
            triangle();
            glDisable(GL_SAMPLE_SHADING);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.frame("triangle");
        });
        g.s("multisample: switched off", () -> {
            final int ms = msTarget();
            glDisable(GL_MULTISAMPLE);
            triangle();
            glEnable(GL_MULTISAMPLE);
            resolve(ms, GL_COLOR_BUFFER_BIT);
            g.frame("triangle");
        });
        msRefused("multisample: reading pixels is refused", () -> {
            final ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        });
        msRefused("multisample: copying pixels is refused", () -> glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, 1, 1));
        msRefused("multisample: a resolve that moves is refused", () -> {
            final int ms = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            final int single = GlConformanceScenarios.target();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, ms);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, single);
            glBlitFramebuffer(0, 0, 8, 8, 1, 1, 9, 9, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        });
        msRefused("multisample: a resolve between formats is refused", () -> {
            final int ms = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            final int single = glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, single);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture2D(SIZE, SIZE, GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE, null), 0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, ms);
            glBlitFramebuffer(0, 0, 8, 8, 0, 0, 8, 8, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        });
        msRefused("multisample: too many samples are refused", () -> {
            final int r = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, r);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, glGetInteger(GL_MAX_SAMPLES) + 1, GL_RGBA8, SIZE, SIZE);
        });
        msRefused("multisample: mipmaps are refused", () -> {
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, t);
            glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, MS, GL_RGBA8, SIZE, SIZE, true);
            glGenerateMipmap(GL_TEXTURE_2D_MULTISAMPLE);
        });
        msRefused("multisample: filtering parameters are refused", () -> {
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, t);
            glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, MS, GL_RGBA8, SIZE, SIZE, true);
            glTexParameteri(GL_TEXTURE_2D_MULTISAMPLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        });
        g.s("multisample: attachments must agree", () -> {
            GlConformanceScenarios.target();
            final int f = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, f);
            final int a = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, a);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, MS, GL_RGBA8, SIZE, SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, a);
            final int b = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, b);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, SIZE, SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, b);
            g.record("mixed samples", glCheckFramebufferStatus(GL_FRAMEBUFFER));
            GlConformanceScenarios.drain();
            glClear(GL_COLOR_BUFFER_BIT);
            g.record("clearing it", glGetError());
        });
    }

    private void framebuffers() {
        g.s("target: several attachments", () -> {
            GlConformanceScenarios.target();
            final int[] t = new int[3];
            for (int i = 0; i < 3; i++) {
                t[i] = texture2D(SIZE, SIZE, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1 + i, GL_TEXTURE_2D, t[i], 0);
            }
            final IntBuffer buffers = BufferUtils.createIntBuffer(4);
            buffers.put(GL_COLOR_ATTACHMENT0).put(GL_NONE).put(GL_COLOR_ATTACHMENT2).put(GL_COLOR_ATTACHMENT3).flip();
            glDrawBuffers(buffers);
            program(VS, """
                #version 330 core
                layout(location = 0) out vec4 a;
                layout(location = 1) out vec4 b;
                layout(location = 2) out vec4 c;
                layout(location = 3) out vec4 d;
                void main() { a = vec4(1.0, 0.0, 0.0, 1.0); b = vec4(0.0, 1.0, 0.0, 1.0); c = vec4(0.0, 0.0, 1.0, 1.0); d = vec4(1.0, 1.0, 0.0, 1.0); }
                """);
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f);
            for (int attachment : new int[] {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3}) {
                glReadBuffer(attachment);
                g.pixelAt("attachment 0x" + Integer.toHexString(attachment), 8, 8);
            }
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
        });
        g.s("target: clear each kind of buffer", () -> {
            GlConformanceScenarios.target();
            final FloatBuffer color = BufferUtils.createFloatBuffer(4);
            color.put(new float[] {0.25f, 0.5f, 0.75f, 1f}).flip();
            glClearBufferfv(GL_COLOR, 0, color);
            g.pixelAt("float", 8, 8);
            final FloatBuffer depth = BufferUtils.createFloatBuffer(1);
            depth.put(0.5f).flip();
            glClearBufferfv(GL_DEPTH, 0, depth);
            g.depthAt("depth", 8, 8);
            final IntBuffer stencil = BufferUtils.createIntBuffer(1);
            stencil.put(9).flip();
            glClearBufferiv(GL_STENCIL, 0, stencil);
            g.stencilAt("stencil", 8, 8);
            glClearBufferfi(GL_DEPTH_STENCIL, 0, 0.25f, 3);
            g.depthAt("both depth", 8, 8);
            g.stencilAt("both stencil", 8, 8);
        });
        for (Object[] format : new Object[][] {{GL_R8, GL_RED}, {GL_RG8, GL_RG}, {GL_RGB10_A2, GL_RGBA}, {GL_R11F_G11F_B10F, GL_RGB}, {GL_RGB565, GL_RGB}, {GL_RGBA4, GL_RGBA}, {GL_RGB5_A1, GL_RGBA}, {GL_SRGB8_ALPHA8, GL_RGBA}}) {
            g.s("target: draw into 0x" + Integer.toHexString((int) format[0]), () -> {
                GlConformanceScenarios.target();
                final int t = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, t);
                glTexImage2D(GL_TEXTURE_2D, 0, (int) format[0], SIZE, SIZE, 0, (int) format[1], GL_UNSIGNED_BYTE, (ByteBuffer) null);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
                g.record("status", Integer.toHexString(glCheckFramebufferStatus(GL_FRAMEBUFFER)));
                colored();
                quad(-1f, -1f, 1f, 1f, 0f, 0.8f, 0.4f, 0.2f, 0.6f);
                g.pixelAt("result", 8, 8);
            });
        }
        g.s("target: sRGB encoding only when enabled", () -> {
            GlConformanceScenarios.target();
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, t);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
            colored();
            quad(-1f, -1f, 1f, 1f, 0f, 0.5f, 0.25f, 0.75f, 1f);
            g.pixelAt("off", 8, 8);
            glEnable(GL_FRAMEBUFFER_SRGB);
            quad(-1f, -1f, 1f, 1f, 0f, 0.5f, 0.25f, 0.75f, 1f);
            g.pixelAt("on", 8, 8);
            glDisable(GL_FRAMEBUFFER_SRGB);
        });
        g.s("target: a mip level and an array layer", () -> {
            GlConformanceScenarios.target();
            final int mips = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, mips);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE * 2, SIZE * 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexImage2D(GL_TEXTURE_2D, 1, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mips, 1);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
            g.record("level one status", glCheckFramebufferStatus(GL_FRAMEBUFFER));
            colored();
            vertices(SHAPE);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("level one");
            final int layers = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, layers);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, SIZE, SIZE, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, layers, 0, 2);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("layer two");
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, layers, 0, 1);
            g.frame("layer one untouched");
        });
        g.s("target: render then sample", () -> {
            final int f = GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            final int color = glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            final int second = GlConformanceScenarios.target();
            glBindTexture(GL_TEXTURE_2D, color);
            screen(SAMPLE, 1f);
            g.frame("a texture rendered to reads the right way up");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(f);
            glDeleteFramebuffers(second);
        });
    }

    private void blits() {
        final int[][] rects = {{0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE}, {0, 0, SIZE, SIZE, SIZE, 0, 0, SIZE}, {0, 0, SIZE, SIZE, 0, SIZE, SIZE, 0}, {2, 3, 10, 9, 4, 5, 15, 14}, {4, 4, 12, 12, 0, 0, SIZE, SIZE}};
        for (int[] r : rects) {
            for (int filter : new int[] {GL_NEAREST, GL_LINEAR}) {
                g.s("blit: " + java.util.Arrays.toString(r) + " 0x" + Integer.toHexString(filter), () -> {
                    final int from = GlConformanceScenarios.target();
                    colored();
                    vertices(SHAPE);
                    glDrawArrays(GL_TRIANGLES, 0, 6);
                    final int to = GlConformanceScenarios.target();
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, from);
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, to);
                    glBlitFramebuffer(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], GL_COLOR_BUFFER_BIT, filter);
                    glBindFramebuffer(GL_FRAMEBUFFER, to);
                    g.frame("image");
                });
            }
        }
        g.s("blit: depth and stencil", () -> {
            final int from = GlConformanceScenarios.target();
            colored();
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_ALWAYS, 6, 0xFF);
            glStencilOp(GL_REPLACE, GL_REPLACE, GL_REPLACE);
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0f, 1f, 0f, 0f, 1f);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
            final int to = GlConformanceScenarios.target();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, from);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, to);
            glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, to);
            g.depthAt("depth inside", 8, 8);
            g.depthAt("depth outside", 1, 1);
            g.stencilAt("stencil inside", 8, 8);
            g.stencilAt("stencil outside", 1, 1);
        });
        g.s("copy: framebuffer to texture", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            final int t = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, t);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 0, 0, SIZE, SIZE, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            screen(SAMPLE, 1f);
            g.frame("whole");
            glClear(GL_COLOR_BUFFER_BIT);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 4, 4, 0, 0, 4, 4);
            screen(SAMPLE, 1f);
            g.frame("sub rectangle of an empty framebuffer");
        });
    }

    private void readBack() {
        final int[][] layouts = {{GL_RGBA, GL_UNSIGNED_BYTE, 4}, {GL_BGRA, GL_UNSIGNED_BYTE, 4}, {GL_RGB, GL_UNSIGNED_BYTE, 3}, {GL_RED, GL_UNSIGNED_BYTE, 1}, {GL_RG, GL_UNSIGNED_BYTE, 2},
            {GL_RGBA, GL_UNSIGNED_SHORT, 8}, {GL_RGB, GL_UNSIGNED_SHORT_5_6_5, 2}, {GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, 2}, {GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4}, {GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, 4}};
        for (int[] layout : layouts) {
            g.s("read: 0x" + Integer.toHexString(layout[0]) + " 0x" + Integer.toHexString(layout[1]), () -> {
                GlConformanceScenarios.target();
                glClearColor(0.8f, 0.4f, 0.2f, 0.6f);
                glClear(GL_COLOR_BUFFER_BIT);
                glPixelStorei(GL_PACK_ALIGNMENT, 1);
                final ByteBuffer out = BufferUtils.createByteBuffer(16);
                glReadPixels(4, 4, 1, 1, layout[0], layout[1], out);
                final StringBuilder bytes = new StringBuilder();
                for (int i = 0; i < layout[2]; i++) bytes.append(i == 0 ? "" : ",").append(out.get(i) & 0xFF);
                g.record("px:bytes", bytes);
                glPixelStorei(GL_PACK_ALIGNMENT, 4);
            });
        }
        g.s("read: a block, with the pack state", () -> {
            GlConformanceScenarios.target();
            colored();
            vertices(SHAPE);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glPixelStorei(GL_PACK_ROW_LENGTH, 6);
            glPixelStorei(GL_PACK_SKIP_ROWS, 1);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 2);
            final ByteBuffer out = BufferUtils.createByteBuffer(6 * 5 * 4);
            glReadPixels(3, 3, 4, 3, GL_RGBA, GL_UNSIGNED_BYTE, out);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            final StringBuilder bytes = new StringBuilder();
            for (int i = 0; i < out.capacity(); i++) bytes.append(i == 0 ? "" : ",").append(out.get(i) & 0xFF);
            g.record("px:bytes", bytes);
        });
        g.s("read: into a buffer object", () -> {
            GlConformanceScenarios.target();
            glClearColor(0.8f, 0.4f, 0.2f, 0.6f);
            glClear(GL_COLOR_BUFFER_BIT);
            final int pbo = glGenBuffers();
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
            glBufferData(GL_PIXEL_PACK_BUFFER, BufferUtils.createByteBuffer(16), GL_STREAM_READ);
            glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, 4L);
            final ByteBuffer out = BufferUtils.createByteBuffer(16);
            glGetBufferSubData(GL_PIXEL_PACK_BUFFER, 0L, out);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glDeleteBuffers(pbo);
            final StringBuilder bytes = new StringBuilder();
            for (int i = 0; i < 16; i++) bytes.append(i == 0 ? "" : ",").append(out.get(i) & 0xFF);
            g.record("px:bytes", bytes);
        });
    }

    private static ByteBuffer clientPixel(int format, int type) {
        final ByteBuffer b = BufferUtils.createByteBuffer(16);
        final int[] order = format == GL_BGRA ? new int[] {2, 1, 0, 3} : new int[] {0, 1, 2, 3};
        final float[] rgba = {0.8f, 0.4f, 0.2f, 0.6f};
        final int count = format == GL_RED ? 1 : format == GL_RG ? 2 : format == GL_RGB ? 3 : 4;
        switch (type) {
            case GL_UNSIGNED_BYTE -> { for (int i = 0; i < count; i++) b.put(i, (byte) Math.round(rgba[order[i]] * 255f)); }
            case GL_UNSIGNED_SHORT -> { for (int i = 0; i < count; i++) b.putShort(i * 2, (short) Math.round(rgba[order[i]] * 65535f)); }
            case GL_FLOAT -> { for (int i = 0; i < count; i++) b.putFloat(i * 4, rgba[order[i]]); }
            case GL_UNSIGNED_SHORT_5_6_5 -> b.putShort(0, (short) (25 << 11 | 25 << 5 | 6));
            case GL_UNSIGNED_SHORT_4_4_4_4 -> b.putShort(0, (short) (12 << 12 | 6 << 8 | 3 << 4 | 9));
            case GL_UNSIGNED_INT_2_10_10_10_REV -> b.putInt(0, 818 | 409 << 10 | 205 << 20 | 2 << 30);
            default -> b.putInt(0, 0xCC663399);
        }
        return b;
    }

    /** Every internal format against the client layouts it can take, compared as it reads back through several layouts. */
    private void pixelConversions() {
        final int[] colours = {GL_RGBA8, GL_RGB8, GL_R8, GL_RG8, GL_RGBA16, GL_RGBA16F, GL_RGBA32F, GL_R11F_G11F_B10F, GL_RGB9_E5, GL_RGB10_A2, GL_RGBA8_SNORM, GL_SRGB8_ALPHA8};
        final int[][] inputs = {{GL_RGBA, GL_UNSIGNED_BYTE}, {GL_BGRA, GL_UNSIGNED_BYTE}, {GL_RGB, GL_UNSIGNED_BYTE}, {GL_RED, GL_UNSIGNED_BYTE}, {GL_RGBA, GL_UNSIGNED_SHORT}, {GL_RGBA, GL_FLOAT},
            {GL_RGB, GL_UNSIGNED_SHORT_5_6_5}, {GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4}, {GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV}, {GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV}};
        final int[][] outputs = {{GL_RGBA, GL_UNSIGNED_BYTE, 4}, {GL_RGBA, GL_FLOAT, 16}, {GL_RGB, GL_UNSIGNED_INT_10F_11F_11F_REV, 4}, {GL_RGB, GL_UNSIGNED_INT_5_9_9_9_REV, 4}, {GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, 2}};
        for (int internal : colours) {
            for (int[] input : inputs) {
                g.s("pixels: 0x" + Integer.toHexString(internal) + " from 0x" + Integer.toHexString(input[0]) + " 0x" + Integer.toHexString(input[1]), () -> {
                    final int t = glGenTextures();
                    glBindTexture(GL_TEXTURE_2D, t);
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                    glPixelStorei(GL_PACK_ALIGNMENT, 1);
                    glTexImage2D(GL_TEXTURE_2D, 0, internal, 1, 1, 0, input[0], input[1], clientPixel(input[0], input[1]));
                    for (int[] output : outputs) {
                        final ByteBuffer out = BufferUtils.createByteBuffer(32);
                        glGetTexImage(GL_TEXTURE_2D, 0, output[0], output[1], out);
                        final StringBuilder bytes = new StringBuilder();
                        for (int i = 0; i < output[2]; i++) bytes.append(i == 0 ? "" : ",").append(out.get(i) & 0xFF);
                        g.record("px:0x" + Integer.toHexString(output[1]), bytes);
                    }
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                    glPixelStorei(GL_PACK_ALIGNMENT, 4);
                    glBindTexture(GL_TEXTURE_2D, 0);
                    glDeleteTextures(t);
                });
            }
        }
        for (int[] integer : new int[][] {{GL_RGBA8UI, GL_UNSIGNED_BYTE}, {GL_RGBA16I, GL_SHORT}, {GL_R32UI, GL_UNSIGNED_INT}, {GL_RGBA8I, GL_UNSIGNED_INT}, {GL_RG16UI, GL_BYTE}}) {
            g.s("pixels: integers 0x" + Integer.toHexString(integer[0]) + " from 0x" + Integer.toHexString(integer[1]), () -> {
                final int t = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, t);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glPixelStorei(GL_PACK_ALIGNMENT, 1);
                final ByteBuffer in = BufferUtils.createByteBuffer(32);
                for (int i = 0; i < 32; i++) in.put(i, (byte) (200 + i));
                glTexImage2D(GL_TEXTURE_2D, 0, integer[0], 1, 1, 0, GL_RGBA_INTEGER, integer[1], in);
                final ByteBuffer out = BufferUtils.createByteBuffer(16);
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA_INTEGER, GL_INT, out);
                g.record("rgba", out.getInt(0) + "," + out.getInt(4) + "," + out.getInt(8) + "," + out.getInt(12));
                glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                glPixelStorei(GL_PACK_ALIGNMENT, 4);
                glBindTexture(GL_TEXTURE_2D, 0);
                glDeleteTextures(t);
            });
        }
    }

    private void buffersAndQueries() {
        g.s("buffer: uniform block layout and range", () -> {
            GlConformanceScenarios.target();
            final int p = program(SCREEN_VS, """
                #version 330 core
                layout(std140) uniform U { float a; vec3 b; vec2 c[2]; mat2 m; };
                in vec2 uv;
                out vec4 f;
                void main() { f = vec4(a, b.y, c[1].x, m[1][0]); }
                """);
            glUniform2f(glGetUniformLocation(p, "uvScale"), 1f, 1f);
            final int block = glGetUniformBlockIndex(p, "U");
            glUniformBlockBinding(p, block, 2);
            final FloatBuffer data = BufferUtils.createFloatBuffer(256);
            for (int i = 0; i < 64; i++) data.put(0f);
            // After an offset: a at 0, b at 16, c at 32 with a stride of 16, m at 64 with a stride of 16.
            final float[] block0 = new float[24];
            block0[0] = 0.25f;
            block0[5] = 0.5f;
            block0[12] = 0.75f;
            block0[20] = 1f;
            data.put(block0);
            data.rewind();
            data.limit(64 + 24);
            final int ubo = glGenBuffers();
            glBindBuffer(GL_UNIFORM_BUFFER, ubo);
            glBufferData(GL_UNIFORM_BUFFER, data, GL_STATIC_DRAW);
            final int alignment = Math.max(256, glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
            final FloatBuffer aligned = BufferUtils.createFloatBuffer(alignment / 4 + 24);
            for (int i = 0; i < alignment / 4; i++) aligned.put(0f);
            aligned.put(block0).flip();
            glBufferData(GL_UNIFORM_BUFFER, aligned, GL_STATIC_DRAW);
            glBindBufferRange(GL_UNIFORM_BUFFER, 2, ubo, alignment, 96);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("values", 8, 8);
        });
        g.s("buffer: written through a map and copied", () -> {
            GlConformanceScenarios.target();
            colored();
            final int a = vertices(SHAPE);
            final ByteBuffer mapped = glMapBufferRange(GL_ARRAY_BUFFER, 0L, 12L, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
            mapped.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(new float[] {-0.91f, 0.83f, 0f});
            glUnmapBuffer(GL_ARRAY_BUFFER);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("mapped");
            glClear(GL_COLOR_BUFFER_BIT);
            final int b = glGenBuffers();
            glBindBuffer(GL_COPY_WRITE_BUFFER, b);
            glBufferData(GL_COPY_WRITE_BUFFER, 28L * 6, GL_STATIC_DRAW);
            glBindBuffer(GL_COPY_READ_BUFFER, a);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, 28L * 6);
            glBindBuffer(GL_ARRAY_BUFFER, b);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0L);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12L);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("copied");
        });
        g.s("buffer: orphaned while a draw is pending", () -> {
            GlConformanceScenarios.target();
            colored();
            final int a = vertices(SHAPE);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            final FloatBuffer other = BufferUtils.createFloatBuffer(42);
            for (int i = 0; i < 6; i++) other.put(SHAPE[5 - i]);
            other.flip();
            glBufferData(GL_ARRAY_BUFFER, other, GL_STATIC_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            g.frame("each draw saw its own data");
            glDeleteBuffers(a);
        });
        g.s("capture: transform feedback", () -> {
            GlConformanceScenarios.target();
            final int p = GlConformanceScenarios.programWithVaryings("""
                #version 330 core
                layout(location = 0) in vec4 p;
                out vec2 o;
                void main() { gl_Position = p; o = p.xy * 2.0; }
                """, FS.replace("in vec4 v;", "").replace("f = v;", "f = vec4(1.0);"), new CharSequence[] {"o"});
            glUseProgram(p);
            vertices(SHAPE);
            final int tf = glGenBuffers();
            glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, tf);
            glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER, 64L, GL_STREAM_READ);
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, tf);
            final int written = glGenQueries();
            glEnable(GL_RASTERIZER_DISCARD);
            glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, written);
            glBeginTransformFeedback(GL_TRIANGLES);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glEndTransformFeedback();
            glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
            glDisable(GL_RASTERIZER_DISCARD);
            g.record("primitives", glGetQueryObjecti(written, GL_QUERY_RESULT));
            final FloatBuffer out = BufferUtils.createFloatBuffer(6);
            glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0L, out);
            for (int i = 0; i < 6; i++) g.record("value " + i, out.get(i));
            g.frame("discarded rasterizing");
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        });
        g.s("query: occlusion and conditional drawing", () -> {
            GlConformanceScenarios.target();
            colored();
            final int passed = glGenQueries(), any = glGenQueries();
            glBeginQuery(GL_SAMPLES_PASSED, passed);
            quad(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f);
            glEndQuery(GL_SAMPLES_PASSED);
            glFinish();
            g.record("samples", glGetQueryObjecti(passed, GL_QUERY_RESULT));
            glBeginQuery(GL_ANY_SAMPLES_PASSED, any);
            quad(5f, 5f, 6f, 6f, 0f, 1f, 0f, 0f, 1f);
            glEndQuery(GL_ANY_SAMPLES_PASSED);
            g.record("any when nothing is drawn", glGetQueryObjecti(any, GL_QUERY_RESULT));
            glClear(GL_COLOR_BUFFER_BIT);
            glBeginConditionalRender(any, GL_QUERY_WAIT);
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 0f, 1f);
            glEndConditionalRender();
            g.pixelAt("skipped", 8, 8);
            glBeginConditionalRender(passed, GL_QUERY_WAIT);
            quad(-0.5f, -0.5f, 0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f);
            glEndConditionalRender();
            g.pixelAt("drawn", 8, 8);
        });
    }

    private void uniforms() {
        g.s("uniform: every kind reaches the shader", () -> {
            GlConformanceScenarios.target();
            final int p = program(SCREEN_VS, """
                #version 330 core
                uniform float a; uniform vec2 b; uniform vec3 c; uniform vec4 d;
                uniform int e; uniform ivec2 g; uniform uint h; uniform bool i;
                uniform float arr[3];
                uniform mat2 m; uniform mat3x2 n;
                struct S { float x; vec2 y; };
                uniform S s;
                in vec2 uv;
                out vec4 f;
                void main() {
                    float sum = a + b.y + c.z + d.w + float(e) / 8.0 + float(g.y) / 8.0 + float(h) / 8.0 + (i ? 0.125 : 0.0);
                    f = vec4(sum / 4.0, arr[2] + s.y.x, m[1][0] + n[2][1], 1.0);
                }
                """);
            glUniform2f(glGetUniformLocation(p, "uvScale"), 1f, 1f);
            glUniform1f(glGetUniformLocation(p, "a"), 0.25f);
            glUniform2f(glGetUniformLocation(p, "b"), 9f, 0.25f);
            glUniform3f(glGetUniformLocation(p, "c"), 9f, 9f, 0.25f);
            glUniform4f(glGetUniformLocation(p, "d"), 9f, 9f, 9f, 0.25f);
            glUniform1i(glGetUniformLocation(p, "e"), 1);
            glUniform2i(glGetUniformLocation(p, "g"), 9, 1);
            glUniform1ui(glGetUniformLocation(p, "h"), 1);
            glUniform1i(glGetUniformLocation(p, "i"), 1);
            final FloatBuffer arr = BufferUtils.createFloatBuffer(3);
            arr.put(new float[] {9f, 9f, 0.25f}).flip();
            glUniform1fv(glGetUniformLocation(p, "arr"), arr);
            final FloatBuffer m = BufferUtils.createFloatBuffer(4);
            m.put(new float[] {9f, 9f, 0.25f, 9f}).flip();
            glUniformMatrix2fv(glGetUniformLocation(p, "m"), false, m);
            final FloatBuffer n = BufferUtils.createFloatBuffer(6);
            n.put(new float[] {9f, 9f, 9f, 9f, 9f, 0.25f}).flip();
            glUniformMatrix3x2fv(glGetUniformLocation(p, "n"), false, n);
            glUniform1f(glGetUniformLocation(p, "s.x"), 9f);
            glUniform2f(glGetUniformLocation(p, "s.y"), 0.25f, 9f);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("colour", 8, 8);
        });
        g.s("uniform: transposed matrices and array elements", () -> {
            GlConformanceScenarios.target();
            final int p = program(SCREEN_VS, """
                #version 330 core
                uniform mat2 m;
                uniform vec2 v[2];
                in vec2 uv;
                out vec4 f;
                void main() { f = vec4(m[0][1], m[1][0], v[1].y, 1.0); }
                """);
            glUniform2f(glGetUniformLocation(p, "uvScale"), 1f, 1f);
            final FloatBuffer m = BufferUtils.createFloatBuffer(4);
            m.put(new float[] {0f, 0.25f, 0.75f, 0f}).flip();
            glUniformMatrix2fv(glGetUniformLocation(p, "m"), true, m);
            glUniform2f(glGetUniformLocation(p, "v[1]"), 0f, 0.5f);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("colour", 8, 8);
        });
        g.s("uniform: a program keeps its values when another is used", () -> {
            GlConformanceScenarios.target();
            final String fs = "#version 330 core\nuniform vec4 c;\nin vec2 uv;\nout vec4 f;\nvoid main() { f = c; }\n";
            final int a = program(SCREEN_VS, fs);
            glUniform2f(glGetUniformLocation(a, "uvScale"), 1f, 1f);
            glUniform4f(glGetUniformLocation(a, "c"), 1f, 0f, 0f, 1f);
            final int b = program(SCREEN_VS, fs);
            glUniform2f(glGetUniformLocation(b, "uvScale"), 1f, 1f);
            glUniform4f(glGetUniformLocation(b, "c"), 0f, 1f, 0f, 1f);
            glUseProgram(a);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("first", 8, 8);
            glUseProgram(b);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            g.pixelAt("second", 8, 8);
        });
    }

    /** What Minecraft itself draws with. Only a driver with a compatibility profile can judge these, which macOS has none of. */
    private void fixedFunction() {
        g.legacy("fixed: immediate mode, colour and modes", () -> {
            GlConformanceScenarios.target();
            final int[] modes = {GL_TRIANGLES, GL_QUADS, GL_POLYGON, GL_QUAD_STRIP, GL_TRIANGLE_FAN, GL_LINE_LOOP, GL_POINTS};
            for (int mode : modes) {
                glClear(GL_COLOR_BUFFER_BIT);
                glBegin(mode);
                for (float[] p : SHAPE) {
                    glColor4f(p[3], p[4], p[5], p[6]);
                    glVertex2f(p[0], p[1]);
                }
                glEnd();
                g.frame("mode 0x" + Integer.toHexString(mode));
            }
            glColor4f(1f, 1f, 1f, 1f);
        });
        g.legacy("fixed: matrices", () -> {
            GlConformanceScenarios.target();
            glMatrixMode(GL_PROJECTION);
            glPushMatrix();
            glLoadIdentity();
            glOrtho(0.0, 16.0, 0.0, 16.0, -1.0, 1.0);
            glMatrixMode(GL_MODELVIEW);
            glPushMatrix();
            glLoadIdentity();
            glTranslatef(8f, 8f, 0f);
            glRotatef(30f, 0f, 0f, 1f);
            glScalef(2f, 1f, 1f);
            glColor3f(0f, 1f, 0f);
            glBegin(GL_QUADS);
            glVertex2f(-3f, -2f);
            glVertex2f(3f, -2f);
            glVertex2f(3f, 3f);
            glVertex2f(-3f, 3f);
            glEnd();
            g.frame("ortho and transforms");
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glClear(GL_COLOR_BUFFER_BIT);
            glLoadIdentity();
            glFrustum(-1.0, 1.0, -1.0, 1.0, 1.0, 10.0);
            glMatrixMode(GL_MODELVIEW);
            glPushMatrix();
            glLoadIdentity();
            glBegin(GL_TRIANGLES);
            glColor3f(1f, 0f, 0f);
            glVertex3f(-1.5f, -1.5f, -2f);
            glColor3f(0f, 1f, 0f);
            glVertex3f(1.5f, -1.5f, -3f);
            glColor3f(0f, 0f, 1f);
            glVertex3f(0f, 1.5f, -4f);
            glEnd();
            g.frame("perspective");
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            glColor4f(1f, 1f, 1f, 1f);
        });
        g.legacy("fixed: alpha test", () -> {
            GlConformanceScenarios.target();
            for (int func : new int[] {GL_NEVER, GL_LESS, GL_EQUAL, GL_LEQUAL, GL_GREATER, GL_NOTEQUAL, GL_GEQUAL, GL_ALWAYS}) {
                glClear(GL_COLOR_BUFFER_BIT);
                glEnable(GL_ALPHA_TEST);
                glAlphaFunc(func, 0.5f);
                glBegin(GL_TRIANGLE_STRIP);
                glColor4f(1f, 0f, 0f, 0f);
                glVertex2f(-1f, -1f);
                glColor4f(1f, 0f, 0f, 1f);
                glVertex2f(1f, -1f);
                glColor4f(1f, 0f, 0f, 0f);
                glVertex2f(-1f, 1f);
                glColor4f(1f, 0f, 0f, 1f);
                glVertex2f(1f, 1f);
                glEnd();
                g.frame("func 0x" + Integer.toHexString(func));
            }
            glDisable(GL_ALPHA_TEST);
            glColor4f(1f, 1f, 1f, 1f);
        });
        for (int mode : new int[] {GL_LINEAR, GL_EXP, GL_EXP2}) {
            g.legacy("fixed: fog 0x" + Integer.toHexString(mode), () -> {
                GlConformanceScenarios.target();
                glEnable(GL_FOG);
                glFogi(GL_FOG_MODE, mode);
                glFogf(GL_FOG_DENSITY, 0.5f);
                glFogf(GL_FOG_START, 0f);
                glFogf(GL_FOG_END, 2f);
                final FloatBuffer color = BufferUtils.createFloatBuffer(4);
                color.put(new float[] {0.25f, 0.5f, 0.75f, 1f}).flip();
                glFogfv(GL_FOG_COLOR, color);
                glColor3f(1f, 0f, 0f);
                glBegin(GL_TRIANGLE_STRIP);
                glVertex3f(-1f, -1f, -0.9f);
                glVertex3f(1f, -1f, 0.9f);
                glVertex3f(-1f, 1f, -0.9f);
                glVertex3f(1f, 1f, 0.9f);
                glEnd();
                g.frame("image");
                glDisable(GL_FOG);
                glColor4f(1f, 1f, 1f, 1f);
            });
        }
        g.legacy("fixed: lighting and materials", () -> {
            GlConformanceScenarios.target();
            glEnable(GL_LIGHTING);
            glEnable(GL_LIGHT0);
            final FloatBuffer value = BufferUtils.createFloatBuffer(4);
            value.put(new float[] {0f, 0f, 1f, 0f}).flip();
            glLightfv(GL_LIGHT0, GL_POSITION, value);
            value.clear();
            value.put(new float[] {0.8f, 0.6f, 0.4f, 1f}).flip();
            glLightfv(GL_LIGHT0, GL_DIFFUSE, value);
            value.clear();
            value.put(new float[] {0.1f, 0.1f, 0.2f, 1f}).flip();
            glLightModelfv(GL_LIGHT_MODEL_AMBIENT, value);
            glBegin(GL_TRIANGLE_STRIP);
            glNormal3f(0f, 0f, 1f);
            glVertex2f(-1f, -1f);
            glNormal3f(0.7f, 0f, 0.7f);
            glVertex2f(1f, -1f);
            glNormal3f(0f, 0.7f, 0.7f);
            glVertex2f(-1f, 1f);
            glNormal3f(-0.7f, 0f, 0.7f);
            glVertex2f(1f, 1f);
            glEnd();
            g.frame("default material");
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_COLOR_MATERIAL);
            glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);
            glColor3f(0.5f, 1f, 0.25f);
            glBegin(GL_TRIANGLE_STRIP);
            glNormal3f(0f, 0f, 1f);
            glVertex2f(-1f, -1f);
            glVertex2f(1f, -1f);
            glVertex2f(-1f, 1f);
            glVertex2f(1f, 1f);
            glEnd();
            g.frame("colour material");
            glDisable(GL_COLOR_MATERIAL);
            glDisable(GL_LIGHT0);
            glDisable(GL_LIGHTING);
            glColor4f(1f, 1f, 1f, 1f);
        });
        for (int mode : new int[] {GL_MODULATE, GL_DECAL, GL_REPLACE, GL_ADD, GL_BLEND}) {
            g.legacy("fixed: texture environment 0x" + Integer.toHexString(mode), () -> {
                GlConformanceScenarios.target();
                glEnable(GL_TEXTURE_2D);
                texture2D(2, 2, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, bytes(200, 100, 50, 128, 50, 200, 100, 255, 100, 50, 200, 64, 255, 255, 255, 255));
                glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, mode);
                final FloatBuffer color = BufferUtils.createFloatBuffer(4);
                color.put(new float[] {0.5f, 0.25f, 0.75f, 1f}).flip();
                glTexEnvfv(GL_TEXTURE_ENV, GL_TEXTURE_ENV_COLOR, color);
                glColor4f(0.8f, 0.6f, 0.4f, 0.9f);
                glBegin(GL_TRIANGLE_STRIP);
                glTexCoord2f(0f, 0f);
                glVertex2f(-1f, -1f);
                glTexCoord2f(1f, 0f);
                glVertex2f(1f, -1f);
                glTexCoord2f(0f, 1f);
                glVertex2f(-1f, 1f);
                glTexCoord2f(1f, 1f);
                glVertex2f(1f, 1f);
                glEnd();
                g.frame("image");
                glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
                glDisable(GL_TEXTURE_2D);
                glColor4f(1f, 1f, 1f, 1f);
            });
        }
        g.legacy("fixed: two texture units and generated coordinates", () -> {
            GlConformanceScenarios.target();
            glActiveTexture(GL_TEXTURE1);
            glEnable(GL_TEXTURE_2D);
            texture2D(1, 1, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, bytes(128, 255, 128, 255));
            glActiveTexture(GL_TEXTURE0);
            glEnable(GL_TEXTURE_2D);
            texture2D(2, 2, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, checker());
            glBegin(GL_TRIANGLE_STRIP);
            glMultiTexCoord2f(GL_TEXTURE0, 0f, 0f);
            glMultiTexCoord2f(GL_TEXTURE1, 0f, 0f);
            glVertex2f(-1f, -1f);
            glMultiTexCoord2f(GL_TEXTURE0, 1f, 0f);
            glVertex2f(1f, -1f);
            glMultiTexCoord2f(GL_TEXTURE0, 0f, 1f);
            glVertex2f(-1f, 1f);
            glMultiTexCoord2f(GL_TEXTURE0, 1f, 1f);
            glVertex2f(1f, 1f);
            glEnd();
            g.frame("modulated");
            glActiveTexture(GL_TEXTURE1);
            glDisable(GL_TEXTURE_2D);
            glActiveTexture(GL_TEXTURE0);
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_TEXTURE_GEN_S);
            glEnable(GL_TEXTURE_GEN_T);
            glTexGeni(GL_S, GL_TEXTURE_GEN_MODE, GL_OBJECT_LINEAR);
            glTexGeni(GL_T, GL_TEXTURE_GEN_MODE, GL_OBJECT_LINEAR);
            glBegin(GL_TRIANGLE_STRIP);
            glVertex2f(-1f, -1f);
            glVertex2f(1f, -1f);
            glVertex2f(-1f, 1f);
            glVertex2f(1f, 1f);
            glEnd();
            g.frame("generated");
            glDisable(GL_TEXTURE_GEN_S);
            glDisable(GL_TEXTURE_GEN_T);
            glDisable(GL_TEXTURE_2D);
        });
        g.legacy("fixed: client pointer on a vertex array object", () -> {
            GlConformanceScenarios.target();
            glVertexPointer(2, GL_FLOAT, 0, BufferUtils.createFloatBuffer(12));
            g.record("error", GlConformanceScenarios.errorName(GlConformanceScenarios.drain()));
        });
        g.legacy("fixed: client arrays", () -> {
            GlConformanceScenarios.target();
            // Client memory is only for vertex array 0: with another bound, the pointer calls are refused and a driver draws from nothing.
            glBindVertexArray(0);
            final FloatBuffer positions = BufferUtils.createFloatBuffer(12);
            for (int i = 0; i < 6; i++) positions.put(SHAPE[i][0]).put(SHAPE[i][1]);
            positions.flip();
            final ByteBuffer colors = BufferUtils.createByteBuffer(24);
            for (int i = 0; i < 6; i++) colors.put((byte) (i * 40)).put((byte) (255 - i * 40)).put((byte) 128).put((byte) 255);
            colors.flip();
            glEnableClientState(GL_VERTEX_ARRAY);
            glEnableClientState(GL_COLOR_ARRAY);
            glVertexPointer(2, GL_FLOAT, 0, positions);
            glColorPointer(4, GL_UNSIGNED_BYTE, 0, colors);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            g.frame("arrays");
            glClear(GL_COLOR_BUFFER_BIT);
            final ByteBuffer indices = bytes(5, 3, 1, 0, 2, 4);
            glDrawElements(GL_TRIANGLES, indices);
            g.frame("elements");
            glDisableClientState(GL_VERTEX_ARRAY);
            glDisableClientState(GL_COLOR_ARRAY);
            glColor4f(1f, 1f, 1f, 1f);
        });
        g.legacy("fixed: display lists", () -> {
            GlConformanceScenarios.target();
            final int list = glGenLists(1);
            glNewList(list, GL_COMPILE);
            glColor3f(0f, 1f, 0f);
            glBegin(GL_TRIANGLES);
            glVertex2f(-0.8f, -0.7f);
            glVertex2f(0.6f, -0.5f);
            glVertex2f(-0.3f, 0.7f);
            glEnd();
            glTranslatef(0.3f, 0.1f, 0f);
            glEndList();
            g.frame("compiling draws nothing");
            glPushMatrix();
            glCallList(list);
            g.frame("called once");
            glCallList(list);
            g.frame("called twice, moved");
            glPopMatrix();
            glDeleteLists(list, 1);
            glColor4f(1f, 1f, 1f, 1f);
        });
        g.legacy("fixed: shade model and attribute stack", () -> {
            GlConformanceScenarios.target();
            glShadeModel(GL_FLAT);
            glBegin(GL_TRIANGLES);
            glColor3f(1f, 0f, 0f);
            glVertex2f(-0.8f, -0.7f);
            glColor3f(0f, 1f, 0f);
            glVertex2f(0.6f, -0.5f);
            glColor3f(0f, 0f, 1f);
            glVertex2f(-0.3f, 0.7f);
            glEnd();
            g.frame("flat");
            glShadeModel(GL_SMOOTH);
            glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_CURRENT_BIT);
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            glColor3f(1f, 0f, 1f);
            glPopAttrib();
            g.record("blend restored", glGetBoolean(GL_BLEND));
            g.record("blend src restored", glGetInteger(GL_BLEND_SRC_RGB));
            final FloatBuffer current = BufferUtils.createFloatBuffer(16);
            glGetFloatv(GL_CURRENT_COLOR, current);
            g.record("colour restored", current.get(0) + " " + current.get(1) + " " + current.get(2));
            glColor4f(1f, 1f, 1f, 1f);
        });
        g.legacy("fixed: quads through a shader, and legacy GLSL", () -> {
            GlConformanceScenarios.target();
            program("""
                #version 120
                varying vec4 c;
                void main() { gl_Position = ftransform(); c = gl_Color; }
                """, """
                #version 120
                varying vec4 c;
                void main() { gl_FragColor = c.bgra; }
                """);
            glBegin(GL_QUADS);
            for (float[] p : SHAPE) {
                glColor4f(p[3], p[4], p[5], p[6]);
                glVertex2f(p[0], p[1]);
            }
            glEnd();
            g.frame("image");
            glColor4f(1f, 1f, 1f, 1f);
        });
    }

    private static ByteBuffer floats(float... values) {
        final ByteBuffer b = BufferUtils.createByteBuffer(values.length * 4);
        b.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(values);
        return b;
    }
}
