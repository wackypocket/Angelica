package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;

/** One-entry cache of a renderer's last pipeline lookup, keyed on every generation counter that feeds the description. */
public final class PipelineStamp {
    private int program = -1, programGen = -1, pipelineGen = -1, layout = -1, mode = -1, targetGen = -1, fboId = Integer.MIN_VALUE;
    private long extra;
    private Object state;
    private long pipeline;

    /** The pipeline for this draw if nothing changed since the last one, otherwise 0. {@code extra} is anything more the backend's description reads. */
    public long get(ProgramBase<?> prog, GlState st, Vao vao, int glMode, TargetBase<?> t, long extra) {
        final boolean same = prog.id == program && prog.gen == programGen && st.pipelineGen == pipelineGen && vao.layoutId() == layout
            && glMode == mode && t.globalGen == targetGen && t.fboId == fboId && st == state && extra == this.extra;
        return same ? pipeline : 0L;
    }

    public void set(ProgramBase<?> prog, GlState st, Vao vao, int glMode, TargetBase<?> t, long extra, long pipeline) {
        program = prog.id;
        programGen = prog.gen;
        pipelineGen = st.pipelineGen;
        layout = vao.layoutId();
        mode = glMode;
        targetGen = t.globalGen;
        fboId = t.fboId;
        state = st;
        this.extra = extra;
        this.pipeline = pipeline;
    }
}
