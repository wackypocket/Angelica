package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Evaluators, which no modern API has: Bezier maps are evaluated here and fed to immediate mode as ordinary vertices, so display lists
 * and everything after glVertex treat them as any other geometry.
 */
public final class GlEvaluators {

    private static final int MAP1_BASE = GL11.GL_MAP1_COLOR_4, MAP2_BASE = GL11.GL_MAP2_COLOR_4;
    private static final int COLOR = 0, NORMAL = 2, TEX1 = 3, TEX2 = 4, TEX3 = 5, TEX4 = 6, VERTEX3 = 7, VERTEX4 = 8;
    private static final int[] COMPONENTS = {4, 1, 3, 1, 2, 3, 4, 3, 4};

    private static final class Map {
        int uorder = 1, vorder = 1;
        float u1, u2 = 1f, v1, v2 = 1f;
        float[] points;
    }

    /** What GL keeps per context. Like GlState, each thread has its own. */
    private static final class State {
        final Map[] map1 = maps(), map2 = maps();
        final boolean[] enabled = new boolean[COMPONENTS.length];
        int grid1n = 1, grid2un = 1, grid2vn = 1;
        float grid1u1, grid1u2 = 1f, grid2u1, grid2u2 = 1f, grid2v1, grid2v2 = 1f;
        final float[] value = new float[4];
        final float[] du = new float[3], dv = new float[3], p0 = new float[3], p1 = new float[3];
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GlEvaluators() {}

    /** GL_MAP1_GRID_DOMAIN, GL_MAP1_GRID_SEGMENTS, GL_MAP2_GRID_DOMAIN and GL_MAP2_GRID_SEGMENTS: how many values, 0 for another name. */
    static int grid(int pname, double[] out) {
        final State state = STATE.get();
        switch (pname) {
            case org.lwjgl.opengl.GL11.GL_MAP1_GRID_DOMAIN -> { out[0] = state.grid1u1; out[1] = state.grid1u2; return 2; }
            case org.lwjgl.opengl.GL11.GL_MAP1_GRID_SEGMENTS -> { out[0] = state.grid1n; return 1; }
            case org.lwjgl.opengl.GL11.GL_MAP2_GRID_DOMAIN -> { out[0] = state.grid2u1; out[1] = state.grid2u2; out[2] = state.grid2v1; out[3] = state.grid2v2; return 4; }
            case org.lwjgl.opengl.GL11.GL_MAP2_GRID_SEGMENTS -> { out[0] = state.grid2un; out[1] = state.grid2vn; return 2; }
            default -> { return 0; }
        }
    }

    private static Map[] maps() {
        final Map[] maps = new Map[COMPONENTS.length];
        for (int i = 0; i < maps.length; i++) {
            maps[i] = new Map();
            maps[i].points = new float[COMPONENTS[i]];
        }
        return maps;
    }

    private static Map map(int target) {
        final State state = STATE.get();
        if (target >= MAP1_BASE && target < MAP1_BASE + COMPONENTS.length) return state.map1[target - MAP1_BASE];
        if (target >= MAP2_BASE && target < MAP2_BASE + COMPONENTS.length) return state.map2[target - MAP2_BASE];
        return null;
    }

    private static int componentsOf(int target) {
        return COMPONENTS[target >= MAP2_BASE ? target - MAP2_BASE : target - MAP1_BASE];
    }

    static void map1(int target, float u1, float u2, int stride, int order, long points, boolean doubles) {
        map2(target, u1, u2, stride, order, 0f, 1f, 0, 1, points, doubles);
    }

    static void map2(int target, float u1, float u2, int ustride, int uorder, float v1, float v2, int vstride, int vorder, long points, boolean doubles) {
        final Map map = map(target);
        if (map == null || uorder < 1 || vorder < 1 || points == 0L) return;
        final int n = componentsOf(target);
        map.u1 = u1;
        map.u2 = u2;
        map.v1 = v1;
        map.v2 = v2;
        map.uorder = uorder;
        map.vorder = vorder;
        map.points = new float[uorder * vorder * n];
        final int size = doubles ? 8 : 4;
        for (int i = 0; i < uorder; i++) {
            for (int j = 0; j < vorder; j++) {
                for (int c = 0; c < n; c++) {
                    final long at = points + ((long) i * ustride + (long) j * vstride + c) * size;
                    map.points[(i * vorder + j) * n + c] = doubles ? (float) MemoryUtil.memGetDouble(at) : MemoryUtil.memGetFloat(at);
                }
            }
        }
    }

    /** kind: 0 float, 1 double, 2 int. */
    static void getMap(int target, int query, long out, int kind) {
        final Map map = map(target);
        if (map == null) return;
        final boolean two = target >= MAP2_BASE;
        switch (query) {
            case GL11.GL_COEFF -> {
                for (int i = 0; i < map.points.length; i++) put(out, i, map.points[i], kind);
            }
            case GL11.GL_ORDER -> {
                put(out, 0, map.uorder, kind);
                if (two) put(out, 1, map.vorder, kind);
            }
            case GL11.GL_DOMAIN -> {
                put(out, 0, map.u1, kind);
                put(out, 1, map.u2, kind);
                if (two) {
                    put(out, 2, map.v1, kind);
                    put(out, 3, map.v2, kind);
                }
            }
            default -> { }
        }
    }

