package com.gtnewhorizons.angelica.metal.pipeline;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.resource.Target;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

public final class ClearPass {
    private static final String VERTEX = """
        #version 330 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private static final String FRAGMENT;

    static {
        final StringBuilder sb = new StringBuilder("#version 330 core\nuniform vec4 angelica_clearColor;\nuniform float angelica_clearDepth;\n");
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) sb.append("layout(location = ").append(i).append(") out vec4 o").append(i).append(";\n");
        sb.append("void main() {\n");
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) sb.append("    o").append(i).append(" = angelica_clearColor;\n");
        FRAGMENT = sb.append("    gl_FragDepth = angelica_clearDepth;\n}\n").toString();
    }

    private final MetalGL gl;
    private int program;
    private int colorLocation = -1;
    private int depthLocation = -1;
    private int vao;

    public ClearPass(MetalGL gl) {
        this.gl = gl;
    }

    private boolean ensureProgram() {
        if (program != 0) return colorLocation >= 0;
        final int vs = gl.createShader(GL20.GL_VERTEX_SHADER);
        gl.shaderSource(vs, VERTEX);
        gl.compileShader(vs);
        final int fs = gl.createShader(GL20.GL_FRAGMENT_SHADER);
        gl.shaderSource(fs, FRAGMENT);
        gl.compileShader(fs);
        program = gl.createProgram();
        gl.attachShader(program, vs);
        gl.attachShader(program, fs);
        gl.linkProgram(program);
        gl.deleteShader(vs);
        gl.deleteShader(fs);
        if (gl.getProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            gl.warnOnce("clear program failed to link: " + gl.getProgramInfoLog(program, 4096));
            return false;
        }
        colorLocation = gl.getUniformLocation(program, "angelica_clearColor");
        depthLocation = gl.getUniformLocation(program, "angelica_clearDepth");
        vao = gl.genVertexArrays();
        return colorLocation >= 0;
    }

    public void clear(GlState st, Target t, boolean[] colors, boolean depth, boolean stencil) {
        final int prevProgram = st.program;
        final int prevVao = st.vao.id;
        if (!ensureProgram()) return;

        final boolean depthTest = st.depthTest, depthMask = st.depthMask, cull = st.cull, stencilTest = st.stencilTest;
        final boolean logicOp = st.logicOpEnabled, alphaToCoverage = st.sampleAlphaToCoverage, depthClamp = st.depthClamp;
        final int polyFront = st.polygonModeFront, polyBack = st.polygonModeBack, depthFunc = st.depthFunc;
        final int vpX = st.vpX, vpY = st.vpY, vpW = st.vpW, vpH = st.vpH;
        final double near = st.depthNear, far = st.depthFar;
        final boolean[] blend = st.blend.clone();
        final boolean polyFill = st.polyOffsetFill;
        final boolean[][] colorMask = new boolean[GlState.MAX_DRAW_BUFFERS][];
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) colorMask[i] = st.colorMask[i].clone();
        final int[] func = st.stencilFunc.clone(), ref = st.stencilRef.clone(), valueMask = st.stencilValueMask.clone();
        final int[] fail = st.stencilFail.clone(), depthFail = st.stencilDepthFail.clone(), pass = st.stencilPass.clone();
        try {
            gl.useProgram(program);
            gl.bindVertexArray(vao);
            gl.uniform4f(colorLocation, st.clearColor[0], st.clearColor[1], st.clearColor[2], st.clearColor[3]);
            if (depthLocation >= 0) gl.uniform1f(depthLocation, (float) Math.max(0, Math.min(1, st.clearDepth)));
            st.depthTest = depth;
            st.depthFunc = GL11.GL_ALWAYS;
            st.depthMask = depth;
            st.depthClamp = false;
            st.depthNear = 0;
            st.depthFar = 1;
            st.cull = false;
            st.logicOpEnabled = false;
            st.sampleAlphaToCoverage = false;
            st.polyOffsetFill = false;
            st.polygonModeFront = st.polygonModeBack = GL11.GL_FILL;
            st.vpX = 0;
            st.vpY = 0;
            st.vpW = t.width;
            st.vpH = t.height;
            java.util.Arrays.fill(st.blend, false);
            for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
                final boolean keep = i < colors.length && colors[i];
                for (int c = 0; c < 4; c++) st.colorMask[i][c] = keep && colorMask[i][c];
            }
            st.stencilTest = stencil;
            if (stencil) {
                for (int face = 0; face < 2; face++) {
                    st.stencilFunc[face] = GL11.GL_ALWAYS;
                    st.stencilRef[face] = st.clearStencil & 0xFF;
                    st.stencilValueMask[face] = 0xFF;
                    st.stencilFail[face] = st.stencilDepthFail[face] = st.stencilPass[face] = GL11.GL_REPLACE;
                }
            }
            st.pipelineGen++;
            st.dynamicGen++;
            gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            st.depthTest = depthTest;
            st.depthFunc = depthFunc;
            st.depthMask = depthMask;
            st.depthClamp = depthClamp;
            st.depthNear = near;
            st.depthFar = far;
            st.cull = cull;
            st.stencilTest = stencilTest;
            st.logicOpEnabled = logicOp;
            st.sampleAlphaToCoverage = alphaToCoverage;
            st.polyOffsetFill = polyFill;
            st.polygonModeFront = polyFront;
            st.polygonModeBack = polyBack;
            st.vpX = vpX;
            st.vpY = vpY;
            st.vpW = vpW;
            st.vpH = vpH;
            System.arraycopy(blend, 0, st.blend, 0, blend.length);
            for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) System.arraycopy(colorMask[i], 0, st.colorMask[i], 0, 4);
            System.arraycopy(func, 0, st.stencilFunc, 0, 2);
            System.arraycopy(ref, 0, st.stencilRef, 0, 2);
            System.arraycopy(valueMask, 0, st.stencilValueMask, 0, 2);
            System.arraycopy(fail, 0, st.stencilFail, 0, 2);
            System.arraycopy(depthFail, 0, st.stencilDepthFail, 0, 2);
            System.arraycopy(pass, 0, st.stencilPass, 0, 2);
            st.pipelineGen++;
            st.dynamicGen++;
            gl.bindVertexArray(prevVao);
            gl.useProgram(prevProgram);
        }
    }
}
