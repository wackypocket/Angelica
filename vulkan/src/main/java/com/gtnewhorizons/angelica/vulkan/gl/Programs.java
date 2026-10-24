package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.shader.Program;
import com.gtnewhorizons.angelica.vulkan.shader.ProgramLinker;
import com.gtnewhorizons.angelica.vulkan.shader.Shader;
import com.gtnewhorizons.angelica.vulkan.shader.ShaderCompiler;
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

final class Programs {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private final VulkanGL gl;
    final ProgramLinker linker;
    private final Int2ObjectOpenHashMap<Shader> shaders = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Program> programs = new Int2ObjectOpenHashMap<>();
    private int nextId = 1;
    private Program cached;
    private GlState cachedFor;
    private int cachedId = -1;
    long links, linkFailures, compileFailures;

    Programs(VulkanGL gl) {
        this.gl = gl;
        this.linker = new ProgramLinker(gl.gpu);
    }

    private int nextName() {
        int id = nextId++;
        while (shaders.containsKey(id) || programs.containsKey(id)) id = nextId++;
        return id;
    }

    int createShader(int type) {
        final int id = nextName();
        shaders.put(id, new Shader(id, type));
        return id;
    }

    Shader shader(int id) {
        return shaders.get(id);
    }

    int createProgram() {
        final int id = nextName();
        programs.put(id, new Program(id));
        return id;
    }

    Program program(int id) {
        return id == 0 ? null : programs.get(id);
    }

    boolean isShader(int id) {
        return shaders.containsKey(id);
    }

    boolean isProgram(int id) {
        return programs.containsKey(id);
    }

    Program current(GlState st) {
        if (st == cachedFor && st.program == cachedId) return cached;
        cachedFor = st;
        cachedId = st.program;
        cached = program(st.program);
        return cached;
    }

