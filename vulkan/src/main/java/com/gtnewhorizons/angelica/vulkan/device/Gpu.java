package com.gtnewhorizons.angelica.vulkan.device;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.EXTIndexTypeUint8;
import org.lwjgl.vulkan.EXTLayerSettings;
import org.lwjgl.vulkan.EXTLineRasterization;
import org.lwjgl.vulkan.EXTPresentModeFifoLatestReady;
import org.lwjgl.vulkan.EXTProvokingVertex;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.KHRPortabilitySubset;
import org.lwjgl.vulkan.KHRPresentId;
import org.lwjgl.vulkan.KHRPresentWait;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDevicePresentModeFifoLatestReadyFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePresentWaitFeaturesKHR;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceIndexTypeUint8FeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDevicePortabilitySubsetFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceLineRasterizationFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProvokingVertexFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

public final class Gpu extends GpuCaps {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private static final int MIN_API = VK_API_VERSION_1_2;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    public VkInstance instance;
    public VkPhysicalDevice physical;
    public VkDevice device;
    public VkQueue queue;
    public int queueFamily;
    public int apiVersion;
    private int instanceApiVersion;
    public String driverInfo = "";
    public int vendorId;
    public int driverVersion;
    public int deviceType;

    private long debugMessenger;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private boolean libraryLoaded;

    public boolean dynamicRenderingCore;
    public boolean provokingVertexLast;
    public boolean bresenhamLines;
    public boolean pushDescriptors;
    public boolean indexTypeUint8;
    public boolean portabilitySubset;

    public boolean presentWait;
    public boolean fifoLatestReady;
    public boolean triangleFans = true;
    public boolean debugUtils;
    public boolean fillModeNonSolid;
    public boolean wideLines;
    public boolean depthClamp;
    public boolean depthBiasClamp;
    public boolean independentBlend;
    public boolean dualSrcBlend;
    public boolean logicOp;
    public boolean multiDrawIndirect;
    public boolean largePoints;
    public boolean fragmentStoresAndAtomics;
    public boolean vertexPipelineStoresAndAtomics;
    public boolean shaderClipDistance;
    public boolean samplerMirrorClampToEdge;
    public boolean shaderStorageImageExtendedFormats;
    public boolean occlusionQueryPrecise;
    public boolean hostQueryReset;
    public float timestampPeriod;
    public boolean timestampsSupported;

    public long optimalBufferCopyOffsetAlignment;
    public long nonCoherentAtomSize;
    public long bufferImageGranularity;
    public int maxPushDescriptors;
    public int maxBoundDescriptorSets;
    public int maxDescriptorSetUniformBuffersDynamic;
    public int maxPushConstantsSize;
    public int maxVertexInputAttributes;
    public int maxVertexInputBindings;
    public long maxMemoryAllocationCount;

    public VkPhysicalDeviceMemoryProperties memoryProperties;

