package com.gtnewhorizons.angelica.glemu.shader;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL40;

/** The fixed function tessellator, by the rules of section 11.2.2 of the OpenGL specification, for a device that runs tessellation shaders as compute shaders. */
public final class Tessellator {
    /** Each generated vertex's three coordinates. */
    public final FloatArrayList coordinates = new FloatArrayList();
    /** Vertices by the order they were generated, a primitive at a time. */
    public final IntArrayList primitives = new IntArrayList();

    private static final float JUST_OVER_ONE = 1.0f + Math.ulp(1.0f);

    private final int mode, spacing, maxLevel;
    private final boolean clockwise, points;

    public Tessellator(int mode, int spacing, int order, boolean points, int maxLevel) {
        this.mode = mode;
        this.spacing = spacing;
        this.clockwise = order == GL11.GL_CW;
        this.points = points;
        this.maxLevel = maxLevel;
    }

    public int verticesPerPrimitive() {
        return points ? 1 : mode == GL40.GL_ISOLINES ? 2 : 3;
    }

    private float clamped(float level) {
        final float max = maxLevel;
        return switch (spacing) {
            case GL40.GL_FRACTIONAL_ODD -> Math.min(Math.max(level, 1.0f), max - 1.0f);
            case GL40.GL_FRACTIONAL_EVEN -> Math.min(Math.max(level, 2.0f), max);
            default -> Math.min(Math.max(level, 1.0f), max);
        };
    }

    /** How many whole segments a level gives, which is even or odd by the spacing. */
    private int segments(float level) {
        final int up = (int) Math.ceil(level);
        return switch (spacing) {
            case GL40.GL_FRACTIONAL_ODD -> up % 2 == 1 ? up : up + 1;
            case GL40.GL_FRACTIONAL_EVEN -> up % 2 == 0 ? up : up + 1;
            default -> up;
        };
    }

    /** Where the points of an edge fall, from 0 to 1. A fractional level makes the two segments next to the middle's symmetric pair shorter. */
    private float[] edge(float level) {
        final float[] at = positions(level);
        // GL wants the points exactly symmetric, and 1 - v is exact for a float of at least a half, in both directions.
        final int n = at.length - 1;
        for (int i = 0; i <= n / 2; i++) at[i] = 1.0f - at[n - i];
        return at;
    }

    private float[] positions(float level) {
        final int n = segments(level);
        // Just over one takes odd spacing to three segments, the outer two with no length that a float can hold.
        if (level == JUST_OVER_ONE && n == 3) return new float[] {0.0f, 0.0f, 1.0f, 1.0f};
        final float[] at = new float[n + 1];
        if (spacing == GL11.GL_EQUAL || n == level || n <= 1) {
            for (int i = 0; i <= n; i++) at[i] = (float) i / n;
            return at;
        }
        // n - 2 long segments, and two short ones whose length grows until they are as long.
        final float shortLength = (level - (n - 2)) / 2.0f;
        final float scale = 1.0f / level;
        final int middle = n / 2;
        float position = 0.0f;
        for (int i = 0; i < n; i++) {
            at[i] = position * scale;
            final boolean isShort = n % 2 == 1 ? i == middle - 1 || i == middle + 1 : i == middle - 1 || i == middle;
            position += isShort ? shortLength : 1.0f;
        }
        at[n] = 1.0f;
        return at;
    }

    private int vertex(float u, float v, float w) {
        coordinates.add(u);
        coordinates.add(v);
        coordinates.add(w);
        return coordinates.size() / 3 - 1;
    }

    private void triangle(int a, int b, int c) {
        if (points) return;
        primitives.add(a);
        if (clockwise) {
            primitives.add(c);
            primitives.add(b);
        } else {
            primitives.add(b);
            primitives.add(c);
        }
    }

    private void line(int a, int b) {
        if (points) return;
        primitives.add(a);
        primitives.add(b);
    }