    void compile(Shader s) {
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
            LOG.warn("Shader {} failed to compile for Vulkan: {}", s.id, s.log);
        }
    }

    void deleteShader(int id) {
        final Shader s = shaders.get(id);
        if (s == null) return;
        if (s.attachCount > 0) {
            s.deletePending = true;
            return;
        }
        shaders.remove(id);
    }

    void attach(Program p, Shader s) {
        if (p == null || s == null || p.attached.contains(s.id)) return;
        p.attached.add(s.id);
        s.attachCount++;
    }

    void detach(Program p, Shader s) {
        if (p == null || s == null) return;
        if (p.attached.rem(s.id)) {
            s.attachCount--;
            if (s.deletePending && s.attachCount <= 0) shaders.remove(s.id);
        }
    }

    void link(Program p) {
        final List<Shader> list = new ArrayList<>();
        for (int i = 0; i < p.attached.size(); i++) {
            final Shader s = shaders.get(p.attached.getInt(i));
            if (s != null) list.add(s);
        }
        p.linkAttempted = true;
        final long[] oldModules = p.modules.clone();
        final long oldLayout = p.pipelineLayout, oldSetLayout = p.setLayout;
        if (linker.link(p, list)) {
            links++;
            retireVulkanObjects(oldModules, oldLayout, oldSetLayout);
        } else {
            linkFailures++;
            p.linked = false;
            LOG.warn("Program {} failed to link for Vulkan: {}", p.id, p.log);
        }
        if (cached == p) cachedId = -1;
    }

    private void retireVulkanObjects(long[] modules, long layout, long setLayout) {
        gl.stream.defer(() -> {
            for (long m : modules) if (m != 0L) org.lwjgl.vulkan.VK10.vkDestroyShaderModule(gl.gpu.device, m, null);
            if (layout != 0L) org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(gl.gpu.device, layout, null);
            if (setLayout != 0L) org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(gl.gpu.device, setLayout, null);
        });
    }

    void deleteProgram(int id, GlState st) {
        final Program p = programs.get(id);
        if (p == null) return;
        if (gl.anyStateUsesProgram(id)) {
            p.deletePending = true;
            return;
        }
        programs.remove(id);
        for (int i = 0; i < p.attached.size(); i++) {
            final Shader s = shaders.get(p.attached.getInt(i));
            if (s != null) {
                s.attachCount--;
                if (s.deletePending && s.attachCount <= 0) shaders.remove(s.id);
            }
        }
        retireVulkanObjects(p.modules.clone(), p.pipelineLayout, p.setLayout);
        p.resetLinkResults();
        if (cached == p) cachedId = -1;
    }


    void uniform(Program p, int location, int count, int comps, boolean srcFloat, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length) return;
        final Program.Uniform u = p.uniforms.get(p.locationUniform[location]);
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
        for (int s = 0; s < Program.STAGES; s++) {
            final int off = u.offset[s];
            if (off < 0) continue;
            final Program.StageBlock blk = p.blocks[s];
            final int stride = u.arrayStride[s];
            for (int i = 0; i < n; i++) {
                final long dst = blk.address + off + (long) (element + i) * stride;
                if (dst + typeComps * 4L > blk.address + blk.size) break;
                for (int c = 0; c < Math.min(comps, typeComps); c++) {
                    final long in = src + ((long) i * comps + c) * 4;
                    switch (scalar) {
                        case 0 -> MemoryUtil.memPutFloat(dst + c * 4L, srcFloat ? MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in));
                        case 3 -> MemoryUtil.memPutInt(dst + c * 4L, (srcFloat ? MemoryUtil.memGetFloat(in) != 0f : MemoryUtil.memGetInt(in) != 0) ? 1 : 0);
                        default -> MemoryUtil.memPutInt(dst + c * 4L, srcFloat ? (int) MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in));
                    }
                }
            }
            blk.dirty = true;
        }
    }

    void uniformMatrix(Program p, int location, int count, int cols, int rows, boolean transpose, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length) return;
        final Program.Uniform u = p.uniforms.get(p.locationUniform[location]);
        if (u.isOpaque()) return;
        final int element = p.locationElement[location];
        final int n = Math.min(count, Math.max(1, u.arraySize) - element);
        for (int s = 0; s < Program.STAGES; s++) {
            final int off = u.offset[s];
            if (off < 0) continue;
            final Program.StageBlock blk = p.blocks[s];
            final int stride = u.arrayStride[s];
            final int ms = u.matrixStride[s];
            final boolean rowMajor = u.rowMajor[s];
            for (int i = 0; i < n; i++) {
                final long base = blk.address + off + (long) (element + i) * stride;
                final long in = src + (long) i * cols * rows * 4;
                for (int c = 0; c < cols; c++) {
                    for (int r = 0; r < rows; r++) {
                        final float v = MemoryUtil.memGetFloat(in + (transpose ? (long) r * cols + c : (long) c * rows + r) * 4);
                        final long dst = rowMajor ? base + (long) r * ms + c * 4L : base + (long) c * ms + r * 4L;
                        if (dst + 4 <= blk.address + blk.size) MemoryUtil.memPutFloat(dst, v);
                    }
                }
            }
            blk.dirty = true;
        }
    }

    void getUniform(Program p, int location, long dst, boolean asFloat) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length) return;
        final Program.Uniform u = p.uniforms.get(p.locationUniform[location]);
        final int element = p.locationElement[location];
        if (u.isOpaque()) {
            if (asFloat) MemoryUtil.memPutFloat(dst, u.units[element]);
            else MemoryUtil.memPutInt(dst, u.units[element]);
            return;
        }
        for (int s = 0; s < Program.STAGES; s++) {
            if (u.offset[s] < 0) continue;
            final Program.StageBlock blk = p.blocks[s];
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

    static int components(int type) {
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

    static int scalarKind(int type) {
        return switch (type) {
            case GL11.GL_INT, GL20.GL_INT_VEC2, GL20.GL_INT_VEC3, GL20.GL_INT_VEC4 -> 1;
            case GL11.GL_UNSIGNED_INT, GL30.GL_UNSIGNED_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC3, GL30.GL_UNSIGNED_INT_VEC4 -> 2;
            case GL20.GL_BOOL, GL20.GL_BOOL_VEC2, GL20.GL_BOOL_VEC3, GL20.GL_BOOL_VEC4 -> 3;
            default -> 0;
        };
    }

    int programi(Program p, int pname) {
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
                for (Program.Uniform u : p.uniforms) max = Math.max(max, u.name.length() + (u.arraySize > 1 ? 3 : 0) + 1);
                yield max;
            }
            case GL20.GL_ACTIVE_ATTRIBUTES -> p.attribs.size();
            case GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH -> {
                int max = 0;
                for (Program.Attrib a : p.attribs) max = Math.max(max, a.name.length() + 1);
                yield max;
            }
            case GL31.GL_ACTIVE_UNIFORM_BLOCKS -> p.ubos.size();
            case GL31.GL_ACTIVE_UNIFORM_BLOCK_MAX_NAME_LENGTH -> {
                int max = 0;
                for (Program.BufferBlock b : p.ubos) max = Math.max(max, b.name.length() + 1);
                yield max;
            }
            case org.lwjgl.opengl.GL43.GL_COMPUTE_WORK_GROUP_SIZE -> p.localX;
            case GL32.GL_GEOMETRY_VERTICES_OUT, GL40.GL_TESS_CONTROL_OUTPUT_VERTICES -> 0;
            case org.lwjgl.opengl.GL41.GL_PROGRAM_BINARY_LENGTH -> 0;
            default -> 0;
        };
    }

    int shaderi(Shader s, int pname) {
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

    static String activeName(Program.Uniform u) {
        return u.arraySize > 1 ? u.name + "[0]" : u.name;
    }

    void destroy() {
        for (Program p : programs.values()) {
            linker.destroy(p);
            p.resetLinkResults();
        }
        programs.clear();
        shaders.clear();
    }
}
