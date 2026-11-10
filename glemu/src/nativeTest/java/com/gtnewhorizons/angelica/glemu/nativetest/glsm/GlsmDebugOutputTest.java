package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glsm.GLDebug;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDebugOutputTest {

    private record Received(int source, int type, int id, int severity, String message) {}

    private static final int ID = 42;

    @AfterEach
    void reset() {
        GLSMDraws.registerDebugMessageListener(null, 0L);
        GL11C.glDisable(KHRDebug.GL_DEBUG_OUTPUT);
        NativeGlsmRig.gl().st().debug.drain(64, null, null, null, null, null, null);
    }

    private static List<Received> listen() {
        final List<Received> got = new ArrayList<>();
        GL11C.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
        GLSMDraws.registerDebugMessageListener((source, type, id, severity, length, message, userParam) ->
            got.add(new Received(source, type, id, severity, MemoryUtil.memUTF8(message, length))), 0L);
        return got;
    }

    private static void insert(int severity, String message) {
        KHRDebug.glDebugMessageInsert(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER, ID, severity, message);
    }

    @Test
    void outputIsOffUntilEnabled() {
        assertFalse(GL11C.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT));
        insert(KHRDebug.GL_DEBUG_SEVERITY_HIGH, "unheard");
        assertEquals(0, GL11C.glGetInteger(KHRDebug.GL_DEBUG_LOGGED_MESSAGES));
        GL11C.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
        assertTrue(GL11C.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT));
    }

    @Test
    void anInsertedMessageReachesTheCallback() {
        final List<Received> got = listen();
        insert(KHRDebug.GL_DEBUG_SEVERITY_HIGH, "hello");
        assertEquals(List.of(new Received(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER, ID, KHRDebug.GL_DEBUG_SEVERITY_HIGH, "hello")), got);
    }

    @Test
    void aBackendWarningReachesTheCallback() {
        final List<Received> got = listen();
        NativeGlsmRig.gl().warnOnce("a warning only this test gives");
        assertEquals(List.of(new Received(KHRDebug.GL_DEBUG_SOURCE_API, KHRDebug.GL_DEBUG_TYPE_OTHER, 0, KHRDebug.GL_DEBUG_SEVERITY_MEDIUM, "a warning only this test gives")), got);
    }

    @Test
    void withoutACallbackMessagesWaitInTheLog() {
        GL11C.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
        insert(KHRDebug.GL_DEBUG_SEVERITY_HIGH, "logged");
        assertEquals(1, GL11C.glGetInteger(KHRDebug.GL_DEBUG_LOGGED_MESSAGES));
        assertEquals("logged".length() + 1, GL11C.glGetInteger(KHRDebug.GL_DEBUG_NEXT_LOGGED_MESSAGE_LENGTH));
        final IntBuffer sources = BufferUtils.createIntBuffer(1), types = BufferUtils.createIntBuffer(1), ids = BufferUtils.createIntBuffer(1);
        final IntBuffer severities = BufferUtils.createIntBuffer(1), lengths = BufferUtils.createIntBuffer(1);
        final ByteBuffer text = BufferUtils.createByteBuffer(64);
        assertEquals(1, KHRDebug.glGetDebugMessageLog(1, sources, types, ids, severities, lengths, text));
        assertEquals(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, sources.get(0));
        assertEquals(KHRDebug.GL_DEBUG_TYPE_MARKER, types.get(0));
        assertEquals(ID, ids.get(0));
        assertEquals(KHRDebug.GL_DEBUG_SEVERITY_HIGH, severities.get(0));
        assertEquals("logged", MemoryUtil.memUTF8(text, lengths.get(0) - 1));
        assertEquals(0, GL11C.glGetInteger(KHRDebug.GL_DEBUG_LOGGED_MESSAGES));
    }

    @Test
    void lowSeverityIsOffUntilAskedFor() {
        final List<Received> got = listen();
        insert(KHRDebug.GL_DEBUG_SEVERITY_LOW, "quiet");
        assertTrue(got.isEmpty());
        KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_LOW, (IntBuffer) null, true);
        insert(KHRDebug.GL_DEBUG_SEVERITY_LOW, "heard");
        assertEquals(1, got.size());
        KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_LOW, (IntBuffer) null, false);
    }

    @Test
    void theLastMatchingControlDecides() {
        final List<Received> got = listen();
        final IntBuffer id = BufferUtils.createIntBuffer(1).put(0, ID);
        KHRDebug.glDebugMessageControl(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER, GL11.GL_DONT_CARE, id, false);
        insert(KHRDebug.GL_DEBUG_SEVERITY_HIGH, "muted");
        assertTrue(got.isEmpty());
        KHRDebug.glDebugMessageControl(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER, GL11.GL_DONT_CARE, id, true);
        insert(KHRDebug.GL_DEBUG_SEVERITY_HIGH, "back");
        assertEquals(1, got.size());
    }

    @Test
    void aDebugGroupAnnouncesItsEntryAndExit() {
        final List<Received> got = listen();
        final int depth = GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH);
        KHRDebug.glPushDebugGroup(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, ID, "pass");
        assertEquals(depth + 1, GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH));
        KHRDebug.glPopDebugGroup();
        assertEquals(depth, GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH));
        assertEquals(List.of(
            new Received(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_PUSH_GROUP, ID, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, "pass"),
            new Received(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_POP_GROUP, ID, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, "pass")), got);
    }

    @Test
    void eachThreadHasItsOwnStream() throws Exception {
        final List<Received> got = listen();
        NativeGlsmRig.onAnotherThread(true, () -> {
            final GlBase<?, ?, ?> gl = NativeGlsmRig.gl();
            assertFalse(gl.isEnabled(KHRDebug.GL_DEBUG_OUTPUT));
            gl.enable(KHRDebug.GL_DEBUG_OUTPUT);
            gl.debugMessageInsert(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER, ID, KHRDebug.GL_DEBUG_SEVERITY_HIGH, "theirs");
            assertEquals(1, gl.getInteger(KHRDebug.GL_DEBUG_LOGGED_MESSAGES));
        });
        assertTrue(got.isEmpty());
        assertTrue(GL11C.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT));
    }

    @Test
    void aThreadWithNoContextReportsToTheMainStream() throws Exception {
        final List<Received> got = listen();
        NativeGlsmRig.onAnotherThread(false, () -> NativeGlsmRig.gl().warnOnce("a warning from a thread with no context"));
        assertEquals(1, got.size());
        assertEquals("a warning from a thread with no context", got.get(0).message());
    }

    @Test
    void whatTheDriverReportsReachesTheStream() {
        final List<Received> got = listen();
        NativeGlsmRig.gl().gpuCaps().driverMessages.message(KHRDebug.GL_DEBUG_TYPE_ERROR, KHRDebug.GL_DEBUG_SEVERITY_HIGH, "from the driver");
        assertEquals(List.of(new Received(KHRDebug.GL_DEBUG_SOURCE_API, KHRDebug.GL_DEBUG_TYPE_ERROR, 0, KHRDebug.GL_DEBUG_SEVERITY_HIGH, "from the driver")), got);
    }

    @Test
    void angelicasOwnDebugHooksRunOnTheBackend() throws Exception {
        final Object before = Reflect.getStatic(GLDebug.class, "debugState");
        Reflect.setStatic(GLDebug.class, "debugState", Reflect.construct(Class.forName(GLDebug.class.getName() + "$KHRDebugState")));
        final int texture = GlsmHeadlessRig.newNearestTexture();
        try {
            assertEquals(2, GLDebug.setupDebugMessageCallback());
            assertTrue(GL11C.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT));
            final int depth = GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH);
            GLDebug.pushGroup("a group");
            assertEquals(depth + 1, GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH));
            GLDebug.debugMessage("a message");
            GLDebug.nameObject(GL11.GL_TEXTURE, texture, "a texture");
            assertEquals("a texture", NativeGlsmRig.gl().objectLabelOf(GL11.GL_TEXTURE, texture));
            GLDebug.popGroup();
            assertEquals(depth, GL11C.glGetInteger(KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH));
            NativeGlsmRig.gl().warnOnce("a warning for Angelica's own log");
            GLDebug.disableDebugMessages();
            assertFalse(GL11C.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT));
        } finally {
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, (IntBuffer) null, true);
            GLSMTextures.glDeleteTextures(texture);
            Reflect.setStatic(GLDebug.class, "debugState", before);
        }
    }
}
