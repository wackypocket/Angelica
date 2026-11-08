package com.gtnewhorizons.angelica.glemu.pipeline;

import com.gtnewhorizons.angelica.glemu.util.IntKey;
import com.gtnewhorizons.angelica.glemu.util.IntKeyTable;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** The pipeline cache every native backend keeps, keyed on {@link #probe} and tracked per program so a relink can drop its pipelines. */
public abstract class PipelinesBase<D> {
    public final IntKey.Probe probe = new IntKey.Probe();

    private final IntKeyTable table = new IntKeyTable();
    private final List<Long> all = new ArrayList<>();
    private final Int2IntOpenHashMap byProgram = new Int2IntOpenHashMap();
    private final Tracy.ZoneId createZone;

    protected PipelinesBase(Tracy.ZoneId createZone) {
        this.createZone = createZone;
    }

    protected abstract int programOf(D desc);

    protected abstract long createUnzoned(D desc);

    public interface Builder<D> {
        long build(D desc, boolean noBlend);
    }

    /** Builds the pipeline, retrying without blending when its attachment formats cannot blend. A retry that works calls onDroppedBlend. */
    protected static <D> long buildWithBlendFallback(D desc, int[] blend, int colorCount, Builder<D> builder, Runnable onDroppedBlend) {
        final long pipeline = builder.build(desc, false);
        if (pipeline != 0L) return pipeline;
        boolean anyBlend = false;
        for (int i = 0; i < colorCount; i++) anyBlend |= blend[i * 8] != 0;
        if (!anyBlend) return 0L;
        final long opaque = builder.build(desc, true);
        if (opaque != 0L) onDroppedBlend.run();
        return opaque;
    }

    protected abstract void destroyNow(long pipeline);

    protected abstract void destroyLater(long pipeline);

    private D pending;
    private final LongSupplier create = this::createPending;

    private long createPending() {
        if (Tracy.ENABLED) Tracy.beginZone(createZone);
        try {
            return createUnzoned(pending);
        } finally {
            if (Tracy.ENABLED) Tracy.endZone();
        }
    }

    public final long get(D desc) {
        pending = desc;
        return findOrCreate(programOf(desc), create);
    }

    protected final long findOrCreate(int programId, LongSupplier create) {
        final long found = table.find(probe);
        if (found != IntKeyTable.MISSING) return found;
        final long pipeline = create.getAsLong();
        table.put(probe, pipeline);
        byProgram.addTo(programId, 1);
        if (pipeline != 0L) all.add(pipeline);
        return pipeline;
    }

    protected boolean stale(IntKey key, int programId, int keepGen) {
        return key.at(0) == programId && (keepGen < 0 || key.at(1) != keepGen);
    }

    public final void forget(int programId, int keepGen) {
        if (byProgram.get(programId) == 0) return;
        table.removeIf(k -> stale(k, programId, keepGen), pipeline -> {
            if (byProgram.addTo(programId, -1) == 1) byProgram.remove(programId);
            if (pipeline != 0L) {
                all.remove(Long.valueOf(pipeline));
                destroyLater(pipeline);
            }
        });
    }

    protected final void destroyPipelines() {
        for (long p : all) destroyNow(p);
        all.clear();
        byProgram.clear();
        table.clear();
    }

    public final int pipelineCount() {
        return all.size();
    }
}
