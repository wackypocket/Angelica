package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.compat.GlFunctions;
import com.gtnewhorizons.angelica.glemu.compat.Lwjgl3GLCapabilitiesShim;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks every native function of core GL and the extensions LWJGL knows. Each must reach something, with its exact C signature, since the
 * stubs read their arguments by that signature.
 */
@Tag("glsm-native")
class GlFunctionCoverageTest {

    /** The native functions of every extension LWJGL knows, by the name LWJGL asks for them. */
    private static Map<String, Method> extensionFunctions() throws Exception {
        final Map<String, Method> out = new TreeMap<>();
        final java.nio.file.Path jar = java.nio.file.Path.of(GL11.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var e = file.entries(); e.hasMoreElements(); ) {
                final String entry = e.nextElement().getName();
                if (!entry.startsWith("org/lwjgl/opengl/") || !entry.endsWith(".class") || entry.contains("$")) continue;
                final String simple = entry.substring("org/lwjgl/opengl/".length(), entry.length() - ".class".length());
                if (!simple.matches("(ARB|EXT|KHR|NV|NVX|AMD|APPLE|INTEL|MESA|OVR|GREMEDY).*")) continue;
                for (Method m : Class.forName("org.lwjgl.opengl." + simple, false, GlFunctionCoverageTest.class.getClassLoader()).getDeclaredMethods()) {
                    if (Modifier.isNative(m.getModifiers())) out.put(m.getName().startsWith("ngl") ? "gl" + m.getName().substring(3) : m.getName(), m);
                }
            }
        }
        return out;
    }

    private static final String[] VERSIONS = {"11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41", "42", "43", "44", "45", "46"};

    private static Map<String, Method> coreFunctions() throws ClassNotFoundException {
        final Map<String, Method> out = new TreeMap<>();
        for (String version : VERSIONS) {
            for (String suffix : new String[] {"", "C"}) {
                final Class<?> c;
                try {
                    c = Class.forName("org.lwjgl.opengl.GL" + version + suffix, false, GlFunctionCoverageTest.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isNative(m.getModifiers())) continue;
                    out.put(m.getName().startsWith("ngl") ? "gl" + m.getName().substring(3) : m.getName(), m);
                }
            }
        }
        return out;
    }

    @Test
    void everyImplementedFunctionHasItsExactCSignature() throws ClassNotFoundException {
        assertSignaturesMatch(coreFunctions());
    }

    @Test
    void everyCoreFunctionReachesSomething() throws ClassNotFoundException {
        final Set<String> unimplemented = new TreeSet<>();
        for (String name : coreFunctions().keySet()) {
            if (GlFunctions.target(name) == null) unimplemented.add(name);
        }
        assertTrue(unimplemented.isEmpty(), () -> "No-ops on the native backends: " + unimplemented);
    }

    /** LWJGL's class for an extension: GL_ARB_shader_objects is ARBShaderObjects. */
    private static Class<?> extensionClass(String extension) {
        final String[] parts = extension.substring(3).split("_");
        final String[] acronyms = {"GPU", "ES2", "ES3", "ES31", "ES32", "RGB", "RGTC", "BPTC", "ASTC", "SRGB", "S3TC", "LOD", "NV", "ARB", "EXT", "KHR", "AMD", "INTEL"};
        final StringBuilder name = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            final String part = parts[i];
            String word = Character.toUpperCase(part.charAt(0)) + part.substring(1);
            for (String acronym : acronyms) {
                if (part.equalsIgnoreCase(acronym)) word = acronym;
                else if (part.toUpperCase().startsWith(acronym) && part.length() > acronym.length() && Character.isDigit(part.charAt(acronym.length()))) word = acronym + part.substring(acronym.length());
            }
            name.append(word);
        }
        try {
            return Class.forName("org.lwjgl.opengl." + name, false, GlFunctionCoverageTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Test
    void everyAdvertisedExtensionFunctionReachesSomething() throws Exception {
        final java.lang.reflect.Field field = GlBase.class.getDeclaredField("EXTENSIONS");
        field.setAccessible(true);
        final Set<String> unimplemented = new TreeSet<>();
        for (String extension : (String[]) field.get(null)) {
            final Class<?> c = extensionClass(extension);
            if (c == null) continue; // an extension that only adds GLSL has no class
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isNative(m.getModifiers())) continue;
                final String name = m.getName().startsWith("ngl") ? "gl" + m.getName().substring(3) : m.getName();
                if (GlFunctions.target(name) == null) unimplemented.add(extension + ":" + name);
            }
        }
        assertTrue(unimplemented.isEmpty(), () -> "No-ops despite being advertised: " + unimplemented);
    }

    @Test
    void everyImplementedExtensionFunctionHasItsExactCSignature() throws Exception {
        assertSignaturesMatch(extensionFunctions());
    }

    @Test
    void lwjglHasAnAddressForEveryFunction() throws Exception {
        final List<String> missing = new ArrayList<>();
        final GLCapabilities caps = Lwjgl3GLCapabilitiesShim.create(Set.of("OpenGL11"));
        for (Field f : GLCapabilities.class.getFields()) {
            if (f.getType() == long.class && !Modifier.isStatic(f.getModifiers()) && f.getLong(caps) == 0L) missing.add(f.getName());
        }
        assertTrue(missing.isEmpty(), () -> missing.size() + " functions have no address: " + missing);
    }

    private static void assertSignaturesMatch(Map<String, Method> functions) {
        final List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Method> e : functions.entrySet()) {
            final MethodHandle target = GlFunctions.target(e.getKey());
            if (target == null) continue;
            final Method m = e.getValue();
            if (!Arrays.equals(m.getParameterTypes(), target.type().parameterArray()) || m.getReturnType() != target.type().returnType()) {
                wrong.add(e.getKey() + " is " + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType() + ", bound as " + target.type());
            }
        }
        assertTrue(wrong.isEmpty(), () -> "Stubs would read garbage for:\n" + String.join("\n", wrong));
    }
}
