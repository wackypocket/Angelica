package com.gtnewhorizons.angelica.vulkan.shader;

public final class Shader {
    public final int id;
    public final int type;
    public String source = "";
    public boolean compiled;
    public String log = "";
    public ShaderCompiler.Compiled result;
    public int resultFixup = -1;
    public boolean deletePending;
    public int attachCount;
    public String label;

    public Shader(int id, int type) {
        this.id = id;
        this.type = type;
    }
}
