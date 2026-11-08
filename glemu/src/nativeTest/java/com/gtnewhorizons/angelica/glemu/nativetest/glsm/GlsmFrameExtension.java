package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.extension.ExtensionContext;

public final class GlsmFrameExtension extends GlsmRigExtension {

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmHeadlessRig.beginFrameAndReset();
    }
}
