package com.gtnewhorizons.angelica.vulkan.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gtnewhorizons.angelica.vulkan.shader.Spirv.*;

public final class StageReflection {
    public final Spirv spirv;

    public final List<Io> inputs = new ArrayList<>();
    public final List<Io> outputs = new ArrayList<>();
    public final List<Member> defaultBlock = new ArrayList<>();
    public int defaultBlockVar = -1;
    public int defaultBlockSize;
    public final List<Resource> resources = new ArrayList<>();
    public int localSizeX = 1, localSizeY = 1, localSizeZ = 1;

    public static final class Io {
        public final int varId;
        public final String name;
        public final String key;
        public final int location;
        public final int locations;
        public final int scalarKind;
        public final int components;

        Io(int varId, String name, String key, int location, int locations, int scalarKind, int components) {
            this.varId = varId;
            this.name = name;
            this.key = key;
            this.location = location;
            this.locations = locations;
            this.scalarKind = scalarKind;
            this.components = components;
        }
    }

    public static final class Member {
        public final String name;
        public final int offset;
        public final int glType;
        public final int arraySize;
        public final int arrayStride;
        public final int matrixStride;
        public final boolean rowMajor;

        Member(String name, int offset, int glType, int arraySize, int arrayStride, int matrixStride, boolean rowMajor) {
            this.name = name;
            this.offset = offset;
            this.glType = glType;
            this.arraySize = arraySize;
            this.arrayStride = arrayStride;
            this.matrixStride = matrixStride;
            this.rowMajor = rowMajor;
        }
    }

    public enum Kind { COMBINED_SAMPLER, SAMPLED_IMAGE, STORAGE_IMAGE, SAMPLER, UNIFORM_BUFFER, STORAGE_BUFFER }

    public static final class Resource {
        public final int varId;
        public final Kind kind;
        public final String name;
        public final int count;
        public final int explicitBinding;
        public final int glType;
        public final int dim;
        public final boolean arrayed;
        public final boolean shadow;
        public final int blockSize;
        public final boolean readOnly;
        public final boolean writeOnly;
        public final int imageFormat;

        Resource(int varId, Kind kind, String name, int count, int explicitBinding, int glType, int dim, boolean arrayed, boolean shadow, int blockSize, boolean readOnly, boolean writeOnly, int imageFormat) {
            this.varId = varId;
            this.kind = kind;
            this.name = name;
            this.count = count;
            this.explicitBinding = explicitBinding;
            this.glType = glType;
            this.dim = dim;
            this.arrayed = arrayed;
            this.shadow = shadow;
            this.blockSize = blockSize;
            this.readOnly = readOnly;
            this.writeOnly = writeOnly;
            this.imageFormat = imageFormat;
        }
    }

    private final java.util.Map<String, Integer> explicitBindings;

    public StageReflection(Spirv spirv, Set<String> boolUniforms) {
        this(spirv, boolUniforms, java.util.Map.of());
    }

    public StageReflection(Spirv spirv, Set<String> boolUniforms, java.util.Map<String, Integer> explicitBindings) {
        this.spirv = spirv;
        this.explicitBindings = explicitBindings;
        for (Spirv.Variable v : spirv.variables) {
            switch (v.storageClass) {
                case SC_INPUT, SC_OUTPUT -> {
                    if (spirv.isBuiltinVariable(v)) continue;
                    final Type t = spirv.pointee(v);
                    final Type et = spirv.elementType(t);
                    final String name = spirv.name(v.id);
                    final boolean block = et != null && et.op == OP_TYPE_STRUCT && spirv.hasDecoration(et.id, DEC_BLOCK);
                    final String key = block ? "block:" + spirv.name(et.id) : name;
                    final int loc = spirv.decoration(v.id, DEC_LOCATION, -1);
                    final Io io = new Io(v.id, name, key, loc, spirv.locationCount(t), spirv.scalarKind(t), spirv.componentCount(t));
                    (v.storageClass == SC_INPUT ? inputs : outputs).add(io);
                }
                case SC_UNIFORM, SC_STORAGE_BUFFER -> reflectBuffer(v, boolUniforms);
                case SC_UNIFORM_CONSTANT -> reflectOpaque(v);
                default -> { }
            }
        }
    }

