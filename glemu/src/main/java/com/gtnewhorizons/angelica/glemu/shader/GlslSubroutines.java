package com.gtnewhorizons.angelica.glemu.shader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers GLSL subroutines, which SPIR-V cannot express, in the source: each subroutine uniform becomes an int holding the subroutine's
 * index, and calls through it a switch over the functions compatible with its type.
 */
public final class GlslSubroutines {

    private static final Pattern TYPE = Pattern.compile("\\bsubroutine\\s+(\\w+(?:\\s*\\[[^\\]]*\\])?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*;");
    private static final Pattern FUNCTION = Pattern.compile("(?:\\blayout\\s*\\([^)]*\\)\\s*)?\\bsubroutine\\s*\\(([^)]*)\\)\\s*(\\w+(?:\\s*\\[[^\\]]*\\])?)\\s+(\\w+)\\s*\\(");
    private static final Pattern EXPLICIT_INDEX = Pattern.compile("\\bindex\\s*=\\s*(\\w+)"), EXPLICIT_LOCATION = Pattern.compile("\\blayout\\s*\\([^)]*\\blocation\\s*=\\s*(\\w+)");
    private static final Pattern UNIFORM = Pattern.compile("(?:\\blayout\\s*\\([^)]*\\)\\s*)?\\bsubroutine\\s+uniform\\s+(\\w+)\\s+(\\w+)\\s*((?:\\[\\s*\\d+\\s*\\]\\s*)*);");
    private static final Pattern DIMENSION = Pattern.compile("\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^\\s*#\\s*extension\\s+GL_ARB_shader_subroutine\\b.*$");
    private static final String DISPATCH_PREFIX = "angelica_subroutine_";
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private static final int MAX_SUBROUTINES = 256, MAX_LOCATIONS = 1024;

