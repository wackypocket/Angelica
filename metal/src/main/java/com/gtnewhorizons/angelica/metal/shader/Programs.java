package com.gtnewhorizons.angelica.metal.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;

import com.gtnewhorizons.angelica.glemu.shader.Shader;

import com.gtnewhorizons.angelica.metal.gl.MetalGL;

import java.util.List;

public final class Programs extends ProgramsBase<Program> {

    private final MetalGL gl;
    public final ProgramLinker linker;

    public Programs(MetalGL gl) {
        this.gl = gl;
        this.linker = new ProgramLinker(gl.gpu);
    }

    private static Program detachMetalObjects(Program p) {
        final Program old = new Program(p.id);
        System.arraycopy(p.libraries, 0, old.libraries, 0, Program.STAGES);
        System.arraycopy(p.functions, 0, old.functions, 0, Program.STAGES);
        old.clipFunctions.putAll(p.clipFunctions);
        old.computePipeline = p.computePipeline;
        old.fragmentNoDepthLibrary = p.fragmentNoDepthLibrary;
        old.fragmentNoDepthFunction = p.fragmentNoDepthFunction;
        old.vertexNoRasterLibrary = p.vertexNoRasterLibrary;
        old.vertexNoRasterFunction = p.vertexNoRasterFunction;
        return old;
    }

    @Override protected String apiName() {
        return "Metal";
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
        return detachMetalObjects(p);
    }

    @Override protected void linkDone(Program p, Object detached, boolean linked) {
        linker.destroy((Program) detached);
    }

    @Override protected void retireNativeObjects(Program p) {
        linker.destroy(p);
    }
}
