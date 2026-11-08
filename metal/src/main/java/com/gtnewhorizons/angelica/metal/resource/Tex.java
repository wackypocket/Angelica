package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.TokenTex;

public final class Tex extends TokenTex {
    public Tex(int id, boolean renderbuffer) {
        super(id, renderbuffer);
    }

    public void copyStateTo(Tex s) {
        copyImageStateTo(s);
    }
}
