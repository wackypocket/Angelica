package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.nativetest.TestBackend;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;

/** What native backend tests look at beyond the GLSM rig. */
public final class NativeGlsmRig {
    private NativeGlsmRig() {}

    public static GlBase<?, ?, ?> gl() {
        return TestBackend.activeGl();
    }

    /** Runs a body on a new thread, as a thread holding a shared context when asked, and fails the caller if it fails. */
    public static void onAnotherThread(boolean holdsContext, Runnable body) throws InterruptedException {
        final Throwable[] failure = new Throwable[1];
        final Thread thread = new Thread(() -> {
            try {
                if (holdsContext) gl().st();
                body.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                gl().releaseThreadState();
            }
        });
        thread.start();
        thread.join();
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    public static long renderPasses() {
        return Reflect.<Long>get(Reflect.get(gl(), "renderer"), "passes");
    }

    public static long bufferTransfers() {
        final Object buffers = Reflect.get(gl(), "buffers");
        return Reflect.<Long>get(buffers, "renames") + Reflect.<Long>get(buffers, "inlineCopies") + Reflect.<Long>get(buffers, "hoistedCopies");
    }

    public static TexBase texture(int id) {
        return Reflect.invoke(Reflect.get(gl(), "textures"), "get", new Class<?>[] {int.class}, id);
    }

    public static long textureImage(int id) {
        final TexBase t = texture(id);
        return t == null ? 0L : t.image;
    }
}
