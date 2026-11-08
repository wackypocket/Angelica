package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

public final class GlState {
    public final DebugOutput debug = new DebugOutput();
    public int error = GL11.GL_NO_ERROR;
    public boolean conditionalRender, conditionalSkip;
    public boolean pointOriginLower;
    public boolean sampleCoverage, sampleMaskEnabled, sampleShading, sampleAlphaToOne;
    public boolean seamlessCubes;
    public int sampleMaskWord = -1;
    public float minSampleShading = 1f;
    public float sampleCoverageValue = 1f;
    public boolean sampleCoverageInvert;
    public int patchVertices = 3;
    public final float[] patchDefaultOuter = {1.0f, 1.0f, 1.0f, 1.0f}, patchDefaultInner = {1.0f, 1.0f};
    public int baseInstance;
    /** What a shader reads as gl_BaseVertex and gl_DrawID for the draw being made. */
    public int drawBaseVertex, drawId;
    public static final int MAX_UNITS = 96;
    public static final int MAX_DRAW_BUFFERS = 8;
    public static final int MAX_BUFFER_BINDINGS = 84;
    public static final int MAX_VIEWPORTS = 16;
    public static final int MAX_IMAGE_UNITS = 16;
    /** Whether a viewport has been set by index since the last call that sets them all, and what each scissor test is beyond the first. */
    public boolean viewportsVary, scissorsVary, depthRangesVary;
    public final boolean[] indexedScissorTest = new boolean[MAX_VIEWPORTS];
    public final float[][] indexedViewports = new float[MAX_VIEWPORTS][], indexedScissors = new float[MAX_VIEWPORTS][], indexedDepthRanges = new float[MAX_VIEWPORTS][];

    public static final int T_1D = 0, T_2D = 1, T_3D = 2, T_CUBE = 3, T_1D_ARRAY = 4, T_2D_ARRAY = 5, T_RECT = 6, T_BUFFER = 7, T_CUBE_ARRAY = 8, T_2D_MS = 9, T_2D_MS_ARRAY = 10, T_COUNT = 11;

    public int activeUnit;
    public final int[][] textures = new int[MAX_UNITS][T_COUNT];
    public final int[] samplers = new int[MAX_UNITS];

    public int arrayBuffer, uniformBuffer, storageBuffer, copyRead, copyWrite, pixelPack, pixelUnpack, drawIndirect, dispatchIndirect, textureBuffer, queryBuffer, atomicBuffer, xfbBuffer, parameterBuffer;
    public final int[] uboBuffer = new int[MAX_BUFFER_BINDINGS];
    public final long[] uboOffset = new long[MAX_BUFFER_BINDINGS];
    public final long[] uboSize = new long[MAX_BUFFER_BINDINGS];
    public final int[] ssboBuffer = new int[MAX_BUFFER_BINDINGS];
    public final long[] ssboOffset = new long[MAX_BUFFER_BINDINGS];
    public final long[] ssboSize = new long[MAX_BUFFER_BINDINGS];
    public final int[] atomicBufferAt = new int[MAX_BUFFER_BINDINGS];
    public final long[] atomicOffset = new long[MAX_BUFFER_BINDINGS];
    public final long[] atomicSize = new long[MAX_BUFFER_BINDINGS];

    public final int[] imageTex = new int[MAX_IMAGE_UNITS];
    public final int[] imageLevel = new int[MAX_IMAGE_UNITS];
    public final boolean[] imageLayered = new boolean[MAX_IMAGE_UNITS];
    public final int[] imageLayer = new int[MAX_IMAGE_UNITS];
    public final int[] imageAccess = new int[MAX_IMAGE_UNITS];
    public final int[] imageFormat = new int[MAX_IMAGE_UNITS];

    public final Vao defaultVao = new Vao(0);
    public Vao vao = defaultVao;
    public int program;
    /** What program {@code program} last linked, kept drawing here after a relink of it failed, until glUseProgram changes the program. */
    public com.gtnewhorizons.angelica.glemu.shader.ProgramBase<?> heldExecutable;
    public int drawFbo, readFbo, renderbuffer;

