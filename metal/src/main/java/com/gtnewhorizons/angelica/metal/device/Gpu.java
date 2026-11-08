package com.gtnewhorizons.angelica.metal.device;

import com.gtnewhorizons.angelica.glemu.gl.DriverMessageSink;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.KHRDebug;

import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sendZ;

public final class Gpu extends GpuCaps {
    {
        maxPerStageDescriptorSamplers = 16;
        minUniformBufferOffsetAlignment = 256;
        minStorageBufferOffsetAlignment = 16;
        maxImageDimension1D = 16384;
        maxImageDimension3D = 2048;
        maxImageDimensionCube = 16384;
        maxFramebufferWidth = 16384;
        maxFramebufferHeight = 16384;
        maxImageArrayLayers = 2048;
        // Every Metal GPU this runs on supports 4, and not every one 8.
        maxSamples = 4;
        maxPerStageDescriptorSampledImages = 128;
        // GL's required blocks plus GLSM's own; the linker checks a stage's total buffer use against MAX_BUFFER_ARGUMENTS.
        maxPerStageDescriptorUniformBuffers = 15;
        maxPerStageDescriptorStorageBuffers = 16;
        maxPerStageDescriptorStorageImages = 16;
        maxReadWriteImagesPerStage = 8;
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
    /** Whether a render pass can say where its samples are, which is how a draw with GL_MULTISAMPLE off puts them all at the pixel's centre. */
    public boolean programmableSamplePositions;
    public boolean unifiedMemory;
    public boolean samplerLodBias;

    public static final int MAX_BUFFER_ARGUMENTS = 31;
    public long recommendedWorkingSet;

    private static final long STATUS = sel("status"), ERROR = sel("error");
    private static final long NAME = sel("name"), NEW_QUEUE = sel("newCommandQueue"), SUPPORTS_FAMILY = sel("supportsFamily:");
    private static final long HAS_UNIFIED = sel("hasUnifiedMemory"), SUPPORTS_BC = sel("supportsBCTextureCompression");
    private static final long WORKING_SET = sel("recommendedMaxWorkingSetSize"), ALLOCATED = sel("currentAllocatedSize");

    public static boolean isMacOs14OrLater() {
        try {
            return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]) >= 14;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private volatile boolean reportedCommandFailure;

    /** Reports the first command buffer that failed; Metal gives no other sign of it. */
    public void reportIfFailed(long commandBuffer, String what, long serial) {
        if (reportedCommandFailure || send(commandBuffer, STATUS) != Mtl.CB_ERROR) return;
        reportedCommandFailure = true;
        final String problem = ObjC.errorDescription(send(commandBuffer, ERROR));
        LOG.error("{} {} failed: {}", what, serial, problem);
        final DriverMessageSink sink = driverMessages;
        if (sink != null) sink.message(KHRDebug.GL_DEBUG_TYPE_ERROR, KHRDebug.GL_DEBUG_SEVERITY_HIGH, what + " " + serial + " failed: " + problem);
    }

    public boolean create() {
        device = ObjC.createSystemDefaultDevice();
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
        programmableSamplePositions = sendZ(device, sel("areProgrammableSamplePositionsSupported"));
        com.gtnewhorizons.angelica.glemu.resource.Formats.nativeRgb5A1 = appleSilicon;
        com.gtnewhorizons.angelica.glemu.shader.DoubleLowering.useInt64(appleSilicon);
        unifiedMemory = sendZ(device, HAS_UNIFIED);
        textureCompressionBC = sendZ(device, SUPPORTS_BC);
        integerResolve = false;
        com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.cubeImagesAsArrays = true;
        com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.matrixArraysFlattened = true;
        com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.gatherEdgesInShader = true;
        com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.lodBiasInShader = true;
        depthBlitScales = false;
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
