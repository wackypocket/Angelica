package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GlsmRedirectAgent;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

public final class GlsmMetalOutOfFrameExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        GlsmMetalHeadlessRig.boot();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmMetalHeadlessRig.endFrame();
        assertFalse(GlsmMetalHeadlessRig.isFrameOpen(), "test must start with no active frame");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlsmMetalHeadlessRig.endFrame();
        GlsmRedirectAgent.assertNoFailures();
    }
}
