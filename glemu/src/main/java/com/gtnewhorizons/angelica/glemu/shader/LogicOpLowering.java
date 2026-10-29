package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess.Edit;
import com.gtnewhorizons.angelica.glsm.shader.ShaderIndex;
import org.antlr.v4.runtime.Token;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess.applyEdits;
import static com.gtnewhorizons.angelica.glemu.resource.PixelFormats.*;

public final class LogicOpLowering {
    public static final int OP_COPY = 12;
    public static final int MAX_LOCATIONS = 8;
    public static final String DST_SAMPLER_PREFIX = "angelica_LogicOpDst";

    private static final String MAIN_RENAME = "angelica_logicOpMain";
    private static final String SNIPPET = """
        vec4 angelica_logicOp(vec4 src, sampler2D dstTex, vec4 maxv) {
            uvec4 s = uvec4(roundEven(clamp(src, 0.0, 1.0) * maxv));
            uvec4 d = uvec4(roundEven(clamp(texelFetch(dstTex, ivec2(gl_FragCoord.xy), 0), 0.0, 1.0) * maxv));
            uvec4 t0 = uvec4(0u - (ANGELICA_LOGIC_OP & 1u));
            uvec4 t1 = uvec4(0u - ((ANGELICA_LOGIC_OP >> 1u) & 1u));
            uvec4 t2 = uvec4(0u - ((ANGELICA_LOGIC_OP >> 2u) & 1u));
            uvec4 t3 = uvec4(0u - ((ANGELICA_LOGIC_OP >> 3u) & 1u));
            uvec4 r = ((~s & ~d & t0) | (~s & d & t1) | (s & ~d & t2) | (s & d & t3)) & uvec4(maxv);
            return mix(src, vec4(r) / max(maxv, vec4(1.0)), greaterThan(maxv, vec4(0.0)));
        }
        """;

    private static final int CLASS_RGBA8 = 1, CLASS_R8 = 2, CLASS_RG8 = 3, CLASS_RGBA16 = 4, CLASS_R16 = 5, CLASS_RG16 = 6, CLASS_RGB10A2 = 7;

    private static final int[] MESA_OP = { 0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15 };

    private static final byte[][] BITS = {
        { 0, 0, 0, 0 },
        { 8, 8, 8, 8 },
        { 8, 0, 0, 0 },
        { 8, 8, 0, 0 },
        { 16, 16, 16, 16 },
        { 16, 0, 0, 0 },
        { 16, 16, 0, 0 },
        { 10, 10, 10, 2 },
    };

    private record Output(String name, int location, int arrayLen, int vecSize) {}

    private LogicOpLowering() {}

    public static int mesaOp(int glOpcode) {
        return MESA_OP[glOpcode & 0xF];
    }

    public static int classOf(int vkFormat) {
        return switch (vkFormat) {
            case PF_RGBA8_UNORM, PF_BGRA8_UNORM -> CLASS_RGBA8;
            case PF_R8_UNORM -> CLASS_R8;
            case PF_RG8_UNORM -> CLASS_RG8;
            case PF_RGBA16_UNORM -> CLASS_RGBA16;
            case PF_R16_UNORM -> CLASS_R16;
            case PF_RG16_UNORM -> CLASS_RG16;
            case PF_RGB10A2_UNORM -> CLASS_RGB10A2;
            default -> 0;
        };
    }

    public static long withClass(long key, int location, int cls) {
        return key | ((long) cls << (4 + 4 * location));
    }

    public static int classAt(long key, int location) {
        return (int) (key >>> (4 + 4 * location)) & 0xF;
    }

    public static int opOf(long key) {
        return (int) (key & 0xF);
    }

    private static float channelMax(int cls, int channel) {
        final int bits = BITS[cls][channel];
        return bits == 0 ? 0.0f : (float) ((1 << bits) - 1);
    }

