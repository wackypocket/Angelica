package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.shader.GlslSubroutines;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.gl;
import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/** Answers program introspection queries from what linking reflected. */
public final class ProgramInfo {

    static GlslSubroutines.Declared subroutineDeclarations(int program, int stage) {
        return subroutines(program, stage);
    }

    private static GlslSubroutines.Declared subroutines(int program, int stage) {
        final ProgramBase<?> p = gl().programBase(program);
        final String source = p == null ? null : p.stageSources.get(stage);
        return source != null ? GlslSubroutines.declared(source) : GlslSubroutines.Declared.none();
    }

    public static int subroutineCount(int program, int stage) {
        return subroutines(program, stage).functions().size();
    }

    public static int subroutineLocationCount(int program, int stage) {
        return subroutines(program, stage).locations();
    }

    public static int subroutineUniformCount(int program, int stage) {
        return subroutines(program, stage).uniforms().size();
    }

    static int subroutineIndex(int program, int stage, String name) {
        final int index = subroutines(program, stage).functionIndex(name);
        return index < 0 ? GL31.GL_INVALID_INDEX : index;
    }

    static int subroutineUniformLocation(int program, int stage, String name) {
        final GlslSubroutines.Declared declared = subroutines(program, stage);
        final int bracket = name.indexOf('[');
        if (bracket < 0 || !name.endsWith("]")) return declared.location(name);
        final String bare = name.substring(0, bracket);
        final int first = declared.location(bare);
        if (first < 0) return -1;
        final int element = declared.element(bare, name.substring(bracket));
        return element < 0 ? -1 : first + element;
    }

    static String subroutineName(int program, int stage, int index) {
        return subroutines(program, stage).functionAt(index);
    }

    static String subroutineUniformName(int program, int stage, int index) {
        final List<String> uniforms = subroutines(program, stage).uniforms();
        return index >= 0 && index < uniforms.size() ? uniforms.get(index) : "";
    }

    static void subroutineUniformQuery(int program, int stage, int index, int pname, long out) {
        final List<Integer> compatible = subroutines(program, stage).compatible().getOrDefault(subroutineUniformName(program, stage, index), List.of());
        switch (pname) {
            case GL40.GL_NUM_COMPATIBLE_SUBROUTINES -> MemoryUtil.memPutInt(out, compatible.size());
            case GL40.GL_COMPATIBLE_SUBROUTINES -> {
                for (int i = 0; i < compatible.size(); i++) MemoryUtil.memPutInt(out + i * 4L, compatible.get(i));
            }
            case GL31.GL_UNIFORM_SIZE -> MemoryUtil.memPutInt(out, subroutines(program, stage).size(subroutineUniformName(program, stage, index)));
            case GL31.GL_UNIFORM_NAME_LENGTH -> MemoryUtil.memPutInt(out, subroutineUniformName(program, stage, index).length() + 1);
            default -> { }
        }
    }

    public static int programStage(int program, int stage, int pname) {
        final GlslSubroutines.Declared declared = subroutines(program, stage);
        return switch (pname) {
            case GL40.GL_ACTIVE_SUBROUTINES -> declared.functionCount();
            case GL40.GL_ACTIVE_SUBROUTINE_UNIFORMS -> declared.uniforms().size();
            case GL40.GL_ACTIVE_SUBROUTINE_UNIFORM_LOCATIONS -> declared.locations();
            case GL40.GL_ACTIVE_SUBROUTINE_MAX_LENGTH -> declared.functions().stream().mapToInt(n -> n.length() + 1).max().orElse(0);
            case GL40.GL_ACTIVE_SUBROUTINE_UNIFORM_MAX_LENGTH -> declared.uniforms().stream().mapToInt(n -> n.length() + 1).max().orElse(0);
            default -> 0;
        };
    }

