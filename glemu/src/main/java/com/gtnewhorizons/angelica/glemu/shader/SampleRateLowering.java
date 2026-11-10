package com.gtnewhorizons.angelica.glemu.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metal runs a fragment shader per sample only when an input asks for it, so GL_SAMPLE_SHADING marks the inputs
 * {@code sample} in a variant of the program. GL allows shading more samples than GL_MIN_SAMPLE_SHADING_VALUE asks for.
 */
public final class SampleRateLowering {
    /** Above the bits a logic op key uses, so that a variant can be both. */
    public static final long KEY = Long.MIN_VALUE;

    private static final Pattern INPUT = Pattern.compile("\\bin\\s+(\\w+)\\s*(\\{)?");
    private static final Pattern INTERPOLATED_ALREADY = Pattern.compile("\\b(flat|centroid|sample|patch)\\b");

    private SampleRateLowering() {}

    public static String lower(String source) {
        final StringBuilder out = new StringBuilder(source.length() + 64);
        final Matcher in = INPUT.matcher(source);
        int copied = 0;
        int depth = 0;
        int scanned = 0;
        while (in.find()) {
            depth += nesting(source, scanned, in.start());
            scanned = in.start();
            if (depth != 0) continue;
            final int declaration = declarationStart(source, in.start());
            final boolean block = in.group(2) != null;
            if (INTERPOLATED_ALREADY.matcher(source.substring(declaration, in.start())).find()) continue;
            out.append(source, copied, in.start());
            copied = in.start();
            if (!block) {
                out.append("sample ");
                continue;
            }
            final int end = source.indexOf('}', in.end());
            if (end < 0) continue;
            out.append(source, copied, in.end());
            for (String member : source.substring(in.end(), end).split("(?<=;)")) {
                if (!member.isBlank() && !INTERPOLATED_ALREADY.matcher(member).find()) out.append(" sample ");
                out.append(member);
            }
            copied = end;
        }
        return out.append(source, copied, source.length()).toString();
    }

    private static int nesting(String source, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            final char c = source.charAt(i);
            if (c == '{' || c == '(') depth++;
            else if (c == '}' || c == ')') depth--;
        }
        return depth;
    }

    private static int declarationStart(String source, int at) {
        for (int i = at - 1; i >= 0; i--) {
            final char c = source.charAt(i);
            if (c == ';' || c == '}' || c == '\n' && i > 0 && source.charAt(i - 1) == '\n') return i + 1;
            if (c == '#') {
                final int end = source.indexOf('\n', i);
                return end < 0 || end >= at ? at : end + 1;
            }
        }
        return 0;
    }
}
