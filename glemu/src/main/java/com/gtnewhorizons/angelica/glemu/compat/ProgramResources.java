package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.shader.GlslSubroutines;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.gl;
import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/** The program interface queries of section 7.3.1, each list built from what linking reflected when it is asked for. */
final class ProgramResources {
    private ProgramResources() {}

    private static final class Resource {
        String name = "";
        /** A program's inputs and outputs, which only its first and last stages have. */
        boolean programInterface;
        int type, arraySize = 1, offset = -1, blockIndex = -1, arrayStride = -1, matrixStride = -1, rowMajor, atomicBuffer = -1;
        int location = -1, locationIndex = -1, component, perPatch, stageSlots;
        int topLevelSize = 1, topLevelStride, binding, dataSize, feedbackBuffer = -1, feedbackStride;
        int[] active = {};
        int[] compatible = {};
        boolean named = true;
    }

    private static final int[] SUBROUTINES = {GL43.GL_VERTEX_SUBROUTINE, GL43.GL_TESS_CONTROL_SUBROUTINE, GL43.GL_TESS_EVALUATION_SUBROUTINE, GL43.GL_GEOMETRY_SUBROUTINE, GL43.GL_FRAGMENT_SUBROUTINE, GL43.GL_COMPUTE_SUBROUTINE};
    private static final int[] SUBROUTINE_UNIFORMS = {GL43.GL_VERTEX_SUBROUTINE_UNIFORM, GL43.GL_TESS_CONTROL_SUBROUTINE_UNIFORM, GL43.GL_TESS_EVALUATION_SUBROUTINE_UNIFORM, GL43.GL_GEOMETRY_SUBROUTINE_UNIFORM, GL43.GL_FRAGMENT_SUBROUTINE_UNIFORM, GL43.GL_COMPUTE_SUBROUTINE_UNIFORM};
    private static final int[] STAGE_TYPES = {GL20.GL_VERTEX_SHADER, GL40.GL_TESS_CONTROL_SHADER, GL40.GL_TESS_EVALUATION_SHADER, GL32.GL_GEOMETRY_SHADER, GL20.GL_FRAGMENT_SHADER, GL43.GL_COMPUTE_SHADER};

