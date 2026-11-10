package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL43;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.gl;
import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/**
 * Program pipelines. Modern APIs link all stages together, so a bound pipeline draws through one program linked from its stage programs'
 * sources; the stage programs keep the uniforms the API addresses, and {@code GlBase} copies them across before each draw.
 */
public final class GlPipelines {

    private static final int[] STAGE_BITS = {GL41.GL_VERTEX_SHADER_BIT, GL41.GL_FRAGMENT_SHADER_BIT, GL41.GL_GEOMETRY_SHADER_BIT, GL41.GL_TESS_CONTROL_SHADER_BIT, GL41.GL_TESS_EVALUATION_SHADER_BIT, GL43.GL_COMPUTE_SHADER_BIT};
    private static final int[] STAGE_TYPES = {GL20.GL_VERTEX_SHADER, GL20.GL_FRAGMENT_SHADER, GL32.GL_GEOMETRY_SHADER, GL40.GL_TESS_CONTROL_SHADER, GL40.GL_TESS_EVALUATION_SHADER, GL43.GL_COMPUTE_SHADER};
    private static final int COMPUTE = STAGE_TYPES.length - 1;

    private static final class Pipeline {
        final int[] stages = new int[STAGE_BITS.length];
        int active;
        boolean validated;
    }

    private static final Int2ObjectOpenHashMap<Pipeline> PIPELINES = new Int2ObjectOpenHashMap<>();
    /** One program per combination of stages, replaced when a stage is relinked. */
    private record Linked(String generations, int program) {}

    private static final Map<String, Linked> LINKED = new HashMap<>();
    private static int nextName = 1;

    /** What GL keeps per context. Like GlState, each thread has its own. */
    private static final class State {
        int bound;
        int separateProgramsPipeline;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GlPipelines() {}

    static int create() {
        final int name = nextName++;
        PIPELINES.put(name, new Pipeline());
        return name;
    }

    /** glGenProgramPipelines only reserves a name, which becomes an object once it is first used. */
    static int reserve() {
        final int name = nextName++;
        RESERVED.add(name);
        return name;
    }

    public static boolean known(int name) {
        return PIPELINES.containsKey(name) || RESERVED.contains(name);
    }

    static void delete(int name) {
        final State state = STATE.get();
        final Pipeline gone = PIPELINES.remove(name);
        if (gone == null) return;
        if (state.bound == name) bind(0);
        for (int program : gone.stages) gl().releaseProgram(program);
    }

    public static boolean usesProgram(int program) {
        for (Pipeline pipeline : PIPELINES.values()) {
            for (int stage : pipeline.stages) {
                if (stage == program) return true;
            }
        }
        return false;
    }

    public static boolean exists(int name) {
        return PIPELINES.containsKey(name);
    }

    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet RESERVED = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    private static Pipeline get(int name) {
        RESERVED.remove(name);
        Pipeline pipeline = PIPELINES.get(name);
        if (pipeline == null && name != 0) {
            pipeline = new Pipeline();
            PIPELINES.put(name, pipeline);
            nextName = Math.max(nextName, name + 1);
        }
        return pipeline;
    }

    static void useStages(int name, int stages, int program) {
        final State state = STATE.get();
        final Pipeline pipeline = get(name);
        if (pipeline == null) return;
        for (int i = 0; i < STAGE_BITS.length; i++) {
            if ((stages & STAGE_BITS[i]) == 0) continue;
            final int was = pipeline.stages[i];
            pipeline.stages[i] = program;
            if (was != program) gl().releaseProgram(was);
            if (program != 0) ProgramInfo.resetSubroutines(program, STAGE_TYPES[i]);
        }
        if (state.bound == name && canTakeOver()) activate(pipeline);
    }

    private static boolean canTakeOver() {
        return GLStateManager.ctx().activeProgram == 0 || gl().pipelineProgramIsCurrent();
    }

    /** The bound pipeline, which draws whenever no program is current through glUseProgram. */
    public static int boundPipeline() {
        return STATE.get().bound;
    }

    public static boolean boundHasCompute() {
        final Pipeline pipeline = PIPELINES.get(STATE.get().bound);
        return pipeline != null && pipeline.stages[COMPUTE] != 0;
    }

    public static boolean boundStagesFit() {
        final Pipeline pipeline = PIPELINES.get(STATE.get().bound);
        return pipeline == null || stagesFit(pipeline);
    }