    /** The program a stage's subroutine calls act on: the one in use, or what a bound pipeline runs for that stage. */
    public static int subroutineProgram(int stage) {
        if (gl().pipelineProgramIsCurrent()) return GlPipelines.stageProgram(stage);
        final int current = com.gtnewhorizons.angelica.glsm.GLSMPrograms.getActiveProgram();
        return current != 0 ? current : GlPipelines.stageProgram(stage);
    }

    public static boolean hasStage(int program, int stage) {
        final ProgramBase<?> p = gl().programBase(program);
        return p != null && p.linked && p.stageSources.containsKey(stage);
    }

    /** Whether the subroutine with this index can go in this location. */
    public static boolean subroutineFits(int program, int stage, int location, int index) {
        final GlslSubroutines.Declared declared = subroutines(program, stage);
        for (String name : declared.uniforms()) {
            final int first = declared.location(name);
            if (location >= first && location < first + declared.size(name)) return declared.compatible().getOrDefault(name, List.of()).contains(index);
        }
        return true;
    }

    /** Each lowered subroutine uniform is an ordinary int in the program, which holds what the query answers even for one the compiler dropped. */
    static void setSubroutines(int stage, int count, long indices) {
        final int program = subroutineProgram(stage);
        final GlslSubroutines.Declared declared = subroutines(program, stage);
        final ProgramBase<?> p = gl().programBase(program);
        if (p == null) return;
        final int[] values = subroutineValues(p, stage, declared);
        for (int at = 0; at < count && at < values.length; at++) values[at] = MemoryUtil.memGetInt(indices + at * 4L);
        writeSubroutines(program, stage, declared, values);
    }

    static int subroutineAt(int stage, int location) {
        final int program = subroutineProgram(stage);
        final ProgramBase<?> p = gl().programBase(program);
        if (p == null) return 0;
        final int[] values = subroutineValues(p, stage, subroutines(program, stage));
        return location < values.length ? values[location] : 0;
    }

    private static int[] subroutineValues(ProgramBase<?> p, int stage, GlslSubroutines.Declared declared) {
        if (p.subroutineValues == null) p.subroutineValues = new java.util.HashMap<>();
        int[] values = p.subroutineValues.get(stage);
        if (values == null) {
            values = defaults(declared);
            p.subroutineValues.put(stage, values);
        }
        return values;
    }

    private static int[] defaults(GlslSubroutines.Declared declared) {
        final int[] values = new int[declared.locations()];
        for (String name : declared.uniforms()) {
            final List<Integer> compatible = declared.compatible().getOrDefault(name, List.of());
            for (int element = 0; element < declared.size(name); element++) {
                values[declared.location(name) + element] = compatible.isEmpty() ? 0 : compatible.get(0);
            }
        }
        return values;
    }

    private static void writeSubroutines(int program, int stage, GlslSubroutines.Declared declared, int[] values) {
        final ProgramBase<?> p = gl().programBase(program);
        for (String name : declared.uniforms()) {
            for (int element = 0; element < declared.size(name); element++) {
                final String uniform = GlslSubroutines.elementName(stage, declared, name, element);
                final int value = values[declared.location(name) + element];
                gl().stageUniform(program, uniform, value);
                // A stage that runs as a compute shader reads them there.
                if (p != null && p.geometry != null) {
                    if (p.geometry.control != null) gl().stageUniform(p.geometry.control.id, uniform, value);
                    if (p.geometry.evaluation != null) gl().stageUniform(p.geometry.evaluation.id, uniform, value);
                    if (p.geometry.compute != null) gl().stageUniform(p.geometry.compute.id, uniform, value);
                }
            }
        }
    }

    /** Copies a pipeline stage program's subroutine values onto the program the pipeline draws through. */
    public static void applySubroutines(ProgramBase<?> from, int drawing) {
        if (!from.usesSubroutines || from.subroutineValues == null) return;
        for (java.util.Map.Entry<Integer, int[]> stage : from.subroutineValues.entrySet()) {
            final String source = from.stageSources.get(stage.getKey());
            if (source != null) writeSubroutines(drawing, stage.getKey(), GlslSubroutines.declared(source), stage.getValue());
        }
    }

