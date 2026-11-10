package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.*;

public abstract class ProgramLinkerBase<P extends ProgramBase<P>> {
    public static final int FIRST_RESOURCE_BINDING = 8;

    protected abstract P newProgram(int id);

    protected abstract boolean linkStaged(P program, List<Shader> shaders, P staged);

    /** Links into a staging program first, so a failure leaves the program as it was. */
    public final boolean link(P program, List<Shader> shaders) {
        final P staged = newProgram(program.id);
        boolean linked = false;
        try {
            linked = linkStaged(program, shaders, staged);
            return linked;
        } finally {
            if (!linked) staged.resetLinkResults();
        }
    }

    protected abstract GpuCaps gpuCaps();

    protected record Res(String key, StageReflection.Kind kind, String name, int count, int explicitBinding, int glType, int dim, boolean arrayed, boolean shadow, int blockSize) {}

    protected abstract int stageBit(int glType);

    protected static final class Stages {
        public final Shader[] byStage = new Shader[ProgramBase.STAGES];
        public boolean compute;
        public final Spirv[] spirv = new Spirv[ProgramBase.STAGES];
        public final StageReflection[] refl = new StageReflection[ProgramBase.STAGES];
        @SuppressWarnings("unchecked")
        public final Set<String>[] explicit = new Set[ProgramBase.STAGES];
        public final ShaderCompiler.Compiled[] compiled = new ShaderCompiler.Compiled[ProgramBase.STAGES];
    }

    /** Sorts the attached shaders by stage. On a problem, returns null with the program's log set. */
    protected final Stages collectStages(P p, List<Shader> shaders) {
        final Stages stages = new Stages();
        for (Shader s : shaders) {
            if (s.type == GL43.GL_COMPUTE_SHADER) stages.compute = true;
            final int slot = stageSlot(s.type);
            if (stages.byStage[slot] != null) {
                p.log = "multiple shaders of one stage attached; only one per stage is supported";
                return null;
            }
            stages.byStage[slot] = s;
        }
        if (!stages.compute && stages.byStage[ProgramBase.VS] == null && !p.separable) {
            p.log = "no vertex shader attached";
            return null;
        }
        return stages;
    }