    public static boolean lacksVertexStage() {
        final int bound = STATE.get().bound;
        if (bound == 0) return false;
        final Pipeline pipeline = PIPELINES.get(bound);
        if (pipeline == null || pipeline.stages[0] != 0) return false;
        for (int i = 1; i < COMPUTE; i++) {
            if (pipeline.stages[i] != 0 && STAGE_TYPES[i] != GL20.GL_FRAGMENT_SHADER) return true;
        }
        return false;
    }

    /** The program the bound pipeline dispatches through, which draws never involve. */
    public static int boundComputeProgram() {
        final Pipeline pipeline = PIPELINES.get(STATE.get().bound);
        return pipeline == null ? 0 : pipeline.stages[COMPUTE];
    }

    /** What the bound pipeline runs for a stage, by shader type. */
    static int stageProgram(int type) {
        final Pipeline pipeline = PIPELINES.get(STATE.get().bound);
        if (pipeline == null) return 0;
        for (int i = 0; i < STAGE_TYPES.length; i++) {
            if (STAGE_TYPES[i] == type) return pipeline.stages[i];
        }
        return 0;
    }

    public static void programReleased() {
        final State state = STATE.get();
        final Pipeline pipeline = state.bound == 0 ? null : PIPELINES.get(state.bound);
        if (pipeline != null) activate(pipeline);
    }

    static void activeProgram(int name, int program) {
        final State state = STATE.get();
        final Pipeline pipeline = get(name);
        if (pipeline == null) return;
        pipeline.active = program;
        if (state.bound == name) gl().setPipelineUniformProgram(program);
    }

    /** A program made current with glUseProgram outranks any pipeline, and the pipeline takes over once that program is gone. */
    static void bind(int name) {
        final State state = STATE.get();
        final Pipeline pipeline = get(name);
        final boolean wasPipelineProgram = state.bound != 0 && gl().pipelineProgramIsCurrent();
        state.bound = name;
        if (pipeline != null) {
            for (int i = 0; i < STAGE_TYPES.length; i++) {
                if (pipeline.stages[i] != 0) ProgramInfo.resetSubroutines(pipeline.stages[i], STAGE_TYPES[i]);
            }
            if (GLStateManager.ctx().activeProgram == 0 || wasPipelineProgram) activate(pipeline);
        } else {
            gl().clearPipeline();
            if (wasPipelineProgram) GLSMPrograms.glUseProgram(0);
        }
    }

    private static void activate(Pipeline pipeline) {
        final int program = linked(pipeline);
        // Stages that cannot draw yet are legal in a pipeline. Drawing without them is what fails, as with no program at all.
        if (program == 0 || rb().getProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            final boolean current = gl().pipelineProgramIsCurrent();
            gl().setPipeline(0, pipeline.active, null);
            if (current) GLSMPrograms.glUseProgram(0);
            return;
        }
        final int[] drawing = pipeline.stages.clone();
        drawing[COMPUTE] = 0;
        gl().setPipeline(program, pipeline.active, drawing);
        GLSMPrograms.glUseProgram(program);
    }

    /** The order stages run in, as indices of STAGE_TYPES. */
    private static final int[] STAGE_ORDER = {0, 3, 4, 2, 1};

    /** GL's rules for a pipeline: a program runs all the stages it was linked with, and no program's stages have another's between them. */
    private static boolean stagesFit(Pipeline pipeline) {
        for (int i = 0; i < COMPUTE; i++) {
            final ProgramBase<?> program = gl().programBase(pipeline.stages[i]);
            if (program == null) continue;
            if (!program.linkedSeparable) return false;
            for (int j = 0; j < COMPUTE; j++) {
                if (pipeline.stages[j] != pipeline.stages[i] && program.stageSources.containsKey(STAGE_TYPES[j])) return false;
            }
        }
        for (int from = 0; from < STAGE_ORDER.length; from++) {
            final int program = pipeline.stages[STAGE_ORDER[from]];
            if (program == 0) continue;
            for (int to = from + 2; to < STAGE_ORDER.length; to++) {
                if (pipeline.stages[STAGE_ORDER[to]] != program) continue;
                for (int between = from + 1; between < to; between++) {
                    if (pipeline.stages[STAGE_ORDER[between]] != program && pipeline.stages[STAGE_ORDER[between]] != 0) return false;
                }
            }
        }
        return true;
    }

