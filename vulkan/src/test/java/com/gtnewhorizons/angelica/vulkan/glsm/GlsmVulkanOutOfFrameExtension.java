package com.gtnewhorizons.angelica.vulkan.glsm;

import com.gtnewhorizons.angelica.glsm.GlsmRedirectAgent;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

public final class GlsmVulkanOutOfFrameExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        GlsmVulkanHeadlessRig.boot();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        GlsmVulkanHeadlessRig.endFrame();
        assertFalse(GlsmVulkanHeadlessRig.isFrameOpen(), "test must start with no active frame");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        GlsmVulkanHeadlessRig.endFrame();
        GlsmRedirectAgent.assertNoFailures();
    }
}