    public boolean create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (Platform.get() == Platform.MACOSX) MoltenVkLoader.pointSdlAtMoltenVk();
            if (!SDLVulkan.SDL_Vulkan_LoadLibrary((CharSequence) null)) {
                LOG.error("SDL could not load a Vulkan loader: {}", SDLError.SDL_GetError());
                return false;
            }
            libraryLoaded = true;
            final long gipa = SDLVulkan.SDL_Vulkan_GetVkGetInstanceProcAddr();
            if (gipa == 0L) {
                LOG.error("SDL_Vulkan_GetVkGetInstanceProcAddr returned null: {}", SDLError.SDL_GetError());
                return false;
            }
            final FunctionProvider loader = new FunctionProvider() {
                @Override public long getFunctionAddress(ByteBuffer functionName) {
                    if ("vkGetInstanceProcAddr".equals(MemoryUtil.memASCII(MemoryUtil.memAddress(functionName)))) return gipa;
                    return JNI.invokePPP(0L, MemoryUtil.memAddress(functionName), gipa);
                }
            };
            Configuration.VULKAN_EXPLICIT_INIT.set(true);
            VK.destroy();
            VK.create(loader);
            if (!createInstance(stack)) return false;
            if (!pickPhysicalDevice(stack)) return false;
            return createDevice(stack);
        } catch (Throwable t) {
            LOG.error("Vulkan bring-up failed", t);
            destroy();
            return false;
        }
    }

    private boolean createInstance(MemoryStack stack) {
        final int loaderVersion = VK.getInstanceVersionSupported();
        if (loaderVersion < MIN_API) {
            LOG.error("The Vulkan loader supports {}, the backend needs 1.2", versionString(loaderVersion));
            return false;
        }
        final Set<String> available = instanceExtensions();
        final List<String> extensions = new ArrayList<>();
        final PointerBuffer sdlExtensions = SDLVulkan.SDL_Vulkan_GetInstanceExtensions();
        if (sdlExtensions == null) {
            LOG.error("SDL_Vulkan_GetInstanceExtensions failed: {}", SDLError.SDL_GetError());
            return false;
        }
        for (int i = sdlExtensions.position(); i < sdlExtensions.limit(); i++) extensions.add(sdlExtensions.getStringUTF8(i));
        int flags = 0;
        if (available.contains(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
            extensions.add(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            flags |= KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
        }
        if (available.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            extensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            debugUtils = true;
        }
        final boolean moltenVkSettings = Platform.get() == Platform.MACOSX && available.contains(EXTLayerSettings.VK_EXT_LAYER_SETTINGS_EXTENSION_NAME);
        if (moltenVkSettings) extensions.add(EXTLayerSettings.VK_EXT_LAYER_SETTINGS_EXTENSION_NAME);
        final List<String> layers = new ArrayList<>();
        if (SystemProperties.VULKAN_DEBUG) {
            if (instanceLayers().contains(VALIDATION_LAYER)) {
                layers.add(VALIDATION_LAYER);
            } else {
                LOG.warn("angelica.vulkan.debug is set but {} is not installed", VALIDATION_LAYER);
            }
        }

        instanceApiVersion = Math.min(loaderVersion, VK_API_VERSION_1_3);
        final VkApplicationInfo app = VkApplicationInfo.calloc(stack)
            .sType$Default()
            .pApplicationName(stack.UTF8("Minecraft"))
            .applicationVersion(VK_MAKE_VERSION(1, 7, 10))
            .pEngineName(stack.UTF8("Angelica"))
            .engineVersion(1)
            .apiVersion(instanceApiVersion);

        final PointerBuffer extNames = heapStrings(extensions);
        final PointerBuffer layerNames = layers.isEmpty() ? null : heapStrings(layers);
        try {
            final VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType$Default()
                .flags(flags)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(extNames)
                .ppEnabledLayerNames(layerNames);
            VkDebugUtilsMessengerCreateInfoEXT messengerInfo = null;
            if (debugUtils && !layers.isEmpty()) {
                messengerInfo = messengerInfo(stack);
                ci.pNext(messengerInfo.address());
            }
            if (moltenVkSettings) ci.pNext(MoltenVkSettings.build(stack).pNext(ci.pNext()).address());
            final PointerBuffer pInstance = stack.mallocPointer(1);
            final int res = vkCreateInstance(ci, null, pInstance);
            if (res != VK_SUCCESS) {
                LOG.error("vkCreateInstance failed: {}", VkResults.name(res));
                return false;
            }
            instance = new VkInstance(pInstance.get(0), ci);
            if (messengerInfo != null) {
                final LongBuffer pMessenger = stack.mallocLong(1);
                if (EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, messengerInfo, null, pMessenger) == VK_SUCCESS) {
                    debugMessenger = pMessenger.get(0);
                }
            }
        } finally {
            freeStrings(extNames);
            freeStrings(layerNames);
        }
        LOG.info("Vulkan instance created (loader {}, extensions {}, layers {})", versionString(loaderVersion), extensions, layers);
        return true;
    }

    private VkDebugUtilsMessengerCreateInfoEXT messengerInfo(MemoryStack stack) {
        if (debugCallback == null) {
            debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, pCallbackData, pUserData) -> {
                final VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                final String msg = data.pMessageString();
                if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                    LOG.error("[VK] {}", msg, new Throwable("validation"));
                } else if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                    LOG.warn("[VK] {}", msg);
                } else {
                    LOG.debug("[VK] {}", msg);
                }
                return VK_FALSE;
            });
        }
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
            .sType$Default()
            .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
            .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
            .pfnUserCallback(debugCallback);
    }

    private record Candidate(VkPhysicalDevice device, String name, int type, int api, int index) {}

    private boolean pickPhysicalDevice(MemoryStack stack) {
        final IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);
        if (count.get(0) == 0) {
            LOG.error("No Vulkan physical devices");
            return false;
        }
        final PointerBuffer handles = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, handles);
        final List<Candidate> candidates = new ArrayList<>();
        final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        for (int i = 0; i < handles.capacity(); i++) {
            final VkPhysicalDevice pd = new VkPhysicalDevice(handles.get(i), instance);
            vkGetPhysicalDeviceProperties(pd, props);
            final String name = props.deviceNameString();
            final boolean usable = props.apiVersion() >= MIN_API && graphicsQueueFamily(pd, stack) >= 0 && deviceExtensions(pd).contains(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            LOG.info("Vulkan device {}: {} (type {}, api {}){}", i, name, props.deviceType(), versionString(props.apiVersion()), usable ? "" : " - unusable");
            if (usable) candidates.add(new Candidate(pd, name, props.deviceType(), props.apiVersion(), i));
        }
        if (candidates.isEmpty()) {
            LOG.error("No Vulkan 1.2 device with a graphics queue and swapchain support");
            return false;
        }
        Candidate chosen = null;
        final String requested = SystemProperties.VULKAN_DEVICE;
        if (!requested.isEmpty()) {
            for (Candidate c : candidates) {
                if (requested.equals(Integer.toString(c.index)) || c.name.toLowerCase(Locale.ROOT).contains(requested.toLowerCase(Locale.ROOT))) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) LOG.warn("angelica.vulkan.device={} matched no usable device", requested);
        }
        if (chosen == null) {
            for (Candidate c : candidates) {
                if (chosen == null || typeRank(c.type) > typeRank(chosen.type)) chosen = c;
            }
        }
        physical = chosen.device;
        return true;
    }

    private static int typeRank(int type) {
        return switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 4;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> 0;
            default -> 1;
        };
    }

    private static int graphicsQueueFamily(VkPhysicalDevice pd, MemoryStack stack) {
        final IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
        final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        return -1;
    }

    private boolean createDevice(MemoryStack stack) {
        final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(physical, props);
        apiVersion = Math.min(props.apiVersion(), instanceApiVersion);
        deviceName = props.deviceNameString();
        vendorId = props.vendorID();
        driverVersion = props.driverVersion();
        deviceType = props.deviceType();
        queueFamily = graphicsQueueFamily(physical, stack);

        final Set<String> exts = deviceExtensions(physical);
        final List<String> enable = new ArrayList<>();
        enable.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);

        dynamicRenderingCore = apiVersion >= VK_API_VERSION_1_3;
        if (!dynamicRenderingCore) {
            if (!exts.contains(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)) {
                LOG.error("{} lacks dynamic rendering (Vulkan 1.3 or VK_KHR_dynamic_rendering)", deviceName);
                return false;
            }
            enable.add(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
            if (apiVersion < VK_API_VERSION_1_2) enable.add("VK_KHR_depth_stencil_resolve");
        }
        final boolean hasProvoking = exts.contains(EXTProvokingVertex.VK_EXT_PROVOKING_VERTEX_EXTENSION_NAME);
        if (hasProvoking) enable.add(EXTProvokingVertex.VK_EXT_PROVOKING_VERTEX_EXTENSION_NAME);
        final boolean hasLineRaster = exts.contains(EXTLineRasterization.VK_EXT_LINE_RASTERIZATION_EXTENSION_NAME);
        if (hasLineRaster) enable.add(EXTLineRasterization.VK_EXT_LINE_RASTERIZATION_EXTENSION_NAME);
        pushDescriptors = exts.contains(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
        if (pushDescriptors) enable.add(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
        final boolean hasUint8 = exts.contains(EXTIndexTypeUint8.VK_EXT_INDEX_TYPE_UINT8_EXTENSION_NAME);
        if (hasUint8) enable.add(EXTIndexTypeUint8.VK_EXT_INDEX_TYPE_UINT8_EXTENSION_NAME);
        final boolean hasPresentWait = exts.contains(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME) && exts.contains(KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME);
        final boolean hasLatestReady = exts.contains(EXTPresentModeFifoLatestReady.VK_EXT_PRESENT_MODE_FIFO_LATEST_READY_EXTENSION_NAME);
        portabilitySubset = exts.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
        if (portabilitySubset) enable.add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);

        final VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
        final VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        final VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
        final VkPhysicalDeviceDynamicRenderingFeaturesKHR fDyn = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default();
        final VkPhysicalDeviceProvokingVertexFeaturesEXT fProv = VkPhysicalDeviceProvokingVertexFeaturesEXT.calloc(stack).sType$Default();
        final VkPhysicalDeviceLineRasterizationFeaturesEXT fLine = VkPhysicalDeviceLineRasterizationFeaturesEXT.calloc(stack).sType$Default();
        final VkPhysicalDeviceIndexTypeUint8FeaturesEXT fU8 = VkPhysicalDeviceIndexTypeUint8FeaturesEXT.calloc(stack).sType$Default();
        final VkPhysicalDevicePresentIdFeaturesKHR fPid = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack).sType$Default();
        final VkPhysicalDevicePresentWaitFeaturesKHR fPw = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack).sType$Default();
        final VkPhysicalDevicePresentModeFifoLatestReadyFeaturesEXT fLr = VkPhysicalDevicePresentModeFifoLatestReadyFeaturesEXT.calloc(stack).sType$Default();
        final VkPhysicalDevicePortabilitySubsetFeaturesKHR fPort = VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(stack).sType$Default();
        final VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        query.pNext(f11.address());
        f11.pNext(f12.address());
        long tail = f12.address();
        tail = link(tail, dynamicRenderingCore ? f13.address() : fDyn.address());
        if (hasProvoking) tail = link(tail, fProv.address());
        if (hasLineRaster) tail = link(tail, fLine.address());
        if (hasUint8) tail = link(tail, fU8.address());
        if (hasPresentWait) {
            tail = link(tail, fPid.address());
            tail = link(tail, fPw.address());
        }
        if (hasLatestReady) tail = link(tail, fLr.address());
        if (portabilitySubset) link(tail, fPort.address());
        vkGetPhysicalDeviceFeatures2(physical, query);

        final VkPhysicalDeviceFeatures have = query.features();
        final VkPhysicalDeviceFeatures2 enableChain = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        final VkPhysicalDeviceFeatures on = enableChain.features();
        on.samplerAnisotropy(samplerAnisotropy = have.samplerAnisotropy());
        on.fillModeNonSolid(fillModeNonSolid = have.fillModeNonSolid());
        on.wideLines(wideLines = have.wideLines());
        on.depthClamp(depthClamp = have.depthClamp());
        on.depthBiasClamp(depthBiasClamp = have.depthBiasClamp());
        on.independentBlend(independentBlend = have.independentBlend());
        on.dualSrcBlend(dualSrcBlend = have.dualSrcBlend());
        on.logicOp(logicOp = have.logicOp());
        on.multiDrawIndirect(multiDrawIndirect = have.multiDrawIndirect());
        on.drawIndirectFirstInstance(drawIndirectFirstInstance = have.drawIndirectFirstInstance());
        on.geometryShader(geometryShader = have.geometryShader());
        on.tessellationShader(tessellationShader = have.tessellationShader());
        on.textureCompressionBC(textureCompressionBC = have.textureCompressionBC());
        on.largePoints(largePoints = have.largePoints());
        on.fragmentStoresAndAtomics(fragmentStoresAndAtomics = have.fragmentStoresAndAtomics());
        on.vertexPipelineStoresAndAtomics(vertexPipelineStoresAndAtomics = have.vertexPipelineStoresAndAtomics());
        on.shaderClipDistance(shaderClipDistance = have.shaderClipDistance());
        on.imageCubeArray(imageCubeArray = have.imageCubeArray());
        on.shaderStorageImageExtendedFormats(shaderStorageImageExtendedFormats = have.shaderStorageImageExtendedFormats());
        on.occlusionQueryPrecise(occlusionQueryPrecise = have.occlusionQueryPrecise());
        on.shaderImageGatherExtended(have.shaderImageGatherExtended());
        on.shaderStorageImageWriteWithoutFormat(have.shaderStorageImageWriteWithoutFormat());
        on.shaderStorageImageReadWithoutFormat(have.shaderStorageImageReadWithoutFormat());
        on.fullDrawIndexUint32(have.fullDrawIndexUint32());
        on.shaderInt64(have.shaderInt64());
        on.shaderFloat64(have.shaderFloat64());
        on.shaderCullDistance(have.shaderCullDistance());
        on.sampleRateShading(have.sampleRateShading());

        final VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
        e11.shaderDrawParameters(shaderDrawParameters = f11.shaderDrawParameters());
        final VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        e12.drawIndirectCount(drawIndirectCount = f12.drawIndirectCount());
        e12.samplerMirrorClampToEdge(samplerMirrorClampToEdge = f12.samplerMirrorClampToEdge());
        e12.hostQueryReset(hostQueryReset = f12.hostQueryReset());
        e12.scalarBlockLayout(f12.scalarBlockLayout());
        e12.uniformBufferStandardLayout(f12.uniformBufferStandardLayout());
        e12.shaderOutputLayer(f12.shaderOutputLayer());
        e12.shaderOutputViewportIndex(f12.shaderOutputViewportIndex());
        e12.separateDepthStencilLayouts(f12.separateDepthStencilLayouts());
        e12.storageBuffer8BitAccess(f12.storageBuffer8BitAccess());
        e12.shaderInt8(f12.shaderInt8());
        enableChain.pNext(e11.address());
        e11.pNext(e12.address());
        tail = e12.address();
        if (dynamicRenderingCore) {
            final VkPhysicalDeviceVulkan13Features e13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            if (!f13.dynamicRendering()) {
                LOG.error("{} reports Vulkan 1.3 without dynamic rendering", deviceName);
                return false;
            }
            e13.dynamicRendering(true);
            e13.maintenance4(f13.maintenance4());
            e13.shaderDemoteToHelperInvocation(f13.shaderDemoteToHelperInvocation());
            tail = link(tail, e13.address());
        } else {
            if (!fDyn.dynamicRendering()) {
                LOG.error("{} exposes VK_KHR_dynamic_rendering without the feature", deviceName);
                return false;
            }
            final VkPhysicalDeviceDynamicRenderingFeaturesKHR eDyn = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default().dynamicRendering(true);
            tail = link(tail, eDyn.address());
        }
        if (hasProvoking && fProv.provokingVertexLast()) {
            provokingVertexLast = true;
            final VkPhysicalDeviceProvokingVertexFeaturesEXT eProv = VkPhysicalDeviceProvokingVertexFeaturesEXT.calloc(stack).sType$Default().provokingVertexLast(true);
            tail = link(tail, eProv.address());
        }
        if (hasLineRaster && fLine.bresenhamLines()) {
            bresenhamLines = true;
            final VkPhysicalDeviceLineRasterizationFeaturesEXT eLine = VkPhysicalDeviceLineRasterizationFeaturesEXT.calloc(stack).sType$Default().bresenhamLines(true);
            tail = link(tail, eLine.address());
        }
        if (hasUint8 && fU8.indexTypeUint8()) {
            indexTypeUint8 = true;
            final VkPhysicalDeviceIndexTypeUint8FeaturesEXT eU8 = VkPhysicalDeviceIndexTypeUint8FeaturesEXT.calloc(stack).sType$Default().indexTypeUint8(true);
            tail = link(tail, eU8.address());
        }
        if (hasPresentWait && fPid.presentId() && fPw.presentWait() && !SystemProperties.VULKAN_NO_PRESENT_WAIT) {
            presentWait = true;
            enable.add(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME);
            enable.add(KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME);
            tail = link(tail, VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack).sType$Default().presentId(true).address());
            tail = link(tail, VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack).sType$Default().presentWait(true).address());
        }
        if (hasLatestReady && fLr.presentModeFifoLatestReady()) {
            fifoLatestReady = true;
            enable.add(EXTPresentModeFifoLatestReady.VK_EXT_PRESENT_MODE_FIFO_LATEST_READY_EXTENSION_NAME);
            tail = link(tail, VkPhysicalDevicePresentModeFifoLatestReadyFeaturesEXT.calloc(stack).sType$Default().presentModeFifoLatestReady(true).address());
        }
        if (portabilitySubset) {
            triangleFans = fPort.triangleFans();
            link(tail, fPort.address());
        }

        final VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack)
            .sType$Default()
            .queueFamilyIndex(queueFamily)
            .pQueuePriorities(stack.floats(1.0f));
        final PointerBuffer extNames = heapStrings(enable);
        try {
            final VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(enableChain.address())
                .pQueueCreateInfos(queues)
                .ppEnabledExtensionNames(extNames);
            final PointerBuffer pDevice = stack.mallocPointer(1);
            final int res = vkCreateDevice(physical, ci, null, pDevice);
            if (res != VK_SUCCESS) {
                LOG.error("vkCreateDevice failed: {}", VkResults.name(res));
                return false;
            }
            device = new VkDevice(pDevice.get(0), physical, ci, apiVersion);
        } finally {
            freeStrings(extNames);
        }
        final PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, pQueue);
        queue = new VkQueue(pQueue.get(0), device);

        readLimits(props.limits(), stack);
        memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(physical, memoryProperties);
        driverName = DriverInfo.describe(physical, apiVersion, stack);
        LOG.info("Vulkan device: {} ({}), api {}, extensions {}", deviceName, driverName, versionString(apiVersion), enable);
        LOG.info("Vulkan features: presentWait={} fifoLatestReady={} provokingLast={} pushDescriptors={} uint8Indices={} portability={} fans={} wideLines={} logicOp={} dualSrc={} geometry={} drawParams={} mdi={} bresenhamLines={}",
            presentWait, fifoLatestReady, provokingVertexLast, pushDescriptors, indexTypeUint8, portabilitySubset, triangleFans, wideLines, logicOp, dualSrcBlend, geometryShader, shaderDrawParameters, multiDrawIndirect, bresenhamLines);
        return true;
    }

    private void readLimits(VkPhysicalDeviceLimits limits, MemoryStack stack) {
        minUniformBufferOffsetAlignment = limits.minUniformBufferOffsetAlignment();
        minStorageBufferOffsetAlignment = limits.minStorageBufferOffsetAlignment();
        optimalBufferCopyOffsetAlignment = Math.max(4, limits.optimalBufferCopyOffsetAlignment());
        nonCoherentAtomSize = limits.nonCoherentAtomSize();
        bufferImageGranularity = limits.bufferImageGranularity();
        maxImageDimension2D = limits.maxImageDimension2D();
        maxImageDimension3D = limits.maxImageDimension3D();
        maxImageArrayLayers = limits.maxImageArrayLayers();
        maxSamplerAnisotropy = samplerAnisotropy ? limits.maxSamplerAnisotropy() : 1f;
        maxBoundDescriptorSets = limits.maxBoundDescriptorSets();
        maxPerStageDescriptorSamplers = limits.maxPerStageDescriptorSamplers();
        maxPerStageDescriptorSampledImages = limits.maxPerStageDescriptorSampledImages();
        maxPerStageDescriptorUniformBuffers = limits.maxPerStageDescriptorUniformBuffers();
        maxPerStageDescriptorStorageBuffers = limits.maxPerStageDescriptorStorageBuffers();
        maxPerStageDescriptorStorageImages = limits.maxPerStageDescriptorStorageImages();
        maxDescriptorSetUniformBuffersDynamic = limits.maxDescriptorSetUniformBuffersDynamic();
        maxPushConstantsSize = limits.maxPushConstantsSize();
        maxVertexInputAttributes = limits.maxVertexInputAttributes();
        maxVertexInputBindings = limits.maxVertexInputBindings();
        maxColorAttachments = limits.maxColorAttachments();
        maxComputeWorkGroupInvocations = limits.maxComputeWorkGroupInvocations();
        lineWidthMin = limits.lineWidthRange(0);
        lineWidthMax = wideLines ? limits.lineWidthRange(1) : 1f;
        pointSizeMax = largePoints ? limits.pointSizeRange(1) : 1f;
        maxMemoryAllocationCount = Integer.toUnsignedLong(limits.maxMemoryAllocationCount());
        timestampPeriod = limits.timestampPeriod();
        timestampsSupported = limits.timestampComputeAndGraphics();
        maxPushDescriptors = pushDescriptors ? DriverInfo.maxPushDescriptors(physical, stack) : 0;
    }

    private static long link(long tail, long next) {
        MemoryUtil.memPutAddress(tail + 8, next);
        return next;
    }

    private Set<String> instanceExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null);
            final Set<String> out = new HashSet<>();
            final VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, props);
                for (VkExtensionProperties p : props) out.add(p.extensionNameString());
            } finally {
                props.free();
            }
            return out;
        }
    }

    private Set<String> instanceLayers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(count, null);
            final Set<String> out = new HashSet<>();
            final VkLayerProperties.Buffer props = VkLayerProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceLayerProperties(count, props);
                for (VkLayerProperties p : props) out.add(p.layerNameString());
            } finally {
                props.free();
            }
            return out;
        }
    }

    public static Set<String> deviceExtensions(VkPhysicalDevice pd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer count = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, null);
            final Set<String> out = new HashSet<>();
            final VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, props);
                for (VkExtensionProperties p : props) out.add(p.extensionNameString());
            } finally {
                props.free();
            }
            return out;
        }
    }

    private static PointerBuffer heapStrings(List<String> strings) {
        final PointerBuffer out = MemoryUtil.memAllocPointer(strings.size());
        for (String s : strings) out.put(MemoryUtil.memUTF8(s));
        return out.flip();
    }

    private static void freeStrings(PointerBuffer strings) {
        if (strings == null) return;
        for (int i = 0; i < strings.limit(); i++) MemoryUtil.nmemFree(strings.get(i));
        MemoryUtil.memFree(strings);
    }

    public static String versionString(int v) {
        return VK_API_VERSION_MAJOR(v) + "." + VK_API_VERSION_MINOR(v) + "." + VK_API_VERSION_PATCH(v);
    }

    public boolean isNvidia() { return vendorId == 0x10DE; }
    public boolean isAmd() { return vendorId == 0x1002; }
    public boolean isIntel() { return vendorId == 0x8086; }
    public boolean isApple() { return vendorId == 0x106B || portabilitySubset; }

    public void waitIdle() {
        if (device != null) vkDeviceWaitIdle(device);
    }

    public void destroy() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            if (debugMessenger != 0L) {
                EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
                debugMessenger = 0L;
            }
            vkDestroyInstance(instance, null);
            instance = null;
        }
        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }
        if (memoryProperties != null) {
            memoryProperties.free();
            memoryProperties = null;
        }
        if (libraryLoaded) {
            SDLVulkan.SDL_Vulkan_UnloadLibrary();
            libraryLoaded = false;
        }
    }

    public boolean supportsPresent(long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer supported = stack.mallocInt(1);
            KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(physical, queueFamily, surface, supported);
            return supported.get(0) == VK_TRUE;
        }
    }
}
