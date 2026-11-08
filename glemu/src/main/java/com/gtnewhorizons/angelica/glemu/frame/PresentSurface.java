package com.gtnewhorizons.angelica.glemu.frame;

import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;

/** What a Metal layer offers for choosing how frames are presented. */
public interface PresentSurface {
    boolean supports(VSyncMode mode);

    VSyncMode setVSync(VSyncMode preferred);
}
