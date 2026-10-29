package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

public final class GlState {
    public static final int MAX_UNITS = 48;
    public static final int MAX_DRAW_BUFFERS = 8;
    public static final int MAX_BUFFER_BINDINGS = 64;
    public static final int MAX_IMAGE_UNITS = 16;

    public static final int T_1D = 0, T_2D = 1, T_3D = 2, T_CUBE = 3, T_1D_ARRAY = 4, T_2D_ARRAY = 5, T_RECT = 6, T_BUFFER = 7, T_CUBE_ARRAY = 8, T_2D_MS = 9, T_COUNT = 10;

    public final Thread owner;

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
    public final int[] attribKind = new int[Vao.MAX_ATTRIBS];

    public int pipelineGen = 1;
    public int dynamicGen = 1;
    public int textureGen = 1;
    public int bufferBindingGen = 1;

    public GlState(Thread owner) {
        this.owner = owner;
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) colorMask[i] = new boolean[] {true, true, true, true};
        for (int i = 0; i < Vao.MAX_ATTRIBS; i++) attribValue[i * 4 + 3] = 1f;
    }

    private static int[] fill(int v) {
        final int[] a = new int[MAX_DRAW_BUFFERS];
        java.util.Arrays.fill(a, v);
        return a;
    }

    public static int targetIndex(int glTarget) {
        return switch (glTarget) {
            case GL11.GL_TEXTURE_1D -> T_1D;
            case GL11.GL_TEXTURE_2D -> T_2D;
            case org.lwjgl.opengl.GL12.GL_TEXTURE_3D -> T_3D;
            case org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP,
                 org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X, org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
                 org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
                 org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z -> T_CUBE;
            case org.lwjgl.opengl.GL30.GL_TEXTURE_1D_ARRAY -> T_1D_ARRAY;
            case org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY -> T_2D_ARRAY;
            case org.lwjgl.opengl.GL31.GL_TEXTURE_RECTANGLE -> T_RECT;
            case org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER -> T_BUFFER;
            case org.lwjgl.opengl.GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> T_CUBE_ARRAY;
            case org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE -> T_2D_MS;
            default -> -1;
        };
    }

    public int boundTexture(int glTarget) {
        final int t = targetIndex(glTarget);
        return t < 0 ? 0 : textures[activeUnit][t];
    }
}
