package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;

import com.gtnewhorizons.angelica.glemu.shader.Shader;

import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;

import java.util.List;

public final class Programs extends ProgramsBase<Program> {

    private final Dx12GL gl;
    public final ProgramLinker linker;

    public Programs(Dx12GL gl) {
        this.gl = gl;
        this.linker = new ProgramLinker(gl.gpu, gl.rootSignatures, gl.stream::defer);
    }

    private static Program detachDx12Objects(Program p) {
        final Program old = new Program(p.id);
        for (int i = 0; i < Program.STAGES; i++) {
            old.code[i] = p.code[i];
            old.functions[i] = p.functions[i];
            p.code[i] = null;
            p.functions[i] = 0L;
        }
        old.vertexVariants.putAll(p.vertexVariants);
        p.vertexVariants.clear();
        old.computePipeline = p.computePipeline;
        p.computePipeline = 0L;        return old;
    }

    @Override protected String apiName() {
        return "Direct3D 12";
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
    }

    @Override protected Object detachNativeObjects(Program p) {
        return detachDx12Objects(p);
    }

    @Override protected void linkDone(Program p, Object detached, boolean linked) {
        linker.destroy((Program) detached);
    }

    @Override protected void retireNativeObjects(Program p) {
        linker.destroy(p);
    }
}
