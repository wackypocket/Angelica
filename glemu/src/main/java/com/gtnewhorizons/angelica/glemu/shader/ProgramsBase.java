package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glsm.CompatShaderTransformer;
import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;
import org.lwjgl.system.MemoryUtil;

public abstract class ProgramsBase<P extends ProgramBase<P>> {
    private static final java.util.regex.Pattern FLAT = java.util.regex.Pattern.compile("\\bflat\\b");

    private Logger logger;

    private Logger log() {
        if (logger == null) logger = LogManager.getLogger("Angelica/" + apiLabel());
        return logger;
    }

    private final Int2ObjectOpenHashMap<Shader> shaders = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<P> programs = new Int2ObjectOpenHashMap<>();
    private int nextId = 1;
    private int nextVariantId = -1;
    private P cached;
    private GlState cachedFor;
    private int cachedId = -1;

    protected abstract String apiName();

    protected abstract String apiLabel();

    protected abstract P newProgram(int id);

    protected abstract boolean linkProgram(P p, List<Shader> shaders);

    protected abstract Object detachNativeObjects(P p);

    protected abstract void linkDone(P p, Object detached, boolean linked);

    /** Releases what a relink replaces while older submissions may still use it; backends that free through the GPU timeline need nothing more. */
    protected void retireNativeObjects(P p) {
        destroyNativeObjects(p);
    }

    protected abstract void destroyNativeObjects(P p);

    protected abstract boolean anyStateUsesProgram(int id);

    protected abstract boolean nativeCullDistance();

    protected abstract boolean nativeGeometry();

    protected abstract int atomicLimit(int pname);

    /** The images a stage can use, of which the stage's limit counts those it both reads and writes. */
    protected abstract int imagesPerStage();

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

    /**
     * Relinks a linked program something is drawing with into a new object under its name. GL keeps a program's last executable drawing
     * where it is current when a relink fails, while the program reports the failure; linking in place would have lost that executable.
     */
    public P relinkInUse(P old) {
        final P fresh = newProgram(old.id);
        fresh.attached.addAll(old.attached);
        fresh.attribBindings.putAll(old.attribBindings);
        fresh.fragDataBindings.putAll(old.fragDataBindings);
        fresh.fragDataIndices.putAll(old.fragDataIndices);
        fresh.separable = old.separable;
        fresh.pipelineComposite = old.pipelineComposite;
        fresh.label = old.label;
        fresh.deletePending = old.deletePending;
        // Pipelines are kept by name and generation, so the generations go on from the old object's.
        fresh.gen = old.gen;
        programs.put(old.id, fresh);
        if (cachedId == old.id) cachedId = -1;
        link(fresh);
        return fresh;
    }

    /** Lets go of an executable nothing draws with any more. */
    public void retireExecutable(P old) {
        retireGeometry(old);
        retireNativeObjects(old);
        old.resetLinkResults();
    }

    public boolean isShader(int id) {
        return shaders.containsKey(id);
    }

    public boolean isProgram(int id) {
        return programs.containsKey(id);
    }

    @SuppressWarnings("unchecked")
    public P current(GlState st) {
        if (st.heldExecutable != null && st.heldExecutable.id == st.program) return (P) st.heldExecutable;
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
        s.compiled = r.compiled() != null || ShaderCompiler.failsOnlyAtLink(r.log());
        s.log = r.log() == null ? "" : r.log();
        if (!s.compiled) {
            log().warn("Shader {} failed to compile for {}: {}", s.id, apiName(), s.log);
        }
    }

    /**
     * What a query says of a uniform's type is what the source declared. Reflection sees doubles as they are; a cube image that runs as an array
     * is named here from the source.
     */
    private void noteDeclaredDoubles(P p, List<Shader> shaders) {
        for (ProgramBase.Uniform u : p.uniforms) if (isDoubleType(u.glType)) u.declaredDouble = u.glType;
        for (ProgramBase.Uniform u : p.blockUniforms) if (isDoubleType(u.glType)) u.declaredDouble = u.glType;
        if (!ShaderCompiler.cubeImagesAsArrays) return;
        for (Shader s : shaders) {
            if (s.source == null || s.spirv != null || !s.source.contains("Cube")) continue;
            final java.util.Map<String, Integer> cubes = ShaderCompiler.declaredCubeImages(s.source);
            if (cubes.isEmpty()) continue;
            for (ProgramBase.Uniform u : p.uniforms) {
                final Integer declared = cubes.get(u.name);
                if (declared != null) u.declaredDouble = declared;
            }
        }
    }

    private static boolean isDoubleType(int type) {
        return type == GL11.GL_DOUBLE || type >= GL40.GL_DOUBLE_MAT2 && type <= GL40.GL_DOUBLE_MAT4x3 || type >= GL40.GL_DOUBLE_VEC2 && type <= GL40.GL_DOUBLE_VEC4;
    }

    public void shaderBinary(Shader s, int[] words) {
        s.spirv = words;
        s.specialized = false;
        s.compiled = false;
        s.result = null;
        s.source = "";
        s.log = "";
    }

    /** Answers the GL error of the request, leaving a shader that failed in the state GL describes: not specialized, and not compiled. */
    public int specialize(Shader s, String entry, int[] constantIds, int[] values) {
        final SpirvSource.Result result;
        try {
            result = SpirvSource.specialized(s.spirv, s.type, entry, constantIds, values);
        } catch (RuntimeException failed) {
            s.log = String.valueOf(failed.getMessage());
            return GL11.GL_INVALID_VALUE;
        }
        if (result.source() == null) return result.error();
        s.source = result.source();
        s.specialized = true;
        compile(s);
        return GL11.GL_NO_ERROR;
    }

    public void deleteShader(int id) {
        final Shader s = shaders.get(id);
        if (s == null) return;
        if (s.attachCount > 0) {
            s.deletePending = true;
            return;
        }
        forget(id);
    }

    private void forget(int shaderId) {
        shaders.remove(shaderId);
        passthroughVertex.remove(shaderId);
    }

    public void attach(P p, Shader s) {
        if (p == null || s == null || p.attached.contains(s.id)) return;
        p.attached.add(s.id);
        s.attachCount++;
    }

    public void detach(P p, Shader s) {
        if (p == null || s == null) return;
        if (p.attached.rem(s.id)) {
            releaseAttachment(s);
        }
    }

    private void releaseAttachment(Shader s) {
        s.attachCount--;
        if (s.deletePending && s.attachCount <= 0) forget(s.id);
    }

    /** GL's default provoking vertex is the last. Emulated geometry assumes it, as the convention can change after linking. */
    private static boolean flatFromFirstVertex() {
        return false;
    }

    private static Shader lastEmulatedStage(List<Shader> list) {
        Shader evaluation = null;
        for (Shader s : list) {
            if (s.type == GL32.GL_GEOMETRY_SHADER) return s;
            if (s.type == GL40.GL_TESS_EVALUATION_SHADER) evaluation = s;
        }
        return evaluation;
    }

    /** A device with these stages still runs them as compute shaders to capture from them or to emit to several streams, which nothing else here can do. */
    private boolean emulatesStages(P p, List<Shader> list) {
        if (!nativeGeometry()) return true;
        // GL lets a program evaluate without a control stage, using the default levels; Vulkan needs both, so such a program runs emulated.
        if (!p.separable && list.stream().anyMatch(s -> s.type == GL40.GL_TESS_EVALUATION_SHADER) && list.stream().noneMatch(s -> s.type == GL40.GL_TESS_CONTROL_SHADER)) return true;
        final Shader last = lastEmulatedStage(list);
        return last != null && (GlTransformFeedback.declaresVaryings(p.id) || last.source.contains("EmitStreamVertex"));
    }

