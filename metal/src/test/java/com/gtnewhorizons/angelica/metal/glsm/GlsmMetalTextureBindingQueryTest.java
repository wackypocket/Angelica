package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalTextureBindingQueryTest {

    @Test
    void eachBindingQueryReadsItsOwnTargetOnTheActiveUnit() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE3);
        final int tex = GLSMTextures.glGenTextures();
        try {
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, tex);
            assertEquals(tex, RENDER_BACKEND.getInteger(GL12.GL_TEXTURE_BINDING_3D), "GL_TEXTURE_BINDING_3D");
            assertEquals(0, RENDER_BACKEND.getInteger(GL11.GL_TEXTURE_BINDING_2D), "GL_TEXTURE_BINDING_2D");
            assertEquals(0, RENDER_BACKEND.getInteger(GL11.GL_TEXTURE_BINDING_1D), "GL_TEXTURE_BINDING_1D");
            assertEquals(0, RENDER_BACKEND.getInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP), "GL_TEXTURE_BINDING_CUBE_MAP");
            assertEquals(0, RENDER_BACKEND.getInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY), "GL_TEXTURE_BINDING_2D_ARRAY");
            assertEquals(0, RENDER_BACKEND.getInteger(GL31.GL_TEXTURE_BINDING_RECTANGLE), "GL_TEXTURE_BINDING_RECTANGLE");

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(0, RENDER_BACKEND.getInteger(GL12.GL_TEXTURE_BINDING_3D), "another unit's 3D binding");
        } finally {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE3);
            GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glDeleteTextures(tex);
        }
    }
}
