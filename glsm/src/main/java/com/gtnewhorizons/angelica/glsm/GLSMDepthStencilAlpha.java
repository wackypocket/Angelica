package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredAlphaHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredDepthColorHandler;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.stacks.AlphaStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.DepthStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.StencilStateStack;
import com.gtnewhorizons.angelica.glsm.states.AlphaState;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordError;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.beforeUncapturedStateChange;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.rejectExitCapture;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMDepthStencilAlpha {

    private GLSMDepthStencilAlpha() {}

    public static boolean isEffectiveDepthMaskEnabled() {
        return ctx().depthState.isEffectiveMaskEnabled();
    }

    public static boolean isEffectiveAlphaTestEnabled() {
        return ctx().alphaTest.isEffectivelyEnabled();
    }

    public static AlphaState getEffectiveAlphaState(AlphaState out) {
        return ctx().alphaState.readEffective(out);
    }

    public static void glDepthFunc(int func) {
        if (!RENDER_BACKEND.checks().depthFunc(func)) return;
        final GLContextState glCtx = ctx();
        glCtx.exitCapture.write(ExitStateCapture.DEPTH_FUNC);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDepthFunc(func);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (!caching || func != glCtx.depthState.getFunc()) {
            mod(glCtx.depthState);
            glCtx.depthState.setFunc(func);
            RENDER_BACKEND.depthFunc(func);
        }
    }

    public static void glDepthMask(boolean mask) {
        final GLContextState glCtx = ctx();
        glCtx.exitCapture.writeDepthColor(ExitStateCapture.DEPTH_MASK);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDepthMask(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredDepthColorHandler dch = GLSMHooks.depthColorHandler;
        if (dch != null && dch.isDepthColorLocked()) {
            mod(glCtx.depthState);
            dch.deferDepthEnable(mask);
            return;
        }
        final boolean caching = isCachingEnabled();
        if (!caching || mask != glCtx.depthState.isEnabled()) {
            mod(glCtx.depthState);
            glCtx.depthState.setEnabled(mask);
            RENDER_BACKEND.depthMask(mask);
        }
    }

    public static void glClearDepth(double depth) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearDepth(depth);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        depth = Math.min(1.0, Math.max(0.0, depth));
        mod(glCtx.depthState);
        if (isCachingEnabled()) glCtx.depthState.setClearValue(depth);
        RENDER_BACKEND.clearDepth(depth);
    }

    public static void enableAlphaTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_ALPHA_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        enableAlphaTest(ctx());
    }

    static void enableAlphaTest(GLContextState glCtx) {
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            glCtx.exitCapture.capability(GL11.GL_ALPHA_TEST, -1);
            glCtx.alphaTest.beforeModify();
            if (ah.deferAlphaTestToggle(true) && GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
            return;
        }
        final boolean wasEnabled = glCtx.alphaTest.isEnabled();
        glCtx.alphaTest.enable();
        if (glCtx.alphaTest.isEnabled() != wasEnabled) {
            glCtx.fragmentGeneration++;
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
    }

    public static void disableAlphaTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_ALPHA_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        disableAlphaTest(ctx());
    }

    static void disableAlphaTest(GLContextState glCtx) {
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            glCtx.exitCapture.capability(GL11.GL_ALPHA_TEST, -1);
            glCtx.alphaTest.beforeModify();
            if (ah.deferAlphaTestToggle(false) && GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
            return;
        }
        final boolean wasEnabled = glCtx.alphaTest.isEnabled();
        glCtx.alphaTest.disable();
        if (glCtx.alphaTest.isEnabled() != wasEnabled) {
            glCtx.fragmentGeneration++;
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
    }

    public static void glAlphaFunc(int function, float reference) {
        if (function < GL11.GL_NEVER || function > GL11.GL_ALWAYS) {
            recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        final GLContextState glCtx = ctx();
        glCtx.exitCapture.writeAlpha();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordAlphaFunc(function, reference);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            mod(glCtx.alphaState);
            if (ah.deferAlphaFunc(function, reference) && GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
            return;
        }
        if (isCachingEnabled()
            && (function != glCtx.alphaState.getFunction() || Float.compare(reference, glCtx.alphaState.getReference()) != 0)) {
            mod(glCtx.alphaState);
            glCtx.alphaState.setFunction(function);
            glCtx.alphaState.setReference(reference);
            glCtx.fragmentGeneration++;
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
    }

    public static void enableDepthTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_DEPTH_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        enableDepthTest(ctx());
    }

    static void enableDepthTest(GLContextState glCtx) {
        glCtx.depthTest.enable();
    }

    public static void disableDepthTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_DEPTH_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        disableDepthTest(ctx());
    }

    static void disableDepthTest(GLContextState glCtx) {
        glCtx.depthTest.disable();
    }

    public static void glDepthRange(double near, double far) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled() || glCtx.viewportState.depthRangeNear != near || glCtx.viewportState.depthRangeFar != far) beforeUncapturedStateChange();
        if (isCachingEnabled()) mod(glCtx.viewportState).setDepthRange(near, far);
        RENDER_BACKEND.depthRange(near, far);
    }

    /** Aims the one-sided stencil calls at a face, for backends taking EXT_stencil_two_side; 0 leaves them applying to both. */
    public static void setStencilFace(int face) {
        ctx().stencilFace = face;
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        if (!RENDER_BACKEND.checks().stencilFunc(func)) return;
        if (ctx().stencilFace != 0) {
            glStencilFuncSeparate(ctx().stencilFace, func, ref, mask);
            return;
        }
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilFunc(func, ref, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (RENDER_BACKEND.emulatesGL()) ref = clampStencilRef(glCtx, ref);
        final boolean caching = isCachingEnabled();
        if (!caching || glCtx.stencilState.getFuncFront() != func || glCtx.stencilState.getRefFront() != ref || glCtx.stencilState.getValueMaskFront() != mask
            || glCtx.stencilState.getFuncBack() != func || glCtx.stencilState.getRefBack() != ref || glCtx.stencilState.getValueMaskBack() != mask) {
            beforeUncapturedStateChange();
            mod(glCtx.stencilState).setFunc(func, ref, mask);
            RENDER_BACKEND.stencilFunc(func, ref, mask);
        }
    }

    public static void glStencilMask(int mask) {
        if (ctx().stencilFace != 0) {
            glStencilMaskSeparate(ctx().stencilFace, mask);
            return;
        }
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilMask(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (!caching || glCtx.stencilState.getWriteMaskFront() != mask || glCtx.stencilState.getWriteMaskBack() != mask) {
            beforeUncapturedStateChange();
            mod(glCtx.stencilState).setWriteMask(mask);
            RENDER_BACKEND.stencilMask(mask);
        }
    }

    public static void glStencilOp(int fail, int zfail, int zpass) {
        if (!RENDER_BACKEND.checks().stencilOp(fail, zfail, zpass)) return;
        if (ctx().stencilFace != 0) {
            glStencilOpSeparate(ctx().stencilFace, fail, zfail, zpass);
            return;
        }
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilOp(fail, zfail, zpass);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (!caching || glCtx.stencilState.getFailOpFront() != fail || glCtx.stencilState.getZFailOpFront() != zfail || glCtx.stencilState.getZPassOpFront() != zpass
            || glCtx.stencilState.getFailOpBack() != fail || glCtx.stencilState.getZFailOpBack() != zfail || glCtx.stencilState.getZPassOpBack() != zpass) {
            beforeUncapturedStateChange();
            mod(glCtx.stencilState).setOp(fail, zfail, zpass);
            RENDER_BACKEND.stencilOp(fail, zfail, zpass);
        }
    }

    /** GL clamps the reference to the stencil range, where a native API masks it instead. */
    private static int clampStencilRef(GLContextState glCtx, int ref) {
        return Math.max(0, Math.min(ref, stencilBits(glCtx)));
    }

    private static int stencilBits(GLContextState glCtx) {
        return glCtx.stencilBitMask == 0xFFFFFFFF ? 0xFF : glCtx.stencilBitMask;
    }

    public static void glClearStencil(int s) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearStencil(s);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        final int kept = s & stencilBits(glCtx);
        if (!caching || glCtx.stencilState.getClearValue() != kept) {
            mod(glCtx.stencilState).setClearValue(kept);
            RENDER_BACKEND.clearStencil(s);
        }
    }

    public static void glStencilFuncSeparate(int face, int func, int ref, int mask) {
        if (!RENDER_BACKEND.checks().stencilFuncSeparate(face, func)) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilFuncSeparate(face, func, ref, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        beforeUncapturedStateChange();
        if (RENDER_BACKEND.emulatesGL()) ref = clampStencilRef(glCtx, ref);
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = glCtx.stencilState.getFuncFront() != func || glCtx.stencilState.getRefFront() != ref || glCtx.stencilState.getValueMaskFront() != mask;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = glCtx.stencilState.getFuncBack() != func || glCtx.stencilState.getRefBack() != ref || glCtx.stencilState.getValueMaskBack() != mask;
            }
        }
        if (needsUpdate) {
            mod(glCtx.stencilState);
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                glCtx.stencilState.setFuncFront(func);
                glCtx.stencilState.setRefFront(ref);
                glCtx.stencilState.setValueMaskFront(mask);
            }
            if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                glCtx.stencilState.setFuncBack(func);
                glCtx.stencilState.setRefBack(ref);
                glCtx.stencilState.setValueMaskBack(mask);
            }
            RENDER_BACKEND.stencilFuncSeparate(face, func, ref, mask);
        }
    }

    public static void glStencilMaskSeparate(int face, int mask) {
        if (!RENDER_BACKEND.checks().stencilMaskSeparate(face)) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilMaskSeparate(face, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        beforeUncapturedStateChange();
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = glCtx.stencilState.getWriteMaskFront() != mask;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = glCtx.stencilState.getWriteMaskBack() != mask;
            }
        }
        if (needsUpdate) {
            mod(glCtx.stencilState);
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                glCtx.stencilState.setWriteMaskFront(mask);
            }
            if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                glCtx.stencilState.setWriteMaskBack(mask);
            }
            RENDER_BACKEND.stencilMaskSeparate(face, mask);
        }
    }

    public static void glStencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        if (!RENDER_BACKEND.checks().stencilOpSeparate(face, sfail, dpfail, dppass)) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilOpSeparate(face, sfail, dpfail, dppass);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        beforeUncapturedStateChange();
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = glCtx.stencilState.getFailOpFront() != sfail || glCtx.stencilState.getZFailOpFront() != dpfail || glCtx.stencilState.getZPassOpFront() != dppass;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = glCtx.stencilState.getFailOpBack() != sfail || glCtx.stencilState.getZFailOpBack() != dpfail || glCtx.stencilState.getZPassOpBack() != dppass;
            }
        }
        if (needsUpdate) {
            mod(glCtx.stencilState);
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                glCtx.stencilState.setFailOpFront(sfail);
                glCtx.stencilState.setZFailOpFront(dpfail);
                glCtx.stencilState.setZPassOpFront(dppass);
            }
            if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                glCtx.stencilState.setFailOpBack(sfail);
                glCtx.stencilState.setZFailOpBack(dpfail);
                glCtx.stencilState.setZPassOpBack(dppass);
            }
            RENDER_BACKEND.stencilOpSeparate(face, sfail, dpfail, dppass);
        }
    }

    public static DepthStateStack getDepthState() {
        return ctx().depthState;
    }

    public static BooleanStateStack getDepthTest() {
        return ctx().depthTest;
    }

    public static AlphaStateStack getAlphaState() {
        return ctx().alphaState;
    }

    public static BooleanStateStack getAlphaTest() {
        return ctx().alphaTest;
    }

    public static BooleanStateStack getStencilTest() {
        return ctx().stencilTest;
    }

    public static StencilStateStack getStencilState() {
        return ctx().stencilState;
    }
}