    /** Tessellates one patch onto the output and returns how many vertices it added. */
    public int tessellate(float[] outer, float[] inner) {
        final int before = coordinates.size() / 3;
        final boolean triangles = mode == GL11.GL_TRIANGLES, quads = mode == GL11.GL_QUADS;
        // A patch with an outer level of zero or less, among those its mode uses, is discarded.
        final int outerUsed = triangles ? 3 : quads ? 4 : 2;
        for (int i = 0; i < outerUsed; i++) if (outer[i] <= 0.0f) return 0;
        if (mode == GL40.GL_ISOLINES) isolines(outer);
        else if (triangles) triangles(outer, inner);
        else quads(outer, inner);
        final int generated = coordinates.size() / 3 - before;
        if (points) for (int i = 0; i < generated; i++) primitives.add(before + i);
        return generated;
    }

    private void isolines(float[] outer) {
        // The first level counts lines and the second the segments of each, and isolines are never closed at v = 1.
        final float countLevel = Math.min(Math.max(outer[0], 1.0f), maxLevel);
        final int lines = (int) Math.ceil(countLevel);
        final float[] along = edge(clamped(outer[1]));
        for (int l = 0; l < lines; l++) {
            final float v = (float) l / lines;
            int previous = -1;
            for (float u : along) {
                final int at = vertex(u, v, 0.0f);
                if (previous >= 0) line(previous, at);
                previous = at;
            }
        }
    }

    private void quads(float[] outerLevels, float[] innerLevels) {
        final float o0 = clamped(outerLevels[0]), o1 = clamped(outerLevels[1]), o2 = clamped(outerLevels[2]), o3 = clamped(outerLevels[3]);
        float i0 = clamped(innerLevels[0]), i1 = clamped(innerLevels[1]);
        if (i0 <= 1.0f && i1 <= 1.0f && o0 <= 1.0f && o1 <= 1.0f && o2 <= 1.0f && o3 <= 1.0f) {
            final int a = vertex(0, 0, 0), b = vertex(1, 0, 0), c = vertex(1, 1, 0), d = vertex(0, 1, 0);
            triangle(a, b, d);
            triangle(b, c, d);
            return;
        }
        // An inner level of one beside any other above it is taken as just over one, which rounds up by the spacing.
        if (i0 <= 1.0f) i0 = JUST_OVER_ONE;
        if (i1 <= 1.0f) i1 = JUST_OVER_ONE;
        final float[] us = edge(i0), vs = edge(i1);
        final int nu = us.length - 1, nv = vs.length - 1;
        final int[][] grid = new int[nu + 1][nv + 1];
        for (int x = 1; x < nu; x++) for (int y = 1; y < nv; y++) grid[x][y] = vertex(us[x], vs[y], 0);
        for (int x = 1; x < nu - 1; x++) {
            for (int y = 1; y < nv - 1; y++) {
                triangle(grid[x][y], grid[x + 1][y], grid[x][y + 1]);
                triangle(grid[x + 1][y], grid[x + 1][y + 1], grid[x][y + 1]);
            }
        }
        final IntArrayList bottom = new IntArrayList(), right = new IntArrayList(), top = new IntArrayList(), left = new IntArrayList();
        for (int x = 1; x < nu; x++) bottom.add(grid[x][1]);
        for (int y = 1; y < nv; y++) right.add(grid[nu - 1][y]);
        for (int x = nu - 1; x >= 1; x--) top.add(grid[x][nv - 1]);
        for (int y = nv - 1; y >= 1; y--) left.add(grid[1][y]);
        final int c0 = vertex(0, 0, 0), c1 = vertex(1, 0, 0), c2 = vertex(1, 1, 0), c3 = vertex(0, 1, 0);
        stitch(c0, c1, o1, bottom, 0);
        stitch(c1, c2, o2, right, 1);
        stitch(c2, c3, o3, top, 2);
        stitch(c3, c0, o0, left, 3);
    }

    /** Fills between a side of the patch, cut by its outer level, and the inner row beside it. */
    private void stitch(int from, int to, float level, IntArrayList inner, int side) {
        final float[] at = edge(level);
        final int n = at.length - 1;
        final int[] outer = new int[n + 1];
        outer[0] = from;
        outer[n] = to;
        for (int i = 1; i < n; i++) {
            final float t = at[i];
            outer[i] = switch (side) {
                case 0 -> vertex(t, 0, 0);
                case 1 -> vertex(1, t, 0);
                case 2 -> vertex(1 - t, 1, 0);
                default -> vertex(0, 1 - t, 0);
            };
        }
        strip(outer, inner.toIntArray());
    }

