package com.gtnewhorizons.angelica.glemu.shader;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What GL has a linker refuse across shaders, which compiling each stage on its own never sees. */
final class LinkChecks {
    private static final Pattern BLOCK = Pattern.compile("\\b(uniform|buffer)\\s+(\\w+)\\s*\\{(.*)\\}\\s*(\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*);?\\s*$", Pattern.DOTALL);
    private static final Pattern UNIFORM = Pattern.compile("\\buniform\\s+([^{;]*);?\\s*$", Pattern.DOTALL);
    private static final Pattern PRECISION = Pattern.compile("\\b(highp|mediump|lowp|precise)\\b");
    private static final Pattern DECLARATOR = Pattern.compile("(\\w+)\\s*((?:\\[[^\\]]*\\]\\s*)*)(?:=.*)?$", Pattern.DOTALL);
    private static final Pattern TYPE = Pattern.compile("^\\s*(?:(?:readonly|writeonly|coherent|volatile|restrict)\\s+)*(\\w+)\\s*((?:\\[[^\\]]*\\]\\s*)*)");
    private static final Pattern IMAGE_FORMAT = Pattern.compile("\\b(rgba32f|rgba16f|rg32f|rg16f|r11f_g11f_b10f|r32f|r16f|rgba16|rgb10_a2|rgba8|rg16|rg8|r16|r8|rgba16_snorm|rgba8_snorm|rg16_snorm|rg8_snorm|r16_snorm|r8_snorm|rgba32i|rgba16i|rgba8i|rg32i|rg16i|rg8i|r32i|r16i|r8i|rgba32ui|rgba16ui|rgb10_a2ui|rgba8ui|rg32ui|rg16ui|rg8ui|r32ui|r16ui|r8ui)\\b");
    private static final Pattern LAYOUT_VALUE_BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");
    private static final Pattern LAYOUT_VALUE_OFFSET = Pattern.compile("\\boffset\\s*=\\s*(\\d+)");
    private static final Pattern COUNTER_NAME = Pattern.compile("\\batomic_uint\\s+(\\w*)\\s*(?:\\[\\s*(\\d+)\\s*\\])?");
    private static final Pattern LOCAL_SIZE = Pattern.compile("\\blayout\\s*\\(((?:[^()]|\\([^()]*\\))*\\blocal_size_(?:[^()]|\\([^()]*\\))*)\\)\\s*in\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bmain\\s*\\(");
    private static final Pattern ARRAY = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static final Pattern PER_VERTEX = Pattern.compile("\\b(in|out)\\s+gl_PerVertex\\s*\\{([^}]*)\\}");
    private static final int[] PIPELINE_ORDER = {GL20.GL_VERTEX_SHADER, GL40.GL_TESS_CONTROL_SHADER, GL40.GL_TESS_EVALUATION_SHADER, GL32.GL_GEOMETRY_SHADER};

    private LinkChecks() {}

    private static String perVertexMembers(String source, String direction) {
        final Matcher block = PER_VERTEX.matcher(source);
        while (block.find()) {
            if (!block.group(1).equals(direction)) continue;
            final java.util.TreeSet<String> members = new java.util.TreeSet<>();
            for (String member : block.group(2).split(";")) {
                final String trimmed = member.trim().replaceAll("\\s+", " ");
                if (!trimmed.isEmpty()) members.add(trimmed);
            }
            return members.toString();
        }
        return null;
    }

    /** Stages next to each other in a separable program have to agree on the gl_PerVertex they both redeclare. */
    private static String perVertexProblem(List<Shader> shaders) {
        String out = null;
        for (int type : PIPELINE_ORDER) {
            String source = null;
            for (Shader s : shaders) {
                if (s.type == type && s.source != null) source = s.source;
            }
            if (source == null) continue;
            final String in = perVertexMembers(source, "in");
            if (out != null && in != null && !out.equals(in)) return "error: stages redeclare gl_PerVertex differently";
            out = perVertexMembers(source, "out");
        }
        return null;
    }

    private record Uniform(String type, String array, String format) {}

    private record Block(boolean named, String members, String array) {}

    /** The log of a link of a separable program that has to fail, or null. */
    static String separableProblem(List<Shader> shaders) {
        return perVertexProblem(shaders);
    }

