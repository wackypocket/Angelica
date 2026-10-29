package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class Dxc {

    public static final String JAR_NAME = "angelica-dxc";

    private static final Logger LOGGER = LogManager.getLogger("Angelica/DXC");
    private static final String LIB_NAME = "dxcompiler.dll";
    private static final String ARCH = archName(System.getProperty("os.arch", ""));

    private static String path;
    private static boolean located;

    private Dxc() {}

    public static synchronized String locate() {
        if (!located) {
            final boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
            path = windows ? extract(bundledResource(ARCH), new File(SystemProperties.DXC_DIR), ARCH) : null;
            located = true;
        }
        return path;
    }

    static String archName(String osArch) {
        final String arch = osArch.toLowerCase();
        return arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
    }

    static String bundledResource(String arch) {
        return "/windows/" + arch + "/" + LIB_NAME;
    }

    static String extract(String resource, File dir, String arch) {
        final byte[] lib;
        try (InputStream in = Dxc.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            lib = readAll(in);
        } catch (IOException e) {
            LOGGER.warn("DXC: failed reading {}: {}", resource, e.toString());
            return null;
        }

        final File target = new File(dir, "dxcompiler-" + arch + ".dll");
        File tmp = null;
        try {
            if (!target.isFile() || target.length() != lib.length || !Arrays.equals(Files.readAllBytes(target.toPath()), lib)) {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                tmp = File.createTempFile("dxcompiler", ".dll", dir);
                Files.write(tmp.toPath(), lib);
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("DXC: failed extracting to {}: {}", target, e.toString());
            if (tmp != null) tmp.delete();
            return null;
        }
        return target.getAbsolutePath();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        final byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
