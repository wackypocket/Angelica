package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;

import static com.gtnewhorizons.angelica.glemu.shader.Spirv.*;

public final class StageReflection {
    public final Spirv spirv;

    public final List<Io> inputs = new ArrayList<>();
    public final List<Io> outputs = new ArrayList<>();
    public final List<Member> defaultBlock = new ArrayList<>();
    private List<Member> flattenInto = defaultBlock;
    private boolean storageRules;
    private int topLevelSize = 1, topLevelStride;
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
        /** The size of the vectors it holds when they are doubles, or 0. */
        public final int doubleSize;
        /** The GL type of one element, and how many elements an array has, 1 for none. */
        public int elementGlType, elements = 1;

        Io(int varId, String name, String key, int location, int locations, int scalarKind, int components, int doubleSize) {
            this.varId = varId;
            this.name = name;
            this.key = key;
            this.location = location;
            this.locations = locations;
            this.scalarKind = scalarKind;
            this.components = components;
            this.doubleSize = doubleSize;
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
        /** For a storage block's variable: the top-level member's elements and their stride, when it is an array of structures or arrays. */
        public int topLevelSize = 1, topLevelStride;

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
        /** A uniform block's members, under the names GL gives them. */
        public List<Member> members = List.of();
        public final int explicitBinding;
        public final int glType;
        public final int dim;
        public final boolean arrayed;
        public final boolean shadow;
        public final int blockSize;
        public final boolean readOnly;

        Resource(int varId, Kind kind, String name, int count, int explicitBinding, int glType, int dim, boolean arrayed, boolean shadow, int blockSize, boolean readOnly) {
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
        }
    }

    private final Map<String, Integer> explicitBindings;

    private static final String ATOMIC_COUNTER_BLOCK = "gl_AtomicCounterBlock_";

