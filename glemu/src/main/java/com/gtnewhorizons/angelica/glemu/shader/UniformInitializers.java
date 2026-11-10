package com.gtnewhorizons.angelica.glemu.shader;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL43;

/** GLSL lets a default-block uniform start at a constant, which SPIR-V for Vulkan has nowhere to keep. The compiler folds each one here, for the link to set. */
final class UniformInitializers {
    record Initial(String name, int[] words) {}

    private static final Pattern MAYBE = Pattern.compile("\\buniform\\b[^;{]*=");
    private static final Pattern DECLARATION = Pattern.compile("^(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+((?:(?:highp|mediump|lowp)\\s+)?\\w+(?:\\s*\\[[^\\]]*\\])*)\\s+(.*)$", Pattern.DOTALL);
    private static final Pattern DECLARATOR = Pattern.compile("^\\s*(\\w+)((?:\\s*\\[[^\\]]*\\])*)\\s*=\\s*(.*)$", Pattern.DOTALL);
    private static final int OP_CONSTANT_TRUE = 41, OP_CONSTANT_FALSE = 42, OP_CONSTANT_COMPOSITE = 44;

    private UniformInitializers() {}

    static List<Initial> of(String source, int glType) {
        if (source == null || !MAYBE.matcher(source).find()) return List.of();
        final List<String> statements = GeometryLowering.parsed(source, glType);
        // A size can be written in terms of the work group's, which the fold has to be run with.
        final StringBuilder declarations = new StringBuilder("#version 460 core\n");
        for (String raw : statements) {
            if (raw.contains("local_size_")) declarations.append(raw.strip()).append(raw.strip().endsWith(";") ? "\n" : ";\n");
        }
        if (declarations.indexOf("local_size_") < 0) declarations.append("layout(local_size_x = 1) in;\n");
        final List<Initial> found = new ArrayList<>();
        for (String raw : statements) {
            final String statement = raw.strip();
            if (statement.startsWith("const ") || statement.startsWith("struct ")) {
                declarations.append(statement).append(statement.endsWith(";") ? "\n" : ";\n");
                continue;
            }
            final Matcher m = DECLARATION.matcher(statement);
            if (!m.matches()) continue;
            for (String part : topLevel(m.group(2).replaceFirst(";\\s*$", ""))) {
                final Matcher d = DECLARATOR.matcher(part);
                if (!d.matches()) continue;
                final int[] words = folded(declarations + "void main() { " + m.group(1) + " v" + d.group(2) + " = " + d.group(3) + "; }\n");
                if (words != null) found.add(new Initial(d.group(1), words));
            }
        }
        return found;
    }

    private static List<String> topLevel(String text) {
        final List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int[] folded(String source) {
        final ShaderCompiler.Result result = ShaderCompiler.compile(source, GL43.GL_COMPUTE_SHADER, "uniform initializer", ShaderCompiler.DEPTH_NONE);
        if (result.compiled() == null) return null;
        final byte[] bytes = result.compiled().spirv();
        final int[] w = new int[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(w);
        final Int2ObjectOpenHashMap<int[]> constants = new Int2ObjectOpenHashMap<>();
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0) return null;
            if (op == OP_CONSTANT_TRUE) constants.put(w[i + 2], new int[] {1});
            else if (op == OP_CONSTANT_FALSE) constants.put(w[i + 2], new int[] {0});
            else if (op == Spirv.OP_CONSTANT && wc == 4) constants.put(w[i + 2], new int[] {w[i + 3]});
            else if (op == Spirv.OP_CONSTANT && wc == 5) constants.put(w[i + 2], new int[] {Float.floatToRawIntBits((float) Double.longBitsToDouble((long) w[i + 4] << 32 | w[i + 3] & 0xFFFFFFFFL))});
            else if (op == OP_CONSTANT_COMPOSITE) {
                final IntArrayList all = new IntArrayList();
                for (int k = i + 3; k < i + wc; k++) {
                    final int[] part = constants.get(w[k]);
                    if (part == null) {
                        all.clear();
                        break;
                    }
                    all.addElements(all.size(), part);
                }
                if (!all.isEmpty()) constants.put(w[i + 2], all.toIntArray());
            } else if (op == Spirv.OP_STORE && constants.containsKey(w[i + 2])) {
                return constants.get(w[i + 2]);
            }
        }
        return null;
    }
}