    public static String lower(String preprocessedFragmentSource, long key, int program) {
        final GLSLParser.Translation_unitContext root = GlslTransformUtils.parseFullQuiet(preprocessedFragmentSource);
        final List<Edit> edits = new ArrayList<>();
        final List<Output> outputs = new ArrayList<>();
        boolean renamed = false;

        for (GLSLParser.External_declarationContext ext : root.external_declaration()) {
            final GLSLParser.Function_definitionContext fn = ext.function_definition();
            if (fn != null) {
                renamed |= renameMain(fn.function_prototype(), edits);
                continue;
            }
            final GLSLParser.DeclarationContext decl = ext.declaration();
            if (decl == null) continue;
            if (decl.function_prototype() != null) {
                renameMain(decl.function_prototype(), edits);
                continue;
            }
            collectOutputs(decl, outputs, program);
        }
        if (!renamed) throw new IllegalStateException("glLogicOp lowering: program " + program + " fragment shader has no main()");

        final String[] exprByLoc = new String[MAX_LOCATIONS];
        final int[] sizeByLoc = new int[MAX_LOCATIONS];
        resolveLocations(outputs, exprByLoc, sizeByLoc, program);

        final StringBuilder sb = new StringBuilder(applyEdits(preprocessedFragmentSource, edits));
        sb.append("\n#define ANGELICA_LOGIC_OP ").append(opOf(key)).append("u\n");
        sb.append(SNIPPET).append('\n');
        for (int loc = 0; loc < MAX_LOCATIONS; loc++) {
            if (classAt(key, loc) == 0 || exprByLoc[loc] == null) continue;
            if (sizeByLoc[loc] == 0) throw new IllegalStateException("glLogicOp lowering: program " + program + " output at location " + loc + " is not a float vector");
            sb.append("uniform sampler2D ").append(DST_SAMPLER_PREFIX).append(loc).append(";\n");
        }
        sb.append("void main() {\n    ").append(MAIN_RENAME).append("();\n");
        for (int loc = 0; loc < MAX_LOCATIONS; loc++) {
            final int cls = classAt(key, loc);
            if (cls == 0 || exprByLoc[loc] == null) continue;
            final String out = exprByLoc[loc];
            final int n = sizeByLoc[loc];
            sb.append("    ").append(out).append(" = angelica_logicOp(").append(widen(out, n)).append(", ").append(DST_SAMPLER_PREFIX).append(loc).append(", vec4(");
            for (int c = 0; c < 4; c++) {
                if (c > 0) sb.append(", ");
                sb.append(channelMax(cls, c));
            }
            sb.append("))").append(narrow(n)).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean renameMain(GLSLParser.Function_prototypeContext proto, List<Edit> edits) {
        if (proto == null || proto.IDENTIFIER() == null || !"main".equals(proto.IDENTIFIER().getText())) return false;
        final Token t = proto.IDENTIFIER().getSymbol();
        edits.add(new Edit(t.getStartIndex(), t.getStopIndex(), MAIN_RENAME));
        return true;
    }

    private static void collectOutputs(GLSLParser.DeclarationContext decl, List<Output> outputs, int program) {
        final GLSLParser.Init_declarator_listContext idl = decl.init_declarator_list();
        if (idl == null) return;
        final GLSLParser.Single_declarationContext single = idl.single_declaration();
        if (single == null || single.fully_specified_type() == null) return;
        final GLSLParser.Fully_specified_typeContext fst = single.fully_specified_type();
        if (fst.type_qualifier() == null) return;

        boolean isOut = false;
        int location = -1;
        int index = 0;
        for (GLSLParser.Single_type_qualifierContext stq : fst.type_qualifier().single_type_qualifier()) {
            if (stq.storage_qualifier() != null && stq.storage_qualifier().OUT() != null) isOut = true;
            final GLSLParser.Layout_qualifierContext lq = stq.layout_qualifier();
            if (lq == null) continue;
            for (GLSLParser.Layout_qualifier_idContext id : lq.layout_qualifier_id_list().layout_qualifier_id()) {
                if (id.IDENTIFIER() == null || id.constant_expression() == null) continue;
                final String qname = id.IDENTIFIER().getText();
                if ("location".equals(qname)) location = parseInt(id.constant_expression().getText(), program);
                else if ("index".equals(qname)) index = parseInt(id.constant_expression().getText(), program);
            }
        }
        if (!isOut || index != 0) return;

        final GLSLParser.Type_specifierContext ts = fst.type_specifier();
        final int vecSize = floatVecSize(ts.type_specifier_nonarray().getText());
        final int typeArrayLen = ts.array_specifier() != null ? arrayLen(ts.array_specifier(), program) : 0;

        int nextLocation = location;
        for (GLSLParser.Typeless_declarationContext td : ShaderIndex.declarators(idl)) {
            final int len = td.array_specifier() != null ? arrayLen(td.array_specifier(), program) : typeArrayLen;
            outputs.add(new Output(td.IDENTIFIER().getText(), nextLocation, len, vecSize));
            if (nextLocation >= 0) nextLocation += Math.max(len, 1);
        }
    }

    private static void resolveLocations(List<Output> outputs, String[] exprByLoc, int[] sizeByLoc, int program) {
        int unlocated = 0;
        for (Output o : outputs) if (o.location() < 0) unlocated++;
        if (unlocated > 0 && outputs.size() > 1) {
            throw new IllegalStateException("glLogicOp lowering: program " + program + " declares " + outputs.size() + " fragment outputs without explicit locations");
        }
        for (Output o : outputs) {
            final int base = Math.max(o.location(), 0);
            final int count = Math.max(o.arrayLen(), 1);
            for (int k = 0; k < count; k++) {
                final int loc = base + k;
                if (loc >= MAX_LOCATIONS) throw new IllegalStateException("glLogicOp lowering: program " + program + " output " + o.name() + " exceeds location " + (MAX_LOCATIONS - 1));
                if (exprByLoc[loc] != null) throw new IllegalStateException("glLogicOp lowering: program " + program + " has two outputs at location " + loc);
                exprByLoc[loc] = o.arrayLen() > 0 ? o.name() + "[" + k + "]" : o.name();
                sizeByLoc[loc] = o.vecSize();
            }
        }
    }

    private static int arrayLen(GLSLParser.Array_specifierContext spec, int program) {
        final List<GLSLParser.DimensionContext> dims = spec.dimension();
        if (dims.size() != 1 || dims.get(0).constant_expression() == null) {
            throw new IllegalStateException("glLogicOp lowering: program " + program + " has an unsized or multidimensional fragment output array");
        }
        return parseInt(dims.get(0).constant_expression().getText(), program);
    }

    private static int parseInt(String text, int program) {
        final String t = text.endsWith("u") || text.endsWith("U") ? text.substring(0, text.length() - 1) : text;
        try {
            return Integer.decode(t);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("glLogicOp lowering: program " + program + " uses non-literal constant '" + text + "' in a fragment output declaration");
        }
    }

    private static int floatVecSize(String type) {
        return switch (type) {
            case "float" -> 1;
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> 0;
        };
    }

    private static String widen(String expr, int n) {
        return switch (n) {
            case 1 -> "vec4(" + expr + ", 0.0, 0.0, 0.0)";
            case 2 -> "vec4(" + expr + ", 0.0, 0.0)";
            case 3 -> "vec4(" + expr + ", 0.0)";
            default -> expr;
        };
    }

    private static String narrow(int n) {
        return switch (n) {
            case 1 -> ".x";
            case 2 -> ".xy";
            case 3 -> ".xyz";
            default -> "";
        };
    }
}
