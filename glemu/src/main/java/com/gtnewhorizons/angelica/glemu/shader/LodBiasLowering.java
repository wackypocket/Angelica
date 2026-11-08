package com.gtnewhorizons.angelica.glemu.shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GL adds a texture's GL_TEXTURE_LOD_BIAS to every sample, explicit levels and a shader's own bias included, which Apple's samplers don't. So
 * each sampler gets a hidden uniform holding its bias, which the renderer sets before each draw and each sample adds. A sampler reaching a sample
 * as a function's parameter takes its bias along as another.
 */
public final class LodBiasLowering {
    /** The hidden uniform for sampler {@code s} is {@code angelica_lodBias_s}, an array when the sampler is one. */
    public static final String PREFIX = "angelica_lodBias_";
    private static final float MAX_BIAS = 15f;
    private static final int IMPLICIT = 0, EXPLICIT = 1, GRADIENT = 2;

    private static final Pattern SAMPLER = Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?([iu]?sampler\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;");
    private static final Pattern FUNCTION = Pattern.compile("\\b(\\w+)\\s+(\\w+)\\s*\\(([^()]*\\bsampler[^()]*)\\)\\s*([{;])");
    private static final Pattern PARAMETER = Pattern.compile("^(?:(?:in|const|lowp|mediump|highp)\\s+)*([iu]?sampler\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?$");
    private static final Pattern SAMPLE = Pattern.compile("\\b(texture(?:Proj)?(?:Lod|Grad)?(?:Offset)?|textureQueryLod)\\s*\\(");
    private static final Pattern SAMPLER_ARGUMENT = Pattern.compile("^(\\w+)\\s*(\\[.*\\])?$", Pattern.DOTALL);
    private static final Pattern MEMBER_ARGUMENT = Pattern.compile("^(\\w+)\\s*(?:\\[(.*?)\\])?\\s*\\.\\s*(\\w+)\\s*(?:\\[(.*)\\])?$", Pattern.DOTALL);
    private static final Pattern STRUCT = Pattern.compile("\\bstruct\\s+(\\w+)\\s*\\{([^}]*)\\}");
    private static final Pattern MEMBER = Pattern.compile("^(?:(?:lowp|mediump|highp)\\s+)?([iu]?sampler\\w+)\\s+(\\w+)\\s*(?:\\[([^\\]]*)\\])?$");
    private static final Pattern STRUCT_UNIFORM = Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*(?:\\[([^\\]]*)\\])?\\s*;");

    /** A sampler in a uniform struct: its type, and the array sizes of the struct uniform and of the member, null for not an array. */
    private record InStruct(String type, String elements, String memberElements) {}

    /** GL's name for a sampler in a uniform struct, {@code s[i].member[j]}, whose bias is {@code angelica_lodBias_s_member}. */
    private static final Pattern IN_STRUCT_NAME = Pattern.compile("^(\\w+)(?:\\[(\\d+)\\])?\\.(\\w+)(?:\\[(\\d+)\\])?$");

    public static String biasNameOf(String samplerName) {
        final Matcher in = IN_STRUCT_NAME.matcher(samplerName);
        if (in.matches()) return PREFIX + in.group(1) + "_" + in.group(3);
        return PREFIX + (samplerName.endsWith("[0]") ? samplerName.substring(0, samplerName.length() - 3) : samplerName);
    }

    /** The struct element and the member element a sampler in a struct is named with, each 0 when not an array; null for one not in a struct. */
    public static int[] structElementsOf(String samplerName) {
        final Matcher in = IN_STRUCT_NAME.matcher(samplerName);
        if (!in.matches()) return null;
        return new int[] {in.group(2) == null ? 0 : Integer.parseInt(in.group(2)), in.group(4) == null ? 0 : Integer.parseInt(in.group(4))};
    }

    private LodBiasLowering() {}

    /** Types a bias reaches: not rectangles, multisampled images or buffers, which have no levels, nor cube array shadows, which have no room. */
    private static boolean biased(String type) {
        final String plain = type.replaceFirst("^[iu]?sampler", "");
        return !plain.startsWith("2DRect") && !plain.startsWith("2DMS") && !plain.equals("Buffer") && !plain.equals("CubeArrayShadow");
    }

