package com.gtnewhorizons.angelica.arch;

import com.gtnewhorizons.angelica.arch.ArchScan.Access;
import com.gtnewhorizons.angelica.arch.ArchScan.ClassConstant;
import com.gtnewhorizons.angelica.arch.ArchScan.Dir;
import com.gtnewhorizons.angelica.arch.ArchScan.ScannedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native backends are reached only through their gates, which must load on an LWJGL2 launch.
 */
class NativeBackendEncapsulationTest {

    private static final String ROOT = "com.gtnewhorizons.angelica.";
    private static final List<String> MODULES = List.of(ROOT + "glemu.", ROOT + "vulkan.", ROOT + "metal.");
    private static final Set<String> GATES = Set.of(ROOT + "glemu.NativeGate", ROOT + "vulkan.VulkanGate", ROOT + "metal.MetalGate");

    @Test
    void nativeBackendsAreReachedOnlyThroughTheirGates() {
        final List<String> violations = new ArrayList<>();
        for (ScannedClass origin : ArchScan.in(Dir.MAIN, Dir.MIXIN, Dir.GLSM)) {
            for (String reference : origin.references()) {
                if (isBehindAGate(reference)) violations.add("  " + origin.name() + " -> " + reference);
            }
        }
        Collections.sort(violations);
        assertTrue(violations.isEmpty(), "Only the gates are safe to load without LWJGL3:\n" + String.join("\n", violations));
    }

    @ParameterizedTest
    @ValueSource(strings = {"glemu.NativeGate", "vulkan.VulkanGate", "metal.MetalGate"})
    void gateStaticInitIsLwjgl3Free(String gate) {
        final ScannedClass scanned = ArchScan.get(ROOT + gate);
        final List<String> violations = new ArrayList<>();
        for (Access access : scanned.accesses()) {
            if (isStaticInit(access.method()) && isLwjgl3(access.owner())) violations.add("  " + access.target());
        }
        for (ClassConstant constant : scanned.classConstants()) {
            if (isStaticInit(constant.method()) && isLwjgl3(constant.type())) violations.add("  " + constant.type() + ".class");
        }
        assertTrue(violations.isEmpty(), gate + " static init resolves LWJGL3 types:\n" + String.join("\n", violations));
    }

    private static boolean isBehindAGate(String name) {
        return !GATES.contains(name) && MODULES.stream().anyMatch(name::startsWith);
    }

    private static boolean isLwjgl3(String name) {
        return name.startsWith("org.lwjgl.") || name.startsWith("org.lwjglx.") || name.startsWith("me.eigenraven.lwjgl3ify.");
    }

    private static boolean isStaticInit(String method) {
        return method.startsWith("<clinit>") || method.startsWith("lambda$static$");
    }
}
