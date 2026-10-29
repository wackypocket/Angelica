package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.locks.ReentrantLock;

public abstract class GpuRenderBackend<G extends GlOps> extends RenderBackend {
    protected G gl;
    protected final ReentrantLock lock = new ReentrantLock();
    protected volatile boolean shared;
    protected volatile boolean mainUnlocked;

    private static final VarHandle MAIN_UNLOCKED;

    static {
        try {
            MAIN_UNLOCKED = MethodHandles.lookup().findVarHandle(GpuRenderBackend.class, "mainUnlocked", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final void exitUnlocked() {
        MAIN_UNLOCKED.setRelease(this, false);
    }

    protected final boolean enterUnlocked() {
        if (shared) return false;
        mainUnlocked = true;
        if (!shared) return true;
        exitUnlocked();
        return false;
    }

    @Override public void flush() {
        if (enterUnlocked()) {
            try {
                gl.flush();
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override public void finish() {
        if (enterUnlocked()) {
            try {
                gl.finish();
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.finish();
        } finally {
            lock.unlock();
        }
    }

    @Override public void enable(int cap) {
        if (enterUnlocked()) {
            try {
                gl.enable(cap);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.enable(cap);
        } finally {
            lock.unlock();
        }
    }

    @Override public void enablei(int cap, int index) {
        if (enterUnlocked()) {
            try {
                gl.enablei(cap, index);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.enablei(cap, index);
        } finally {
            lock.unlock();
        }
    }

    @Override public void disable(int cap) {
        if (enterUnlocked()) {
            try {
                gl.disable(cap);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.disable(cap);
        } finally {
            lock.unlock();
        }
    }

    @Override public void disablei(int cap, int index) {
        if (enterUnlocked()) {
            try {
                gl.disablei(cap, index);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.disablei(cap, index);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (enterUnlocked()) {
            try {
                gl.blendFuncSeparatei(buf, srcRGB, dstRGB, srcAlpha, dstAlpha);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blendFuncSeparatei(buf, srcRGB, dstRGB, srcAlpha, dstAlpha);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blendFunc(int sfactor, int dfactor) {
        if (enterUnlocked()) {
            try {
                gl.blendFunc(sfactor, dfactor);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blendFunc(sfactor, dfactor);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (enterUnlocked()) {
            try {
                gl.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blendEquation(int mode) {
        if (enterUnlocked()) {
            try {
                gl.blendEquation(mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blendEquation(mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blendEquationSeparate(int modeRGB, int modeAlpha) {
        if (enterUnlocked()) {
            try {
                gl.blendEquationSeparate(modeRGB, modeAlpha);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blendEquationSeparate(modeRGB, modeAlpha);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blendColor(float red, float green, float blue, float alpha) {
        if (enterUnlocked()) {
            try {
                gl.blendColor(red, green, blue, alpha);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blendColor(red, green, blue, alpha);
        } finally {
            lock.unlock();
        }
    }

    @Override public void depthFunc(int func) {
        if (enterUnlocked()) {
            try {
                gl.depthFunc(func);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.depthFunc(func);
        } finally {
            lock.unlock();
        }
    }

    @Override public void depthMask(boolean flag) {
        if (enterUnlocked()) {
            try {
                gl.depthMask(flag);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.depthMask(flag);
        } finally {
            lock.unlock();
        }
    }

    @Override public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (enterUnlocked()) {
            try {
                gl.colorMask(red, green, blue, alpha);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.colorMask(red, green, blue, alpha);
        } finally {
            lock.unlock();
        }
    }

    @Override public void cullFace(int mode) {
        if (enterUnlocked()) {
            try {
                gl.cullFace(mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.cullFace(mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void frontFace(int mode) {
        if (enterUnlocked()) {
            try {
                gl.frontFace(mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.frontFace(mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void polygonMode(int face, int mode) {
        if (enterUnlocked()) {
            try {
                gl.polygonMode(face, mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.polygonMode(face, mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void polygonOffset(float factor, float units) {
        if (enterUnlocked()) {
            try {
                gl.polygonOffset(factor, units);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.polygonOffset(factor, units);
        } finally {
            lock.unlock();
        }
    }

    @Override public void polygonOffsetClamp(float factor, float units, float clamp) {
        if (enterUnlocked()) {
            try {
                gl.polygonOffsetClamp(factor, units, clamp);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.polygonOffsetClamp(factor, units, clamp);
        } finally {
            lock.unlock();
        }
    }

    @Override public void stencilFunc(int func, int ref, int mask) {
        if (enterUnlocked()) {
            try {
                gl.stencilFunc(func, ref, mask);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.stencilFunc(func, ref, mask);
        } finally {
            lock.unlock();
        }
    }

    @Override public void stencilOp(int sfail, int dpfail, int dppass) {
        if (enterUnlocked()) {
            try {
                gl.stencilOp(sfail, dpfail, dppass);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.stencilOp(sfail, dpfail, dppass);
        } finally {
            lock.unlock();
        }
    }

    @Override public void stencilMask(int mask) {
        if (enterUnlocked()) {
            try {
                gl.stencilMask(mask);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.stencilMask(mask);
        } finally {
            lock.unlock();
        }
    }

    @Override public void stencilFuncSeparate(int face, int func, int ref, int mask) {
        if (enterUnlocked()) {
            try {
                gl.stencilFuncSeparate(face, func, ref, mask);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.stencilFuncSeparate(face, func, ref, mask);
        } finally {
            lock.unlock();
        }
    }

    @Override public void stencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        if (enterUnlocked()) {
            try {
                gl.stencilOpSeparate(face, sfail, dpfail, dppass);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.stencilOpSeparate(face, sfail, dpfail, dppass);
        } finally {
            lock.unlock();
        }
    }

    @Override public void stencilMaskSeparate(int face, int mask) {
        if (enterUnlocked()) {
            try {
                gl.stencilMaskSeparate(face, mask);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.stencilMaskSeparate(face, mask);
        } finally {
            lock.unlock();
        }
    }

    @Override public void viewport(int x, int y, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.viewport(x, y, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.viewport(x, y, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void depthRange(double nearVal, double farVal) {
        if (enterUnlocked()) {
            try {
                gl.depthRange(nearVal, farVal);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.depthRange(nearVal, farVal);
        } finally {
            lock.unlock();
        }
    }

    @Override public void scissor(int x, int y, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.scissor(x, y, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.scissor(x, y, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clearColor(float red, float green, float blue, float alpha) {
        if (enterUnlocked()) {
            try {
                gl.clearColor(red, green, blue, alpha);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clearColor(red, green, blue, alpha);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clearDepth(double depth) {
        if (enterUnlocked()) {
            try {
                gl.clearDepth(depth);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clearDepth(depth);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clearStencil(int s) {
        if (enterUnlocked()) {
            try {
                gl.clearStencil(s);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clearStencil(s);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clear(int mask) {
        if (enterUnlocked()) {
            try {
                gl.clear(mask);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clear(mask);
        } finally {
            lock.unlock();
        }
    }

    @Override public void lineWidth(float width) {
        if (enterUnlocked()) {
            try {
                gl.lineWidth(width);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.lineWidth(width);
        } finally {
            lock.unlock();
        }
    }

    @Override public void pointSize(float size) {
        if (enterUnlocked()) {
            try {
                gl.pointSize(size);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.pointSize(size);
        } finally {
            lock.unlock();
        }
    }

    @Override public void logicOp(int opcode) {
        if (enterUnlocked()) {
            try {
                gl.logicOp(opcode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.logicOp(opcode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void hint(int target, int mode) {
        if (enterUnlocked()) {
            try {
                gl.hint(target, mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.hint(target, mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawArrays(int mode, int first, int count) {
        if (enterUnlocked()) {
            try {
                gl.drawArrays(mode, first, count);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawArrays(mode, first, count);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawElements(int mode, int count, int type, long indices) {
        if (enterUnlocked()) {
            try {
                gl.drawElements(mode, count, type, indices);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawElements(mode, count, type, indices);
        } finally {
            lock.unlock();
        }
    }

    @Override public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        if (enterUnlocked()) {
            try {
                gl.multiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.multiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
        } finally {
            lock.unlock();
        }
    }

    @Override public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        if (enterUnlocked()) {
            try {
                gl.multiDrawArraysIndirectCount(mode, indirect, drawcount, maxdrawcount, stride);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.multiDrawArraysIndirectCount(mode, indirect, drawcount, maxdrawcount, stride);
        } finally {
            lock.unlock();
        }
    }

    @Override public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        if (enterUnlocked()) {
            try {
                gl.multiDrawElementsIndirectCount(mode, type, indirect, drawcount, maxdrawcount, stride);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.multiDrawElementsIndirectCount(mode, type, indirect, drawcount, maxdrawcount, stride);
        } finally {
            lock.unlock();
        }
    }

    @Override public void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        if (enterUnlocked()) {
            try {
                gl.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawElementsInstanced(int mode, int count, int type, long indices, int primcount) {
        if (enterUnlocked()) {
            try {
                gl.drawElementsInstanced(mode, count, type, indices, primcount);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawElementsInstanced(mode, count, type, indices, primcount);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawArraysInstanced(int mode, int first, int count, int primcount) {
        if (enterUnlocked()) {
            try {
                gl.drawArraysInstanced(mode, first, count, primcount);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawArraysInstanced(mode, first, count, primcount);
        } finally {
            lock.unlock();
        }
    }

    @Override public void provokingVertex(int provokeMode) {
        if (enterUnlocked()) {
            try {
                gl.provokingVertex(provokeMode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.provokingVertex(provokeMode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex) {
        if (enterUnlocked()) {
            try {
                gl.drawElementsBaseVertex(mode, count, type, indices, baseVertex);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawElementsBaseVertex(mode, count, type, indices, baseVertex);
        } finally {
            lock.unlock();
        }
    }

    @Override public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        if (enterUnlocked()) {
            try {
                gl.multiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.multiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex);
        } finally {
            lock.unlock();
        }
    }

    @Override public void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {
        if (enterUnlocked()) {
            try {
                gl.multiDrawArrays(mode, firsts, counts);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.multiDrawArrays(mode, firsts, counts);
        } finally {
            lock.unlock();
        }
    }

    @Override public void primitiveRestartIndex(int index) {
        if (enterUnlocked()) {
            try {
                gl.primitiveRestartIndex(index);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.primitiveRestartIndex(index);
        } finally {
            lock.unlock();
        }
    }

    @Override public void pointParameterf(int pname, float param) {
        if (enterUnlocked()) {
            try {
                gl.pointParameterf(pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.pointParameterf(pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void pointParameteri(int pname, int param) {
        if (enterUnlocked()) {
            try {
                gl.pointParameteri(pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.pointParameteri(pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawBuffer(int mode) {
        if (enterUnlocked()) {
            try {
                gl.drawBuffer(mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawBuffer(mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        if (enterUnlocked()) {
            try {
                gl.dispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.dispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
        } finally {
            lock.unlock();
        }
    }

    @Override public void dispatchComputeIndirect(long offset) {
        if (enterUnlocked()) {
            try {
                gl.dispatchComputeIndirect(offset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.dispatchComputeIndirect(offset);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genTextures() {
        if (enterUnlocked()) {
            try {
                return gl.genTextures();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genTextures();
        } finally {
            lock.unlock();
        }
    }

    @Override public void genTextures(IntBuffer textures) {
        if (enterUnlocked()) {
            try {
                gl.genTextures(textures);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.genTextures(textures);
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteTextures(int texture) {
        if (enterUnlocked()) {
            try {
                gl.deleteTextures(texture);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteTextures(texture);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindTexture(int target, int texture) {
        if (enterUnlocked()) {
            try {
                gl.bindTexture(target, texture);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindTexture(target, texture);
        } finally {
            lock.unlock();
        }
    }

    @Override public void activeTexture(int texture) {
        if (enterUnlocked()) {
            try {
                gl.activeTexture(texture);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.activeTexture(texture);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixelBufferOffset) {
        if (enterUnlocked()) {
            try {
                gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixelBufferOffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage2D(target, level, internalFormat, width, height, border, format, type, pixelBufferOffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage1D(int target, int level, int internalFormat, int width, int border, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage1D(target, level, internalFormat, width, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage1D(target, level, internalFormat, width, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texSubImage1D(int target, int level, int xoffset, int width, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texSubImage1D(target, level, xoffset, width, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texSubImage1D(target, level, xoffset, width, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pboOffset) {
        if (enterUnlocked()) {
            try {
                gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pboOffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pboOffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        if (enterUnlocked()) {
            try {
                gl.copyTexImage2D(target, level, internalFormat, x, y, width, height, border);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.copyTexImage2D(target, level, internalFormat, x, y, width, height, border);
        } finally {
            lock.unlock();
        }
    }

    @Override public void copyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.copyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.copyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texParameteri(int target, int pname, int param) {
        if (enterUnlocked()) {
            try {
                gl.texParameteri(target, pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texParameteri(target, pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texParameterf(int target, int pname, float param) {
        if (enterUnlocked()) {
            try {
                gl.texParameterf(target, pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texParameterf(target, pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texParameteriv(int target, int pname, IntBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.texParameteriv(target, pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texParameteriv(target, pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texParameterfv(int target, int pname, FloatBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.texParameterfv(target, pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texParameterfv(target, pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getTexParameteri(int target, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getTexParameteri(target, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getTexParameteri(target, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public float getTexParameterf(int target, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getTexParameterf(target, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getTexParameterf(target, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getTexLevelParameteri(int target, int level, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getTexLevelParameteri(target, level, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getTexLevelParameteri(target, level, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void generateMipmap(int target) {
        if (enterUnlocked()) {
            try {
                gl.generateMipmap(target);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.generateMipmap(target);
        } finally {
            lock.unlock();
        }
    }

    @Override public void pixelStorei(int pname, int param) {
        if (enterUnlocked()) {
            try {
                gl.pixelStorei(pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.pixelStorei(pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void sampleCoverage(float value, boolean invert) {
        if (enterUnlocked()) {
            try {
                gl.sampleCoverage(value, invert);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.sampleCoverage(value, invert);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genSamplers() {
        if (enterUnlocked()) {
            try {
                return gl.genSamplers();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genSamplers();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteSamplers(int sampler) {
        if (enterUnlocked()) {
            try {
                gl.deleteSamplers(sampler);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteSamplers(sampler);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindSampler(int unit, int sampler) {
        if (enterUnlocked()) {
            try {
                gl.bindSampler(unit, sampler);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindSampler(unit, sampler);
        } finally {
            lock.unlock();
        }
    }

    @Override public void samplerParameteri(int sampler, int pname, int param) {
        if (enterUnlocked()) {
            try {
                gl.samplerParameteri(sampler, pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.samplerParameteri(sampler, pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void samplerParameterf(int sampler, int pname, float param) {
        if (enterUnlocked()) {
            try {
                gl.samplerParameterf(sampler, pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.samplerParameterf(sampler, pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genFramebuffers() {
        if (enterUnlocked()) {
            try {
                return gl.genFramebuffers();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genFramebuffers();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteFramebuffers(int framebuffer) {
        if (enterUnlocked()) {
            try {
                gl.deleteFramebuffers(framebuffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteFramebuffers(framebuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindFramebuffer(int target, int framebuffer) {
        if (enterUnlocked()) {
            try {
                gl.bindFramebuffer(target, framebuffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindFramebuffer(target, framebuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (enterUnlocked()) {
            try {
                gl.framebufferTexture2D(target, attachment, textarget, texture, level);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.framebufferTexture2D(target, attachment, textarget, texture, level);
        } finally {
            lock.unlock();
        }
    }

    @Override public void framebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        if (enterUnlocked()) {
            try {
                gl.framebufferTexture1D(target, attachment, textarget, texture, level);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.framebufferTexture1D(target, attachment, textarget, texture, level);
        } finally {
            lock.unlock();
        }
    }

    @Override public void framebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {
        if (enterUnlocked()) {
            try {
                gl.framebufferTexture3D(target, attachment, textarget, texture, level, zoffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.framebufferTexture3D(target, attachment, textarget, texture, level, zoffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        if (enterUnlocked()) {
            try {
                gl.framebufferTextureLayer(target, attachment, texture, level, layer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.framebufferTextureLayer(target, attachment, texture, level, layer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void framebufferTexture(int target, int attachment, int texture, int level) {
        if (enterUnlocked()) {
            try {
                gl.framebufferTexture(target, attachment, texture, level);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.framebufferTexture(target, attachment, texture, level);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genRenderbuffers() {
        if (enterUnlocked()) {
            try {
                return gl.genRenderbuffers();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genRenderbuffers();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteRenderbuffers(int renderbuffer) {
        if (enterUnlocked()) {
            try {
                gl.deleteRenderbuffers(renderbuffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteRenderbuffers(renderbuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindRenderbuffer(int target, int renderbuffer) {
        if (enterUnlocked()) {
            try {
                gl.bindRenderbuffer(target, renderbuffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindRenderbuffer(target, renderbuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void renderbufferStorage(int target, int internalformat, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.renderbufferStorage(target, internalformat, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.renderbufferStorage(target, internalformat, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.renderbufferStorageMultisample(target, samples, internalformat, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.renderbufferStorageMultisample(target, samples, internalformat, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (enterUnlocked()) {
            try {
                gl.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public int checkFramebufferStatus(int target) {
        if (enterUnlocked()) {
            try {
                return gl.checkFramebufferStatus(target);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.checkFramebufferStatus(target);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (enterUnlocked()) {
            try {
                gl.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawBuffers(int buffer) {
        if (enterUnlocked()) {
            try {
                gl.drawBuffers(buffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawBuffers(buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void drawBuffers(IntBuffer bufs) {
        if (enterUnlocked()) {
            try {
                gl.drawBuffers(bufs);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.drawBuffers(bufs);
        } finally {
            lock.unlock();
        }
    }

    @Override public void readBuffer(int mode) {
        if (enterUnlocked()) {
            try {
                gl.readBuffer(mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.readBuffer(mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.readPixels(x, y, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.readPixels(x, y, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.readPixels(x, y, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.readPixels(x, y, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.readPixels(x, y, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.readPixels(x, y, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void readPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {
        if (enterUnlocked()) {
            try {
                gl.readPixels(x, y, width, height, format, type, pixelBufferOffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.readPixels(x, y, width, height, format, type, pixelBufferOffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.getTexImage(target, level, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getTexImage(target, level, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getTexImage(int target, int level, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.getTexImage(target, level, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getTexImage(target, level, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getTexImage(int target, int level, int format, int type, long pixelBufferOffset) {
        if (enterUnlocked()) {
            try {
                gl.getTexImage(target, level, format, type, pixelBufferOffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getTexImage(target, level, format, type, pixelBufferOffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getFramebufferAttachmentParameteri(target, attachment, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getFramebufferAttachmentParameteri(target, attachment, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public int createShader(int type) {
        if (enterUnlocked()) {
            try {
                return gl.createShader(type);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.createShader(type);
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteShader(int shader) {
        if (enterUnlocked()) {
            try {
                gl.deleteShader(shader);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteShader(shader);
        } finally {
            lock.unlock();
        }
    }

    @Override public void shaderSource(int shader, CharSequence source) {
        if (enterUnlocked()) {
            try {
                gl.shaderSource(shader, source);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.shaderSource(shader, source);
        } finally {
            lock.unlock();
        }
    }

    @Override public void compileShader(int shader) {
        if (enterUnlocked()) {
            try {
                gl.compileShader(shader);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.compileShader(shader);
        } finally {
            lock.unlock();
        }
    }

    @Override public int createProgram() {
        if (enterUnlocked()) {
            try {
                return gl.createProgram();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.createProgram();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteProgram(int program) {
        if (enterUnlocked()) {
            try {
                gl.deleteProgram(program);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteProgram(program);
        } finally {
            lock.unlock();
        }
    }

    @Override public void attachShader(int program, int shader) {
        if (enterUnlocked()) {
            try {
                gl.attachShader(program, shader);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.attachShader(program, shader);
        } finally {
            lock.unlock();
        }
    }

    @Override public void detachShader(int program, int shader) {
        if (enterUnlocked()) {
            try {
                gl.detachShader(program, shader);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.detachShader(program, shader);
        } finally {
            lock.unlock();
        }
    }

    @Override public void linkProgram(int program) {
        if (enterUnlocked()) {
            try {
                gl.linkProgram(program);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.linkProgram(program);
        } finally {
            lock.unlock();
        }
    }

    @Override public void useProgram(int program) {
        if (enterUnlocked()) {
            try {
                gl.useProgram(program);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.useProgram(program);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getShaderInfoLog(int shader, int maxLength) {
        if (enterUnlocked()) {
            try {
                return gl.getShaderInfoLog(shader, maxLength);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getShaderInfoLog(shader, maxLength);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {
        if (enterUnlocked()) {
            try {
                gl.getShaderInfoLog(shader, length, infoLog);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getShaderInfoLog(shader, length, infoLog);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getProgramInfoLog(int program, int maxLength) {
        if (enterUnlocked()) {
            try {
                return gl.getProgramInfoLog(program, maxLength);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getProgramInfoLog(program, maxLength);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {
        if (enterUnlocked()) {
            try {
                gl.getProgramInfoLog(program, length, infoLog);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getProgramInfoLog(program, length, infoLog);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getShaderi(int shader, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getShaderi(shader, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getShaderi(shader, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getProgrami(int program, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getProgrami(program, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getProgrami(program, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getProgramiv(int program, int pname, IntBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getProgramiv(program, pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getProgramiv(program, pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) {
        if (enterUnlocked()) {
            try {
                return gl.getActiveUniform(program, index, maxLength, sizeType);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getActiveUniform(program, index, maxLength, sizeType);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        if (enterUnlocked()) {
            try {
                gl.getActiveUniform(program, index, length, size, type, name);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getActiveUniform(program, index, length, size, type, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        if (enterUnlocked()) {
            try {
                gl.getActiveAttrib(program, index, length, size, type, name);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getActiveAttrib(program, index, length, size, type, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) {
        if (enterUnlocked()) {
            try {
                return gl.getActiveAttrib(program, index, maxLength, sizeType);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getActiveAttrib(program, index, maxLength, sizeType);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindAttribLocation(int program, int index, CharSequence name) {
        if (enterUnlocked()) {
            try {
                gl.bindAttribLocation(program, index, name);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindAttribLocation(program, index, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindFragDataLocation(int program, int colorNumber, CharSequence name) {
        if (enterUnlocked()) {
            try {
                gl.bindFragDataLocation(program, colorNumber, name);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindFragDataLocation(program, colorNumber, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getAttribLocation(int program, CharSequence name) {
        if (enterUnlocked()) {
            try {
                return gl.getAttribLocation(program, name);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getAttribLocation(program, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getAttribLocation(int program, ByteBuffer name) {
        if (enterUnlocked()) {
            try {
                return gl.getAttribLocation(program, name);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getAttribLocation(program, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getUniformLocation(int program, CharSequence name) {
        if (enterUnlocked()) {
            try {
                return gl.getUniformLocation(program, name);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getUniformLocation(program, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getUniformLocation(int program, ByteBuffer name) {
        if (enterUnlocked()) {
            try {
                return gl.getUniformLocation(program, name);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getUniformLocation(program, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isShader(int obj) {
        if (enterUnlocked()) {
            try {
                return gl.isShader(obj);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isShader(obj);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isProgram(int obj) {
        if (enterUnlocked()) {
            try {
                return gl.isProgram(obj);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isProgram(obj);
        } finally {
            lock.unlock();
        }
    }

    @Override public void validateProgram(int program) {
        if (enterUnlocked()) {
            try {
                gl.validateProgram(program);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.validateProgram(program);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getShaderSource(int shader, int maxLength) {
        if (enterUnlocked()) {
            try {
                return gl.getShaderSource(shader, maxLength);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getShaderSource(shader, maxLength);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getShaderSource(int shader, IntBuffer length, ByteBuffer source) {
        if (enterUnlocked()) {
            try {
                gl.getShaderSource(shader, length, source);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getShaderSource(shader, length, source);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform1i(int location, int v0) {
        if (enterUnlocked()) {
            try {
                gl.uniform1i(location, v0);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform1i(location, v0);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform1f(int location, float v0) {
        if (enterUnlocked()) {
            try {
                gl.uniform1f(location, v0);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform1f(location, v0);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform1fv(int location, FloatBuffer values) {
        if (enterUnlocked()) {
            try {
                gl.uniform1fv(location, values);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform1fv(location, values);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform3fv(int location, float[] values) {
        if (enterUnlocked()) {
            try {
                gl.uniform3fv(location, values);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform3fv(location, values);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform4fv(int location, float[] values) {
        if (enterUnlocked()) {
            try {
                gl.uniform4fv(location, values);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform4fv(location, values);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform2f(int location, float v0, float v1) {
        if (enterUnlocked()) {
            try {
                gl.uniform2f(location, v0, v1);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform2f(location, v0, v1);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform2i(int location, int v0, int v1) {
        if (enterUnlocked()) {
            try {
                gl.uniform2i(location, v0, v1);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform2i(location, v0, v1);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform3f(int location, float v0, float v1, float v2) {
        if (enterUnlocked()) {
            try {
                gl.uniform3f(location, v0, v1, v2);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform3f(location, v0, v1, v2);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform3i(int location, int v0, int v1, int v2) {
        if (enterUnlocked()) {
            try {
                gl.uniform3i(location, v0, v1, v2);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform3i(location, v0, v1, v2);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform4f(int location, float v0, float v1, float v2, float v3) {
        if (enterUnlocked()) {
            try {
                gl.uniform4f(location, v0, v1, v2, v3);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform4f(location, v0, v1, v2, v3);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform4i(int location, int v0, int v1, int v2, int v3) {
        if (enterUnlocked()) {
            try {
                gl.uniform4i(location, v0, v1, v2, v3);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform4i(location, v0, v1, v2, v3);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform2(int location, FloatBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform2(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform2(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform3(int location, FloatBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform3(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform3(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform4(int location, FloatBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform4(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform4(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform1iv(int location, IntBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform1iv(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform1iv(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform2iv(int location, IntBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform2iv(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform2iv(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform3iv(int location, IntBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform3iv(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform3iv(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniform4iv(int location, IntBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniform4iv(location, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniform4iv(location, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniformMatrix3(location, transpose, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniformMatrix3(location, transpose, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        if (enterUnlocked()) {
            try {
                gl.uniformMatrix4(location, transpose, value);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniformMatrix4(location, transpose, value);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniformMatrix2(int location, boolean transpose, FloatBuffer matrices) {
        if (enterUnlocked()) {
            try {
                gl.uniformMatrix2(location, transpose, matrices);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniformMatrix2(location, transpose, matrices);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getUniformfv(int program, int location, FloatBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getUniformfv(program, location, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getUniformfv(program, location, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getUniformiv(int program, int location, IntBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getUniformiv(program, location, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getUniformiv(program, location, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttrib2f(int index, float v0, float v1) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttrib2f(index, v0, v1);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttrib2f(index, v0, v1);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttrib3f(int index, float v0, float v1, float v2) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttrib3f(index, v0, v1, v2);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttrib3f(index, v0, v1, v2);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttrib4f(index, v0, v1, v2, v3);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttrib4f(index, v0, v1, v2, v3);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genBuffers() {
        if (enterUnlocked()) {
            try {
                return gl.genBuffers();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genBuffers();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteBuffers(int buffer) {
        if (enterUnlocked()) {
            try {
                gl.deleteBuffers(buffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteBuffers(buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteBuffers(IntBuffer buffers) {
        if (enterUnlocked()) {
            try {
                gl.deleteBuffers(buffers);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteBuffers(buffers);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindBuffer(int target, int buffer) {
        if (enterUnlocked()) {
            try {
                gl.bindBuffer(target, buffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindBuffer(target, buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindBufferBase(int target, int index, int buffer) {
        if (enterUnlocked()) {
            try {
                gl.bindBufferBase(target, index, buffer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindBufferBase(target, index, buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindBufferRange(int target, int index, int buffer, long offset, long size) {
        if (enterUnlocked()) {
            try {
                gl.bindBufferRange(target, index, buffer, offset, size);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindBufferRange(target, index, buffer, offset, size);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getUniformBlockIndex(int program, CharSequence name) {
        if (enterUnlocked()) {
            try {
                return gl.getUniformBlockIndex(program, name);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getUniformBlockIndex(program, name);
        } finally {
            lock.unlock();
        }
    }

    @Override public void uniformBlockBinding(int program, int blockIndex, int binding) {
        if (enterUnlocked()) {
            try {
                gl.uniformBlockBinding(program, blockIndex, binding);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.uniformBlockBinding(program, blockIndex, binding);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getIndexedBufferBinding(int target, int index) {
        if (enterUnlocked()) {
            try {
                return gl.getIndexedBufferBinding(target, index);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getIndexedBufferBinding(target, index);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getIntegerIndexed(int pname, int index) {
        if (enterUnlocked()) {
            try {
                return gl.getIntegerIndexed(pname, index);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getIntegerIndexed(pname, index);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, long size, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, size, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, size, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, ByteBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, FloatBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, ShortBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, IntBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, DoubleBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, int[] data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferData(int target, float[] data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.bufferData(target, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferData(target, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferSubData(int target, long offset, ByteBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.bufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferSubData(int target, long offset, ShortBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.bufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferSubData(int target, long offset, IntBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.bufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferSubData(int target, long offset, FloatBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.bufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferSubData(int target, long offset, DoubleBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.bufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public ByteBuffer mapBuffer(int target, int access) {
        if (enterUnlocked()) {
            try {
                return gl.mapBuffer(target, access);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.mapBuffer(target, access);
        } finally {
            lock.unlock();
        }
    }

    @Override public ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer old_buffer) {
        if (enterUnlocked()) {
            try {
                return gl.mapBuffer(target, access, length, old_buffer);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.mapBuffer(target, access, length, old_buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean unmapBuffer(int target) {
        if (enterUnlocked()) {
            try {
                return gl.unmapBuffer(target);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.unmapBuffer(target);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferStorage(int target, ByteBuffer data, int flags) {
        if (enterUnlocked()) {
            try {
                gl.bufferStorage(target, data, flags);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferStorage(target, data, flags);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bufferStorage(int target, long size, int flags) {
        if (enterUnlocked()) {
            try {
                gl.bufferStorage(target, size, flags);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bufferStorage(target, size, flags);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getBufferSubData(int target, long offset, ByteBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.getBufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getBufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getBufferSubData(int target, long offset, ShortBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.getBufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getBufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getBufferSubData(int target, long offset, IntBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.getBufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getBufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getBufferSubData(int target, long offset, FloatBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.getBufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getBufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getBufferSubData(int target, long offset, DoubleBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.getBufferSubData(target, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getBufferSubData(target, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getBufferParameteri(int target, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getBufferParameteri(target, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getBufferParameteri(target, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isBuffer(int buffer) {
        if (enterUnlocked()) {
            try {
                return gl.isBuffer(buffer);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isBuffer(buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        if (enterUnlocked()) {
            try {
                return gl.mapBufferRange(target, offset, length, access);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.mapBufferRange(target, offset, length, access);
        } finally {
            lock.unlock();
        }
    }

    @Override public void flushMappedBufferRange(int target, long offset, long length) {
        if (enterUnlocked()) {
            try {
                gl.flushMappedBufferRange(target, offset, length);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.flushMappedBufferRange(target, offset, length);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isTexture(int texture) {
        if (enterUnlocked()) {
            try {
                return gl.isTexture(texture);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isTexture(texture);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isFramebuffer(int framebuffer) {
        if (enterUnlocked()) {
            try {
                return gl.isFramebuffer(framebuffer);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isFramebuffer(framebuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isRenderbuffer(int renderbuffer) {
        if (enterUnlocked()) {
            try {
                return gl.isRenderbuffer(renderbuffer);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isRenderbuffer(renderbuffer);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isSampler(int sampler) {
        if (enterUnlocked()) {
            try {
                return gl.isSampler(sampler);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isSampler(sampler);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isQuery(int query) {
        if (enterUnlocked()) {
            try {
                return gl.isQuery(query);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isQuery(query);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genVertexArrays() {
        if (enterUnlocked()) {
            try {
                return gl.genVertexArrays();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genVertexArrays();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteVertexArrays(int array) {
        if (enterUnlocked()) {
            try {
                gl.deleteVertexArrays(array);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteVertexArrays(array);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindVertexArray(int array) {
        if (enterUnlocked()) {
            try {
                gl.bindVertexArray(array);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindVertexArray(array);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isVertexArray(int array) {
        if (enterUnlocked()) {
            try {
                return gl.isVertexArray(array);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.isVertexArray(array);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttribPointer(index, size, type, normalized, stride, pointer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttribPointer(index, size, type, normalized, stride, pointer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttribIPointer(index, size, type, stride, pointer);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttribIPointer(index, size, type, stride, pointer);
        } finally {
            lock.unlock();
        }
    }

    @Override public void enableVertexAttribArray(int index) {
        if (enterUnlocked()) {
            try {
                gl.enableVertexAttribArray(index);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.enableVertexAttribArray(index);
        } finally {
            lock.unlock();
        }
    }

    @Override public void disableVertexAttribArray(int index) {
        if (enterUnlocked()) {
            try {
                gl.disableVertexAttribArray(index);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.disableVertexAttribArray(index);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttribDivisor(int index, int divisor) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttribDivisor(index, divisor);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttribDivisor(index, divisor);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) {
        if (enterUnlocked()) {
            try {
                gl.bindVertexBuffer(bindingindex, buffer, offset, stride);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindVertexBuffer(bindingindex, buffer, offset, stride);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttribFormat(attribindex, size, type, normalized, relativeoffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttribFormat(attribindex, size, type, normalized, relativeoffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttribIFormat(attribindex, size, type, relativeoffset);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttribIFormat(attribindex, size, type, relativeoffset);
        } finally {
            lock.unlock();
        }
    }

    @Override public void vertexAttribBinding(int attribindex, int bindingindex) {
        if (enterUnlocked()) {
            try {
                gl.vertexAttribBinding(attribindex, bindingindex);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.vertexAttribBinding(attribindex, bindingindex);
        } finally {
            lock.unlock();
        }
    }

    @Override public int createTextures(int target) {
        if (enterUnlocked()) {
            try {
                return gl.createTextures(target);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.createTextures(target);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindTextureUnit(int unit, int texture) {
        if (enterUnlocked()) {
            try {
                gl.bindTextureUnit(unit, texture);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindTextureUnit(unit, texture);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureParameteri(int texture, int target, int pname, int param) {
        if (enterUnlocked()) {
            try {
                gl.textureParameteri(texture, target, pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureParameteri(texture, target, pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureParameterf(int texture, int target, int pname, float param) {
        if (enterUnlocked()) {
            try {
                gl.textureParameterf(texture, target, pname, param);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureParameterf(texture, target, pname, param);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureParameteriv(int texture, int target, int pname, IntBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.textureParameteriv(texture, target, pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureParameteriv(texture, target, pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public void texStorage2D(int target, int levels, int internalFormat, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.texStorage2D(target, levels, internalFormat, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.texStorage2D(target, levels, internalFormat, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public void generateTextureMipmap(int texture) {
        if (enterUnlocked()) {
            try {
                gl.generateTextureMipmap(texture);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.generateTextureMipmap(texture);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.textureImage2DEXT(texture, target, level, internalformat, width, height, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureImage2DEXT(texture, target, level, internalformat, width, height, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.textureImage2DEXT(texture, target, level, internalformat, width, height, border, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureImage2DEXT(texture, target, level, internalformat, width, height, border, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.textureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        if (enterUnlocked()) {
            try {
                gl.textureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, pixels);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.textureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, pixels);
        } finally {
            lock.unlock();
        }
    }

    @Override public int createFramebuffers() {
        if (enterUnlocked()) {
            try {
                return gl.createFramebuffers();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.createFramebuffers();
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        if (enterUnlocked()) {
            try {
                gl.namedFramebufferTexture(framebuffer, attachment, texture, level);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedFramebufferTexture(framebuffer, attachment, texture, level);
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedFramebufferReadBuffer(int framebuffer, int mode) {
        if (enterUnlocked()) {
            try {
                gl.namedFramebufferReadBuffer(framebuffer, mode);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedFramebufferReadBuffer(framebuffer, mode);
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs) {
        if (enterUnlocked()) {
            try {
                gl.namedFramebufferDrawBuffers(framebuffer, bufs);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedFramebufferDrawBuffers(framebuffer, bufs);
        } finally {
            lock.unlock();
        }
    }

    @Override public void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (enterUnlocked()) {
            try {
                gl.blitNamedFramebuffer(readFramebuffer, drawFramebuffer, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.blitNamedFramebuffer(readFramebuffer, drawFramebuffer, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } finally {
            lock.unlock();
        }
    }

    @Override public int createBuffers() {
        if (enterUnlocked()) {
            try {
                return gl.createBuffers();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.createBuffers();
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedBufferData(int buffer, long size, int usage) {
        if (enterUnlocked()) {
            try {
                gl.namedBufferData(buffer, size, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedBufferData(buffer, size, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedBufferData(int buffer, ByteBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.namedBufferData(buffer, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedBufferData(buffer, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedBufferData(int buffer, FloatBuffer data, int usage) {
        if (enterUnlocked()) {
            try {
                gl.namedBufferData(buffer, data, usage);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedBufferData(buffer, data, usage);
        } finally {
            lock.unlock();
        }
    }

    @Override public void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.namedBufferSubData(buffer, offset, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.namedBufferSubData(buffer, offset, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (enterUnlocked()) {
            try {
                gl.copyTextureSubImage2D(texture, target, level, xoffset, yoffset, x, y, width, height);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.copyTextureSubImage2D(texture, target, level, xoffset, yoffset, x, y, width, height);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getTextureParameteri(int texture, int target, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getTextureParameteri(texture, target, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getTextureParameteri(texture, target, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public float getTextureParameterf(int texture, int target, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getTextureParameterf(texture, target, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getTextureParameterf(texture, target, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getTextureLevelParameteri(int texture, int level, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getTextureLevelParameteri(texture, level, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getTextureLevelParameteri(texture, level, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getInteger(int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getInteger(pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getInteger(pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getInteger(int pname, IntBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getInteger(pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getInteger(pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public float getFloat(int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getFloat(pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getFloat(pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getFloat(int pname, FloatBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getFloat(pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getFloat(pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean getBoolean(int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getBoolean(pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getBoolean(pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getBoolean(int pname, ByteBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getBoolean(pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getBoolean(pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getString(int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getString(pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getString(pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getStringi(int name, int index) {
        if (enterUnlocked()) {
            try {
                return gl.getStringi(name, index);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getStringi(name, index);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getError() {
        if (enterUnlocked()) {
            try {
                return gl.getError();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getError();
        } finally {
            lock.unlock();
        }
    }

    @Override public long fenceSync(int condition, int flags) {
        if (enterUnlocked()) {
            try {
                return gl.fenceSync(condition, flags);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.fenceSync(condition, flags);
        } finally {
            lock.unlock();
        }
    }

    @Override public int clientWaitSync(long sync, int flags, long timeout) {
        if (enterUnlocked()) {
            try {
                return gl.clientWaitSync(sync, flags, timeout);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.clientWaitSync(sync, flags, timeout);
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteSync(long sync) {
        if (enterUnlocked()) {
            try {
                gl.deleteSync(sync);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteSync(sync);
        } finally {
            lock.unlock();
        }
    }

    @Override public void waitSync(long sync, int flags, long timeout) {
        if (enterUnlocked()) {
            try {
                gl.waitSync(sync, flags, timeout);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.waitSync(sync, flags, timeout);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getSynci(long sync, int pname, IntBuffer length) {
        if (enterUnlocked()) {
            try {
                return gl.getSynci(sync, pname, length);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getSynci(sync, pname, length);
        } finally {
            lock.unlock();
        }
    }

    @Override public void genQueries(IntBuffer ids) {
        if (enterUnlocked()) {
            try {
                gl.genQueries(ids);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.genQueries(ids);
        } finally {
            lock.unlock();
        }
    }

    @Override public int genQueries() {
        if (enterUnlocked()) {
            try {
                return gl.genQueries();
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.genQueries();
        } finally {
            lock.unlock();
        }
    }

    @Override public void deleteQueries(int id) {
        if (enterUnlocked()) {
            try {
                gl.deleteQueries(id);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.deleteQueries(id);
        } finally {
            lock.unlock();
        }
    }

    @Override public void beginQuery(int target, int id) {
        if (enterUnlocked()) {
            try {
                gl.beginQuery(target, id);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.beginQuery(target, id);
        } finally {
            lock.unlock();
        }
    }

    @Override public void endQuery(int target) {
        if (enterUnlocked()) {
            try {
                gl.endQuery(target);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.endQuery(target);
        } finally {
            lock.unlock();
        }
    }

    @Override public void getQueryObjectui(int id, int pname, IntBuffer params) {
        if (enterUnlocked()) {
            try {
                gl.getQueryObjectui(id, pname, params);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.getQueryObjectui(id, pname, params);
        } finally {
            lock.unlock();
        }
    }

    @Override public int getQueryObjecti(int id, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getQueryObjecti(id, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getQueryObjecti(id, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void queryCounter(int id, int target) {
        if (enterUnlocked()) {
            try {
                gl.queryCounter(id, target);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.queryCounter(id, target);
        } finally {
            lock.unlock();
        }
    }

    @Override public long getQueryObjectui64(int id, int pname) {
        if (enterUnlocked()) {
            try {
                return gl.getQueryObjectui64(id, pname);
            } finally {
                exitUnlocked();
            }
        }
        lock.lock();
        try {
            return gl.getQueryObjectui64(id, pname);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.clearBufferSubData(target, internalFormat, offset, size, format, type, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clearBufferSubData(target, internalFormat, offset, size, format, type, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) {
        if (enterUnlocked()) {
            try {
                gl.clearBufferData(target, internalformat, format, type, data);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clearBufferData(target, internalformat, format, type, data);
        } finally {
            lock.unlock();
        }
    }

    @Override public void clearTexImage(int texture, int level, int format, int type) {
        if (enterUnlocked()) {
            try {
                gl.clearTexImage(texture, level, format, type);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.clearTexImage(texture, level, format, type);
        } finally {
            lock.unlock();
        }
    }

    @Override public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (enterUnlocked()) {
            try {
                gl.bindImageTexture(unit, texture, level, layered, layer, access, format);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.bindImageTexture(unit, texture, level, layered, layer, access, format);
        } finally {
            lock.unlock();
        }
    }

    @Override public void memoryBarrier(int barriers) {
        if (enterUnlocked()) {
            try {
                gl.memoryBarrier(barriers);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.memoryBarrier(barriers);
        } finally {
            lock.unlock();
        }
    }

    @Override public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int srcWidth, int srcHeight, int srcDepth) {
        if (enterUnlocked()) {
            try {
                gl.copyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.copyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
        } finally {
            lock.unlock();
        }
    }

    @Override public void objectLabel(int identifier, int name, CharSequence label) {
        if (enterUnlocked()) {
            try {
                gl.objectLabel(identifier, name, label);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.objectLabel(identifier, name, label);
        } finally {
            lock.unlock();
        }
    }

    @Override public void pushDebugGroup(int source, int id, CharSequence message) {
        if (enterUnlocked()) {
            try {
                gl.pushDebugGroup(source, id, message);
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.pushDebugGroup(source, id, message);
        } finally {
            lock.unlock();
        }
    }

    @Override public void popDebugGroup() {
        if (enterUnlocked()) {
            try {
                gl.popDebugGroup();
            } finally {
                exitUnlocked();
            }
            return;
        }
        lock.lock();
        try {
            gl.popDebugGroup();
        } finally {
            lock.unlock();
        }
    }
}
