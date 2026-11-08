package com.gtnewhorizons.angelica.glsm.backend;

/**
 * Lets GLSM ask a backend whether a call is one GL refuses before GLSM remembers its arguments, since a refused call changes no state.
 * A backend on a real driver has nothing to say here: the driver raises its own errors.
 */
public interface GlCallChecks {
    default boolean bindTextureUnit(int unit, int texture) {
        return true;
    }

    default boolean namedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) {
        return true;
    }

    default boolean namedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffertarget, int renderbuffer) {
        return true;
    }

    GlCallChecks NONE = new GlCallChecks() {};

    default boolean isEnabled(int cap) { return true; }
    default boolean uniformProgramBound(int activeProgram) { return true; }
    default boolean hint(int target, int mode) { return true; }
    default boolean stencilMaskSeparate(int face) { return true; }
    default boolean depthFunc(int func) { return true; }
    default boolean glGetProgramiv(int program, int pname) { return true; }
    default boolean glGetShaderiv(int shader, int pname) { return true; }
    default boolean glGetBooleanv(int pname) { return true; }
    default boolean glGetBufferParameteriv(int target, int value) { return true; }
    default boolean glGetBufferSubData(int target) { return true; }
    default boolean glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname) { return true; }
    default boolean glGetBooleani_v(int target, int index) { return true; }
    default boolean glGetIntegeri_v(int target, int index) { return true; }
    default boolean glGetProgramInfoLog(int program, int maxLength) { return true; }
    default boolean glGetShaderInfoLog(int shader, int maxLength) { return true; }
    default boolean glGetShaderSource(int shader, int bufSize) { return true; }
    default boolean glGetTexLevelParameteriv(int target, int level, int pname) { return true; }
    default boolean glGetTexParameteriv(int target, int pname) { return true; }
    default boolean glGetUniformBlockIndex(int program) { return true; }
    default boolean glGetAttribLocation(int program) { return true; }
    default boolean glGetAttachedShaders(int program, int maxCount) { return true; }
    default boolean glBindAttribLocation(int program, int index) { return true; }
    default boolean glBindFragDataLocation(int program, int colorNumber) { return true; }
    default boolean logicOp(int opcode) { return true; }
    default boolean glGetTexImage(int target, int level, int format, int type) { return true; }
    default boolean glUniform1fv(int location, int count) { return true; }
    default boolean glUniform2fv(int location, int count) { return true; }
    default boolean glUniform3fv(int location, int count) { return true; }
    default boolean glUniform4fv(int location, int count) { return true; }
    default boolean glUniform1iv(int location, int count) { return true; }
    default boolean glUniform2iv(int location, int count) { return true; }
    default boolean glUniform3iv(int location, int count) { return true; }
    default boolean glUniform4iv(int location, int count) { return true; }
    default boolean glUniformMatrix2fv(int location, int count) { return true; }
    default boolean glUniformMatrix3fv(int location, int count) { return true; }
    default boolean glUniformMatrix4fv(int location, int count) { return true; }
    default boolean drawRange(int start, int end) { return true; }
    default boolean drawPrimitive(int mode) { return true; }
    default boolean pointParameteri(int pname, int param) { return true; }
    default boolean pointParameterf(int pname, float param) { return true; }
    default boolean blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { return true; }
    default boolean blendEquation(int mode) { return true; }
    default boolean blendEquationSeparate(int modeRGB, int modeAlpha) { return true; }
    default boolean cullFace(int mode) { return true; }
    default boolean frontFace(int mode) { return true; }
    default boolean polygonMode(int face, int mode) { return true; }
    default boolean stencilFunc(int func) { return true; }
    default boolean stencilFuncSeparate(int face, int func) { return true; }
    default boolean stencilOp(int sfail, int dpfail, int dppass) { return true; }
    default boolean stencilOpSeparate(int face, int sfail, int dpfail, int dppass) { return true; }
    default boolean viewport(int width, int height) { return true; }
    default boolean scissor(int width, int height) { return true; }
    default boolean lineWidth(float width) { return true; }
    default boolean pointSize(float size) { return true; }
    default boolean activeTexture(int texture) { return true; }
    default boolean bindTexture(int target, int texture) { return true; }
    default boolean texParameteri(int target, int pname, int param) { return true; }
    default boolean texParameterf(int target, int pname, float param) { return true; }
    default boolean drawBuffer(int mode) { return true; }
    default boolean useProgram(int program) { return true; }
    default boolean bindVertexArray(int array) { return true; }
    default boolean vertexAttribPointer(int index, int size, int type, boolean normalized, int stride) { return true; }
    default boolean vertexAttribIPointer(int index, int size, int type, int stride) { return true; }
    default boolean enableVertexAttribArray(int index) { return true; }
    default boolean disableVertexAttribArray(int index) { return true; }
}
