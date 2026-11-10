package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.util.DebugMessageRing;
import com.gtnewhorizons.angelica.glsm.backend.GLDebugMessageListener;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.system.MemoryUtil;

/** One context's KHR_debug message stream: what the application inserts and what the backend warns about, filtered as GL specifies. */
public final class DebugOutput {
    private static final Logger LOG = LogManager.getLogger("Angelica/GPU");
    private static final int MAX_RULES = 256, MAX_MESSAGE_BYTES = 1023;

    private record Rule(int source, int type, int severity, int[] ids, boolean enabled) {
        boolean matches(int source, int type, int id, int severity) {
            if (this.source != GL11.GL_DONT_CARE && this.source != source) return false;
            if (this.type != GL11.GL_DONT_CARE && this.type != type) return false;
            if (this.severity != GL11.GL_DONT_CARE && this.severity != severity) return false;
            if (ids == null) return true;
            for (int candidate : ids) if (candidate == id) return true;
            return false;
        }
    }

    private record Group(int source, int id, String message) {}

    private final DebugMessageRing log = new DebugMessageRing(64);
    private final List<Rule> rules = new ArrayList<>();
    private final ArrayDeque<Group> groups = new ArrayDeque<>();
    private volatile GLDebugMessageListener listener;
    private volatile long userParam;
    private volatile boolean enabled, synchronous;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    public boolean isSynchronous() {
        return synchronous;
    }

    /** Messages are always delivered on the calling thread, so this is only remembered for the query. */
    public void setSynchronous(boolean on) {
        synchronous = on;
    }

    public void setCallback(GLDebugMessageListener callback, long param) {
        userParam = param;
        listener = callback;
    }

    public synchronized void control(int source, int type, int severity, IntBuffer ids, boolean on) {
        int[] listed = null;
        if (ids != null && ids.hasRemaining()) {
            listed = new int[ids.remaining()];
            ids.get(ids.position(), listed);
        }
        if (rules.size() >= MAX_RULES) rules.remove(0);
        rules.add(new Rule(source, type, severity, listed, on));
    }

    private synchronized boolean passes(int source, int type, int id, int severity) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            final Rule rule = rules.get(i);
            if (rule.matches(source, type, id, severity)) return rule.enabled;
        }
        return severity != KHRDebug.GL_DEBUG_SEVERITY_LOW;
    }

    /** Keeps a message within GL_MAX_DEBUG_MESSAGE_LENGTH, terminator included, without cutting a character in two. */
    private static String clip(String message) {
        final byte[] utf8 = message.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= MAX_MESSAGE_BYTES) return message;
        int end = MAX_MESSAGE_BYTES;
        while (end > 0 && (utf8[end] & 0xC0) == 0x80) end--;
        return new String(utf8, 0, end, StandardCharsets.UTF_8);
    }

    public void post(int source, int type, int id, int severity, String message) {
        if (!enabled || !passes(source, type, id, severity)) return;
        final String clipped = clip(message);
        final GLDebugMessageListener target = listener;
        if (target == null) {
            log.record(source, type, id, severity, clipped);
            return;
        }
        final ByteBuffer text = MemoryUtil.memUTF8(clipped);
        try {
            target.onMessage(source, type, id, severity, text.remaining() - 1, MemoryUtil.memAddress(text), userParam);
        } catch (Throwable t) {
            LOG.warn("GL debug message callback threw", t);
        } finally {
            MemoryUtil.memFree(text);
        }
    }

    public void postWarning(String message) {
        post(KHRDebug.GL_DEBUG_SOURCE_API, KHRDebug.GL_DEBUG_TYPE_OTHER, 0, KHRDebug.GL_DEBUG_SEVERITY_MEDIUM, message);
    }

    public void pushGroup(int source, int id, String message) {
        synchronized (this) {
            groups.push(new Group(source, id, message));
        }
        post(source, KHRDebug.GL_DEBUG_TYPE_PUSH_GROUP, id, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, message);
    }

    public void popGroup() {
        final Group group;
        synchronized (this) {
            group = groups.poll();
        }
        if (group != null) post(group.source, KHRDebug.GL_DEBUG_TYPE_POP_GROUP, group.id, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, group.message);
    }

    public synchronized int groupDepth() {
        return groups.size() + 1;
    }

    public int loggedMessages() {
        return log.size();
    }

    public int nextLoggedLength() {
        return log.nextLength();
    }

    public int drain(int count, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer messageLog) {
        return log.drain(count, sources, types, ids, severities, lengths, messageLog);
    }
}
