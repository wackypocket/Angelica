package com.gtnewhorizons.angelica.glemu.shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Devices without custom border colours only offer black and white, so a program sampling a texture with any other border runs in a variant of
 * itself holding that colour. Past the edge a sample becomes the colour, without the blend a linear filter gives on the last half texel.
 */
public final class BorderLowering {
    /** Above the bits a logic op key uses, below the one for sample shading. */
    public static final long KEY = 1L << 62;
    private static final int ID_SHIFT = 37, ID_BITS = 25;
    public static final long MASK = KEY | ((1L << ID_BITS) - 1) << ID_SHIFT;

    /** A sampler uniform, the axes that wrap to its border, and the colour as the components of its sampled type. */
    public record Sampler(String name, boolean s, boolean t, boolean r, String color, boolean edgeGather, boolean faces) {
        public Sampler(String name, boolean s, boolean t, boolean r, String color, boolean edgeGather) {
            this(name, s, t, r, color, edgeGather, false);
        }

        /** A cube sampled with a linear filter while GL_TEXTURE_CUBE_MAP_SEAMLESS is off, on a device that always filters across faces. */
        public static Sampler apartFaces(String name) {
            return new Sampler(name, false, false, false, "", false, true);
        }
    }

    private static final Map<List<Sampler>, Long> KEYS = new HashMap<>();
    private static final Map<Long, List<Sampler>> SAMPLERS = new HashMap<>();
    private static volatile boolean seen;

    private BorderLowering() {}

    /** Set once any border colour no device can give has been asked for, so that nobody else pays to look. */
    public static void noteColor(boolean preset) {
        if (!preset) seen = true;
    }

    public static boolean seen() {
        return seen;
    }

    public static boolean isPreset(float r, float g, float b, float a) {
        return a == 0f && r == 0f && g == 0f && b == 0f || a == 1f && r == g && g == b && (r == 0f || r == 1f);
    }

    public static synchronized long keyOf(List<Sampler> samplers) {
        final Long known = KEYS.get(samplers);
        if (known != null) return known;
        final long key = KEY | (long) (KEYS.size() + 1) << ID_SHIFT;
        KEYS.put(List.copyOf(samplers), key);
        SAMPLERS.put(key, List.copyOf(samplers));
        return key;
    }

    public static synchronized String lower(String source, long key, boolean fragment) {
        String out = source;
        for (Sampler sampler : SAMPLERS.getOrDefault(key & MASK, List.of())) out = lower(out, sampler, fragment);
        return out;
    }

    /** The offset forms move the footprint, or with textureGatherOffsets each texel by its own offset, before the edge is tested. */
    private static String gather(String sampler, String plain, String result, String color) {
        return result + " " + plain + "_gatherAt(vec2 c, ivec2 shifts[4], int component) {\n"
            + "    ivec2 size = textureSize(" + sampler + ", 0);\n"
            + "    ivec2 base = ivec2(floor(c * vec2(size) - 0.5));\n"
            + "    " + result + " out_value;\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "        ivec2 at = base + shifts[i];\n"
            + "        bool inside = at.x >= 0 && at.y >= 0 && at.x < size.x && at.y < size.y;\n"
            + "        out_value[i] = inside ? texelFetch(" + sampler + ", at, 0)[component] : " + color + "[component];\n"
            + "    }\n    return out_value;\n}\n"
            + result + " " + plain + "_gatherOffset(vec2 c, ivec2 o, int component) { return " + plain
            + "_gatherAt(c, ivec2[4](o + ivec2(0, 1), o + ivec2(1, 1), o + ivec2(1, 0), o), component); }\n"
            + result + " " + plain + "_gatherOffset(vec2 c, ivec2 o) { return " + plain + "_gatherOffset(c, o, 0); }\n"
            + result + " " + plain + "_gather(vec2 c, int component) { return " + plain + "_gatherOffset(c, ivec2(0), component); }\n"
            + result + " " + plain + "_gather(vec2 c) { return " + plain + "_gather(c, 0); }\n"
            + result + " " + plain + "_gatherOffsets(vec2 c, ivec2 o[4], int component) { return " + plain + "_gatherAt(c, o, component); }\n"
            + result + " " + plain + "_gatherOffsets(vec2 c, ivec2 o[4]) { return " + plain + "_gatherAt(c, o, 0); }\n";
    }