    /** The log of a link that has to fail, or null. */
    static String problem(List<Shader> shaders) {
        final String compute = computeProblem(shaders);
        if (compute != null) return compute;
        boolean spirv = false, glsl = false;
        for (Shader s : shaders) {
            if (s.spirv != null) spirv = true;
            else glsl = true;
        }
        if (spirv && glsl) return "error: a program cannot link SPIR-V shaders with GLSL ones";
        final String main = mainProblem(shaders);
        if (main != null) return main;
        boolean worthParsing = shaders.size() > 1;
        for (Shader s : shaders) worthParsing |= s.source != null && s.source.contains("atomic_uint");
        if (!worthParsing) return null;

        final Map<String, Uniform> uniforms = new HashMap<>();
        final Map<String, Block> blocks = new HashMap<>();
        final Map<String, String> unnamedMembers = new HashMap<>();
        final Map<Long, String> counters = new HashMap<>();
        final Map<String, Long> counterPlaces = new HashMap<>();
        for (Shader s : shaders) {
            final List<String> statements;
            try {
                statements = GeometryLowering.parsed(s.source, s.type);
            } catch (RuntimeException failedToPreprocess) {
                continue;
            }
            final Map<Integer, Integer> nextOffset = new HashMap<>();
            for (String statement : statements) {
                if (statement.startsWith("#")) continue;
                final int open = statement.indexOf('{');
                final String head = open < 0 ? statement : statement.substring(0, open);
                if (head.contains("(") && !head.contains("layout") || statement.contains("subroutine")) continue;
                String problem = block(statement, blocks, unnamedMembers);
                if (problem == null) problem = uniform(statement, uniforms, unnamedMembers);
                if (problem == null) problem = counter(statement, nextOffset, counters, counterPlaces);
                if (problem != null) return problem;
            }
        }
        for (Map.Entry<String, Uniform> uniform : uniforms.entrySet()) {
            if (unnamedMembers.containsKey(uniform.getKey())) return "error: " + uniform.getKey() + " is both a uniform and a member of block " + unnamedMembers.get(uniform.getKey());
        }
        return null;
    }

    private static String block(String statement, Map<String, Block> blocks, Map<String, String> unnamedMembers) {
        final Matcher m = BLOCK.matcher(statement);
        if (!m.find()) return null;
        final String key = m.group(1) + " " + m.group(2);
        final boolean named = !m.group(4).isEmpty();
        final String members = canonicalMembers(m.group(3), named ? null : m.group(2), unnamedMembers);
        if (members == null) return "error: " + m.group(4) + " is a member of two blocks";
        final Block block = new Block(named, members, m.group(5).trim());
        final Block seen = blocks.putIfAbsent(key, block);
        if (seen == null) return null;
        if (seen.named != named) return "error: block " + m.group(2) + " has an instance name in only some of its shaders";
        if (!seen.members.equals(members)) return "error: block " + m.group(2) + " has different members in different shaders";
        if (bothNumbers(seen.array, block.array) && !seen.array.equals(block.array)) return "error: block " + m.group(2) + " has different sizes in different shaders";
        return null;
    }

    /** One line per member, whatever way the shader grouped them. Null if an unnamed block's member belongs to a block of another name. */
    private static String canonicalMembers(String body, String unnamedBlock, Map<String, String> unnamedMembers) {
        final List<String> out = new ArrayList<>();
        for (String member : body.split(";")) {
            final String text = PRECISION.matcher(member).replaceAll(" ").trim();
            if (text.isEmpty()) continue;
            final List<String> parts = topLevel(text);
            final Matcher first = DECLARATOR.matcher(parts.get(0));
            if (!first.find()) {
                out.add(text.replaceAll("\\s+", " "));
                continue;
            }
            final String declaredType = parts.get(0).substring(0, first.start());
            final String typeArray = arrayOf(declaredType);
            final String type = declaredType.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\s+", " ").trim();
            for (int i = 0; i < parts.size(); i++) {
                final Matcher declarator = DECLARATOR.matcher(parts.get(i));
                if (!declarator.find()) continue;
                final String name = declarator.group(1);
                final String array = typeArray + arrayOf(declarator.group(2));
                out.add(type + " " + name + (isNumbers(array) ? array : ""));
                if (unnamedBlock != null) {
                    final String holder = unnamedMembers.putIfAbsent(name, unnamedBlock);
                    if (holder != null && !holder.equals(unnamedBlock)) return null;
                }
            }
        }
        return String.join("\n", out);
    }

    private static String arrayOf(String text) {
        final Matcher size = ARRAY.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (size.find()) out.append('[').append(size.group(1).trim()).append(']');
        return out.toString();
    }

