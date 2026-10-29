package com.gtnewhorizons.angelica.dx12.device;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Gpu extends GpuCaps {
    {
        maxPerStageDescriptorSamplers = 16;
        minUniformBufferOffsetAlignment = 256;
        minStorageBufferOffsetAlignment = 16;
        maxImageDimension3D = 2048;
        maxImageArrayLayers = 2048;
        maxPerStageDescriptorSampledImages = 128;
        maxPerStageDescriptorUniformBuffers = 14;
        maxPerStageDescriptorStorageBuffers = 8;
        maxPerStageDescriptorStorageImages = 8;
        maxColorAttachments = 8;
        maxComputeWorkGroupInvocations = 1024;
        samplerAnisotropy = true;
        drawIndirectFirstInstance = true;
        drawIndirectCount = true;
        textureCompressionBC = true;
        imageCubeArray = true;
        maxImageDimension2D = 16384;
        maxSamplerAnisotropy = 16f;
        pointSizeMax = 511f;
    }

    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static final SymbolLookup D3D12 = Com.lookup("d3d12.dll");
    private static final SymbolLookup DXGI = Com.lookup("dxgi.dll");
    private static final MethodHandle CREATE_DEVICE = Com.downcall(D3D12, "D3D12CreateDevice", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle CREATE_FACTORY = Com.downcall(DXGI, "CreateDXGIFactory1", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));

    public long factory;
    public long adapter;
    public long device;
    public long queue;
    public String apiVersion = "";
    public int vendorId;
    public boolean unifiedMemory;
    public boolean gpuUploadHeap;

    public boolean independentBlend = true;
    public boolean dualSrcBlend = true;
    public boolean depthClamp = true;
    public boolean depth24Stencil8 = true;
    public boolean wideLines = false;
    public boolean samplerLodBias = true;
    public boolean multiDrawIndirect = true;
    public boolean fragmentStoresAndAtomics = true;
    public boolean vertexPipelineStoresAndAtomics = true;
    public boolean shaderClipDistance = true;
    public boolean triangleFans = false;
    public boolean indexTypeUint8 = false;
    public boolean provokingVertexLast = false;
    public boolean fillModeNonSolid = true;

    public int maxVertexInputAttributes = 32;
    public int maxBufferArguments = 14;
    public long recommendedWorkingSet;
    public long dedicatedVideoMemory;
    public int resourceBindingTier = 1;
    public int shaderModel = Dx.SHADER_MODEL_5_1;
    public long timestampFrequency;

    public int rtvIncrement, dsvIncrement, cbvSrvUavIncrement, samplerIncrement;

    private final Fence fence = new Fence();

    public boolean create() {
        if (com.gtnewhorizons.angelica.config.SystemProperties.DX12_DEBUG) DebugLayer.enable();
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            int hr = (int) CREATE_FACTORY.invokeExact(Dx.IID_DXGI_FACTORY2, out.address());
            if (!Com.ok(hr)) {
                LOG.error("CreateDXGIFactory1 failed: 0x{}", Integer.toHexString(hr));
                return false;
            }
            factory = out.get(JAVA_LONG, 0);
            if (!pickAdapterAndDevice(a)) {
                destroy();
                return false;
            }
            Com.check(Com.hr(device, Dx.DEV_CREATE_COMMAND_QUEUE, queueDesc(a).address(), Dx.IID_COMMAND_QUEUE, out.address()), "CreateCommandQueue");
            queue = out.get(JAVA_LONG, 0);
            if (com.gtnewhorizons.angelica.config.SystemProperties.DX12_DEBUG) DebugLayer.attach(device);
            fence.create(device);
            rtvIncrement = (int) Com.p(device, Dx.DEV_DESCRIPTOR_INCREMENT, Dx.DH_RTV);
            dsvIncrement = (int) Com.p(device, Dx.DEV_DESCRIPTOR_INCREMENT, Dx.DH_DSV);
            cbvSrvUavIncrement = (int) Com.p(device, Dx.DEV_DESCRIPTOR_INCREMENT, Dx.DH_CBV_SRV_UAV);
            samplerIncrement = (int) Com.p(device, Dx.DEV_DESCRIPTOR_INCREMENT, Dx.DH_SAMPLER);
            probeFeatures(a);
            recommendedWorkingSet = unifiedMemory ? Runtime.getRuntime().maxMemory() : dedicatedVideoMemory;
            final MemorySegment freq = a.allocate(8);
            if (Com.ok(Com.hr(queue, Dx.Q_TIMESTAMP_FREQUENCY, freq.address()))) timestampFrequency = freq.get(JAVA_LONG, 0);
            driverName = "Windows " + System.getProperty("os.version", "?");
            apiVersion = "12";
            LOG.info("Direct3D 12 device: {} (vendor 0x{}), UMA={}, BC={}, binding tier {}", deviceName, Integer.toHexString(vendorId), unifiedMemory, textureCompressionBC, resourceBindingTier);
            return true;
        } catch (Throwable t) {
            LOG.error("Direct3D 12 is unavailable: {}", t.toString());
            destroy();
            return false;
        }
    }

    private static MemorySegment queueDesc(Arena a) {
        final MemorySegment d = a.allocate(16);
        d.set(JAVA_INT, 0, Dx.LIST_DIRECT);
        return d;
    }

    private boolean pickAdapterAndDevice(Arena a) throws Throwable {
        final MemorySegment out = a.allocate(8), desc = a.allocate(312, 8);
        long best = 0L;
        long bestMemory = -1L;
        String bestName = "";
        int bestVendor = 0;
        for (int i = 0; ; i++) {
            final int hr = Com.hr(factory, Dx.FACTORY_ENUM_ADAPTERS1, i, out.address());
            if (!Com.ok(hr)) break;
            final long ad = out.get(JAVA_LONG, 0);
            Com.check(Com.hr(ad, Dx.ADAPTER1_GET_DESC1, desc.address()), "GetDesc1");
            final int flags = desc.get(JAVA_INT, 304);
            final String name = utf16(desc, 0, 128);
            final long vram = desc.get(JAVA_LONG, 272);
            final int vendor = desc.get(JAVA_INT, 256);
            final boolean software = (flags & 2) != 0;
            final boolean usable = !software && Com.ok((int) CREATE_DEVICE.invokeExact(ad, Dx.FEATURE_LEVEL_11_0, 0L, 0L));
            if (usable && vram > bestMemory) {
                if (best != 0L) Com.release(best);
                best = ad;
                bestMemory = vram;
                bestName = name;
                bestVendor = vendor;
            } else {
                Com.release(ad);
            }
        }
        if (best == 0L) {
            LOG.error("No hardware Direct3D 12 adapter found");
            return false;
        }
        adapter = best;
        deviceName = bestName;
        vendorId = bestVendor;
        dedicatedVideoMemory = bestMemory;
        final int hr = (int) CREATE_DEVICE.invokeExact(adapter, Dx.FEATURE_LEVEL_11_0, Dx.IID_DEVICE, out.address());
        if (!Com.ok(hr)) {
            LOG.error("D3D12CreateDevice failed: 0x{}", Integer.toHexString(hr));
            return false;
        }
        device = out.get(JAVA_LONG, 0);
        return true;
    }

    private void probeFeatures(Arena a) {
        final MemorySegment opts = a.allocate(64, 8);
        if (Com.ok(Com.hr(device, Dx.DEV_CHECK_FEATURE, Dx.FEATURE_OPTIONS, opts.address(), 60))) resourceBindingTier = opts.get(JAVA_INT, 16);
        final MemorySegment arch = a.allocate(16, 8);
        arch.set(JAVA_INT, 0, 0);
        if (Com.ok(Com.hr(device, Dx.DEV_CHECK_FEATURE, Dx.FEATURE_ARCHITECTURE, arch.address(), 16))) unifiedMemory = arch.get(JAVA_INT, 8) != 0;
        final MemorySegment sm = a.allocate(4, 4);
        sm.set(JAVA_INT, 0, Dx.SHADER_MODEL_6_0);
        if (Com.ok(Com.hr(device, Dx.DEV_CHECK_FEATURE, Dx.FEATURE_SHADER_MODEL, sm.address(), 4))) shaderModel = sm.get(JAVA_INT, 0);
        final MemorySegment opts16 = a.allocate(8, 4);
        if (Com.ok(Com.hr(device, Dx.DEV_CHECK_FEATURE, Dx.FEATURE_OPTIONS16, opts16.address(), 8))) gpuUploadHeap = opts16.get(JAVA_INT, 4) != 0;
        textureCompressionBC = formatSupported(a, 71);
        depth24Stencil8 = formatSupported(a, Dx.FMT_D24_UNORM_S8_UINT);
    }

    private boolean formatSupported(Arena a, int dxgiFormat) {
        final MemorySegment s = a.allocate(12, 4);
        s.set(JAVA_INT, 0, dxgiFormat);
        if (!Com.ok(Com.hr(device, Dx.DEV_CHECK_FEATURE, Dx.FEATURE_FORMAT_SUPPORT, s.address(), 12))) return false;
        return (s.get(JAVA_INT, 4) & Dx.FORMAT_SUPPORT1_TEXTURE2D) != 0;
    }

    private static String utf16(MemorySegment seg, long offset, int chars) {
        final byte[] bytes = new byte[chars * 2];
        MemorySegment.copy(seg, java.lang.foreign.ValueLayout.JAVA_BYTE, offset, bytes, 0, bytes.length);
        final String s = new String(bytes, StandardCharsets.UTF_16LE);
        final int nul = s.indexOf('\0');
        return nul < 0 ? s : s.substring(0, nul);
    }

    private final java.util.concurrent.atomic.AtomicLong allocated = new java.util.concurrent.atomic.AtomicLong();

    public long allocatedBytes() {
        return allocated.get();
    }

    public Fence fence() {
        return fence;
    }

    public boolean isNvidia() { return vendorId == 0x10DE; }
    public boolean isAmd() { return vendorId == 0x1002 || vendorId == 0x1022; }
    public boolean isIntel() { return vendorId == 0x8086; }
    public boolean isApple() { return false; }

    public String lowerName() { return deviceName.toLowerCase(Locale.ROOT); }

    public void destroy() {
        fence.destroy();
        queue = release0(queue);
        device = release0(device);
        adapter = release0(adapter);
        factory = release0(factory);
    }

    private static long release0(long obj) {
        Com.release(obj);
        return 0L;
    }
}

