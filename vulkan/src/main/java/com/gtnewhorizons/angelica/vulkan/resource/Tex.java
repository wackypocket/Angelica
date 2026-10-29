package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.vulkan.memory.Allocation;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

import static org.lwjgl.vulkan.VK10.*;

public final class Tex extends TexBase {
    public Allocation memory;
    public int layout = VK_IMAGE_LAYOUT_UNDEFINED;
    public int discardedFrom = VK_IMAGE_LAYOUT_UNDEFINED;

    public long uploadSerial = -1, uploadImage;
    public int uploadAspect, uploadFinalLayout;
    public int[] uploadRects = new int[9 * 4];
    public int uploadRectCount;
    public long copySerial = -1;
    public int[] copyRects = new int[9 * 4];
    public int copyRectCount;

    public final Int2LongOpenHashMap attachmentViews = new Int2LongOpenHashMap();
    public long storageView;
    public int storageViewKey = -1;

    public Tex(int id, boolean renderbuffer) {
        super(id, renderbuffer);
    }
}