    /** What a stage declares, in the order that gives subroutines and their uniforms their indices. */
    public record Declared(List<String> functions, List<String> uniforms, Map<String, List<Integer>> compatible, Map<String, Integer> sizes,
                           Map<String, Integer> functionIndices, Map<String, Integer> uniformLocations, Map<String, int[]> dimensions) {
        public static Declared none() {
            return new Declared(List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        /** The element of a uniform that subscripts like [1][2] pick, counted through all its dimensions, or -1. */
        public int element(String uniform, String subscripts) {
            final int[] sizes = dimensions.getOrDefault(uniform, new int[0]);
            final Matcher subscript = DIMENSION.matcher(subscripts);
            int element = 0;
            int given = 0;
            while (subscript.find()) {
                if (given >= sizes.length) return -1;
                final int at = Integer.parseInt(subscript.group(1));
                if (at >= sizes[given]) return -1;
                element = element * sizes[given++] + at;
            }
            for (int i = given; i < sizes.length; i++) element *= sizes[i];
            return element;
        }

        public int size(String uniform) {
            return sizes.getOrDefault(uniform, 1);
        }

        /** A uniform's first location. Each element of an array has one of its own. */
        public int location(String uniform) {
            return uniformLocations.getOrDefault(uniform, -1);
        }

        public int locations() {
            int count = 0;
            for (String uniform : uniforms) count = Math.max(count, location(uniform) + size(uniform));
            return count;
        }

        /** One past the highest index: an application can leave some out. */
        public int functionCount() {
            int count = 0;
            for (int index : functionIndices.values()) count = Math.max(count, index + 1);
            return count;
        }

        /** What keeps a program with these declarations from linking, or null. */
        public String problem() {
            final java.util.Set<Integer> indices = new java.util.HashSet<>();
            for (Map.Entry<String, Integer> function : functionIndices.entrySet()) {
                if (function.getValue() >= MAX_SUBROUTINES) return "subroutine " + function.getKey() + " has an index over GL_MAX_SUBROUTINES";
                if (!indices.add(function.getValue())) return "two subroutines have the index " + function.getValue();
            }
            final java.util.Set<Integer> taken = new java.util.HashSet<>();
            for (String uniform : uniforms) {
                if (compatible.getOrDefault(uniform, List.of()).isEmpty()) return "no subroutine has the type of the subroutine uniform " + uniform;
                for (int i = 0; i < size(uniform); i++) {
                    final int at = location(uniform) + i;
                    if (at >= MAX_LOCATIONS) return "subroutine uniform " + uniform + " has a location over GL_MAX_SUBROUTINE_UNIFORM_LOCATIONS";
                    if (!taken.add(at)) return "two subroutine uniforms have the location " + at;
                }
            }
            return null;
        }

        public int functionIndex(String function) {
            return functionIndices.getOrDefault(function, -1);
        }

        public String functionAt(int index) {
            for (Map.Entry<String, Integer> entry : functionIndices.entrySet()) if (entry.getValue() == index) return entry.getKey();
            return "";
        }
    }

    private record Type(String result, String parameters) {}

    private GlslSubroutines() {}

    public static boolean uses(String source) {
        return source.contains("subroutine");
    }

    private static int[] dimensionsOf(String subscripts) {
        final Matcher dimension = DIMENSION.matcher(subscripts);
        final List<Integer> sizes = new ArrayList<>();
        while (dimension.find()) sizes.add(Integer.parseInt(dimension.group(1)));
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    public static Declared declared(String source) {
        final List<String> functions = new ArrayList<>(), uniforms = new ArrayList<>();
        final Map<String, List<String>> byType = new LinkedHashMap<>();
        final Map<String, Integer> functionIndices = new LinkedHashMap<>(), uniformLocations = new LinkedHashMap<>();
        final Matcher function = FUNCTION.matcher(source);
        while (function.find()) {
            functions.add(function.group(3));
            final Matcher explicit = EXPLICIT_INDEX.matcher(function.group().substring(0, function.group().indexOf("subroutine")));
            if (explicit.find()) functionIndices.put(function.group(3), Integer.decode(explicit.group(1)));
            for (String type : function.group(1).split(",")) {
                byType.computeIfAbsent(type.trim(), k -> new ArrayList<>()).add(function.group(3));
            }
        }
        final Map<String, List<Integer>> compatible = new LinkedHashMap<>();
        final Map<String, Integer> sizes = new LinkedHashMap<>();
        final Map<String, int[]> dimensions = new LinkedHashMap<>();
        final Matcher uniform = UNIFORM.matcher(source);
        while (uniform.find()) {
            uniforms.add(uniform.group(2));
            if (!uniform.group(3).isEmpty()) {
                final int[] sized = dimensionsOf(uniform.group(3));
                dimensions.put(uniform.group(2), sized);
                sizes.put(uniform.group(2), java.util.Arrays.stream(sized).reduce(1, (a, b) -> a * b));
            }
            final Matcher explicit = EXPLICIT_LOCATION.matcher(uniform.group());
            if (explicit.find()) uniformLocations.put(uniform.group(2), Integer.decode(explicit.group(1)));
        }
        // What is not given an index or location takes the first free one.
        int nextIndex = 0;
        for (String name : functions) {
            if (functionIndices.containsKey(name)) continue;
            while (functionIndices.containsValue(nextIndex)) nextIndex++;
            functionIndices.put(name, nextIndex);
        }
        for (String name : uniforms) {
            if (uniformLocations.containsKey(name)) continue;
            final int size = sizes.getOrDefault(name, 1);
            int at = 0;
            while (locationsTaken(uniformLocations, sizes, at, size)) at++;
            uniformLocations.put(name, at);
        }
        final Matcher again = UNIFORM.matcher(source);
        while (again.find()) {
            final List<Integer> indices = new ArrayList<>();
            for (String name : byType.getOrDefault(again.group(1), List.of())) indices.add(functionIndices.get(name));
            compatible.put(again.group(2), indices);
        }
        return new Declared(functions, uniforms, compatible, sizes, functionIndices, uniformLocations, dimensions);
    }

    private static boolean locationsTaken(Map<String, Integer> taken, Map<String, Integer> sizes, int at, int size) {
        for (Map.Entry<String, Integer> entry : taken.entrySet()) {
            final int first = entry.getValue(), last = first + sizes.getOrDefault(entry.getKey(), 1);
            if (at < last && first < at + size) return true;
        }
        return false;
    }

    /** The int a stage's subroutine uniform becomes: the stages of a program can each have their own by one name. */
    public static String uniformName(int glType, String name) {
        return ProgramBase.STAGE_UNIFORM_PREFIX + glType + "_" + name;
    }

    /** The name one element of that int goes by, to set it through the program. */
    public static String elementName(int glType, Declared declared, String name, int element) {
        return uniformName(glType, name) + (declared.size(name) > 1 ? "[" + element + "]" : "");
    }

    private static String refusal(String source, Map<String, Type> types, Declared declared) {
        final String code = COMMENT.matcher(source).replaceAll(" ");
        final Matcher function = FUNCTION.matcher(code);
        while (function.find()) {
            final int close = code.indexOf(')', function.end());
            if (close < 0) continue;
            final List<String> parameters = signature(code.substring(function.end(), close));
            for (String typeName : function.group(1).split(",")) {
                final Type type = types.get(typeName.trim());
                if (type == null) return "subroutine " + function.group(3) + " has the undeclared type " + typeName.trim();
                if (!compact(function.group(2)).equals(compact(type.result())) || !parameters.equals(signature(type.parameters()))) {
                    return "subroutine " + function.group(3) + " does not match its type " + typeName.trim();
                }
            }
        }
        for (String name : declared.uniforms()) {
            final String element = "(?:\\s*\\[[^\\]]*\\])?";
            if (Pattern.compile("\\b" + name + "\\b" + element + "\\s*(?:==|!=)|(?:==|!=)\\s*\\b" + name + "\\b(?!\\s*(?:\\(|\\.|\\[[^\\]]*\\]\\s*\\())").matcher(code).find()) {
                return "subroutine uniform " + name + " cannot be compared";
            }
        }
        return null;
    }

    private static String compact(String type) {
        return type.replaceAll("\\s+", "");
    }

    /** Parameters without what they may differ in: names, and whether "in" is spelled out. */
    private static List<String> signature(String parameters) {
        final List<String> out = new ArrayList<>();
        final String trimmed = parameters.trim();
        if (trimmed.isEmpty() || trimmed.equals("void")) return out;
        for (String parameter : trimmed.split(",")) {
            final String spaced = parameter.trim().replaceAll("\\s+", " ").replaceAll("\\s*\\[\\s*", "[").replaceAll("\\s*\\]", "]");
            final StringBuilder array = new StringBuilder();
            final Matcher brackets = Pattern.compile("\\[[^\\]]*\\]").matcher(spaced);
            while (brackets.find()) array.append(brackets.group());
            final StringBuilder kept = new StringBuilder();
            boolean typed = false;
            for (String word : spaced.replaceAll("\\[[^\\]]*\\]", "").split(" ")) {
                if (word.equals("in") || word.equals("highp") || word.equals("mediump") || word.equals("lowp")) continue;
                if (PARAMETER_QUALIFIERS.contains(word)) kept.append(word).append(' ');
                else if (!typed) {
                    kept.append(word);
                    typed = true;
                }
            }
            out.add(kept.append(array).toString());
        }
        return out;
    }

    /** The stored uniform has one dimension, so that subscripts through many become one. */
    private static String flattenedCalls(String text, String name, int[] sizes) {
        final StringBuilder subscripts = new StringBuilder("\\b" + name);
        for (int i = 0; i < sizes.length; i++) subscripts.append("\\s*\\[([^\\]]*)\\]");
        final Matcher call = Pattern.compile(subscripts + "(\\s*\\()").matcher(text);
        return call.replaceAll(found -> {
            final StringBuilder flat = new StringBuilder("(int(" + found.group(1) + ")");
            for (int i = 1; i < sizes.length; i++) flat.insert(0, "(").append(" * ").append(sizes[i]).append(" + int(").append(found.group(i + 1)).append("))");
            return Matcher.quoteReplacement(name + "[" + flat + ")]" + found.group(sizes.length + 1));
        });
    }

    /** A subroutine type or uniform named here is gone from the code the compiler sees, which would not then refuse a reserved word as its name. */
    private static String nameProbe(java.util.Collection<String> types, java.util.Collection<String> uniforms) {
        final StringBuilder probe = new StringBuilder("void " + NAME_PROBE + "() {");
        for (String name : types) probe.append(" { int ").append(name).append("; }");
        for (String name : uniforms) probe.append(" { int ").append(name).append("; }");
        return probe.append(" }\n").toString();
    }

    public static String lower(String source, int glType) {
        if (!uses(source)) return source;
        final Declared declared = declared(source);
        final Map<String, Type> types = new LinkedHashMap<>();
        final Matcher type = TYPE.matcher(source);
        while (type.find()) types.put(type.group(2), new Type(type.group(1), named(type.group(3).trim())));
        final String refused = refusal(source, types, declared);
        if (refused != null) return source + "\n#error " + refused + "\n";

        String text = EXTENSION.matcher(source).replaceAll("");
        text = TYPE.matcher(text).replaceAll("");
        text = FUNCTION.matcher(text).replaceAll("$2 $3(");

        final StringBuilder dispatchers = new StringBuilder();
        final Matcher uniform = UNIFORM.matcher(text);
        final StringBuilder declaredUniforms = new StringBuilder();
        while (uniform.find()) {
            final Type t = types.get(uniform.group(1));
            final String name = uniform.group(2);
            final boolean array = !uniform.group(3).isEmpty();
            final String parameters = t == null ? "" : array ? ELEMENT + (t.parameters().isEmpty() || t.parameters().equals("void") ? "" : ", " + t.parameters()) : t.parameters();
            final String prototype = t == null ? "" : t.result() + " " + DISPATCH_PREFIX + name + "(" + parameters + ")";
            final String stored = uniformName(glType, name);
            uniform.appendReplacement(declaredUniforms, Matcher.quoteReplacement("uniform int " + stored + (array ? "[" + declared.size(name) + "]" : "") + ";\n" + (t == null ? "" : prototype + ";")));
            if (t != null) dispatchers.append(dispatcher(prototype, t, array ? stored + "[angelica_element]" : stored, name, declared));
        }
        uniform.appendTail(declaredUniforms);
        text = declaredUniforms.toString();
        for (String name : declared.uniforms()) {
            final int[] sizes = declared.dimensions().getOrDefault(name, new int[0]);
            if (sizes.length > 1) {
                text = text.replaceAll("\\b" + name + "\\s*\\[[^\\]]*\\]\\s*\\.\\s*length\\s*\\(\\s*\\)", String.valueOf(sizes[1]));
                text = text.replaceAll("\\b" + name + "\\s*\\.\\s*length\\s*\\(\\s*\\)", String.valueOf(sizes[0]));
                text = flattenedCalls(text, name, sizes);
            }
            text = text.replaceAll("\\b" + name + "\\s*\\[([^\\]]*)\\]\\s*\\(\\s*\\)", DISPATCH_PREFIX + name + "(int($1))");
            text = text.replaceAll("\\b" + name + "\\s*\\[([^\\]]*)\\]\\s*\\(", DISPATCH_PREFIX + name + "(int($1), ");
            text = text.replaceAll("\\b" + name + "(\\s*\\()", DISPATCH_PREFIX + name + "$1");
            text = text.replaceAll("\\b" + name + "(\\s*\\.\\s*length)", uniformName(glType, name) + "$1");
        }
        return text + "\n" + dispatchers + (text.contains(NAME_PROBE) ? "" : nameProbe(types.keySet(), declared.uniforms()));
    }

    private static final String NAME_PROBE = "angelica_subroutineNames";
    private static final String ELEMENT = "int angelica_element";

    private static String dispatcher(String prototype, Type t, String selected, String uniform, Declared declared) {
        final boolean returns = !t.result().equals("void");
        final String call = "(" + arguments(t.parameters()) + ");";
        final StringBuilder out = new StringBuilder(prototype).append(" {\n");
        if (returns) out.append("    ").append(t.result()).append(" unset;\n");
        out.append("    switch (").append(selected).append(") {\n");
        for (int index : declared.compatible().get(uniform)) {
            final String function = declared.functionAt(index);
            out.append("        case ").append(index).append(": ");
            out.append(returns ? "return " + function + call : function + call + " return;").append('\n');
        }
        out.append("    }\n");
        if (returns) out.append("    return unset;\n");
        return out.append("}\n").toString();
    }

    /** A subroutine type can leave its parameters unnamed, which a function of ours that passes them on cannot. */
    private static String named(String parameters) {
        if (parameters.isEmpty() || parameters.equals("void")) return parameters;
        final StringBuilder out = new StringBuilder();
        int index = 0;
        for (String parameter : parameters.split(",")) {
            final String trimmed = parameter.trim();
            int words = 0;
            for (String word : trimmed.split("\\s+")) {
                if (!PARAMETER_QUALIFIERS.contains(word)) words++;
            }
            if (out.length() > 0) out.append(", ");
            out.append(trimmed);
            if (words < 2) out.append(" angelica_argument_").append(index);
            index++;
        }
        return out.toString();
    }

    private static final java.util.Set<String> PARAMETER_QUALIFIERS = java.util.Set.of("in", "out", "inout", "const", "highp", "mediump", "lowp", "precise", "coherent", "volatile", "restrict", "readonly", "writeonly");

    private static String arguments(String parameters) {
        if (parameters.isEmpty() || parameters.equals("void")) return "";
        final StringBuilder out = new StringBuilder();
        for (String parameter : parameters.split(",")) {
            final String trimmed = parameter.trim().replaceAll("\\[.*]$", "").trim();
            if (out.length() > 0) out.append(", ");
            out.append(trimmed.substring(trimmed.lastIndexOf(' ') + 1));
        }
        return out.toString();
    }
}