    /** What GL does whenever a program starts being used for a stage: its subroutine uniforms go back to valid values. */
    public static void resetSubroutines(int program, int stage) {
        final ProgramBase<?> p = gl().programBase(program);
        if (p == null || !p.linked) return;
        final String source = p.stageSources.get(stage);
        if (source == null || !GlslSubroutines.uses(source)) return;
        final GlslSubroutines.Declared declared = GlslSubroutines.declared(source);
        final int[] values = defaults(declared);
        if (p.subroutineValues == null) p.subroutineValues = new java.util.HashMap<>();
        p.subroutineValues.put(stage, values);
        writeSubroutines(program, stage, declared, values);
    }

    public static void resetSubroutines(int program) {
        final ProgramBase<?> p = gl().programBase(program);
        if (p == null || !p.linked) return;
        for (int stage : p.stageSources.keySet().toArray(new Integer[0])) resetSubroutines(program, stage);
    }

    private static final int UNIFORM = 0x92E1, UNIFORM_BLOCK = 0x92E2, PROGRAM_INPUT = 0x92E3, SHADER_STORAGE_BLOCK = 0x92E6;

    private ProgramInfo() {}

    private static int firstStage(ProgramBase.Uniform u) {
        int stage = 0;
        while (stage < u.offset.length - 1 && u.offset[stage] < 0) stage++;
        return stage;
    }

    /** A binding from glBindFragDataLocation, or else what the fragment shader's source declares for the name. */
    static void bindFragDataIndex(int program, String name, int index) {
        final ProgramBase<?> p = gl().programBase(program);
        if (p != null) p.fragDataIndices.put(name, index);
    }

    static int fragDataLocation(int program, String name) {
        final ProgramBase<?> p = gl().programBase(program);
        if (p == null) return -1;
        final int bound = p.fragDataBindings.getInt(name);
        if (bound >= 0) return bound;
        for (Shader shader : p.linkedShaders) {
            if (shader.type != GL20.GL_FRAGMENT_SHADER) continue;
            final Matcher output = Pattern.compile("(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?\\bout\\s+\\w+\\s+\\w+\\s+" + Pattern.quote(name) + "\\b|(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?\\bout\\s+\\w+\\s+" + Pattern.quote(name) + "\\b").matcher(shader.source);
            if (!output.find()) continue;
            final String location = output.group(1) != null ? output.group(1) : output.group(2);
            return location == null ? 0 : Integer.parseInt(location);
        }
        return -1;
    }

    private static ProgramBase<?> program(int id) {
        return gl().programBase(id);
    }

    static String uniformName(int programId, int index) {
        final ProgramBase<?> p = program(programId);
        return p == null || index < 0 || index >= p.activeUniformCount() ? "" : ProgramsBase.activeName(p.activeUniform(index));
    }

    static int uniformIndex(int programId, String name) {
        final ProgramBase<?> p = program(programId);
        if (p == null) return GL31.GL_INVALID_INDEX;
        for (int i = 0; i < p.activeUniformCount(); i++) {
            final ProgramBase.Uniform u = p.activeUniform(i);
            if (u.name.equals(name) || ProgramsBase.activeName(u).equals(name)) return i;
        }
        return GL31.GL_INVALID_INDEX;
    }

