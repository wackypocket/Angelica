package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

/**
 * Rewrites a program with a geometry shader for a device that has none. Its vertex shader runs first with rasterization off, storing what it
 * outputs at the vertex's index. The geometry shader, as a compute shader, reads those per primitive and stores what it emits. The same
 * vertex shader then runs on those emitted vertices, handing them to the fragment shader. GeometryDraws runs the three in turn.
 * Tessellation goes the same way, its two shaders as compute shaders around the Tessellator, which runs on the levels they read back.
 */
public final class GeometryLowering {
    public static final String LAYER_LIMIT = "angelica_gs_layer_limit";
    public static final String STAGE = "angelica_gs_stage", BASE = "angelica_gs_base", SPAN = "angelica_gs_span", FIRST_INSTANCE = "angelica_gs_firstInstance";
    public static final String PRIMITIVES_PER_INSTANCE = "angelica_gs_primitivesPerInstance";
    /** Under those of transform feedback and cull distances, which count down from the top. */
    public static final int VERTICES_BINDING = GlState.MAX_BUFFER_BINDINGS - 6, PRIMITIVES_BINDING = GlState.MAX_BUFFER_BINDINGS - 7,
        EMITTED_BINDING = GlState.MAX_BUFFER_BINDINGS - 8, INDICES_BINDING = GlState.MAX_BUFFER_BINDINGS - 9, CONTROL_BINDING = GlState.MAX_BUFFER_BINDINGS - 10,
        PATCH_BINDING = GlState.MAX_BUFFER_BINDINGS - 11, COORDINATE_BINDING = GlState.MAX_BUFFER_BINDINGS - 12;
    public static final int LEVELS_BINDING = GlState.MAX_BUFFER_BINDINGS - 13;
    public static final String PATCH_VERTICES = "angelica_gs_patchVertices", CONTROL_VERTICES = "angelica_gs_controlVertices", DEFAULT_OUTER = "angelica_gs_defaultOuter", DEFAULT_INNER = "angelica_gs_defaultInner";
    /** Bytes a patch's levels take at the start of its record, more than they need so it stays aligned. */
    private static final int LEVELS_BYTES = 32;
    private static final int CLIP_SLOTS = 8;

    /** What the rewrite makes, or why it could not: a problem GL would report when linking. */
    public record Result(String vertex, String fragment, String compute, String control, String evaluation, long vertexBytes, long emittedBytes, long controlBytes, long patchBytes, long evaluationBytes,
                         int indicesPerRun, int drawMode, int emittedStride, Map<String, Integer> emittedOffsets, boolean evaluationCulls, String problem) {
        static Result failed(String problem) {
            return new Result(null, null, null, null, null, 0L, 0L, 0L, 0L, 0L, 0, 0, 0, Map.of(), false, problem);
        }
    }

    /** The bytes of a field by std430, in the record's order, and how far apart records are; -1 where a struct field leaves it unknown. */
    private static int layoutOf(List<Field> fields, Map<String, Integer> offsets) {
        int at = 0, largest = 4;
        for (Field f : fields) {
            final int[] size = scalarSize(f.type);
            if (size == null) return -1;
            final int alignment = size[0], element = size[1];
            final long count = elements(f.inner, -1L);
            if (count < 0) return -1;
            at = (at + alignment - 1) & -alignment;
            offsets.put(f.source, at);
            at += (count > 1 ? ((element + alignment - 1) & -alignment) * (int) count : element);
            largest = Math.max(largest, alignment);
        }
        return (at + largest - 1) & -largest;
    }

