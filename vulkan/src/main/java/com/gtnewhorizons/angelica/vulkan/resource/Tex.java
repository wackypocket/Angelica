package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.vulkan.memory.Allocation;

import static org.lwjgl.vulkan.VK10.*;

public final class Tex extends TexBase {
    public Allocation memory;
    public boolean presentShared;
    public int layout = VK_IMAGE_LAYOUT_UNDEFINED;
    public int discardedFrom = VK_IMAGE_LAYOUT_UNDEFINED;

    public long uploadSerial = -1, uploadImage;
    public int uploadAspect, uploadFinalLayout;
    public int[] uploadRects = new int[9 * 4];
    public int uploadRectCount;
    public long copySerial = -1;
    public int[] copyRects = new int[9 * 4];
    public int copyRectCount;

    /** Keyed by level, layer, layer count and sRGB writes, which a 16384-deep 3D texture needs more than an int for. */
    public final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap attachmentViews = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    public Tex(int id, boolean renderbuffer) {
        super(id, renderbuffer);
    }

    public void copyStateTo(Tex s) {
        copyImageStateTo(s);
        s.memory = memory;
        s.layout = layout;
        s.discardedFrom = discardedFrom;
        s.attachmentViews.putAll(attachmentViews);
    }
}
