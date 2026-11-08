package com.gtnewhorizons.angelica.glemu.shader;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ARB_shading_language_include's named strings, and the #include directives they answer, which the compiler sees already expanded. */
public final class GlslIncludes {

    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]*[\"<]([^\">]+)[\">].*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_(?:ARB_shading_language_include|GOOGLE_include_directive)\\b.*$");
    public static final String BINDABLE_BLOCK_PREFIX = "angelica_bindable_";
    private static final Pattern BINDABLE = Pattern.compile("\\bbindable\\s+uniform\\s+(\\w+)\\s+(\\w+)\\s*((?:\\[[^]]*])?)\\s*;");
    private static final Pattern BINDABLE_EXTENSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_EXT_bindable_uniform\\b.*$");
    private static final Pattern VARIABLE_GROUP = Pattern.compile("layout\\s*\\(\\s*local_size_variable\\s*\\)\\s*in\\s*;");
    private static final Pattern VARIABLE_GROUP_EXTENSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_ARB_compute_variable_group_size\\b.*$");
    private static final Map<String, String> NAMED = new ConcurrentHashMap<>();

    private GlslIncludes() {}

    public static void put(String name, String source) {
        NAMED.put(name, source);
    }

    public static void remove(String name) {
        NAMED.remove(name);
    }

    public static String get(String name) {
        return NAMED.get(name);
    }

    /** The source without the qualifiers of extensions this layer answers in other ways. */
    public static String lower(String source) {
        return withoutVariableGroupSize(withBindableBlocks(source));
    }

    private static String withBindableBlocks(String source) {
        if (source.indexOf("bindable") < 0) return source;
        final Matcher bindable = BINDABLE.matcher(BINDABLE_EXTENSION.matcher(source).replaceAll(""));
        return bindable.replaceAll("layout(std140) uniform " + BINDABLE_BLOCK_PREFIX + "$2 { $1 $2$3; };");
    }

    /** Local sizes are fixed per dispatch by a variant compiled for them, so the program itself compiles with the smallest. */
    private static String withoutVariableGroupSize(String source) {
        if (source.indexOf("variable_group_size") < 0 && source.indexOf("local_size_variable") < 0) return source;
        final String fixed = VARIABLE_GROUP.matcher(VARIABLE_GROUP_EXTENSION.matcher(source).replaceAll("")).replaceAll("layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;");
        return fixed.replace("gl_LocalGroupSizeARB", "gl_WorkGroupSize");
    }

    public static String withGroupSize(String source, int x, int y, int z) {
        final String sized = "layout(local_size_x = " + x + ", local_size_y = " + y + ", local_size_z = " + z + ") in;";
        return VARIABLE_GROUP.matcher(source).replaceAll(sized);
    }

    public static String expand(String source) {
        if (source.indexOf("include") < 0) return source;
        return expand(EXTENSION.matcher(source).replaceAll(""), new HashSet<>());
    }

    /** A file included again adds nothing, which stops cycles and stands in for the guards most includes carry anyway. */
    private static String expand(String source, Set<String> seen) {
        final Matcher include = INCLUDE.matcher(source);
        final StringBuilder out = new StringBuilder();
        while (include.find()) {
            final String named = NAMED.get(include.group(1));
            final String body = named == null || !seen.add(include.group(1)) ? "" : expand(named, seen);
            include.appendReplacement(out, Matcher.quoteReplacement(body));
        }
        include.appendTail(out);
        return out.toString();
    }
}
