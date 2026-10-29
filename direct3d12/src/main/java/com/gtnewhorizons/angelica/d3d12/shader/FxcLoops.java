package com.gtnewhorizons.angelica.d3d12.shader;

import java.util.ArrayList;
import java.util.List;

public final class FxcLoops {
    private FxcLoops() {}

    // Implicit-gradient sampling inside a loop makes FXC unroll it regardless of [loop]; explicit level-zero sampling
    // is exact for the screen, depth and noise textures such loops read, and costs only mip selection elsewhere.
    public static String levelZeroSamplingInLoops(String hlsl) {
        final int n = hlsl.length();
        final boolean[] inLoop = new boolean[n];
        int from = 0;
        boolean any = false;
        while (from < n) {
            final int next = nextLoop(hlsl, from);
            if (next < 0) break;
            final int bodyStart = hlsl.indexOf('{', next);
            final int bodyEnd = bodyStart < 0 ? -1 : matchBrace(hlsl, bodyStart);
            if (bodyEnd < 0) break;
            for (int i = bodyStart; i <= bodyEnd; i++) inLoop[i] = true;
            any = true;
            from = bodyStart + 1;
        }
        if (!any) return hlsl;
        final StringBuilder out = new StringBuilder(n + 512);
        int i = 0;
        while (i < n) {
            final int at = nextSampleCall(hlsl, i);
            if (at < 0 || !inLoop[at]) {
                if (at < 0) {
                    out.append(hlsl, i, n);
                    break;
                }
                out.append(hlsl, i, at + 1);
                i = at + 1;
                continue;
            }
            final int open = hlsl.indexOf('(', at);
            final int close = matchParen(hlsl, open);
            if (close < 0) {
                out.append(hlsl, i, n);
                break;
            }
            final String method = hlsl.substring(at + 1, open);
            final List<String> args = splitArgs(hlsl.substring(open + 1, close));
            out.append(hlsl, i, at);
            if (args.size() < 2) {
                out.append(hlsl, at, close + 1);
                i = close + 1;
                continue;
            }
            switch (method) {
                case "Sample" -> {
                    args.add(2, "0.0f");
                    out.append(".SampleLevel(").append(String.join(", ", args)).append(')');
                }
                case "SampleBias" -> out.append(".SampleLevel(").append(String.join(", ", args)).append(')');
                case "SampleCmp" -> out.append(".SampleCmpLevelZero(").append(String.join(", ", args)).append(')');
                default -> out.append(hlsl, at, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static int nextSampleCall(String s, int from) {
        int i = from;
        while (true) {
            i = s.indexOf(".Sample", i);
            if (i < 0) return -1;
            int j = i + 7;
            while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
            final String method = s.substring(i + 1, j);
            if (j < s.length() && s.charAt(j) == '(' && (method.equals("Sample") || method.equals("SampleBias") || method.equals("SampleCmp"))) return i;
            i = j;
        }
    }

    static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    static List<String> splitArgs(String inner) {
        final List<String> args = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                args.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        args.add(inner.substring(start).trim());
        return args;
    }

    public static String addLoopHints(String hlsl) {
        final StringBuilder out = new StringBuilder(hlsl.length() + 256);
        int i = 0;
        final int n = hlsl.length();
        while (i < n) {
            final int next = nextLoop(hlsl, i);
            if (next < 0) {
                out.append(hlsl, i, n);
                break;
            }
            out.append(hlsl, i, next);
            final int bodyStart = hlsl.indexOf('{', next);
            final int bodyEnd = bodyStart < 0 ? -1 : matchBrace(hlsl, bodyStart);
            if (bodyEnd < 0) {
                out.append(hlsl, next, n);
                break;
            }
            if (!alreadyHinted(hlsl, next)) out.append("[loop] ");
            final int keywordEnd = next + (hlsl.startsWith("do", next) ? 2 : hlsl.startsWith("for", next) ? 3 : 5);
            out.append(hlsl, next, keywordEnd);
            i = keywordEnd;
        }
        return out.toString();
    }

    // A loop keyword counts only at statement start, outside identifiers; the `while (...);` that closes a do-loop is skipped.
    private static int nextLoop(String s, int from) {
        final int n = s.length();
        for (int i = from; i < n; i++) {
            final char c = s.charAt(i);
            if (c != 'f' && c != 'w' && c != 'd') continue;
            final String kw = s.startsWith("for", i) ? "for" : s.startsWith("while", i) ? "while" : s.startsWith("do", i) ? "do" : null;
            if (kw == null) continue;
            final int end = i + kw.length();
            if (i > 0 && isIdent(s.charAt(i - 1))) continue;
            if (end < n && isIdent(s.charAt(end))) continue;
            if (kw.equals("while") && conditionEndsStatement(s, end)) continue;
            final char prev = previousNonBlank(s, i);
            if (prev != ';' && prev != '{' && prev != '}' && prev != ')' && prev != 0 && prev != ']') continue;
            if (kw.equals("do")) {
                final int brace = skipBlank(s, end);
                if (brace >= n || s.charAt(brace) != '{') continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean alreadyHinted(String s, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i >= 0 && s.charAt(i) == ']';
    }

    private static boolean conditionEndsStatement(String s, int at) {
        final int open = skipBlank(s, at);
        if (open >= s.length() || s.charAt(open) != '(') return false;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) {
                final int after = skipBlank(s, i + 1);
                return after < s.length() && s.charAt(after) == ';';
            }
        }
        return false;
    }

    private static char previousNonBlank(String s, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i < 0 ? 0 : s.charAt(i);
    }

    private static int skipBlank(String s, int at) {
        while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++;
        return at;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
