package com.gtnewhorizons.angelica.glemu.util;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/** GL's debug message log: a bounded queue that glGetDebugMessageLog drains, losing the oldest message when full. */
public final class DebugMessageRing {
    private record Message(int source, int type, int id, int severity, byte[] utf8) {}

    private final Message[] messages;
    private int head;
    private int count;

    public DebugMessageRing(int capacity) {
        messages = new Message[capacity];
    }

    public synchronized void record(int source, int type, int id, int severity, String message) {
        messages[head] = new Message(source, type, id, severity, message.getBytes(StandardCharsets.UTF_8));
        head = (head + 1) % messages.length;
        if (count < messages.length) count++;
    }

    public synchronized int size() {
        return count;
    }

    /** The next message's length with its terminator, or 0 when the log is empty. */
    public synchronized int nextLength() {
        return count == 0 ? 0 : messages[tail()].utf8.length + 1;
    }

    private int tail() {
        return (head - count + messages.length) % messages.length;
    }

    public synchronized int drain(int max, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer log) {
        int written = 0;
        while (written < max && count > 0) {
            final Message m = messages[tail()];
            final int needed = m.utf8.length + 1; // GL counts the NUL terminator in `lengths`
            if (log != null && log.remaining() < needed) break;
            if (log != null) log.put(m.utf8).put((byte) 0);
            if (sources != null) sources.put(m.source);
            if (types != null) types.put(m.type);
            if (ids != null) ids.put(m.id);
            if (severities != null) severities.put(m.severity);
            if (lengths != null) lengths.put(needed);
            messages[tail()] = null;
            count--;
            written++;
        }
        return written;
    }
}
