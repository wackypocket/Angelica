package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

public abstract class ProgramsBase<P extends ProgramBase<P>> {
    private static final Logger LOG = LogManager.getLogger("Angelica/GPU");

    private final Int2ObjectOpenHashMap<Shader> shaders = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<P> programs = new Int2ObjectOpenHashMap<>();
    private int nextId = 1;
    private int nextVariantId = -1;
    private P cached;
    private GlState cachedFor;
    private int cachedId = -1;
    public long links, linkFailures, compileFailures;

    protected abstract String apiName();

    protected abstract P newProgram(int id);

    protected abstract boolean linkProgram(P p, List<Shader> shaders);

    protected abstract Object detachNativeObjects(P p);

    protected abstract void linkDone(P p, Object detached, boolean linked);

    protected abstract void retireNativeObjects(P p);

    protected abstract void destroyNativeObjects(P p);

    protected abstract boolean anyStateUsesProgram(int id);

    protected int nextName() {
        int id = nextId++;
        while (shaders.containsKey(id) || programs.containsKey(id)) id = nextId++;
        return id;
    }

    public int createShader(int type) {
        final int id = nextName();
        shaders.put(id, new Shader(id, type));
        return id;
    }

    public Shader shader(int id) {
        return shaders.get(id);
    }

    public int createProgram() {
        final int id = nextName();
        programs.put(id, newProgram(id));
        return id;
    }

    public P program(int id) {
        return id == 0 ? null : programs.get(id);
    }

    public boolean isShader(int id) {
        return shaders.containsKey(id);
    }

    public boolean isProgram(int id) {
        return programs.containsKey(id);
    }

    public P current(GlState st) {
        if (st == cachedFor && st.program == cachedId) return cached;
        cachedFor = st;
        cachedId = st.program;
        cached = program(st.program);
        return cached;
    }

    public void compile(Shader s) {
        final int fixup = switch (s.type) {
            case GL20.GL_VERTEX_SHADER -> ShaderCompiler.DEPTH_AT_END_OF_MAIN;
            case GL32.GL_GEOMETRY_SHADER -> ShaderCompiler.DEPTH_AT_EMIT;
            default -> ShaderCompiler.DEPTH_NONE;
        };
        final ShaderCompiler.Result r = ShaderCompiler.compile(s.source, s.type, "shader" + s.id + (s.label != null ? " (" + s.label + ")" : ""), fixup);
        s.result = r.compiled();
        s.resultFixup = fixup;
        s.compiled = r.compiled() != null;
        s.log = r.log() == null ? "" : r.log();
        if (!s.compiled) {
            compileFailures++;
            LOG.warn("Shader {} failed to compile for {}: {}", s.id, apiName(), s.log);
        }
    }

    public void deleteShader(int id) {
        final Shader s = shaders.get(id);
        if (s == null) return;
        if (s.attachCount > 0) {
            s.deletePending = true;
            return;
        }
        shaders.remove(id);
    }

    public void attach(P p, Shader s) {
        if (p == null || s == null || p.attached.contains(s.id)) return;
        p.attached.add(s.id);
        s.attachCount++;
    }

    public void detach(P p, Shader s) {
        if (p == null || s == null) return;
        if (p.attached.rem(s.id)) {
            s.attachCount--;
            if (s.deletePending && s.attachCount <= 0) shaders.remove(s.id);
        }
    }

    public void link(P p) {
        final List<Shader> list = new ArrayList<>();
        for (int i = 0; i < p.attached.size(); i++) {
            final Shader s = shaders.get(p.attached.getInt(i));
            if (s != null) list.add(s);
        }
        p.linkAttempted = true;
        retireLogicVariants(p);
        final Object old = detachNativeObjects(p);
        if (linkProgram(p, list)) {
            links++;
            p.linkedShaders = new Shader[list.size()];
            for (int i = 0; i < p.linkedShaders.length; i++) {
                final Shader src = list.get(i);
                final Shader copy = new Shader(src.id, src.type);
                copy.source = src.source;
                copy.result = src.result;
                copy.resultFixup = src.resultFixup;
                copy.compiled = src.compiled;
                p.linkedShaders[i] = copy;
            }
            linkDone(p, old, true);
        } else {
            linkFailures++;
            p.linked = false;
            LOG.warn("Program {} failed to link for {}: {}", p.id, apiName(), p.log);
            linkDone(p, old, false);
        }
        if (cached == p) cachedId = -1;
    }

