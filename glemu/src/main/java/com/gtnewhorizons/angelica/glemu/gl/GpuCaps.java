package com.gtnewhorizons.angelica.glemu.gl;

public abstract class GpuCaps {
    public boolean fragmentStoresAndAtomics = true;
    /** Metal, and so MoltenVK, has no resolve for integer formats, though it renders and reads them. */
    public boolean integerResolve = true;
    /** Whether a device command can stretch depth and stencil in a blit. */
    public boolean depthBlitScales = true;
    public boolean vertexPipelineStoresAndAtomics = true;
    public boolean samplerAnisotropy;
    public boolean drawIndirectFirstInstance;
    public boolean drawIndirectCount;
    public boolean shaderDrawParameters;
    public boolean geometryShader;
    public boolean tessellationShader;
    public boolean cullDistance;
    public boolean indexTypeUint8;
    public boolean textureCompressionBC;
    public boolean imageCubeArray;
    public int maxUniformBlockSize = 65536;
    public int maxImageDimension1D;
    public int maxImageDimension2D;
    public float maxSamplerAnisotropy;
    public float lineWidthMin = 1f;
    public float lineWidthMax = 1f;
    public float pointSizeMax = 1f;
    public String deviceName = "";
    public volatile DriverMessageSink driverMessages;
    public String driverName = "";
    public long minUniformBufferOffsetAlignment;
    public long minStorageBufferOffsetAlignment;
    public int maxImageDimension3D;
    public int maxImageDimensionCube;
    public int maxFramebufferWidth;
    public int maxFramebufferHeight;
    public int maxImageArrayLayers;
    /** The most samples every kind of attachment can have. */
    public int maxSamples = 1;
    public int maxPerStageDescriptorSampledImages;
    public int maxPerStageDescriptorUniformBuffers;
    public int maxPerStageDescriptorStorageBuffers;
    public int maxPerStageDescriptorStorageImages;
    /** Fewer than the images above on Apple, whose stages can read and write only 8. */
    public int maxReadWriteImagesPerStage = Integer.MAX_VALUE;
    public int maxPerStageDescriptorSamplers;
    public int maxColorAttachments;
    public int maxComputeWorkGroupInvocations;
    /** What a device's own geometry and tessellation stages pass between them, in components; read only when it runs both stages itself. */
    public int maxGeometryInputComponents, maxGeometryOutputComponents, maxTessControlInputComponents, maxTessControlOutputComponents,
        maxTessControlTotalOutputComponents, maxTessPatchComponents, maxTessEvaluationInputComponents, maxTessEvaluationOutputComponents, maxTessGenLevel;
}
