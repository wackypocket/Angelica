package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPhasesTest {
    @Test
    void aMainWithoutABarrierStaysWhole() {
        final ControlPhases phases = ControlPhases.of("void main() { int a = 1; x = a; }", 4);
        assertEquals(1, phases.count());
        assertTrue(phases.source().contains("int a = 1;"));
    }

    @Test
    void aBarrierCutsMainAndLocalsOutliveTheCut() {
        final ControlPhases phases = ControlPhases.of("void main() {\n  int a = 1;\n  x[a_gl_InvocationID] = a;\n  barrier();\n  y = a + 1;\n}", 4);
        assertEquals(2, phases.count());
        assertTrue(phases.source().contains("int angelica_local_a[4];"));
        assertTrue(phases.source().contains("angelica_local_a[a_gl_InvocationID] = 1;"));
        assertTrue(phases.source().contains("y = angelica_local_a[a_gl_InvocationID] + 1;"));
        assertFalse(phases.source().contains("barrier"));
    }

    @Test
    void anIfWithItsElseIsOneStatement() {
        final ControlPhases phases = ControlPhases.of("void main() {\n  if (c) { z = 1; } else { z = 2; }\n  barrier();\n  z = 3;\n}", 2);
        assertEquals(2, phases.count());
        assertTrue(phases.source().indexOf("else") < phases.source().indexOf("angelica_tc_main_1"));
    }
}
