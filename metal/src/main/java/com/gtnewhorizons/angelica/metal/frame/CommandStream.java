package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.frame.SerialTracker;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.callBlocking;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class CommandStream extends SerialTracker {
    private static final Tracy.ZoneId Z_MTL_SUBMIT = Tracy.zoneId("mtlSubmit", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_MTL_FENCE_WAIT = Tracy.zoneId("mtlFenceWait", Tracy.COLOR_SWAP);

    public static final int NONE = 0, RENDER = 1, BLIT = 2, COMPUTE = 3;

    private static final long COMMAND_BUFFER = sel("commandBuffer"), COMMIT = sel("commit"), WAIT = sel("waitUntilCompleted"), STATUS = sel("status");
    private static final long RENDER_ENCODER = sel("renderCommandEncoderWithDescriptor:"), BLIT_ENCODER = sel("blitCommandEncoder");
    private static final long COMPUTE_ENCODER = sel("computeCommandEncoder"), END_ENCODING = sel("endEncoding");
    private static final long GPU_START = sel("GPUStartTime"), GPU_END = sel("GPUEndTime");
    private static final long NEW_FENCE = sel("newFence"), UPDATE_FENCE_STAGES = sel("updateFence:afterStages:"), WAIT_FENCE_STAGES = sel("waitForFence:beforeStages:");
    private static final long UPDATE_FENCE = sel("updateFence:"), WAIT_FENCE = sel("waitForFence:");
    private static final long RENDER_STAGES = 1L | 2L;

    private final Gpu gpu;
    private final long fence;
    private boolean fencePending;
    private boolean storageWrites;
    private long cmd;
    private long upload;
    private long uploadBlit;
    private long encoder;
    private int encoderKind = NONE;
    public long gpuNanos;

    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private Runnable beforeSubmit = () -> {};
    private Runnable beforeEncoderEnd = () -> {};

    private record InFlight(long serial, long cmd, long upload) {}

    public CommandStream(Gpu gpu) {
        this.gpu = gpu;
        this.fence = send(gpu.device, NEW_FENCE);
    }

    public void markStorageWrites() {
        storageWrites = true;
    }

    // Hazard tracking does not order a fragment stage's image atomics against the next encoder's readback; ComputeDeviceTest covers it.
    private void fenceOpenEncoder() {
        if (encoderKind == RENDER) call(encoder, UPDATE_FENCE_STAGES, fence, RENDER_STAGES);
        else call(encoder, UPDATE_FENCE, fence);
        fencePending = true;
    }

    public void setBeforeSubmit(Runnable hook) {
        this.beforeSubmit = hook;
    }

    public void setBeforeEncoderEnd(Runnable hook) {
        this.beforeEncoderEnd = hook;
    }

    private long newCommandBuffer(String label) {
        final long pool = ObjC.poolPush();
        try {
            final long cb = ObjC.retain(ObjC.sendBlocking(gpu.queue, COMMAND_BUFFER));
            if (cb == 0L) throw new IllegalStateException("MTLCommandQueue returned no command buffer");
            if (SystemProperties.METAL_DEBUG) ObjC.setLabel(cb, label + " " + currentSerial);
            return cb;
        } finally {
            ObjC.poolPop(pool);
        }
    }

    public long cmd() {
        if (cmd == 0L) cmd = newCommandBuffer("angelica");
        return cmd;
    }

    public int encoderKind() {
        return encoderKind;
    }

    public long encoder() {
        return encoder;
    }

    private long openEncoder(int kind, long sel, long arg) {
        endEncoder();
        final long c = cmd();
        final long pool = ObjC.poolPush();
        try {
            encoder = ObjC.retain(arg == 0L && kind != RENDER ? send(c, sel) : send(c, sel, arg));
        } finally {
            ObjC.poolPop(pool);
        }
        if (encoder == 0L) throw new IllegalStateException("Metal returned no encoder of kind " + kind);
        encoderKind = kind;
        if (fencePending) {
            if (kind == RENDER) call(encoder, WAIT_FENCE_STAGES, fence, RENDER_STAGES);
            else call(encoder, WAIT_FENCE, fence);
            fencePending = false;
        }
        return encoder;
    }

    public long render(long passDescriptor) {
        return openEncoder(RENDER, RENDER_ENCODER, passDescriptor);
    }

    public long blit() {
        if (encoderKind == BLIT) return encoder;
        return openEncoder(BLIT, BLIT_ENCODER, 0L);
    }

    public long compute() {
        if (encoderKind == COMPUTE) return encoder;
        return openEncoder(COMPUTE, COMPUTE_ENCODER, 0L);
    }

    public void endEncoder() {
        if (encoder == 0L) return;
        if (encoderKind == RENDER) beforeEncoderEnd.run();
        if (storageWrites) {
            fenceOpenEncoder();
            storageWrites = false;
        }
        call(encoder, END_ENCODING);
        ObjC.release(encoder);
        encoder = 0L;
        encoderKind = NONE;
    }

    public long uploadBlit() {
        if (uploadBlit != 0L) return uploadBlit;
        if (upload == 0L) upload = newCommandBuffer("angelica upload");
        final long pool = ObjC.poolPush();
        try {
            uploadBlit = ObjC.retain(send(upload, BLIT_ENCODER));
        } finally {
            ObjC.poolPop(pool);
        }
        return uploadBlit;
    }

    @Override public long submit() {
        Tracy.beginZone(Z_MTL_SUBMIT);
        try {
            return submitUnzoned();
        } finally {
            Tracy.endZone();
        }
    }

    private long submitUnzoned() {
        beforeSubmit.run();
        endEncoder();
        if (cmd == 0L && upload == 0L) return closeEmptySerial();
        if (uploadBlit != 0L) {
            call(uploadBlit, END_ENCODING);
            ObjC.release(uploadBlit);
            uploadBlit = 0L;
        }
        if (upload != 0L) callBlocking(upload, COMMIT); // committed first, so its copies land before the frame's draws
        final long c = cmd();
        callBlocking(c, COMMIT);
        final long serial = currentSerial;
        inFlight.addLast(new InFlight(serial, c, upload));
        submits++;
        cmd = 0L;
        upload = 0L;
        currentSerial++;
        poll();
        return serial;
    }

    @Override protected boolean nothingInFlight() {
        return inFlight.isEmpty();
    }

    private boolean done(long cb) {
        return send(cb, STATUS) >= Mtl.CB_COMPLETED;
    }

    @Override public void poll() {
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.peekFirst();
            // The upload buffer reads staging memory that retiring hands back, so both have to be finished.
            if (!done(f.cmd) || f.upload != 0L && !done(f.upload)) break;
            retire(inFlight.pollFirst());
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    private void retire(InFlight f) {
        completedSerial = Math.max(completedSerial, f.serial);
        gpu.reportIfFailed(f.cmd, "Command buffer", f.serial);
        if (f.upload != 0L) gpu.reportIfFailed(f.upload, "Upload command buffer", f.serial);
        if (SystemProperties.METAL_STATS) {
            gpuNanos += gpuNanos(f.cmd);
            if (f.upload != 0L) gpuNanos += gpuNanos(f.upload);
        }
        ObjC.release(f.cmd);
        if (f.upload != 0L) ObjC.release(f.upload);
    }

    private static long gpuNanos(long cb) {
        final double seconds = ObjC.sendD(cb, GPU_END) - ObjC.sendD(cb, GPU_START);
        return seconds > 0 ? (long) (seconds * 1e9) : 0L;
    }

    @Override public void waitFor(long serial) {
        Tracy.beginZone(Z_MTL_FENCE_WAIT);
        try {
            waitForUnzoned(serial);
        } finally {
            Tracy.endZone();
        }
    }

    private void waitForUnzoned(long serial) {
        if (serial <= completedSerial) return;
        if (serial >= currentSerial) submit();
        while (!inFlight.isEmpty() && inFlight.peekFirst().serial <= serial) {
            final InFlight f = inFlight.pollFirst();
            if (f.upload != 0L) callBlocking(f.upload, WAIT);
            callBlocking(f.cmd, WAIT);
            retire(f);
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    @Override public boolean waitFor(long serial, long timeoutNanos) {
        if (serial <= completedSerial) return true;
        if (serial >= currentSerial) return false;
        final long deadline = System.nanoTime() + Math.max(0L, timeoutNanos);
        while (!inFlight.isEmpty() && inFlight.peekFirst().serial <= serial) {
            final InFlight f = inFlight.peekFirst();
            if (!done(f.cmd) || f.upload != 0L && !done(f.upload)) {
                if (System.nanoTime() - deadline >= 0L) return false;
                LockSupport.parkNanos(50_000L);
                continue;
            }
            retire(inFlight.pollFirst());
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
        return serial <= completedSerial;
    }

    @Override public void finish() {
        waitFor(currentSerial);
    }

    @Override public void destroy() {
        endEncoder();
        if (uploadBlit != 0L) {
            call(uploadBlit, END_ENCODING);
            ObjC.release(uploadBlit);
            uploadBlit = 0L;
        }
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.pollFirst();
            if (f.upload != 0L) callBlocking(f.upload, WAIT);
            callBlocking(f.cmd, WAIT);
            retire(f);
        }
        if (cmd != 0L) ObjC.release(cmd);
        if (upload != 0L) ObjC.release(upload);
        cmd = upload = 0L;
        completedSerial = currentSerial;
        runAllDeferred();
        ObjC.release(fence);
    }
}
