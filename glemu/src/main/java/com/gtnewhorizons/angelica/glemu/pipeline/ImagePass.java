package com.gtnewhorizons.angelica.glemu.pipeline;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

/**
 * Copies one image to another by drawing through the emulated GL, for blits a device has no command for: into a multisampled image, and out of a
 * multisampled integer one, where GL takes sample 0. The nearest texel is taken, as neither case can filter.
 */
public final class ImagePass<T extends TexBase> {
    private static final int UNIT = GlState.MAX_UNITS - 1;
    private static final String VERTEX = """
        #version 330 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private final GlBase<T, ?, ?> gl;
    private final Object2IntOpenHashMap<String> programs = new Object2IntOpenHashMap<>();
    private int vao;

    public ImagePass(GlBase<T, ?, ?> gl) {
        this.gl = gl;
    }

    private static String fragment(String prefix, boolean multisampled, int aspect) {
        final String fetch = "texelFetch(angelica_source, at, " + (multisampled ? "0" : "angelica_level") + ")";
        final String output = switch (aspect) {
            case Formats.ASPECT_DEPTH -> "    gl_FragDepth = " + fetch + ".r;\n";
            case Formats.ASPECT_STENCIL -> "    if (angelica_bit != 0 && (int(" + fetch + ".r) & angelica_bit) == 0) discard;\n";
            default -> "    o = " + fetch + ";\n";
        };
        return "#version 330 core\n"
            + "uniform " + prefix + "sampler2D" + (multisampled ? "MS" : "") + " angelica_source;\n"
            + "uniform vec4 angelica_map;\nuniform int angelica_level;\nuniform int angelica_bit;\n"
            + (aspect == Formats.ASPECT_COLOR ? "layout(location = 0) out " + prefix + "vec4 o;\n" : "")
            + "void main() {\n    ivec2 at = ivec2(floor(gl_FragCoord.xy * angelica_map.xy + angelica_map.zw));\n"
            + output + "}\n";
    }

    private int program(String prefix, boolean multisampled, int aspect) {
        final String key = prefix + multisampled + aspect;
        final int known = programs.getInt(key);
        if (known != 0) return known;
        final int vs = gl.createShader(GL20.GL_VERTEX_SHADER), fs = gl.createShader(GL20.GL_FRAGMENT_SHADER);
        gl.shaderSource(vs, VERTEX);
        gl.compileShader(vs);
        gl.shaderSource(fs, fragment(prefix, multisampled, aspect));
        gl.compileShader(fs);
        final int program = gl.createProgram();
        gl.attachShader(program, vs);
        gl.attachShader(program, fs);
        gl.linkProgram(program);
        gl.deleteShader(vs);
        gl.deleteShader(fs);
        if (gl.getProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            gl.warnOnce("image pass failed to link: " + gl.getProgramInfoLog(program, 4096));
            return 0;
        }
        programs.put(key, program);
        return program;
    }

    /** Whether a source can be read here: arrays, volumes and cube faces are left to the caller. */
    public static boolean reads(TexBase source) {
        return source.renderbuffer || source.target == GL11.GL_TEXTURE_2D || source.target == GL32.GL_TEXTURE_2D_MULTISAMPLE;
    }

    /** A shader cannot write stencil, so its values come back to the host as texels, and each bit is then drawn on its own. */
    public void copyStencil(GlState st, T source, int sourceLevel, int sourceLayer, T target, int level, int layer, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        final int width = source.levelWidth(sourceLevel), height = source.levelHeight(sourceLevel);
        final long values = MemoryUtil.nmemCalloc((long) width * height, 1);
        final PixelConvert.Store tight = new PixelConvert.Store();
        tight.alignment = 1;
        final T texels = gl.textures().internalTexture(GL11.GL_TEXTURE_2D, Formats.of(GL30.GL_R8UI, 0, 0), width, height, 1, 1, "stencilBits");
        try {
            gl.textures().readImage(source, Formats.ASPECT_STENCIL, sourceLevel, sourceLayer, 0, 0, width, height, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, tight, values);
            final PixelConvert.Store unpack = new PixelConvert.Store();
            unpack.copyFrom(st.unpack);
            st.unpack.copyFrom(tight);
            try {
                gl.textures().upload(st, texels, 0, 0, 0, 0, width, height, 1, 0, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, values, false);
            } finally {
                st.unpack.copyFrom(unpack);
            }
            draw(st, texels, 0, target, level, layer, Formats.ASPECT_STENCIL, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
        } finally {
            MemoryUtil.nmemFree(values);
            gl.releaseImage(texels);
        }
    }

    /** The rectangles are as a blit gives them, already clipped, so either can be flipped. */
    public void copy(GlState st, T source, int sourceLevel, T target, int level, int layer, boolean depth, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        draw(st, source, sourceLevel, target, level, layer, depth ? Formats.ASPECT_DEPTH : Formats.ASPECT_COLOR, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
    }

    private void draw(GlState st, T source, int sourceLevel, T target, int level, int layer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        final boolean multisampled = source.samples > 1, depth = aspect == Formats.ASPECT_DEPTH, stencil = aspect == Formats.ASPECT_STENCIL;
        final boolean unsigned = source.format.kind() == Formats.K_UINT || source.format.pf() == PixelFormats.PF_RGB10A2_UINT;
        final String prefix = depth || !source.format.isInteger() ? "" : unsigned ? "u" : "i";
        final int program = program(prefix, multisampled, aspect);
        if (program == 0) return;
        if (vao == 0) vao = gl.genVertexArrays();

        final int prevProgram = st.program, prevVao = st.vao.id, prevActive = st.activeUnit, drawFbo = st.drawFbo, readFbo = st.readFbo;
        final int slot = multisampled ? GlState.T_2D_MS : GlState.T_2D;
        final int prevTexture = st.textures[UNIT][slot], prevSampler = st.samplers[UNIT];
        final boolean depthTest = st.depthTest, depthMask = st.depthMask, cull = st.cull, stencilTest = st.stencilTest, scissorTest = st.scissorTest;
        final boolean logicOp = st.logicOpEnabled, depthClamp = st.depthClamp, polyFill = st.polyOffsetFill, skip = st.conditionalSkip;
        final boolean viewportsVary = st.viewportsVary, scissorsVary = st.scissorsVary, depthRangesVary = st.depthRangesVary;
        final boolean discard = st.rasterizerDiscard, coverage = st.sampleCoverage, maskEnabled = st.sampleMaskEnabled, sampleShading = st.sampleShading;
        final int polyFront = st.polygonModeFront, polyBack = st.polygonModeBack, depthFunc = st.depthFunc;
        final int vpX = st.vpX, vpY = st.vpY, vpW = st.vpW, vpH = st.vpH, scX = st.scX, scY = st.scY, scW = st.scW, scH = st.scH;
        final double near = st.depthNear, far = st.depthFar;
        final boolean[] blend = st.blend.clone();
        final boolean[] mask = st.colorMask[0].clone();
        final int[] func = st.stencilFunc.clone(), ref = st.stencilRef.clone(), valueMask = st.stencilValueMask.clone(), writeMask = st.stencilWriteMask.clone();
        final int[] fail = st.stencilFail.clone(), depthFail = st.stencilDepthFail.clone(), pass = st.stencilPass.clone();
        final int minFilter = source.minFilter, magFilter = source.magFilter, compareMode = source.compareMode;
        final int name = gl.textures().borrowName(source);
        final int targetName = target.renderbuffer ? target.id : gl.textures().borrowName(target);
        final int fbo = gl.genFramebuffers();
        try {
            st.program = program;
            gl.bindVertexArray(vao);
            gl.activeTexture(GL13.GL_TEXTURE0 + UNIT);
            st.textures[UNIT][slot] = name;
            st.samplers[UNIT] = 0;
            source.minFilter = GL11.GL_NEAREST;
            source.magFilter = GL11.GL_NEAREST;
            source.compareMode = GL11.GL_NONE;
            source.samplerDirty = true;
            gl.uniform1i(gl.getUniformLocation(program, "angelica_source"), UNIT);
            gl.uniform1i(gl.getUniformLocation(program, "angelica_level"), sourceLevel);
            final float scaleX = (float) (sx1 - sx0) / (dx1 - dx0), scaleY = (float) (sy1 - sy0) / (dy1 - dy0);
            gl.uniform4f(gl.getUniformLocation(program, "angelica_map"), scaleX, scaleY, sx0 - dx0 * scaleX, sy0 - dy0 * scaleY);

            gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            final int attachment = depth ? GL30.GL_DEPTH_ATTACHMENT : stencil ? GL30.GL_STENCIL_ATTACHMENT : GL30.GL_COLOR_ATTACHMENT0;
            if (target.renderbuffer) gl.framebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_RENDERBUFFER, target.id);
            else if (target.layers > 1 || target.is3D() || target.target == GL13.GL_TEXTURE_CUBE_MAP) gl.framebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, attachment, targetName, level, layer);
            else gl.framebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, target.target, targetName, level);

            st.conditionalSkip = false;
            st.rasterizerDiscard = false;
            st.depthTest = depth;
            st.depthFunc = GL11.GL_ALWAYS;
            st.depthMask = depth;
            st.depthClamp = false;
            st.depthNear = 0;
            st.depthFar = 1;
            st.cull = false;
            st.stencilTest = stencil;
            st.logicOpEnabled = false;
            st.polyOffsetFill = false;
            st.polygonModeFront = st.polygonModeBack = GL11.GL_FILL;
            st.sampleCoverage = st.sampleMaskEnabled = st.sampleShading = false;
            st.viewportsVary = st.scissorsVary = st.depthRangesVary = false;
            st.vpX = 0;
            st.vpY = 0;
            st.vpW = target.levelWidth(level);
            st.vpH = target.levelHeight(level);
            st.scissorTest = true;
            st.scX = Math.min(dx0, dx1);
            st.scY = Math.min(dy0, dy1);
            st.scW = Math.abs(dx1 - dx0);
            st.scH = Math.abs(dy1 - dy0);
            Arrays.fill(st.blend, false);
            Arrays.fill(st.colorMask[0], true);
            st.pipelineGen++;
            st.dynamicGen++;
            if (!stencil) {
                gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
            } else {
                final int bitLocation = gl.getUniformLocation(program, "angelica_bit");
                for (int face = 0; face < 2; face++) {
                    st.stencilFunc[face] = GL11.GL_ALWAYS;
                    st.stencilValueMask[face] = 0xFF;
                    st.stencilFail[face] = st.stencilDepthFail[face] = st.stencilPass[face] = GL11.GL_REPLACE;
                }
                // First everything to zero, then each bit where the source has it set.
                for (int bit = -1; bit < 8; bit++) {
                    for (int face = 0; face < 2; face++) {
                        st.stencilRef[face] = bit < 0 ? 0 : 0xFF;
                        st.stencilWriteMask[face] = bit < 0 ? 0xFF : 1 << bit;
                    }
                    gl.uniform1i(bitLocation, bit < 0 ? 0 : 1 << bit);
                    st.pipelineGen++;
                    st.dynamicGen++;
                    gl.drawArrays(GL11.GL_TRIANGLES, 0, 3);
                }
            }
        } finally {
            source.minFilter = minFilter;
            source.magFilter = magFilter;
            source.compareMode = compareMode;
            source.samplerDirty = true;
            st.textures[UNIT][slot] = prevTexture;
            st.samplers[UNIT] = prevSampler;
            gl.textures().returnName(name);
            if (!target.renderbuffer) gl.textures().returnName(targetName);
            gl.activeTexture(GL13.GL_TEXTURE0 + prevActive);
            st.conditionalSkip = skip;
            st.rasterizerDiscard = discard;
            st.depthTest = depthTest;
            st.depthFunc = depthFunc;
            st.depthMask = depthMask;
            st.depthClamp = depthClamp;
            st.depthNear = near;
            st.depthFar = far;
            st.cull = cull;
            st.stencilTest = stencilTest;
            st.scissorTest = scissorTest;
            st.logicOpEnabled = logicOp;
            st.polyOffsetFill = polyFill;
            st.polygonModeFront = polyFront;
            st.polygonModeBack = polyBack;
            st.sampleCoverage = coverage;
            st.sampleMaskEnabled = maskEnabled;
            st.sampleShading = sampleShading;
            st.viewportsVary = viewportsVary;
            st.scissorsVary = scissorsVary;
            st.depthRangesVary = depthRangesVary;
            st.vpX = vpX;
            st.vpY = vpY;
            st.vpW = vpW;
            st.vpH = vpH;
            st.scX = scX;
            st.scY = scY;
            st.scW = scW;
            st.scH = scH;
            System.arraycopy(blend, 0, st.blend, 0, blend.length);
            System.arraycopy(mask, 0, st.colorMask[0], 0, 4);
            System.arraycopy(func, 0, st.stencilFunc, 0, 2);
            System.arraycopy(ref, 0, st.stencilRef, 0, 2);
            System.arraycopy(valueMask, 0, st.stencilValueMask, 0, 2);
            System.arraycopy(writeMask, 0, st.stencilWriteMask, 0, 2);
            System.arraycopy(fail, 0, st.stencilFail, 0, 2);
            System.arraycopy(depthFail, 0, st.stencilDepthFail, 0, 2);
            System.arraycopy(pass, 0, st.stencilPass, 0, 2);
            st.pipelineGen++;
            st.dynamicGen++;
            gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            gl.deleteFramebuffers(fbo);
            gl.bindVertexArray(prevVao);
            st.program = prevProgram;
        }
    }
}