    /** Apple's gather reads past the edge as zero whatever the wrap mode, so a clamped image gathers texel by texel too. */
    private static String edgeGather(String source, Sampler sampler, int after, String prefix) {
        final String name = Pattern.quote(sampler.name()), plain = "angelica_edge_" + sampler.name(), result = prefix + "vec4";
        final String head = source.substring(0, after);
        final String rest = source.substring(after).replaceAll("\\btextureGather\\s*\\(\\s*" + name + "\\s*,", plain + "(");
        final String helper = "\n" + result + " " + plain + "(vec2 c, int component) {\n"
            + "    ivec2 size = textureSize(" + sampler.name() + ", 0);\n"
            + "    ivec2 base = ivec2(floor(c * vec2(size) - 0.5));\n"
            + "    ivec2 offsets[4] = ivec2[4](ivec2(0, 1), ivec2(1, 1), ivec2(1, 0), ivec2(0, 0));\n"
            + "    " + result + " out_value;\n"
            + "    for (int i = 0; i < 4; i++) out_value[i] = texelFetch(" + sampler.name() + ", clamp(base + offsets[i], ivec2(0), size - 1), 0)[component];\n"
            + "    return out_value;\n}\n"
            + result + " " + plain + "(vec2 c) { return " + plain + "(c, 0); }\n";
        return head + helper + rest;
    }

    /**
     * Keeps a direction within the last texel centres of its face, where a linear filter reads no further than the edge texels. Mipmap blends
     * clamp for the nearer level alone.
     */
    private static String apartFaces(String source, Sampler sampler, boolean fragment) {
        final String name = Pattern.quote(sampler.name());
        final Matcher declared = Pattern.compile("\\buniform\\s+(?:\\w+\\s+)*?sampler(Cube|CubeArray)\\s+" + name + "\\s*;").matcher(source);
        if (!declared.find()) return source;
        final boolean array = declared.group(1).equals("CubeArray");
        final String coordinate = array ? "vec4" : "vec3", axes = array ? "c.xyz" : "c", plain = "angelica_faces_" + sampler.name(), lod = plain + "_lod";
        final String rebuilt = array ? "vec4(d, c.w)" : "d";
        final String clamp = "vec3 " + plain + "_dir(vec3 d, float level) {\n"
            + "    float size = float(textureSize(" + sampler.name() + ", int(max(level, 0.0) + 0.5)).x);\n"
            + "    vec3 a = abs(d);\n"
            + "    float m = max(a.x, max(a.y, a.z)) * (1.0 - 1.0 / size);\n"
            + "    if (a.x >= a.y && a.x >= a.z) d.yz = clamp(d.yz, -m, m);\n"
            + "    else if (a.y >= a.z) d.xz = clamp(d.xz, -m, m);\n"
            + "    else d.xy = clamp(d.xy, -m, m);\n"
            + "    return d;\n}\n";
        final String sampled = "vec4 " + lod + "(" + coordinate + " c, float level) { vec3 d = " + plain + "_dir(" + axes + ", level); return textureLod(" + sampler.name() + ", " + rebuilt + ", level); }\n";
        final String implicit = fragment
            ? "float " + plain + "_level(vec3 d) {\n"
                + "    vec3 a = abs(d);\n"
                + "    float size = float(textureSize(" + sampler.name() + ", 0).x), major = max(max(a.x, max(a.y, a.z)), 1e-6);\n"
                + "    return log2(max(max(length(dFdx(d)), length(dFdy(d))) / major * size * 0.5, 1e-6));\n}\n"
                + "vec4 " + plain + "(" + coordinate + " c) { float level = " + plain + "_level(" + axes + "); vec3 d = " + plain + "_dir(" + axes + ", level); return textureGrad(" + sampler.name() + ", " + rebuilt + ", dFdx(" + axes + "), dFdy(" + axes + ")); }\n"
                + "vec4 " + plain + "(" + coordinate + " c, float bias) { return " + lod + "(c, " + plain + "_level(" + axes + ") + bias); }\n"
            : "vec4 " + plain + "(" + coordinate + " c) { return " + lod + "(c, 0.0); }\n";
        final String rest = source.substring(declared.end())
            .replaceAll("\\btexture\\s*\\(\\s*" + name + "\\s*,", plain + "(")
            .replaceAll("\\btextureLod\\s*\\(\\s*" + name + "\\s*,", lod + "(");
        return source.substring(0, declared.end()) + "\n" + clamp + sampled + implicit + rest;
    }

