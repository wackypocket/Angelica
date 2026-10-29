package com.gtnewhorizons.angelica.dx12.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Dx {
    private Dx() {}

    public static long guid(int d1, int d2, int d3, int... tail) {
        final MemorySegment g = Arena.global().allocate(16, 8);
        g.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, d1);
        g.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) d2);
        g.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) d3);
        for (int i = 0; i < 8; i++) g.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8 + i, (byte) tail[i]);
        return g.address();
    }

    public static final long IID_DEVICE = guid(0x189819f1, 0x1db6, 0x4b57, 0xbe, 0x54, 0x18, 0x21, 0x33, 0x9b, 0x85, 0xf7);
    public static final long IID_COMMAND_QUEUE = guid(0x0ec870a6, 0x5d7e, 0x4c22, 0x8c, 0xfc, 0x5b, 0xaa, 0xe0, 0x76, 0x16, 0xed);
    public static final long IID_COMMAND_ALLOCATOR = guid(0x6102dee4, 0xaf59, 0x4b09, 0xb9, 0x99, 0xb4, 0x4d, 0x73, 0xf0, 0x9b, 0x24);
    public static final long IID_COMMAND_LIST = guid(0x5b160d0f, 0xac1b, 0x4185, 0x8b, 0xa8, 0xb3, 0xae, 0x42, 0xa5, 0xa4, 0x55);
    public static final long IID_FENCE = guid(0x0a753dcf, 0xc4d8, 0x4b91, 0xad, 0xf6, 0xbe, 0x5a, 0x60, 0xd9, 0x5a, 0x76);
    public static final long IID_RESOURCE = guid(0x696442be, 0xa72e, 0x4059, 0xbc, 0x79, 0x5b, 0x5c, 0x98, 0x04, 0x0f, 0xad);
    public static final long IID_DESCRIPTOR_HEAP = guid(0x8efb471d, 0x616c, 0x4f49, 0x90, 0xf7, 0x12, 0x7b, 0xb7, 0x63, 0xfa, 0x51);
    public static final long IID_ROOT_SIGNATURE = guid(0xc54a6b66, 0x72df, 0x4ee8, 0x8b, 0xe5, 0xa9, 0x46, 0xa1, 0x42, 0x92, 0x14);
    public static final long IID_PIPELINE_STATE = guid(0x765a30f3, 0xf624, 0x4c6f, 0xa8, 0x28, 0xac, 0xe9, 0x48, 0x62, 0x24, 0x45);
    public static final long IID_COMMAND_SIGNATURE = guid(0xc36a797c, 0xec80, 0x4f0a, 0x89, 0x85, 0xa7, 0xb2, 0x47, 0x50, 0x82, 0xd1);
    public static final long IID_DXGI_FACTORY2 = guid(0x50c83a1c, 0xe072, 0x4c48, 0x87, 0xb0, 0x36, 0x30, 0xfa, 0x36, 0xa6, 0xd0);
    public static final long IID_DXGI_SWAPCHAIN3 = guid(0x94d99bdb, 0xf1f8, 0x4ab0, 0xb2, 0x36, 0x7d, 0xa0, 0x17, 0x0e, 0xda, 0xb1);

    public static final int FEATURE_LEVEL_11_0 = 0xb000;
    public static final int FEATURE_OPTIONS = 0, FEATURE_ARCHITECTURE = 1, FEATURE_FORMAT_SUPPORT = 3, FEATURE_SHADER_MODEL = 7, FEATURE_OPTIONS16 = 45;
    public static final int SHADER_MODEL_5_1 = 0x51, SHADER_MODEL_6_0 = 0x60;
    public static final int FORMAT_SUPPORT1_TEXTURE2D = 0x20;

    public static final int DEV_CREATE_COMMAND_QUEUE = 8, DEV_CREATE_COMMAND_ALLOCATOR = 9, DEV_CREATE_GRAPHICS_PSO = 10, DEV_CREATE_COMPUTE_PSO = 11;
    public static final int DEV_CREATE_COMMAND_LIST = 12, DEV_CHECK_FEATURE = 13, DEV_CREATE_DESCRIPTOR_HEAP = 14, DEV_DESCRIPTOR_INCREMENT = 15;
    public static final int DEV_CREATE_ROOT_SIGNATURE = 16, DEV_CREATE_CBV = 17, DEV_CREATE_SRV = 18, DEV_CREATE_UAV = 19, DEV_CREATE_RTV = 20, DEV_CREATE_DSV = 21;
    public static final int DEV_CREATE_SAMPLER = 22, DEV_COPY_DESCRIPTORS = 23, DEV_COPY_DESCRIPTORS_SIMPLE = 24, DEV_GET_ALLOCATION_INFO = 25;
    public static final int DEV_CREATE_COMMITTED_RESOURCE = 27, DEV_CREATE_FENCE = 36, DEV_REMOVED_REASON = 37, DEV_GET_COPYABLE_FOOTPRINTS = 38;
    public static final int DEV_CREATE_QUERY_HEAP = 39, DEV_CREATE_COMMAND_SIGNATURE = 41;

    public static final int Q_EXECUTE = 10, Q_SIGNAL = 14, Q_WAIT = 15, Q_TIMESTAMP_FREQUENCY = 16;

    public static final int ALLOCATOR_RESET = 8;

    public static final int FENCE_COMPLETED = 8, FENCE_SET_EVENT = 9, FENCE_SIGNAL = 10;

    public static final int RES_MAP = 8, RES_UNMAP = 9, RES_GET_DESC = 10, RES_GPU_VA = 11;

    public static final int HEAP_CPU_START = 9, HEAP_GPU_START = 10;

    public static final int CL_CLOSE = 9, CL_RESET = 10, CL_DRAW = 12, CL_DRAW_INDEXED = 13, CL_DISPATCH = 14, CL_COPY_BUFFER = 15, CL_COPY_TEXTURE = 16;
    public static final int CL_COPY_RESOURCE = 17, CL_RESOLVE = 19, CL_TOPOLOGY = 20, CL_VIEWPORTS = 21, CL_SCISSORS = 22, CL_BLEND_FACTOR = 23, CL_STENCIL_REF = 24;
    public static final int CL_SET_PSO = 25, CL_BARRIER = 26, CL_SET_HEAPS = 28, CL_CS_ROOT_SIG = 29, CL_GFX_ROOT_SIG = 30, CL_CS_ROOT_TABLE = 31, CL_GFX_ROOT_TABLE = 32;
    public static final int CL_CS_ROOT_32 = 33, CL_GFX_ROOT_32 = 34, CL_CS_ROOT_32S = 35, CL_GFX_ROOT_32S = 36, CL_CS_ROOT_CBV = 37, CL_GFX_ROOT_CBV = 38;
    public static final int CL_CS_ROOT_SRV = 39, CL_GFX_ROOT_SRV = 40, CL_CS_ROOT_UAV = 41, CL_GFX_ROOT_UAV = 42, CL_INDEX_BUFFER = 43, CL_VERTEX_BUFFERS = 44;
    public static final int CL_RENDER_TARGETS = 46, CL_CLEAR_DSV = 47, CL_CLEAR_RTV = 48, CL_CLEAR_UAV_UINT = 49, CL_CLEAR_UAV_FLOAT = 50, CL_DISCARD = 51;
    public static final int CL_END_QUERY = 53, CL_RESOLVE_QUERY = 54, CL_EXECUTE_INDIRECT = 59;

    public static final int FACTORY_ENUM_ADAPTERS1 = 12, FACTORY_MAKE_WINDOW_ASSOCIATION = 8, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND = 15;
    public static final int ADAPTER1_GET_DESC1 = 10;
    public static final int SC_PRESENT = 8, SC_GET_BUFFER = 9, SC_RESIZE_BUFFERS = 13, SC_SET_MAX_LATENCY = 31, SC_LATENCY_WAITABLE = 33, SC_CURRENT_INDEX = 36;

    public static final int HEAP_DEFAULT = 1, HEAP_UPLOAD = 2, HEAP_READBACK = 3, HEAP_GPU_UPLOAD = 5;
    public static final int DIM_BUFFER = 1, DIM_TEX1D = 2, DIM_TEX2D = 3, DIM_TEX3D = 4;
    public static final int RF_NONE = 0, RF_RENDER_TARGET = 1, RF_DEPTH_STENCIL = 2, RF_UNORDERED_ACCESS = 4, RF_DENY_SHADER_RESOURCE = 8;
    public static final int STATE_COMMON = 0, STATE_VERTEX_AND_CONSTANT = 1, STATE_INDEX_BUFFER = 2, STATE_RENDER_TARGET = 4, STATE_UNORDERED_ACCESS = 8;
    public static final int STATE_DEPTH_WRITE = 0x10, STATE_DEPTH_READ = 0x20, STATE_NON_PIXEL_SRV = 0x40, STATE_PIXEL_SRV = 0x80, STATE_INDIRECT_ARGUMENT = 0x200;
    public static final int STATE_COPY_DEST = 0x400, STATE_COPY_SOURCE = 0x800, STATE_GENERIC_READ = 0xAC3, STATE_PRESENT = 0;
    public static final int LIST_DIRECT = 0, LIST_COPY = 3;
    public static final int DH_CBV_SRV_UAV = 0, DH_SAMPLER = 1, DH_RTV = 2, DH_DSV = 3;
    public static final int DH_SHADER_VISIBLE = 1;
    public static final int BARRIER_TRANSITION = 0, BARRIER_UAV = 2;

    public static final int FMT_UNKNOWN = 0, FMT_R8G8B8A8_UNORM = 28, FMT_B8G8R8A8_UNORM = 87, FMT_D32_FLOAT = 40, FMT_D24_UNORM_S8_UINT = 45, FMT_R32_FLOAT = 41;

    public static final long ALL_SUBRESOURCES = 0xFFFFFFFFL;
    public static final long NULL = 0L;

    public static long u64(long address, long offset) {
        return Com.RAW.get(JAVA_LONG, address + offset);
    }
}
