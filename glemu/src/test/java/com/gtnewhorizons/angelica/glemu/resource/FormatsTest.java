package com.gtnewhorizons.angelica.glemu.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatsTest {

    @Test
    void theUnsignedPackedFormatIsAnIntegerFormat() {
        assertTrue(Formats.of(GL33.GL_RGB10_A2UI, 0, 0).isInteger());
        assertFalse(Formats.of(GL11.GL_RGB10_A2, 0, 0).isInteger());
    }

    @ParameterizedTest
    @ValueSource(ints = {GL30.GL_R8I, GL30.GL_R8UI, GL30.GL_RG8, GL30.GL_RG8I, GL30.GL_RG8UI, GL30.GL_RG16F, GL30.GL_RG16I,
        GL11.GL_RGBA8, GL30.GL_RGBA8I, GL30.GL_RGBA16F, GL30.GL_RGBA16I, GL30.GL_RG32I, GL30.GL_RGBA32F, GL30.GL_RGBA32I})
    void texelBytesAgreeBetweenTheFormatAndItsPixelFormat(int glInternal) {
        final Formats.Format f = Formats.of(glInternal, 0, 0);
        assertEquals(f.texelBytes(), Formats.texelBytesOf(f.pf()), "format 0x" + Integer.toHexString(glInternal));
    }
}
