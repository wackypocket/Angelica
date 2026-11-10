package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import java.io.IOException;
import java.nio.file.Path;

/** The way into the probes for tests in other packages. */
public final class GlConformanceProbes {
    private GlConformanceProbes() {}

    public static void modPath(Path file, Runnable between, boolean driver) throws IOException {
        GlConformanceScenariosModPath.run(file, between, driver);
    }
}