    protected void retireLogicVariants(P p) {
        for (P v : p.logicVariants.values()) {
            retireNativeObjects(v);
            v.resetLinkResults();
        }
        p.logicVariants.clear();
        p.logicFailed.clear();
    }

    public P logicOpVariant(P base, long key) {
        final P hit = base.logicVariants.get(key);
        if (hit != null) return hit;
        if (base.logicFailed.contains(key)) return null;
        try {
            final P built = buildLogicOpVariant(base, key);
            base.logicVariants.put(key, built);
            return built;
        } catch (RuntimeException e) {
            base.logicFailed.add(key);
            LOG.warn("glLogicOp is not emulated for program {}: {}", base.id, e.getMessage());
            return null;
        }
    }

    protected P buildLogicOpVariant(P base, long key) {
        Shader fragment = null;
        for (Shader s : base.linkedShaders) if (s.type == GL20.GL_FRAGMENT_SHADER) fragment = s;
        if (fragment == null || base.compute) throw new IllegalStateException("no fragment shader");
        final String name = "logicop" + base.id;
        final String clean = fragment.source.indexOf('\0') >= 0 ? fragment.source.replace("\0", "") : fragment.source;
        final GlslVulkanPreprocess.Result pre = GlslVulkanPreprocess.run(clean, GL20.GL_FRAGMENT_SHADER, name, false);
        if (pre == null) throw new IllegalStateException("fragment preprocessing failed");
        final String lowered = LogicOpLowering.lower(pre.rewrittenSource(), key, base.id);
        final ShaderCompiler.Result r = ShaderCompiler.compilePreprocessed(lowered, pre.boolUniforms(), name);
        if (r.compiled() == null) throw new IllegalStateException("variant failed to compile: " + r.log());

        final Shader variantFragment = new Shader(fragment.id, GL20.GL_FRAGMENT_SHADER);
        variantFragment.source = lowered;
        variantFragment.result = r.compiled();
        variantFragment.resultFixup = ShaderCompiler.DEPTH_NONE;
        variantFragment.compiled = true;
        final List<Shader> list = new ArrayList<>();
        for (Shader s : base.linkedShaders) list.add(s == fragment ? variantFragment : s);
        final P v = newProgram(nextVariantId--);
        v.label = "logicop of " + base.id;
        v.attribBindings.putAll(base.attribBindings);
        v.fragDataBindings.putAll(base.fragDataBindings);
        if (!linkProgram(v, list)) throw new IllegalStateException("variant failed to link: " + v.log);

        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final ProgramBase.StageBlock a = base.blocks[s], b = v.blocks[s];
            if (a == null != (b == null) || a != null && a.size != b.size) {
                destroyNativeObjects(v);
                v.resetLinkResults();
                throw new IllegalStateException("default uniform block layout differs in stage " + s);
            }
        }
        v.logicBase = base;
        v.logicBaseUniform = new int[v.uniforms.size()];
        for (int i = 0; i < v.logicBaseUniform.length; i++) {
            final int loc = base.locations.getInt(v.uniforms.get(i).name);
            v.logicBaseUniform[i] = loc < 0 ? -1 : base.locationUniform[loc];
        }
        return v;
    }

    public void deleteProgram(int id, GlState st) {
        final P p = programs.get(id);
        if (p == null) return;
        if (anyStateUsesProgram(id)) {
            p.deletePending = true;
            return;
        }
        programs.remove(id);
        retireLogicVariants(p);
        for (int i = 0; i < p.attached.size(); i++) {
            final Shader s = shaders.get(p.attached.getInt(i));
            if (s != null) {
                s.attachCount--;
                if (s.deletePending && s.attachCount <= 0) shaders.remove(s.id);
            }
        }
        retireNativeObjects(p);
        p.resetLinkResults();
        if (cached == p) cachedId = -1;
    }

    public void uniform(P p, int location, int count, int comps, boolean srcFloat, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        int element = p.locationElement[location];
        final int n = Math.min(count, Math.max(1, u.arraySize) - element);
        if (u.isOpaque()) {
            for (int i = 0; i < n; i++) {
                final int v = srcFloat ? (int) MemoryUtil.memGetFloat(src + i * 4L) : MemoryUtil.memGetInt(src + i * 4L);
                u.units[element + i] = v;
            }
            return;
        }
        final int type = u.glType;
        final int typeComps = components(type);
        final int scalar = scalarKind(type);
        if (n == 1 && srcFloat && scalar == 0) {
            final int k = Math.min(comps, typeComps);
            for (int s = 0; s < ProgramBase.STAGES; s++) {
                final int off = u.offset[s];
                if (off < 0) continue;
                final ProgramBase.StageBlock blk = p.blocks[s];
                final long dst = blk.address + off + (long) element * u.arrayStride[s];
                if (dst + typeComps * 4L > blk.address + blk.size) continue;
                boolean changed = false;
                for (int c = 0; c < k; c++) {
                    final int bits = MemoryUtil.memGetInt(src + c * 4L);
                    if (MemoryUtil.memGetInt(dst + c * 4L) != bits) {
                        MemoryUtil.memPutInt(dst + c * 4L, bits);
                        changed = true;
                    }
                }
                if (changed) blk.dirty = true;
            }
            return;
        }
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final int off = u.offset[s];
            if (off < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            final int stride = u.arrayStride[s];
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                final long dst = blk.address + off + (long) (element + i) * stride;
                if (dst + typeComps * 4L > blk.address + blk.size) break;
                for (int c = 0; c < Math.min(comps, typeComps); c++) {
                    final long in = src + ((long) i * comps + c) * 4;
                    final int bits = switch (scalar) {
                        case 0 -> Float.floatToRawIntBits(srcFloat ? MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in));
                        case 3 -> (srcFloat ? MemoryUtil.memGetFloat(in) != 0f : MemoryUtil.memGetInt(in) != 0) ? 1 : 0;
                        default -> srcFloat ? (int) MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in);
                    };
                    if (MemoryUtil.memGetInt(dst + c * 4L) != bits) {
                        MemoryUtil.memPutInt(dst + c * 4L, bits);
                        changed = true;
                    }
                }
            }
            if (changed) blk.dirty = true;
        }
    }

    public void uniformMatrix(P p, int location, int count, int cols, int rows, boolean transpose, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        if (u.isOpaque()) return;
        final int element = p.locationElement[location];
        final int n = Math.min(count, Math.max(1, u.arraySize) - element);
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final int off = u.offset[s];
            if (off < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            final int stride = u.arrayStride[s];
            final int ms = u.matrixStride[s];
            final boolean rowMajor = u.rowMajor[s];
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                final long base = blk.address + off + (long) (element + i) * stride;
                final long in = src + (long) i * cols * rows * 4;
                for (int c = 0; c < cols; c++) {
                    for (int r = 0; r < rows; r++) {
                        final float v = MemoryUtil.memGetFloat(in + (transpose ? (long) r * cols + c : (long) c * rows + r) * 4);
                        final long dst = rowMajor ? base + (long) r * ms + c * 4L : base + (long) c * ms + r * 4L;
                        if (dst + 4 <= blk.address + blk.size) {
                            final int bits = Float.floatToRawIntBits(v);
                            if (MemoryUtil.memGetInt(dst) != bits) {
                                MemoryUtil.memPutInt(dst, bits);
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (changed) blk.dirty = true;
        }
    }

    public void getUniform(P p, int location, long dst, boolean asFloat) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        final int element = p.locationElement[location];
        if (u.isOpaque()) {
            if (asFloat) MemoryUtil.memPutFloat(dst, u.units[element]);
            else MemoryUtil.memPutInt(dst, u.units[element]);
            return;
        }
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            if (u.offset[s] < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            final long src = blk.address + u.offset[s] + (long) element * u.arrayStride[s];
            final int comps = components(u.glType);
            final boolean isFloat = scalarKind(u.glType) == 0;
            for (int c = 0; c < comps; c++) {
                final long in = src + c * 4L;
                if (asFloat) MemoryUtil.memPutFloat(dst + c * 4L, isFloat ? MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in));
                else MemoryUtil.memPutInt(dst + c * 4L, isFloat ? (int) MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in));
            }
            return;
        }
    }

    public static int components(int type) {
        return switch (type) {
            case GL20.GL_FLOAT_VEC2, GL20.GL_INT_VEC2, GL20.GL_BOOL_VEC2, GL30.GL_UNSIGNED_INT_VEC2 -> 2;
            case GL20.GL_FLOAT_VEC3, GL20.GL_INT_VEC3, GL20.GL_BOOL_VEC3, GL30.GL_UNSIGNED_INT_VEC3 -> 3;
            case GL20.GL_FLOAT_VEC4, GL20.GL_INT_VEC4, GL20.GL_BOOL_VEC4, GL30.GL_UNSIGNED_INT_VEC4, GL20.GL_FLOAT_MAT2 -> 4;
            case GL20.GL_FLOAT_MAT3 -> 9;
            case GL20.GL_FLOAT_MAT4 -> 16;
            case GL21.GL_FLOAT_MAT2x3, GL21.GL_FLOAT_MAT3x2 -> 6;
            case GL21.GL_FLOAT_MAT2x4, GL21.GL_FLOAT_MAT4x2 -> 8;
            case GL21.GL_FLOAT_MAT3x4, GL21.GL_FLOAT_MAT4x3 -> 12;
            default -> 1;
        };
    }

    public static int scalarKind(int type) {
        return switch (type) {
            case GL11.GL_INT, GL20.GL_INT_VEC2, GL20.GL_INT_VEC3, GL20.GL_INT_VEC4 -> 1;
            case GL11.GL_UNSIGNED_INT, GL30.GL_UNSIGNED_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC3, GL30.GL_UNSIGNED_INT_VEC4 -> 2;
            case GL20.GL_BOOL, GL20.GL_BOOL_VEC2, GL20.GL_BOOL_VEC3, GL20.GL_BOOL_VEC4 -> 3;
            default -> 0;
        };
    }

    public int programi(P p, int pname) {
        if (p == null) return 0;
        return switch (pname) {
            case GL20.GL_LINK_STATUS -> p.linked ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_DELETE_STATUS -> p.deletePending ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_VALIDATE_STATUS -> p.linked ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_INFO_LOG_LENGTH -> p.log.isEmpty() ? 0 : p.log.length() + 1;
            case GL20.GL_ATTACHED_SHADERS -> p.attached.size();
            case GL20.GL_ACTIVE_UNIFORMS -> p.uniforms.size();
            case GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH -> {
                int max = 0;
                for (ProgramBase.Uniform u : p.uniforms) max = Math.max(max, u.name.length() + (u.arraySize > 1 ? 3 : 0) + 1);
                yield max;
            }
            case GL20.GL_ACTIVE_ATTRIBUTES -> p.attribs.size();
            case GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH -> {
                int max = 0;
                for (P.Attrib a : p.attribs) max = Math.max(max, a.name.length() + 1);
                yield max;
            }
            case GL31.GL_ACTIVE_UNIFORM_BLOCKS -> p.ubos.size();
            case GL31.GL_ACTIVE_UNIFORM_BLOCK_MAX_NAME_LENGTH -> {
                int max = 0;
                for (P.BufferBlock b : p.ubos) max = Math.max(max, b.name.length() + 1);
                yield max;
            }
            case org.lwjgl.opengl.GL43.GL_COMPUTE_WORK_GROUP_SIZE -> p.localX;
            case GL32.GL_GEOMETRY_VERTICES_OUT, GL40.GL_TESS_CONTROL_OUTPUT_VERTICES -> 0;
            case org.lwjgl.opengl.GL41.GL_PROGRAM_BINARY_LENGTH -> 0;
            default -> 0;
        };
    }

    public int shaderi(Shader s, int pname) {
        if (s == null) return 0;
        return switch (pname) {
            case GL20.GL_SHADER_TYPE -> s.type;
            case GL20.GL_COMPILE_STATUS -> s.compiled ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_DELETE_STATUS -> s.deletePending ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_INFO_LOG_LENGTH -> s.log.isEmpty() ? 0 : s.log.length() + 1;
            case GL20.GL_SHADER_SOURCE_LENGTH -> s.source.length() + 1;
            default -> 0;
        };
    }

    public static String activeName(ProgramBase.Uniform u) {
        return u.arraySize > 1 ? u.name + "[0]" : u.name;
    }

    public void destroy() {
        for (P p : programs.values()) {
            for (P v : p.logicVariants.values()) {
                destroyNativeObjects(v);
                v.resetLinkResults();
            }
            p.logicVariants.clear();
            destroyNativeObjects(p);
            p.resetLinkResults();
        }
        programs.clear();
        shaders.clear();
    }
}