    private static int linked(Pipeline pipeline) {
        boolean empty = true;
        for (int i = 0; i < COMPUTE; i++) empty &= pipeline.stages[i] == 0;
        if (empty || !stagesFit(pipeline)) return 0;
        final StringBuilder stages = new StringBuilder(), generations = new StringBuilder();
        for (int i = 0; i < COMPUTE; i++) {
            final int stage = pipeline.stages[i];
            final ProgramBase<?> program = gl().programBase(stage);
            stages.append(stage).append(',');
            generations.append(program == null ? 0 : program.gen).append(',');
        }
        final String key = stages.toString();
        final Linked known = LINKED.get(key);
        if (known != null && known.generations.contentEquals(generations)) return known.program;
        if (known != null) GLSMPrograms.glDeleteProgram(known.program);
        final int program = link(pipeline);
        LINKED.put(key, new Linked(generations.toString(), program));
        return program;
    }

    private static int link(Pipeline pipeline) {
        final int program = GLSMPrograms.glCreateProgram();
        gl().programBase(program).pipelineComposite = true;
        for (int i = 0; i < COMPUTE; i++) {
            final ProgramBase<?> stage = gl().programBase(pipeline.stages[i]);
            if (stage == null) continue;
            final String source = stage.stageSources.get(STAGE_TYPES[i]);
            if (source != null) GlEntryHelpers.attachCompiled(program, STAGE_TYPES[i], source);
        }
        final int last = pipeline.stages[2] != 0 ? pipeline.stages[2] : pipeline.stages[4] != 0 ? pipeline.stages[4] : pipeline.stages[0];
        GlTransformFeedback.inherit(program, last);
        GLSMPrograms.glLinkProgram(program);
        return program;
    }

    /** EXT_separate_shader_objects sets stages on the context itself, which here is one hidden pipeline. */
    static void useShaderProgram(int type, int program) {
        final State state = STATE.get();
        if (state.separateProgramsPipeline == 0) state.separateProgramsPipeline = create();
        for (int i = 0; i < STAGE_TYPES.length; i++) {
            if (STAGE_TYPES[i] == type) useStages(state.separateProgramsPipeline, STAGE_BITS[i], program);
        }
        if (state.bound != state.separateProgramsPipeline) bind(state.separateProgramsPipeline);
    }

    static void activeProgram(int program) {
        final State state = STATE.get();
        if (state.separateProgramsPipeline != 0) activeProgram(state.separateProgramsPipeline, program);
    }

    static int createShaderProgram(int type, String source) {
        final int program = GLSMPrograms.glCreateProgram();
        rb().programParameteri(program, GL41.GL_PROGRAM_SEPARABLE, 1);
        GlEntryHelpers.attachCompiled(program, type, source);
        GLSMPrograms.glLinkProgram(program);
        // glCreateShaderProgramv leaves no shader attached, so a later link starts over.
        final it.unimi.dsi.fastutil.ints.IntArrayList attached = gl().programBase(program).attached;
        for (int i = attached.size() - 1; i >= 0; i--) GLSMPrograms.glDetachShader(program, attached.getInt(i));
        return program;
    }

    static void validate(int name) {
        final Pipeline pipeline = get(name);
        if (pipeline == null) return;
        final int drawing = linked(pipeline), compute = pipeline.stages[COMPUTE];
        pipeline.validated = (drawing != 0 || compute != 0)
            && (drawing == 0 || rb().getProgrami(drawing, GL20.GL_LINK_STATUS) != 0)
            && (compute == 0 || rb().getProgrami(compute, GL20.GL_LINK_STATUS) != 0);
    }

    static int query(int name, int pname) {
        final Pipeline pipeline = get(name);
        if (pipeline == null) return 0;
        if (pname == GL41.GL_ACTIVE_PROGRAM) return pipeline.active;
        if (pname == GL20.GL_VALIDATE_STATUS) return pipeline.validated ? 1 : 0;
        if (pname == GL20.GL_INFO_LOG_LENGTH) {
            final String log = infoLog(name);
            return log.isEmpty() ? 0 : log.length() + 1;
        }
        for (int i = 0; i < STAGE_TYPES.length; i++) {
            if (STAGE_TYPES[i] == pname) return pipeline.stages[i];
        }
        return 0;
    }

    static String infoLog(int name) {
        final Pipeline pipeline = get(name);
        return pipeline == null || linked(pipeline) == 0 ? "" : rb().getProgramInfoLog(linked(pipeline), 4096);
    }
}
