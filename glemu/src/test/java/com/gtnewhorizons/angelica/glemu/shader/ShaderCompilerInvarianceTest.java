package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCompilerInvarianceTest {

    private static final Pattern DECLARATION = Pattern.compile("[^;{}]*\\binvariant\\b[^;{}]*");
    private static final Pattern INPUT = Pattern.compile("\\bin\\b");
    private static final Pattern WORD = Pattern.compile("\\binvariant\\s+");

    /** The rewrite as a single regex, kept as the reference the scan must agree with. */
    private static String byPattern(String text) {
        return DECLARATION.matcher(text).replaceAll(found -> Matcher.quoteReplacement(INPUT.matcher(found.group()).find() ? WORD.matcher(found.group()).replaceAll("") : found.group()));
    }

    @Test
    void anInputLosesItsInvarianceAndAnOutputKeepsIt() {
        final String src = "#version 410\n#define A 1\n#define B 2\ninvariant gl_Position;\ninvariant in vec4 a;\nout vec4 b;\ninvariant out vec4 c;\nvoid main() { b = a; }\n";
        final String out = ShaderCompiler.withoutInputInvariance(src);
        assertTrue(out.contains("invariant gl_Position;"));
        assertTrue(out.contains("\nin vec4 a;"));
        assertTrue(out.contains("invariant out vec4 c;"));
        assertEquals(byPattern(src), out);
    }

    @Test
    void wordsThatOnlyContainItAreLeftAlone() {
        final String src = "in vec4 invariantish; uniform float notinvariant; in vec4 x_invariant;";
        assertEquals(src, ShaderCompiler.withoutInputInvariance(src));
        assertEquals(byPattern(src), ShaderCompiler.withoutInputInvariance(src));
    }

    @Test
    void agreesWithThePatternOnRandomText() {
        final String[] pieces = { "invariant", " ", "\n", "in", "out", ";", "{", "}", "vec4", "a", "_", "invariant ", "in ", "#define X 1\n", "\t", "x" };
        final Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            final StringBuilder b = new StringBuilder();
            final int n = random.nextInt(30);
            for (int k = 0; k < n; k++) b.append(pieces[random.nextInt(pieces.length)]);
            final String text = b.toString();
            assertEquals(byPattern(text), ShaderCompiler.withoutInputInvariance(text), text);
        }
    }

    @Test
    void aLongRunOfDefinesStaysFast() {
        final StringBuilder b = new StringBuilder("#version 330 core\n");
        for (int i = 0; i < 400; i++) b.append("#define CONSTANT_").append(i).append(' ').append(i).append('\n');
        b.append("invariant gl_Position;\nin vec3 a;\nvoid main() { gl_Position = vec4(a, 1.0); }\n");
        final String text = b.toString();
        // The best of several runs after a warm-up, so another JVM busy on the same machine does not read as a slow pattern.
        for (int i = 0; i < 20; i++) ShaderCompiler.withoutInputInvariance(text);
        long perCallMicros = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            final long start = System.nanoTime();
            for (int i = 0; i < 50; i++) ShaderCompiler.withoutInputInvariance(text);
            perCallMicros = Math.min(perCallMicros, (System.nanoTime() - start) / 50_000L);
        }
        assertEquals(byPattern(text), ShaderCompiler.withoutInputInvariance(text));
        assertTrue(perCallMicros < 2_000, "took " + perCallMicros + " us a call");
    }
}
