package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL46C;

/** The pipeline statistics queries, counted from the draws made, as not every device can count them. GL lets most of them run over what the work strictly needs. */
final class PipelineStatistics {
    private final Int2LongOpenHashMap counted = new Int2LongOpenHashMap();

    static boolean target(int target) {
        return switch (target) {
            case GL46C.GL_VERTICES_SUBMITTED, GL46C.GL_PRIMITIVES_SUBMITTED, GL46C.GL_VERTEX_SHADER_INVOCATIONS, GL46C.GL_TESS_CONTROL_SHADER_PATCHES, GL46C.GL_TESS_EVALUATION_SHADER_INVOCATIONS,
                 GL46C.GL_GEOMETRY_SHADER_INVOCATIONS, GL46C.GL_GEOMETRY_SHADER_PRIMITIVES_EMITTED, GL46C.GL_FRAGMENT_SHADER_INVOCATIONS, GL46C.GL_COMPUTE_SHADER_INVOCATIONS,
                 GL46C.GL_CLIPPING_INPUT_PRIMITIVES, GL46C.GL_CLIPPING_OUTPUT_PRIMITIVES -> true;
            default -> false;
        };
    }

    long value(int target) {
        return counted.get(target);
    }

    /** {@code deviceCountsGeometry}: what a geometry shader the device runs emits is being counted there, so is not guessed here. */
    void draw(GlState st, ProgramBase<?> program, int mode, int count, int instances, boolean deviceCountsGeometry) {
        if (st.conditionalSkip || count <= 0 || instances <= 0) return;
        final long vertices = (long) count * instances;
        final long primitives = mode == GL40.GL_PATCHES ? count / Math.max(1, st.patchVertices) * (long) instances : GlTransformFeedback.primitivesIn(mode, count) * instances;
        counted.addTo(GL46C.GL_VERTICES_SUBMITTED, vertices);
        counted.addTo(GL46C.GL_PRIMITIVES_SUBMITTED, primitives);
        counted.addTo(GL46C.GL_VERTEX_SHADER_INVOCATIONS, vertices);
        if (program != null && program.stageSources.containsKey(GL40.GL_TESS_CONTROL_SHADER)) counted.addTo(GL46C.GL_TESS_CONTROL_SHADER_PATCHES, primitives);
        if (program != null && program.stageSources.containsKey(GL40.GL_TESS_EVALUATION_SHADER)) counted.addTo(GL46C.GL_TESS_EVALUATION_SHADER_INVOCATIONS, primitives);
        if (program != null && program.stageSources.containsKey(GL32.GL_GEOMETRY_SHADER)) {
            final int invocations = program.geometry != null ? Math.max(1, program.geometry.invocations) : 1;
            counted.addTo(GL46C.GL_GEOMETRY_SHADER_INVOCATIONS, primitives * invocations);
            // Where the stage is emulated, what it emitted is reported once it has run.
            if (program.geometry == null && !deviceCountsGeometry) counted.addTo(GL46C.GL_GEOMETRY_SHADER_PRIMITIVES_EMITTED, primitives * invocations);
        }
        counted.addTo(GL46C.GL_CLIPPING_INPUT_PRIMITIVES, primitives);
        counted.addTo(GL46C.GL_CLIPPING_OUTPUT_PRIMITIVES, primitives);
        if (program == null || program.stageSources.containsKey(GL20.GL_FRAGMENT_SHADER)) counted.addTo(GL46C.GL_FRAGMENT_SHADER_INVOCATIONS, primitives);
    }

    void emitted(long primitives) {
        counted.addTo(GL46C.GL_GEOMETRY_SHADER_PRIMITIVES_EMITTED, primitives);
    }

    void dispatch(ProgramBase<?> program, long groups) {
        if (program != null) counted.addTo(GL46C.GL_COMPUTE_SHADER_INVOCATIONS, groups * program.localX * program.localY * program.localZ);
    }
}