    private static final java.util.regex.Pattern LATER_STREAM = java.util.regex.Pattern.compile("\\bEmitStreamVertex\\s*\\(\\s*[1-9]|\\bstream\\s*=\\s*[1-9]");

    /** The core values of these program queries; LWJGL's constants of the same names hold the extension's. */
    public static final int GEOMETRY_LINKED_VERTICES_OUT = 0x8916, GEOMETRY_LINKED_INPUT_TYPE = 0x8917, GEOMETRY_LINKED_OUTPUT_TYPE = 0x8918;

    private static final ProgramBase.EmulatedGeometry FAILED_GEOMETRY = new ProgramBase.EmulatedGeometry(null, null, null, 0, 0, 0, 0, 0, 0, false, 0L, 0L, 0L, 0L, 0L, 0, java.util.Map.of(), false);

    /** A stage that runs as a compute shader keeps the names of the stage it was written as. */
    private static String subroutinesLowered(Shader shader) {
        return shader == null ? null : GlslSubroutines.lower(GlslIncludes.expand(shader.source), shader.type);
    }

    /** On a device without those stages, leaves in the list what stands in for them. Returns how to run it, null with none to emulate, or FAILED_GEOMETRY with the log set. */
    private ProgramBase.EmulatedGeometry lowerGeometry(P p, List<Shader> list, StageModes modes) {
        if (!emulatesStages(p, list)) return null;
        Shader vertex = null, control = null, evaluation = null, geometry = null, fragment = null;
        for (Shader s : list) {
            switch (s.type) {
                case GL20.GL_VERTEX_SHADER -> vertex = s;
                case GL40.GL_TESS_CONTROL_SHADER -> control = s;
                case GL40.GL_TESS_EVALUATION_SHADER -> evaluation = s;
                case GL32.GL_GEOMETRY_SHADER -> geometry = s;
                case GL20.GL_FRAGMENT_SHADER -> fragment = s;
                default -> {
                    return null;
                }
            }
        }
        // A separable program can hold just these stages. A pipeline links them with the others, so here they only need their interfaces known.
        final boolean stagesAlone = vertex == null && p.separable && (control != null || evaluation != null || geometry != null);
        if (stagesAlone) {
            vertex = stubStage(GL20.GL_VERTEX_SHADER, "#version 460 core\nvoid main() { gl_Position = vec4(0.0); }\n");
            list.add(vertex);
            if (control != null && evaluation == null && geometry == null) {
                evaluation = stubStage(GL40.GL_TESS_EVALUATION_SHADER, "#version 460 core\nlayout(triangles) in;\nvoid main() { gl_Position = vec4(0.0); }\n");
                list.add(evaluation);
            }
        }
        if (vertex == null || geometry == null && evaluation == null) return null;
        for (Shader s : new Shader[] {control, evaluation, geometry}) {
            if (s == null) continue;
            if (s.result == null) compile(s);
            if (s.result == null) return null;
            modes.read(ProgramsBase.slotOf(s.type), new Spirv(java.nio.ByteBuffer.wrap(s.result.spirv()).order(java.nio.ByteOrder.LITTLE_ENDIAN)));
        }
        if (geometry != null && modes.geometryOutput != GL11.GL_POINTS && LATER_STREAM.matcher(geometry.source).find()) {
            p.log = "a geometry shader can only emit to several vertex streams when its output is points";
            return FAILED_GEOMETRY;
        }
        final int verticesIn = switch (modes.geometryInput) {
            case GL11.GL_POINTS -> 1;
            case GL11.GL_LINES -> 2;
            case GL32.GL_LINES_ADJACENCY -> 4;
            case GL32.GL_TRIANGLES_ADJACENCY -> 6;
            default -> 3;
        };
        final int controlVertices = control != null ? modes.tessOutputVertices : 32;
        final GeometryLowering.Result lowered = GeometryLowering.lower(vertex.source, subroutinesLowered(control), subroutinesLowered(evaluation), subroutinesLowered(geometry),
            fragment == null ? null : fragment.source, controlVertices, verticesIn, modes.geometryVerticesOut, modes.geometryInvocations, modes.geometryOutput, false, stagesAlone);
        if (lowered.problem() != null) {
            p.log = lowered.problem();
            return FAILED_GEOMETRY;
        }
        final P geometryProgram = lowered.compute() == null ? null : computeProgram(p, geometry == null ? 0 : geometry.id, lowered.compute(), "geometry");
        final P controlProgram = lowered.control() == null ? null : computeProgram(p, control == null ? 0 : control.id, lowered.control(), "tessellation control");
        final P evaluationProgram = lowered.evaluation() == null ? null : computeProgram(p, evaluation == null ? 0 : evaluation.id, lowered.evaluation(), "tessellation evaluation");
        if (lowered.compute() != null && geometryProgram == null || lowered.control() != null && controlProgram == null || lowered.evaluation() != null && evaluationProgram == null) return FAILED_GEOMETRY;
        list.set(list.indexOf(vertex), lowered(vertex, lowered.vertex()));
        list.remove(geometry);
        list.remove(control);
        list.remove(evaluation);
        if (lowered.fragment() != null) list.set(list.indexOf(fragment), lowered(fragment, lowered.fragment()));
        return new ProgramBase.EmulatedGeometry(geometryProgram, controlProgram, evaluationProgram, verticesIn, modes.geometryVerticesOut, modes.geometryInvocations, lowered.indicesPerRun(), lowered.drawMode(), controlVertices, control != null,
            lowered.vertexBytes(), lowered.emittedBytes(), lowered.controlBytes(), lowered.patchBytes(), lowered.evaluationBytes(), lowered.emittedStride(), lowered.emittedOffsets(), lowered.evaluationCulls());
    }

    private static final java.util.regex.Pattern ATOMIC_COUNTER = java.util.regex.Pattern.compile("layout\\s*\\([^)]*\\bbinding\\s*=\\s*(\\d+)[^)]*\\)\\s*uniform\\s+atomic_uint\\s+\\w+\\s*(?:\\[\\s*(\\d+)\\s*\\])?");

    /** Compute shaders stand in for some stages and take their limits from it, so GL's for each stage are held to here. */
    private String atomicCountersOverLimit(List<Shader> list) {
        for (Shader s : list) {
            if (s.source == null || s.source.indexOf("atomic_uint") < 0) continue;
            final int[] limits = switch (s.type) {
                case GL20.GL_VERTEX_SHADER -> new int[] {GL42.GL_MAX_VERTEX_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_VERTEX_ATOMIC_COUNTERS};
                case GL40.GL_TESS_CONTROL_SHADER -> new int[] {GL42.GL_MAX_TESS_CONTROL_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_TESS_CONTROL_ATOMIC_COUNTERS};
                case GL40.GL_TESS_EVALUATION_SHADER -> new int[] {GL42.GL_MAX_TESS_EVALUATION_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_TESS_EVALUATION_ATOMIC_COUNTERS};
                case GL32.GL_GEOMETRY_SHADER -> new int[] {GL42.GL_MAX_GEOMETRY_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_GEOMETRY_ATOMIC_COUNTERS};
                case GL20.GL_FRAGMENT_SHADER -> new int[] {GL42.GL_MAX_FRAGMENT_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_FRAGMENT_ATOMIC_COUNTERS};
                case GL43.GL_COMPUTE_SHADER -> new int[] {GL43.GL_MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS, GL43.GL_MAX_COMPUTE_ATOMIC_COUNTERS};
                default -> null;
            };
            if (limits == null) continue;
            final java.util.BitSet buffers = new java.util.BitSet();
            int counters = 0;
            final java.util.regex.Matcher m = ATOMIC_COUNTER.matcher(ShaderCompiler.preprocessed(ShaderCompiler.withDeclaredLanguage(s.source), s.type));
            while (m.find()) {
                buffers.set(Integer.parseInt(m.group(1)));
                counters += m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            }
            if (buffers.cardinality() > atomicLimit(limits[0])) return "a shader uses more atomic counter buffers than its stage allows";
            if (counters > atomicLimit(limits[1])) return "a shader uses more atomic counters than its stage allows";
        }
        return null;
    }