    private void reflectBuffer(Spirv.Variable v, Set<String> boolUniforms) {
        final Type t = spirv.pointee(v);
        final Type block = spirv.elementType(t);
        if (block == null || block.op != OP_TYPE_STRUCT) return;
        final String blockName = spirv.name(block.id);
        final boolean ssbo = v.storageClass == SC_STORAGE_BUFFER || spirv.hasDecoration(block.id, DEC_BUFFER_BLOCK);
        if (!ssbo && "gl_DefaultUniformBlock".equals(blockName)) {
            defaultBlockVar = v.id;
            flatten(block, "", 0, boolUniforms, true);
            defaultBlockSize = structSize(block);
            return;
        }
        final int binding = explicitBindings.getOrDefault(blockName, -1);
        final boolean readOnly = ssbo && allMembers(block, DEC_NON_WRITABLE);
        final boolean writeOnly = ssbo && allMembers(block, DEC_NON_READABLE);
        resources.add(new Resource(v.id, ssbo ? Kind.STORAGE_BUFFER : Kind.UNIFORM_BUFFER, blockName, spirv.flatArraySize(t), binding, 0, 0, false, false, structSize(block), readOnly, writeOnly, 0));
    }

    private boolean allMembers(Type struct, int decoration) {
        if (struct.operands.length == 0) return false;
        for (int m = 0; m < struct.operands.length; m++) {
            if (!spirv.hasMemberDecoration(struct.id, m, decoration)) return false;
        }
        return true;
    }

    private void reflectOpaque(Spirv.Variable v) {
        final Type t = spirv.pointee(v);
        final Type et = spirv.elementType(t);
        if (et == null) return;
        final int count = spirv.flatArraySize(t);
        final String name = spirv.name(v.id);
        final int binding = explicitBindings.getOrDefault(name, -1);
        switch (et.op) {
            case OP_TYPE_SAMPLED_IMAGE -> {
                final Type img = spirv.type(et.operands[0]);
                resources.add(image(v.id, Kind.COMBINED_SAMPLER, name, count, binding, img, true));
            }
            case OP_TYPE_IMAGE -> {
                final boolean storage = et.operands[5] == 2;
                resources.add(image(v.id, storage ? Kind.STORAGE_IMAGE : Kind.SAMPLED_IMAGE, name, count, binding, et, !storage));
            }
            case OP_TYPE_SAMPLER -> resources.add(new Resource(v.id, Kind.SAMPLER, name, count, binding, 0, 0, false, false, 0, false, false, 0));
            default -> { }
        }
    }

    private Resource image(int varId, Kind kind, String name, int count, int binding, Type img, boolean sampled) {
        final int dim = img.operands[1];
        final boolean depth = img.operands[2] == 1;
        final boolean arrayed = img.operands[3] == 1;
        final boolean ms = img.operands[4] == 1;
        final int kindOfScalar = spirv.scalarKind(spirv.type(img.operands[0]));
        final boolean ro = spirv.hasDecoration(varId, DEC_NON_WRITABLE);
        final boolean wo = spirv.hasDecoration(varId, DEC_NON_READABLE);
        final int glType = sampled ? samplerGlType(dim, depth, arrayed, ms, kindOfScalar) : imageGlType(dim, arrayed, ms, kindOfScalar);
        return new Resource(varId, kind, name, count, binding, glType, dim, arrayed, depth, 0, ro, wo, img.operands[6]);
    }

    private void flatten(Type struct, String prefix, int base, Set<String> boolUniforms, boolean topLevel) {
        for (int m = 0; m < struct.operands.length; m++) {
            final String memberName = spirv.memberName(struct.id, m);
            final int offset = base + spirv.memberDecoration(struct.id, m, DEC_OFFSET, 0);
            final Type mt = spirv.type(struct.operands[m]);
            final String name = prefix + memberName;
            final boolean rowMajor = spirv.hasMemberDecoration(struct.id, m, DEC_ROW_MAJOR);
            final int matrixStride = spirv.memberDecoration(struct.id, m, DEC_MATRIX_STRIDE, 16);
            flattenValue(mt, name, offset, boolUniforms, topLevel && boolUniforms.contains(memberName), rowMajor, matrixStride);
        }
    }