    private static void put(long out, int index, float v, int kind) {
        switch (kind) {
            case 0 -> MemoryUtil.memPutFloat(out + index * 4L, v);
            case 1 -> MemoryUtil.memPutDouble(out + index * 8L, v);
            default -> MemoryUtil.memPutInt(out + index * 4L, Math.round(v));
        }
    }

    static void mapGrid1(int n, float u1, float u2) {
        final State state = STATE.get();
        state.grid1n = n;
        state.grid1u1 = u1;
        state.grid1u2 = u2;
    }

    static void mapGrid2(int un, float u1, float u2, int vn, float v1, float v2) {
        final State state = STATE.get();
        state.grid2un = un;
        state.grid2u1 = u1;
        state.grid2u2 = u2;
        state.grid2vn = vn;
        state.grid2v1 = v1;
        state.grid2v2 = v2;
    }

    /** de Casteljau over one row of control points, whose values are spread by {@code step} in the array. */
    private static float bezier(float[] points, int start, int step, int order, float t) {
        if (order == 1) return points[start];
        final float[] row = new float[order];
        for (int i = 0; i < order; i++) row[i] = points[start + i * step];
        for (int level = order - 1; level > 0; level--) {
            for (int i = 0; i < level; i++) row[i] += (row[i + 1] - row[i]) * t;
        }
        return row[0];
    }

    private static void eval1(Map map, int n, float u) {
        final State state = STATE.get();
        final float t = (u - map.u1) / (map.u2 - map.u1);
        for (int c = 0; c < n; c++) state.value[c] = bezier(map.points, c, n, map.uorder, t);
    }

    private static void eval2(Map map, int n, float u, float v) {
        final State state = STATE.get();
        final float s = (u - map.u1) / (map.u2 - map.u1), t = (v - map.v1) / (map.v2 - map.v1);
        final float[] rows = new float[map.uorder];
        for (int c = 0; c < n; c++) {
            for (int i = 0; i < map.uorder; i++) rows[i] = bezier(map.points, i * map.vorder * n + c, n, map.vorder, t);
            state.value[c] = bezier(rows, 0, 1, map.uorder, s);
        }
    }

    static void evalCoord1(float u) {
        final State state = STATE.get();
        emit(state.map1, u, 0f, false);
    }

    static void evalCoord2(float u, float v) {
        final State state = STATE.get();
        emit(state.map2, u, v, true);
    }

    private static void eval(Map[] maps, int index, float u, float v, boolean two) {
        if (two) eval2(maps[index], COMPONENTS[index], u, v);
        else eval1(maps[index], COMPONENTS[index], u);
    }

    private static void emit(Map[] maps, float u, float v, boolean two) {
        final State state = STATE.get();
        final int base = two ? MAP2_BASE : MAP1_BASE;
        for (int i = 0; i < state.enabled.length; i++) state.enabled[i] = GLSMEnable.glIsEnabled(base + i);
        final boolean autoNormal = two && GLSMEnable.glIsEnabled(GL11.GL_AUTO_NORMAL);
        final boolean vertex3 = state.enabled[VERTEX3], vertex4 = state.enabled[VERTEX4];
        final var color = GLSMCurrentAttribs.getColor();
        final float red = color.getRed(), green = color.getGreen(), blue = color.getBlue(), alpha = color.getAlpha();
        final Vector3f normal = ShaderManager.getCurrentNormal();
        final float nx = normal.x, ny = normal.y, nz = normal.z;
        final Vector4f coord = ShaderManager.getCurrentTexCoord();
        final float s0 = coord.x, t0 = coord.y, r0 = coord.z, q0 = coord.w;
        if (state.enabled[COLOR]) {
            eval(maps, COLOR, u, v, two);
            GLSMCurrentAttribs.glColor4f(state.value[0], state.value[1], state.value[2], state.value[3]);
        }
        if (state.enabled[NORMAL]) {
            eval(maps, NORMAL, u, v, two);
            GLSMCurrentAttribs.glNormal3f(state.value[0], state.value[1], state.value[2]);
        } else if (autoNormal && (vertex3 || vertex4)) {
            autoNormal(maps, vertex4 ? VERTEX4 : VERTEX3, u, v);
        }
        if (state.enabled[TEX4]) {
            eval(maps, TEX4, u, v, two);
            GLSMCurrentAttribs.glTexCoord4f(state.value[0], state.value[1], state.value[2], state.value[3]);
        } else if (state.enabled[TEX3]) {
            eval(maps, TEX3, u, v, two);
            GLSMCurrentAttribs.glTexCoord4f(state.value[0], state.value[1], state.value[2], 1f);
        } else if (state.enabled[TEX2]) {
            eval(maps, TEX2, u, v, two);
            GLSMCurrentAttribs.glTexCoord4f(state.value[0], state.value[1], 0f, 1f);
        } else if (state.enabled[TEX1]) {
            eval(maps, TEX1, u, v, two);
            GLSMCurrentAttribs.glTexCoord4f(state.value[0], 0f, 0f, 1f);
        }
        if (vertex4) {
            eval(maps, VERTEX4, u, v, two);
            GLSMDraws.glVertex3f(state.value[0] / state.value[3], state.value[1] / state.value[3], state.value[2] / state.value[3]);
        } else if (vertex3) {
            eval(maps, VERTEX3, u, v, two);
            GLSMDraws.glVertex3f(state.value[0], state.value[1], state.value[2]);
        }
        // A map supplies these for its own vertex only, and the current values stay as they were.
        if (state.enabled[COLOR]) GLSMCurrentAttribs.glColor4f(red, green, blue, alpha);
        if (state.enabled[NORMAL] || autoNormal) GLSMCurrentAttribs.glNormal3f(nx, ny, nz);
        if (state.enabled[TEX1] || state.enabled[TEX2] || state.enabled[TEX3] || state.enabled[TEX4]) GLSMCurrentAttribs.glTexCoord4f(s0, t0, r0, q0);
    }

