package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites shaders for a device without cull distances. A vertex shader's distances become an array it stores at the vertex's index when
 * asked, which CullDraws reads to leave out the primitives GL would cull, and a fragment shader reads them as an ordinary varying.
 */
public final class CullDistanceLowering {
    public static final String ENABLED = "angelica_cull_enabled", BASE = "angelica_cull_base", SPAN = "angelica_cull_span", FIRST_INSTANCE = "angelica_cull_firstInstance";
    /** Under the bindings transform feedback takes from the top. */
    public static final int STORAGE_BINDING = GlState.MAX_BUFFER_BINDINGS - 5;
    /** Below those of geometry and tessellation: which primitives GL culls, as the fragment shader reads them. */
    public static final int CULLED_BINDING = GlState.MAX_BUFFER_BINDINGS - 14;
    public static final String CULLED_FIRST = "angelica_cull_firstPrimitive", CULLED_ENABLED = "angelica_cull_flags";

    private static final String BUILTIN = "gl_CullDistance", LOWERED = "angelica_cullDistance";
    private static final Pattern DECLARATION = Pattern.compile("(?:\\bout\\s+|\\bin\\s+)?\\bfloat\\s+gl_CullDistance\\s*\\[\\s*\\d*\\s*\\]\\s*;");
    private static final Pattern SIZED = Pattern.compile("\\bfloat\\s+gl_CullDistance\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern INDEXED = Pattern.compile("\\bgl_CullDistance\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern NAME = Pattern.compile("\\bgl_CullDistance\\b");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final int MAX = 8;

    private CullDistanceLowering() {}

    public static boolean uses(String source) {
        return NAME.matcher(source).find();
    }

    /** What the shader declares, or else one past the highest index it writes: GLSL only allows a variable index on a declared size. */
    public static int count(String source) {
        final Matcher sized = SIZED.matcher(source);
        if (sized.find()) return Math.min(MAX, Integer.parseInt(sized.group(1)));
        final Matcher indexed = INDEXED.matcher(source);
        int highest = -1;
        while (indexed.find()) highest = Math.max(highest, Integer.parseInt(indexed.group(1)));
        return highest < 0 ? MAX : Math.min(MAX, highest + 1);
    }

    private static String renamed(String source) {
        return NAME.matcher(DECLARATION.matcher(source).replaceAll("")).replaceAll(LOWERED);
    }

    public static String vertex(String source, int count, boolean fragmentReads) {
        final String body = MAIN.matcher(renamed(source)).replaceAll("void angelica_cull_main(");
        final int at = ShaderCompiler.headerEnd(body);
        final String declarations = (fragmentReads ? "out " : "") + "float " + LOWERED + "[" + count + "];\n"
            + "uniform int " + ENABLED + ";\nuniform int " + BASE + ";\nuniform int " + SPAN + ";\nuniform int " + FIRST_INSTANCE + ";\n"
            + "layout(std430, binding = " + STORAGE_BINDING + ") buffer AngelicaCull { float angelica_cull[]; };\n";
        return body.substring(0, at) + declarations + body.substring(at)
            + "\nvoid main() {\n    for (int i = 0; i < " + count + "; i++) " + LOWERED + "[i] = 1.0;\n    angelica_cull_main();\n    if (" + ENABLED + " != 0) {\n"
            + "        int at = ((gl_InstanceID - " + FIRST_INSTANCE + ") * " + SPAN + " + gl_VertexID - " + BASE + ") * " + count + ";\n"
            + "        for (int i = 0; i < " + count + "; i++) angelica_cull[at + i] = " + LOWERED + "[i];\n    }\n}\n";
    }

    public static String fragment(String source, int count) {
        final String body = renamed(source);
        final int at = ShaderCompiler.headerEnd(body);
        return body.substring(0, at) + "in float " + LOWERED + "[" + count + "];\n" + body.substring(at);
    }

    /**
     * Whether a fragment shader can drop the fragments of a culled primitive instead: one taking early tests would have passed depth and stencil
     * by then.
     */
    public static boolean discards(String fragment) {
        return !fragment.contains("early_fragment_tests") && MAIN.matcher(fragment).find();
    }

    /** Drops every fragment of a primitive a compute pass found culled, before anything else the shader does. */
    public static String withCulledDiscarded(String fragment) {
        final int at = ShaderCompiler.headerEnd(fragment);
        final String withDeclarations = fragment.substring(0, at) + "layout(std430, binding = " + CULLED_BINDING + ") readonly buffer AngelicaCulled { uint angelica_culled[]; };\n"
            + "uniform int " + CULLED_FIRST + ";\nuniform int " + CULLED_ENABLED + ";\n" + fragment.substring(at);
        final Matcher main = MAIN.matcher(withDeclarations);
        if (!main.find()) return fragment;
        final int open = withDeclarations.indexOf('{', main.end());
        if (open < 0) return fragment;
        return withDeclarations.substring(0, open + 1) + "\n    if (" + CULLED_ENABLED + " != 0 && angelica_culled[" + CULLED_FIRST + " + gl_PrimitiveID] != 0u) discard;"
            + withDeclarations.substring(open + 1);
    }

    /**
     * Marks each primitive GL culls: one of whose distances is negative at all its vertices. A primitive names up to four vertices, as a quad is
     * culled whole, the rest of its slots past the end.
     */
    public static final String FLAGS = """
        #version 430 core
        layout(local_size_x = 64) in;
        layout(std430, binding = %d) readonly buffer AngelicaCull { float angelica_cull[]; };
        layout(std430, binding = %d) readonly buffer AngelicaCorners { uint angelica_corners[]; };
        layout(std430, binding = %d) writeonly buffer AngelicaCulled { uint angelica_culled[]; };
        uniform int angelica_cull_primitives, angelica_cull_instances, angelica_cull_span, angelica_cull_count;
        void main() {
            int p = int(gl_GlobalInvocationID.x);
            if (p >= angelica_cull_primitives * angelica_cull_instances) return;
            int instance = p / angelica_cull_primitives, q = p - instance * angelica_cull_primitives;
            uint culled = 0u;
            for (int plane = 0; plane < angelica_cull_count; plane++) {
                bool outside = true;
                for (int c = 0; c < 4; c++) {
                    uint corner = angelica_corners[q * 4 + c];
                    if (corner == 0xFFFFFFFFu) break;
                    if (angelica_cull[(instance * angelica_cull_span + int(corner)) * angelica_cull_count + plane] >= 0.0) outside = false;
                }
                if (outside) culled = 1u;
            }
            angelica_culled[p] = culled;
        }
        """;
}
