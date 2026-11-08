package com.gtnewhorizons.angelica.glsm;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordError;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.beforeUncapturedStateChange;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.guardUnsupportedFFP;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.rejectExitCapture;

public final class GLSMTexEnvGen {

    private GLSMTexEnvGen() {}

    /** The pnames each glTexEnv target accepts. */
    private static boolean texEnvParameter(int target, int pname) {
        if (target == GL14.GL_TEXTURE_FILTER_CONTROL && pname == GL14.GL_TEXTURE_LOD_BIAS) return true;
        if (target == GL20.GL_POINT_SPRITE && pname == GL20.GL_COORD_REPLACE) return true;
        if (target == GL11.GL_TEXTURE_ENV) {
            switch (pname) {
                case GL11.GL_TEXTURE_ENV_MODE, GL11.GL_TEXTURE_ENV_COLOR, GL13.GL_COMBINE_RGB, GL13.GL_COMBINE_ALPHA, GL13.GL_RGB_SCALE, GL11.GL_ALPHA_SCALE,
                     GL13.GL_SOURCE0_RGB, GL13.GL_SOURCE1_RGB, GL13.GL_SOURCE2_RGB, GL13.GL_SOURCE0_ALPHA, GL13.GL_SOURCE1_ALPHA, GL13.GL_SOURCE2_ALPHA,
                     GL13.GL_OPERAND0_RGB, GL13.GL_OPERAND1_RGB, GL13.GL_OPERAND2_RGB, GL13.GL_OPERAND0_ALPHA, GL13.GL_OPERAND1_ALPHA, GL13.GL_OPERAND2_ALPHA -> {
                    return true;
                }
                default -> {
                }
            }
        }
        recordError(GL11.GL_INVALID_ENUM);
        return false;
    }

    public static void glTexEnvi(int target, int pname, int param) {
        handleTexEnvScalar(target, pname, param);
    }

    public static void glTexEnvf(int target, int pname, float param) {
        handleTexEnvScalar(target, pname, param);
    }

    public static void glTexEnv(int target, int pname, FloatBuffer params) {
        rejectExitCapture();
        if (!texEnvParameter(target, pname)) return;
        final GLContextState glCtx = ctx();
        if (target == GL11.GL_TEXTURE_ENV && pname == GL11.GL_TEXTURE_ENV_COLOR && params.remaining() >= 4) {
            beforeUncapturedStateChange();
            final int pos = params.position();
            final var envState = glCtx.textures.getTexEnvState(glCtx.activeTextureUnit.getValue());
            envState.beforeModify();
            envState.envColorR = params.get(pos);
            envState.envColorG = params.get(pos + 1);
            envState.envColorB = params.get(pos + 2);
            envState.envColorA = params.get(pos + 3);
            glCtx.fragmentGeneration++;
            return;
        }
        if (target == GL14.GL_TEXTURE_FILTER_CONTROL) {
            if (RENDER_BACKEND.isNative()) {
                handleTexEnvScalar(target, pname, params.get(params.position()));
                return;
            }
            guardUnsupportedFFP(
                    "glTexEnv",
                    "LOD bias via buffer glTexEnv is not supported; use glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, value) instead.");
            return;
        }
        // Silently ignore other buffer glTexEnv calls (some mods pass combine params via buffers)
    }

    public static void glTexEnv(int target, int pname, IntBuffer params) {
        rejectExitCapture();
        if (!texEnvParameter(target, pname)) return;
        if (target == GL11.GL_TEXTURE_ENV) {
            handleTexEnvScalar(target, pname, (float) params.get(params.position()));
        }
    }

