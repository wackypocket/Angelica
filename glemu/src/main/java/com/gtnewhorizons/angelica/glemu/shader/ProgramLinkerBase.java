package com.gtnewhorizons.angelica.glemu.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.*;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public abstract class ProgramLinkerBase<P extends ProgramBase<P>> {
    protected abstract GpuCaps gpuCaps();

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

    protected void assignVertexInputs(P glProgram, P out, StageReflection vs, java.util.Set<String> explicitInputs) {
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
            out.inputKind[loc + i] = in.scalarKind < 0 ? 0 : (in.scalarKind == 3 ? 1 : in.scalarKind);
        }
        out.attribs.add(new ProgramBase.Attrib(in.name, loc, attribGlType(in), in.locations));
    }

    protected static int attribGlType(StageReflection.Io in) {
        final int n = in.locations > 1 ? in.components / in.locations : in.components;
        if (in.locations > 1 && in.scalarKind == 0) {
            return switch (in.locations) {
                case 2 -> GL20.GL_FLOAT_MAT2;
                case 3 -> GL20.GL_FLOAT_MAT3;
                default -> GL20.GL_FLOAT_MAT4;
            };
        }
        return switch (in.scalarKind) {
            case 1, 3 -> n == 1 ? GL11.GL_INT : n == 2 ? GL20.GL_INT_VEC2 : n == 3 ? GL20.GL_INT_VEC3 : GL20.GL_INT_VEC4;
            case 2 -> n == 1 ? GL11.GL_UNSIGNED_INT : n == 2 ? org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC2 : n == 3 ? org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC3 : org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC4;
            default -> n == 1 ? GL11.GL_FLOAT : n == 2 ? GL20.GL_FLOAT_VEC2 : n == 3 ? GL20.GL_FLOAT_VEC3 : GL20.GL_FLOAT_VEC4;
        };
    }

    protected void matchInterface(StageReflection producer, StageReflection consumer) {
        final Object2IntOpenHashMap<String> byKey = new Object2IntOpenHashMap<>();
        byKey.defaultReturnValue(-1);
        int next = 0;
        for (StageReflection.Io out : producer.outputs) {
            final int count = Math.max(1, out.locations);
            producer.spirv.setDecoration(out.varId, DEC_LOCATION, next);
            byKey.put(out.key, next);
            next += count;
        }
        for (StageReflection.Io in : consumer.inputs) {
            int loc = byKey.getInt(in.key);
            if (loc < 0) {
                loc = next;
                next += Math.max(1, in.locations);
            }
            consumer.spirv.setDecoration(in.varId, DEC_LOCATION, loc);
        }
    }

    protected void assignFragmentOutputs(P glProgram, P out, StageReflection fs) {
        int max = 0;
        for (StageReflection.Io o : fs.outputs) {
            int loc = glProgram.fragDataBindings.getInt(o.name);
            if (loc >= 0) fs.spirv.setDecoration(o.varId, DEC_LOCATION, loc);
            else loc = Math.max(0, o.location);
            max = Math.max(max, loc + Math.max(1, o.locations));
        }
        out.colorOutputs = Math.max(1, max);
    }

    protected void relieveSamplerPressure(Spirv[] spirv, StageReflection[] refl, ShaderCompiler.Compiled[] compiled, List<String> problems) {
        final int limit = gpuCaps().maxPerStageDescriptorSamplers;
        boolean over = false;
        for (StageReflection r : refl) over |= r != null && samplerSlots(r) > limit;
        if (!over) return;

        final java.util.Set<String> movable = new java.util.HashSet<>();
        final java.util.Set<String> pinned = new java.util.HashSet<>();
        for (int slot = 0; slot < ProgramBase.STAGES; slot++) {
            if (refl[slot] == null) continue;
            final java.util.Set<String> fetchOnly = spirv[slot].fetchOnlySamplers();
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

    protected static int samplerSlots(StageReflection r) {
        int n = 0;
        for (StageReflection.Resource res : r.resources) {
            if (res.kind == StageReflection.Kind.SAMPLER || res.kind == StageReflection.Kind.COMBINED_SAMPLER && res.dim != StageReflection.DIM_BUFFER) n += res.count;
        }
        return n;
    }
}