    /** A 2D array shadow has neither a bias nor a level to take one in, in core GLSL. */
    private static boolean takesBias(String type) {
        return !type.replaceFirst("^[iu]?sampler", "").equals("2DArrayShadow");
    }

    private static boolean hasLevels(String type) {
        final String plain = type.replaceFirst("^[iu]?sampler", "");
        return !plain.equals("CubeShadow") && !plain.equals("2DArrayShadow");
    }

    public static String lower(String source, boolean fragment) {
        if (!source.contains("sampler") || source.contains(PREFIX)) return source;
        final Map<String, String> types = new HashMap<>();
        final Matcher declared = SAMPLER.matcher(source);
        final StringBuilder out = new StringBuilder();
        int last = 0;
        while (declared.find()) {
            if (!biased(declared.group(1))) continue;
            types.put(declared.group(2), declared.group(1));
            out.append(source, last, declared.end()).append(" uniform float ").append(PREFIX).append(declared.group(2)).append(declared.group(3) == null ? "" : declared.group(3)).append(';');
            last = declared.end();
        }
        out.append(source.substring(last));
        String text = withStructBiases(out.toString());
        final Map<String, InStruct> inStructs = structSamplers(text);
        for (Map.Entry<String, InStruct> e : inStructs.entrySet()) types.put(e.getKey(), e.getValue().type());
        if (types.isEmpty() && !FUNCTION.matcher(text).find()) return source;

        // Functions taking samplers take each one's bias after it.
        final Map<String, List<Integer>> takes = new HashMap<>();
        final StringBuilder withParameters = new StringBuilder();
        final Matcher function = FUNCTION.matcher(text);
        last = 0;
        while (function.find()) {
            if (function.group(1).equals("return") || function.group(1).equals("else")) continue;
            final String[] parameters = function.group(3).split(",");
            final List<Integer> positions = new ArrayList<>();
            final StringBuilder list = new StringBuilder();
            for (int k = 0; k < parameters.length; k++) {
                if (k > 0) list.append(", ");
                final String parameter = parameters[k].trim();
                list.append(parameter);
                final Matcher p = PARAMETER.matcher(parameter);
                if (p.matches() && biased(p.group(1))) {
                    types.put(p.group(2), p.group(1));
                    positions.add(k);
                    list.append(", float ").append(PREFIX).append(p.group(2)).append(p.group(3) == null ? "" : p.group(3));
                }
            }
            if (positions.isEmpty()) continue;
            takes.put(function.group(2) + "/" + parameters.length, positions);
            withParameters.append(text, last, function.start(3)).append(list).append(text, function.end(3), function.end());
            last = function.end();
        }
        withParameters.append(text.substring(last));
        text = withParameters.toString();
        if (types.isEmpty()) return source;

        // Their calls pass each sampler's bias along, from the last so that a call inside another's arguments is done first.
        if (!takes.isEmpty()) {
            final Pattern call = Pattern.compile("\\b(" + String.join("|", takes.keySet().stream().map(k -> Pattern.quote(k.substring(0, k.indexOf('/')))).distinct().toList()) + ")\\s*\\(");
            final List<int[]> calls = new ArrayList<>();
            final Matcher m = call.matcher(text);
            while (m.find()) {
                if (isDefinition(text, m.start())) continue;
                calls.add(new int[] {m.start(), m.end()});
            }
            for (int i = calls.size() - 1; i >= 0; i--) {
                final List<String> args = new ArrayList<>();
                final int end = arguments(text, calls.get(i)[1], args);
                if (end < 0) continue;
                final String name = text.substring(calls.get(i)[0], calls.get(i)[1]).replaceAll("\\s*\\($", "");
                final List<Integer> positions = takes.get(name + "/" + args.size());
                if (positions == null) continue;
                final List<String> passed = new ArrayList<>();
                for (int k = 0; k < args.size(); k++) {
                    passed.add(args.get(k));
                    if (positions.contains(k)) {
                        final String bias = biasOf(args.get(k), types, inStructs);
                        passed.add(bias == null ? "0.0" : bias);
                    }
                }
                text = text.substring(0, calls.get(i)[1]) + String.join(", ", passed) + text.substring(end);
            }
        }

        // Every sample adds its sampler's bias.
        final List<int[]> samples = new ArrayList<>();
        final List<String> functions = new ArrayList<>();
        final Matcher sample = SAMPLE.matcher(text);
        while (sample.find()) {
            samples.add(new int[] {sample.start(), sample.end()});
            functions.add(sample.group(1));
        }
        for (int i = samples.size() - 1; i >= 0; i--) {
            final List<String> args = new ArrayList<>();
            final int end = arguments(text, samples.get(i)[1], args);
            if (end < 0 || args.size() < 2) continue;
            final String type = typeOf(args.get(0), types);
            final String bias = biasOf(args.get(0), types, inStructs);
            if (type == null || bias == null || !takesBias(type) && !functions.get(i).equals("textureQueryLod")) continue;
            final String rewritten = functions.get(i).equals("textureQueryLod")
                ? "angelica_biasedLod(textureQueryLod(" + String.join(", ", args) + "), " + bias + (hasLevels(type) ? ", textureQueryLevels(" + args.get(0) + "))" : ", 1)")
                : biasedCall(functions.get(i), args, bias, fragment, hasLevels(type));
            if (rewritten != null) text = text.substring(0, samples.get(i)[0]) + rewritten + text.substring(end + 1);
        }
        if (text.contains("angelica_biasedLod(")) text = withHelper(text);
        return text;
    }

