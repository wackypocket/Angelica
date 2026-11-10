package com.gtnewhorizons.angelica.glsm.backend;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What a running game asks of GL, for telling which differences from a real driver matter: how often each function is reached, and each
 * call refused, with the class that made it. Off unless -Dangelica.glCallLog names a file, since finding a caller walks the stack.
 * glemu/scripts/summarize_gl_calls.py reads the file. A call GLSM answers from its cache never reaches a backend, so is not counted.
 */
public final class GlCallLog {
    private static final String PATH = System.getProperty("angelica.glCallLog");
    public static final boolean ENABLED = PATH != null;
    private static final long WRITE_EVERY_MILLIS = 30_000L;
    private static final List<String> NOT_A_CALLER = List.of("java.", "jdk.", "sun.", "org.lwjgl.");
    private static final List<String> OURS = List.of("com.gtnewhorizons.angelica.glsm.", "com.gtnewhorizons.angelica.glemu.", "com.gtnewhorizons.angelica.vulkan.", "com.gtnewhorizons.angelica.metal.");
    private static final String TESTS = ".nativetest.";

    private static final Map<String, LongAdder> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> REFUSALS = new ConcurrentHashMap<>();

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> write(Path.of(PATH)), "GL call log"));
            final Thread writer = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(WRITE_EVERY_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    write(Path.of(PATH));
                }
            }, "GL call log");
            writer.setDaemon(true);
            writer.start();
        }
    }

    private GlCallLog() {}

    public static void call(String function) {
        CALLS.computeIfAbsent(function, k -> new LongAdder()).increment();
    }

    /** A call GL refused, or that nothing implements. The function is the outermost GL call in our code, and the caller is what made it. */
    public static void refusal(String what) {
        final String[] found = StackWalker.getInstance().walk(frames -> {
            final String[] result = {null, "unknown"};
            frames.dropWhile(f -> f.getClassName().equals(GlCallLog.class.getName())).forEach(f -> {
                if (!result[1].equals("unknown")) return;
                final String name = f.getClassName();
                if (OURS.stream().anyMatch(name::startsWith) && !name.contains(TESTS)) {
                    if (f.getMethodName().startsWith("gl") || result[0] == null) result[0] = f.getMethodName();
                } else if (NOT_A_CALLER.stream().noneMatch(name::startsWith)) {
                    result[1] = name;
                }
            });
            return result;
        });
        REFUSALS.computeIfAbsent(found[0] + "\t" + what + "\t" + found[1], k -> new LongAdder()).increment();
    }

    public static void refusal(int error) {
        refusal(switch (error) {
            case 0x0500 -> "GL_INVALID_ENUM";
            case 0x0501 -> "GL_INVALID_VALUE";
            case 0x0502 -> "GL_INVALID_OPERATION";
            case 0x0503 -> "GL_STACK_OVERFLOW";
            case 0x0504 -> "GL_STACK_UNDERFLOW";
            case 0x0505 -> "GL_OUT_OF_MEMORY";
            case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "0x" + Integer.toHexString(error);
        });
    }

    static synchronized void write(Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
                w.println("# call\tfunction\tcount, then refusal\tfunction\twhy\tcaller\tcount");
                CALLS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> w.println("call\t" + e.getKey() + "\t" + e.getValue().sum()));
                REFUSALS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> w.println("refusal\t" + e.getKey() + "\t" + e.getValue().sum()));
            }
        } catch (IOException e) {
            // Losing a diagnostic file is no reason to disturb the game; the next write may work.
        }
    }
}
