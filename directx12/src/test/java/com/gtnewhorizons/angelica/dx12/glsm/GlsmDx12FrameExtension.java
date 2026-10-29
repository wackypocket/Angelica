package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GlsmRedirectAgent;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class GlsmDx12FrameExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        GlsmDx12HeadlessRig.boot();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmDx12HeadlessRig.beginFrameAndReset();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlsmDx12HeadlessRig.endFrame();
        GlsmRedirectAgent.assertNoFailures();
    }
}