    public static int atomicCounterBinding(String blockName) {
        if (blockName == null || !blockName.startsWith(ATOMIC_COUNTER_BLOCK)) return -1;
        try {
            return Integer.parseInt(blockName.substring(ATOMIC_COUNTER_BLOCK.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public StageReflection(Spirv spirv, Set<String> boolUniforms, Map<String, Integer> explicitBindings) {
        this.spirv = spirv;
        this.explicitBindings = explicitBindings;
        for (Spirv.Variable v : spirv.variables) {
            switch (v.storageClass) {
                case SC_INPUT, SC_OUTPUT -> {
                    if (spirv.isBuiltinVariable(v)) continue;
                    final Type t = spirv.pointee(v);
                    final Type et = spirv.elementType(t);
                    final String name = GlslVulkanPreprocess.sourceName(spirv.name(v.id));
                    final boolean block = et != null && et.op == OP_TYPE_STRUCT && spirv.hasDecoration(et.id, DEC_BLOCK);
                    final String key = block ? "block:" + spirv.name(et.id) : name;
                    final int loc = spirv.decoration(v.id, DEC_LOCATION, -1);
                    final Io io = new Io(v.id, name, key, loc, spirv.locationCount(t, v.storageClass == SC_INPUT && spirv.executionModel == 0), spirv.scalarKind(t), spirv.componentCount(t), spirv.doubleVectorSize(t));
                    final Type element = spirv.elementType(t);
                    if (element != null && element.op != OP_TYPE_STRUCT) io.elementGlType = glType(element, false);
                    io.elements = Math.max(1, spirv.flatArraySize(t));
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
        final String declaredName = spirv.name(block.id);
        final String instanceName = spirv.name(v.id);
        final boolean element = instanceName != null && instanceName.startsWith(Spirv.BLOCK_ELEMENT);
        final String elementTail = element ? instanceName.substring(instanceName.indexOf(Spirv.BLOCK_ELEMENT, Spirv.BLOCK_ELEMENT.length())) : "";
        final String blockName = element ? declaredName + elementTail.substring(Spirv.BLOCK_ELEMENT.length()) : declaredName;
        final boolean ssbo = v.storageClass == SC_STORAGE_BUFFER || spirv.hasDecoration(block.id, DEC_BUFFER_BLOCK);
        if (!ssbo && "gl_DefaultUniformBlock".equals(blockName)) {
            defaultBlockVar = v.id;
            reachedDefaultMembers = reachedMembers(v.id, block);
            flatten(block, "", 0, boolUniforms, true);
            defaultBlockSize = structSize(block);
            return;
        }
        final int declaredBinding = explicitBindings.getOrDefault(declaredName, atomicCounterBinding(declaredName));
        final int elementNumber = element ? Integer.parseInt(instanceName.substring(Spirv.BLOCK_ELEMENT.length(), instanceName.length() - elementTail.length())) : 0;
        final int binding = declaredBinding >= 0 ? declaredBinding + elementNumber : declaredBinding;
        final boolean readOnly = ssbo && allMembers(block, DEC_NON_WRITABLE);
        final List<Member> members = new ArrayList<>();
        flattenInto = members;
        storageRules = ssbo;
        // Every element of a block array shares its one set of members, named after the block and not an element.
        final boolean counters = atomicCounterBinding(declaredName) >= 0;
        flatten(block, counters || instanceName == null || instanceName.isEmpty() ? "" : declaredName + ".", 0, boolUniforms, true);
        storageRules = false;
        flattenInto = defaultBlock;
        final Resource buffer = new Resource(v.id, ssbo ? Kind.STORAGE_BUFFER : Kind.UNIFORM_BUFFER, blockName, spirv.flatArraySize(t), binding, 0, 0, false, false, structSize(block), readOnly);
        buffer.members = members;
        resources.add(buffer);
    }

    /** The default block's top-level members the stage's code reaches; null for every one, when the code takes the block other than member by member. */
    private Set<String> reachedDefaultMembers;

    /** Whether the stage's code reaches a uniform of its default block, which GL then calls referenced by the stage; declared but unused is not. */
    public boolean reaches(String uniform) {
        if (reachedDefaultMembers == null) return true;
        int end = uniform.length();
        final int dot = uniform.indexOf('.'), bracket = uniform.indexOf('[');
        if (dot >= 0) end = dot;
        if (bracket >= 0 && bracket < end) end = bracket;
        return reachedDefaultMembers.contains(uniform.substring(0, end));
    }

    private static final int OP_FUNCTION_CALL = 57, OP_COPY_MEMORY = 63;

    private Set<String> reachedMembers(int variable, Type block) {
        final Set<String> reached = new java.util.HashSet<>();
        final int[] w = spirv.words;
        for (int i = 5; i < w.length; ) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (wc == 0 || i + wc > w.length) return null;
            switch (op) {
                case OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN -> {
                    if (w[i + 3] == variable) {
                        if (wc < 5 || !spirv.constants.containsKey(w[i + 4])) return null;
                        reached.add(spirv.memberName(block.id, spirv.constants.get(w[i + 4])));
                    }
                }
                case OP_LOAD, OP_COPY_OBJECT, OP_PTR_ACCESS_CHAIN -> {
                    if (w[i + 3] == variable) return null;
                }
                case OP_COPY_MEMORY -> {
                    if (w[i + 1] == variable || w[i + 2] == variable) return null;
                }
                case OP_FUNCTION_CALL -> {
                    for (int k = 4; k < wc; k++) if (w[i + k] == variable) return null;
                }
                default -> { }
            }
            i += wc;
        }
        return reached;
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
                final boolean storage = et.operands[5] == IMAGE_SAMPLED_STORAGE;
                resources.add(image(v.id, storage ? Kind.STORAGE_IMAGE : Kind.SAMPLED_IMAGE, name, count, binding, et, !storage));
            }
            case OP_TYPE_SAMPLER -> resources.add(new Resource(v.id, Kind.SAMPLER, name, count, binding, 0, 0, false, false, 0, false));
            default -> { }
        }
    }

    private Resource image(int varId, Kind kind, String name, int count, int binding, Type img, boolean sampled) {
        final String imageName = spirv.name(img.id);
        final boolean bufferTexture = BUFFER_IMAGE_NAME.equals(imageName);
        final int dim = img.operands[4] == 1 ? DIM_MULTISAMPLE : RECT_IMAGE_NAME.equals(imageName) ? DIM_RECT : bufferTexture ? DIM_BUFFER_TEXTURE : img.operands[1];
        final boolean depth = img.operands[2] == 1;
        final boolean arrayed = img.operands[3] == 1;
        final int kindOfScalar = spirv.scalarKind(spirv.type(img.operands[0]));
        final boolean ro = spirv.hasDecoration(varId, DEC_NON_WRITABLE);
        final int glDim = bufferTexture ? DIM_BUFFER : dim;
        final int glType = sampled ? samplerGlType(glDim, depth, arrayed, kindOfScalar) : imageGlType(glDim, arrayed, kindOfScalar);
        return new Resource(varId, kind, name, count, binding, glType, dim, arrayed, depth, 0, ro);
    }

    private void flatten(Type struct, String prefix, int base, Set<String> boolUniforms, boolean topLevel) {
        for (int m = 0; m < struct.operands.length; m++) {
            final String memberName = spirv.memberName(struct.id, m);
            final int offset = base + spirv.memberDecoration(struct.id, m, DEC_OFFSET, 0);
            final Type mt = spirv.type(struct.operands[m]);
            final String name = prefix + memberName;
            final boolean rowMajor = spirv.hasMemberDecoration(struct.id, m, DEC_ROW_MAJOR);
            final int matrixStride = spirv.memberDecoration(struct.id, m, DEC_MATRIX_STRIDE, 16);
            // GL lists a storage block's top-level array of structures or arrays by its first element only.
            if (storageRules && topLevel && (mt.op == OP_TYPE_ARRAY || mt.op == OP_TYPE_RUNTIME_ARRAY)) {
                final Type element = spirv.type(mt.operands[0]);
                if (element.op == OP_TYPE_STRUCT || element.op == OP_TYPE_ARRAY) {
                    topLevelSize = mt.op == OP_TYPE_RUNTIME_ARRAY ? 0 : Math.max(1, spirv.arrayLength(mt));
                    topLevelStride = spirv.decoration(mt.id, DEC_ARRAY_STRIDE, 16);
                    flattenValue(element, name + "[0]", offset, boolUniforms, false, rowMajor, matrixStride);
                    topLevelSize = 1;
                    topLevelStride = 0;
                    continue;
                }
            }
            flattenValue(mt, name, offset, boolUniforms, topLevel && boolUniforms.contains(memberName) || boolUniforms.contains(spirv.name(struct.id) + "." + memberName), rowMajor, matrixStride);
        }
    }

    private void flattenValue(Type t, String name, int offset, Set<String> boolUniforms, boolean wasBool, boolean rowMajor, int matrixStride) {
        // Arrays of scalars or vectors stay one strided member; arrays of structs or arrays flatten element by element.
        if (t.op == OP_TYPE_ARRAY || t.op == OP_TYPE_RUNTIME_ARRAY) {
            final Type elem = spirv.type(t.operands[0]);
            final int len = t.op == OP_TYPE_RUNTIME_ARRAY ? 0 : Math.max(1, spirv.arrayLength(t));
            final int stride = spirv.decoration(t.id, DEC_ARRAY_STRIDE, 16);
            if (elem.op == OP_TYPE_STRUCT || elem.op == OP_TYPE_ARRAY) {
                for (int i = 0; i < len; i++) flattenValue(elem, name + "[" + i + "]", offset + i * stride, boolUniforms, false, rowMajor, matrixStride);
                return;
            }
            addMember(new Member(name, offset, glType(elem, wasBool), len, stride, elem.op == OP_TYPE_MATRIX ? matrixStride : 0, rowMajor));
            return;
        }
        if (t.op == OP_TYPE_STRUCT) {
            flatten(t, name + ".", offset, boolUniforms, false);
            return;
        }
        addMember(new Member(name, offset, glType(t, wasBool), 1, 0, t.op == OP_TYPE_MATRIX ? matrixStride : 0, rowMajor));
    }

    private void addMember(Member member) {
        member.topLevelSize = topLevelSize;
        member.topLevelStride = topLevelStride;
        flattenInto.add(member);
    }

    private static final int DEC_PATCH = 15, DEC_COMPONENT = 31, DEC_INDEX = 32;

    private static String builtinName(int builtin) {
        return switch (builtin) {
            case 0 -> "gl_Position";
            case 1 -> "gl_PointSize";
            case 3 -> "gl_ClipDistance";
            case 4 -> "gl_CullDistance";
            case 7 -> "gl_PrimitiveID";
            case 8 -> "gl_InvocationID";
            case 9 -> "gl_Layer";
            case 10 -> "gl_ViewportIndex";
            case 11 -> "gl_TessLevelOuter";
            case 12 -> "gl_TessLevelInner";
            case 13 -> "gl_TessCoord";
            case 14 -> "gl_PatchVerticesIn";
            case 15 -> "gl_FragCoord";
            case 16 -> "gl_PointCoord";
            case 17 -> "gl_FrontFacing";
            case 18 -> "gl_SampleID";
            case 19 -> "gl_SamplePosition";
            case 20 -> "gl_SampleMask";
            case 22 -> "gl_FragDepth";
            case 42 -> "gl_VertexID";
            case 43 -> "gl_InstanceID";
            default -> null;
        };
    }

    /** What a stage takes in or puts out under GL's names, with each array under its first element. */
    public List<ProgramBase.IoVariable> interfaceVariables(int slot, boolean input) {
        final List<ProgramBase.IoVariable> out = new ArrayList<>();
        final boolean perVertex = input ? slot == ProgramBase.TCS || slot == ProgramBase.TES || slot == ProgramBase.GS : slot == ProgramBase.TCS;
        for (Spirv.Variable v : spirv.variables) {
            if (v.storageClass != (input ? SC_INPUT : SC_OUTPUT)) continue;
            final boolean patch = spirv.hasDecoration(v.id, DEC_PATCH);
            Type t = spirv.pointee(v);
            if (perVertex && !patch && t.op == OP_TYPE_ARRAY) t = spirv.type(t.operands[0]);
            final int builtin = spirv.decoration(v.id, DEC_BUILTIN, -1);
            if (builtin >= 0) {
                final String name = builtinName(builtin);
                if (name != null) addInterface(out, name, t, -1, 0, 0, patch || builtin == 11 || builtin == 12, true, slot);
                continue;
            }
            final Type element = spirv.elementType(t);
            if (element != null && element.op == OP_TYPE_STRUCT && spirv.hasDecoration(element.id, DEC_BLOCK)) {
                final boolean builtinBlock = element.operands.length > 0 && spirv.hasMemberDecoration(element.id, 0, DEC_BUILTIN);
                final int blockLocation = spirv.decoration(v.id, DEC_LOCATION, -1);
                int running = Math.max(0, blockLocation);
                final int elements = Math.max(1, spirv.flatArraySize(t));
                for (int e = 0; e < elements; e++) {
                    for (int m = 0; m < element.operands.length; m++) {
                        final Type mt = spirv.type(element.operands[m]);
                        if (builtinBlock) {
                            final String member = builtinName(spirv.memberDecoration(element.id, m, DEC_BUILTIN, -1));
                            // GL names a member of the array gl_in or gl_out by its block.
                            if (member != null) addInterface(out, perVertex && !patch ? "gl_PerVertex." + member : member, mt, -1, 0, 0, patch, true, slot);
                            continue;
                        }
                        final String prefix = spirv.name(element.id) + (t.op == OP_TYPE_ARRAY ? "[" + e + "]" : "") + ".";
                        final int location = spirv.memberDecoration(element.id, m, DEC_LOCATION, running);
                        addInterface(out, prefix + spirv.memberName(element.id, m), mt, location, spirv.memberDecoration(element.id, m, DEC_COMPONENT, 0), 0, patch, false, slot);
                        running = location + spirv.locationCount(mt);
                    }
                    if (builtinBlock) break;
                }
                continue;
            }
            final String name = GlslVulkanPreprocess.sourceName(spirv.name(v.id));
            if (name == null || name.isEmpty() || name.startsWith("angelica_")) continue;
            addInterface(out, name, t, spirv.decoration(v.id, DEC_LOCATION, -1), spirv.decoration(v.id, DEC_COMPONENT, 0), spirv.decoration(v.id, DEC_INDEX, 0), patch, false, slot);
        }
        return out;
    }

    private void addInterface(List<ProgramBase.IoVariable> out, String name, Type t, int location, int component, int index, boolean patch, boolean builtin, int slot) {
        if (t.op == OP_TYPE_ARRAY) {
            final Type element = spirv.type(t.operands[0]);
            final int length = Math.max(1, spirv.arrayLength(t));
            if (element.op == OP_TYPE_STRUCT || element.op == OP_TYPE_ARRAY) {
                final int each = spirv.locationCount(element);
                for (int i = 0; i < length; i++) addInterface(out, name + "[" + i + "]", element, location < 0 ? -1 : location + i * each, component, index, patch, builtin, slot);
                return;
            }
            out.add(new ProgramBase.IoVariable(name + "[0]", glType(element, false), length, location, component, index, patch, builtin, slot));
            return;
        }
        if (t.op == OP_TYPE_STRUCT) {
            int running = location;
            for (int m = 0; m < t.operands.length; m++) {
                final Type member = spirv.type(t.operands[m]);
                addInterface(out, name + "." + spirv.memberName(t.id, m), member, running, 0, index, patch, builtin, slot);
                if (running >= 0) running += spirv.locationCount(member);
            }
            return;
        }
        out.add(new ProgramBase.IoVariable(name, glType(t, false), 1, location, component, index, patch, builtin, slot));
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
                if (c.op == OP_TYPE_FLOAT && c.operands[0] == 64) return n == 2 ? GL40.GL_DOUBLE_VEC2 : n == 3 ? GL40.GL_DOUBLE_VEC3 : GL40.GL_DOUBLE_VEC4;
                if (c.op == OP_TYPE_FLOAT) return n == 2 ? GL20.GL_FLOAT_VEC2 : n == 3 ? GL20.GL_FLOAT_VEC3 : GL20.GL_FLOAT_VEC4;
                if (c.op == OP_TYPE_BOOL || wasBool) return n == 2 ? GL20.GL_BOOL_VEC2 : n == 3 ? GL20.GL_BOOL_VEC3 : GL20.GL_BOOL_VEC4;
                if (c.operands[1] != 0) return n == 2 ? GL20.GL_INT_VEC2 : n == 3 ? GL20.GL_INT_VEC3 : GL20.GL_INT_VEC4;
                return n == 2 ? GL30.GL_UNSIGNED_INT_VEC2 : n == 3 ? GL30.GL_UNSIGNED_INT_VEC3 : GL30.GL_UNSIGNED_INT_VEC4;
            }
            case OP_TYPE_MATRIX: {
                final int cols = t.operands[1];
                final int rows = spirv.type(t.operands[0]).operands[1];
                if (spirv.type(spirv.type(t.operands[0]).operands[0]).operands[0] == 64) {
                    if (cols == rows) return cols == 2 ? GL40.GL_DOUBLE_MAT2 : cols == 3 ? GL40.GL_DOUBLE_MAT3 : GL40.GL_DOUBLE_MAT4;
                    if (cols == 2) return rows == 3 ? GL40.GL_DOUBLE_MAT2x3 : GL40.GL_DOUBLE_MAT2x4;
                    if (cols == 3) return rows == 2 ? GL40.GL_DOUBLE_MAT3x2 : GL40.GL_DOUBLE_MAT3x4;
                    return rows == 2 ? GL40.GL_DOUBLE_MAT4x2 : GL40.GL_DOUBLE_MAT4x3;
                }
                if (cols == rows) return cols == 2 ? GL20.GL_FLOAT_MAT2 : cols == 3 ? GL20.GL_FLOAT_MAT3 : GL20.GL_FLOAT_MAT4;
                if (cols == 2) return rows == 3 ? GL21.GL_FLOAT_MAT2x3 : GL21.GL_FLOAT_MAT2x4;
                if (cols == 3) return rows == 2 ? GL21.GL_FLOAT_MAT3x2 : GL21.GL_FLOAT_MAT3x4;
                return rows == 2 ? GL21.GL_FLOAT_MAT4x2 : GL21.GL_FLOAT_MAT4x3;
            }
            default:
                return GL11.GL_FLOAT;
        }
    }

    public static final int DIM_1D = 0, DIM_3D = 2, DIM_CUBE = 3, DIM_BUFFER = 5;
    /** No SPIR-V dimension: a samplerBuffer lowered to a 2D image, sampled from the unit's buffer texture binding. */
    public static final int DIM_BUFFER_TEXTURE = 7;
    /** No SPIR-V dimension either: two dimensions with several samples per texel. */
    public static final int DIM_MULTISAMPLE = 8;

    static int samplerGlType(int dim, boolean shadow, boolean arrayed, int scalar) {
        if (scalar == 1 || scalar == 2) {
            final boolean u = scalar == 2;
            return switch (dim) {
                case DIM_1D -> arrayed ? (u ? GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY : GL30.GL_INT_SAMPLER_1D_ARRAY) : (u ? GL30.GL_UNSIGNED_INT_SAMPLER_1D : GL30.GL_INT_SAMPLER_1D);
                case DIM_3D -> u ? GL30.GL_UNSIGNED_INT_SAMPLER_3D : GL30.GL_INT_SAMPLER_3D;
                case DIM_CUBE -> arrayed ? (u ? GL40.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY : GL40.GL_INT_SAMPLER_CUBE_MAP_ARRAY) : (u ? GL30.GL_UNSIGNED_INT_SAMPLER_CUBE : GL30.GL_INT_SAMPLER_CUBE);
                case DIM_BUFFER -> u ? GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER : GL31.GL_INT_SAMPLER_BUFFER;
                case DIM_MULTISAMPLE -> arrayed ? (u ? GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY : GL32.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY) : (u ? GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE : GL32.GL_INT_SAMPLER_2D_MULTISAMPLE);
                case DIM_RECT -> u ? GL31.GL_UNSIGNED_INT_SAMPLER_2D_RECT : GL31.GL_INT_SAMPLER_2D_RECT;
                default -> arrayed ? (u ? GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY : GL30.GL_INT_SAMPLER_2D_ARRAY) : (u ? GL30.GL_UNSIGNED_INT_SAMPLER_2D : GL30.GL_INT_SAMPLER_2D);
            };
        }
        return switch (dim) {
            case DIM_1D -> arrayed ? (shadow ? GL30.GL_SAMPLER_1D_ARRAY_SHADOW : GL30.GL_SAMPLER_1D_ARRAY) : (shadow ? GL20.GL_SAMPLER_1D_SHADOW : GL20.GL_SAMPLER_1D);
            case DIM_3D -> GL20.GL_SAMPLER_3D;
            case DIM_CUBE -> arrayed ? (shadow ? GL40.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW : GL40.GL_SAMPLER_CUBE_MAP_ARRAY) : (shadow ? GL30.GL_SAMPLER_CUBE_SHADOW : GL20.GL_SAMPLER_CUBE);
            case DIM_BUFFER -> GL31.GL_SAMPLER_BUFFER;
            case DIM_MULTISAMPLE -> arrayed ? GL32.GL_SAMPLER_2D_MULTISAMPLE_ARRAY : GL32.GL_SAMPLER_2D_MULTISAMPLE;
            case DIM_RECT -> shadow ? GL31.GL_SAMPLER_2D_RECT_SHADOW : GL31.GL_SAMPLER_2D_RECT;
            default -> arrayed ? (shadow ? GL30.GL_SAMPLER_2D_ARRAY_SHADOW : GL30.GL_SAMPLER_2D_ARRAY) : (shadow ? GL20.GL_SAMPLER_2D_SHADOW : GL20.GL_SAMPLER_2D);
        };
    }

    static int imageGlType(int dim, boolean arrayed, int scalar) {
        final boolean i = scalar == 1, u = scalar == 2;
        return switch (dim) {
            case DIM_1D -> arrayed ? (i ? GL42.GL_INT_IMAGE_1D_ARRAY : u ? GL42.GL_UNSIGNED_INT_IMAGE_1D_ARRAY : GL42.GL_IMAGE_1D_ARRAY)
                : (i ? GL42.GL_INT_IMAGE_1D : u ? GL42.GL_UNSIGNED_INT_IMAGE_1D : GL42.GL_IMAGE_1D);
            case DIM_3D -> i ? GL42.GL_INT_IMAGE_3D : u ? GL42.GL_UNSIGNED_INT_IMAGE_3D : GL42.GL_IMAGE_3D;
            case DIM_CUBE -> arrayed ? (i ? GL42.GL_INT_IMAGE_CUBE_MAP_ARRAY : u ? GL42.GL_UNSIGNED_INT_IMAGE_CUBE_MAP_ARRAY : GL42.GL_IMAGE_CUBE_MAP_ARRAY)
                : (i ? GL42.GL_INT_IMAGE_CUBE : u ? GL42.GL_UNSIGNED_INT_IMAGE_CUBE : GL42.GL_IMAGE_CUBE);
            case DIM_RECT -> i ? GL42.GL_INT_IMAGE_2D_RECT : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D_RECT : GL42.GL_IMAGE_2D_RECT;
            case DIM_MULTISAMPLE -> arrayed ? (i ? GL42.GL_INT_IMAGE_2D_MULTISAMPLE_ARRAY : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE_ARRAY : GL42.GL_IMAGE_2D_MULTISAMPLE_ARRAY)
                : (i ? GL42.GL_INT_IMAGE_2D_MULTISAMPLE : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE : GL42.GL_IMAGE_2D_MULTISAMPLE);
            case DIM_BUFFER -> i ? GL42.GL_INT_IMAGE_BUFFER : u ? GL42.GL_UNSIGNED_INT_IMAGE_BUFFER : GL42.GL_IMAGE_BUFFER;
            default -> arrayed ? (i ? GL42.GL_INT_IMAGE_2D_ARRAY : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D_ARRAY : GL42.GL_IMAGE_2D_ARRAY)
                : (i ? GL42.GL_INT_IMAGE_2D : u ? GL42.GL_UNSIGNED_INT_IMAGE_2D : GL42.GL_IMAGE_2D);
        };
    }

    public void readLocalSize() {
        final int[] w = spirv.words;
        int i = 5;
        while (i < w.length) {
            final int wc = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (op == OP_EXECUTION_MODE && w[i + 2] == EXECUTION_MODE_LOCAL_SIZE) {
                localSizeX = w[i + 3];
                localSizeY = w[i + 4];
                localSizeZ = w[i + 5];
            }
            i += wc;
        }
    }
}
