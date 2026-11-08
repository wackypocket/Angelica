package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GlsmRedirectAgent;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

abstract class GlsmRigExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        GlsmHeadlessRig.boot();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlsmHeadlessRig.endFrame();
        GlsmRedirectAgent.assertNoFailures();
    }
}
