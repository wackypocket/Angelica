package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What each limit reports beside the least OpenGL 4.6 allows, from glemu/scripts/conformance-required-limits.tsv. */
final class GlRequiredLimits {
    private GlRequiredLimits() {}

    static int constant(String name) {
        try {
            return Class.forName("org.lwjgl.opengl.GL46C").getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** The limits under what the table requires, each as name, value and what is required. */
    static List<String> shortfalls(Path report) throws IOException {
        final List<String> out = new ArrayList<>();
        Files.createDirectories(report.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(report))) {
            for (String line : Files.readAllLines(Path.of("../glemu/scripts/conformance-required-limits.tsv"))) {
                if (line.startsWith("#")) continue;
                final String[] parts = line.split("\t");
                final int least = Integer.parseInt(parts[1]);
                GL11.glGetError();
                final int value = GL11.glGetInteger(constant(parts[0]));
                final int error = GL11.glGetError();
                final String row = parts[0] + "\t" + (error == GL11.GL_NO_ERROR ? value : "error 0x" + Integer.toHexString(error)) + "\t" + least;
                w.println(row);
                if (error != GL11.GL_NO_ERROR || value < least) out.add(row);
            }
        }
        return out;
    }
}