    private static List<String> topLevel(String text) {
        final List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String uniform(String statement, Map<String, Uniform> uniforms, Map<String, String> unnamedMembers) {
        final Matcher m = UNIFORM.matcher(statement);
        if (!m.find() || statement.contains("struct")) return null;
        final String body = PRECISION.matcher(m.group(1)).replaceAll(" ");
        final Matcher type = TYPE.matcher(body);
        if (!type.find()) return null;
        final String format = imageFormat(statement.substring(0, m.start()));
        final String after = body.substring(type.end());
        for (String part : topLevel(after)) {
            final Matcher declarator = DECLARATOR.matcher(part.trim());
            if (!declarator.lookingAt()) continue;
            final Uniform uniform = new Uniform(type.group(1), (type.group(2) + declarator.group(2)).replaceAll("\\s+", ""), format);
            final Uniform seen = uniforms.putIfAbsent(declarator.group(1), uniform);
            if (seen == null) continue;
            final boolean differs = !seen.type.equals(uniform.type) || !seen.format.equals(uniform.format)
                || bothNumbers(seen.array, uniform.array) && !seen.array.equals(uniform.array);
            if (differs) return "error: uniform " + declarator.group(1) + " is declared differently in different shaders";
        }
        return null;
    }

    private static String imageFormat(String qualifiers) {
        final Matcher format = IMAGE_FORMAT.matcher(qualifiers);
        return format.find() ? format.group(1) : "";
    }

    /** Sizes given in a constant expression can match without reading the same, so only literal ones count. */
    private static boolean bothNumbers(String a, String b) {
        return isNumbers(a) && isNumbers(b);
    }

    private static boolean isNumbers(String array) {
        return !array.contains("[]") && NUMBER.matcher(array).replaceAll("").replaceAll("[\\[\\]\\s]", "").isEmpty();
    }

    private static String counter(String statement, Map<Integer, Integer> nextOffset, Map<Long, String> counters, Map<String, Long> places) {
        if (!statement.contains("uniform") || !statement.contains("atomic_uint")) return null;
        final Matcher name = COUNTER_NAME.matcher(statement);
        if (!name.find()) return null;
        final Matcher binding = LAYOUT_VALUE_BINDING.matcher(statement);
        final int unit = binding.find() ? Integer.parseInt(binding.group(1)) : 0;
        final Matcher offset = LAYOUT_VALUE_OFFSET.matcher(statement);
        int at = offset.find() ? Integer.parseInt(offset.group(1)) : nextOffset.getOrDefault(unit, 0);
        // A declaration with no name only moves where the next counter goes.
        if (name.group(1).isEmpty()) {
            nextOffset.put(unit, at);
            return null;
        }
        final int count = name.group(2) == null ? 1 : Integer.parseInt(name.group(2));
        final Long place = places.putIfAbsent(name.group(1), ((long) unit << 32) | at);
        if (place != null && place != (((long) unit << 32) | at)) return "error: atomic counter " + name.group(1) + " is declared in different places";
        for (int i = 0; i < count; i++) {
            final long slot = ((long) unit << 32) | (at + i * 4L);
            final String holder = counters.putIfAbsent(slot, name.group(1));
            if (holder != null && !holder.equals(name.group(1))) return "error: atomic counters " + holder + " and " + name.group(1) + " overlap";
        }
        nextOffset.put(unit, at + count * 4);
        return null;
    }

    private static String mainProblem(List<Shader> shaders) {
        final Map<Integer, Boolean> hasMain = new HashMap<>();
        for (Shader s : shaders) hasMain.merge(s.type, s.source != null && MAIN.matcher(GeometryLowering.stripComments(s.source)).find(), Boolean::logicalOr);
        return hasMain.containsValue(false) ? "error: a stage has no main function" : null;
    }

    private static String computeProblem(List<Shader> shaders) {
        String size = null;
        boolean anyCompute = false;
        for (Shader s : shaders) {
            if (s.type != org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER || s.source == null) continue;
            anyCompute = true;
            if (s.source.contains("local_size_variable")) return null;
            final String declared = declaredSize(s.source);
            if (declared == null) continue;
            if (size != null && !size.equals(declared)) return "error: compute shaders disagree on their work group size";
            size = declared;
        }
        return anyCompute && size == null ? "error: no compute shader declares a work group size" : null;
    }

    /** One shader can declare each axis apart, and an axis it never names is 1. */
    private static String declaredSize(String source) {
        final Matcher layout = LOCAL_SIZE.matcher(source);
        final StringBuilder all = new StringBuilder();
        while (layout.find()) all.append(layout.group(1)).append(',');
        if (all.length() == 0) return null;
        final StringBuilder size = new StringBuilder();
        for (String axis : new String[] {"x", "y", "z"}) {
            final Matcher value = Pattern.compile("\\blocal_size_" + axis + "\\s*=\\s*\\(*\\s*(\\w+)").matcher(all);
            size.append(value.find() ? value.group(1) : "1").append(',');
        }
        return size.toString();
    }
}
