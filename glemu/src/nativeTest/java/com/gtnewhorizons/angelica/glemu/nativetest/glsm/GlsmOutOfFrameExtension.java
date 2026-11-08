package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.extension.ExtensionContext;

public final class GlsmOutOfFrameExtension extends GlsmRigExtension {

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmHeadlessRig.endFrame();
    }
}