    private static final float MAX_BIAS = 15f;
    private static final int IMPLICIT = 0, EXPLICIT = 1, GRADIENT = 2;

    /** The end of the call whose arguments start at {@code from}, with those arguments gathered. */
    private static int arguments(String source, int from, List<String> into) {
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

    private static String lower(String source, Sampler sampler, boolean fragment) {
        if (sampler.faces()) return apartFaces(source, sampler, fragment);
        final String name = Pattern.quote(sampler.name());
        final Matcher declared = Pattern.compile("\\buniform\\s+(?:\\w+\\s+)*?([iu]?)sampler(1D|2DArray|2D|3D)\\s+" + name + "\\s*;").matcher(source);
        if (!declared.find()) return source;
        final String prefix = declared.group(1), dimension = declared.group(2);
        if (sampler.edgeGather()) return dimension.equals("2D") ? edgeGather(source, sampler, declared.end(), prefix) : source;
        final String plain = "angelica_bordered_" + sampler.name(), lod = plain + "_lod";
        final String head = source.substring(0, declared.end());
        final String rest = source.substring(declared.end())
            .replaceAll("\\btexture\\s*\\(\\s*" + name + "\\s*,", plain + "(")
            .replaceAll("\\btextureLod\\s*\\(\\s*" + name + "\\s*,", lod + "(");
        final boolean gathers = sampler.s() && sampler.t() && dimension.equals("2D") && rest.contains("textureGather");
        final String gathered = !gathers ? rest : rest
            .replaceAll("\\btextureGatherOffsets\\s*\\(\\s*" + name + "\\s*,", plain + "_gatherOffsets(")
            .replaceAll("\\btextureGatherOffset\\s*\\(\\s*" + name + "\\s*,", plain + "_gatherOffset(")
            .replaceAll("\\btextureGather\\s*\\(\\s*" + name + "\\s*,", plain + "_gather(");
        final String coordinate = switch (dimension) {
            case "1D" -> "float";
            case "2D" -> "vec2";
            default -> "vec3";
        };
        final List<String> outside = new ArrayList<>();
        final boolean oneDimensional = dimension.equals("1D");
        if (sampler.s()) outside.add(oneDimensional ? "c < 0.0 || c >= 1.0" : "c.x < 0.0 || c.x >= 1.0");
        if (sampler.t() && !oneDimensional) outside.add("c.y < 0.0 || c.y >= 1.0");
        if (sampler.r() && dimension.equals("3D")) outside.add("c.z < 0.0 || c.z >= 1.0");
        final String test = outside.isEmpty() ? "false" : String.join(" || ", outside);
        final String result = prefix + "vec4", color = result + sampler.color();
        final String guard = "if (" + test + ") return " + color + "; ";
        final String helpers = "\n" + result + " " + plain + "(" + coordinate + " c) { " + guard + "return texture(" + sampler.name() + ", c); }\n"
            + (fragment ? result + " " + plain + "(" + coordinate + " c, float bias) { " + guard + "return texture(" + sampler.name() + ", c, bias); }\n" : "")
            + result + " " + lod + "(" + coordinate + " c, float level) { " + guard + "return textureLod(" + sampler.name() + ", c, level); }\n"
            + (gathers ? gather(sampler.name(), plain, result, color) : "");
        return head + helpers + gathered;
    }
}