    private static void position(Map[] maps, int index, float u, float v, float[] out) {
        final State state = STATE.get();
        eval(maps, index, u, v, true);
        final float w = index == VERTEX4 ? state.value[3] : 1f;
        out[0] = state.value[0] / w;
        out[1] = state.value[1] / w;
        out[2] = state.value[2] / w;
    }

    /** The normal GL_AUTO_NORMAL specifies, dP/du x dP/dv, with the partials approximated by central differences. */
    private static void autoNormal(Map[] maps, int index, float u, float v) {
        final State state = STATE.get();
        final Map map = maps[index];
        final float eu = (map.u2 - map.u1) * 1e-3f, ev = (map.v2 - map.v1) * 1e-3f;
        position(maps, index, u - eu, v, state.p0);
        position(maps, index, u + eu, v, state.p1);
        for (int i = 0; i < 3; i++) state.du[i] = state.p1[i] - state.p0[i];
        position(maps, index, u, v - ev, state.p0);
        position(maps, index, u, v + ev, state.p1);
        for (int i = 0; i < 3; i++) state.dv[i] = state.p1[i] - state.p0[i];
        final float x = state.du[1] * state.dv[2] - state.du[2] * state.dv[1], y = state.du[2] * state.dv[0] - state.du[0] * state.dv[2], z = state.du[0] * state.dv[1] - state.du[1] * state.dv[0];
        final float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length > 0f) GLSMCurrentAttribs.glNormal3f(x / length, y / length, z / length);
    }

    private static float grid1(int i) {
        final State state = STATE.get();
        return i == state.grid1n ? state.grid1u2 : state.grid1u1 + i * (state.grid1u2 - state.grid1u1) / state.grid1n;
    }

    private static float grid2u(int i) {
        final State state = STATE.get();
        return i == state.grid2un ? state.grid2u2 : state.grid2u1 + i * (state.grid2u2 - state.grid2u1) / state.grid2un;
    }

    private static float grid2v(int j) {
        final State state = STATE.get();
        return j == state.grid2vn ? state.grid2v2 : state.grid2v1 + j * (state.grid2v2 - state.grid2v1) / state.grid2vn;
    }

    static void evalPoint1(int i) {
        evalCoord1(grid1(i));
    }

    static void evalPoint2(int i, int j) {
        evalCoord2(grid2u(i), grid2v(j));
    }

    static void evalMesh1(int mode, int i1, int i2) {
        GLSMDraws.glBegin(mode == GL11.GL_POINT ? GL11.GL_POINTS : GL11.GL_LINE_STRIP);
        for (int i = i1; i <= i2; i++) evalCoord1(grid1(i));
        GLSMDraws.glEnd();
    }

    static void evalMesh2(int mode, int i1, int i2, int j1, int j2) {
        switch (mode) {
            case GL11.GL_POINT -> {
                GLSMDraws.glBegin(GL11.GL_POINTS);
                for (int j = j1; j <= j2; j++) {
                    for (int i = i1; i <= i2; i++) evalPoint2(i, j);
                }
                GLSMDraws.glEnd();
            }
            case GL11.GL_LINE -> {
                for (int j = j1; j <= j2; j++) {
                    GLSMDraws.glBegin(GL11.GL_LINE_STRIP);
                    for (int i = i1; i <= i2; i++) evalPoint2(i, j);
                    GLSMDraws.glEnd();
                }
                for (int i = i1; i <= i2; i++) {
                    GLSMDraws.glBegin(GL11.GL_LINE_STRIP);
                    for (int j = j1; j <= j2; j++) evalPoint2(i, j);
                    GLSMDraws.glEnd();
                }
            }
            default -> {
                for (int j = j1; j < j2; j++) {
                    GLSMDraws.glBegin(GL11.GL_QUAD_STRIP);
                    for (int i = i1; i <= i2; i++) {
                        evalPoint2(i, j);
                        evalPoint2(i, j + 1);
                    }
                    GLSMDraws.glEnd();
                }
            }
        }
    }
}