    private static int indexIn(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static boolean knownInterface(int iface) {
        return switch (iface) {
            case GL43.GL_UNIFORM, GL43.GL_UNIFORM_BLOCK, GL42.GL_ATOMIC_COUNTER_BUFFER, GL43.GL_PROGRAM_INPUT, GL43.GL_PROGRAM_OUTPUT, GL43.GL_TRANSFORM_FEEDBACK_VARYING,
                 GL44.GL_TRANSFORM_FEEDBACK_BUFFER, GL43.GL_BUFFER_VARIABLE, GL43.GL_SHADER_STORAGE_BLOCK -> true;
            default -> indexIn(SUBROUTINES, iface) >= 0 || indexIn(SUBROUTINE_UNIFORMS, iface) >= 0;
        };
    }

    private static boolean unnamed(int iface) {
        return iface == GL42.GL_ATOMIC_COUNTER_BUFFER || iface == GL44.GL_TRANSFORM_FEEDBACK_BUFFER;
    }

    private static boolean hasLocations(int iface) {
        return iface == GL43.GL_UNIFORM || iface == GL43.GL_PROGRAM_INPUT || iface == GL43.GL_PROGRAM_OUTPUT || iface == GL43.GL_TRANSFORM_FEEDBACK_VARYING && false || indexIn(SUBROUTINE_UNIFORMS, iface) >= 0;
    }

    /** The program, or null with the error GL gives for what the name is instead. */
    private static ProgramBase<?> program(int id) {
        final ProgramBase<?> p = id == 0 ? null : gl().programBase(id);
        if (p == null) gl().recordError(id != 0 && gl().isShader(id) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return p;
    }

    private static List<Resource> list(ProgramBase<?> p, int iface) {
        if (!p.linked) return List.of();
        return switch (iface) {
            case GL43.GL_UNIFORM -> uniforms(p);
            case GL43.GL_UNIFORM_BLOCK -> blocks(p, p.ubos, GL43.GL_UNIFORM);
            case GL43.GL_SHADER_STORAGE_BLOCK -> blocks(p, plainStorage(p), GL43.GL_BUFFER_VARIABLE);
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> atomicBuffers(p);
            case GL43.GL_BUFFER_VARIABLE -> bufferVariables(p);
            case GL43.GL_PROGRAM_INPUT -> variables(p, p.programInputs, false);
            case GL43.GL_PROGRAM_OUTPUT -> variables(p, p.programOutputs, true);
            case GL43.GL_TRANSFORM_FEEDBACK_VARYING -> feedbackVaryings(p);
            case GL44.GL_TRANSFORM_FEEDBACK_BUFFER -> feedbackBuffers(p);
            default -> {
                final int routine = indexIn(SUBROUTINES, iface);
                yield routine >= 0 ? subroutines(p, routine) : subroutineUniforms(p, indexIn(SUBROUTINE_UNIFORMS, iface));
            }
        };
    }

    private static List<ProgramBase.BufferBlock> plainStorage(ProgramBase<?> p) {
        final List<ProgramBase.BufferBlock> blocks = new ArrayList<>();
        for (ProgramBase.BufferBlock b : p.ssbos) if (!b.atomicCounters && !b.internal()) blocks.add(b);
        return blocks;
    }

    private static List<ProgramBase.BufferBlock> atomicStorage(ProgramBase<?> p) {
        final List<ProgramBase.BufferBlock> blocks = new ArrayList<>();
        for (ProgramBase.BufferBlock b : p.ssbos) if (b.atomicCounters) blocks.add(b);
        return blocks;
    }

    /** Which stage slots use a uniform. A stage running as a compute shader knows it by the same name in its own program. */
    private static int uniformSlots(ProgramBase<?> p, ProgramBase.Uniform u) {
        int slots = 0;
        if (u.isOpaque()) {
            final String bare = u.name;
            for (Shader s : p.linkedShaders) {
                final int stage = indexIn(STAGE_TYPES, s.type);
                if (stage >= 0 && s.source != null && s.source.contains(bare)) slots |= 1 << (stage == 5 ? ProgramBase.CS : stage);
            }
        } else {
            slots = u.reachedSlots;
        }
        return slots | emulatedSlots(p, u.name, true);
    }

    private static int emulatedSlots(ProgramBase<?> p, String name, boolean uniform) {
        if (p.geometry == null) return 0;
        return (declares(p.geometry.control, name, uniform) ? 1 << ProgramBase.TCS : 0) | (declares(p.geometry.evaluation, name, uniform) ? 1 << ProgramBase.TES : 0)
            | (declares(p.geometry.compute, name, uniform) ? 1 << ProgramBase.GS : 0);
    }

    private static boolean declares(ProgramBase<?> stage, String name, boolean uniform) {
        if (stage == null) return false;
        if (uniform) {
            for (ProgramBase.Uniform u : stage.uniforms) if (u.name.equals(name)) return true;
            return false;
        }
        for (ProgramBase.BufferBlock b : stage.ubos) if (b.name.equals(name)) return true;
        for (ProgramBase.BufferBlock b : stage.ssbos) if (b.name.equals(name)) return true;
        return false;
    }

    private static List<Resource> uniforms(ProgramBase<?> p) {
        final List<Resource> out = new ArrayList<>();
        final List<ProgramBase.BufferBlock> atomic = atomicStorage(p);
        for (int i = 0; i < p.activeUniformCount(); i++) {
            final ProgramBase.Uniform u = p.activeUniform(i);
            if (u.name.startsWith("angelica_")) continue;
            final Resource r = new Resource();
            r.name = ProgramsBase.activeName(u);
            r.type = u.declaredDouble != 0 ? u.declaredDouble : u.glType;
            r.arraySize = Math.max(1, u.arraySize);
            if (u.blockIndex >= 0) {
                final ProgramBase.BufferBlock block = p.ubos.get(u.blockIndex);
                r.blockIndex = u.blockIndex;
                r.offset = u.offset[0];
                r.arrayStride = u.arrayStride[0];
                r.matrixStride = u.matrixStride[0];
                r.rowMajor = u.rowMajor[0] ? 1 : 0;
                r.stageSlots = block.stageSlots | emulatedSlots(p, block.name, false);
            } else if (u.atomicBuffer >= 0) {
                r.atomicBuffer = u.atomicBuffer;
                r.offset = u.offset[0];
                r.arrayStride = u.arrayStride[0];
                r.matrixStride = 0;
                r.stageSlots = atomic.get(u.atomicBuffer).stageSlots;
            } else {
                r.location = rb().getUniformLocation(p.id, u.name);
                r.stageSlots = uniformSlots(p, u);
            }
            out.add(r);
        }
        return out;
    }

    private static List<Resource> blocks(ProgramBase<?> p, List<ProgramBase.BufferBlock> blocks, int memberInterface) {
        final List<Resource> out = new ArrayList<>();
        final List<Resource> members = memberInterface == GL43.GL_UNIFORM ? uniforms(p) : bufferVariables(p);
        for (int b = 0; b < blocks.size(); b++) {
            final ProgramBase.BufferBlock block = blocks.get(b);
            final Resource r = new Resource();
            r.name = block.name;
            r.binding = block.glBinding;
            r.dataSize = block.size;
            r.stageSlots = block.stageSlots | emulatedSlots(p, block.name, false);
            final List<Integer> active = new ArrayList<>();
            for (int m = 0; m < members.size(); m++) if (members.get(m).blockIndex == b) active.add(m);
            r.active = active.stream().mapToInt(Integer::intValue).toArray();
            out.add(r);
        }
        return out;
    }

    private static List<Resource> atomicBuffers(ProgramBase<?> p) {
        final List<Resource> out = new ArrayList<>();
        final List<ProgramBase.BufferBlock> blocks = atomicStorage(p);
        final List<Resource> uniforms = uniforms(p);
        for (int b = 0; b < blocks.size(); b++) {
            final Resource r = new Resource();
            r.named = false;
            r.binding = blocks.get(b).glBinding;
            r.dataSize = blocks.get(b).size;
            r.stageSlots = blocks.get(b).stageSlots;
            final List<Integer> active = new ArrayList<>();
            for (int m = 0; m < uniforms.size(); m++) if (uniforms.get(m).atomicBuffer == b) active.add(m);
            r.active = active.stream().mapToInt(Integer::intValue).toArray();
            out.add(r);
        }
        return out;
    }

    private static List<Resource> bufferVariables(ProgramBase<?> p) {
        final List<Resource> out = new ArrayList<>();
        final List<ProgramBase.BufferBlock> blocks = plainStorage(p);
        for (int b = 0; b < blocks.size(); b++) {
            final ProgramBase.BufferBlock block = blocks.get(b);
            for (StageReflection.Member m : block.members) {
                final Resource r = new Resource();
                r.name = m.arrayStride > 0 ? m.name + "[0]" : m.name;
                r.type = m.glType;
                r.arraySize = m.arrayStride > 0 ? m.arraySize : 1;
                r.offset = m.offset;
                r.blockIndex = b;
                r.arrayStride = m.arrayStride;
                r.matrixStride = m.matrixStride;
                r.rowMajor = m.rowMajor ? 1 : 0;
                r.topLevelSize = m.topLevelSize;
                r.topLevelStride = m.topLevelStride;
                r.stageSlots = block.stageSlots | emulatedSlots(p, block.name, false);
                out.add(r);
            }
        }
        return out;
    }

    private static List<Resource> variables(ProgramBase<?> p, List<ProgramBase.IoVariable> variables, boolean output) {
        final List<Resource> out = new ArrayList<>();
        for (ProgramBase.IoVariable v : variables) {
            if (v.builtin() && !written(p, v)) continue;
            final Resource r = new Resource();
            r.name = v.name();
            r.type = v.glType();
            r.programInterface = true;
            r.arraySize = v.arraySize();
            r.component = v.component();
            r.perPatch = v.perPatch() ? 1 : 0;
            r.stageSlots = 1 << v.slot();
            if (!v.builtin()) {
                final String bare = v.name().endsWith("[0]") ? v.name().substring(0, v.name().length() - 3) : v.name();
                final int bound = output && v.slot() == ProgramBase.FS ? p.fragDataBindings.getInt(bare) : -1;
                final int attribute = !output && v.slot() == ProgramBase.VS && !p.compute ? rb().getAttribLocation(p.id, bare) : -1;
                r.location = bound >= 0 ? bound : attribute >= 0 ? attribute : v.location();
                if (output && v.slot() == ProgramBase.FS) {
                    final int boundIndex = p.fragDataIndices.getInt(bare);
                    r.locationIndex = boundIndex > 0 ? boundIndex : v.index();
                }
            }
            out.add(r);
        }
        return out;
    }

    /** Whether the application's source uses a built-in, as the shader the device compiles can use ones it did not. Declaring one in a gl_PerVertex block is not using it. */
    private static boolean written(ProgramBase<?> p, ProgramBase.IoVariable v) {
        final String source = p.stageSources.get(STAGE_TYPES[v.slot()]);
        if (source == null) return false;
        final String code = PER_VERTEX.matcher(source).replaceAll("");
        final String name = v.name().startsWith("gl_PerVertex.") ? v.name().substring("gl_PerVertex.".length()) : v.name();
        return code.contains(name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name);
    }

    private static final java.util.regex.Pattern PER_VERTEX = java.util.regex.Pattern.compile("\\bgl_PerVertex\\s*\\{[^}]*\\}");
    private static final java.util.regex.Pattern ELEMENT = java.util.regex.Pattern.compile("\\[(?!0\\d)\\d+\\]");

    private static List<Resource> feedbackVaryings(ProgramBase<?> p) {
        final List<Resource> out = new ArrayList<>();
        for (GlTransformFeedback.ResourceVarying v : GlTransformFeedback.resourceVaryings(p.id)) {
            final Resource r = new Resource();
            r.name = v.name();
            r.type = v.glType();
            r.programInterface = true;
            r.arraySize = v.arraySize();
            r.offset = v.offset();
            r.feedbackBuffer = v.buffer();
            out.add(r);
        }
        return out;
    }

    private static List<Resource> feedbackBuffers(ProgramBase<?> p) {
        final List<Resource> out = new ArrayList<>();
        final List<GlTransformFeedback.ResourceVarying> varyings = GlTransformFeedback.resourceVaryings(p.id);
        int buffers = 0;
        for (GlTransformFeedback.ResourceVarying v : varyings) if (v.glType() != 0) buffers = Math.max(buffers, v.buffer() + 1);
        for (int b = 0; b < buffers; b++) {
            final Resource r = new Resource();
            r.named = false;
            r.binding = b;
            final List<Integer> active = new ArrayList<>();
            for (int i = 0; i < varyings.size(); i++) {
                final GlTransformFeedback.ResourceVarying v = varyings.get(i);
                if (v.buffer() != b) continue;
                r.feedbackStride = Math.max(r.feedbackStride, v.end());
                if (v.glType() != 0) active.add(i);
            }
            r.active = active.stream().mapToInt(Integer::intValue).toArray();
            out.add(r);
        }
        return out;
    }

    private static List<Resource> subroutines(ProgramBase<?> p, int stage) {
        final List<Resource> out = new ArrayList<>();
        final GlslSubroutines.Declared declared = ProgramInfo.subroutineDeclarations(p.id, STAGE_TYPES[stage]);
        for (int index = 0; index < declared.functionCount(); index++) {
            final Resource r = new Resource();
            r.name = declared.functionAt(index);
            out.add(r);
        }
        return out;
    }

    private static List<Resource> subroutineUniforms(ProgramBase<?> p, int stage) {
        final List<Resource> out = new ArrayList<>();
        final GlslSubroutines.Declared declared = ProgramInfo.subroutineDeclarations(p.id, STAGE_TYPES[stage]);
        for (String name : declared.uniforms()) {
            final Resource r = new Resource();
            r.name = declared.size(name) > 1 ? name + "[0]" : name;
            r.arraySize = declared.size(name);
            r.location = ProgramInfo.subroutineUniformLocation(p.id, STAGE_TYPES[stage], name);
            r.compatible = declared.compatible().getOrDefault(name, List.of()).stream().mapToInt(Integer::intValue).toArray();
            out.add(r);
        }
        return out;
    }

    static void interfaceQuery(int program, int iface, int pname, long params) {
        if (program(program) == null) return; // The program is looked at before the interface, as drivers do.
        if (!knownInterface(iface)) {
            gl().recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        final boolean subroutineUniform = indexIn(SUBROUTINE_UNIFORMS, iface) >= 0;
        final boolean holdsVariables = iface == GL43.GL_UNIFORM_BLOCK || iface == GL42.GL_ATOMIC_COUNTER_BUFFER || iface == GL43.GL_SHADER_STORAGE_BLOCK || iface == GL44.GL_TRANSFORM_FEEDBACK_BUFFER;
        switch (pname) {
            case GL43.GL_ACTIVE_RESOURCES -> { }
            case GL43.GL_MAX_NAME_LENGTH -> {
                if (unnamed(iface)) {
                    gl().recordError(GL11.GL_INVALID_OPERATION);
                    return;
                }
            }
            case GL43.GL_MAX_NUM_ACTIVE_VARIABLES -> {
                if (!holdsVariables) {
                    gl().recordError(GL11.GL_INVALID_OPERATION);
                    return;
                }
            }
            case GL43.GL_MAX_NUM_COMPATIBLE_SUBROUTINES -> {
                if (!subroutineUniform) {
                    gl().recordError(GL11.GL_INVALID_OPERATION);
                    return;
                }
            }
            default -> {
                gl().recordError(GL11.GL_INVALID_ENUM);
                return;
            }
        }
        final ProgramBase<?> p = program(program);
        if (p == null) return;
        final List<Resource> resources = list(p, iface);
        int answer = 0;
        for (Resource r : resources) {
            answer = Math.max(answer, switch (pname) {
                case GL43.GL_MAX_NAME_LENGTH -> r.name.length() + 1;
                case GL43.GL_MAX_NUM_ACTIVE_VARIABLES -> r.active.length;
                case GL43.GL_MAX_NUM_COMPATIBLE_SUBROUTINES -> r.compatible.length;
                default -> 0;
            });
        }
        MemoryUtil.memPutInt(params, pname == GL43.GL_ACTIVE_RESOURCES ? resources.size() : answer);
    }

    /** The name asked for, which may leave off the [0] of an array. */
    private static int find(List<Resource> resources, String name) {
        for (int i = 0; i < resources.size(); i++) {
            final String own = resources.get(i).name;
            if (own.equals(name) || own.equals(name + "[0]")) return i;
        }
        return -1;
    }

    static int index(int program, int iface, String name) {
        if (program(program) == null) return -1;
        if (!knownInterface(iface) || unnamed(iface)) {
            gl().recordError(GL11.GL_INVALID_ENUM);
            return (int) 0xFFFFFFFFL;
        }
        final ProgramBase<?> p = program(program);
        if (p == null) return -1;
        if (iface == GL43.GL_TRANSFORM_FEEDBACK_VARYING && (name.equals("gl_NextBuffer") || name.startsWith("gl_SkipComponents"))) return -1;
        return find(list(p, iface), name);
    }

    static void name(int program, int iface, int index, int bufSize, long length, long out) {
        if (program(program) == null) return;
        if (!knownInterface(iface) || unnamed(iface)) {
            gl().recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        final ProgramBase<?> p = program(program);
        if (p == null) return;
        final List<Resource> resources = list(p, iface);
        if (index < 0 || index >= resources.size() || bufSize < 0) {
            gl().recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        GlEntryHelpers.putName(resources.get(index).name, bufSize, length, out);
    }

    /** A variable's location, or for an element of an array that many past its first. */
    private static int located(List<Resource> resources, String name, boolean wantIndex) {
        final int found = find(resources, name);
        if (found >= 0) return wantIndex ? resources.get(found).locationIndex : resources.get(found).location;
        final int bracket = name.lastIndexOf('[');
        if (bracket <= 0 || !name.endsWith("]")) return -1;
        final int element;
        try {
            element = Integer.parseInt(name.substring(bracket + 1, name.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        final int whole = find(resources, name.substring(0, bracket));
        if (whole < 0 || element < 0 || element >= resources.get(whole).arraySize || resources.get(whole).location < 0) return -1;
        return wantIndex ? resources.get(whole).locationIndex : resources.get(whole).location + element;
    }

    static int location(int program, int iface, String name) {
        if (program(program) == null) return -1;
        if (!hasLocations(iface)) {
            gl().recordError(GL11.GL_INVALID_ENUM);
            return -1;
        }
        final ProgramBase<?> p = program(program);
        if (p == null) return -1;
        if (!p.linked) {
            gl().recordError(GL11.GL_INVALID_OPERATION);
            return -1;
        }
        if (name.startsWith("gl_")) return -1;
        // An element is named by plain digits with nothing around them.
        final int bracket = name.indexOf('[');
        if (bracket >= 0 && !ELEMENT.matcher(name).replaceAll("").matches("[^\\[\\]]*")) return -1;
        if (iface == GL43.GL_UNIFORM) {
            final int found = rb().getUniformLocation(p.id, name);
            if (found >= 0) return found;
        }
        final int subroutineStage = indexIn(SUBROUTINE_UNIFORMS, iface);
        if (subroutineStage >= 0) return ProgramInfo.subroutineUniformLocation(p.id, STAGE_TYPES[subroutineStage], name);
        return located(list(p, iface), name, false);
    }

    static int locationIndex(int program, int iface, String name) {
        if (program(program) == null) return -1;
        if (iface != GL43.GL_PROGRAM_OUTPUT) {
            gl().recordError(GL11.GL_INVALID_ENUM);
            return -1;
        }
        final ProgramBase<?> p = program(program);
        if (p == null) return -1;
        if (!p.linked) {
            gl().recordError(GL11.GL_INVALID_OPERATION);
            return -1;
        }
        if (name.startsWith("gl_")) return -1;
        return located(list(p, iface), name, true);
    }

    private static boolean applies(int iface, int property) {
        final boolean uniform = iface == GL43.GL_UNIFORM, variable = iface == GL43.GL_BUFFER_VARIABLE, input = iface == GL43.GL_PROGRAM_INPUT, output = iface == GL43.GL_PROGRAM_OUTPUT;
        final boolean varying = iface == GL43.GL_TRANSFORM_FEEDBACK_VARYING, feedbackBuffer = iface == GL44.GL_TRANSFORM_FEEDBACK_BUFFER;
        final boolean block = iface == GL43.GL_UNIFORM_BLOCK || iface == GL43.GL_SHADER_STORAGE_BLOCK, atomic = iface == GL42.GL_ATOMIC_COUNTER_BUFFER;
        final boolean subroutineUniform = indexIn(SUBROUTINE_UNIFORMS, iface) >= 0;
        return switch (property) {
            case GL43.GL_NAME_LENGTH -> !unnamed(iface);
            case GL43.GL_TYPE -> uniform || input || output || varying || variable;
            case GL43.GL_ARRAY_SIZE -> uniform || input || output || varying || variable || subroutineUniform;
            case GL43.GL_OFFSET -> uniform || variable || varying;
            case GL43.GL_BLOCK_INDEX, GL43.GL_ARRAY_STRIDE, GL43.GL_MATRIX_STRIDE, GL43.GL_IS_ROW_MAJOR -> uniform || variable;
            case GL43.GL_ATOMIC_COUNTER_BUFFER_INDEX -> uniform;
            case GL43.GL_BUFFER_BINDING -> block || atomic || feedbackBuffer;
            case GL43.GL_BUFFER_DATA_SIZE -> block || atomic;
            case GL43.GL_NUM_ACTIVE_VARIABLES, GL43.GL_ACTIVE_VARIABLES -> block || atomic || feedbackBuffer;
            case GL43.GL_REFERENCED_BY_VERTEX_SHADER, GL43.GL_REFERENCED_BY_TESS_CONTROL_SHADER, GL43.GL_REFERENCED_BY_TESS_EVALUATION_SHADER, GL43.GL_REFERENCED_BY_GEOMETRY_SHADER,
                 GL43.GL_REFERENCED_BY_FRAGMENT_SHADER, GL43.GL_REFERENCED_BY_COMPUTE_SHADER -> uniform || variable || block || atomic || input || output;
            case GL43.GL_TOP_LEVEL_ARRAY_SIZE, GL43.GL_TOP_LEVEL_ARRAY_STRIDE -> variable;
            case GL43.GL_LOCATION -> uniform || input || output || subroutineUniform;
            case GL43.GL_LOCATION_INDEX -> output;
            case GL43.GL_IS_PER_PATCH, GL44.GL_LOCATION_COMPONENT -> input || output;
            case GL44.GL_TRANSFORM_FEEDBACK_BUFFER_INDEX -> varying;
            case GL44.GL_TRANSFORM_FEEDBACK_BUFFER_STRIDE -> feedbackBuffer;
            case GL40.GL_NUM_COMPATIBLE_SUBROUTINES, GL40.GL_COMPATIBLE_SUBROUTINES -> subroutineUniform;
            default -> false;
        };
    }

    private static boolean knownProperty(int property) {
        for (int iface : new int[] {GL43.GL_UNIFORM, GL43.GL_UNIFORM_BLOCK, GL43.GL_PROGRAM_OUTPUT, GL43.GL_BUFFER_VARIABLE, GL43.GL_TRANSFORM_FEEDBACK_VARYING, GL44.GL_TRANSFORM_FEEDBACK_BUFFER, GL43.GL_VERTEX_SUBROUTINE_UNIFORM}) {
            if (applies(iface, property)) return true;
        }
        return false;
    }

    /** A stage that runs as a compute shader has no slot of its own to count references in, so its source says. */
    private static int emulatedReference(ProgramBase<?> p, Resource r, int stage) {
        if (p.geometry == null) return 0;
        final String source = p.stageSources.get(stage);
        if (source == null) return 0;
        final String code = com.gtnewhorizons.angelica.glemu.shader.ProgramsBase.withoutComments(source);
        if (r.name == null || r.name.isEmpty()) {
            // A buffer of atomic counters has no name of its own: it is what its binding says.
            final java.util.regex.Matcher counter = java.util.regex.Pattern.compile("\\bbinding\\s*=\\s*" + r.binding + "\\b[^)]*\\)\\s*uniform\\s+atomic_uint\\s+(\\w+)").matcher(code);
            while (counter.find()) {
                if (uses(code, counter.group(1)) > 1) return 1;
            }
            return 0;
        }
        final int end = r.name.indexOf('[');
        final String bare = (end < 0 ? r.name : r.name.substring(0, end)).replaceAll("^.*\\.", "");
        final java.util.regex.Matcher block = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(bare) + "\\s*\\{([^}]*)\\}\\s*(\\w*)").matcher(code);
        final boolean isBlock = block.find();
        if (isBlock && block.group(2).isEmpty()) {
            // A block with no instance name is used through its members, which are its last words before each semicolon or subscript.
            final java.util.regex.Matcher member = java.util.regex.Pattern.compile("(\\w+)\\s*(?:\\[[^\\]]*\\]\\s*)*;").matcher(block.group(1));
            while (member.find()) {
                if (uses(code, member.group(1)) > 1) return 1;
            }
            return 0;
        }
        final String used = isBlock ? block.group(2) : bare;
        // The first is its declaration.
        return uses(code, used) > 1 ? 1 : 0;
    }

    private static int uses(String code, String name) {
        final java.util.regex.Matcher word = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b").matcher(code);
        int count = 0;
        while (word.find()) count++;
        return count;
    }

    private static int stageReference(ProgramBase<?> p, Resource r, int property) {
        return switch (property) {
            case GL43.GL_REFERENCED_BY_VERTEX_SHADER -> p.compute ? 0 : r.stageSlots >> ProgramBase.VS & 1;
            case GL43.GL_REFERENCED_BY_TESS_CONTROL_SHADER -> p.geometry != null && !r.programInterface ? emulatedReference(p, r, GL40.GL_TESS_CONTROL_SHADER) : r.stageSlots >> ProgramBase.TCS & 1;
            case GL43.GL_REFERENCED_BY_TESS_EVALUATION_SHADER -> p.geometry != null && !r.programInterface ? emulatedReference(p, r, GL40.GL_TESS_EVALUATION_SHADER) : r.stageSlots >> ProgramBase.TES & 1;
            case GL43.GL_REFERENCED_BY_GEOMETRY_SHADER -> p.geometry != null && !r.programInterface ? emulatedReference(p, r, GL32.GL_GEOMETRY_SHADER) : r.stageSlots >> ProgramBase.GS & 1;
            case GL43.GL_REFERENCED_BY_FRAGMENT_SHADER -> r.stageSlots >> ProgramBase.FS & 1;
            default -> p.compute ? r.stageSlots >> ProgramBase.CS & 1 : 0;
        };
    }

    static void query(int program, int iface, int index, int propCount, long props, int bufSize, long length, long params) {
        if (program(program) == null) return;
        if (propCount <= 0 || bufSize < 0) {
            gl().recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (!knownInterface(iface)) {
            gl().recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        final ProgramBase<?> p = program(program);
        if (p == null) return;
        for (int n = 0; n < propCount; n++) {
            final int property = MemoryUtil.memGetInt(props + n * 4L);
            if (!knownProperty(property)) {
                gl().recordError(GL11.GL_INVALID_ENUM);
                return;
            }
            if (!applies(iface, property)) {
                gl().recordError(GL11.GL_INVALID_OPERATION);
                return;
            }
        }
        final List<Resource> resources = list(p, iface);
        if (index < 0 || index >= resources.size()) {
            gl().recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        final Resource r = resources.get(index);
        int written = 0;
        for (int n = 0; n < propCount && written < bufSize; n++) {
            final int property = MemoryUtil.memGetInt(props + n * 4L);
            if (property == GL43.GL_ACTIVE_VARIABLES || property == GL40.GL_COMPATIBLE_SUBROUTINES) {
                final int[] values = property == GL43.GL_ACTIVE_VARIABLES ? r.active : r.compatible;
                for (int value : values) {
                    if (written == bufSize) break;
                    MemoryUtil.memPutInt(params + written++ * 4L, value);
                }
                continue;
            }
            MemoryUtil.memPutInt(params + written++ * 4L, switch (property) {
                case GL43.GL_NAME_LENGTH -> r.name.length() + 1;
                case GL43.GL_TYPE -> r.type;
                case GL43.GL_ARRAY_SIZE -> r.arraySize;
                case GL43.GL_OFFSET -> r.offset;
                case GL43.GL_BLOCK_INDEX -> r.blockIndex;
                case GL43.GL_ARRAY_STRIDE -> r.arrayStride;
                case GL43.GL_MATRIX_STRIDE -> r.matrixStride;
                case GL43.GL_IS_ROW_MAJOR -> r.rowMajor;
                case GL43.GL_ATOMIC_COUNTER_BUFFER_INDEX -> r.atomicBuffer;
                case GL43.GL_BUFFER_BINDING -> r.binding;
                case GL43.GL_BUFFER_DATA_SIZE -> r.dataSize;
                case GL43.GL_NUM_ACTIVE_VARIABLES -> r.active.length;
                case GL43.GL_TOP_LEVEL_ARRAY_SIZE -> r.topLevelSize;
                case GL43.GL_TOP_LEVEL_ARRAY_STRIDE -> r.topLevelStride;
                case GL43.GL_LOCATION -> r.location;
                case GL43.GL_LOCATION_INDEX -> r.locationIndex;
                case GL43.GL_IS_PER_PATCH -> r.perPatch;
                case GL44.GL_LOCATION_COMPONENT -> r.component;
                case GL44.GL_TRANSFORM_FEEDBACK_BUFFER_INDEX -> r.feedbackBuffer;
                case GL44.GL_TRANSFORM_FEEDBACK_BUFFER_STRIDE -> r.feedbackStride;
                case GL40.GL_NUM_COMPATIBLE_SUBROUTINES -> r.compatible.length;
                default -> stageReference(p, r, property);
            });
        }
        if (length != 0L) MemoryUtil.memPutInt(length, written);
    }

    /** glGetActiveAtomicCounterBufferiv says the same as the ATOMIC_COUNTER_BUFFER interface. */
    static void atomicBuffer(int program, int index, int pname, long params) {
        final ProgramBase<?> p = program(program);
        if (p == null) return;
        final List<Resource> resources = list(p, GL42.GL_ATOMIC_COUNTER_BUFFER);
        if (index < 0 || index >= resources.size()) {
            gl().recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        final Resource r = resources.get(index);
        if (pname == GL42.GL_ATOMIC_COUNTER_BUFFER_ACTIVE_ATOMIC_COUNTER_INDICES) {
            for (int i = 0; i < r.active.length; i++) MemoryUtil.memPutInt(params + i * 4L, r.active[i]);
            return;
        }
        final int property = switch (pname) {
            case GL42.GL_ATOMIC_COUNTER_BUFFER_REFERENCED_BY_VERTEX_SHADER -> GL43.GL_REFERENCED_BY_VERTEX_SHADER;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_REFERENCED_BY_TESS_CONTROL_SHADER -> GL43.GL_REFERENCED_BY_TESS_CONTROL_SHADER;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_REFERENCED_BY_TESS_EVALUATION_SHADER -> GL43.GL_REFERENCED_BY_TESS_EVALUATION_SHADER;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_REFERENCED_BY_GEOMETRY_SHADER -> GL43.GL_REFERENCED_BY_GEOMETRY_SHADER;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_REFERENCED_BY_FRAGMENT_SHADER -> GL43.GL_REFERENCED_BY_FRAGMENT_SHADER;
            case GL43.GL_ATOMIC_COUNTER_BUFFER_REFERENCED_BY_COMPUTE_SHADER -> GL43.GL_REFERENCED_BY_COMPUTE_SHADER;
            default -> 0;
        };
        MemoryUtil.memPutInt(params, switch (pname) {
            case GL42.GL_ATOMIC_COUNTER_BUFFER_BINDING -> r.binding;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_DATA_SIZE -> r.dataSize;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_ACTIVE_ATOMIC_COUNTERS -> r.active.length;
            default -> property == 0 ? 0 : stageReference(p, r, property);
        });
    }

    static int atomicBuffers(int program) {
        final ProgramBase<?> p = gl().programBase(program);
        return p == null ? 0 : atomicStorage(p).size();
    }
}