    /** Compiles what still needs it and reflects every stage. On failure, returns false with the program's log set. */
    protected final boolean compileStages(P p, Stages stages) {
        final boolean hasGs = stages.byStage[ProgramBase.GS] != null, hasTes = stages.byStage[ProgramBase.TES] != null;
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            final Shader s = stages.byStage[slot];
            if (s == null) continue;
            final int fixup = stages.compute ? ShaderCompiler.DEPTH_NONE : depthFixupFor(s.type, hasGs, hasTes);
            ShaderCompiler.Compiled c = s.result;
            if (c == null || s.resultFixup != fixup) {
                final ShaderCompiler.Result r = ShaderCompiler.compile(s.source, s.type, "shader" + s.id, fixup);
                if (r.compiled() == null) {
                    p.log = "shader " + s.id + " failed to compile: " + r.log();
                    return false;
                }
                c = r.compiled();
            }
            stages.spirv[slot] = new Spirv(ByteBuffer.wrap(c.spirv()).order(ByteOrder.LITTLE_ENDIAN));
            stages.refl[slot] = new StageReflection(stages.spirv[slot], c.boolUniforms(), c.bindings());
            stages.explicit[slot] = c.explicitInputs();
            stages.compiled[slot] = c;
        }
        return true;
    }

    /** Lays out what passes between stages. Returns the problems found so far, for assignResources to add to. */
    protected final List<String> assignInterfaces(P p, P staged, Stages stages) {
        final List<String> problems = new ArrayList<>();
        final StageReflection[] refl = stages.refl;
        relieveSamplerPressure(stages.spirv, refl, stages.compiled, problems);
        staged.compute = stages.compute;
        if (stages.compute) {
            refl[ProgramBase.CS].readLocalSize();
            staged.localX = refl[ProgramBase.CS].localSizeX;
            staged.localY = refl[ProgramBase.CS].localSizeY;
            staged.localZ = refl[ProgramBase.CS].localSizeZ;
        } else {
            staged.vertexless = refl[ProgramBase.VS] == null;
            if (staged.vertexless && !p.separable && !p.pipelineComposite && (refl[ProgramBase.TCS] != null || refl[ProgramBase.TES] != null || refl[ProgramBase.GS] != null)) {
                problems.add("error: a program with a geometry or tessellation shader needs a vertex shader unless it is separable");
            }
            for (int slot : new int[] {ProgramBase.TCS, ProgramBase.TES, ProgramBase.GS}) {
                if (refl[slot] != null) staged.stageModes.read(slot, stages.spirv[slot]);
            }
            final int patch = staged.stageModes.tessOutputVertices;
            if (refl[ProgramBase.TCS] != null && (patch < 1 || patch > MAX_PATCH_VERTICES)) problems.add("error: a tessellation control shader's output patch must have 1 to " + MAX_PATCH_VERTICES + " vertices, not " + patch);
            if (!staged.vertexless) assignVertexInputs(p, staged, refl[ProgramBase.VS], stages.explicit[ProgramBase.VS]);
            int producer = staged.vertexless ? -1 : ProgramBase.VS;
            for (int slot : new int[] {ProgramBase.TCS, ProgramBase.TES, ProgramBase.GS, ProgramBase.FS}) {
                if (refl[slot] == null) continue;
                if (producer >= 0) {
                    // Stages linked into one program must agree; across a pipeline's programs a mismatch only leaves the values undefined.
                    final String mismatch = p.separable || p.pipelineComposite ? null : typeMismatch(refl[producer], refl[slot], stages.compiled[producer].explicitVaryings(), stages.compiled[slot].explicitVaryings());
                    if (mismatch != null) problems.add(mismatch);
                    matchInterface(refl[producer], refl[slot], stages.compiled[producer].explicitVaryings(), stages.compiled[slot].explicitVaryings());
                }
                producer = slot;
            }
            if (producer >= 0 && producer != ProgramBase.FS) placeLastOutputs(refl[producer], stages.compiled[producer].explicitVaryings());
            if (refl[ProgramBase.FS] != null) assignFragmentOutputs(p, staged, refl[ProgramBase.FS]);
            int first = -1, last = -1;
            for (int slot : new int[] {ProgramBase.VS, ProgramBase.TCS, ProgramBase.TES, ProgramBase.GS, ProgramBase.FS}) {
                if (refl[slot] == null) continue;
                if (first < 0) first = slot;
                last = slot;
            }
            if (first >= 0) {
                staged.programInputs.addAll(refl[first].interfaceVariables(first, true));
                staged.programOutputs.addAll(refl[last].interfaceVariables(last, false));
            }
        }
        return problems;
    }

    protected final void publishLink(P p, P staged) {
        p.compute = staged.compute;
        p.vertexless = staged.vertexless;
        p.localX = staged.localX;
        p.localY = staged.localY;
        p.localZ = staged.localZ;
        p.stageModes = staged.stageModes;
        System.arraycopy(staged.stageBits, 0, p.stageBits, 0, ProgramBase.STAGES);
        p.uniforms.addAll(staged.uniforms);
        p.locations.putAll(staged.locations);
        p.locationUniform = staged.locationUniform;
        p.locationElement = staged.locationElement;
        System.arraycopy(staged.blocks, 0, p.blocks, 0, ProgramBase.STAGES);
        p.ubos.addAll(staged.ubos);
        p.blockUniforms.addAll(staged.blockUniforms);
        p.ssbos.addAll(staged.ssbos);
        p.attribs.addAll(staged.attribs);
        p.programInputs.addAll(staged.programInputs);
        p.programOutputs.addAll(staged.programOutputs);
        p.vertexInputMask = staged.vertexInputMask;
        System.arraycopy(staged.inputKind, 0, p.inputKind, 0, p.inputKind.length);
        System.arraycopy(staged.inputDoubles, 0, p.inputDoubles, 0, p.inputDoubles.length);
        System.arraycopy(staged.secondHalfOf, 0, p.secondHalfOf, 0, p.secondHalfOf.length);
        p.colorOutputs = staged.colorOutputs;
        p.clipMask = staged.clipMask;
        p.linked = true;
        p.gen++;
        p.log = "";
    }

    protected abstract int bindingCount(P out);

    protected void beginResources(StageReflection[] refl) {
    }

    protected abstract void placeDefaultBlock(P out, ProgramBase.StageBlock block, StageReflection r, int slot, int stageBit);

    protected abstract void addBufferBinding(P out, boolean storage, int binding, int stageFlags, int blockIndex);

    protected abstract void addOpaqueBinding(P out, ProgramBase.BindingKind kind, Res res, int binding, int stageFlags);

    /** Points an opaque uniform's binding at its final index in the uniform list. */
    protected abstract void rebindOpaque(P out, int bindingIndex, int uniformIndex);

    protected final void assignResources(P out, Stages stages, List<String> problems) {
        final Shader[] byStage = stages.byStage;
        final StageReflection[] refl = stages.refl;
        beginResources(refl);
        final Object2ObjectLinkedOpenHashMap<String, ProgramBase.Uniform> merged = new Object2ObjectLinkedOpenHashMap<>();
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null || r.defaultBlockVar < 0) continue;
            final ProgramBase.StageBlock block = out.newBlock(slot, r.defaultBlockSize);
            block.binding = slot;
            placeDefaultBlock(out, block, r, slot, stageBit(byStage[slot].type));
            for (StageReflection.Member m : r.defaultBlock) {
                ProgramBase.Uniform u = merged.get(m.name);
                if (u == null) {
                    u = new ProgramBase.Uniform(GlslVulkanPreprocess.sourceName(m.name), m.glType, m.arraySize);
                    merged.put(m.name, u);
                }
                u.offset[slot] = m.offset;
                if (r.reaches(m.name)) u.reachedSlots |= 1 << slot;
                u.arrayStride[slot] = m.arrayStride;
                u.matrixStride[slot] = m.matrixStride;
                u.rowMajor[slot] = m.rowMajor;
            }
        }

        // Each distinct resource gets one binding shared by every stage declaring it; the SPIR-V is re-decorated to match.
        final Object2ObjectLinkedOpenHashMap<String, Res> resources = new Object2ObjectLinkedOpenHashMap<>();
        final Object2IntLinkedOpenHashMap<String> stageFlags = new Object2IntLinkedOpenHashMap<>();
        final Object2IntLinkedOpenHashMap<String> stageSlots = new Object2IntLinkedOpenHashMap<>();
        final java.util.Map<String, List<StageReflection.Member>> blockMembers = new java.util.HashMap<>();
        final Set<String> writable = new HashSet<>();
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null) continue;
            for (StageReflection.Resource res : r.resources) {
                final String key = res.kind.name() + ":" + res.name;
                if (r.spirv.referenced(res.varId)) stageSlots.put(key, stageSlots.getInt(key) | 1 << slot);
                blockMembers.putIfAbsent(key, res.members);
                // Any stage's declaration can be the one stating a binding.
                resources.merge(key, new Res(key, res.kind, res.name, res.count, res.explicitBinding, res.glType, res.dim, res.arrayed, res.shadow, res.blockSize),
                    (first, other) -> {
                        if (first.explicitBinding >= 0 && other.explicitBinding >= 0 && first.explicitBinding != other.explicitBinding) problems.add("'" + first.name + "' has different bindings in different stages");
                        return first.explicitBinding >= 0 || other.explicitBinding < 0 ? first : other;
                    });
                stageFlags.put(key, stageFlags.getInt(key) | stageBit(byStage[slot].type));
                if (!res.readOnly) writable.add(key);
            }
        }
        int binding = FIRST_RESOURCE_BINDING;
        final Object2IntOpenHashMap<String> bindingOf = new Object2IntOpenHashMap<>();
        for (Res res : resources.values()) {
            final int flags = stageFlags.getInt(res.key);
            final int b = binding++;
            bindingOf.put(res.key, b);
            switch (res.kind) {
                case UNIFORM_BUFFER -> {
                    final ProgramBase.BufferBlock bb = new ProgramBase.BufferBlock(GlslVulkanPreprocess.sourceName(res.name), res.blockSize, Math.max(0, res.explicitBinding));
                    bb.bindingIndex = bindingCount(out);
                    out.ubos.add(bb);
                    addBufferBinding(out, false, b, flags, out.ubos.size() - 1);
                    bb.stageSlots = stageSlots.getInt(res.key);
                    for (StageReflection.Member m : blockMembers.getOrDefault(res.key, List.of())) {
                        final String memberName = GlslVulkanPreprocess.sourceName(m.name);
                        final ProgramBase.Uniform earlierElement = res.name.indexOf('[') < 0 ? null : out.blockUniforms.stream().filter(u -> u.name.equals(memberName)).findFirst().orElse(null);
                        if (earlierElement != null) {
                            earlierElement.blockCount++;
                            continue;
                        }
                        final ProgramBase.Uniform member = new ProgramBase.Uniform(memberName, m.glType, m.arraySize);
                        member.blockIndex = out.ubos.size() - 1;
                        member.offset[0] = m.offset;
                        member.arrayStride[0] = m.arrayStride;
                        member.matrixStride[0] = m.matrixStride;
                        member.rowMajor[0] = m.rowMajor;
                        out.blockUniforms.add(member);
                    }
                    if (res.count > 1) problems.add("uniform block array '" + res.name + "' is not supported");
                }
                case STORAGE_BUFFER -> {
                    final ProgramBase.BufferBlock bb = new ProgramBase.BufferBlock(GlslVulkanPreprocess.sourceName(res.name), res.blockSize, Math.max(0, res.explicitBinding));
                    bb.bindingIndex = bindingCount(out);
                    bb.readOnly = !writable.contains(res.key);
                    bb.stageSlots = stageSlots.getInt(res.key);
                    bb.members = blockMembers.getOrDefault(res.key, List.of());
                    out.ssbos.add(bb);
                    if (bb.atomicCounters) {
                        int atomicBlock = 0;
                        for (ProgramBase.BufferBlock earlier : out.ssbos) if (earlier != bb && earlier.atomicCounters) atomicBlock++;
                        for (StageReflection.Member m : bb.members) {
                            final ProgramBase.Uniform counter = new ProgramBase.Uniform(m.name, GL42.GL_UNSIGNED_INT_ATOMIC_COUNTER, m.arraySize);
                            counter.atomicBuffer = atomicBlock;
                            counter.offset[0] = m.offset;
                            counter.arrayStride[0] = m.arrayStride;
                            out.blockUniforms.add(counter);
                        }
                    }
                    addBufferBinding(out, true, b, flags, out.ssbos.size() - 1);
                    if (res.count > 1) problems.add("storage block array '" + res.name + "' is not supported");
                }
                default -> {
                    final ProgramBase.BindingKind kind = switch (res.kind) {
                        case COMBINED_SAMPLER -> ProgramBase.BindingKind.COMBINED_SAMPLER;
                        case SAMPLED_IMAGE -> ProgramBase.BindingKind.SAMPLED_IMAGE;
                        case STORAGE_IMAGE -> ProgramBase.BindingKind.STORAGE_IMAGE;
                        default -> ProgramBase.BindingKind.SAMPLER;
                    };
                    final ProgramBase.Uniform u = new ProgramBase.Uniform(GlslVulkanPreprocess.sourceName(res.name), res.glType, res.count);
                    u.units = new int[res.count];
                    for (int i = 0; i < res.count; i++) u.units[i] = res.explicitBinding >= 0 ? res.explicitBinding + i : 0;
                    u.bindingIndex = bindingCount(out);
                    merged.put(res.name, u);
                    addOpaqueBinding(out, kind, res, b, flags);
                }
            }
        }
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            final StageReflection r = refl[slot];
            if (r == null) continue;
            for (StageReflection.Resource res : r.resources) {
                final int b = bindingOf.getInt(res.kind.name() + ":" + res.name);
                r.spirv.setDecoration(res.varId, Spirv.DEC_BINDING, b);
                r.spirv.setDecoration(res.varId, Spirv.DEC_DESCRIPTOR_SET, 0);
            }
        }

        final List<ProgramBase.Uniform> ordered = new ArrayList<>(merged.values());
        // Stable, so only the uniform a program never declared moves, past the end of what it can enumerate.
        ordered.sort(Comparator.comparing(u -> ProgramBase.undeclared(u.name)));
        final int location = assignUniformLocations(ordered, stages.compiled, problems);
        out.locationUniform = new int[location];
        Arrays.fill(out.locationUniform, -1);
        out.locationElement = new int[location];
        for (int i = 0; i < ordered.size(); i++) {
            final ProgramBase.Uniform u = ordered.get(i);
            out.uniforms.add(u);
            for (int e = 0; e < Math.max(1, u.arraySize); e++) {
                out.locationUniform[u.baseLocation + e] = i;
                out.locationElement[u.baseLocation + e] = e;
            }
            out.locations.put(u.name, u.baseLocation);
            if (u.arraySize >= 1) {
                for (int e = 0; e < u.arraySize; e++) out.locations.put(u.name + "[" + e + "]", u.baseLocation + e);
            }
            if (u.isOpaque()) rebindOpaque(out, u.bindingIndex, i);
        }
    }

    /**
     * Locations the source declares are kept and the rest fill the gaps; the members of a struct or array of arrays count up from
     * the location it declares. Returns how many there are, holes included.
     */
    private static int assignUniformLocations(List<ProgramBase.Uniform> uniforms, ShaderCompiler.Compiled[] compiled, List<String> problems) {
        final Map<String, Integer> declared = new HashMap<>();
        for (ShaderCompiler.Compiled c : compiled) if (c != null) declared.putAll(c.explicitUniforms());
        final Map<String, Integer> nextOfDeclared = new HashMap<>();
        final BitSet taken = new BitSet();
        final List<ProgramBase.Uniform> free = new ArrayList<>();
        for (ProgramBase.Uniform u : uniforms) {
            final int size = Math.max(1, u.arraySize);
            final String root = u.name.split("[.\\[]", 2)[0];
            if (!declared.containsKey(root)) {
                free.add(u);
                continue;
            }
            final int at = nextOfDeclared.getOrDefault(root, declared.get(root));
            nextOfDeclared.put(root, at + size);
            if (!taken.get(at, at + size).isEmpty()) {
                problems.add("uniform '" + u.name + "' overlaps the locations of another uniform");
                free.add(u);
                continue;
            }
            u.baseLocation = at;
            taken.set(at, at + size);
        }
        // Uniforms of our own go after the application's, whose limit they don't count against.
        free.sort(Comparator.comparing(u -> u.name.startsWith("angelica_")));
        int applicationLocations = taken.length();
        for (ProgramBase.Uniform u : free) {
            final int size = Math.max(1, u.arraySize);
            int at = taken.nextClearBit(0);
            while (!taken.get(at, at + size).isEmpty()) at = taken.nextClearBit(at + 1);
            u.baseLocation = at;
            taken.set(at, at + size);
            if (!u.name.startsWith("angelica_")) applicationLocations = taken.length();
        }
        if (applicationLocations > MAX_UNIFORM_LOCATIONS) problems.add("the program's uniforms take " + applicationLocations + " locations, over GL_MAX_UNIFORM_LOCATIONS");
        return taken.length();
    }

    private static final int MAX_UNIFORM_LOCATIONS = 1024;

    public static int stageSlot(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER, GL43.GL_COMPUTE_SHADER -> ProgramBase.VS;
            case GL40.GL_TESS_CONTROL_SHADER -> ProgramBase.TCS;
            case GL40.GL_TESS_EVALUATION_SHADER -> ProgramBase.TES;
            case GL32.GL_GEOMETRY_SHADER -> ProgramBase.GS;
            default -> ProgramBase.FS;
        };
    }

    public static int depthFixupFor(int glType, boolean hasGeometry, boolean hasTessEval) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> hasGeometry || hasTessEval ? ShaderCompiler.DEPTH_NONE : ShaderCompiler.DEPTH_AT_END_OF_MAIN;
            case GL40.GL_TESS_EVALUATION_SHADER -> hasGeometry ? ShaderCompiler.DEPTH_NONE : ShaderCompiler.DEPTH_AT_END_OF_MAIN;
            case GL32.GL_GEOMETRY_SHADER -> ShaderCompiler.DEPTH_AT_EMIT;
            default -> ShaderCompiler.DEPTH_NONE;
        };
    }

    protected void assignVertexInputs(P glProgram, P out, StageReflection vs, Set<String> explicitInputs) {
        // Explicit layout locations and glBindAttribLocation win; the remaining inputs take the lowest free slots, matrices spanning several.
        int used = 0;
        final List<StageReflection.Io> pending = new ArrayList<>();
        for (StageReflection.Io in : vs.inputs) {
            int loc = -1;
            if (explicitInputs.contains(in.name) && in.location >= 0) loc = in.location;
            else if (glProgram.attribBindings.getInt(in.name) >= 0) loc = glProgram.attribBindings.getInt(in.name);
            if (loc < 0) {
                pending.add(in);
                continue;
            }
            place(out, vs, in, loc);
            used |= mask(loc, in.locations);
        }
        for (StageReflection.Io in : pending) {
            int loc = 0;
            while (loc < 32 && (used & mask(loc, in.locations)) != 0) loc++;
            place(out, vs, in, loc);
            used |= mask(loc, in.locations);
        }
        placeSecondHalves(out);
    }

    /** Each vector of three or four doubles reads its second half from a location of its own past GL's, in the order of theirs. */
    protected static void placeSecondHalves(ProgramBase<?> out) {
        int next = 0;
        for (int loc = 0; loc < 16; loc++) {
            if (out.inputDoubles[loc] <= 2) continue;
            final int location = DoubleLowering.HIGH_LOCATIONS + next;
            if (location < 32) {
                out.vertexInputMask |= 1 << location;
                out.inputKind[location] = 2;
                out.secondHalfOf[next] = loc;
            }
            next++;
        }
    }

    protected static int mask(int loc, int n) {
        int m = 0;
        for (int i = 0; i < n && loc + i < 32; i++) m |= 1 << (loc + i);
        return m;
    }

    protected void place(P out, StageReflection vs, StageReflection.Io in, int loc) {
        vs.spirv.setDecoration(in.varId, DEC_LOCATION, loc);
        for (int i = 0; i < in.locations && loc + i < 16; i++) {
            out.vertexInputMask |= 1 << (loc + i);
            out.inputKind[loc + i] = in.doubleSize > 0 ? 2 : in.scalarKind < 0 ? 0 : (in.scalarKind == 3 ? 1 : in.scalarKind);
            out.inputDoubles[loc + i] = in.doubleSize;
        }
        final ProgramBase.Attrib attrib = new ProgramBase.Attrib(in.name, loc, in.elementGlType != 0 ? in.elementGlType : attribGlType(in), in.locations);
        attrib.size = in.elements;
        attrib.elementLocations = Math.max(1, in.locations / in.elements);
        out.attribs.add(attrib);
    }

    protected static int attribGlType(StageReflection.Io in) {
        final int n = in.locations > 1 ? in.components / in.locations : in.components;
        if (in.locations > 1 && in.scalarKind == 0 && n > 1) {
            return switch (in.locations) {
                case 2 -> GL20.GL_FLOAT_MAT2;
                case 3 -> GL20.GL_FLOAT_MAT3;
                default -> GL20.GL_FLOAT_MAT4;
            };
        }
        return switch (in.scalarKind) {
            case 1, 3 -> n == 1 ? GL11.GL_INT : n == 2 ? GL20.GL_INT_VEC2 : n == 3 ? GL20.GL_INT_VEC3 : GL20.GL_INT_VEC4;
            case 2 -> n == 1 ? GL11.GL_UNSIGNED_INT : n == 2 ? GL30.GL_UNSIGNED_INT_VEC2 : n == 3 ? GL30.GL_UNSIGNED_INT_VEC3 : GL30.GL_UNSIGNED_INT_VEC4;
            default -> n == 1 ? GL11.GL_FLOAT : n == 2 ? GL20.GL_FLOAT_VEC2 : n == 3 ? GL20.GL_FLOAT_VEC3 : GL20.GL_FLOAT_VEC4;
        };
    }

    /** With nothing after it a stage's outputs match nothing, but those it left unplaced still cannot share a location with those it placed. */
    private static void placeLastOutputs(StageReflection stage, Map<String, Integer> explicit) {
        final java.util.BitSet taken = new java.util.BitSet();
        for (StageReflection.Io out : stage.outputs) {
            if (explicit.containsKey(out.name)) taken.set(out.location, out.location + Math.max(1, out.locations));
        }
        int next = 0;
        for (StageReflection.Io out : stage.outputs) {
            if (explicit.containsKey(out.name) || out.location < 0) continue;
            final int count = Math.max(1, out.locations);
            while (taken.nextSetBit(next) >= 0 && taken.nextSetBit(next) < next + count) next = taken.nextClearBit(taken.nextSetBit(next));
            stage.spirv.setDecoration(out.varId, DEC_LOCATION, next);
            taken.set(next, next + count);
            next += count;
        }
    }

    /** What GL_MAX_PATCH_VERTICES answers. */
    static final int MAX_PATCH_VERTICES = 32;

    /** An output and the input of the same name, neither placed by location, whose element types differ. */
    static String typeMismatch(StageReflection producer, StageReflection consumer, Map<String, Integer> producerExplicit, Map<String, Integer> consumerExplicit) {
        for (StageReflection.Io in : consumer.inputs) {
            if (in.elementGlType == 0 || consumerExplicit.containsKey(in.name)) continue;
            for (StageReflection.Io out : producer.outputs) {
                if (!out.key.equals(in.key) || out.elementGlType == 0 || producerExplicit.containsKey(out.name)) continue;
                if (out.elementGlType != in.elementGlType) return "error: " + in.name + " is an input of a different type from the output it reads";
            }
        }
        return null;
    }

    public static void matchInterface(StageReflection producer, StageReflection consumer, Map<String, Integer> producerExplicit, Map<String, Integer> consumerExplicit) {
        final Object2IntOpenHashMap<String> byKey = new Object2IntOpenHashMap<>();
        byKey.defaultReturnValue(-1);
        final Int2IntOpenHashMap byLocation = new Int2IntOpenHashMap();
        byLocation.defaultReturnValue(-1);
        int next = 0;
        for (StageReflection.Io out : producer.outputs) {
            final int count = Math.max(1, out.locations);
            producer.spirv.setDecoration(out.varId, DEC_LOCATION, next);
            byKey.put(out.key, next);
            final Integer declared = producerExplicit.get(out.name);
            if (declared != null) byLocation.put(declared.intValue(), next);
            next += count;
        }
        for (StageReflection.Io in : consumer.inputs) {
            final Integer declared = consumerExplicit.get(in.name);
            int loc = declared != null ? byLocation.get(declared.intValue()) : -1;
            if (loc < 0) loc = byKey.getInt(in.key);
            if (loc < 0) {
                loc = next;
                next += Math.max(1, in.locations);
            }
            consumer.spirv.setDecoration(in.varId, DEC_LOCATION, loc);
        }
    }

    protected void assignFragmentOutputs(P glProgram, P out, StageReflection fs) {
        int max = 0;
        final java.util.BitSet taken = new java.util.BitSet();
        final String source = glProgram.stageSources.get(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER);
        // An output a shader placed keeps its place; the compiler can give one it did not place the same, which the linker settles.
        for (StageReflection.Io o : fs.outputs) {
            if (glProgram.fragDataBindings.getInt(o.name) < 0 && placedInSource(source, o.name)) taken.set(Math.max(0, o.location), Math.max(0, o.location) + Math.max(1, o.locations));
        }
        for (StageReflection.Io o : fs.outputs) {
            int loc = glProgram.fragDataBindings.getInt(o.name);
            if (loc >= 0) {
                fs.spirv.setDecoration(o.varId, DEC_LOCATION, loc);
            } else {
                loc = Math.max(0, o.location);
                if (!placedInSource(source, o.name) && taken.get(loc)) {
                    loc = taken.nextClearBit(loc);
                    fs.spirv.setDecoration(o.varId, DEC_LOCATION, loc);
                }
                taken.set(loc, loc + Math.max(1, o.locations));
            }
            max = Math.max(max, loc + Math.max(1, o.locations));
        }
        out.colorOutputs = Math.max(1, max);
    }

    private static boolean placedInSource(String source, String name) {
        return source != null && java.util.regex.Pattern.compile("\\blayout\\s*\\([^)]*\\blocation\\b[^)]*\\)[^;]*\\b" + java.util.regex.Pattern.quote(name) + "\\b").matcher(source).find();
    }

    protected void relieveSamplerPressure(Spirv[] spirv, StageReflection[] refl, ShaderCompiler.Compiled[] compiled, List<String> problems) {
        // Over the device's sampler limit, samplers only ever used with texelFetch are demoted to plain images, which need no sampler slot.
        final int limit = gpuCaps().maxPerStageDescriptorSamplers;
        boolean over = false;
        for (StageReflection r : refl) over |= r != null && samplerSlots(r) > limit;
        if (!over) return;

        final Set<String> movable = new HashSet<>();
        final Set<String> pinned = new HashSet<>();
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            if (refl[slot] == null) continue;
            final Set<String> fetchOnly = spirv[slot].fetchOnlySamplers();
            for (StageReflection.Resource res : refl[slot].resources) {
                if (res.kind == StageReflection.Kind.COMBINED_SAMPLER) (fetchOnly.contains(res.name) ? movable : pinned).add(res.name);
            }
        }
        movable.removeAll(pinned);
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            if (refl[slot] == null || movable.isEmpty()) continue;
            final byte[] demoted = spirv[slot].demoteSamplers(movable);
            if (demoted == null) continue;
            spirv[slot] = new Spirv(ByteBuffer.wrap(demoted).order(ByteOrder.LITTLE_ENDIAN));
            refl[slot] = new StageReflection(spirv[slot], compiled[slot].boolUniforms(), compiled[slot].bindings());
        }
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            if (refl[slot] != null && samplerSlots(refl[slot]) > limit) {
                problems.add("stage " + slot + " samples " + samplerSlots(refl[slot]) + " textures with filtering; the device allows " + limit);
            }
        }
    }

    /** Buffer textures count too: they are lowered to combined 2D images, each with a sampler. */
    protected static int samplerSlots(StageReflection r) {
        int n = 0;
        for (StageReflection.Resource res : r.resources) {
            if (res.kind == StageReflection.Kind.SAMPLER || res.kind == StageReflection.Kind.COMBINED_SAMPLER) n += res.count;
        }
        return n;
    }
}
