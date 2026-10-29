package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;

public final class Target extends TargetBase<Tex> {
    public Target() {
        super(new Tex[Fbo.MAX_COLOR]);
    }
}
