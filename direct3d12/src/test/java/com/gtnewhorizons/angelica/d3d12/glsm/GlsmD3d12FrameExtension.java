package com.gtnewhorizons.angelica.d3d12.glsm;

import com.gtnewhorizons.angelica.glsm.GlsmRedirectAgent;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class GlsmD3d12FrameExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        GlsmD3d12HeadlessRig.boot();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmD3d12HeadlessRig.beginFrameAndReset();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlsmD3d12HeadlessRig.endFrame();
        GlsmRedirectAgent.assertNoFailures();
    }
}
