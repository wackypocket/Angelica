package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Calls every core function that takes no pointer with arguments GL has an answer for, writing the error each answers. The arguments are
 * nothing a mod would mean, so a real driver and a backend can be held to the same answers without either having to understand the call.
 * glemu/scripts/gen_conformance_checklist.py reads the output.
 */
public final class GlCallProbe {
    private static final String[] VERSIONS = {"11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41"};
    /** Names none of the calls here can reach an object with, so nothing a probe does depends on which names exist. */
    private static final int[] VALUES = {0xBEEF, -1};
    private static final Set<Class<?>> PLAIN = Set.of(int.class, float.class, double.class, boolean.class, short.class, byte.class);

    private GlCallProbe() {}

    private static List<Method> functions() throws ClassNotFoundException {
        final Set<String> seen = new TreeSet<>();
        final List<Method> out = new ArrayList<>();
        for (String version : VERSIONS) {
            for (Method m : Class.forName("org.lwjgl.opengl.GL" + version + "C", false, GlCallProbe.class.getClassLoader()).getDeclaredMethods()) {
                if (!Modifier.isNative(m.getModifiers()) || !m.getName().startsWith("gl")) continue;
                boolean plain = true;
                for (Class<?> c : m.getParameterTypes()) plain &= PLAIN.contains(c);
                if (plain && seen.add(m.getName() + java.util.Arrays.toString(m.getParameterTypes()))) out.add(m);
            }
        }
        out.sort(Comparator.comparing(Method::getName).thenComparing(m -> java.util.Arrays.toString(m.getParameterTypes())));
        return out;
    }

    private static Object arg(Class<?> c, int value) {
        if (c == int.class) return value;
        if (c == float.class) return (float) value;
        if (c == double.class) return (double) value;
        if (c == short.class) return (short) value;
        if (c == byte.class) return (byte) value;
        return value != 0;
    }

    static int drain() {
        int first = GL11.GL_NO_ERROR;
        for (int i = 0; i < 16; i++) {
            final int e = GL11.glGetError();
            if (e == GL11.GL_NO_ERROR) break;
            if (first == GL11.GL_NO_ERROR) first = e;
        }
        return first;
    }

    private static String call(Method m, Object[] args) {
        drain();
        try {
            m.invoke(null, args);
        } catch (InvocationTargetException e) {
            drain();
            return "threw " + e.getCause().getClass().getSimpleName();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return GlConformanceScenarios.errorName(drain());
    }

    /** Calls that take a real driver down, which says nothing about how they should answer. */
    private static Set<String> skipped() throws IOException {
        final Path list = Path.of("../glemu/scripts/conformance-probe-skip.txt");
        return Files.exists(list) ? Set.copyOf(Files.readAllLines(list)) : Set.of();
    }

    public static void run(Path file) throws IOException, ClassNotFoundException {
        Files.createDirectories(file.getParent());
        final Set<String> skipped = skipped();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file), true);
             PrintWriter progress = new PrintWriter(Files.newBufferedWriter(Path.of(file + ".progress")), true)) {
            for (Method m : functions()) {
                final Class<?>[] types = m.getParameterTypes();
                GlConformanceScenarios.reset();
                GlConformanceScenarios.setUp();
                final int[] values = new int[types.length];
                final Object[] args = new Object[types.length];
                record(w, progress, skipped, m, types, values, args);
                for (int i = 0; i < types.length; i++) {
                    for (int value : VALUES) {
                        values[i] = value;
                        record(w, progress, skipped, m, types, values, args);
                    }
                    values[i] = 0;
                }
            }
        }
    }

    private static void record(PrintWriter w, PrintWriter progress, Set<String> skipped, Method m, Class<?>[] types, int[] values, Object[] args) {
        final StringBuilder key = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            args[i] = arg(types[i], values[i]);
            key.append(i == 0 ? "" : ",").append(values[i]);
        }
        final String id = m.getName() + "\t" + key;
        if (skipped.contains(id)) return;
        progress.println(id);
        w.println(id + "\t" + call(m, args));
    }
}
