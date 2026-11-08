package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import java.util.List;

/** Linking with bindings in a flat list, in stages numbered the same way. */
public abstract class TokenProgramLinkerBase<P extends ProgramBase<P>, B extends TokenBinding> extends ProgramLinkerBase<P> {
    private final GpuCaps caps;

    protected TokenProgramLinkerBase(GpuCaps caps) {
        this.caps = caps;
    }

    protected abstract List<B> bindingsOf(P program);

    protected abstract B newBinding(int binding, ProgramBase.BindingKind kind, int descriptorType, int count, int stageFlags, int ref, int dim, boolean arrayed, boolean shadow, int glType);

    /** Carries the slots a backend has assigned over to a binding that replaces this one. */
    protected abstract void copySlots(B from, B to);

    /** Whether every stage declaring this resource only reads it; a resource written by any stage counts as written in all. */
    protected static boolean readOnly(StageReflection[] refl, TokenBinding b, StageReflection.Kind kind, String name) {
        boolean readOnly = false;
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            if (refl[slot] == null || (b.stageFlags & 1 << slot) == 0) continue;
            for (StageReflection.Resource r : refl[slot].resources) {
                if (r.kind != kind || !r.name.equals(name)) continue;
                if (!r.readOnly) return false;
                readOnly = true;
            }
        }
        return readOnly;
    }

    /** Runs a link up to the backend's own slot assignment. Returns null with the program's log set when it fails. */
    protected final Stages beginLink(P p, List<Shader> shaders, P staged, String apiName) {
        final Stages stages = collectStages(p, shaders);
        if (stages == null) return null;
        final Shader[] byStage = stages.byStage;
        // A separable program of those stages alone only serves for a pipeline to link anew, which emulates them, so it needs no native form.
        if (!p.separable && (byStage[ProgramBase.GS] != null || byStage[ProgramBase.TCS] != null || byStage[ProgramBase.TES] != null)) {
            p.log = "geometry and tessellation shaders are not supported on " + apiName;
            return null;
        }
        if (!compileStages(p, stages)) return null;
        final List<String> problems = assignInterfaces(p, staged, stages);
        assignResources(staged, stages, problems);
        if (!problems.isEmpty()) {
            p.log = String.join("\n", problems);
            return null;
        }
        return stages;
    }

    @Override protected GpuCaps gpuCaps() {
        return caps;
    }

    @Override protected int stageBit(int glType) {
        return 1 << stageSlot(glType);
    }

    @Override protected int bindingCount(P out) {
        return bindingsOf(out).size();
    }

    @Override protected void placeDefaultBlock(P out, ProgramBase.StageBlock block, StageReflection r, int slot, int stageBit) {
        r.spirv.setDecoration(r.defaultBlockVar, Spirv.DEC_BINDING, slot);
        r.spirv.setDecoration(r.defaultBlockVar, Spirv.DEC_DESCRIPTOR_SET, 0);
        bindingsOf(out).add(newBinding(slot, ProgramBase.BindingKind.DEFAULT_BLOCK, 0, 1, stageBit, slot, 0, false, false, 0));
    }

    @Override protected void addBufferBinding(P out, boolean storage, int binding, int stageFlags, int blockIndex) {
        final ProgramBase.BindingKind kind = storage ? ProgramBase.BindingKind.STORAGE_BUFFER : ProgramBase.BindingKind.UNIFORM_BUFFER;
        bindingsOf(out).add(newBinding(binding, kind, 0, 1, stageFlags, blockIndex, 0, false, false, 0));
    }

    @Override protected void addOpaqueBinding(P out, ProgramBase.BindingKind kind, Res res, int binding, int stageFlags) {
        bindingsOf(out).add(newBinding(binding, kind, 0, res.count(), stageFlags, -1, res.dim(), res.arrayed(), res.shadow(), res.glType()));
    }

    @Override protected void rebindOpaque(P out, int bindingIndex, int uniformIndex) {
        final B b = bindingsOf(out).get(bindingIndex);
        final B rebound = newBinding(b.binding, b.kind, b.descriptorType, b.count, b.stageFlags, uniformIndex, b.dim, b.arrayed, b.shadow, b.glType);
        copySlots(b, rebound);
        bindingsOf(out).set(bindingIndex, rebound);
    }
}
