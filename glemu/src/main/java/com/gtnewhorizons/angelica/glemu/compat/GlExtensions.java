package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.shader.GlslIncludes;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.EXTDebugLabel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.gl;
import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/** The state behind the small vendor extensions that have an equivalent in core GL. */
public final class GlExtensions {

    private static final int STENCIL_TEST_TWO_SIDE = 0x8910;
    private static final Int2IntOpenHashMap BINDABLE_BINDINGS = new Int2IntOpenHashMap();
    /** One compiled variant per program and size, replaced when the program is relinked. */
    private record GroupSizeVariant(int generation, int program) {}

    private static final Map<String, GroupSizeVariant> GROUP_SIZE_VARIANTS = new HashMap<>();

    /** What GL keeps per context. Like GlState, each thread has its own. */
    private static final class State {
        boolean stencilTwoSide;
        int activeStencilFace = GL11.GL_FRONT;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GlExtensions() {}

    public static boolean setCapability(int cap, boolean on) {
        final State state = STATE.get();
        if (cap != STENCIL_TEST_TWO_SIDE) return false;
        state.stencilTwoSide = on;
        applyStencilFace();
        return true;
    }

    public static boolean isEnabled(int cap) {
        final State state = STATE.get();
        return cap == STENCIL_TEST_TWO_SIDE && state.stencilTwoSide;
    }

    static void activeStencilFace(int face) {
        final State state = STATE.get();
        state.activeStencilFace = face;
        applyStencilFace();
    }

    /** EXT_stencil_two_side aims the one-sided stencil calls at a face, which GLSM routes to their separate forms. */
    private static void applyStencilFace() {
        final State state = STATE.get();
        GLSMDepthStencilAlpha.setStencilFace(state.stencilTwoSide ? state.activeStencilFace : 0);
    }

    /** Each buffer gets one uniform buffer binding from the top of the range, shared by every program bound to it. */
    static void uniformBuffer(int program, int location, int buffer) {
        final int block = location - GlBase.BINDABLE_LOCATION_BASE;
        if (block < 0) return;
        final int binding = bindableBinding(buffer);
        rb().uniformBlockBinding(program, block, binding);
        rb().bindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, buffer);
    }

    private static int bindableBinding(int buffer) {
        if (BINDABLE_BINDINGS.containsKey(buffer)) return BINDABLE_BINDINGS.get(buffer);
        int binding = freeBindableBinding();
        if (binding < 0) {
            BINDABLE_BINDINGS.keySet().removeIf(b -> !rb().isBuffer(b));
            binding = freeBindableBinding();
        }
        if (binding < 0) {
            gl().warnOnce("more bindable uniform buffers are in use than there are bindings; the newest takes over the top binding");
            binding = GlState.MAX_BUFFER_BINDINGS - 1;
        }
        BINDABLE_BINDINGS.put(buffer, binding);
        return binding;
    }

    private static int freeBindableBinding() {
        for (int binding = GlState.MAX_BUFFER_BINDINGS - 1; binding >= 0; binding--) {
            if (!BINDABLE_BINDINGS.containsValue(binding)) return binding;
        }
        return -1;
    }

    static int uniformBufferSize(int program, int location) {
        final int block = location - GlBase.BINDABLE_LOCATION_BASE;
        return block < 0 ? 0 : ProgramInfo.block(program, block, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
    }

    /** Dispatches a variant of the program in use compiled for this local size, as a native compute pipeline fixes its local size when it is built. */
    static void dispatchGroupSize(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        final int program = GLStateManager.ctx().activeProgram;
        final ProgramBase<?> base = gl().programBase(program);
        if (base == null) return;
        final String key = program + ":" + sizeX + "," + sizeY + "," + sizeZ;
        GroupSizeVariant known = GROUP_SIZE_VARIANTS.get(key);
        if (known == null || known.generation != base.gen) {
            if (known != null) GLSMPrograms.glDeleteProgram(known.program);
            final int linked = GLSMPrograms.glCreateProgram();
            for (Shader source : base.linkedShaders) {
                GlEntryHelpers.attachCompiled(linked, source.type, GlslIncludes.withGroupSize(source.source, sizeX, sizeY, sizeZ));
            }
            GLSMPrograms.glLinkProgram(linked);
            known = new GroupSizeVariant(base.gen, linked);
            GROUP_SIZE_VARIANTS.put(key, known);
        }
        final int variant = known.program;
        gl().withUniformsFrom(variant, program, () -> {
            GLSMPrograms.glUseProgram(variant);
            GLSMPrograms.glDispatchCompute(x, y, z);
            GLSMPrograms.glUseProgram(program);
        });
    }

    static void pushMarker(int length, long marker) {
        rb().pushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, GlEntryHelpers.markerText(length, marker));
    }

    static void insertMarker(int length, long marker) {
        rb().debugMessageInsert(GL43.GL_DEBUG_SOURCE_APPLICATION, GL43.GL_DEBUG_TYPE_MARKER, 0, GL43.GL_DEBUG_SEVERITY_NOTIFICATION, GlEntryHelpers.markerText(length, marker));
    }

    /** EXT_debug_label's object types, which mostly name KHR_debug's identifiers differently. */
    static int labelIdentifier(int type) {
        return switch (type) {
            case EXTDebugLabel.GL_BUFFER_OBJECT_EXT -> GL43.GL_BUFFER;
            case EXTDebugLabel.GL_PROGRAM_OBJECT_EXT -> GL43.GL_PROGRAM;
            case EXTDebugLabel.GL_SHADER_OBJECT_EXT -> GL43.GL_SHADER;
            default -> type;
        };
    }

    static void clientAttribDefault(int mask) {
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0) {
            for (int cap : new int[] {GL11.GL_VERTEX_ARRAY, GL11.GL_NORMAL_ARRAY, GL11.GL_COLOR_ARRAY, GL11.GL_INDEX_ARRAY, GL11.GL_EDGE_FLAG_ARRAY, GL14.GL_FOG_COORDINATE_ARRAY, GL14.GL_SECONDARY_COLOR_ARRAY}) {
                GLSMVertexInput.glDisableClientState(cap);
            }
            final int was = GlEntryHelpers.useClientUnit(GL13.GL_TEXTURE0);
            for (int unit = 0; unit < GLStateManager.MAX_TEXTURE_COORDS; unit++) {
                GlEntryHelpers.useClientUnit(GL13.GL_TEXTURE0 + unit);
                GLSMVertexInput.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            }
            GlEntryHelpers.useClientUnit(was);
        }
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            for (int pname : new int[] {GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS}) {
                rb().pixelStorei(pname, 0);
            }
            rb().pixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            rb().pixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        }
    }
}
