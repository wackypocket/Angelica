package com.gtnewhorizons.angelica.glemu.shader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tessellation control shader's main, for running its invocations one after another in a compute shader. barrier() can only stand at the top level of
 * main, so main is cut there into phases, and every invocation finishes one phase before any begins the next. Locals have to outlive a cut, so they
 * become global arrays with an element per invocation.
 */
record ControlPhases(String source, int count) {
    private static final Pattern BARRIER = Pattern.compile("^\\s*barrier\\s*\\(\\s*\\)\\s*;\\s*$");
    private static final Pattern LOCAL = Pattern.compile("^\\s*(?:const\\s+)?(?:(?:highp|mediump|lowp)\\s+)?([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*)(?:=\\s*([\\s\\S]*?))?\\s*;\\s*$");

    static ControlPhases of(String main, int vertices) {
        final int open = main.indexOf('{'), close = main.lastIndexOf('}');
        final String body = main.substring(open + 1, close);
        if (!Pattern.compile("\\bbarrier\\s*\\(").matcher(body).find()) return new ControlPhases("void angelica_tc_main_0() {" + body + "}\n", 1);
        final Map<String, String> locals = new LinkedHashMap<>();
        final List<StringBuilder> phases = new ArrayList<>();
        phases.add(new StringBuilder());
        for (String statement : statements(body)) {
            if (BARRIER.matcher(statement).matches()) {
                phases.add(new StringBuilder());
                continue;
            }
            final Matcher local = LOCAL.matcher(statement);
            if (local.matches() && !local.group(1).equals("return") && !local.group(1).equals("else")) {
                locals.put(local.group(2), local.group(1) + " angelica_local_" + local.group(2) + "[" + vertices + "]" + local.group(3).trim() + ";\n");
                if (local.group(4) != null) phases.get(phases.size() - 1).append(local.group(2)).append(" = ").append(local.group(4)).append(";\n");
                continue;
            }
            phases.get(phases.size() - 1).append(statement).append('\n');
        }
        final StringBuilder out = new StringBuilder();
        locals.values().forEach(out::append);
        for (int i = 0; i < phases.size(); i++) {
            String text = phases.get(i).toString();
            for (String name : locals.keySet()) text = text.replaceAll("\\b" + name + "\\b", "angelica_local_" + name + "[a_gl_InvocationID]");
            out.append("void angelica_tc_main_").append(i).append("() {\n").append(text).append("}\n");
        }
        return new ControlPhases(out.toString(), phases.size());
    }

    /** The body's statements at its top level, with an if and its else as one. */
    private static List<String> statements(String body) {
        final List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c == '{' || c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '}') {
                depth--;
                if (depth == 0 && !followedByContinuation(body, i + 1)) {
                    out.add(body.substring(start, i + 1));
                    start = i + 1;
                }
            } else if (c == ';' && depth == 0) {
                out.add(body.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (!body.substring(start).isBlank()) out.add(body.substring(start));
        return out;
    }

    private static boolean followedByContinuation(String body, int from) {
        final String rest = body.substring(from).stripLeading();
        return rest.startsWith("else") || rest.startsWith("while") && body.lastIndexOf("do", from) > body.lastIndexOf(';', from);
    }
}