    public final boolean[] blend = new boolean[MAX_DRAW_BUFFERS];
    public final int[] blendSrcRgb = fill(GL11.GL_ONE), blendDstRgb = fill(GL11.GL_ZERO), blendSrcA = fill(GL11.GL_ONE), blendDstA = fill(GL11.GL_ZERO);
    public final int[] blendEqRgb = fill(GL14.GL_FUNC_ADD), blendEqA = fill(GL14.GL_FUNC_ADD);
    public final boolean[][] colorMask = new boolean[MAX_DRAW_BUFFERS][];
    public final float[] blendColor = new float[4];
    public boolean depthTest, depthMask = true, cull, stencilTest, scissorTest, polyOffsetFill, polyOffsetLine, polyOffsetPoint, depthClamp, rasterizerDiscard,
        logicOpEnabled, primitiveRestart, primitiveRestartFixed, programPointSize, sampleAlphaToCoverage, framebufferSrgb, multisample = true;
    public int depthFunc = GL11.GL_LESS;
    public double depthNear = 0.0, depthFar = 1.0;
    public int cullFace = GL11.GL_BACK, frontFace = GL11.GL_CCW;
    /** glClipControl's two settings, as bit 0 for an upper left origin and bit 1 for depth from zero to one. */
    public int clipState;
    public int polygonModeFront = GL11.GL_FILL, polygonModeBack = GL11.GL_FILL;
    public float polyFactor, polyUnits, polyClamp;
    public final int[] stencilFunc = {GL11.GL_ALWAYS, GL11.GL_ALWAYS};
    public final int[] stencilRef = new int[2];
    public final int[] stencilValueMask = {0xFF, 0xFF};
    public final int[] stencilWriteMask = {0xFF, 0xFF};
    public final int[] stencilFail = {GL11.GL_KEEP, GL11.GL_KEEP}, stencilDepthFail = {GL11.GL_KEEP, GL11.GL_KEEP}, stencilPass = {GL11.GL_KEEP, GL11.GL_KEEP};
    public int vpX, vpY, vpW, vpH;
    public int scX, scY, scW, scH;
    public final float[] clearColor = new float[4];
    public double clearDepth = 1.0;
    public int clearStencil;
    public float lineWidth = 1f, pointSize = 1f;
    public int logicOp = GL11.GL_COPY;
    public int clipDistances;
    public int primitiveRestartIndex;
    public boolean defaultDrawNone, defaultReadNone;
    public boolean provokingFirst;

    public final int[][] proxies = new int[10][];

    public final PixelConvert.Store unpack = new PixelConvert.Store();
    public final PixelConvert.Store pack = new PixelConvert.Store();

    public final float[] attribValue = new float[Vao.MAX_ATTRIBS * 4];
    /** What glVertexAttribL set, for an input of doubles. */
    public final double[] attribDoubles = new double[Vao.MAX_ATTRIBS * 4];
    /** Bumped whenever attribValue or attribDoubles changes. */
    public int attribVersion;

    public int pipelineGen = 1;
    public int dynamicGen = 1;

    public GlState() {
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) colorMask[i] = new boolean[] {true, true, true, true};
        for (int i = 0; i < Vao.MAX_ATTRIBS; i++) attribValue[i * 4 + 3] = 1f;
        for (int i = 0; i < Vao.MAX_ATTRIBS; i++) attribDoubles[i * 4 + 3] = 1.0;
    }

    private static int[] fill(int v) {
        final int[] a = new int[MAX_DRAW_BUFFERS];
        Arrays.fill(a, v);
        return a;
    }

    public static int targetIndex(int glTarget) {
        return switch (glTarget) {
            case GL11.GL_TEXTURE_1D -> T_1D;
            case GL11.GL_TEXTURE_2D -> T_2D;
            case GL12.GL_TEXTURE_3D -> T_3D;
            case GL13.GL_TEXTURE_CUBE_MAP,
                 GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
                 GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
                 GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z -> T_CUBE;
            case GL30.GL_TEXTURE_1D_ARRAY -> T_1D_ARRAY;
            case GL30.GL_TEXTURE_2D_ARRAY -> T_2D_ARRAY;
            case GL31.GL_TEXTURE_RECTANGLE -> T_RECT;
            case GL31.GL_TEXTURE_BUFFER -> T_BUFFER;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> T_CUBE_ARRAY;
            case GL32.GL_TEXTURE_2D_MULTISAMPLE -> T_2D_MS;
            case GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> T_2D_MS_ARRAY;
            default -> -1;
        };
    }

    public int boundTexture(int glTarget) {
        final int t = targetIndex(glTarget);
        return t < 0 ? 0 : textures[activeUnit][t];
    }
}