    static int uniform(int programId, int index, int pname) {
        final ProgramBase<?> p = program(programId);
        if (p == null || index < 0 || index >= p.activeUniformCount()) return 0;
        final ProgramBase.Uniform u = p.activeUniform(index);
        final boolean inBlock = u.blockIndex >= 0;
        return switch (pname) {
            case GL31.GL_UNIFORM_TYPE -> u.declaredDouble != 0 ? u.declaredDouble : u.glType;
            case GL31.GL_UNIFORM_SIZE -> Math.max(1, u.arraySize);
            case GL31.GL_UNIFORM_NAME_LENGTH -> ProgramsBase.activeName(u).length() + 1;
            case GL31.GL_UNIFORM_BLOCK_INDEX -> u.blockIndex;
            case GL31.GL_UNIFORM_OFFSET -> inBlock || u.atomicBuffer >= 0 ? u.offset[0] : -1;
            case GL31.GL_UNIFORM_ARRAY_STRIDE -> inBlock || u.atomicBuffer >= 0 ? u.arrayStride[0] : -1;
            case GL31.GL_UNIFORM_MATRIX_STRIDE -> inBlock ? u.matrixStride[0] : u.atomicBuffer >= 0 ? 0 : -1;
            case GL42.GL_UNIFORM_ATOMIC_COUNTER_BUFFER_INDEX -> u.atomicBuffer;
            case GL31.GL_UNIFORM_IS_ROW_MAJOR -> inBlock && u.rowMajor[0] ? 1 : 0;
            default -> 0;
        };
    }

    static String blockName(int programId, int index) {
        final ProgramBase<?> p = program(programId);
        return p == null || index < 0 || index >= p.ubos.size() ? "" : (p.ubos.get(index)).name;
    }

    static int block(int programId, int index, int pname) {
        final ProgramBase<?> p = program(programId);
        if (p == null || index < 0 || index >= p.ubos.size()) return 0;
        final ProgramBase.BufferBlock b = p.ubos.get(index);
        return switch (pname) {
            case GL31.GL_UNIFORM_BLOCK_BINDING -> b.glBinding;
            case GL31.GL_UNIFORM_BLOCK_DATA_SIZE -> b.size;
            case GL31.GL_UNIFORM_BLOCK_NAME_LENGTH -> b.name.length() + 1;
            case GL31.GL_UNIFORM_BLOCK_ACTIVE_UNIFORMS -> blockMembers(p, index).length;
            case GL31.GL_UNIFORM_BLOCK_REFERENCED_BY_VERTEX_SHADER -> b.stageSlots >> ProgramBase.VS & (p.compute ? 0 : 1);
            case GL40.GL_UNIFORM_BLOCK_REFERENCED_BY_TESS_CONTROL_SHADER -> b.stageSlots >> ProgramBase.TCS & 1;
            case GL40.GL_UNIFORM_BLOCK_REFERENCED_BY_TESS_EVALUATION_SHADER -> b.stageSlots >> ProgramBase.TES & 1;
            case GL32.GL_UNIFORM_BLOCK_REFERENCED_BY_GEOMETRY_SHADER -> b.stageSlots >> ProgramBase.GS & 1;
            case GL31.GL_UNIFORM_BLOCK_REFERENCED_BY_FRAGMENT_SHADER -> b.stageSlots >> ProgramBase.FS & 1;
            case GL43.GL_UNIFORM_BLOCK_REFERENCED_BY_COMPUTE_SHADER -> p.compute ? b.stageSlots & 1 : 0;
            default -> 0;
        };
    }

    private static int[] blockMembers(ProgramBase<?> p, int block) {
        final int first = p.activeUniforms().size();
        final int[] found = new int[p.blockUniforms.size()];
        int count = 0;
        for (int i = 0; i < p.blockUniforms.size(); i++) {
            final ProgramBase.Uniform u = p.blockUniforms.get(i);
            if (block >= u.blockIndex && block < u.blockIndex + u.blockCount) found[count++] = first + i;
        }
        return java.util.Arrays.copyOf(found, count);
    }

    static void blockQuery(int programId, int index, int pname, long out) {
        if (pname != GL31.GL_UNIFORM_BLOCK_ACTIVE_UNIFORM_INDICES) {
            MemoryUtil.memPutInt(out, block(programId, index, pname));
            return;
        }
        final ProgramBase<?> p = program(programId);
        if (p == null || index < 0 || index >= p.ubos.size()) return;
        final int[] members = blockMembers(p, index);
        for (int i = 0; i < members.length; i++) MemoryUtil.memPutInt(out + i * 4L, members[i]);
    }
}