    public static String withoutComments(String source) {
        return GeometryLowering.stripComments(source);
    }

    private static final java.util.regex.Pattern IMAGE_UNIFORM = java.util.regex.Pattern.compile("\\buniform\\s+(?:\\w+\\s+)*[iu]?image\\w+\\s+(\\w+)\\s*(?:\\[([^\\]]*)\\])?");
    private static final java.util.regex.Pattern IMAGE_LIMIT_NAME = java.util.regex.Pattern.compile("\\bgl_Max(\\w*)ImageUniforms\\b");

    private static int imageLimitOf(int type) {
        return switch (type) {
            case GL20.GL_VERTEX_SHADER -> GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS;
            case GL40.GL_TESS_CONTROL_SHADER -> GL42.GL_MAX_TESS_CONTROL_IMAGE_UNIFORMS;
            case GL40.GL_TESS_EVALUATION_SHADER -> GL42.GL_MAX_TESS_EVALUATION_IMAGE_UNIFORMS;
            case GL32.GL_GEOMETRY_SHADER -> GL42.GL_MAX_GEOMETRY_IMAGE_UNIFORMS;
            case GL20.GL_FRAGMENT_SHADER -> GL42.GL_MAX_FRAGMENT_IMAGE_UNIFORMS;
            case GL43.GL_COMPUTE_SHADER -> GL43.GL_MAX_COMPUTE_IMAGE_UNIFORMS;
            default -> 0;
        };
    }

    /** A declared size can be a limit plus or minus a number, the one kind of expression a test of limits has any use for. */
    private int imageArraySize(String size) {
        if (size == null || size.isBlank()) return 1;
        int total = 0;
        for (String term : size.replace(" ", "").replace("-", "+-").split("\\+")) {
            if (term.isEmpty()) continue;
            final boolean negative = term.startsWith("-");
            final String bare = negative ? term.substring(1) : term;
            final java.util.regex.Matcher limit = IMAGE_LIMIT_NAME.matcher(bare);
            final int value;
            if (limit.matches()) {
                value = atomicLimit(switch (limit.group(1)) {
                    case "Vertex" -> GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS;
                    case "TessControl" -> GL42.GL_MAX_TESS_CONTROL_IMAGE_UNIFORMS;
                    case "TessEvaluation" -> GL42.GL_MAX_TESS_EVALUATION_IMAGE_UNIFORMS;
                    case "Geometry" -> GL42.GL_MAX_GEOMETRY_IMAGE_UNIFORMS;
                    case "Fragment" -> GL42.GL_MAX_FRAGMENT_IMAGE_UNIFORMS;
                    case "Compute" -> GL43.GL_MAX_COMPUTE_IMAGE_UNIFORMS;
                    default -> GL42.GL_MAX_COMBINED_IMAGE_UNIFORMS;
                });
            } else if (bare.matches("\\d+")) {
                value = Integer.parseInt(bare);
            } else {
                return 1;
            }
            total += negative ? -value : value;
        }
        return Math.max(1, total);
    }

    /** Whether a shader reads and writes an image, taking one it uses any other way than the image functions to do both. */
    static boolean readAndWritten(String code, int from, String name) {
        boolean reads = false;
        boolean writes = false;
        final java.util.regex.Matcher use = java.util.regex.Pattern.compile("\\b(\\w+)\\s*\\(\\s*" + name + "\\b|\\b" + name + "\\b").matcher(code);
        use.region(from, code.length());
        while (use.find()) {
            final String call = use.group(1);
            if (call == null) return true;
            switch (call) {
                case "imageLoad" -> reads = true;
                case "imageStore" -> writes = true;
                case "imageSize", "imageSamples" -> { }
                default -> { return true; }
            }
        }
        return reads && writes;
    }

    private String imagesOverLimit(List<Shader> list) {
        int combined = 0;
        for (Shader s : list) {
            if (s.source == null || !s.source.contains("image")) continue;
            final String code = GeometryLowering.stripComments(ShaderCompiler.preprocessed(ShaderCompiler.withDeclaredLanguage(s.source), s.type));
            int stage = 0;
            int both = 0;
            final java.util.regex.Matcher m = IMAGE_UNIFORM.matcher(code);
            while (m.find()) {
                final java.util.regex.Matcher used = java.util.regex.Pattern.compile("\\b" + m.group(1) + "\\b").matcher(code);
                used.region(m.end(), code.length());
                if (!used.find()) continue;
                final int size = imageArraySize(m.group(2));
                stage += size;
                if (readAndWritten(code, m.end(), m.group(1))) both += size;
            }
            if (both > atomicLimit(imageLimitOf(s.type)) || stage > imagesPerStage()) return "a shader uses more images than its stage allows";
            combined += stage;
        }
        return combined > atomicLimit(GL42.GL_MAX_COMBINED_IMAGE_UNIFORMS) ? "the program's stages use more images than GL_MAX_COMBINED_IMAGE_UNIFORMS" : null;
    }

    private Shader stubStage(int type, String source) {
        final Shader stub = new Shader(nextName(), type);
        stub.source = source;
        compile(stub);
        return stub;
    }

    private static int slotOf(int type) {
        return type == GL40.GL_TESS_CONTROL_SHADER ? ProgramBase.TCS : type == GL40.GL_TESS_EVALUATION_SHADER ? ProgramBase.TES : ProgramBase.GS;
    }

    private P computeProgram(P owner, int shaderId, String source, String stage) {
        final Shader compute = lowered(new Shader(shaderId, GL43.GL_COMPUTE_SHADER), source);
        if (compute.result == null) {
            owner.log = stage + " shader " + shaderId + " could not run as a compute shader: " + compute.log;
            return null;
        }
        final P program = newProgram(nextVariantId--);
        program.label = stage + " shader of " + owner.id;
        if (!linkProgram(program, List.of(compute))) {
            owner.log = stage + " shader " + shaderId + " could not run as a compute shader: " + program.log;
            return null;
        }
        programs.put(program.id, program);
        return program;
    }

    private void retireGeometry(P p) {
        if (p.geometry == null) return;
        final ProgramBase<?>[] all = {p.geometry.compute, p.geometry.control, p.geometry.evaluation};
        p.geometry = null;
        for (ProgramBase<?> stage : all) {
            if (stage == null) continue;
            @SuppressWarnings("unchecked")
            final P program = (P) stage;
            programs.remove(program.id);
            retireNativeObjects(program);
            program.resetLinkResults();
        }
    }

