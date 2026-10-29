package com.gtnewhorizons.angelica.dx12.resource;

import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;

public final class Target extends TargetBase<Tex> {
    public Target() {
        super(new Tex[Fbo.MAX_COLOR]);
    }
}