    /** Triangles between two rows, advancing whichever is further behind, so both are used up evenly. */
    private void strip(int[] outer, int[] inner) {
        if (inner.length == 0) {
            for (int i = 1; i + 1 < outer.length; i++) triangle(outer[0], outer[i], outer[i + 1]);
            return;
        }
        int o = 0, i = 0;
        while (o < outer.length - 1 || i < inner.length - 1) {
            final boolean advanceOuter = i >= inner.length - 1 || o < outer.length - 1 && (long) (o + 1) * (inner.length - 1) <= (long) (i + 1) * (outer.length - 1);
            if (advanceOuter) {
                triangle(outer[o], outer[o + 1], inner[i]);
                o++;
            } else {
                triangle(outer[o], inner[i + 1], inner[i]);
                i++;
            }
        }
    }

    private void triangles(float[] outerLevels, float[] innerLevels) {
        final float o0 = clamped(outerLevels[0]), o1 = clamped(outerLevels[1]), o2 = clamped(outerLevels[2]);
        float innerLevel = clamped(innerLevels[0]);
        if (innerLevel <= 1.0f && o0 <= 1.0f && o1 <= 1.0f && o2 <= 1.0f) {
            triangle(vertex(0, 0, 1), vertex(1, 0, 0), vertex(0, 1, 0));
            return;
        }
        if (innerLevel <= 1.0f) innerLevel = JUST_OVER_ONE;
        final float[] positions = edge(innerLevel);
        final int n = positions.length - 1;
        // Concentric triangles, each with two segments less to a side than the one around it, ending in a point or a triangle.
        int[][] outermost = null, previous = null;
        for (int ring = 1; ring * 2 <= n; ring++) {
            final int[][] sides = n == ring * 2 ? center() : triangleRing(ring, n, positions);
            if (outermost == null) outermost = sides;
            if (previous != null) for (int s = 0; s < 3; s++) strip(previous[s], sides[s]);
            previous = sides;
        }
        if (n % 2 == 1) triangle(previous[0][0], previous[1][0], previous[2][0]);
        final int c0 = vertex(0, 0, 1), c1 = vertex(1, 0, 0), c2 = vertex(0, 1, 0);
        triangleEdge(c0, c1, o1, outermost[0], 0);
        triangleEdge(c1, c2, o2, outermost[1], 1);
        triangleEdge(c2, c0, o0, outermost[2], 2);
    }

    private int[][] center() {
        final int c = vertex(1.0f / 3.0f, 1.0f / 3.0f, 1.0f / 3.0f);
        return new int[][] {{c}, {c}, {c}};
    }

    /** One inner ring, as its three sides, each from one corner to the next, sharing the corners. */
    private int[][] triangleRing(int ring, int n, float[] positions) {
        final int segments = n - 2 * ring;
        final float low = ring * 2.0f / (3.0f * n), high = 1.0f - 2.0f * low;
        final int[][] sides = new int[3][segments + 1];
        final int c0 = vertex(low, low, high), c1 = vertex(high, low, low), c2 = vertex(low, high, low);
        sides[0][0] = sides[2][segments] = c0;
        sides[0][segments] = sides[1][0] = c1;
        sides[1][segments] = sides[2][0] = c2;
        final float span = positions[n - ring] - positions[ring];
        for (int i = 1; i < segments; i++) {
            final float t = (positions[ring + i] - positions[ring]) / span;
            final float along = (high - low) * t;
            sides[0][i] = vertex(low + along, low, high - along);
            sides[1][i] = vertex(high - along, low + along, low);
            sides[2][i] = vertex(low, high - along, low + along);
        }
        return sides;
    }

    private void triangleEdge(int from, int to, float level, int[] inner, int side) {
        final float[] at = edge(level);
        final int n = at.length - 1;
        final int[] outer = new int[n + 1];
        outer[0] = from;
        outer[n] = to;
        for (int i = 1; i < n; i++) {
            final float t = at[i];
            outer[i] = switch (side) {
                case 0 -> vertex(t, 0, 1 - t);
                case 1 -> vertex(1 - t, t, 0);
                default -> vertex(0, 1 - t, t);
            };
        }
        strip(outer, inner);
    }
}
