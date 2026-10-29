package com.gtnewhorizons.angelica.dx12.device;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.dx12.frame.CommandStream;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class SwapChain {
    private static final Tracy.ZoneId Z_DX12_PRESENT = Tracy.zoneId("dx12Present", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    public static final int FORMAT = Gfx.PF_BGRA8_UNORM;

    private static final java.lang.foreign.SymbolLookup DXGI = Com.lookup("dxgi.dll");

    private final Gpu gpu;
    private final CommandStream stream;
    private final long hwnd;
    private final int bufferCount;
    private long swapChain;
    private final long[] buffers;
    public int width, height;
    private VSyncMode vsync = VSyncMode.ON;
    private int currentIndex;

    private static final int SWAP_CHAIN_ALLOW_TEARING = 0x800, PRESENT_ALLOW_TEARING = 0x200;
    private static final int FACTORY5_CHECK_FEATURE_SUPPORT = 28, FEATURE_PRESENT_ALLOW_TEARING = 0;
    private static final long IID_DXGI_FACTORY5 = Dx.guid(0x7632e1f5, 0xee65, 0x4dca, 0x87, 0xfd, 0x84, 0xcd, 0x75, 0xf8, 0x83, 0x8d);

    public final boolean tearing;

    private int swapFlags() {
        return tearing ? SWAP_CHAIN_ALLOW_TEARING : 0;
    }

    private static boolean tearingSupported(Gpu gpu) {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            if (Com.hr(gpu.factory, 0, IID_DXGI_FACTORY5, out.address()) < 0) return false;
            final long factory5 = out.get(JAVA_LONG, 0);
            try {
                final MemorySegment allow = a.allocate(4);
                return Com.hr(factory5, FACTORY5_CHECK_FEATURE_SUPPORT, FEATURE_PRESENT_ALLOW_TEARING, allow.address(), 4) >= 0 && allow.get(JAVA_INT, 0) != 0;
            } finally {
                Com.release(factory5);
            }
        }
    }

    public SwapChain(Gpu gpu, CommandStream stream, long hwnd, int framesInFlight) {
        this.gpu = gpu;
        this.stream = stream;
        this.hwnd = hwnd;
        this.bufferCount = Math.max(2, Math.min(3, framesInFlight + 1));
        this.buffers = new long[bufferCount];
        this.tearing = tearingSupported(gpu);
    }

    public boolean supports(VSyncMode mode) {
        return mode == VSyncMode.OFF || mode == VSyncMode.ON;
    }

    public VSyncMode setVSync(VSyncMode preferred) {
        vsync = preferred == VSyncMode.OFF ? VSyncMode.OFF : VSyncMode.ON;
        return vsync;
    }

    public VSyncMode vsyncMode() {
        return vsync;
    }

    public boolean ensure(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        if (swapChain == 0L) {
            create(w, h);
        } else if (w != width || h != height) {
            resize(w, h);
        }
        return swapChain != 0L;
    }

    private void create(int w, int h) {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment desc = a.allocate(48, 8);
            desc.set(JAVA_INT, 0, w);
            desc.set(JAVA_INT, 4, h);
            desc.set(JAVA_INT, 8, Dxgi.view(FORMAT));
            desc.set(JAVA_INT, 16, 1);
            desc.set(JAVA_INT, 24, 0x20);
            desc.set(JAVA_INT, 28, bufferCount);
            desc.set(JAVA_INT, 36, 4);
            desc.set(JAVA_INT, 44, swapFlags());
            final MemorySegment out = a.allocate(8);
            Com.check(Com.hr(gpu.factory, Dx.FACTORY2_CREATE_SWAPCHAIN_FOR_HWND, gpu.queue, hwnd, desc.address(), 0L, 0L, out.address()), "CreateSwapChainForHwnd");
            final long sc1 = out.get(JAVA_LONG, 0);
            Com.check(Com.hr(sc1, 0, Dx.IID_DXGI_SWAPCHAIN3, out.address()), "QueryInterface(IDXGISwapChain3)");
            swapChain = out.get(JAVA_LONG, 0);
            Com.release(sc1);
            Com.hr(gpu.factory, Dx.FACTORY_MAKE_WINDOW_ASSOCIATION, hwnd, 2);
            width = w;
            height = h;
            fetchBuffers();
        }
    }

    private void fetchBuffers() {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            for (int i = 0; i < bufferCount; i++) {
                Com.check(Com.hr(swapChain, Dx.SC_GET_BUFFER, i, Dx.IID_RESOURCE, out.address()), "GetBuffer");
                buffers[i] = out.get(JAVA_LONG, 0);
            }
        }
    }

    private void resize(int w, int h) {
        stream.finish();
        for (int i = 0; i < bufferCount; i++) {
            Com.release(buffers[i]);
            buffers[i] = 0L;
        }
        Com.check(Com.hr(swapChain, Dx.SC_RESIZE_BUFFERS, bufferCount, w, h, Dxgi.UNKNOWN, swapFlags()), "ResizeBuffers");
        width = w;
        height = h;
        fetchBuffers();
        LOG.info("Swap chain resized to {}x{}", w, h);
    }

    public long backBuffer() {
        currentIndex = (int) Com.p(swapChain, Dx.SC_CURRENT_INDEX);
        return buffers[currentIndex];
    }

    public void present() {
        final boolean off = vsync == VSyncMode.OFF;
        Tracy.beginZone(Z_DX12_PRESENT);
        final int hr;
        try {
            hr = Com.hr(swapChain, Dx.SC_PRESENT, off ? 0 : 1, off && tearing ? PRESENT_ALLOW_TEARING : 0);
        } finally {
            Tracy.endZone();
        }
        if (hr < 0) LOG.error("Present failed: 0x{} (device removed reason 0x{})", Integer.toHexString(hr), Integer.toHexString(Com.hr(gpu.device, Dx.DEV_REMOVED_REASON)));
    }

    public void destroy() {
        for (int i = 0; i < bufferCount; i++) {
            Com.release(buffers[i]);
            buffers[i] = 0L;
        }
        Com.release(swapChain);
        swapChain = 0L;
    }
}
