package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import com.gtnewhorizons.angelica.glsm.states.ViewportState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Software emulation of GL feedback mode
 */
public class FeedbackManager {

    private static final int FB_3D = 0x01;
    private static final int FB_4D = 0x02;
    private static final int FB_COLOR = 0x04;
    private static final int FB_TEXTURE = 0x08;

    private static int renderMode = GL11.GL_RENDER;
    private static FloatBuffer feedbackBuffer;
    private static int feedbackBufferSize;
    private static int feedbackMask;
    private static int feedbackType = GL11.GL_2D;
    private static int feedbackCount;

    private static IntBuffer selectBuffer;
    private static int selectCount;
    private static int hitRecords;
    private static boolean selectOverflow;
    private static boolean hit;
    private static float hitMinZ, hitMaxZ;
    private static final int[] nameStack = new int[64];
    private static int nameDepth;
    private static float[] primitive = new float[64];
    private static float[] clipScratch = new float[64];
    private static int primitiveVertices;

    private static int immediateMode;
    private static int immediateCount;

    private static final Matrix4f mvpMatrix = new Matrix4f();
    private static final Vector4f clipVec = new Vector4f();

    private static ByteBuffer readbackBuf = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder());
    private static ByteBuffer indexReadbackBuf = ByteBuffer.allocateDirect(4 * 1024).order(ByteOrder.nativeOrder());

    @FunctionalInterface
    private interface IndexReader {

        int read(int i);
    }

    public static boolean isFeedbackMode() {
        return renderMode == GL11.GL_FEEDBACK || isSelecting();
    }

    /** Only a native backend takes a select buffer, so on a GL driver nothing is intercepted. */
    private static boolean isSelecting() {
        return renderMode == GL11.GL_SELECT && selectBuffer != null;
    }

    /** Immediate draws normally bypass the draw calls this class intercepts; only the native backends route them here. */
    public static boolean capturesImmediate() {
        return isSelecting() || renderMode == GL11.GL_FEEDBACK && RENDER_BACKEND.isNative();
    }

    public static void immediateBegin(int mode) {
        immediateMode = mode;
        immediateCount = 0;
    }

    public static void immediateVertex(float x, float y, float z) {
        readbackBuf.limit(readbackBuf.capacity());
        if ((immediateCount + 1) * 12 > readbackBuf.capacity()) {
            final ByteBuffer grown = ByteBuffer.allocateDirect(readbackBuf.capacity() * 2).order(ByteOrder.nativeOrder());
            grown.put(0, readbackBuf, 0, immediateCount * 12);
            readbackBuf = grown;
        }
        readbackBuf.putFloat(immediateCount * 12, x).putFloat(immediateCount * 12 + 4, y).putFloat(immediateCount * 12 + 8, z);
        immediateCount++;
    }

    public static void processImmediate() {
        computeMVP();
        emitAllPrimitives(immediateMode, immediateCount, i -> i, 12);
        immediateCount = 0;
    }

    public static void glSelectBuffer(IntBuffer buffer) {
        if (renderMode == GL11.GL_SELECT) {
            GLStateManager.LOGGER.error("glSelectBuffer called while already in selection mode");
            return;
        }
        selectBuffer = buffer;
    }

    public static void glInitNames() {
        if (!isSelecting()) return;
        writeHit();
        nameDepth = 0;
    }

    public static void glPushName(int name) {
        if (!isSelecting()) return;
        writeHit();
        if (nameDepth < nameStack.length) nameStack[nameDepth++] = name;
    }

    public static void glPopName() {
        if (!isSelecting()) return;
        writeHit();
        if (nameDepth > 0) nameDepth--;
    }

    public static void glLoadName(int name) {
        if (!isSelecting()) return;
        writeHit();
        if (nameDepth > 0) nameStack[nameDepth - 1] = name;
    }

    /** A hit record: the name count, the depth range hit since the names last changed, then the names. */
    private static void writeHit() {
        if (!hit) return;
        hit = false;
        if (selectCount + 3 + nameDepth > selectBuffer.remaining()) {
            selectOverflow = true;
            return;
        }
        final int at = selectBuffer.position() + selectCount;
        selectBuffer.put(at, nameDepth);
        selectBuffer.put(at + 1, (int) (long) (hitMinZ * 4294967295.0));
        selectBuffer.put(at + 2, (int) (long) (hitMaxZ * 4294967295.0));
        for (int i = 0; i < nameDepth; i++) selectBuffer.put(at + 3 + i, nameStack[i]);
        selectCount += 3 + nameDepth;
        hitRecords++;
    }

    private static void selectVertex() {
        if ((primitiveVertices + 1) * 4 > primitive.length) primitive = Arrays.copyOf(primitive, primitive.length * 2);
        final int at = primitiveVertices++ * 4;
        primitive[at] = clipVec.x;
        primitive[at + 1] = clipVec.y;
        primitive[at + 2] = clipVec.z;
        primitive[at + 3] = clipVec.w;
    }

    /** Clips the assembled primitive to the view volume; whatever survives is a hit at that depth. */
    private static void flushPrimitive() {
        int count = primitiveVertices;
        primitiveVertices = 0;
        if (count == 0) return;
        for (int plane = 0; plane < 6 && count > 0; plane++) count = clipAgainst(plane, count);
        if (count == 0) return;
        final ViewportState vp = GLSMRasterizer.getViewportState();
        for (int i = 0; i < count; i++) {
            final float ndc = primitive[i * 4 + 2] / primitive[i * 4 + 3];
            final float z = (float) (vp.depthRangeNear + (vp.depthRangeFar - vp.depthRangeNear) * (ndc + 1.0) * 0.5);
            hitMinZ = hit ? Math.min(hitMinZ, z) : z;
            hitMaxZ = hit ? Math.max(hitMaxZ, z) : z;
            hit = true;
        }
    }

    /** One Sutherland-Hodgman pass in clip space, where plane 0-5 is w-x, w+x, w-y, w+y, w-z, w+z. */
    private static int clipAgainst(int plane, int count) {
        if ((count * 2 + 1) * 4 > clipScratch.length) clipScratch = new float[(count * 2 + 1) * 8];
        int out = 0;
        for (int i = 0; i < count; i++) {
            final int a = i * 4, b = (i + 1) % count * 4;
            final float da = planeDistance(plane, a), db = planeDistance(plane, b);
            if (da >= 0) {
                System.arraycopy(primitive, a, clipScratch, out++ * 4, 4);
            }
            if (da >= 0 != db >= 0) {
                final float t = da / (da - db);
                for (int c = 0; c < 4; c++) clipScratch[out * 4 + c] = primitive[a + c] + (primitive[b + c] - primitive[a + c]) * t;
                out++;
            }
        }
        if (out * 4 > primitive.length) primitive = new float[clipScratch.length];
        System.arraycopy(clipScratch, 0, primitive, 0, out * 4);
        return out;
    }

    private static float planeDistance(int plane, int vertex) {
        final float value = primitive[vertex + plane / 2];
        return primitive[vertex + 3] + (plane % 2 == 0 ? -value : value);
    }

    public static int nameStackDepth() { return nameDepth; }
    public static int maxNameStackDepth() { return nameStack.length; }
    public static int feedbackBufferSize() { return feedbackBuffer == null ? 0 : feedbackBufferSize; }
    public static int feedbackBufferType() { return feedbackType; }
    public static int selectionBufferSize() { return selectBuffer == null ? 0 : selectBuffer.remaining(); }

    public static int getRenderMode() {
        return renderMode;
    }

    public static void glFeedbackBuffer(int type, FloatBuffer buffer) {
        if (type != GL11.GL_2D && type != GL11.GL_3D && type != GL11.GL_3D_COLOR && type != GL11.GL_3D_COLOR_TEXTURE && type != GL11.GL_4D_COLOR_TEXTURE) {
            GLStateManager.recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        if (renderMode == GL11.GL_FEEDBACK) {
            GLStateManager.recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        feedbackBuffer = buffer;
        feedbackBufferSize = buffer.remaining();
        feedbackType = type;
        feedbackMask = computeMask(type);
        feedbackCount = 0;
    }

    public static int glRenderMode(int mode) {
        if (mode != GL11.GL_RENDER && mode != GL11.GL_SELECT && mode != GL11.GL_FEEDBACK) {
            GLStateManager.recordError(GL11.GL_INVALID_ENUM);
            return 0;
        }
        if (isSelecting()) writeHit();
        int result = switch (renderMode) {
            case GL11.GL_FEEDBACK -> (feedbackCount > feedbackBufferSize) ? -1 : feedbackCount;
            case GL11.GL_SELECT -> selectOverflow ? -1 : hitRecords;
            case GL11.GL_RENDER -> 0;
            default -> 0;
        };

        selectCount = hitRecords = nameDepth = 0;
        selectOverflow = hit = false;
        if (mode == GL11.GL_SELECT && selectBuffer == null) {
            GLStateManager.LOGGER.warn("glRenderMode(GL_SELECT): selection mode not emulated");
        }

        feedbackCount = 0;
        renderMode = mode;
        return result;
    }

    public static void glPassThrough(float token) {
        if (renderMode == GL11.GL_FEEDBACK) {
            feedbackToken(GL11.GL_PASS_THROUGH_TOKEN);
            feedbackToken(token);
        }
    }

    private static void feedbackToken(float token) {
        if (isSelecting()) {
            flushPrimitive();
            return;
        }
        if (feedbackCount < feedbackBufferSize) {
            feedbackBuffer.put(feedbackBuffer.position() + feedbackCount, token);
        }
        feedbackCount++;
    }

    private static void feedbackVertex(float winX, float winY, float winZ, float winW) {
        feedbackToken(winX);
        feedbackToken(winY);
        if ((feedbackMask & FB_3D) != 0) feedbackToken(winZ);
        if ((feedbackMask & FB_4D) != 0) feedbackToken(winW);
        if ((feedbackMask & FB_COLOR) != 0) {
            feedbackToken(1f);
            feedbackToken(1f);
            feedbackToken(1f);
            feedbackToken(1f);
        }
        if ((feedbackMask & FB_TEXTURE) != 0) {
            feedbackToken(0f);
            feedbackToken(0f);
            feedbackToken(0f);
            feedbackToken(1f);
        }
    }

    private static void transformAndEmitVertex(float x, float y, float z) {
        clipVec.set(x, y, z, 1.0f);
        mvpMatrix.transform(clipVec);
        if (isSelecting()) {
            selectVertex();
            return;
        }

        final float w = clipVec.w;
        final float ndcX = clipVec.x / w;
        final float ndcY = clipVec.y / w;
        final float ndcZ = clipVec.z / w;

        final ViewportState vp = GLSMRasterizer.getViewportState();
        final float winX = vp.x + vp.width * (ndcX + 1.0f) * 0.5f;
        final float winY = vp.y + vp.height * (ndcY + 1.0f) * 0.5f;
        final float winZ = (float) ((vp.depthRangeFar - vp.depthRangeNear) * ndcZ + (vp.depthRangeFar + vp.depthRangeNear)) * 0.5f;
        final float winW = 1.0f / w;

        feedbackVertex(winX, winY, winZ, winW);
    }

    private static int positionStride(GLContextState glCtx) {
        final int s = glCtx.vaos.attribEffectiveStride(0);
        return (s != 0) ? s : 12;
    }

    private static long positionOffset(GLContextState glCtx) {
        return glCtx.vaos.attribOffset(0);
    }

    public static void processDrawArrays(int mode, int first, int count) {
        computeMVP();
        final GLContextState glCtx = GLStateManager.ctx();
        if (!glCtx.vaos.isAttribEnabled(0)) return;
        final int stride = positionStride(glCtx);
        final long posOffset = positionOffset(glCtx);
        final int byteCount = count * stride;
        ensureReadbackCapacity(byteCount);
        readbackBuf.clear().limit(byteCount);
        readPositionBuffer((long) first * stride + posOffset, readbackBuf, glCtx);

        emitAllPrimitives(mode, count, i -> i, stride);
    }

    public static void processDrawElements(int mode, int indexCount, int type, long eboOffset) {
        computeMVP();
        final GLContextState glCtx = GLStateManager.ctx();
        if (!glCtx.vaos.isAttribEnabled(0)) return;
        final int stride = positionStride(glCtx);

        final int elementSize = GLTypes.sizeBytes(type);
        final int indexByteCount = indexCount * elementSize;
        ensureIndexReadbackCapacity(indexByteCount);
        indexReadbackBuf.clear().limit(indexByteCount);
        RENDER_BACKEND.getBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboOffset, indexReadbackBuf);
        final ByteBuffer indexBuf = indexReadbackBuf;

        int minIdx = Integer.MAX_VALUE, maxIdx = Integer.MIN_VALUE;
        for (int i = 0; i < indexCount; i++) {
            final int idx = ImmediateModeRecorder.readIndex(indexBuf, type, 0, i);
            if (idx < minIdx) minIdx = idx;
            if (idx > maxIdx) maxIdx = idx;
        }

        final long posOffset = positionOffset(glCtx);
        final int vertexCount = maxIdx - minIdx + 1;
        final int byteCount = vertexCount * stride;
        ensureReadbackCapacity(byteCount);
        readbackBuf.clear().limit(byteCount);
        readPositionBuffer((long) minIdx * stride + posOffset, readbackBuf, glCtx);

        final int base = minIdx;
        emitAllPrimitives(mode, indexCount, i -> ImmediateModeRecorder.readIndex(indexBuf, type, 0, i) - base, stride);
    }

    public static void processDrawElements(int mode, ByteBuffer indices) {
        final int basePos = indices.position();
        processDrawElementsImpl(mode, indices.remaining(), i -> indices.get(basePos + i) & 0xFF);
    }

    public static void processDrawElements(int mode, ShortBuffer indices) {
        final int basePos = indices.position();
        processDrawElementsImpl(mode, indices.remaining(), i -> indices.get(basePos + i) & 0xFFFF);
    }

    public static void processDrawElements(int mode, IntBuffer indices) {
        final int basePos = indices.position();
        processDrawElementsImpl(mode, indices.remaining(), i -> indices.get(basePos + i));
    }

    public static void processDrawElements(int mode, int count, int type, ByteBuffer indices) {
        final int pos = indices.position();
        final IndexReader reader = switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> i -> indices.get(pos + i) & 0xFF;
            case GL11.GL_UNSIGNED_SHORT -> i -> indices.getShort(pos + i * 2) & 0xFFFF;
            case GL11.GL_UNSIGNED_INT -> i -> indices.getInt(pos + i * 4);
            default -> i -> 0;
        };
        processDrawElementsImpl(mode, count, reader);
    }

    private static void processDrawElementsImpl(int mode, int count, IndexReader reader) {
        computeMVP();
        final GLContextState glCtx = GLStateManager.ctx();
        if (!glCtx.vaos.isAttribEnabled(0)) return;
        final int stride = positionStride(glCtx);

        int minIdx = Integer.MAX_VALUE, maxIdx = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            final int idx = reader.read(i);
            if (idx < minIdx) minIdx = idx;
            if (idx > maxIdx) maxIdx = idx;
        }

        readVerticesFromVBO(minIdx, maxIdx, stride, glCtx);
        final int base = minIdx;
        emitAllPrimitives(mode, count, i -> reader.read(i) - base, stride);
    }

    private static void computeMVP() {
        mvpMatrix.set(GLSMMatrices.getProjectionMatrix()).mul(GLSMMatrices.getModelViewMatrix());
    }

    private static int computeMask(int type) {
        return switch (type) {
            case GL11.GL_2D -> 0;
            case GL11.GL_3D -> FB_3D;
            case GL11.GL_3D_COLOR -> FB_3D | FB_COLOR;
            case GL11.GL_3D_COLOR_TEXTURE -> FB_3D | FB_COLOR | FB_TEXTURE;
            case GL11.GL_4D_COLOR_TEXTURE -> FB_3D | FB_4D | FB_COLOR | FB_TEXTURE;
            default -> 0;
        };
    }

    private static void ensureReadbackCapacity(int bytes) {
        if (readbackBuf.capacity() < bytes) {
            readbackBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
    }

    private static void ensureIndexReadbackCapacity(int bytes) {
        if (indexReadbackBuf.capacity() < bytes) {
            indexReadbackBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
    }

    private static void readVerticesFromVBO(int minIdx, int maxIdx, int stride, GLContextState glCtx) {
        final long posOffset = positionOffset(glCtx);
        final int vertexCount = maxIdx - minIdx + 1;
        final int byteCount = vertexCount * stride;
        ensureReadbackCapacity(byteCount);
        readbackBuf.clear().limit(byteCount);
        readPositionBuffer((long) minIdx * stride + posOffset, readbackBuf, glCtx);
    }

    private static void readPositionBuffer(long offset, ByteBuffer destination, GLContextState glCtx) {
        final VAOManager.Attrib position = glCtx.vaos.attrib(0);
        if (position != null && position.clientPointer != null) {
            final ByteBuffer source = position.clientPointer.duplicate();
            source.position(source.position() + (int) offset);
            if (source.remaining() > destination.remaining()) source.limit(source.position() + destination.remaining());
            destination.duplicate().put(source);
            return;
        }
        final int positionVbo = glCtx.vaos.attribVBO(0);
        final int previousVbo = glCtx.boundVBO;

        if (positionVbo != 0 && positionVbo != previousVbo) {
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVbo);
        }
        try {
            RENDER_BACKEND.getBufferSubData(GL15.GL_ARRAY_BUFFER, offset, destination);
        } finally {
            if (positionVbo != 0 && positionVbo != previousVbo) {
                GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousVbo);
            }
        }
    }

    private static void emitVertex(int vertexIndex, int stride) {
        final float x = readbackBuf.getFloat(vertexIndex * stride);
        final float y = readbackBuf.getFloat(vertexIndex * stride + 4);
        final float z = readbackBuf.getFloat(vertexIndex * stride + 8);
        transformAndEmitVertex(x, y, z);
    }

    private static void emitAllPrimitives(int mode, int count, IndexReader reader, int stride) {
        switch (mode) {
            case GL11.GL_POINTS:
                for (int i = 0; i < count; i++) {
                    feedbackToken(GL11.GL_POINT_TOKEN);
                    emitVertex(reader.read(i), stride);
                }
                break;

            case GL11.GL_LINES:
                for (int i = 0; i + 1 < count; i += 2) {
                    feedbackToken(i == 0 ? GL11.GL_LINE_RESET_TOKEN : GL11.GL_LINE_TOKEN);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                }
                break;

            case GL11.GL_LINE_STRIP:
                for (int i = 0; i + 1 < count; i++) {
                    feedbackToken(i == 0 ? GL11.GL_LINE_RESET_TOKEN : GL11.GL_LINE_TOKEN);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                }
                break;

            case GL11.GL_LINE_LOOP:
                for (int i = 0; i + 1 < count; i++) {
                    feedbackToken(i == 0 ? GL11.GL_LINE_RESET_TOKEN : GL11.GL_LINE_TOKEN);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                }
                if (count > 2) {
                    feedbackToken(GL11.GL_LINE_TOKEN);
                    emitVertex(reader.read(count - 1), stride);
                    emitVertex(reader.read(0), stride);
                }
                break;

            case GL11.GL_TRIANGLES:
                for (int i = 0; i + 2 < count; i += 3) {
                    feedbackToken(GL11.GL_POLYGON_TOKEN);
                    feedbackToken(3);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                    emitVertex(reader.read(i + 2), stride);
                }
                break;

            case GL11.GL_TRIANGLE_STRIP:
                for (int i = 0; i + 2 < count; i++) {
                    feedbackToken(GL11.GL_POLYGON_TOKEN);
                    feedbackToken(3);
                    if ((i & 1) == 0) {
                        emitVertex(reader.read(i), stride);
                        emitVertex(reader.read(i + 1), stride);
                    } else {
                        emitVertex(reader.read(i + 1), stride);
                        emitVertex(reader.read(i), stride);
                    }
                    emitVertex(reader.read(i + 2), stride);
                }
                break;

            case GL11.GL_TRIANGLE_FAN:
                for (int i = 1; i + 1 < count; i++) {
                    feedbackToken(GL11.GL_POLYGON_TOKEN);
                    feedbackToken(3);
                    emitVertex(reader.read(0), stride);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                }
                break;

            case GL11.GL_QUADS:
                for (int i = 0; i + 3 < count; i += 4) {
                    feedbackToken(GL11.GL_POLYGON_TOKEN);
                    feedbackToken(4);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                    emitVertex(reader.read(i + 2), stride);
                    emitVertex(reader.read(i + 3), stride);
                }
                break;

            case GL11.GL_QUAD_STRIP:
                for (int i = 0; i + 3 < count; i += 2) {
                    feedbackToken(GL11.GL_POLYGON_TOKEN);
                    feedbackToken(4);
                    emitVertex(reader.read(i), stride);
                    emitVertex(reader.read(i + 1), stride);
                    emitVertex(reader.read(i + 3), stride);
                    emitVertex(reader.read(i + 2), stride);
                }
                break;

            case GL11.GL_POLYGON:
                if (count >= 3) {
                    feedbackToken(GL11.GL_POLYGON_TOKEN);
                    feedbackToken(count);
                    for (int i = 0; i < count; i++) {
                        emitVertex(reader.read(i), stride);
                    }
                }
                break;

            default:
                throw new UnsupportedOperationException("Feedback mode: unsupported primitive mode 0x" + Integer.toHexString(mode));
        }
        if (isSelecting()) flushPrimitive();
    }

    static void reset() {
        renderMode = GL11.GL_RENDER;
        selectBuffer = null;
        selectCount = hitRecords = nameDepth = 0;
        selectOverflow = hit = false;
        feedbackBuffer = null;
        feedbackBufferSize = 0;
        feedbackMask = 0;
        feedbackCount = 0;
    }
}
