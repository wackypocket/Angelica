package com.gtnewhorizons.angelica.glemu.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metal cannot pass arrays of matrices between stages, though it can pass the same columns as an array of vectors, which takes the same locations.
 * Each such input or output keeps its name as a global, copied from its columns as main starts or to them as main ends. Double matrices cross
 * as their words already (DoubleLowering), so they are left alone.
 */
final class MatrixArrayLowering {
    static final String PREFIX = "angelica_columns_";
    private static final Pattern DECLARATION = Pattern.compile(
        "(?m)^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*)(in|out)(\\s+(?:(?:highp|mediump|lowp)\\s+)?)mat([234])(?:x([234]))?\\s+(\\w+)\\s*\\[\\s*(\\d+)\\s*\\]\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private MatrixArrayLowering() {}

    private static void copies(String name, int columns, int count, boolean input, StringBuilder into) {
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < columns; c++) {
                final String element = name + "[" + i + "][" + c + "]", column = PREFIX + name + "[" + (i * columns + c) + "]";
                into.append("    ").append(input ? element : column).append(" = ").append(input ? column : element).append(";\n");
            }
        }
    }

    /** An interface block with no instance name, whose members are named as globals are. */
    private static final Pattern BLOCK = Pattern.compile("\\b(in|out)\\s+\\w+\\s*\\{([^{}]*)\\}\\s*;");
    private static final Pattern MEMBER = Pattern.compile("\\b((?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*)(?<![\\w])mat([234])(?:x([234]))?\\s+(\\w+)\\s*\\[\\s*(\\d+)\\s*\\]\\s*;");

    static boolean applies(String source) {
        if (!source.contains("mat")) return false;
        if (DECLARATION.matcher(source).find()) return true;
        final Matcher block = BLOCK.matcher(source);
        while (block.find()) {
            if (MEMBER.matcher(block.group(2)).find()) return true;
        }
        return false;
    }

    /** What a flattened array was: its name, its matrix type's columns and rows, and how many there are. */
    record Flattened(String name, int columns, int rows, int count) {}

    static List<Flattened> inputs(String source) {
        final List<Flattened> out = new ArrayList<>();
        final Matcher declared = DECLARATION.matcher(source);
        while (declared.find()) {
            if (!declared.group(2).equals("in")) continue;
            final int columns = Integer.parseInt(declared.group(4));
            out.add(new Flattened(declared.group(6), columns, declared.group(5) != null ? Integer.parseInt(declared.group(5)) : columns, Integer.parseInt(declared.group(7))));
        }
        return out;
    }

    static String lower(String source) {
        final StringBuilder read = new StringBuilder(), write = new StringBuilder(), out = new StringBuilder();
        final StringBuilder blocks = new StringBuilder();
        final Matcher block = BLOCK.matcher(source);
        int blockCopied = 0;
        while (block.find()) {
            final StringBuilder globals = new StringBuilder();
            final boolean input = block.group(1).equals("in");
            final Matcher member = MEMBER.matcher(block.group(2));
            final String body = member.replaceAll(m -> {
                final int columns = Integer.parseInt(m.group(2));
                final int rows = m.group(3) != null ? Integer.parseInt(m.group(3)) : columns;
                final int count = Integer.parseInt(m.group(5));
                globals.append("mat").append(columns).append('x').append(rows).append(' ').append(m.group(4)).append('[').append(count).append("];\n");
                copies(m.group(4), columns, count, input, input ? read : write);
                return m.group(1) + "vec" + rows + " " + PREFIX + m.group(4) + "[" + count * columns + "];";
            });
            blocks.append(source, blockCopied, block.start(2)).append(body).append(source, block.end(2), block.end()).append('\n').append(globals);
            blockCopied = block.end();
        }
        blocks.append(source, blockCopied, source.length());
        final String unblocked = blocks.toString();
        final Matcher declared = DECLARATION.matcher(unblocked);
        int copied = 0;
        while (declared.find()) {
            final int columns = Integer.parseInt(declared.group(4));
            final int rows = declared.group(5) != null ? Integer.parseInt(declared.group(5)) : columns;
            final String name = declared.group(6);
            final int count = Integer.parseInt(declared.group(7));
            final String matrix = "mat" + columns + "x" + rows;
            out.append(unblocked, copied, declared.start());
            out.append(declared.group(1)).append(declared.group(2)).append(declared.group(3)).append("vec").append(rows).append(' ').append(PREFIX).append(name).append('[').append(count * columns).append("];\n");
            out.append(matrix).append(' ').append(name).append('[').append(count).append("];");
            copied = declared.end();
            final boolean input = declared.group(2).equals("in");
            copies(name, columns, count, input, input ? read : write);
        }
        out.append(unblocked, copied, unblocked.length());
        final String declaredOnes = out.toString();
        final Matcher main = MAIN.matcher(declaredOnes);
        if (!main.find()) return source;
        int depth = 1, end = main.end();
        while (end < declaredOnes.length() && depth > 0) {
            final char c = declaredOnes.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return declaredOnes.substring(0, main.end()) + "\n" + read + declaredOnes.substring(main.end(), end - 1) + write + declaredOnes.substring(end - 1);
    }
}