    /** A query's level moves by the bias too, the level it picks staying within the texture's. */
    private static String withHelper(String text) {
        final String helper = "vec2 angelica_biasedLod(vec2 q, float bias, int levels) { return vec2(clamp(q.x + bias, 0.0, float(max(levels - 1, 0))), q.y + bias); }\n";
        final Matcher first = Pattern.compile("\\n[^\\n#]*\\b\\w+\\s+\\w+\\s*\\([^;{]*\\)\\s*\\{").matcher(text);
        if (!first.find()) return text;
        return text.substring(0, first.start() + 1) + helper + text.substring(first.start() + 1);
    }

    private static boolean isDefinition(String text, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i < 0 || !Character.isJavaIdentifierPart(text.charAt(i))) return false;
        final int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) i--;
        final String before = text.substring(i + 1, end);
        return !before.equals("return") && !before.equals("else");
    }

    private static String typeOf(String argument, Map<String, String> types) {
        final Matcher member = MEMBER_ARGUMENT.matcher(argument.trim());
        if (member.matches()) return types.get(member.group(1) + "." + member.group(3));
        final Matcher named = SAMPLER_ARGUMENT.matcher(argument.trim());
        return named.matches() ? types.get(named.group(1)) : null;
    }

    /** The bias of a sampler argument: its uniform, an element of one, a parameter's, or one in a struct, whose elements are counted as one array. */
    private static String biasOf(String argument, Map<String, String> types, Map<String, InStruct> inStructs) {
        final Matcher member = MEMBER_ARGUMENT.matcher(argument.trim());
        if (member.matches()) {
            final InStruct in = inStructs.get(member.group(1) + "." + member.group(3));
            if (in == null) return null;
            final String outer = member.group(2), inner = member.group(4);
            final String name = PREFIX + member.group(1) + "_" + member.group(3);
            if (outer == null && inner == null) return name;
            if (outer == null) return name + "[" + inner + "]";
            if (inner == null) return name + "[" + outer + "]";
            return name + "[(" + outer + ") * (" + in.memberElements() + ") + (" + inner + ")]";
        }
        final Matcher named = SAMPLER_ARGUMENT.matcher(argument.trim());
        if (!named.matches() || !types.containsKey(named.group(1))) return null;
        return PREFIX + named.group(1) + (named.group(2) == null ? "" : named.group(2));
    }

    private static Map<String, List<String[]>> structs(String text) {
        final Map<String, List<String[]>> found = new HashMap<>();
        final Matcher struct = STRUCT.matcher(text);
        while (struct.find()) {
            final List<String[]> samplers = new ArrayList<>();
            for (String member : struct.group(2).split(";")) {
                final Matcher m = MEMBER.matcher(member.trim());
                if (m.matches() && biased(m.group(1))) samplers.add(new String[] {m.group(1), m.group(2), m.group(3)});
            }
            if (!samplers.isEmpty()) found.put(struct.group(1), samplers);
        }
        return found;
    }

    /** Each sampler in a uniform struct, by the path a sample names it with. */
    private static Map<String, InStruct> structSamplers(String text) {
        final Map<String, List<String[]>> structs = structs(text);
        final Map<String, InStruct> found = new HashMap<>();
        if (structs.isEmpty()) return found;
        final Matcher uniform = STRUCT_UNIFORM.matcher(text);
        while (uniform.find()) {
            final List<String[]> samplers = structs.get(uniform.group(1));
            if (samplers == null) continue;
            for (String[] s : samplers) found.put(uniform.group(2) + "." + s[1], new InStruct(s[0], uniform.group(3), s[2]));
        }
        return found;
    }

    /** Declares a bias for each sampler in a uniform struct, after the struct uniform: one value for every unit its elements and the member's hold. */
    private static String withStructBiases(String text) {
        final Map<String, List<String[]>> structs = structs(text);
        if (structs.isEmpty()) return text;
        final StringBuilder out = new StringBuilder();
        final Matcher uniform = STRUCT_UNIFORM.matcher(text);
        int last = 0;
        while (uniform.find()) {
            final List<String[]> samplers = structs.get(uniform.group(1));
            if (samplers == null) continue;
            out.append(text, last, uniform.end());
            for (String[] s : samplers) {
                final String outer = uniform.group(3), inner = s[2];
                final String size = outer == null && inner == null ? "" : outer == null ? "[" + inner + "]" : inner == null ? "[" + outer + "]" : "[(" + outer + ") * (" + inner + ")]";
                out.append(" uniform float ").append(PREFIX).append(uniform.group(2)).append('_').append(s[1]).append(size).append(';');
            }
            last = uniform.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** The end of the call whose arguments start at {@code from}, with those arguments gathered. */
    static int arguments(String source, int from, List<String> into) {
        int depth = 0, start = from;
        for (int i = from; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ')') {
                if (depth == 0) {
                    into.add(source.substring(start, i).trim());
                    return i;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                into.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        return -1;
    }

    private static int family(String function) {
        if (function.contains("Grad")) return GRADIENT;
        return function.contains("Lod") ? EXPLICIT : IMPLICIT;
    }

    private static String biasedCall(String function, List<String> args, String bias, boolean fragment, boolean levels) {
        final String sampler = args.get(0), coordinate = args.get(1);
        final int at = family(function) == GRADIENT ? 4 : function.contains("Lod") ? 3 : 2;
        final String offset = function.contains("Offset") && args.size() > at ? args.get(at) : null;
        switch (family(function)) {
            case GRADIENT -> {
                // A level is the logarithm of the longest derivative, so scaling them all adds to it.
                if (args.size() < 4) return null;
                final String scale = "exp2(" + bias + ")";
                final String tail = offset == null ? "" : ", " + offset;
                return function + "(" + sampler + ", " + coordinate + ", (" + args.get(2) + ") * " + scale + ", (" + args.get(3) + ") * " + scale + tail + ")";
            }
            case EXPLICIT -> {
                if (args.size() < 3) return null;
                final String tail = offset == null ? "" : ", " + offset;
                return function + "(" + sampler + ", " + coordinate + ", (" + args.get(2) + ") + " + bias + tail + ")";
            }
            default -> {
                final int given = offset == null ? 2 : 3;
                if (args.size() > given) {
                    final String sum = "clamp((" + args.get(given) + ") + " + bias + ", " + -MAX_BIAS + ", " + MAX_BIAS + ")";
                    return function + "(" + sampler + ", " + coordinate + (offset == null ? "" : ", " + offset) + ", " + sum + ")";
                }
                if (fragment) return function + "(" + sampler + ", " + coordinate + (offset == null ? "" : ", " + offset) + ", " + bias + ")";
                if (!levels) return null;
                final String explicit = function.contains("Proj") ? "textureProjLod" : "textureLod";
                return explicit + (offset == null ? "" : "Offset") + "(" + sampler + ", " + coordinate + ", " + bias + (offset == null ? "" : ", " + offset) + ")";
            }
        }
    }
}
