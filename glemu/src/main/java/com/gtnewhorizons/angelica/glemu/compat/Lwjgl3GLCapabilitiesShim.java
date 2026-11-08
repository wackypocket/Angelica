package com.gtnewhorizons.angelica.glemu.compat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.FunctionProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** {@link GLCapabilities} shim for Lwjgl3ify-Aware mods */
public final class Lwjgl3GLCapabilitiesShim {

    private static final Logger LOG = LogManager.getLogger("Angelica/GPU");

    private static final Pattern CONDITION = Pattern.compile("GL_[A-Za-z0-9]+_[A-Za-z0-9_]+");

    private static GLCapabilities everything;

    private Lwjgl3GLCapabilitiesShim() {}

    public static void installOnCurrentThread(Set<String> advertised) {
        GL.setCapabilities(create(advertised));
        LOG.info("Installed fabricated LWJGL3 GLCapabilities on {} ({} capability names advertised)", Thread.currentThread().getName(), advertised.size());
    }

    public static void clearOnCurrentThread() {
        GL.setCapabilities(null);
        LOG.info("Cleared fabricated LWJGL3 GLCapabilities on {}", Thread.currentThread().getName());
    }

    /**
     * Aware mods probe extension entry points through {@code GL.getFunctionProvider()}, which is null with no GL context.
     * Every name resolves to 0 so those probes see the extension as absent and use the core path, which GLSM routes.
     */
    public static void installFunctionProvider() {
        if (GL.getFunctionProvider() != null) return;
        GL.create(name -> 0L);
        LOG.info("Installed null-address LWJGL3 GL function provider");
    }

    public static GLCapabilities create(Set<String> advertised) {
        final GLCapabilities caps = construct(advertised, true);
        giveEveryFunctionAnAddress(caps);
        return caps;
    }

    private static GLCapabilities construct(Set<String> extensions, boolean forwardCompatible) {
        final IntFunction<PointerBuffer> bufferFactory = BufferUtils::createPointerBuffer;
        try {
            final Constructor<GLCapabilities> ctor = GLCapabilities.class.getDeclaredConstructor(FunctionProvider.class, Set.class, boolean.class, IntFunction.class);
            ctor.setAccessible(true);
            return ctor.newInstance(GlFunctions.INSTANCE, extensions, forwardCompatible, bufferFactory);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GLCapabilities(FunctionProvider, Set, boolean, IntFunction) is gone", e);
        }
    }

    /**
     * LWJGL only loads the functions of what is advertised, and aborts the JVM when a call finds an empty slot. A mod may call
     * anything, so the slots and fields come from capabilities built with everything advertised, while the flags mods read stay truthful.
     */
    private static void giveEveryFunctionAnAddress(GLCapabilities caps) {
        final GLCapabilities everything = everything();
        final PointerBuffer to = caps.getAddressBuffer(), from = everything.getAddressBuffer();
        for (int i = 0; i < from.capacity(); i++) to.put(i, from.get(i));
        try {
            for (Field f : GLCapabilities.class.getFields()) {
                if (f.getType() != long.class || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.setLong(caps, f.getLong(everything));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not fill in GLCapabilities", e);
        }
    }

    /** The flags, and also the names only ever tested as a function's condition, such as GL_ARB_compatibility, which have no field. */
    private static Set<String> capabilityNames() {
        final Set<String> names = new HashSet<>();
        for (Field f : GLCapabilities.class.getFields()) {
            if (f.getType() == boolean.class) names.add(f.getName());
        }
        try (InputStream in = GLCapabilities.class.getResourceAsStream("GLCapabilities.class")) {
            if (in != null) {
                final Matcher constant = CONDITION.matcher(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                while (constant.find()) names.add(constant.group());
            }
        } catch (IOException e) {
            LOG.warn("Could not read the capability names GLCapabilities tests", e);
        }
        return names;
    }

    /**
     * LWJGL has fields for some functions that no capability check ever loads, such as glGetnMapdv: a call would find the slot empty and
     * abort the JVM. The constructor's bytecode says which slot each field reads.
     */
    private static void fillSlotsNoNameReaches(GLCapabilities caps) {
        final PointerBuffer slots = caps.getAddressBuffer();
        final Map<String, Integer> slotOfField = slotsOfFields();
        try {
            for (Field f : GLCapabilities.class.getFields()) {
                if (f.getType() != long.class || Modifier.isStatic(f.getModifiers()) || f.getLong(caps) != 0L) continue;
                final long address = GlFunctions.INSTANCE.address(f.getName());
                f.setAccessible(true);
                f.setLong(caps, address);
                final Integer slot = slotOfField.get(f.getName());
                if (slot != null) slots.put(slot, address);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not fill in GLCapabilities", e);
        }
    }

    private static Map<String, Integer> slotsOfFields() {
        final Map<String, Integer> out = new HashMap<>();
        try (InputStream in = GLCapabilities.class.getResourceAsStream("GLCapabilities.class")) {
            if (in == null) return out;
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("<init>")) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        private int lastInt;

                        @Override public void visitIntInsn(int opcode, int operand) {
                            lastInt = operand;
                        }

                        @Override public void visitInsn(int opcode) {
                            if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) lastInt = opcode - Opcodes.ICONST_0;
                        }

                        @Override public void visitLdcInsn(Object value) {
                            if (value instanceof Integer i) lastInt = i;
                        }

                        @Override public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                            if (opcode == Opcodes.PUTFIELD && fieldDescriptor.equals("J")) out.put(field, lastInt);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read which slot each GLCapabilities field uses", e);
        }
        return out;
    }

    private static synchronized GLCapabilities everything() {
        if (everything == null) {
            everything = construct(capabilityNames(), false);
            fillSlotsNoNameReaches(everything);
        }
        return everything;
    }
}
