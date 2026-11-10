package com.gtnewhorizons.angelica.vulkan.shader;

import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import java.util.List;
import org.lwjgl.vulkan.VK10;

public final class Programs extends ProgramsBase<Program> {

    private final VulkanGL gl;
    final ProgramLinker linker;

    @Override protected boolean nativeCullDistance() {
        return gl.gpu.cullDistance;
    }
    @Override protected int atomicLimit(int pname) {
        return gl.getInteger(pname);
    }

    @Override protected int imagesPerStage() {
        return gl.imagesPerStage();
    }


    @Override protected boolean nativeGeometry() {
        return gl.gpu.geometryShader;
    }

    public Programs(VulkanGL gl) {
        this.gl = gl;
        this.linker = new ProgramLinker(gl.gpu);
    }

    private void retireVulkanObjects(long[] modules, long layout, long setLayout) {
        gl.stream.defer(() -> {
            for (long m : modules) if (m != 0L) VK10.vkDestroyShaderModule(gl.gpu.device, m, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(gl.gpu.device, layout, null);
            if (setLayout != 0L) VK10.vkDestroyDescriptorSetLayout(gl.gpu.device, setLayout, null);
        });
    }

    @Override protected String apiName() {
        return "Vulkan";
    }

    @Override protected String apiLabel() {
        return "Vulkan";
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
        return new Object[] {p.modules.clone(), p.pipelineLayout, p.setLayout};
    }

    @Override protected void linkDone(Program p, Object detached, boolean linked) {
        if (!linked) return;
        forgetPipelines(p, p.gen);
        final Object[] old = (Object[]) detached;
        retireVulkanObjects((long[]) old[0], (long) old[1], (long) old[2]);
    }

    @Override protected void retireNativeObjects(Program p) {
        retireVulkanObjects(p.modules.clone(), p.pipelineLayout, p.setLayout);
        forgetPipelines(p, -1);
    }

    private void forgetPipelines(Program p, int keepGen) {
        gl.renderer.pipelines.forget(p.id, keepGen);
    }
}