    /**
     * Scalar glTexEnvi/glTexEnvf dispatch. glTexEnv is removed in core profile. GL_TEXTURE_FILTER_CONTROL (LOD bias) is remapped to glTexParameterf. All
     * GL_TEXTURE_ENV parameters including GL_COMBINE sub-params are tracked in per-unit TexEnvState.
     */
    private static void handleTexEnvScalar(int target, int pname, float param) {
        if (!texEnvParameter(target, pname)) return;
        if (pname == GL11.GL_TEXTURE_ENV_MODE && param != GL11.GL_MODULATE && param != GL11.GL_DECAL && param != GL11.GL_BLEND && param != GL11.GL_REPLACE
            && param != GL11.GL_ADD && param != GL13.GL_COMBINE) {
            recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        beforeUncapturedStateChange();
        final GLContextState glCtx = ctx();
        if (target == GL14.GL_TEXTURE_FILTER_CONTROL) {
            // LOD bias via legacy glTexEnv path - remap to core-profile glTexParameterf
            GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, param);
            return;
        }
        if (target != GL11.GL_TEXTURE_ENV) return;

        final var envState = glCtx.textures.getTexEnvState(glCtx.activeTextureUnit.getValue());
        envState.beforeModify();
        final int iparam = (int) param;

        switch (pname) {
            case GL11.GL_TEXTURE_ENV_MODE -> {
                envState.mode = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_COMBINE_RGB -> {
                envState.combineRgb = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_COMBINE_ALPHA -> {
                envState.combineAlpha = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_SOURCE0_RGB -> {
                envState.sourceRgb[0] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_SOURCE1_RGB -> {
                envState.sourceRgb[1] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_SOURCE2_RGB -> {
                envState.sourceRgb[2] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_SOURCE0_ALPHA -> {
                envState.sourceAlpha[0] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_SOURCE1_ALPHA -> {
                envState.sourceAlpha[1] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_SOURCE2_ALPHA -> {
                envState.sourceAlpha[2] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_OPERAND0_RGB -> {
                envState.operandRgb[0] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_OPERAND1_RGB -> {
                envState.operandRgb[1] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_OPERAND2_RGB -> {
                envState.operandRgb[2] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_OPERAND0_ALPHA -> {
                envState.operandAlpha[0] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_OPERAND1_ALPHA -> {
                envState.operandAlpha[1] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_OPERAND2_ALPHA -> {
                envState.operandAlpha[2] = iparam;
                glCtx.fragmentGeneration++;
            }
            case GL13.GL_RGB_SCALE -> {
                envState.scaleRgb = param;
                glCtx.fragmentGeneration++;
            }
            case GL11.GL_ALPHA_SCALE -> {
                envState.scaleAlpha = param;
                glCtx.fragmentGeneration++;
            }
            // Silently ignore unknown pname - other mods may pass unrecognized params
        }
    }

    public static float glGetTexEnvf(int target, int pname) {
        return (float) glGetTexEnvi(target, pname);
    }

    public static int glGetTexEnvi(int target, int pname) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        if (target != GL11.GL_TEXTURE_ENV) {
            // GL_TEXTURE_FILTER_CONTROL and GL_POINT_SPRITE are valid targets whose state is not kept here.
            if (target != 0x8500 && target != 0x8861) recordError(GL11.GL_INVALID_ENUM);
            return 0;
        }

        final var envState = glCtx.textures.getTexEnvState(glCtx.activeTextureUnit.getValue());
        return switch (pname) {
            case GL11.GL_TEXTURE_ENV_MODE -> envState.mode;
            case GL13.GL_COMBINE_RGB -> envState.combineRgb;
            case GL13.GL_COMBINE_ALPHA -> envState.combineAlpha;
            case GL13.GL_SOURCE0_RGB -> envState.sourceRgb[0];
            case GL13.GL_SOURCE1_RGB -> envState.sourceRgb[1];
            case GL13.GL_SOURCE2_RGB -> envState.sourceRgb[2];
            case GL13.GL_SOURCE0_ALPHA -> envState.sourceAlpha[0];
            case GL13.GL_SOURCE1_ALPHA -> envState.sourceAlpha[1];
            case GL13.GL_SOURCE2_ALPHA -> envState.sourceAlpha[2];
            case GL13.GL_OPERAND0_RGB -> envState.operandRgb[0];
            case GL13.GL_OPERAND1_RGB -> envState.operandRgb[1];
            case GL13.GL_OPERAND2_RGB -> envState.operandRgb[2];
            case GL13.GL_OPERAND0_ALPHA -> envState.operandAlpha[0];
            case GL13.GL_OPERAND1_ALPHA -> envState.operandAlpha[1];
            case GL13.GL_OPERAND2_ALPHA -> envState.operandAlpha[2];
            case GL13.GL_RGB_SCALE -> (int) envState.scaleRgb;
            case GL11.GL_ALPHA_SCALE -> (int) envState.scaleAlpha;
            default -> {
                recordError(GL11.GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public static void glGetTexEnv(int target, int pname, IntBuffer params) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        if (target != GL11.GL_TEXTURE_ENV) return;
        if (pname == GL11.GL_TEXTURE_ENV_COLOR) {
            final var envState = glCtx.textures.getTexEnvState(glCtx.activeTextureUnit.getValue());
            params.put(params.position(), (int) (envState.envColorR * 2147483647.0f));
            params.put(params.position() + 1, (int) (envState.envColorG * 2147483647.0f));
            params.put(params.position() + 2, (int) (envState.envColorB * 2147483647.0f));
            params.put(params.position() + 3, (int) (envState.envColorA * 2147483647.0f));
        } else {
            params.put(params.position(), glGetTexEnvi(target, pname));
        }
    }

    public static void glGetTexEnv(int target, int pname, FloatBuffer params) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        if (target != GL11.GL_TEXTURE_ENV) return;
        if (pname == GL11.GL_TEXTURE_ENV_COLOR) {
            final var envState = glCtx.textures.getTexEnvState(glCtx.activeTextureUnit.getValue());
            params.put(params.position(), envState.envColorR);
            params.put(params.position() + 1, envState.envColorG);
            params.put(params.position() + 2, envState.envColorB);
            params.put(params.position() + 3, envState.envColorA);
        } else {
            params.put(params.position(), (float) glGetTexEnvi(target, pname));
        }
    }


    public static void glTexGeni(int coord, int pname, int param) {
        // The one value the single forms set is a mode; the planes come as vectors.
        if (coord < GL11.GL_S || coord > GL11.GL_Q || pname != GL11.GL_TEXTURE_GEN_MODE
            || param != GL11.GL_OBJECT_LINEAR && param != GL11.GL_EYE_LINEAR && param != GL11.GL_SPHERE_MAP && param != GL13.GL_NORMAL_MAP && param != GL13.GL_REFLECTION_MAP
            || param == GL11.GL_SPHERE_MAP && coord > GL11.GL_T || (param == GL13.GL_NORMAL_MAP || param == GL13.GL_REFLECTION_MAP) && coord == GL11.GL_Q) {
            recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        beforeUncapturedStateChange();
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled()) return;
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            final int unit = glCtx.activeTextureUnit.getValue();
            glCtx.textures.getTexGenState(unit).setMode(coord, param);
            glCtx.texGenGeneration++;
        }
    }

    public static void glTexGenf(int coord, int pname, float param) {
        // Single-float form only valid for GL_TEXTURE_GEN_MODE
        glTexGeni(coord, pname, (int) param);
    }

    public static void glTexGend(int coord, int pname, double param) {
        glTexGeni(coord, pname, (int) param);
    }

    public static void glTexGen(int coord, int pname, FloatBuffer params) {
        beforeUncapturedStateChange();
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled()) return;
        final int unit = glCtx.activeTextureUnit.getValue();
        final var texGenState = glCtx.textures.getTexGenState(unit);
        final int pos = params.position();
        switch (pname) {
            case GL11.GL_TEXTURE_GEN_MODE -> {
                texGenState.setMode(coord, (int) params.get(pos));
                glCtx.texGenGeneration++;
            }
            case GL11.GL_OBJECT_PLANE -> {
                glCtx.texGenTempPlane[0] = params.get(pos);
                glCtx.texGenTempPlane[1] = params.get(pos + 1);
                glCtx.texGenTempPlane[2] = params.get(pos + 2);
                glCtx.texGenTempPlane[3] = params.get(pos + 3);
                texGenState.setObjectPlane(coord, glCtx.texGenTempPlane);
                glCtx.texGenGeneration++;
            }
            case GL11.GL_EYE_PLANE -> {
                glCtx.texGenTempPlane[0] = params.get(pos);
                glCtx.texGenTempPlane[1] = params.get(pos + 1);
                glCtx.texGenTempPlane[2] = params.get(pos + 2);
                glCtx.texGenTempPlane[3] = params.get(pos + 3);
                texGenState.setEyePlane(coord, glCtx.texGenTempPlane, glCtx.modelViewMatrix);
                glCtx.texGenGeneration++;
            }
        }
    }

    public static void glTexGen(int coord, int pname, DoubleBuffer params) {
        beforeUncapturedStateChange();
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled()) return;
        final int pos = params.position();
        glCtx.texGenTempPlane[0] = (float) params.get(pos);
        glCtx.texGenTempPlane[1] = (float) params.get(pos + 1);
        glCtx.texGenTempPlane[2] = (float) params.get(pos + 2);
        glCtx.texGenTempPlane[3] = (float) params.get(pos + 3);
        final int unit = glCtx.activeTextureUnit.getValue();
        final var texGenState = glCtx.textures.getTexGenState(unit);
        switch (pname) {
            case GL11.GL_TEXTURE_GEN_MODE -> {
                texGenState.setMode(coord, (int) params.get(pos));
                glCtx.texGenGeneration++;
            }
            case GL11.GL_OBJECT_PLANE -> {
                texGenState.setObjectPlane(coord, glCtx.texGenTempPlane);
                glCtx.texGenGeneration++;
            }
            case GL11.GL_EYE_PLANE -> {
                texGenState.setEyePlane(coord, glCtx.texGenTempPlane, glCtx.modelViewMatrix);
                glCtx.texGenGeneration++;
            }
        }
    }

    public static void glTexGen(int coord, int pname, IntBuffer params) {
        beforeUncapturedStateChange();
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled()) return;
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            final int unit = glCtx.activeTextureUnit.getValue();
            glCtx.textures.getTexGenState(unit).setMode(coord, params.get(params.position()));
            glCtx.texGenGeneration++;
        }
    }

    public static int glGetTexGeni(int coord, int pname) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        if (coord < GL11.GL_S || coord > GL11.GL_Q || pname != GL11.GL_TEXTURE_GEN_MODE && pname != GL11.GL_OBJECT_PLANE && pname != GL11.GL_EYE_PLANE) {
            recordError(GL11.GL_INVALID_ENUM);
            return 0;
        }
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            return glCtx.textures.getTexGenState(glCtx.activeTextureUnit.getValue()).getMode(coord);
        }
        return 0;
    }

    public static float glGetTexGenf(int coord, int pname) {
        return (float) glGetTexGeni(coord, pname);
    }

    public static double glGetTexGend(int coord, int pname) {
        return glGetTexGeni(coord, pname);
    }

    public static void glGetTexGen(int coord, int pname, IntBuffer params) {
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            params.put(params.position(), glGetTexGeni(coord, pname));
        }
    }

    public static void glGetTexGen(int coord, int pname, FloatBuffer params) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final var state = glCtx.textures.getTexGenState(glCtx.activeTextureUnit.getValue());
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            params.put(params.position(), (float) state.getMode(coord));
        } else if (pname == GL11.GL_OBJECT_PLANE) {
            final float[] plane = state.getObjectPlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        } else if (pname == GL11.GL_EYE_PLANE) {
            final float[] plane = state.getEyePlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        }
    }

    public static void glGetTexGen(int coord, int pname, DoubleBuffer params) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final var state = glCtx.textures.getTexGenState(glCtx.activeTextureUnit.getValue());
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            params.put(params.position(), state.getMode(coord));
        } else if (pname == GL11.GL_OBJECT_PLANE) {
            final float[] plane = state.getObjectPlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        } else if (pname == GL11.GL_EYE_PLANE) {
            final float[] plane = state.getEyePlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        }
    }
}
