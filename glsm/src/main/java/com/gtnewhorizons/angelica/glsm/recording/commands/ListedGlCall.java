package com.gtnewhorizons.angelica.glsm.recording.commands;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.states.PixelStoreState;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;

/**
 * A call recorded for replay as it was made. GL takes a list's client pixels when it is compiled, under the pixel store of that moment,
 * since a mod is free to change either before the list is called.
 */
public final class ListedGlCall implements DisplayListCommand {
    private final Consumer<ByteBuffer> call;
    private final @Nullable ByteBuffer pixels;
    private final @Nullable PixelStoreState unpack;

    private ListedGlCall(Consumer<ByteBuffer> call, @Nullable ByteBuffer pixels, @Nullable PixelStoreState unpack) {
        this.call = call;
        this.pixels = pixels;
        this.unpack = unpack;
    }

    public static ListedGlCall of(Runnable call) {
        return new ListedGlCall(ignored -> call.run(), null, null);
    }

    public static ListedGlCall withPixels(@Nullable ByteBuffer pixels, PixelStoreState unpack, Consumer<ByteBuffer> call) {
        return new ListedGlCall(call, PixelDataSnapshot.copy(pixels), pixels == null ? null : unpack);
    }

    /** Takes ownership of pixels already copied, as those read from a buffer object at compile time are. */
    public static ListedGlCall withCopiedPixels(ByteBuffer pixels, PixelStoreState unpack, Consumer<ByteBuffer> call) {
        return new ListedGlCall(call, pixels, unpack);
    }

    public static ByteBuffer allocate(int bytes) {
        return memAlloc(bytes);
    }

    @Override
    public void execute() {
        if (unpack == null) {
            call.accept(pixels);
            return;
        }
        GLSMTextures.forcePixelUnpackState(unpack);
        try {
            call.accept(pixels);
        } finally {
            GLSMTextures.restorePixelUnpackState(unpack);
        }
    }

    @Override
    public void delete() {
        if (pixels != null) memFree(pixels);
    }
}
