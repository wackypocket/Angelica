package com.gtnewhorizons.angelica.metal.device;

import com.gtnewhorizons.angelica.glemu.NativeDrawable;
import com.gtnewhorizons.angelica.metal.MetalGate;

public final class MtlDrawable extends NativeDrawable {
    public MtlDrawable() {
        super(MetalGate.GATE::isActive);
    }
}
