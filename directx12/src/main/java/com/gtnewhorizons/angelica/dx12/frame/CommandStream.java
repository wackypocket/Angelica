package com.gtnewhorizons.angelica.dx12.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Descriptors;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Fence;
import com.gtnewhorizons.angelica.dx12.device.Gpu;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class CommandStream {
    private static final Tracy.ZoneId Z_DX12_SUBMIT = Tracy.zoneId("dx12Submit", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_DX12_FENCE_WAIT = Tracy.zoneId("dx12FenceWait", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static final int MAX_BARRIERS = 128;

    private final Gpu gpu;
    private final Fence fence;
    private Descriptors descriptors;

    private final long mainList, uploadList;
    private boolean mainOpen, uploadOpen;
    private long mainAllocator, uploadAllocator;
    private final ArrayDeque<long[]> freeAllocators = new ArrayDeque<>();

    private final MemorySegment barriers;
    private int barrierCount;
    private final MemorySegment single = Arena.global().allocate(32, 8);
    private final MemorySegment listArray = Arena.global().allocate(16, 8);

    private long currentSerial = 1;
    private long completedSerial = 0;
    private boolean recordedWork;
    private boolean uavPending;
    public long gpuNanos, submits;

    private record InFlight(long serial, long mainAllocator, long uploadAllocator) {}
    private record Deferred(long serial, Runnable action) {}

    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private Runnable beforeSubmit = () -> {};

    public CommandStream(Gpu gpu) {
        this.gpu = gpu;
        this.fence = gpu.fence();
        barriers = Arena.global().allocate(32L * MAX_BARRIERS, 8);
        final long a = newAllocator(), b = newAllocator();
        mainList = newList(a);
        Com.check(Com.hr(mainList, Dx.CL_CLOSE), "Close");
        uploadList = newList(b);
        Com.check(Com.hr(uploadList, Dx.CL_CLOSE), "Close");
        freeAllocators.add(new long[] {a, 0});
        freeAllocators.add(new long[] {b, 0});
    }

    public void bindDescriptors(Descriptors d) {
        this.descriptors = d;
    }

    private long newAllocator() {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMAND_ALLOCATOR, Dx.LIST_DIRECT, Dx.IID_COMMAND_ALLOCATOR, out.address()), "CreateCommandAllocator");
            return out.get(JAVA_LONG, 0);
        }
    }

    private long newList(long allocator) {
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment out = a.allocate(8);
            Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_COMMAND_LIST, 0, Dx.LIST_DIRECT, allocator, 0L, Dx.IID_COMMAND_LIST, out.address()), "CreateCommandList");
            return out.get(JAVA_LONG, 0);
        }
    }

    private long takeAllocator() {
        final long[] head = freeAllocators.peekFirst();
        if (head != null && fence.isDone(head[1])) {
            freeAllocators.pollFirst();
            Com.check(Com.hr(head[0], Dx.ALLOCATOR_RESET), "ID3D12CommandAllocator::Reset");
            return head[0];
        }
        return newAllocator();
    }

    public void setBeforeSubmit(Runnable hook) {
        this.beforeSubmit = hook;
    }

    public long currentSerial() { return currentSerial; }
    public long completedSerial() { return completedSerial; }

    public boolean isIdle(long lastUseSerial) {
        return lastUseSerial <= completedSerial;
    }

    public boolean hasRecording() {
        return mainOpen;
    }

    public boolean hasRecordedWork() {
        return recordedWork;
    }

    public boolean canHoist(long lastUse) {
        return lastUse < currentSerial;
    }

    public long list() {
        if (!mainOpen) {
            mainAllocator = takeAllocator();
            Com.check(Com.hr(mainList, Dx.CL_RESET, mainAllocator, 0L), "ID3D12GraphicsCommandList::Reset");
            mainOpen = true;
            if (descriptors != null) descriptors.bindHeaps(mainList);
            barrierCount = 0;
        }
        recordedWork = true;
        flushBarriers();
        return mainList;
    }

    public boolean isUploadList(long list) {
        return list == uploadList;
    }

    public long uploadList() {
        if (!uploadOpen) {
            uploadAllocator = takeAllocator();
            Com.check(Com.hr(uploadList, Dx.CL_RESET, uploadAllocator, 0L), "ID3D12GraphicsCommandList::Reset");
            uploadOpen = true;
        }
        return uploadList;
    }

    public void transition(long resource, int subresource, int before, int after) {
        if (before == after) return;
        if (!mainOpen) list();
        if (barrierCount == MAX_BARRIERS) flushBarriers();
        final long o = 32L * barrierCount++;
        barriers.set(JAVA_INT, o, Dx.BARRIER_TRANSITION);
        barriers.set(JAVA_INT, o + 4, 0);
        barriers.set(JAVA_LONG, o + 8, resource);
        barriers.set(JAVA_INT, o + 16, subresource);
        barriers.set(JAVA_INT, o + 20, before);
        barriers.set(JAVA_INT, o + 24, after);
    }

    public void uavBarrier(long resource) {
        if (!mainOpen) list();
        if (barrierCount == MAX_BARRIERS) flushBarriers();
        final long o = 32L * barrierCount++;
        barriers.set(JAVA_INT, o, Dx.BARRIER_UAV);
        barriers.set(JAVA_INT, o + 4, 0);
        barriers.set(JAVA_LONG, o + 8, resource);
    }

    public void flushBarriers() {
        if (barrierCount == 0) return;
        Com.v(mainList, Dx.CL_BARRIER, barrierCount, barriers.address());
        barrierCount = 0;
    }

    public void uploadTransition(long resource, int subresource, int before, int after) {
        if (before == after) return;
        uploadList();
        single.set(JAVA_INT, 0, Dx.BARRIER_TRANSITION);
        single.set(JAVA_INT, 4, 0);
        single.set(JAVA_LONG, 8, resource);
        single.set(JAVA_INT, 16, subresource);
        single.set(JAVA_INT, 20, before);
        single.set(JAVA_INT, 24, after);
        Com.v(uploadList, Dx.CL_BARRIER, 1, single.address());
    }

    public void markStorageWrites() {
        uavPending = true;
    }

    public void storageBarrier() {
        if (!uavPending) return;
        uavPending = false;
        uavBarrier(0L);
    }

    public long submit() {
        Tracy.beginZone(Z_DX12_SUBMIT);
        try {
            return submitUnzoned();
        } finally {
            Tracy.endZone();
        }
    }

    private long submitUnzoned() {
        beforeSubmit.run();
        if (!mainOpen && !uploadOpen) return closeEmptySerial();
        int n = 0;
        if (uploadOpen) {
            closeChecked(uploadList, "upload list");
            listArray.set(JAVA_LONG, 8L * n++, uploadList);
        }
        if (mainOpen) {
            flushBarriers();
            closeChecked(mainList, "main list");
            listArray.set(JAVA_LONG, 8L * n++, mainList);
        }
        Com.execute(gpu.queue, n, listArray.address());
        final long serial = currentSerial;
        Com.check(Com.hr(gpu.queue, Dx.Q_SIGNAL, fence.handle(), serial), "Signal");
        fence.recordSignal(serial);
        inFlight.addLast(new InFlight(serial, mainOpen ? mainAllocator : 0L, uploadOpen ? uploadAllocator : 0L));
        mainOpen = uploadOpen = false;
        recordedWork = false;
        uavPending = false;
        submits++;
        currentSerial++;
        poll();
        return serial;
    }

    private static void closeChecked(long list, String what) {
        final int hr = Com.hr(list, Dx.CL_CLOSE);
        if (hr < 0) com.gtnewhorizons.angelica.dx12.device.DebugLayer.dump(what);
        Com.check(hr, "Close ( " + what + ")");
    }

    private long closeEmptySerial() {
        final long serial = currentSerial;
        currentSerial++;
        if (inFlight.isEmpty()) {
            completedSerial = serial;
            fence.recordSignal(serial);
            runDeferred();
        } else {
            Com.check(Com.hr(gpu.queue, Dx.Q_SIGNAL, fence.handle(), serial), "Signal");
            fence.recordSignal(serial);
            inFlight.addLast(new InFlight(serial, 0L, 0L));
        }
        return serial;
    }

    public void poll() {
        final long done = fence.completed();
        while (!inFlight.isEmpty() && inFlight.peekFirst().serial <= done) retire(inFlight.pollFirst());
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    private void retire(InFlight f) {
        completedSerial = Math.max(completedSerial, f.serial);
        if (f.mainAllocator != 0L) freeAllocators.addLast(new long[] {f.mainAllocator, f.serial});
        if (f.uploadAllocator != 0L) freeAllocators.addLast(new long[] {f.uploadAllocator, f.serial});
    }

    public void waitFor(long serial) {
        Tracy.beginZone(Z_DX12_FENCE_WAIT);
        try {
            waitForUnzoned(serial);
        } finally {
            Tracy.endZone();
        }
    }

    private void waitForUnzoned(long serial) {
        if (serial <= completedSerial) return;
        if (serial >= currentSerial) submit();
        if (serial <= completedSerial) return;
        fence.await(serial);
        poll();
    }

    public boolean waitFor(long serial, long timeoutNanos) {
        if (serial <= completedSerial) return true;
        if (serial >= currentSerial) return false;
        final boolean reached = fence.await(serial, timeoutNanos);
        poll();
        return reached && serial <= completedSerial;
    }

    public int pendingSubmissions() {
        return inFlight.size();
    }

    public void finish() {
        waitFor(currentSerial);
    }

    public void defer(Runnable action) {
        deferred.addLast(new Deferred(currentSerial, action));
    }

    public void deferAfter(long serial, Runnable action) {
        if (serial <= completedSerial) {
            action.run();
            return;
        }
        deferred.addLast(new Deferred(serial, action));
    }

    private void runDeferred() {
        while (!deferred.isEmpty() && deferred.peekFirst().serial <= completedSerial) {
            deferred.pollFirst().action.run();
        }
    }

    public void parkBriefly() {
        LockSupport.parkNanos(50_000L);
    }

    public void destroy() {
        if (mainOpen) {
            Com.check(Com.hr(mainList, Dx.CL_CLOSE), "Close");
            mainOpen = false;
        }
        if (uploadOpen) {
            Com.check(Com.hr(uploadList, Dx.CL_CLOSE), "Close");
            uploadOpen = false;
        }
        if (!inFlight.isEmpty()) {
            fence.await(inFlight.peekLast().serial);
            poll();
        }
        completedSerial = currentSerial;
        while (!deferred.isEmpty()) deferred.pollFirst().action.run();
        for (long[] a : freeAllocators) Com.release(a[0]);
        freeAllocators.clear();
        Com.release(mainList);
        Com.release(uploadList);
    }
}