    private void flattenValue(Type t, String name, int offset, Set<String> boolUniforms, boolean wasBool, boolean rowMajor, int matrixStride) {
        if (t.op == OP_TYPE_ARRAY) {
            final Type elem = spirv.type(t.operands[0]);
            final int len = Math.max(1, spirv.arrayLength(t));
            final int stride = spirv.decoration(t.id, DEC_ARRAY_STRIDE, 16);
            if (elem.op == OP_TYPE_STRUCT || elem.op == OP_TYPE_ARRAY) {
                for (int i = 0; i < len; i++) flattenValue(elem, name + "[" + i + "]", offset + i * stride, boolUniforms, false, rowMajor, matrixStride);
                return;
            }
            defaultBlock.add(new Member(name, offset, glType(elem, wasBool), len, stride, matrixStride, rowMajor));
            return;
        }
        if (t.op == OP_TYPE_STRUCT) {
            flatten(t, name + ".", offset, boolUniforms, false);
            return;
        }
        defaultBlock.add(new Member(name, offset, glType(t, wasBool), 1, 0, matrixStride, rowMajor));
    }

    int structSize(Type struct) {
        int size = 0;
        for (int m = 0; m < struct.operands.length; m++) {
            final int off = spirv.memberDecoration(struct.id, m, DEC_OFFSET, 0);
            final Type mt = spirv.type(struct.operands[m]);
            final int matrixStride = spirv.memberDecoration(struct.id, m, DEC_MATRIX_STRIDE, 16);
            final boolean rowMajor = spirv.hasMemberDecoration(struct.id, m, DEC_ROW_MAJOR);
            size = Math.max(size, off + sizeOf(mt, matrixStride, rowMajor));
        }
        return size;
    }

    private int sizeOf(Type t, int matrixStride, boolean rowMajor) {
        return switch (t.op) {
            case OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT -> t.op == OP_TYPE_BOOL ? 4 : t.operands[0] / 8;
            case OP_TYPE_VECTOR -> t.operands[1] * sizeOf(spirv.type(t.operands[0]), 0, false);
            case OP_TYPE_MATRIX -> {
                final Type col = spirv.type(t.operands[0]);
                final int cols = t.operands[1];
                final int rows = col.operands[1];
                yield rowMajor ? rows * matrixStride : cols * matrixStride;
            }
            case OP_TYPE_ARRAY -> Math.max(1, spirv.arrayLength(t)) * spirv.decoration(t.id, DEC_ARRAY_STRIDE, 16);
            case OP_TYPE_RUNTIME_ARRAY -> 0;
            case OP_TYPE_STRUCT -> structSize(t);
            default -> 4;
        };
    }

    int glType(Type t, boolean wasBool) {
        switch (t.op) {
            case OP_TYPE_FLOAT:
                return t.operands[0] == 64 ? GL11.GL_DOUBLE : GL11.GL_FLOAT;
            case OP_TYPE_INT:
                if (wasBool) return GL20.GL_BOOL;
                return t.operands[1] != 0 ? GL11.GL_INT : GL11.GL_UNSIGNED_INT;
            case OP_TYPE_BOOL:
                return GL20.GL_BOOL;
            case OP_TYPE_VECTOR: {
                final Type c = spirv.type(t.operands[0]);
                final int n = t.operands[1];
                if (c.op == OP_TYPE_FLOAT) return n == 2 ? GL20.GL_FLOAT_VEC2 : n == 3 ? GL20.GL_FLOAT_VEC3 : GL20.GL_FLOAT_VEC4;
                if (c.op == OP_TYPE_BOOL || wasBool) return n == 2 ? GL20.GL_BOOL_VEC2 : n == 3 ? GL20.GL_BOOL_VEC3 : GL20.GL_BOOL_VEC4;
                if (c.operands[1] != 0) return n == 2 ? GL20.GL_INT_VEC2 : n == 3 ? GL20.GL_INT_VEC3 : GL20.GL_INT_VEC4;
                return n == 2 ? GL30.GL_UNSIGNED_INT_VEC2 : n == 3 ? GL30.GL_UNSIGNED_INT_VEC3 : GL30.GL_UNSIGNED_INT_VEC4;
            }
            case OP_TYPE_MATRIX: {
                final int cols = t.operands[1];
                final int rows = spirv.type(t.operands[0]).operands[1];
                if (cols == rows) return cols == 2 ? GL20.GL_FLOAT_MAT2 : cols == 3 ? GL20.GL_FLOAT_MAT3 : GL20.GL_FLOAT_MAT4;
                if (cols == 2) return rows == 3 ? GL21.GL_FLOAT_MAT2x3 : GL21.GL_FLOAT_MAT2x4;
                if (cols == 3) return rows == 2 ? GL21.GL_FLOAT_MAT3x2 : GL21.GL_FLOAT_MAT3x4;
                return rows == 2 ? GL21.GL_FLOAT_MAT4x2 : GL21.GL_FLOAT_MAT4x3;
            }
            default:
                return GL11.GL_FLOAT;
        }
    }

