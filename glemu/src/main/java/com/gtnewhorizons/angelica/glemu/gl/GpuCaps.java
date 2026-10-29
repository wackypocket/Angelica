package com.gtnewhorizons.angelica.glemu.gl;

public abstract class GpuCaps {
    public boolean samplerAnisotropy;
    public boolean drawIndirectFirstInstance;
    public boolean drawIndirectCount;
    public boolean shaderDrawParameters;
    public boolean geometryShader;
    public boolean tessellationShader;
    public boolean textureCompressionBC;
    public boolean imageCubeArray;
    public int maxImageDimension2D;
    public float maxSamplerAnisotropy;
    public float lineWidthMin = 1f;
    public float lineWidthMax = 1f;
    public float pointSizeMax = 1f;
    public String deviceName = "";
    public String driverName = "";
    public long minUniformBufferOffsetAlignment;
    public long minStorageBufferOffsetAlignment;
    public int maxImageDimension3D;
    public int maxImageArrayLayers;
    public int maxPerStageDescriptorSampledImages;
    public int maxPerStageDescriptorUniformBuffers;
    public int maxPerStageDescriptorStorageBuffers;
    public int maxPerStageDescriptorStorageImages;
    public int maxPerStageDescriptorSamplers;
    public int maxColorAttachments;
    public int maxComputeWorkGroupInvocations;
}
