package com.gtnewhorizons.angelica.dx12.device;

import com.gtnewhorizons.angelica.dx12.frame.CommandStream;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Descriptors {
    private static final int CHUNK = 4096;
    private static final int VIEW_RING = 1 << 19;
    private static final int RING_BLOCK = 2048;
    private static final int SAMPLER_HEAP = 2048;
    private static final int RTV_RING = 128, DSV_RING = 64;

    private final Gpu gpu;
    private final CommandStream stream;
    private final Arena arena = Arena.global();

    private final CpuHeap views, samplers;
    private final long rtvHeap, dsvHeap;
    private final long rtvBase, dsvBase;
    private int rtvNext, dsvNext;

    public final long viewHeap, samplerHeap;
    private final long viewCpu, viewGpu, samplerCpu, samplerGpu;
    private int ringHead, headBlock = -1;
    private long ringLap;
    private static final int TABLE_CACHE_LIMIT = 4096;
    private final Long2ObjectOpenHashMap<long[]> viewTables = new Long2ObjectOpenHashMap<>();
    private long tableEpoch = -1;
    public long viewTableHits;
    private final long[] blockSerial = new long[VIEW_RING / RING_BLOCK];

    private int samplerHead;
    public int samplerGeneration;
    private final Long2IntOpenHashMap samplerTables = new Long2IntOpenHashMap();
    public long ringAllocations, samplerTableHits, samplerTableMisses;

    private final MemorySegment scratch;
    private static final int MAX_COPY = 256;
    private final MemorySegment copySources = Arena.global().allocate(8L * MAX_COPY, 8), copyDest = Arena.global().allocate(16, 8);

    private static final class CpuHeap {
        final Gpu gpu;
        final int type, increment;
        final LongArrayList free = new LongArrayList();
        final LongArrayList heaps = new LongArrayList();

        CpuHeap(Gpu gpu, int type, int increment) {
            this.gpu = gpu;
            this.type = type;
            this.increment = increment;
        }

        long alloc() {
            if (free.isEmpty()) grow();
            return free.removeLong(free.size() - 1);
        }

        void free(long handle) {
            if (handle != 0L) free.add(handle);
        }

        private void grow() {
            final long heap = createHeap(gpu, type, CHUNK, false);
            heaps.add(heap);
            final long start = cpuStart(heap);
            for (int i = CHUNK - 1; i >= 0; i--) free.add(start + (long) i * increment);
        }
    }

    public Descriptors(Gpu gpu, CommandStream stream) {
        this.gpu = gpu;
        this.stream = stream;
        views = new CpuHeap(gpu, Dx.DH_CBV_SRV_UAV, gpu.cbvSrvUavIncrement);
        samplers = new CpuHeap(gpu, Dx.DH_SAMPLER, gpu.samplerIncrement);
        rtvHeap = createHeap(gpu, Dx.DH_RTV, RTV_RING, false);
        dsvHeap = createHeap(gpu, Dx.DH_DSV, DSV_RING, false);
        rtvBase = cpuStart(rtvHeap);
        dsvBase = cpuStart(dsvHeap);
        viewHeap = createHeap(gpu, Dx.DH_CBV_SRV_UAV, VIEW_RING, true);
        samplerHeap = createHeap(gpu, Dx.DH_SAMPLER, SAMPLER_HEAP, true);
        viewCpu = cpuStart(viewHeap);
        viewGpu = gpuStart(viewHeap);
        samplerCpu = cpuStart(samplerHeap);
        samplerGpu = gpuStart(samplerHeap);
        samplerTables.defaultReturnValue(-1);
        scratch = arena.allocate(512, 8);
    }

    static long createHeap(Gpu gpu, int type, int count, boolean shaderVisible) {
        final MemorySegment desc = Arena.ofAuto().allocate(16, 4);
        desc.set(JAVA_INT, 0, type);
        desc.set(JAVA_INT, 4, count);
        desc.set(JAVA_INT, 8, shaderVisible ? Dx.DH_SHADER_VISIBLE : 0);
        final MemorySegment out = Arena.ofAuto().allocate(8);
        Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_DESCRIPTOR_HEAP, desc.address(), Dx.IID_DESCRIPTOR_HEAP, out.address()), "CreateDescriptorHeap");
        return out.get(JAVA_LONG, 0);
    }

    static long cpuStart(long heap) {
        final MemorySegment out = Arena.ofAuto().allocate(8);
        Com.p(heap, Dx.HEAP_CPU_START, out.address());
        return out.get(JAVA_LONG, 0);
    }

    static long gpuStart(long heap) {
        final MemorySegment out = Arena.ofAuto().allocate(8);
        Com.p(heap, Dx.HEAP_GPU_START, out.address());
        return out.get(JAVA_LONG, 0);
    }

    public long allocView() {
        return views.alloc();
    }

    public void freeView(long handle) {
        if (handle == 0L) return;
        views.free(handle);
        viewEpoch++;
        epoch++;
    }

    public static long epoch, viewEpoch;

    public long allocSampler() {
        return samplers.alloc();
    }

    public void freeSampler(long handle) {
        samplers.free(handle);
    }

    public long nextRtv() {
        final long h = rtvBase + (long) rtvNext * gpu.rtvIncrement;
        rtvNext = (rtvNext + 1) % RTV_RING;
        return h;
    }

    public long nextRtvRun(int count) {
        if (rtvNext + count > RTV_RING) rtvNext = 0;
        final long h = rtvBase + (long) rtvNext * gpu.rtvIncrement;
        rtvNext += count;
        if (rtvNext >= RTV_RING) rtvNext = 0;
        return h;
    }

    public long nextDsv() {
        final long h = dsvBase + (long) dsvNext * gpu.dsvIncrement;
        dsvNext = (dsvNext + 1) % DSV_RING;
        return h;
    }

    public long rtvAt(long run, int i) {
        return run + (long) i * gpu.rtvIncrement;
    }

    public long viewTable(long[] src, int from, int count, long nullDescriptor) {
        if (tableEpoch != viewEpoch || viewTables.size() > TABLE_CACHE_LIMIT) {
            viewTables.clear();
            tableEpoch = viewEpoch;
        }
        long key = count;
        for (int i = 0; i < count; i++) {
            final long s = src[from + i];
            key = key * 0x9E3779B97F4A7C15L + (s != 0L ? s : nullDescriptor);
        }
        key ^= key >>> 29;
        long[] e = viewTables.get(key);
        if (e != null && e.length == count + 1 && ringPosition() - e[0] < VIEW_RING / 2 && sameTable(e, src, from, count, nullDescriptor)) {
            final int index = (int) (e[0] % VIEW_RING);
            final long serial = stream.currentSerial();
            for (int b = index / RING_BLOCK, last = (index + count - 1) / RING_BLOCK; b <= last; b++) blockSerial[b] = serial;
            viewTableHits++;
            return ringGpu(index);
        }
        final int index = ringAlloc(count);
        copyToRing(index, src, from, count, nullDescriptor);
        if (e == null || e.length != count + 1) e = new long[count + 1];
        e[0] = ringLap + index;
        for (int i = 0; i < count; i++) {
            final long s = src[from + i];
            e[i + 1] = s != 0L ? s : nullDescriptor;
        }
        viewTables.put(key, e);
        return ringGpu(index);
    }

    private static boolean sameTable(long[] e, long[] src, int from, int count, long nullDescriptor) {
        for (int i = 0; i < count; i++) {
            final long s = src[from + i];
            if (e[i + 1] != (s != 0L ? s : nullDescriptor)) return false;
        }
        return true;
    }

    private long ringPosition() {
        return ringLap + ringHead;
    }

    public int ringAlloc(int n) {
        if (ringHead + n > VIEW_RING) {
            ringHead = 0;
            ringLap += VIEW_RING;
        }
        final int first = ringHead;
        final long serial = stream.currentSerial();
        for (int b = first / RING_BLOCK, last = (first + n - 1) / RING_BLOCK; b <= last; b++) {
            if (b != headBlock) {
                if (!stream.isIdle(blockSerial[b])) stream.waitFor(blockSerial[b]);
                headBlock = b;
            }
            blockSerial[b] = serial;
        }
        ringHead += n;
        ringAllocations++;
        return first;
    }

    public long ringCpu(int index) {
        return viewCpu + (long) index * gpu.cbvSrvUavIncrement;
    }

    public long ringGpu(int index) {
        return viewGpu + (long) index * gpu.cbvSrvUavIncrement;
    }

    public void copyToRing(int index, long[] src, int from, int count, long nullDescriptor) {
        if (count <= 0) return;
        if (count > MAX_COPY) {
            copyToRing(index, src, from, MAX_COPY, nullDescriptor);
            copyToRing(index + MAX_COPY, src, from + MAX_COPY, count - MAX_COPY, nullDescriptor);
            return;
        }
        for (int i = 0; i < count; i++) {
            final long s = src[from + i];
            copySources.set(JAVA_LONG, 8L * i, s != 0L ? s : nullDescriptor);
        }
        copyDest.set(JAVA_LONG, 0, ringCpu(index));
        copyDest.set(JAVA_INT, 8, count);
        Com.v(gpu.device, Dx.DEV_COPY_DESCRIPTORS, 1, copyDest.address(), copyDest.address() + 8, count, copySources.address(), 0L, Dx.DH_CBV_SRV_UAV);
    }

    public void writeCbv(int index, long gpuAddress, int sizeBytes) {
        scratch.set(JAVA_LONG, 0, gpuAddress);
        scratch.set(JAVA_INT, 8, (sizeBytes + 255) & ~255);
        Com.v(gpu.device, Dx.DEV_CREATE_CBV, scratch.address(), viewCpu + (long) index * gpu.cbvSrvUavIncrement);
    }

    public long samplerTable(long[] src, int count, long nullSampler) {
        long key = count;
        for (int i = 0; i < count; i++) key = key * 0x9E3779B97F4A7C15L + (src[i] == 0L ? nullSampler : src[i]);
        key ^= key >>> 29;
        int slot = samplerTables.get(key);
        if (slot >= 0) {
            samplerTableHits++;
            return samplerGpu + (long) slot * gpu.samplerIncrement;
        }
        samplerTableMisses++;
        if (samplerHead + count > SAMPLER_HEAP) {
            stream.finish();
            samplerTables.clear();
            samplerHead = 0;
            samplerGeneration++;
        }
        slot = samplerHead;
        samplerHead += count;
        for (int i = 0; i < count; i++) {
            final long s = src[i] != 0L ? src[i] : nullSampler;
            Com.v(gpu.device, Dx.DEV_COPY_DESCRIPTORS_SIMPLE, 1, samplerCpu + (long) (slot + i) * gpu.samplerIncrement, s, Dx.DH_SAMPLER);
        }
        samplerTables.put(key, slot);
        return samplerGpu + (long) slot * gpu.samplerIncrement;
    }

    public void bindHeaps(long list) {
        scratch.set(JAVA_LONG, 0, viewHeap);
        scratch.set(JAVA_LONG, 8, samplerHeap);
        Com.v(list, Dx.CL_SET_HEAPS, 2, scratch.address());
    }

    public void destroy() {
        for (long h : views.heaps) Com.release(h);
        for (long h : samplers.heaps) Com.release(h);
        Com.release(rtvHeap);
        Com.release(dsvHeap);
        Com.release(viewHeap);
        Com.release(samplerHeap);
    }
}
