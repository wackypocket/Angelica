package com.gtnewhorizons.angelica.metal.device;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sendZ;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Gpu extends GpuCaps {
    {
        maxPerStageDescriptorSamplers = 16;
        minUniformBufferOffsetAlignment = 256;
        minStorageBufferOffsetAlignment = 16;
        maxImageDimension3D = 2048;
        maxImageArrayLayers = 2048;
        maxPerStageDescriptorSampledImages = 128;
        maxPerStageDescriptorUniformBuffers = 14;
        maxPerStageDescriptorStorageBuffers = 16;
        maxPerStageDescriptorStorageImages = 8;
        maxColorAttachments = 8;
        maxComputeWorkGroupInvocations = 1024;
        samplerAnisotropy = true;
        drawIndirectFirstInstance = true;
        imageCubeArray = true;
        maxImageDimension2D = 16384;
        maxSamplerAnisotropy = 16f;
        pointSizeMax = 511f;
    }

    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    public long device;
    public long queue;
    public String apiVersion = "";
    public boolean appleSilicon;
    public boolean unifiedMemory;

    public boolean dynamicRenderingCore = true;
    public boolean provokingVertexLast = false;
    public boolean triangleFans = false;
    public boolean indexTypeUint8 = false;
    public boolean logicOp = false;
    public boolean wideLines = false;
    public boolean fillModeNonSolid = true;
    public boolean depthClamp = true;
    public boolean depthBiasClamp = true;
    public boolean independentBlend = true;
    public boolean dualSrcBlend = true;
    public boolean multiDrawIndirect = false;
    public boolean samplerMirrorClampToEdge = true;
    public boolean fragmentStoresAndAtomics = true;
    public boolean vertexPipelineStoresAndAtomics = true;
    public boolean shaderClipDistance = true;
    public boolean samplerLodBias;
    public boolean depth24Stencil8;

    public int maxBufferArguments = 31;
    public int maxVertexInputAttributes = 31;
    public long recommendedWorkingSet;

    private static final long NAME = sel("name"), NEW_QUEUE = sel("newCommandQueue"), SUPPORTS_FAMILY = sel("supportsFamily:");
    private static final long HAS_UNIFIED = sel("hasUnifiedMemory"), SUPPORTS_BC = sel("supportsBCTextureCompression");
    private static final long WORKING_SET = sel("recommendedMaxWorkingSetSize"), ALLOCATED = sel("currentAllocatedSize");
    private static final long D24S8 = sel("isDepth24Stencil8PixelFormatSupported");

    public boolean create() {
        try {
            device = ObjC.createSystemDefaultDevice();
        } catch (Throwable t) {
            LOG.error("Metal is unavailable: {}", t.toString());
            return false;
        }
        if (device == 0L) {
            LOG.error("MTLCreateSystemDefaultDevice returned no device");
            return false;
        }
        if (!sendZ(device, SUPPORTS_FAMILY, Mtl.GPU_FAMILY_MAC2) && !sendZ(device, SUPPORTS_FAMILY, Mtl.GPU_FAMILY_APPLE7)) {
            LOG.error("{} supports neither MTLGPUFamilyMac2 nor MTLGPUFamilyApple7", ObjC.string(send(device, NAME)));
            ObjC.release(device);
            device = 0L;
            return false;
        }
        queue = send(device, NEW_QUEUE);
        if (queue == 0L) {
            LOG.error("Could not create a Metal command queue");
            ObjC.release(device);
            device = 0L;
            return false;
        }
        deviceName = ObjC.string(send(device, NAME));
        appleSilicon = sendZ(device, SUPPORTS_FAMILY, Mtl.GPU_FAMILY_APPLE7);
        unifiedMemory = sendZ(device, HAS_UNIFIED);
        textureCompressionBC = sendZ(device, SUPPORTS_BC);
        depth24Stencil8 = sendZ(device, D24S8);
        recommendedWorkingSet = send(device, WORKING_SET);
        final long probe = ObjC.create("MTLSamplerDescriptor");
        samplerLodBias = ObjC.responds(probe, "setLodBias:");
        ObjC.release(probe);
        apiVersion = sendZ(device, SUPPORTS_FAMILY, Mtl.GPU_FAMILY_METAL3) ? "3" : "2";
        driverName = "macOS " + System.getProperty("os.version", "?");
        LOG.info("Metal device: {} (Metal {}, {}), unified memory={}, BC={}", deviceName, apiVersion, driverName, unifiedMemory, textureCompressionBC);
        return true;
    }

    public long allocatedBytes() {
        return device == 0L ? 0L : send(device, ALLOCATED);
    }

    public boolean isNvidia() { return false; }
    public boolean isAmd() {
        final String n = deviceName.toLowerCase(Locale.ROOT);
        return n.contains("amd") || n.contains("radeon");
    }
    public boolean isIntel() { return deviceName.toLowerCase(Locale.ROOT).contains("intel"); }
    public boolean isApple() { return appleSilicon || deviceName.toLowerCase(Locale.ROOT).startsWith("apple"); }

    public void destroy() {
        if (queue != 0L) ObjC.release(queue);
        if (device != 0L) ObjC.release(device);
        queue = 0L;
        device = 0L;
    }
}
