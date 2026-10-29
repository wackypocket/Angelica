package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.callBlocking;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class CommandStream {
    private static final Tracy.ZoneId Z_MTL_SUBMIT = Tracy.zoneId("mtlSubmit", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_MTL_FENCE_WAIT = Tracy.zoneId("mtlFenceWait", Tracy.COLOR_SWAP);

    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    public static final int NONE = 0, RENDER = 1, BLIT = 2, COMPUTE = 3;

    private static final long COMMAND_BUFFER = sel("commandBuffer"), COMMIT = sel("commit"), WAIT = sel("waitUntilCompleted"), STATUS = sel("status");
    private static final long ERROR = sel("error"), RENDER_ENCODER = sel("renderCommandEncoderWithDescriptor:"), BLIT_ENCODER = sel("blitCommandEncoder");
    private static final long COMPUTE_ENCODER = sel("computeCommandEncoder"), END_ENCODING = sel("endEncoding"), PRESENT = sel("presentDrawable:");
    private static final long SET_LABEL = sel("setLabel:"), GPU_START = sel("GPUStartTime"), GPU_END = sel("GPUEndTime");
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
    private long currentSerial = 1;
    private long completedSerial = 0;
    private boolean recordedWork;
    private boolean reportedError;
    public long gpuNanos, submits;

    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private Runnable beforeSubmit = () -> {};
    private Runnable beforeEncoderEnd = () -> {};

    private record InFlight(long serial, long cmd, long upload) {}
    private record Deferred(long serial, Runnable action) {}

    public CommandStream(Gpu gpu) {
        this.gpu = gpu;
        this.fence = send(gpu.device, NEW_FENCE);
    }

    public void markStorageWrites() {
        storageWrites = true;
    }

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

    public long currentSerial() { return currentSerial; }
    public long completedSerial() { return completedSerial; }

    public boolean isIdle(long lastUseSerial) {
        return lastUseSerial <= completedSerial;
    }

    public boolean hasRecording() {
        return cmd != 0L;
    }

    public boolean hasRecordedWork() {
        return recordedWork;
    }

    public boolean canHoist(long lastUse) {
        return lastUse < currentSerial;
    }

    private long newCommandBuffer(String label) {
        final long pool = ObjC.poolPush();
        try {
            final long cb = ObjC.retain(send(gpu.queue, COMMAND_BUFFER));
            if (cb == 0L) throw new IllegalStateException("MTLCommandQueue returned no command buffer");
            if (SystemProperties.METAL_DEBUG) labelObject(cb, label + " " + currentSerial);
            return cb;
        } finally {
            ObjC.poolPop(pool);
        }
    }

    private static void labelObject(long object, String label) {
        final long s = ObjC.nsString(label);
        call(object, SET_LABEL, s);
        ObjC.release(s);
    }

    public long cmd() {
        if (cmd == 0L) cmd = newCommandBuffer("angelica");
        recordedWork = true;
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

    public boolean isUploadEncoder(long enc) {
        return enc != 0L && enc == uploadBlit;
    }

    public long submit() {
        return submit(0L);
    }

    public long submit(long drawable) {
        Tracy.beginZone(Z_MTL_SUBMIT);
        try {
            return submitUnzoned(drawable);
        } finally {
            Tracy.endZone();
        }
    }

    private long submitUnzoned(long drawable) {
        beforeSubmit.run();
        endEncoder();
        if (cmd == 0L && upload == 0L && drawable == 0L) return closeEmptySerial();
        if (uploadBlit != 0L) {
            call(uploadBlit, END_ENCODING);
            ObjC.release(uploadBlit);
            uploadBlit = 0L;
        }
        if (upload != 0L) callBlocking(upload, COMMIT);
        final long c = cmd();
        if (drawable != 0L) callBlocking(c, PRESENT, drawable);
        callBlocking(c, COMMIT);
        final long serial = currentSerial;
        inFlight.addLast(new InFlight(serial, c, upload));
        submits++;
        cmd = 0L;
        upload = 0L;
        recordedWork = false;
        currentSerial++;
        poll();
        return serial;
    }

    private long closeEmptySerial() {
        final long serial = currentSerial;
        currentSerial++;
        if (inFlight.isEmpty()) {
            completedSerial = serial;
            runDeferred();
        }
        return serial;
    }

    private boolean done(long cb) {
        return send(cb, STATUS) >= Mtl.CB_COMPLETED;
    }

    public void poll() {
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.peekFirst();
            if (!done(f.cmd)) break;
            retire(inFlight.pollFirst());
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    private void retire(InFlight f) {
        completedSerial = Math.max(completedSerial, f.serial);
        if (send(f.cmd, STATUS) == Mtl.CB_ERROR && !reportedError) {
            reportedError = true;
            LOG.error("Metal command buffer {} failed: {}", f.serial, ObjC.errorDescription(send(f.cmd, ERROR)));
        }
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

    public void waitFor(long serial) {
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
            callBlocking(f.cmd, WAIT);
            retire(f);
        }
        if (inFlight.isEmpty()) completedSerial = currentSerial - 1;
        runDeferred();
    }

    public boolean waitFor(long serial, long timeoutNanos) {
        if (serial <= completedSerial) return true;
        if (serial >= currentSerial) return false;
        final long deadline = System.nanoTime() + Math.max(0L, timeoutNanos);
        while (!inFlight.isEmpty() && inFlight.peekFirst().serial <= serial) {
            final InFlight f = inFlight.peekFirst();
            if (!done(f.cmd)) {
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

    public void destroy() {
        endEncoder();
        if (uploadBlit != 0L) {
            call(uploadBlit, END_ENCODING);
            ObjC.release(uploadBlit);
            uploadBlit = 0L;
        }
        while (!inFlight.isEmpty()) {
            final InFlight f = inFlight.pollFirst();
            callBlocking(f.cmd, WAIT);
            retire(f);
        }
        if (cmd != 0L) ObjC.release(cmd);
        if (upload != 0L) ObjC.release(upload);
        cmd = upload = 0L;
        completedSerial = currentSerial;
        while (!deferred.isEmpty()) deferred.pollFirst().action.run();
        ObjC.release(fence);
    }
}
