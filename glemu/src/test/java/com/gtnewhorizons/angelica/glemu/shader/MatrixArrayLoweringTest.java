package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixArrayLoweringTest {
    @Test
    void anOutputIsCopiedToItsColumnsAsMainEnds() {
        final String lowered = MatrixArrayLowering.lower("out mat2x3 m[2];\nvoid main() { m[0] = x; }");
        assertTrue(lowered.contains("out vec3 angelica_columns_m[4];"));
        assertTrue(lowered.contains("mat2x3 m[2];"));
        assertTrue(lowered.indexOf("m[0] = x;") < lowered.indexOf("angelica_columns_m[0] = m[0][0];"));
        assertTrue(lowered.contains("angelica_columns_m[3] = m[1][1];"));
    }

    @Test
    void anInputIsCopiedFromItsColumnsAsMainStarts() {
        final String lowered = MatrixArrayLowering.lower("flat in mat2 m[2];\nvoid main() { x = m[1]; }");
        assertTrue(lowered.contains("flat in vec2 angelica_columns_m[4];"));
        assertTrue(lowered.indexOf("m[1][1] = angelica_columns_m[3];") < lowered.indexOf("x = m[1];"));
    }

    @Test
    void doubleMatricesAreLeftToTheDoublePass() {
        assertFalse(MatrixArrayLowering.applies("flat in dmat2 m[2];\nvoid main() {}"));
        assertFalse(MatrixArrayLowering.applies("in Data {\n  flat dmat3x2 v[2];\n};\nvoid main() {}"));
    }

    @Test
    void aVertexInputSaysWhatItWas() {
        final List<MatrixArrayLowering.Flattened> inputs = MatrixArrayLowering.inputs("in mat3x4 m[5];\nout mat2 o[2];\nvoid main() {}");
        assertEquals(List.of(new MatrixArrayLowering.Flattened("m", 3, 4, 5)), inputs);
    }

    @Test
    void aBlockWithoutAnInstanceNameIsLoweredToo() {
        final String source = "in Data {\n  flat mat2 v[2];\n};\nvoid main() { x = v[1]; }";
        assertTrue(MatrixArrayLowering.applies(source));
        final String lowered = MatrixArrayLowering.lower(source);
        assertTrue(lowered.contains("flat vec2 angelica_columns_v[4];"));
        assertTrue(lowered.contains("mat2x2 v[2];"));
        assertTrue(lowered.indexOf("v[0][0] = angelica_columns_v[0];") < lowered.indexOf("x = v[1];"));
    }

    @Test
    void whatIsNotAMatrixArrayIsLeftAlone() {
        assertFalse(MatrixArrayLowering.applies("in mat4 m;\nin vec2 v[3];\nvoid main() {}"));
        assertFalse(MatrixArrayLowering.applies("uniform mat4 m[2];\nvoid main() {}"));
    }
}
