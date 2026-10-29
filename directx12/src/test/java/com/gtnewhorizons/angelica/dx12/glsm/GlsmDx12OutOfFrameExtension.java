package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GlsmRedirectAgent;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

public final class GlsmDx12OutOfFrameExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        GlsmDx12HeadlessRig.boot();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmDx12HeadlessRig.endFrame();
        assertFalse(GlsmDx12HeadlessRig.isFrameOpen(), "test must start with no active frame");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlsmDx12HeadlessRig.endFrame();
        GlsmRedirectAgent.assertNoFailures();
    }
}
