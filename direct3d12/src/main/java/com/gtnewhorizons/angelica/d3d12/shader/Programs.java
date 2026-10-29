package com.gtnewhorizons.angelica.d3d12.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;

import com.gtnewhorizons.angelica.glemu.shader.Shader;

import com.gtnewhorizons.angelica.d3d12.gl.D3d12GL;

import java.util.List;

public final class Programs extends ProgramsBase<Program> {

    private final D3d12GL gl;
    public final ProgramLinker linker;

    public Programs(D3d12GL gl) {
        this.gl = gl;
        this.linker = new ProgramLinker(gl.gpu, gl.rootSignatures, gl.stream::defer);
    }

    private static Program detachD3d12Objects(Program p) {
        final Program old = new Program(p.id);
        for (int i = 0; i < Program.STAGES; i++) {
            old.code[i] = p.code[i];
            old.pendingCode[i] = p.pendingCode[i];
            p.code[i] = null;
            p.pendingCode[i] = null;
        }
        old.vertexVariants.putAll(p.vertexVariants);
        p.vertexVariants.clear();
        old.pendingVariants.putAll(p.pendingVariants);
        p.pendingVariants.clear();
        p.failedVariants.clear();
        old.computePipeline = p.computePipeline;
        p.computePipeline = 0L;
        return old;
    }

    public void finishPending() {
        for (Program p : allPrograms()) {
            if (!p.linked) continue;
            for (int slot = 0; slot < Program.STAGES; slot++) {
                if (p.pendingCode[slot] != null) p.code(slot);
            }
            if (!p.linked) throw new IllegalStateException("program " + p.id + " " + p.log);
        }
    }

    @Override protected String apiName() {
        return "Direct3D 12";
    }

    @Override protected String apiLabel() {
        return "D3D12";
    }

    @Override protected Program newProgram(int id) {
        return new Program(id);
    }

    @Override protected boolean linkProgram(Program p, List<Shader> shaders) {
        return linker.link(p, shaders);
    }

    @Override protected boolean anyStateUsesProgram(int id) {
        return gl.anyStateUsesProgram(id);
    }

    @Override protected void destroyNativeObjects(Program p) {
        linker.destroy(p);
        forgetPipelines(p, -1);
    }

    @Override protected Object detachNativeObjects(Program p) {
        return detachD3d12Objects(p);
    }

    @Override protected void linkDone(Program p, Object detached, boolean linked) {
        linker.destroy((Program) detached);
        if (linked) forgetPipelines(p, p.gen);
    }

    @Override protected void retireNativeObjects(Program p) {
        linker.destroy(p);
        forgetPipelines(p, -1);
    }

    private void forgetPipelines(Program p, int keepGen) {
        if (gl.renderer != null) gl.renderer.pipelines.forget(p.id, keepGen);
    }
}