    static final int DIM_1D = 0, DIM_2D = 1, DIM_3D = 2, DIM_CUBE = 3, DIM_RECT = 4, DIM_BUFFER = 5;

    static int samplerGlType(int dim, boolean shadow, boolean arrayed, boolean ms, int scalar) {
        if (scalar == 1 || scalar == 2) {
            final boolean u = scalar == 2;
            return switch (dim) {
                case DIM_1D -> arrayed ? (u ? GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY : GL30.GL_INT_SAMPLER_1D_ARRAY) : (u ? GL30.GL_UNSIGNED_INT_SAMPLER_1D : GL30.GL_INT_SAMPLER_1D);
                case DIM_3D -> u ? GL30.GL_UNSIGNED_INT_SAMPLER_3D : GL30.GL_INT_SAMPLER_3D;
                case DIM_CUBE -> u ? GL30.GL_UNSIGNED_INT_SAMPLER_CUBE : GL30.GL_INT_SAMPLER_CUBE;
                case DIM_BUFFER -> u ? GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER : GL31.GL_INT_SAMPLER_BUFFER;
                case DIM_RECT -> u ? GL31.GL_UNSIGNED_INT_SAMPLER_2D_RECT : GL31.GL_INT_SAMPLER_2D_RECT;
                default -> arrayed ? (u ? GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY : GL30.GL_INT_SAMPLER_2D_ARRAY) : (u ? GL30.GL_UNSIGNED_INT_SAMPLER_2D : GL30.GL_INT_SAMPLER_2D);
            };
        }
        return switch (dim) {
            case DIM_1D -> arrayed ? (shadow ? GL30.GL_SAMPLER_1D_ARRAY_SHADOW : GL30.GL_SAMPLER_1D_ARRAY) : (shadow ? GL20.GL_SAMPLER_1D_SHADOW : GL20.GL_SAMPLER_1D);
            case DIM_3D -> GL20.GL_SAMPLER_3D;
            case DIM_CUBE -> arrayed ? (shadow ? GL40.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW : GL40.GL_SAMPLER_CUBE_MAP_ARRAY) : (shadow ? GL30.GL_SAMPLER_CUBE_SHADOW : GL20.GL_SAMPLER_CUBE);
            case DIM_BUFFER -> GL31.GL_SAMPLER_BUFFER;
            case DIM_RECT -> shadow ? GL31.GL_SAMPLER_2D_RECT_SHADOW : GL31.GL_SAMPLER_2D_RECT;
            default -> arrayed ? (shadow ? GL30.GL_SAMPLER_2D_ARRAY_SHADOW : GL30.GL_SAMPLER_2D_ARRAY) : (shadow ? GL20.GL_SAMPLER_2D_SHADOW : GL20.GL_SAMPLER_2D);
        };
    }

    static int imageGlType(int dim, boolean arrayed, boolean ms, int scalar) {
        final boolean i = scalar == 1, u = scalar == 2;
        return switch (dim) {
            case DIM_1D -> i ? GL42.GL_INT_IMAGE_1D : u ? GL42.GL_UNSIGNED_INT_IMAGE_1D : GL42.GL_IMAGE_1D;
            case DIM_3D -> i ? GL42.GL_INT_IMAGE_3D : u ? GL42.GL_UNSIGNED_INT_IMAGE_3D : GL42.GL_IMAGE_3D;
            case DIM_CUBE -> i ? GL42.GL_INT_IMAGE_CUBE : u ? GL42.GL_UNSIGNED_INT_IMAGE_CUBE : GL42.GL_IMAGE_CUBE;
            case DIM_BUFFER -> i ? GL42.GL_INT_IMAGE_BUFFER : u ? GL42.GL_UNSIGNED_INT_IMAGE_BUFFER : GL42.GL_IMAGE_BUFFER;
            default -> arrayed ? (i ? GL42.GL_INT_IMAGE_2D_ARRAY : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D_ARRAY : GL42.GL_IMAGE_2D_ARRAY)
                : (i ? GL42.GL_INT_IMAGE_2D : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D : GL42.GL_IMAGE_2D);
        };
    }

    void readLocalSize() {
        final int[] w = spirv.words;
        int i = 5;
        while (i < w.length) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (op == OP_EXECUTION_MODE && w[i + 2] == 17 /* LocalSize */) {
                localSizeX = w[i + 3];
                localSizeY = w[i + 4];
                localSizeZ = w[i + 5];
            }
            i += wc;
        }
    }
}