    /** A sampler or image only an emulated stage reads is gone from the program that draws, and is still the application's to set. */
    private void adoptOpaqueUniforms(P p, ProgramBase<?> stage) {
        if (stage == null) return;
        for (ProgramBase.Uniform u : stage.uniforms) {
            if (!u.isOpaque() || u.units == null || p.locations.containsKey(u.name) || u.name.startsWith("angelica_")) continue;
            final ProgramBase.Uniform adopted = new ProgramBase.Uniform(u.name, u.glType, u.arraySize);
            adopted.units = u.units.clone();
            adopted.baseLocation = p.locationUniform.length;
            final int count = Math.max(1, u.arraySize);
            p.locationUniform = java.util.Arrays.copyOf(p.locationUniform, adopted.baseLocation + count);
            p.locationElement = java.util.Arrays.copyOf(p.locationElement, adopted.baseLocation + count);
            for (int e = 0; e < count; e++) {
                p.locationUniform[adopted.baseLocation + e] = p.uniforms.size();
                p.locationElement[adopted.baseLocation + e] = e;
            }
            p.uniforms.add(adopted);
            p.locations.put(u.name, adopted.baseLocation);
            if (u.arraySize >= 1) {
                for (int e = 0; e < count; e++) p.locations.put(u.name + "[" + e + "]", adopted.baseLocation + e);
            }
        }
    }

    public void copyUniforms(ProgramBase<?> from, ProgramBase<?> to) {
        for (ProgramBase.Uniform target : to.uniforms) {
            final int location = from.locations.getInt(target.name);
            if (location < 0 || from.locationUniform[location] < 0) continue;
            final ProgramBase.Uniform source = from.uniforms.get(from.locationUniform[location]);
            if (target.isOpaque()) {
                if (source.units != null) System.arraycopy(source.units, 0, target.units, 0, Math.min(source.units.length, target.units.length));
                continue;
            }
            for (int s = 0; s < ProgramBase.STAGES; s++) {
                if (source.offset[s] < 0 || target.offset[0] < 0) continue;
                final ProgramBase.StageBlock in = from.blocks[s], out = to.blocks[0];
                final long room = Math.min(in.size - source.offset[s], out.size - target.offset[0]);
                MemoryUtil.memCopy(in.address + source.offset[s], out.address + target.offset[0], Math.min(room, uniformBytes(target, 0)));
                out.dirty = true;
                break;
            }
        }
        for (ProgramBase.BufferBlock target : to.ubos) {
            for (ProgramBase.BufferBlock source : from.ubos) if (source.name.equals(target.name)) target.glBinding = source.glBinding;
        }
        for (ProgramBase.BufferBlock target : to.ssbos) {
            for (ProgramBase.BufferBlock source : from.ssbos) if (source.name.equals(target.name)) target.glBinding = source.glBinding;
        }
    }

    private static long uniformBytes(ProgramBase.Uniform uniform, int stage) {
        final long stride = uniform.arrayStride[stage];
        return stride > 0 ? stride * Math.max(1, uniform.arraySize)
            : matrixColumns(uniform.glType) > 0 ? (long) uniform.matrixStride[stage] * matrixColumns(uniform.glType) : (long) components(uniform.glType) * Integer.BYTES;
    }

    /** For a stage whose default block is laid out differently in the two programs, because of what each adds of its own. */
    public void copyDeclaredUniforms(ProgramBase<?> from, ProgramBase<?> to, int stage) {
        final ProgramBase.StageBlock in = from.blocks[stage], out = to.blocks[stage];
        for (ProgramBase.Uniform target : to.uniforms) {
            if (target.offset[stage] < 0 || target.isOpaque()) continue;
            if (ProgramBase.undeclared(target.name) && !target.name.startsWith(ProgramBase.STAGE_UNIFORM_PREFIX)) continue;
            // A stage program that runs emulated has these of its own, which say nothing about the draw in hand.
            if (target.name.startsWith("angelica_gs_")) continue;
            final int location = from.locations.getInt(target.name);
            if (location < 0 || from.locationUniform[location] < 0) continue;
            final ProgramBase.Uniform source = from.uniforms.get(from.locationUniform[location]);
            if (source.offset[stage] < 0) continue;
            if (storesDoubles(source) && !storesDoubles(target)) {
                copyNarrowed(source, in, target, out, stage);
                out.dirty = true;
                continue;
            }
            final long room = Math.min(in.size - source.offset[stage], out.size - target.offset[stage]);
            MemoryUtil.memCopy(in.address + source.offset[stage], out.address + target.offset[stage], Math.min(room, uniformBytes(target, stage)));
            out.dirty = true;
        }
    }