    private static int[] scalarSize(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new int[] {4, 4};
            case "vec2", "ivec2", "uvec2", "bvec2", "double" -> new int[] {8, 8};
            case "vec3", "ivec3", "uvec3", "bvec3" -> new int[] {16, 12};
            case "vec4", "ivec4", "uvec4", "bvec4", "dvec2" -> new int[] {16, 16};
            case "dvec3" -> new int[] {32, 24};
            case "dvec4" -> new int[] {32, 32};
            case "mat2", "mat2x2" -> new int[] {8, 16};
            case "mat3", "mat3x3" -> new int[] {16, 48};
            case "mat4", "mat4x4" -> new int[] {16, 64};
            default -> null;
        };
    }

    /** Strips are stored as lists, as that is what lets one primitive of a strip go when its cull distances say so. */
    private static int drawMode(int outputMode) {
        return outputMode == GL11.GL_POINTS ? GL11.GL_POINTS : outputMode == GL11.GL_LINE_STRIP ? GL11.GL_LINES : GL11.GL_TRIANGLES;
    }

    private static int indicesPerRun(int outputMode, int maxVertices) {
        return outputMode == GL11.GL_POINTS ? maxVertices : outputMode == GL11.GL_LINE_STRIP ? Math.max(0, maxVertices - 1) * 2 : Math.max(0, maxVertices - 2) * 3;
    }

    private record Field(String type, String name, String inner, String source) {}

    private static final class Io {
        final String qualifiers, type, name, inner, blockType;
        final int location;
        final List<Io> members;

        Io(String qualifiers, String type, String name, String inner, String blockType, int location, List<Io> members) {
            this.qualifiers = qualifiers;
            this.type = type;
            this.name = name;
            this.inner = inner;
            this.blockType = blockType;
            this.location = location;
            this.members = members;
        }

        boolean isBlock() {
            return blockType != null;
        }

        boolean isPatch() {
            return qualifiers.contains("patch");
        }
    }

    private static final Pattern LAYOUT = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)");
    private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern STAGE_LAYOUT = Pattern.compile("^\\s*(?:layout\\s*\\([^)]*\\)\\s*)+(in|out)\\s*;\\s*$");
    private static final Set<String> QUALIFIERS = Set.of("in", "out", "patch", "flat", "smooth", "noperspective", "centroid", "sample", "invariant", "precise", "highp", "mediump", "lowp");
    private static final Set<String> BUILTIN_VERTEX = Set.of("gl_Position", "gl_PointSize", "gl_ClipDistance", "gl_CullDistance", "gl_TessLevelOuter", "gl_TessLevelInner");
    private static final Pattern BUILTIN_NAME = Pattern.compile("\\b(gl_Position|gl_PointSize|gl_ClipDistance|gl_CullDistance|gl_in|gl_Layer|gl_ViewportIndex|gl_PrimitiveIDIn|gl_PrimitiveID|gl_InvocationID|gl_out|gl_TessCoord|gl_TessLevelOuter|gl_TessLevelInner|gl_PatchVerticesIn)\\b");
    private static final Pattern STAGE_ONLY = Pattern.compile("^\\s*(?:invariant|precise)\\s+gl_\\w+\\s*;\\s*$");

    private GeometryLowering() {}

    /** The geometry and the two tessellation sources may be null. Without a control shader, one that hands on the vertices is made here. */
    public static Result lower(String vertexSource, String controlSource, String evaluationSource, String geometrySource, String fragmentSource, int controlVertices,
                               int verticesIn, int maxVertices, int invocations, int outputMode, boolean firstConvention, boolean stagesAlone) {
        final List<String> vs = parsed(vertexSource, GL20.GL_VERTEX_SHADER);
        final List<Io> vsOut = interfaceOf(vs, "out", false);
        final List<Field> vertexFields = new ArrayList<>(builtinFields());
        final Map<String, Field> vertexByKey = new HashMap<>();
        final Map<Integer, Field> vertexByLocation = new HashMap<>();
        for (Io out : vsOut) addFields(out, vertexFields, vertexByKey, vertexByLocation);

        final List<String> extras = new ArrayList<>();
        String control = null, evaluation = null;
        long controlBytes = 0L, patchBytes = 0L, evaluationBytes = 0L;
        List<Field> producerFields = vertexFields;
        Map<String, Field> producerByKey = vertexByKey;
        Map<Integer, Field> producerByLocation = vertexByLocation;
        List<String> producer = vs;
        List<Io> finalOut = vsOut;
        List<Field> finalFields = null;

        if (evaluationSource != null) {
            final String tcsSource = controlSource != null ? controlSource : passthroughControl(vsOut, controlVertices);
            final List<String> tcs = parsed(tcsSource, GL40.GL_TESS_CONTROL_SHADER);
            final List<String> tes = parsed(evaluationSource, GL40.GL_TESS_EVALUATION_SHADER);
            extras.addAll(tcs);
            extras.addAll(tes);
            final List<Io> tcsIn = interfaceOf(tcs, "in", true), tcsOut = interfaceOf(tcs, "out", true), tesIn = interfaceOf(tes, "in", true), tesOut = interfaceOf(tes, "out", false);
            final List<Field> controlFields = new ArrayList<>(builtinFields()), patchFields = new ArrayList<>();
            final Map<String, Field> controlByKey = new HashMap<>(), patchByKey = new HashMap<>();
            final Map<Integer, Field> controlByLocation = new HashMap<>(), patchByLocation = new HashMap<>();
            for (Io out : tcsOut) {
                if (out.isPatch()) addFields(out, patchFields, patchByKey, patchByLocation);
                else addFields(out, controlFields, controlByKey, controlByLocation);
            }
            final StringBuilder controlLoads = new StringBuilder(), evaluationLoads = new StringBuilder(), patchLoads = new StringBuilder();
            for (Io in : tcsIn) {
                final String problem = loadsOf(in, vertexByKey, vertexByLocation, controlLoads, "tessellation control", stagesAlone ? List.of() : tcs);
                if (problem != null) return Result.failed(problem);
            }
            for (Io in : tesIn) {
                final String problem = in.isPatch() ? patchLoadOf(in, patchByKey, patchByLocation, patchLoads) : loadsOf(in, controlByKey, controlByLocation, evaluationLoads, "tessellation evaluation", stagesAlone ? List.of() : tes);
                if (problem != null) return Result.failed(problem);
            }
            final List<Field> evaluationFields = emittedFieldsOf(tesOut);
            control = controlShader(tcs, vs, tcsIn, tcsOut, vertexFields, controlFields, patchFields, controlLoads.toString(), controlVertices);
            evaluation = evaluationShader(tes, tcs, tesIn, tesOut, controlFields, patchFields, evaluationFields, evaluationLoads.toString(), patchLoads.toString(), controlVertices);
            controlBytes = upperBoundBytes(controlFields);
            patchBytes = LEVELS_BYTES + upperBoundBytes(patchFields);
            evaluationBytes = upperBoundBytes(evaluationFields);
            producerFields = evaluationFields;
            producerByKey = new HashMap<>();
            producerByLocation = new HashMap<>();
            for (Io out : tesOut) addFields(out, new ArrayList<>(), producerByKey, producerByLocation);
            producer = tes;
            finalOut = tesOut;
            finalFields = evaluationFields;
        }

        String compute = null;
        int indices = 0, drawMode = 0;
        if (geometrySource != null) {
            final List<String> gs = parsed(geometrySource, GL32.GL_GEOMETRY_SHADER);
            extras.addAll(gs);
            final List<Io> gsIn = interfaceOf(gs, "in", true), gsOut = interfaceOf(gs, "out", false);
            final StringBuilder loads = new StringBuilder();
            for (Io in : gsIn) {
                final String problem = loadsOf(in, producerByKey, producerByLocation, loads, "geometry", stagesAlone ? List.of() : gs);
                if (problem != null) return Result.failed(problem);
            }
            finalOut = gsOut;
            finalFields = emittedFieldsOf(gsOut);
            if (gs.stream().anyMatch(s -> s.contains("EmitStreamVertex"))) finalFields.add(new Field("int", "b_stream", "", GlTransformFeedback.STREAM_RECORD));
            compute = computeSource(gs, gsIn, gsOut, producer, producerFields, finalFields, loads.toString(), verticesIn, maxVertices, invocations, outputMode, firstConvention);
            indices = indicesPerRun(outputMode, maxVertices);
            drawMode = drawMode(outputMode);
        }

        final String all = fragmentSource == null ? "" : fragmentSource;
        final boolean primitiveId = all.contains("gl_PrimitiveID");
        final boolean layer = extras.stream().anyMatch(s -> s.contains("gl_Layer"));
        // Metal wants a layered draw's primitive class declared, and refuses to declare one other than points for a shader writing a point size.
        final boolean pointSize = !layer && extras.stream().noneMatch(s -> s.contains("gl_ViewportIndex")) || drawMode == GL11.GL_POINTS;
        final String vertex = vertexSource(vs, extras, vsOut, finalOut, vertexFields, finalFields, primitiveId, layer, pointSize);
        final String fragment = primitiveId
            ? insertAfterHeader(fragmentSource.replaceAll("\\bgl_PrimitiveID\\b", "angelica_primitiveId"), "flat in int angelica_primitiveId;\n")
            : null;
        final Map<String, Integer> offsets = new HashMap<>();
        final int stride = layoutOf(finalFields, offsets);
        return new Result(vertex, fragment, compute, control, evaluation, upperBoundBytes(vertexFields), upperBoundBytes(finalFields), controlBytes, patchBytes, evaluationBytes, indices, drawMode, stride, offsets,
            evaluationSource != null && geometrySource == null && evaluationSource.contains("gl_CullDistance"), null);
    }

    private static List<Field> emittedFieldsOf(List<Io> outs) {
        final List<Field> fields = new ArrayList<>(builtinFields());
        fields.add(new Field("int", "b_layer", "", "gl_Layer"));
        fields.add(new Field("int", "b_viewport", "", "gl_ViewportIndex"));
        fields.add(new Field("int", "b_primitive", "", "gl_PrimitiveID"));
        for (Io out : outs) addFields(out, fields, new HashMap<>(), new HashMap<>());
        return fields;
    }

    private static boolean skippedInCompute(String statement) {
        final String t = statement.trim();
        return isHeaderLine(statement) || t.startsWith("struct") || STAGE_LAYOUT.matcher(t).matches() || STAGE_ONLY.matcher(t).matches() || isInterface(statement);
    }

    private static void perVertexStruct(StringBuilder out, String name) {
        out.append("struct ").append(name).append(" { vec4 a_gl_Position; float a_gl_PointSize; float a_gl_ClipDistance[").append(CLIP_SLOTS).append("]; float a_gl_CullDistance[").append(CLIP_SLOTS).append("]; };\n");
    }

    private static void computeHeader(StringBuilder out, List<String> own, List<String> other) {
        for (String s : own) if (s.trim().startsWith("#extension")) out.append(s.trim()).append('\n');
        out.append("layout(local_size_x = 1) in;\n");
        // Declarations made here can be written in terms of them, and defining one again the same way is allowed.
        for (String s : own) if (OBJECT_MACRO.matcher(s).find()) out.append(s.trim()).append('\n');
        for (String s : other) if (s.trim().startsWith("struct") && !own.contains(s)) out.append(s).append('\n');
        for (String s : own) if (s.trim().startsWith("struct")) out.append(s).append('\n');
    }

    /** A control shader for a program without one, handing on what the vertex shader outputs with the default levels. */
    private static String passthroughControl(List<Io> vsOut, int vertices) {
        final StringBuilder out = new StringBuilder("#version 450 core\nlayout(vertices = ").append(vertices).append(") out;\n");
        out.append("uniform vec4 ").append(DEFAULT_OUTER).append(";\nuniform vec2 ").append(DEFAULT_INNER).append(";\n");
        final StringBuilder body = new StringBuilder();
        int index = 0;
        for (Io io : vsOut) {
            if (io.isBlock()) {
                final String in = "angelica_in_" + index, outName = "angelica_out_" + index;
                index++;
                out.append("in ").append(io.blockType).append(" {");
                for (Io m : io.members) out.append(' ').append(m.type).append(' ').append(m.name).append(m.inner).append(';');
                out.append(" } ").append(in).append("[];\nout ").append(io.blockType).append(" {");
                for (Io m : io.members) out.append(' ').append(m.type).append(' ').append(m.name).append(m.inner).append(';');
                out.append(" } ").append(outName).append("[];\n");
                for (Io m : io.members) body.append("    ").append(outName).append("[gl_InvocationID].").append(m.name).append(" = ").append(in).append("[gl_InvocationID].").append(m.name).append(";\n");
                continue;
            }
            out.append("in ").append(io.type).append(" angelica_in_").append(io.name).append("[]").append(io.inner).append(";\n");
            out.append("out ").append(io.type).append(' ').append(io.name).append("[]").append(io.inner).append(";\n");
            body.append("    ").append(io.name).append("[gl_InvocationID] = angelica_in_").append(io.name).append("[gl_InvocationID];\n");
        }
        out.append("void main() {\n    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;\n");
        out.append("    gl_out[gl_InvocationID].gl_PointSize = gl_in[gl_InvocationID].gl_PointSize;\n");
        out.append("    for (int i = 0; i < 4; i++) gl_TessLevelOuter[i] = ").append(DEFAULT_OUTER).append("[i];\n");
        out.append("    for (int i = 0; i < 2; i++) gl_TessLevelInner[i] = ").append(DEFAULT_INNER).append("[i];\n");
        return out.append(body).append("}\n").toString();
    }

    private static final int MAX_PATCH_VERTICES = 32;

    private static String controlShader(List<String> tcs, List<String> vs, List<Io> in, List<Io> out, List<Field> vertexFields, List<Field> controlFields, List<Field> patchFields, String loads, int vertices) {
        final StringBuilder src = new StringBuilder(versionOf(tcs));
        computeHeader(src, tcs, vs);
        struct(src, "AngelicaVertex", vertexFields);
        struct(src, "AngelicaControl", controlFields);
        struct(src, "AngelicaPatch", withLevels(patchFields));
        src.append("layout(std430, binding = ").append(VERTICES_BINDING).append(") readonly buffer AngelicaVertices { AngelicaVertex angelica_vertices[]; };\n");
        src.append("layout(std430, binding = ").append(PRIMITIVES_BINDING).append(") readonly buffer AngelicaPrimitives { int angelica_primitives[]; };\n");
        src.append("layout(std430, binding = ").append(CONTROL_BINDING).append(") writeonly buffer AngelicaControls { AngelicaControl angelica_controls[]; };\n");
        src.append("layout(std430, binding = ").append(PATCH_BINDING).append(") writeonly buffer AngelicaPatches { AngelicaPatch angelica_patches[]; };\n");
        src.append("layout(std430, binding = ").append(LEVELS_BINDING).append(") writeonly buffer AngelicaLevels { float angelica_levels[]; };\n");
        src.append("uniform int ").append(PATCH_VERTICES).append(";\nuniform int ").append(PRIMITIVES_PER_INSTANCE).append(";\n");
        perVertexStruct(src, "AngelicaPerVertex");
        src.append("AngelicaPerVertex a_gl_in[").append(MAX_PATCH_VERTICES).append("];\nAngelicaPerVertex a_gl_out[").append(vertices).append("];\n");
        src.append("float a_gl_TessLevelOuter[4];\nfloat a_gl_TessLevelInner[2];\nint a_gl_InvocationID;\nint a_gl_PrimitiveID;\nint a_gl_PatchVerticesIn;\n");
        for (Io io : in) declareGlobal(src, io, "[" + MAX_PATCH_VERTICES + "]");
        for (Io io : out) declareGlobal(src, io, io.isPatch() ? "" : "[" + vertices + "]");
        int phases = 1;
        for (String s : tcs) {
            if (skippedInCompute(s)) continue;
            final String named = BUILTIN_NAME.matcher(s).replaceAll("a_$1");
            if (MAIN.matcher(s).find()) {
                final ControlPhases split = ControlPhases.of(named, vertices);
                phases = split.count();
                src.append(split.source());
            } else {
                src.append(named).append('\n');
            }
        }
        src.append("void main() {\n    int patchIndex = int(gl_GlobalInvocationID.x);\n");
        src.append("    a_gl_PatchVerticesIn = ").append(PATCH_VERTICES).append(";\n    a_gl_PrimitiveID = patchIndex % ").append(PRIMITIVES_PER_INSTANCE).append(";\n");
        src.append("    for (int i = 0; i < ").append(PATCH_VERTICES).append("; i++) {\n");
        src.append("        AngelicaVertex v = angelica_vertices[angelica_primitives[patchIndex * ").append(PATCH_VERTICES).append(" + i]];\n");
        src.append("        a_gl_in[i].a_gl_Position = v.b_position;\n        a_gl_in[i].a_gl_PointSize = v.b_pointSize;\n");
        src.append("        a_gl_in[i].a_gl_ClipDistance = v.b_clip;\n        a_gl_in[i].a_gl_CullDistance = v.b_cull;\n");
        src.append(loads).append("    }\n");
        src.append("    for (int i = 0; i < 4; i++) a_gl_TessLevelOuter[i] = 0.0;\n    for (int i = 0; i < 2; i++) a_gl_TessLevelInner[i] = 0.0;\n");
        for (int phase = 0; phase < phases; phase++) {
            src.append("    for (a_gl_InvocationID = 0; a_gl_InvocationID < ").append(vertices).append("; a_gl_InvocationID++) angelica_tc_main_").append(phase).append("();\n");
        }
        src.append("    angelica_patches[patchIndex].f_outer = a_gl_TessLevelOuter;\n    angelica_patches[patchIndex].f_inner = a_gl_TessLevelInner;\n");
        src.append("    for (int i = 0; i < 4; i++) angelica_levels[patchIndex * 6 + i] = a_gl_TessLevelOuter[i];\n    for (int i = 0; i < 2; i++) angelica_levels[patchIndex * 6 + 4 + i] = a_gl_TessLevelInner[i];\n");
        for (Field f : patchFields) src.append("    angelica_patches[patchIndex].").append(f.name).append(" = ").append(patchValue(f, out)).append(";\n");
        src.append("    for (int i = 0; i < ").append(vertices).append("; i++) {\n        int at = patchIndex * ").append(vertices).append(" + i;\n");
        for (Field f : controlFields) src.append("        angelica_controls[at].").append(f.name).append(" = ").append(controlValue(f, out)).append(";\n");
        return src.append("    }\n}\n").toString();
    }

    private static List<Field> withLevels(List<Field> patchFields) {
        final List<Field> all = new ArrayList<>();
        all.add(new Field("float", "f_outer", "[4]", "gl_TessLevelOuter"));
        all.add(new Field("float", "f_inner", "[2]", "gl_TessLevelInner"));
        all.addAll(patchFields);
        return all;
    }

    private static String patchValue(Field f, List<Io> out) {
        for (Io io : out) {
            if (!io.isBlock() || !io.isPatch()) continue;
            for (Io m : io.members) if (f.name.equals("f_" + io.blockType + "_" + m.name)) return (io.name.isEmpty() ? "" : io.name + ".") + m.name;
        }
        return f.source;
    }

    private static String controlValue(Field f, List<Io> out) {
        switch (f.name) {
            case "b_position":
                return "a_gl_out[i].a_gl_Position";
            case "b_pointSize":
                return "a_gl_out[i].a_gl_PointSize";
            case "b_clip":
                return "a_gl_out[i].a_gl_ClipDistance";
            case "b_cull":
                return "a_gl_out[i].a_gl_CullDistance";
            default:
                break;
        }
        for (Io io : out) {
            if (!io.isBlock()) continue;
            for (Io m : io.members) if (f.name.equals("f_" + io.blockType + "_" + m.name)) return (io.name.isEmpty() ? m.name + "[i]" : io.name + "[i]." + m.name);
        }
        return f.source + "[i]";
    }

    private static String patchLoadOf(Io in, Map<String, Field> byKey, Map<Integer, Field> byLocation, StringBuilder loads) {
        if (in.isBlock()) {
            for (Io member : in.members) {
                final Field f = byKey.get(in.blockType + "." + member.name);
                if (f == null) return "tessellation evaluation shader input " + in.blockType + "." + member.name + " has no matching tessellation control shader output";
                loads.append("    ").append(in.name.isEmpty() ? member.name : in.name + "." + member.name).append(" = p.").append(f.name).append(";\n");
            }
            return null;
        }
        Field f = in.location >= 0 ? byLocation.get(in.location) : null;
        if (f == null) f = byKey.get(in.name);
        if (f == null) return "tessellation evaluation shader input " + in.name + " has no matching tessellation control shader output";
        loads.append("    ").append(in.name).append(" = p.").append(f.name).append(";\n");
        return null;
    }

    private static String evaluationShader(List<String> tes, List<String> tcs, List<Io> in, List<Io> out, List<Field> controlFields, List<Field> patchFields, List<Field> evaluationFields, String loads, String patchLoads, int vertices) {
        final StringBuilder src = new StringBuilder(versionOf(tes));
        computeHeader(src, tes, tcs);
        struct(src, "AngelicaControl", controlFields);
        struct(src, "AngelicaPatch", withLevels(patchFields));
        struct(src, "AngelicaEmitted", evaluationFields);
        src.append("struct AngelicaCoordinate { float u; float v; float w; int patchIndex; };\n");
        src.append("layout(std430, binding = ").append(CONTROL_BINDING).append(") readonly buffer AngelicaControls { AngelicaControl angelica_controls[]; };\n");
        src.append("layout(std430, binding = ").append(PATCH_BINDING).append(") readonly buffer AngelicaPatches { AngelicaPatch angelica_patches[]; };\n");
        src.append("layout(std430, binding = ").append(COORDINATE_BINDING).append(") readonly buffer AngelicaCoordinates { AngelicaCoordinate angelica_coordinates[]; };\n");
        src.append("layout(std430, binding = ").append(EMITTED_BINDING).append(") writeonly buffer AngelicaEmits { AngelicaEmitted angelica_emitted[]; };\n");
        src.append("uniform int ").append(CONTROL_VERTICES).append(";\nuniform int ").append(PRIMITIVES_PER_INSTANCE).append(";\n");
        perVertexStruct(src, "AngelicaPerVertex");
        // Its size is the most a patch can have, whichever the control shader declared.
        src.append("AngelicaPerVertex a_gl_in[").append(MAX_PATCH_VERTICES).append("];\n");
        src.append("vec4 a_gl_Position;\nfloat a_gl_PointSize = 1.0;\nfloat a_gl_ClipDistance[").append(CLIP_SLOTS).append("];\nfloat a_gl_CullDistance[").append(CLIP_SLOTS).append("];\n");
        src.append("vec3 a_gl_TessCoord;\nfloat a_gl_TessLevelOuter[4];\nfloat a_gl_TessLevelInner[2];\nint a_gl_PrimitiveID;\nint a_gl_PatchVerticesIn;\nint a_gl_Layer = 0;\nint a_gl_ViewportIndex = 0;\n");
        for (Io io : in) declareGlobal(src, io, io.isPatch() ? "" : "[" + vertices + "]");
        for (Io io : out) declareGlobal(src, io, "");
        for (String s : tes) {
            if (skippedInCompute(s)) continue;
            src.append(BUILTIN_NAME.matcher(MAIN.matcher(s).replaceAll("void angelica_te_main(")).replaceAll("a_$1")).append('\n');
        }
        src.append("void main() {\n    int id = int(gl_GlobalInvocationID.x);\n    AngelicaCoordinate coordinate = angelica_coordinates[id];\n");
        src.append("    int patchIndex = coordinate.patchIndex;\n    a_gl_TessCoord = vec3(coordinate.u, coordinate.v, coordinate.w);\n");
        src.append("    a_gl_PatchVerticesIn = ").append(CONTROL_VERTICES).append(";\n    a_gl_PrimitiveID = patchIndex % ").append(PRIMITIVES_PER_INSTANCE).append(";\n");
        src.append("    AngelicaPatch p = angelica_patches[patchIndex];\n    a_gl_TessLevelOuter = p.f_outer;\n    a_gl_TessLevelInner = p.f_inner;\n");
        src.append(patchLoads);
        src.append("    for (int i = 0; i < ").append(vertices).append("; i++) {\n        AngelicaControl v = angelica_controls[patchIndex * ").append(vertices).append(" + i];\n");
        src.append("        a_gl_in[i].a_gl_Position = v.b_position;\n        a_gl_in[i].a_gl_PointSize = v.b_pointSize;\n");
        src.append("        a_gl_in[i].a_gl_ClipDistance = v.b_clip;\n        a_gl_in[i].a_gl_CullDistance = v.b_cull;\n");
        src.append(loads).append("    }\n");
        src.append("    for (int i = 0; i < ").append(CLIP_SLOTS).append("; i++) { a_gl_ClipDistance[i] = 1.0; a_gl_CullDistance[i] = 1.0; }\n    a_gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n    angelica_te_main();\n");
        for (Field f : evaluationFields) {
            src.append("    angelica_emitted[id].").append(f.name).append(" = ").append(f.name.equals("b_primitive") ? "a_gl_PrimitiveID" : emittedValue(f, out)).append(";\n");
        }
        return src.append("}\n").toString();
    }

    private static String insertAfterHeader(String source, String text) {
        final int at = ShaderCompiler.headerEnd(source);
        return source.substring(0, at) + text + source.substring(at);
    }

    private static List<Field> builtinFields() {
        final List<Field> fields = new ArrayList<>();
        fields.add(new Field("vec4", "b_position", "", "gl_Position"));
        fields.add(new Field("float", "b_pointSize", "", "gl_PointSize"));
        fields.add(new Field("float", "b_clip", "[" + CLIP_SLOTS + "]", "gl_ClipDistance"));
        fields.add(new Field("float", "b_cull", "[" + CLIP_SLOTS + "]", "gl_CullDistance"));
        return fields;
    }

    private static void addFields(Io io, List<Field> fields, Map<String, Field> byKey, Map<Integer, Field> byLocation) {
        if (io.isBlock()) {
            for (Io member : io.members) {
                final Field f = new Field(member.type, "f_" + io.blockType + "_" + member.name, member.inner, member.name);
                fields.add(f);
                byKey.put(io.blockType + "." + member.name, f);
            }
            return;
        }
        final Field f = new Field(io.type, "f_" + io.name, io.inner, io.name);
        fields.add(f);
        byKey.put(io.name, f);
        if (io.location >= 0) byLocation.put(io.location, f);
    }

    /** Overstates a field's size, each scalar counted as a whole vec4, as a buffer only has to be large enough. */
    private static long upperBoundBytes(List<Field> fields) {
        long total = 0L;
        for (Field f : fields) total += 16L * slots(f.type) * elements(f.inner);
        return Math.max(16L, total);
    }

    private static long slots(String type) {
        // A struct's size is not known here, so it is given room for a good many members.
        if (type.startsWith("mat") || type.startsWith("dmat")) return 4L;
        return Character.isUpperCase(type.charAt(0)) || !type.matches("[a-z0-9]+") ? 32L : 1L;
    }

    private static final Pattern DIMENSION = Pattern.compile("\\[([^\\[\\]]*)\\]");
    private static final Pattern LITERAL = Pattern.compile("\\s*(?:0[xX]([0-9a-fA-F]+)|(\\d+))[uU]?\\s*");

    /** A size the preprocessor left as an expression rather than a literal (16u is one) is overstated, as an upper bound may be. */
    private static long elements(String inner) {
        return elements(inner, 64L);
    }

    /** {@code unknown} stands for each size that is not a literal; a negative one makes the whole count that. */
    private static long elements(String inner, long unknown) {
        long count = 1L;
        final Matcher sized = DIMENSION.matcher(inner);
        while (sized.find()) {
            if (sized.group(1).isBlank()) continue;
            final Matcher literal = LITERAL.matcher(sized.group(1));
            if (!literal.matches() && unknown < 0) return unknown;
            count *= !literal.matches() ? unknown : literal.group(1) != null ? Long.parseLong(literal.group(1), 16) : Long.parseLong(literal.group(2));
        }
        return count;
    }

    private static final Pattern DECLARED_VERSION = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)");

    static List<String> parsed(String source, int glType) {
        return statements(stripComments(ShaderCompiler.preprocessed(ShaderCompiler.withDeclaredLanguage(source), glType)));
    }

    /** Only an input the stage reads has to match an output. */
    private static boolean reads(List<String> stage, String name) {
        final Pattern word = Pattern.compile("\\b" + name + "\\b");
        for (String s : stage) {
            if (!isInput(s) && word.matcher(s).find()) return true;
        }
        return false;
    }

    private static String loadsOf(Io in, Map<String, Field> byKey, Map<Integer, Field> byLocation, StringBuilder loads, String stage, List<String> statements) {
        if (in.isBlock()) {
            for (Io member : in.members) {
                final Field f = byKey.get(in.blockType + "." + member.name);
                if (f == null) {
                    if (!reads(statements, member.name)) continue;
                    return stage + " shader input " + in.blockType + "." + member.name + " has no matching output";
                }
                final String target = in.name.isEmpty() ? member.name + "[i]" : in.name + "[i]." + member.name;
                loads.append("        ").append(target).append(" = v.").append(f.name).append(";\n");
            }
            return null;
        }
        Field f = in.location >= 0 ? byLocation.get(in.location) : null;
        // The control shader made for a program without one names its inputs apart from its outputs.
        if (f == null) f = byKey.get(in.name.startsWith("angelica_in_") ? in.name.substring("angelica_in_".length()) : in.name);
        if (f == null) return reads(statements, in.name) ? stage + " shader input " + in.name + " has no matching output" : null;
        if (!f.type.equals(in.type)) return stage + " shader input " + in.name + " does not match the type of the output";
        loads.append("        ").append(in.name).append("[i] = v.").append(f.name).append(";\n");
        return null;
    }

    private static void struct(StringBuilder out, String name, List<Field> fields) {
        out.append("struct ").append(name).append(" {\n");
        for (Field f : fields) out.append("    ").append(f.type).append(' ').append(f.name).append(f.inner).append(";\n");
        out.append("};\n");
    }

    private static String versionOf(List<String> statements) {
        for (String s : statements) {
            final Matcher version = DECLARED_VERSION.matcher(s);
            if (version.find() && Integer.parseInt(version.group(1)) >= 430) return s.trim() + "\n";
        }
        return "#version 450 core\n";
    }

    private static boolean isHeaderLine(String s) {
        final String t = s.trim();
        return t.startsWith("#version") || t.startsWith("#extension");
    }

    /** The geometry shader's code as a compute shader, one invocation for each time it would run. */
    private static String computeSource(List<String> gs, List<Io> gsIn, List<Io> gsOut, List<String> vs, List<Field> vertexFields, List<Field> emittedFields, String loads,
                                        int verticesIn, int maxVertices, int invocations, int outputMode, boolean firstConvention) {
        final StringBuilder out = new StringBuilder(versionOf(gs));
        computeHeader(out, gs, vs);
        struct(out, "AngelicaVertex", vertexFields);
        struct(out, "AngelicaEmitted", emittedFields);
        out.append("layout(std430, binding = ").append(VERTICES_BINDING).append(") readonly buffer AngelicaVertices { AngelicaVertex angelica_vertices[]; };\n");
        out.append("layout(std430, binding = ").append(PRIMITIVES_BINDING).append(") readonly buffer AngelicaPrimitives { int angelica_primitives[]; };\n");
        out.append("layout(std430, binding = ").append(EMITTED_BINDING).append(") buffer AngelicaEmits { AngelicaEmitted angelica_emitted[]; };\n");
        out.append("layout(std430, binding = ").append(INDICES_BINDING).append(") writeonly buffer AngelicaIndices { uint angelica_indices[]; };\n");
        out.append("uniform int ").append(PRIMITIVES_PER_INSTANCE).append(";\n");
        out.append("struct AngelicaGlIn { vec4 a_gl_Position; float a_gl_PointSize; float a_gl_ClipDistance[").append(CLIP_SLOTS).append("]; float a_gl_CullDistance[").append(CLIP_SLOTS).append("]; };\n");
        out.append("AngelicaGlIn a_gl_in[").append(verticesIn).append("];\n");
        out.append("vec4 a_gl_Position;\nfloat a_gl_PointSize = 1.0;\nfloat a_gl_ClipDistance[").append(CLIP_SLOTS).append("];\nfloat a_gl_CullDistance[").append(CLIP_SLOTS).append("];\n");
        out.append("int a_gl_Layer = 0;\nint a_gl_ViewportIndex = 0;\nint a_gl_PrimitiveID = 0;\nint a_gl_PrimitiveIDIn;\nint a_gl_InvocationID;\nint angelica_stream = 0;\n");
        out.append("int angelica_emittedCount = 0, angelica_indexCount = 0, angelica_slot, angelica_inPrimitive = 0, angelica_before = 0, angelica_earlier = 0;\n");
        for (Io in : gsIn) declareGlobal(out, in, "[" + verticesIn + "]");
        for (Io o : gsOut) declareGlobal(out, o, "");

        final int indices = indicesPerRun(outputMode, maxVertices);
        out.append("void angelica_put_index(int value) {\n    angelica_indices[angelica_slot * ").append(indices).append(" + angelica_indexCount] = uint(value);\n    angelica_indexCount++;\n}\n");
        out.append("bool angelica_culled(int a, int b, int c) {\n    for (int plane = 0; plane < ").append(CLIP_SLOTS).append("; plane++) {\n");
        out.append("        if (angelica_emitted[a].b_cull[plane] < 0.0 && angelica_emitted[b].b_cull[plane] < 0.0 && angelica_emitted[c].b_cull[plane] < 0.0) return true;\n    }\n    return false;\n}\n");
        out.append("void EndPrimitive() {\n    angelica_inPrimitive = 0;\n}\n");
        out.append("void EmitVertex() {\n    if (angelica_emittedCount >= ").append(maxVertices).append(") return;\n");
        out.append("    int at = angelica_slot * ").append(maxVertices + 1).append(" + angelica_emittedCount;\n");
        for (Field f : emittedFields) out.append("    angelica_emitted[at].").append(f.name).append(" = ").append(emittedValue(f, gsOut)).append(";\n");
        out.append(primitiveAssembly(outputMode, firstConvention));
        out.append("    angelica_earlier = angelica_before;\n    angelica_before = at;\n    angelica_inPrimitive++;\n    angelica_emittedCount++;\n}\n");
        out.append("void EmitStreamVertex(int stream) {\n    angelica_stream = stream;\n    EmitVertex();\n    angelica_stream = 0;\n}\n");
        out.append("void EndStreamPrimitive(int stream) {\n    EndPrimitive();\n}\n");

        for (String s : gs) {
            if (skippedInCompute(s)) continue;
            out.append(BUILTIN_NAME.matcher(MAIN.matcher(s).replaceAll("void angelica_gs_main(")).replaceAll("a_$1")).append('\n');
        }

        out.append("void main() {\n    angelica_slot = int(gl_GlobalInvocationID.x);\n");
        out.append("    a_gl_InvocationID = angelica_slot % ").append(invocations).append(";\n");
        out.append("    int primitive = angelica_slot / ").append(invocations).append(";\n");
        out.append("    a_gl_PrimitiveIDIn = primitive % ").append(PRIMITIVES_PER_INSTANCE).append(";\n");
        out.append("    for (int i = 0; i < ").append(verticesIn).append("; i++) {\n");
        out.append("        AngelicaVertex v = angelica_vertices[angelica_primitives[primitive * ").append(verticesIn).append(" + i]];\n");
        out.append("        a_gl_in[i].a_gl_Position = v.b_position;\n        a_gl_in[i].a_gl_PointSize = v.b_pointSize;\n");
        out.append("        a_gl_in[i].a_gl_ClipDistance = v.b_clip;\n        a_gl_in[i].a_gl_CullDistance = v.b_cull;\n");
        // Distances a shader does not write keep a primitive, and a global starts out undefined.
        out.append(loads).append("    }\n    for (int i = 0; i < ").append(CLIP_SLOTS).append("; i++) { a_gl_ClipDistance[i] = 1.0; a_gl_CullDistance[i] = 1.0; }\n    angelica_gs_main();\n");
        // What was not emitted points at a vertex kept outside the clip volume, past those the shader can emit.
        out.append("    int unused = angelica_slot * ").append(maxVertices + 1).append(" + ").append(maxVertices).append(";\n");
        out.append("    angelica_emitted[unused].b_position = vec4(2.0, 2.0, 2.0, 1.0);\n");
        out.append("    while (angelica_indexCount < ").append(indices).append(") angelica_put_index(unused);\n}\n");
        return out.toString();
    }

    /** Puts out the primitive the vertex at "at" completes, unless its cull distances leave it out, in the order GL gives a strip's. */
    private static String primitiveAssembly(int outputMode, boolean firstConvention) {
        if (outputMode == GL11.GL_POINTS) return "    if (!angelica_culled(at, at, at)) angelica_put_index(at);\n";
        if (outputMode == GL11.GL_LINE_STRIP) {
            return "    if (angelica_inPrimitive >= 1 && !angelica_culled(angelica_before, angelica_before, at)) {\n"
                + "        angelica_put_index(angelica_before);\n        angelica_put_index(at);\n    }\n";
        }
        final String odd = firstConvention ? "angelica_earlier, at, angelica_before" : "angelica_before, angelica_earlier, at";
        return "    if (angelica_inPrimitive >= 2 && !angelica_culled(angelica_earlier, angelica_before, at)) {\n"
            + "        bool even = (angelica_inPrimitive - 2) % 2 == 0;\n"
            + "        int a = even ? angelica_earlier : " + odd.split(", ")[0] + ";\n"
            + "        int b = even ? angelica_before : " + odd.split(", ")[1] + ";\n"
            + "        int c = even ? at : " + odd.split(", ")[2] + ";\n"
            + "        angelica_put_index(a);\n        angelica_put_index(b);\n        angelica_put_index(c);\n    }\n";
    }

    private static void declareGlobal(StringBuilder out, Io io, String outer) {
        if (io.isBlock() && io.name.isEmpty()) {
            // A block with no instance name has its members in the global scope.
            for (Io m : io.members) out.append(m.type).append(' ').append(m.name).append(outer).append(m.inner).append(";\n");
            return;
        }
        if (io.isBlock()) {
            // A block type can name both what comes in and what goes out.
            String structName = "angelica_block_" + io.blockType;
            while (out.indexOf("struct " + structName + " ") >= 0) structName += "_";
            out.append("struct ").append(structName).append(" {\n");
            for (Io m : io.members) out.append("    ").append(m.type).append(' ').append(m.name).append(m.inner).append(";\n");
            out.append("};\n").append(structName).append(' ').append(io.name.isEmpty() ? "angelica_" + io.blockType : io.name).append(outer).append(";\n");
            return;
        }
        out.append(io.type).append(' ').append(io.name).append(outer).append(io.inner).append(";\n");
    }

    private static String emittedValue(Field f, List<Io> gsOut) {
        return switch (f.name) {
            case "b_position" -> "a_gl_Position";
            case "b_pointSize" -> "a_gl_PointSize";
            case "b_clip" -> "a_gl_ClipDistance";
            case "b_cull" -> "a_gl_CullDistance";
            case "b_layer" -> "a_gl_Layer";
            case "b_viewport" -> "a_gl_ViewportIndex";
            case "b_primitive" -> "a_gl_PrimitiveID";
            case "b_stream" -> "angelica_stream";
            default -> {
                for (Io io : gsOut) {
                    if (io.isBlock()) {
                        for (Io m : io.members) {
                            if (f.name.equals("f_" + io.blockType + "_" + m.name)) yield (io.name.isEmpty() ? "" : io.name + ".") + m.name;
                        }
                    }
                }
                yield f.source;
            }
        };
    }

    /** The vertex shader, which either runs as written and stores its outputs, or hands an emitted vertex on. */
    private static String vertexSource(List<String> vs, List<String> gs, List<Io> vsOut, List<Io> gsOut, List<Field> vertexFields, List<Field> emittedFields, boolean primitiveId, boolean layer, boolean pointSize) {
        // The stages that run as compute shaders declare structs and uniforms that may repeat, and only the first of each can stay.
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final Map<String, String> renames = new HashMap<>();
        for (Io io : vsOut) {
            if (!io.isBlock()) {
                renames.put(io.name, "angelica_v_" + io.name);
            } else if (io.name.isEmpty()) {
                for (Io m : io.members) renames.put(m.name, "angelica_v_" + m.name);
            } else {
                renames.put(io.name, "angelica_v_" + io.name);
            }
        }
        for (String builtin : BUILTIN_VERTEX) renames.put(builtin, "angelica_v_" + builtin);

        final StringBuilder out = new StringBuilder(versionOf(vs));
        for (String s : vs) if (s.trim().startsWith("#extension")) out.append(s.trim()).append('\n');
        if (layer || gs.stream().anyMatch(s -> s.contains("gl_ViewportIndex"))) out.append("#extension GL_ARB_shader_viewport_layer_array : require\n");
        for (String s : gs) {
            if (s.trim().startsWith("struct") && !vs.contains(s) && seen.add(s)) out.append(s).append('\n');
        }
        struct(out, "AngelicaVertex", vertexFields);
        struct(out, "AngelicaEmitted", emittedFields);
        out.append("layout(std430, binding = ").append(VERTICES_BINDING).append(") buffer AngelicaVertices { AngelicaVertex angelica_vertices[]; };\n");
        out.append("layout(std430, binding = ").append(EMITTED_BINDING).append(") readonly buffer AngelicaEmits { AngelicaEmitted angelica_emitted[]; };\n");
        out.append("uniform int ").append(STAGE).append(";\nuniform int ").append(BASE).append(";\nuniform int ").append(SPAN).append(";\nuniform int ").append(FIRST_INSTANCE).append(";\n");
        if (layer) out.append("uniform int ").append(LAYER_LIMIT).append(";\n");
        out.append("vec4 angelica_v_gl_Position;\nfloat angelica_v_gl_PointSize = 1.0;\nfloat angelica_v_gl_ClipDistance[").append(CLIP_SLOTS).append("];\nfloat angelica_v_gl_CullDistance[").append(CLIP_SLOTS).append("];\n");
        out.append("out float gl_ClipDistance[").append(CLIP_SLOTS).append("];\n");
        if (primitiveId) out.append("flat out int angelica_primitiveId;\n");
        for (Io io : vsOut) {
            if (io.isBlock()) {
                final String structName = "angelica_vblock_" + io.blockType;
                out.append("struct ").append(structName).append(" {\n");
                for (Io m : io.members) out.append("    ").append(m.type).append(' ').append(m.name).append(m.inner).append(";\n");
                out.append("};\n");
                if (io.name.isEmpty()) {
                    for (Io m : io.members) out.append(m.type).append(" angelica_v_").append(m.name).append(m.inner).append(";\n");
                } else {
                    out.append(structName).append(" angelica_v_").append(io.name).append(io.inner).append(";\n");
                }
            } else {
                out.append(io.type).append(" angelica_v_").append(io.name).append(io.inner).append(";\n");
            }
        }
        // What the fragment shader receives is what the geometry shader declared.
        for (Io io : gsOut) out.append(declarationOfOutput(io)).append('\n');
        // Uniforms only the geometry shader uses still belong to the program, and any stage's declaration can be the one naming a binding.
        final Map<String, String> resources = new HashMap<>();
        for (List<String> stage : List.of(vs, gs)) {
            for (String s : stage) {
                if (isResource(s)) resources.merge(resourceName(s), s, (first, other) -> first.contains("binding") ? first : other.contains("binding") ? other : first);
            }
        }
        final java.util.Set<String> vertexResources = new java.util.HashSet<>();
        for (String s : vs) if (isResource(s)) vertexResources.add(resourceName(s));
        // What those declarations are written in terms of goes with them, and no further.
        final List<String> borrowedMacros = new ArrayList<>();
        for (String s : gs) {
            final Matcher macro = OBJECT_MACRO.matcher(s);
            if (macro.find()) {
                borrowedMacros.add(macro.group(1));
                out.append("#ifndef ").append(macro.group(1)).append('\n').append(s.trim()).append("\n#endif\n");
            }
        }
        for (String s : gs) {
            if (isResource(s) && !vertexResources.contains(resourceName(s)) && seen.add(resourceName(s))) out.append(resources.get(resourceName(s))).append('\n');
        }
        final java.util.Set<String> vertexMacros = new java.util.HashSet<>();
        for (String s : vs) {
            final Matcher macro = OBJECT_MACRO.matcher(s);
            if (macro.find()) vertexMacros.add(macro.group(1));
        }
        for (String macro : borrowedMacros) if (!vertexMacros.contains(macro)) out.append("#undef ").append(macro).append('\n');

        for (String s : vs) {
            if (isHeaderLine(s) || isOutput(s) || STAGE_ONLY.matcher(s.trim()).matches()) continue;
            final String statement = isResource(s) ? resources.get(resourceName(s)) : s;
            out.append(renamed(MAIN.matcher(statement).replaceAll("void angelica_v_main("), renames)).append('\n');
        }

        out.append("void main() {\n    if (").append(STAGE).append(" == 0) {\n");
        out.append("        angelica_v_gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n        for (int i = 0; i < ").append(CLIP_SLOTS).append("; i++) { angelica_v_gl_ClipDistance[i] = 1.0; angelica_v_gl_CullDistance[i] = 1.0; }\n");
        out.append("        angelica_v_main();\n");
        out.append("        int at = (gl_InstanceID - ").append(FIRST_INSTANCE).append(") * ").append(SPAN).append(" + gl_VertexID - ").append(BASE).append(";\n");
        for (Field f : vertexFields) {
            out.append("        angelica_vertices[at].").append(f.name).append(" = ").append(vertexValue(f, vsOut)).append(";\n");
        }
        out.append("        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n    } else {\n");
        out.append("        AngelicaEmitted e = angelica_emitted[gl_VertexID];\n        gl_Position = e.b_position;\n");
        // Only the first stream reaches the rasterizer.
        if (emittedFields.stream().anyMatch(f -> f.name.equals("b_stream"))) out.append("        if (e.b_stream != 0) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n");
        if (pointSize) out.append("        gl_PointSize = e.b_pointSize;\n");
        out.append("        for (int i = 0; i < ").append(CLIP_SLOTS).append("; i++) gl_ClipDistance[i] = e.b_clip[i];\n");
        if (primitiveId) out.append("        angelica_primitiveId = e.b_primitive;\n");
        // A layer past the framebuffer is out of the image, where devices would wrap it onto one that is there.
        if (layer) out.append("        gl_Layer = e.b_layer;\n        if (e.b_layer < 0 || e.b_layer >= ").append(LAYER_LIMIT).append(") gl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n");
        if (gs.stream().anyMatch(s -> s.contains("gl_ViewportIndex"))) out.append("        gl_ViewportIndex = e.b_viewport;\n");
        for (Io io : gsOut) {
            if (io.isBlock()) {
                for (Io m : io.members) {
                    out.append("        ").append(io.name.isEmpty() ? "" : io.name + ".").append(m.name).append(" = e.f_").append(io.blockType).append('_').append(m.name).append(";\n");
                }
            } else {
                out.append("        ").append(io.name).append(" = e.f_").append(io.name).append(";\n");
            }
        }
        out.append("    }\n}\n");
        return out.toString();
    }

    private static String vertexValue(Field f, List<Io> vsOut) {
        return switch (f.name) {
            case "b_position" -> "angelica_v_gl_Position";
            case "b_pointSize" -> "angelica_v_gl_PointSize";
            case "b_clip" -> "angelica_v_gl_ClipDistance";
            case "b_cull" -> "angelica_v_gl_CullDistance";
            default -> {
                for (Io io : vsOut) {
                    if (io.isBlock() && !io.name.isEmpty()) {
                        for (Io m : io.members) {
                            if (f.name.equals("f_" + io.blockType + "_" + m.name)) yield "angelica_v_" + io.name + "." + m.name;
                        }
                    }
                }
                yield "angelica_v_" + f.source;
            }
        };
    }

    private static String declarationOfOutput(Io io) {
        final StringBuilder out = new StringBuilder(io.qualifiers).append(" out ");
        if (io.isBlock()) {
            out.append(io.blockType).append(" {\n");
            for (Io m : io.members) out.append("    ").append(m.qualifiers).append(' ').append(m.type).append(' ').append(m.name).append(m.inner).append(";\n");
            out.append("} ").append(io.name).append(io.inner).append(';');
        } else {
            out.append(io.type).append(' ').append(io.name).append(io.inner).append(';');
        }
        return out.toString();
    }

    private static String renamed(String statement, Map<String, String> renames) {
        final Matcher word = Pattern.compile("(?<![\\w.])[A-Za-z_]\\w*").matcher(statement);
        final StringBuilder out = new StringBuilder();
        while (word.find()) word.appendReplacement(out, Matcher.quoteReplacement(renames.getOrDefault(word.group(), word.group())));
        word.appendTail(out);
        return out.toString();
    }

    private static final Pattern OBJECT_MACRO = Pattern.compile("^\\s*#\\s*define\\s+(\\w+)(?:\\s|$)");
    private static final Pattern BLOCK_NAME = Pattern.compile("(\\w+)\\s*\\{"), VARIABLE_NAME = Pattern.compile("(\\w+)\\s*(?:\\[[^\\]]*\\]\\s*)*(?:=[^;]*)?;?\\s*$");

    /** A block goes by its block name across stages, and a variable by its own. */
    private static String resourceName(String statement) {
        final Matcher block = BLOCK_NAME.matcher(statement);
        if (block.find()) return block.group(1);
        final Matcher variable = VARIABLE_NAME.matcher(withoutLayout(statement));
        return variable.find() ? variable.group(1) : statement;
    }

    private static boolean isResource(String statement) {
        final String head = withoutLayout(statement);
        return head.matches("(?s)^\\s*(?:(?:readonly|writeonly|coherent|volatile|restrict|highp|mediump|lowp|shared|packed|std140|std430)\\s+)*(?:uniform|buffer)\\b.*");
    }

    private static boolean isInterface(String statement) {
        return isOutput(statement) || isInput(statement);
    }

    private static boolean isOutput(String statement) {
        return hasStorage(statement, "out");
    }

    private static boolean isInput(String statement) {
        return hasStorage(statement, "in");
    }

    private static boolean hasStorage(String statement, String storage) {
        final String head = withoutLayout(statement).trim();
        if (head.startsWith("#") || head.startsWith("uniform") || head.startsWith("struct")) return false;
        final int brace = head.indexOf('{'), paren = head.indexOf('('), bracket = head.indexOf('[');
        if (paren >= 0 && (brace < 0 || paren < brace) && (bracket < 0 || paren < bracket)) return false;
        final String before = brace >= 0 ? head.substring(0, brace) : head;
        for (String token : before.trim().split("\\s+")) {
            if (token.equals(storage)) return true;
            if (!QUALIFIERS.contains(token)) return false;
        }
        return false;
    }

    private static String withoutLayout(String statement) {
        return LAYOUT.matcher(statement).replaceAll(" ");
    }

    private static List<Io> interfaceOf(List<String> statements, String storage, boolean perVertex) {
        final List<Io> found = new ArrayList<>();
        for (String s : statements) {
            if (!hasStorage(s, storage) || STAGE_LAYOUT.matcher(s.trim()).matches()) continue;
            parseIo(s, storage, perVertex, found);
        }
        return found;
    }

    /** Takes a declaration apart, leaving out those of the built in vertex block, and the outermost array size of what a geometry shader takes per vertex. */
    private static void parseIo(String statement, String storage, boolean perVertex, List<Io> into) {
        final Matcher layout = LAYOUT.matcher(statement);
        final String layoutText = layout.find() ? withoutStream(layout.group()) : "";
        final Matcher location = LOCATION.matcher(layoutText);
        final int at = location.find() ? Integer.parseInt(location.group(1)) : -1;
        final String head = withoutLayout(statement).trim();
        final StringBuilder qualifiers = new StringBuilder(layoutText);
        final String[] tokens = head.substring(0, head.endsWith(";") ? head.length() - 1 : head.length()).trim().split("\\s+", -1);
        int t = 0;
        while (t < tokens.length && QUALIFIERS.contains(tokens[t])) {
            if (!tokens[t].equals(storage)) qualifiers.append(tokens[t]).append(' ');
            t++;
        }
        final String rest = String.join(" ", java.util.Arrays.copyOfRange(tokens, t, tokens.length));
        final int brace = rest.indexOf('{');
        if (brace >= 0) {
            final String blockType = rest.substring(0, brace).trim();
            if (blockType.equals("gl_PerVertex")) return;
            final int close = rest.lastIndexOf('}');
            final List<Io> members = new ArrayList<>();
            for (String member : rest.substring(brace + 1, close).split(";")) {
                if (member.isBlank()) continue;
                final String[] parts = LAYOUT.matcher(member).replaceAll("").trim().split("\\s+");
                final StringBuilder memberQualifiers = new StringBuilder();
                int p = 0;
                while (p < parts.length && QUALIFIERS.contains(parts[p])) memberQualifiers.append(parts[p++]).append(' ');
                final String type = parts[p++];
                for (String declarator : String.join(" ", java.util.Arrays.copyOfRange(parts, p, parts.length)).split(",")) {
                    final String[] named = nameAndArray(declarator.trim());
                    members.add(new Io(memberQualifiers.toString().trim(), type, named[0], named[1], null, -1, List.of()));
                }
            }
            final String[] named = nameAndArray(rest.substring(close + 1).trim());
            into.add(new Io(qualifiers.toString().trim(), null, named[0], perVertex && !qualifiers.toString().contains("patch") ? innerOf(named[1]) : named[1], blockType, at, members));
            return;
        }
        final int space = rest.indexOf(' ');
        String type = space < 0 ? rest : rest.substring(0, space);
        String prefixArray = "";
        final int typeBracket = type.indexOf('[');
        if (typeBracket >= 0) {
            prefixArray = type.substring(typeBracket);
            type = type.substring(0, typeBracket);
        }
        for (String declarator : (space < 0 ? "" : rest.substring(space + 1)).split(",")) {
            if (declarator.isBlank()) continue;
            final String[] named = nameAndArray(declarator.trim());
            if (BUILTIN_VERTEX.contains(named[0])) continue;
            final String array = prefixArray + named[1];
            into.add(new Io(qualifiers.toString().trim(), type, named[0], perVertex && !qualifiers.toString().contains("patch") ? innerOf(array) : array, null, at, List.of()));
        }
    }

    /** A layout qualifier without the vertex stream, which says nothing once a stage's outputs are stored. */
    private static String withoutStream(String layout) {
        final String kept = layout.replaceAll("\\bstream\\s*=\\s*\\w+\\s*,?\\s*", "").replaceAll(",\\s*\\)", ")");
        return kept.matches("layout\\s*\\(\\s*\\)") ? "" : kept + " ";
    }

    private static String innerOf(String array) {
        final int close = array.indexOf(']');
        return close < 0 ? array : array.substring(close + 1);
    }

    private static String[] nameAndArray(String declarator) {
        final int bracket = declarator.indexOf('[');
        return bracket < 0 ? new String[] {declarator, ""} : new String[] {declarator.substring(0, bracket).trim(), declarator.substring(bracket).replace(" ", "")};
    }

    static String stripComments(String source) {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (source.startsWith("/*", i)) {
                final int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else {
                out.append(source.charAt(i++));
            }
        }
        return out.toString();
    }

    /** Splits a shader at the top level: preprocessor lines, declarations and whole functions. */
    static List<String> statements(String source) {
        final List<String> out = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        int i = 0;
        boolean lineStart = true;
        while (i < source.length()) {
            final char c = source.charAt(i);
            if (lineStart && depth == 0 && current.toString().isBlank()) {
                int j = i;
                while (j < source.length() && (source.charAt(j) == ' ' || source.charAt(j) == '\t')) j++;
                if (j < source.length() && source.charAt(j) == '#') {
                    int end = source.indexOf('\n', j);
                    if (end < 0) end = source.length();
                    while (end > 0 && end < source.length() && source.charAt(end - 1) == '\\') {
                        end = source.indexOf('\n', end + 1);
                        if (end < 0) end = source.length();
                    }
                    out.add(source.substring(j, end));
                    current.setLength(0);
                    i = end;
                    continue;
                }
            }
            lineStart = c == '\n';
            current.append(c);
            i++;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0 && isFunction(current.toString())) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                }
            } else if (c == ';' && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) out.add(current.toString().trim());
        return out;
    }

    private static boolean isFunction(String text) {
        final String head = withoutLayout(text);
        final int brace = head.indexOf('{');
        return brace >= 0 && head.substring(0, brace).contains("(");
    }
}
