package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import com.gtnewhorizons.angelica.glemu.compat.Lwjgl3GLCapabilitiesShim;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.nativetest.TestBackend;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import org.lwjgl.opengl.GL43;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the GLSM rig needs from a native backend. */
final class GlsmRigHooks {
    private GlsmRigHooks() {}

    static void bootBackend(int size) {
        DeviceRequirement.require(TestBackend.initPlatform(), "windowing system unavailable");
        final GpuCaps gpu = TestBackend.newDevice();
        DeviceRequirement.require(gpu != null, "no " + TestBackend.NAME + " device");
        final GlBase<?, ?, ?> gl = TestBackend.newGl(gpu);
        gl.attachOffscreen(size, size);
        TestBackend.adopt(gpu, gl);
        // Installed here as well so GLStateManager's capability object, made before the backend starts, reads the real flags.
        Lwjgl3GLCapabilitiesShim.installOnCurrentThread(GlBase.advertisedCapabilities(gpu));
    }

    static boolean isSelected(RenderBackend backend) {
        return TestBackend.isSelected(backend);
    }

    static void targetCreated(int colorTexture) {
    }

    static void installFinalTarget(int width, int height) {
        TestBackend.activeGl().attachOffscreen(width, height);
    }

    static void dropFinalTarget(int size) {
        TestBackend.activeGl().attachOffscreen(size, size);
    }

    static NativeLwjglService createService() {
        return TestBackend.createService();
    }

    static boolean textureAllocated(int id) {
        return NativeGlsmRig.textureImage(id) != 0L;
    }

    static int textureWidth(int id) {
        return NativeGlsmRig.texture(id).width;
    }

    static int textureHeight(int id) {
        return NativeGlsmRig.texture(id).height;
    }

    static int boundSampler(int unit) {
        return NativeGlsmRig.gl().st().samplers[unit];
    }

    static long bufferTransfers() {
        return NativeGlsmRig.bufferTransfers();
    }

    static long renderPasses() {
        return NativeGlsmRig.renderPasses();
    }

    /** The native backends need nothing set up for this. */
    static Runnable stepConstantsPerInstance() {
        return () -> {};
    }

    static long textureHandle(int id) {
        return NativeGlsmRig.textureImage(id);
    }

    static int textureLevels(int id) {
        return NativeGlsmRig.texture(id).levels;
    }

    static Object textureFormat(int id) {
        return NativeGlsmRig.texture(id).format;
    }

    static int boundStorageBuffer(int slot) {
        return BackendManager.RENDER_BACKEND.getIntegerIndexed(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, slot);
    }

    static int maxDrawBuffers() {
        return GlState.MAX_DRAW_BUFFERS;
    }

    static void assertBlendFactors(int buffer, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, String when) {
        final GlState st = NativeGlsmRig.gl().st();
        assertEquals(srcRgb, st.blendSrcRgb[buffer], when + ": src rgb of buffer " + buffer);
        assertEquals(dstRgb, st.blendDstRgb[buffer], when + ": dst rgb of buffer " + buffer);
        assertEquals(srcAlpha, st.blendSrcA[buffer], when + ": src alpha of buffer " + buffer);
        assertEquals(dstAlpha, st.blendDstA[buffer], when + ": dst alpha of buffer " + buffer);
    }

    static boolean comparesHighStencilReferences() {
        return true;
    }
}