    /** What runs an emulated stage holds as floats what the program declares as doubles. */
    private static void copyNarrowed(ProgramBase.Uniform source, ProgramBase.StageBlock in, ProgramBase.Uniform target, ProgramBase.StageBlock out, int stage) {
        final int cols = doubleColumns(source.glType), rows = doubleRows(source.glType);
        for (int i = 0; i < Math.max(1, Math.min(source.arraySize, target.arraySize)); i++) {
            final long from = in.address + source.offset[stage] + (long) i * source.arrayStride[stage];
            final long to = out.address + target.offset[stage] + (long) i * target.arrayStride[stage];
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    final long dst = to + (long) c * target.matrixStride[stage] + r * 4L;
                    if (dst + 4 <= out.address + out.size) MemoryUtil.memPutFloat(dst, (float) MemoryUtil.memGetDouble(from + (long) c * source.matrixStride[stage] + r * 8L));
                }
            }
        }
    }

    /** Whether the last program lowerCullDistances rewrote drops culled primitives' fragments itself, so the draw need not wait for the distances. */
    private boolean cullsOnDevice;

    /** Rewrites a vertex shader that is the last before rasterizing, and returns how many distances it stores. */
    private int lowerCullDistances(List<Shader> list) {
        cullsOnDevice = false;
        if (nativeCullDistance()) return 0;
        Shader vertex = null, fragment = null;
        for (Shader s : list) {
            if (s.type == GL20.GL_VERTEX_SHADER) vertex = s;
            else if (s.type == GL20.GL_FRAGMENT_SHADER) fragment = s;
            else if (s.type != GL43.GL_COMPUTE_SHADER) return 0;
        }
        if (vertex == null || !CullDistanceLowering.uses(vertex.source)) return 0;
        final int count = CullDistanceLowering.count(vertex.source);
        final boolean fragmentReads = fragment != null && CullDistanceLowering.uses(fragment.source);
        list.set(list.indexOf(vertex), lowered(vertex, CullDistanceLowering.vertex(vertex.source, count, fragmentReads)));
        String fragmentSource = fragment == null ? null : fragmentReads ? CullDistanceLowering.fragment(fragment.source, count) : fragment.source;
        cullsOnDevice = fragmentSource != null && CullDistanceLowering.discards(fragmentSource);
        if (cullsOnDevice) fragmentSource = CullDistanceLowering.withCulledDiscarded(fragmentSource);
        if (fragment != null && !fragmentSource.equals(fragment.source)) list.set(list.indexOf(fragment), lowered(fragment, fragmentSource));
        return count;
    }

    private boolean lowerMatrixArrays(List<Shader> list, List<MatrixArrayLowering.Flattened> vertexInputs) {
        if (!ShaderCompiler.matrixArraysFlattened) return false;
        boolean any = false;
        for (int i = 0; i < list.size(); i++) {
            final Shader s = list.get(i);
            if (s.spirv != null || s.source == null || !s.source.contains("mat") || s.type == GL43.GL_COMPUTE_SHADER) continue;
            final String text = GeometryLowering.stripComments(ShaderCompiler.settled(ShaderCompiler.withDeclaredLanguage(s.source), s.type));
            if (!MatrixArrayLowering.applies(text)) continue;
            if (s.type == GL20.GL_VERTEX_SHADER) vertexInputs.addAll(MatrixArrayLowering.inputs(text));
            list.set(i, lowered(s, MatrixArrayLowering.lower(text)));
            any = true;
        }
        return any;
    }

    private void restoreFlattenedAttributes(P p, List<MatrixArrayLowering.Flattened> inputs) {
        for (MatrixArrayLowering.Flattened input : inputs) {
            for (int i = 0; i < p.attribs.size(); i++) {
                final ProgramBase.Attrib a = p.attribs.get(i);
                if (!a.name.equals(MatrixArrayLowering.PREFIX + input.name())) continue;
                final ProgramBase.Attrib restored = new ProgramBase.Attrib(input.name(), a.location, matrixType(input.columns(), input.rows()), a.locations);
                restored.elementLocations = input.columns();
                restored.size = input.count();
                p.attribs.set(i, restored);
            }
        }
    }

    private static int matrixType(int columns, int rows) {
        return switch (columns * 10 + rows) {
            case 22 -> GL20.GL_FLOAT_MAT2;
            case 23 -> org.lwjgl.opengl.GL21.GL_FLOAT_MAT2x3;
            case 24 -> org.lwjgl.opengl.GL21.GL_FLOAT_MAT2x4;
            case 32 -> org.lwjgl.opengl.GL21.GL_FLOAT_MAT3x2;
            case 33 -> GL20.GL_FLOAT_MAT3;
            case 34 -> org.lwjgl.opengl.GL21.GL_FLOAT_MAT3x4;
            case 42 -> org.lwjgl.opengl.GL21.GL_FLOAT_MAT4x2;
            case 43 -> org.lwjgl.opengl.GL21.GL_FLOAT_MAT4x3;
            default -> GL20.GL_FLOAT_MAT4;
        };
    }

    private Shader lowered(Shader original, String source) {
        final Shader shader = new Shader(original.id, original.type);
        shader.source = source;
        compile(shader);
        return shader;
    }

    private static final java.util.regex.Pattern MAIN = java.util.regex.Pattern.compile("\\bvoid\\s+main\\s*\\(");

    /** A stage made of several shaders compiles here as one, whichever its own compile left out. */
    private boolean joinStages(P p, List<Shader> list) {
        final java.util.LinkedHashMap<Integer, List<Shader>> byType = new java.util.LinkedHashMap<>();
        for (Shader s : list) byType.computeIfAbsent(s.type, k -> new ArrayList<>()).add(s);
        for (List<Shader> stage : byType.values()) {
            if (stage.size() < 2) continue;
            if (stage.stream().filter(s -> MAIN.matcher(s.source).find()).count() > 1) {
                p.linked = false;
                p.log = "more than one shader of a stage defines main";
                return false;
            }
            final Shader joined = new Shader(stage.get(0).id, stage.get(0).type);
            joined.source = ShaderCompiler.joinUnits(stage.stream().map(s -> s.source).toList());
            compile(joined);
            if (!joined.compiled) {
                p.linked = false;
                p.log = joined.log;
                return false;
            }
            list.removeAll(stage);
            list.add(joined);
        }
        return true;
    }

    public void link(P p) {
        GlTransformFeedback.linking(p.id);
        final List<Shader> list = new ArrayList<>();
        for (int i = 0; i < p.attached.size(); i++) {
            final Shader s = shaders.get(p.attached.getInt(i));
            if (s != null) list.add(s);
        }
        p.fromSpirv = list.stream().anyMatch(s -> s.spirv != null);
        if (list.isEmpty() && !p.pipelineComposite && !p.separable) {
            // GL links a program with nothing attached. What it draws is undefined in a core profile, and here it is nothing.
            p.resetLinkResults();
            p.noShaders = true;
            p.linked = true;
            p.gen++;
            p.log = "";
            return;
        }
        final String acrossShaders = p.pipelineComposite ? null : p.separable && LinkChecks.separableProblem(list) != null ? LinkChecks.separableProblem(list) : LinkChecks.problem(list);
        if (acrossShaders != null) {
            p.linked = false;
            p.log = acrossShaders;
            return;
        }
        if (!joinStages(p, list)) return;
        p.stageSources.clear();
        p.subroutineValues = null;
        p.usesSubroutines = false;
        p.writesViewportIndex = false;
        for (Shader s : list) {
            p.stageSources.put(s.type, s.source);
            p.usesSubroutines |= GlslSubroutines.uses(s.source);
            p.writesViewportIndex |= s.source.contains("gl_ViewportIndex");
            final String refused = s.source != null && GlslSubroutines.uses(s.source) ? GlslSubroutines.declared(s.source).problem() : null;
            if (refused != null) {
                p.linked = false;
                p.log = refused;
                return;
            }
        }
        final Shader lastStage = emulatesStages(p, list) ? lastEmulatedStage(list) : null;
        if (lastStage != null) {
            final String unwritten = GlTransformFeedback.declareFromStage(p.id, ShaderCompiler.preprocessed(ShaderCompiler.withDeclaredLanguage(lastStage.source), lastStage.type));
            if (unwritten != null) {
                p.linked = false;
                p.log = "transform feedback varying " + unwritten + " is not written by the last stage before rasterization";
                return;
            }
            final String mixed = GlTransformFeedback.mixedStreams(p.id);
            if (mixed != null) {
                p.linked = false;
                p.log = "transform feedback varying " + mixed + " shares a buffer with one of another vertex stream";
                return;
            }
        }
        final String overImages = p.pipelineComposite ? null : imagesOverLimit(list);
        if (overImages != null) {
            p.linked = false;
            p.log = overImages;
            return;
        }
        final String overAtomics = atomicCountersOverLimit(list);
        if (overAtomics != null) {
            p.linked = false;
            p.log = overAtomics;
            return;
        }
        for (int i = 0; i < list.size() && lastStage == null; i++) {
            final Shader s = list.get(i);
            final String missing = s.type == GL20.GL_VERTEX_SHADER ? GlTransformFeedback.unwritten(p.id, s.source) : null;
            if (missing != null) {
                p.linked = false;
                p.log = "transform feedback varying " + missing + " is not written by the last stage before rasterization";
                return;
            }
            final String capturing = s.type == GL20.GL_VERTEX_SHADER ? GlTransformFeedback.capturingSource(p.id, s.source) : null;
            if (capturing == null) continue;
            final Shader capture = new Shader(s.id, s.type);
            capture.source = capturing;
            compile(capture);
            list.set(i, capture);
        }
        final List<Shader> asWritten = List.copyOf(list);
        final int cullDistances = lowerCullDistances(list);
        final List<MatrixArrayLowering.Flattened> flattenedInputs = new ArrayList<>();
        final boolean noVertexStage = list.stream().noneMatch(s -> s.type == GL20.GL_VERTEX_SHADER);
        final StageModes geometryModes = new StageModes();
        final ProgramBase.EmulatedGeometry geometry = lowerGeometry(p, list, geometryModes);
        if (geometry == FAILED_GEOMETRY) {
            p.linked = false;
            return;
        }
        final Shader fragmentOnly = fragmentOnly(list);
        if (fragmentOnly != null) list.add(passthroughVertexFor(fragmentOnly));
        retireLogicVariants(p);
        final Object old = detachNativeObjects(p);
        final boolean flattened = lowerMatrixArrays(list, flattenedInputs);
        if (linkProgram(p, list)) {
            p.cullDistances = cullDistances;
            p.cullsOnDevice = cullsOnDevice;
            retireGeometry(p);
            if (geometry != null) {
                p.geometry = geometry;
                p.stageModes = geometryModes;
                p.vertexless = noVertexStage;
                adoptOpaqueUniforms(p, geometry.compute);
                adoptOpaqueUniforms(p, geometry.control);
                adoptOpaqueUniforms(p, geometry.evaluation);
            }
            if (geometry != null || fragmentOnly != null || flattened) reportInterfacesOf(p, asWritten);
            restoreFlattenedAttributes(p, flattenedInputs);
            noteDeclaredDoubles(p, asWritten);
            p.usesGather = list.stream().anyMatch(s -> s.source != null && s.source.contains("textureGather"));
            p.usesCubes = list.stream().anyMatch(s -> s.source != null && s.source.contains("samplerCube"));
            setInitialValues(p, asWritten);
            // Snapshots of the linked sources outlive later glShaderSource calls, so logic-op variants can be built from exactly what was linked.
            p.linkedShaders = new Shader[list.size()];
            p.flatInputs = list.stream().anyMatch(s -> FLAT.matcher(s.source).find());
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
            p.linked = false;
            log().warn("Program {} failed to link for {}: {}", p.id, apiName(), p.log);
            linkDone(p, old, false);
        }
        if (cached == p) cachedId = -1;
    }

    /** What stands in for stages has interfaces of its own, while the program's are those of the first and last stages the application wrote. */
    private void reportInterfacesOf(P p, List<Shader> written) {
        Shader first = null, last = null;
        int firstSlot = 0, lastSlot = 0;
        for (Shader s : written) {
            final int slot = s.type == GL20.GL_VERTEX_SHADER ? ProgramBase.VS : s.type == GL20.GL_FRAGMENT_SHADER ? ProgramBase.FS : slotOf(s.type);
            if (first == null || slot < firstSlot) {
                first = s;
                firstSlot = slot;
            }
            if (last == null || slot > lastSlot) {
                last = s;
                lastSlot = slot;
            }
        }
        if (first == null || first.result == null || last.result == null) return;
        p.programInputs.clear();
        p.programOutputs.clear();
        p.programInputs.addAll(reflected(first).interfaceVariables(firstSlot, true));
        p.programOutputs.addAll(reflected(last).interfaceVariables(lastSlot, false));
    }

    private void setInitialValues(P p, List<Shader> written) {
        for (Shader s : written) {
            for (UniformInitializers.Initial initial : UniformInitializers.of(s.source, s.type)) {
                int location = p.locations.getInt(initial.name());
                if (location < 0) location = p.locations.getInt(initial.name() + "[0]");
                if (location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) continue;
                final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
                final int[] matrix = matrixShape(u.glType);
                final long values = MemoryUtil.nmemAlloc(initial.words().length * 4L);
                for (int i = 0; i < initial.words().length; i++) MemoryUtil.memPutInt(values + i * 4L, initial.words()[i]);
                if (matrix != null) uniformMatrix(p, location, initial.words().length / (matrix[0] * matrix[1]), matrix[0], matrix[1], false, values);
                else if (!u.isOpaque()) uniform(p, location, initial.words().length / components(u.glType), components(u.glType), scalarKind(u.glType) == 0, values);
                MemoryUtil.nmemFree(values);
            }
        }
    }

    private static int[] matrixShape(int glType) {
        return switch (glType) {
            case GL20.GL_FLOAT_MAT2 -> new int[] {2, 2};
            case GL20.GL_FLOAT_MAT3 -> new int[] {3, 3};
            case GL20.GL_FLOAT_MAT4 -> new int[] {4, 4};
            case GL21.GL_FLOAT_MAT2x3 -> new int[] {2, 3};
            case GL21.GL_FLOAT_MAT2x4 -> new int[] {2, 4};
            case GL21.GL_FLOAT_MAT3x2 -> new int[] {3, 2};
            case GL21.GL_FLOAT_MAT3x4 -> new int[] {3, 4};
            case GL21.GL_FLOAT_MAT4x2 -> new int[] {4, 2};
            case GL21.GL_FLOAT_MAT4x3 -> new int[] {4, 3};
            default -> null;
        };
    }

    private static StageReflection reflected(Shader s) {
        return new StageReflection(new Spirv(java.nio.ByteBuffer.wrap(s.result.spirv()).order(java.nio.ByteOrder.LITTLE_ENDIAN)), s.result.boolUniforms(), s.result.bindings());
    }

    private static Shader fragmentOnly(List<Shader> list) {
        Shader fs = null;
        for (Shader s : list) {
            switch (s.type) {
                case GL20.GL_VERTEX_SHADER, GL43.GL_COMPUTE_SHADER, GL32.GL_GEOMETRY_SHADER, GL40.GL_TESS_CONTROL_SHADER, GL40.GL_TESS_EVALUATION_SHADER -> {
                    return null;
                }
                case GL20.GL_FRAGMENT_SHADER -> fs = s;
                default -> {}
            }
        }
        return fs;
    }

    private final Int2ObjectOpenHashMap<Shader> passthroughVertex = new Int2ObjectOpenHashMap<>();

    /**
     * OpenGL links a program with no vertex shader against the fixed-function vertex stage (Botania relies on it);
     * the emulated stage is a generated passthrough of the varyings the fragment shader declares.
     */
    private Shader passthroughVertexFor(Shader fs) {
        final Shader existing = passthroughVertex.get(fs.id);
        final String source = CompatShaderTransformer.generatePassthroughVertexShader(fs.source);
        if (existing != null && existing.source.equals(source)) return existing;
        final Shader vs = new Shader(nextName(), GL20.GL_VERTEX_SHADER);
        vs.label = "fixed-function vertex stage for shader " + fs.id;
        vs.source = source;
        compile(vs);
        passthroughVertex.put(fs.id, vs);
        return vs;
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
            log().warn("{} is not emulated for program {}: {}", key == SampleRateLowering.KEY ? "GL_SAMPLE_SHADING" : "glLogicOp", base.id, e.getMessage());
            return null;
        }
    }

    /** The other stages sample border colours too. */
    private Shader withBorder(Shader s, long key) {
        if (s.spirv != null || s.source == null) return s;
        final String settled = ShaderCompiler.settled(ShaderCompiler.lowerLanguage(s.source, s.type), s.type);
        final String lowered = BorderLowering.lower(settled, key, false);
        return lowered.equals(settled) ? s : lowered(s, lowered);
    }

    protected P buildLogicOpVariant(P base, long key) {
        Shader fragment = null;
        for (Shader s : base.linkedShaders) if (s.type == GL20.GL_FRAGMENT_SHADER) fragment = s;
        if (fragment == null || base.compute) throw new IllegalStateException("no fragment shader");
        final String name = "logicop" + base.id;
        final GlslVulkanPreprocess.Result pre = GlslVulkanPreprocess.run(ShaderCompiler.settled(ShaderCompiler.lowerLanguage(fragment.source, GL20.GL_FRAGMENT_SHADER), GL20.GL_FRAGMENT_SHADER), GL20.GL_FRAGMENT_SHADER, name, false);
        if (pre == null) throw new IllegalStateException("fragment preprocessing failed");
        final boolean sampleRate = (key & SampleRateLowering.KEY) != 0;
        final long logicOp = key & ~SampleRateLowering.KEY & ~BorderLowering.MASK;
        final String perSample = sampleRate ? SampleRateLowering.lower(pre.rewrittenSource()) : pre.rewrittenSource();
        final String rewritten = (key & BorderLowering.KEY) != 0 ? BorderLowering.lower(perSample, key, true) : perSample;
        final String lowered = logicOp != 0 ? LogicOpLowering.lower(rewritten, logicOp, base.id) : rewritten;
        final ShaderCompiler.Result r = ShaderCompiler.compilePreprocessed(lowered, pre, name);
        if (r.compiled() == null) throw new IllegalStateException("variant failed to compile: " + r.log());

        final Shader variantFragment = new Shader(fragment.id, GL20.GL_FRAGMENT_SHADER);
        variantFragment.source = lowered;
        variantFragment.result = r.compiled();
        variantFragment.resultFixup = ShaderCompiler.DEPTH_NONE;
        variantFragment.compiled = true;
        final List<Shader> list = new ArrayList<>();
        for (Shader s : base.linkedShaders) list.add(s == fragment ? variantFragment : (key & BorderLowering.KEY) != 0 ? withBorder(s, key) : s);
        final P v = newProgram(nextVariantId--);
        v.label = "variant of " + base.id;
        v.attribBindings.putAll(base.attribBindings);
        v.fragDataBindings.putAll(base.fragDataBindings);
        if (!linkProgram(v, list)) {
            v.resetLinkResults();
            throw new IllegalStateException("variant failed to link: " + v.log);
        }

        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final ProgramBase.StageBlock a = base.blocks[s], b = v.blocks[s];
            if (a == null != (b == null) || a != null && a.size != b.size) {
                destroyNativeObjects(v);
                v.resetLinkResults();
                throw new IllegalStateException("default uniform block layout differs in stage " + s);
            }
        }
        v.logicBaseUniform = new int[v.uniforms.size()];
        for (int i = 0; i < v.logicBaseUniform.length; i++) {
            final int loc = base.locations.getInt(v.uniforms.get(i).name);
            v.logicBaseUniform[i] = loc < 0 ? -1 : base.locationUniform[loc];
        }
        return v;
    }

    public void deleteProgram(int id) {
        final P p = programs.get(id);
        if (p == null) return;
        if (anyStateUsesProgram(id)) {
            p.deletePending = true;
            return;
        }
        programs.remove(id);
        retireGeometry(p);
        retireLogicVariants(p);
        for (int i = 0; i < p.attached.size(); i++) {
            final Shader s = shaders.get(p.attached.getInt(i));
            if (s != null) releaseAttachment(s);
        }
        retireNativeObjects(p);
        p.resetLinkResults();
        if (cached == p) cachedId = -1;
    }

    public void uniform(P p, int location, int count, int comps, boolean srcFloat, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
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

    /** Columns and rows of a double type: a vector is one column, a matrix as it says. */
    private static int doubleColumns(int type) {
        return switch (type) {
            case GL40.GL_DOUBLE_MAT2, GL40.GL_DOUBLE_MAT2x3, GL40.GL_DOUBLE_MAT2x4 -> 2;
            case GL40.GL_DOUBLE_MAT3, GL40.GL_DOUBLE_MAT3x2, GL40.GL_DOUBLE_MAT3x4 -> 3;
            case GL40.GL_DOUBLE_MAT4, GL40.GL_DOUBLE_MAT4x2, GL40.GL_DOUBLE_MAT4x3 -> 4;
            default -> 1;
        };
    }

    private static int doubleRows(int type) {
        return switch (type) {
            case GL40.GL_DOUBLE_VEC2, GL40.GL_DOUBLE_MAT2, GL40.GL_DOUBLE_MAT3x2, GL40.GL_DOUBLE_MAT4x2 -> 2;
            case GL40.GL_DOUBLE_VEC3, GL40.GL_DOUBLE_MAT2x3, GL40.GL_DOUBLE_MAT3, GL40.GL_DOUBLE_MAT4x3 -> 3;
            case GL40.GL_DOUBLE_VEC4, GL40.GL_DOUBLE_MAT2x4, GL40.GL_DOUBLE_MAT3x4, GL40.GL_DOUBLE_MAT4 -> 4;
            default -> 1;
        };
    }

    private static boolean storesDoubles(ProgramBase.Uniform u) {
        return u.glType != 0 && isDoubleType(u.glType);
    }

    public void uniformDoubles(P p, int location, int count, int comps, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        if (u.isOpaque()) return;
        final int element = p.locationElement[location];
        final int n = Math.min(count, Math.max(1, u.arraySize) - element);
        if (!storesDoubles(u)) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                final long floats = stack.nmalloc(4, n * comps * 4);
                for (int i = 0; i < n * comps; i++) MemoryUtil.memPutFloat(floats + i * 4L, (float) MemoryUtil.memGetDouble(src + i * 8L));
                uniform(p, location, n, comps, true, floats);
            }
            return;
        }
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            if (u.offset[s] < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            for (int i = 0; i < n; i++) {
                final long dst = blk.address + u.offset[s] + (long) (element + i) * u.arrayStride[s];
                if (dst + comps * 8L > blk.address + blk.size) break;
                MemoryUtil.memCopy(src + (long) i * comps * 8L, dst, comps * 8L);
            }
            blk.dirty = true;
        }
    }

    public void uniformMatrixDoubles(P p, int location, int count, int cols, int rows, boolean transpose, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        if (u.isOpaque()) return;
        final int element = p.locationElement[location];
        final int n = Math.min(count, Math.max(1, u.arraySize) - element);
        if (!storesDoubles(u)) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                final int total = n * cols * rows;
                final long floats = stack.nmalloc(4, total * 4);
                for (int i = 0; i < total; i++) MemoryUtil.memPutFloat(floats + i * 4L, (float) MemoryUtil.memGetDouble(src + i * 8L));
                uniformMatrix(p, location, n, cols, rows, transpose, floats);
            }
            return;
        }
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            if (u.offset[s] < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            final int ms = u.matrixStride[s];
            for (int i = 0; i < n; i++) {
                final long base = blk.address + u.offset[s] + (long) (element + i) * u.arrayStride[s];
                final long in = src + (long) i * cols * rows * 8;
                for (int c = 0; c < cols; c++) {
                    for (int r = 0; r < rows; r++) {
                        final long from = in + (transpose ? (long) r * cols + c : (long) c * rows + r) * 8;
                        final long dst = base + (long) c * ms + r * 8L;
                        if (dst + 8 <= blk.address + blk.size) MemoryUtil.memPutLong(dst, MemoryUtil.memGetLong(from));
                    }
                }
            }
            blk.dirty = true;
        }
    }

    public boolean getUniformDoubles(P p, int location, long out) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return false;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        if (u.isOpaque() || !storesDoubles(u)) return false;
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            if (u.offset[s] < 0) continue;
            final long base = p.blocks[s].address + u.offset[s] + (long) p.locationElement[location] * u.arrayStride[s];
            final int cols = doubleColumns(u.glType), rows = doubleRows(u.glType);
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) MemoryUtil.memPutLong(out + (long) (c * rows + r) * 8, MemoryUtil.memGetLong(base + (long) c * u.matrixStride[s] + r * 8L));
            }
            return true;
        }
        return false;
    }

    /** 64-bit integers, which go into the default block as they are. */
    public void uniform64(P p, int location, int count, int comps, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        if (u.isOpaque()) return;
        final int element = p.locationElement[location];
        final int n = Math.min(count, Math.max(1, u.arraySize) - element);
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final int off = u.offset[s];
            if (off < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            for (int i = 0; i < n; i++) {
                final long dst = blk.address + off + (long) (element + i) * u.arrayStride[s];
                if (dst + comps * 8L > blk.address + blk.size) break;
                MemoryUtil.memCopy(src + (long) i * comps * 8L, dst, comps * 8L);
            }
            blk.dirty = true;
        }
    }

    public void getUniform64(P p, int location, int comps, long out) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
        final ProgramBase.Uniform u = p.uniforms.get(p.locationUniform[location]);
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            if (u.offset[s] < 0) continue;
            final ProgramBase.StageBlock blk = p.blocks[s];
            MemoryUtil.memCopy(blk.address + u.offset[s] + (long) p.locationElement[location] * u.arrayStride[s], out, comps * 8L);
            return;
        }
    }

    public void uniformMatrix(P p, int location, int count, int cols, int rows, boolean transpose, long src) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
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

    private static int matrixColumns(int glType) {
        return switch (glType) {
            case GL20.GL_FLOAT_MAT2, GL21.GL_FLOAT_MAT2x3, GL21.GL_FLOAT_MAT2x4 -> 2;
            case GL20.GL_FLOAT_MAT3, GL21.GL_FLOAT_MAT3x2, GL21.GL_FLOAT_MAT3x4 -> 3;
            case GL20.GL_FLOAT_MAT4, GL21.GL_FLOAT_MAT4x2, GL21.GL_FLOAT_MAT4x3 -> 4;
            default -> 0;
        };
    }

    public void getUniform(P p, int location, long dst, boolean asFloat) {
        if (p == null || !p.linked || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
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
            if (storesDoubles(u)) {
                final int cols = doubleColumns(u.glType), rows = doubleRows(u.glType);
                for (int c = 0; c < cols; c++) {
                    for (int r = 0; r < rows; r++) {
                        final double v = MemoryUtil.memGetDouble(src + (long) c * u.matrixStride[s] + r * 8L);
                        final long to = dst + (long) (c * rows + r) * 4;
                        if (asFloat) MemoryUtil.memPutFloat(to, (float) v);
                        else MemoryUtil.memPutInt(to, (int) Math.round(v));
                    }
                }
                return;
            }
            final int comps = components(u.glType);
            final boolean isFloat = scalarKind(u.glType) == 0;
            final int columns = matrixColumns(u.glType), rows = columns == 0 ? 0 : comps / columns;
            final int matrixStride = u.matrixStride[s];
            final boolean rowMajor = u.rowMajor[s];
            for (int c = 0; c < comps; c++) {
                final long in = columns == 0 ? src + c * 4L
                    : rowMajor ? src + (long) (c % rows) * matrixStride + (c / rows) * 4L : src + (long) (c / rows) * matrixStride + (c % rows) * 4L;
                if (asFloat) MemoryUtil.memPutFloat(dst + c * 4L, isFloat ? MemoryUtil.memGetFloat(in) : MemoryUtil.memGetInt(in));
                else MemoryUtil.memPutInt(dst + c * 4L, isFloat ? Math.round(MemoryUtil.memGetFloat(in)) : MemoryUtil.memGetInt(in));
            }
            return;
        }
    }

    public static int components(int type) {
        if (isDoubleType(type)) return doubleColumns(type) * doubleRows(type);
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
            case GL41.GL_PROGRAM_SEPARABLE -> p.linkedSeparable ? GL11.GL_TRUE : GL11.GL_FALSE;
            case COMPLETION_STATUS -> GL11.GL_TRUE;
            case GL20.GL_VALIDATE_STATUS -> p.linked ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_INFO_LOG_LENGTH -> p.log.isEmpty() ? 0 : p.log.length() + 1;
            case GL20.GL_ATTACHED_SHADERS -> p.attached.size();
            case GL20.GL_ACTIVE_UNIFORMS -> p.activeUniformCount();
            case GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH -> {
                int max = 0;
                for (int i = 0; i < p.activeUniformCount(); i++) {
                    final ProgramBase.Uniform u = p.activeUniform(i);
                    max = Math.max(max, u.name.length() + (u.arraySize > 1 ? 3 : 0) + 1);
                }
                yield max;
            }
            case GL20.GL_ACTIVE_ATTRIBUTES -> p.attribs.size();
            case GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH -> {
                int max = 0;
                for (P.Attrib a : p.attribs) max = Math.max(max, a.name.length() + 1);
                yield max;
            }
            case GL31.GL_ACTIVE_UNIFORM_BLOCKS -> p.ubos.size();
            case GL42.GL_ACTIVE_ATOMIC_COUNTER_BUFFERS -> (int) p.ssbos.stream().filter(b -> b.atomicCounters).count();
            case GL31.GL_ACTIVE_UNIFORM_BLOCK_MAX_NAME_LENGTH -> {
                int max = 0;
                for (P.BufferBlock b : p.ubos) max = Math.max(max, b.name.length() + 1);
                yield max;
            }
            case GL43.GL_COMPUTE_WORK_GROUP_SIZE -> p.localX;
            case GL40.GL_TESS_CONTROL_OUTPUT_VERTICES -> p.stageModes.tessOutputVertices;
            case GL40.GL_TESS_GEN_MODE -> p.stageModes.tessMode;
            case GL40.GL_TESS_GEN_SPACING -> p.stageModes.tessSpacing;
            case GL40.GL_TESS_GEN_VERTEX_ORDER -> p.stageModes.tessOrder;
            case GL40.GL_TESS_GEN_POINT_MODE -> p.stageModes.tessPointMode ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL32.GL_GEOMETRY_VERTICES_OUT, GEOMETRY_LINKED_VERTICES_OUT -> p.stageModes.geometryVerticesOut;
            case GL32.GL_GEOMETRY_INPUT_TYPE, GEOMETRY_LINKED_INPUT_TYPE -> p.stageModes.geometryInput;
            case GL32.GL_GEOMETRY_OUTPUT_TYPE, GEOMETRY_LINKED_OUTPUT_TYPE -> p.stageModes.geometryOutput;
            case GL40.GL_GEOMETRY_SHADER_INVOCATIONS -> p.stageModes.geometryInvocations;
            default -> 0;
        };
    }

    /** GL_COMPLETION_STATUS_ARB, always true: compiles and links finish before the call that starts them returns. */
    public static final int COMPLETION_STATUS = 0x91B1;

    public int shaderi(Shader s, int pname) {
        if (s == null) return 0;
        return switch (pname) {
            case GL20.GL_SHADER_TYPE -> s.type;
            case GL20.GL_COMPILE_STATUS -> s.compiled ? GL11.GL_TRUE : GL11.GL_FALSE;
            case COMPLETION_STATUS -> GL11.GL_TRUE;
            case GL46.GL_SPIR_V_BINARY -> s.spirv != null ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_DELETE_STATUS -> s.deletePending ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL20.GL_INFO_LOG_LENGTH -> s.log.isEmpty() ? 0 : s.log.length() + 1;
            case GL20.GL_SHADER_SOURCE_LENGTH -> s.source.length() + 1;
            default -> 0;
        };
    }

    public static String activeName(ProgramBase.Uniform u) {
        return u.isArray() ? u.name + "[0]" : u.name;
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
