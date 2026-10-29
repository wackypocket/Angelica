package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextureLifetime;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.SIZE;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.describe;
import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.pixelAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalVanillaTextureTest {

    private static final int GL_TEXTURE_MAX_ANISOTROPY_EXT = 34046;

    private static final int ATLAS_SIZE = 64;
    private static final int ATLAS_MIPMAP_LEVELS = 4;
    private static final int[] LEVEL_COLORS = {
        0xFFFF4020,
        0xFF00FF00,
        0xFF0000FF,
        0xFF20C080,
        0xFFFFFFFF,
    };

    private static final int LIGHTMAP_SIZE = 16;
    private static final int RELEASE_DELAY_FRAMES = Reflect.getStatic(GLSMTextureLifetime.class, "RELEASE_DELAY_FRAMES");
    private static final int CLEAR_COLOR = 0xFF000000;

    private static int atlasTexture;
    private static int lightmapTexture;

    @BeforeAll
    static void boot() {
        GlsmMetalHeadlessRig.beginFrame();
        atlasTexture = createMipmappedAtlas();
        lightmapTexture = allocateDynamicLightmap();
        GlsmMetalHeadlessRig.endFrame();
    }

    private static int createMipmappedAtlas() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        final int id = GLSMTextures.glGenTextures();
        allocateTextureImpl(id, ATLAS_MIPMAP_LEVELS, ATLAS_SIZE, ATLAS_SIZE, 1.0f);
        for (int level = 0; level <= ATLAS_MIPMAP_LEVELS; level++) {
            uploadTextureSub(level, LEVEL_COLORS[level], ATLAS_SIZE >> level, ATLAS_SIZE >> level, true);
        }
        return id;
    }

    private static int allocateDynamicLightmap() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        final int id = GLSMTextures.glGenTextures();
        allocateTextureImpl(id, 0, LIGHTMAP_SIZE, LIGHTMAP_SIZE, 1.0f);
        return id;
    }

    private static void allocateTextureImpl(int id, int maxLevel, int width, int height, float aniso) {
        GLSMTextures.glDeleteTextures(id);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, id);

        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, aniso);

        if (maxLevel > 0) {
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, maxLevel);
            GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0.0f);
            GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, (float) maxLevel);
            GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0f);
        }

        for (int level = 0; level <= maxLevel; level++) {
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, width >> level, height >> level, 0,
                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
        }
    }

    private static void uploadTextureSub(int level, int argb, int width, int height, boolean mipmapped) {
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
            mipmapped ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        final IntBuffer data = BufferUtils.createIntBuffer(width * height);
        for (int i = 0; i < width * height; i++) {
            data.put(i, argb);
        }
        data.position(0);
        GLSMTextures.glTexSubImage2D(GL11.GL_TEXTURE_2D, level, 0, 0, width, height,
            GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, data);
    }

    private static void uploadLightmapFrame(int argb) {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);
        uploadTextureSub(0, argb, LIGHTMAP_SIZE, LIGHTMAP_SIZE, false);
    }

    private static void bindAtlasUnit0() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void enableLightmap() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glLoadIdentity();
        final float scale = 0.00390625f;
        GLSMMatrices.glScalef(scale, scale, scale);
        GLSMMatrices.glTranslatef(8.0f, 8.0f, 8.0f);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GLSMCurrentAttribs.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void quad(float extent, boolean lightmap, boolean counterClockwise) {
        GLSMDraws.glBegin(GL11.GL_QUADS);
        if (counterClockwise) {
            GlsmMetalHeadlessRig.emitVertex(-extent, -extent, 0.0f, 0.0f, lightmap, (short) 240);
            GlsmMetalHeadlessRig.emitVertex(extent, -extent, 1.0f, 0.0f, lightmap, (short) 240);
            GlsmMetalHeadlessRig.emitVertex(extent, extent, 1.0f, 1.0f, lightmap, (short) 240);
            GlsmMetalHeadlessRig.emitVertex(-extent, extent, 0.0f, 1.0f, lightmap, (short) 240);
        } else {
            GlsmMetalHeadlessRig.emitVertex(-extent, extent, 0.0f, 1.0f, lightmap, (short) 240);
            GlsmMetalHeadlessRig.emitVertex(extent, extent, 1.0f, 1.0f, lightmap, (short) 240);
            GlsmMetalHeadlessRig.emitVertex(extent, -extent, 1.0f, 0.0f, lightmap, (short) 240);
            GlsmMetalHeadlessRig.emitVertex(-extent, -extent, 0.0f, 0.0f, lightmap, (short) 240);
        }
        GLSMDraws.glEnd();
    }

    private static int center() {
        return pixelAt(GlsmMetalHeadlessRig.readTarget(), SIZE, SIZE / 2, SIZE / 2);
    }

    private static void assertColorNear(int expected, int actual, int tolerance, String label) {
        assertEquals(255, (actual >>> 24) & 0xFF, () -> label + ": alpha " + describe(actual));
        for (int shift = 0; shift <= 16; shift += 8) {
            final int e = (expected >> shift) & 0xFF;
            final int a = (actual >> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= tolerance,
                () -> label + ": expected " + describe(expected) + " got " + describe(actual));
        }
    }

    private static int modulate(int a, int b) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            final int product = Math.round((((a >> shift) & 0xFF) / 255.0f) * (((b >> shift) & 0xFF) / 255.0f) * 255.0f);
            out |= product << shift;
        }
        return out;
    }

    @Test
    void mipmappedAtlasMagnifiedShowsLevelZero() {
        bindAtlasUnit0();
        quad(1.0f, false, true);

        final int actual = center();
        assertColorNear(LEVEL_COLORS[0], actual, 1, "magnified level 0");
    }

    @Test
    void mipmappedAtlasMinifiedShowsALowerLevel() {
        bindAtlasUnit0();
        quad(0.125f, false, true);

        final int actual = center();
        assertEquals(255, (actual >>> 24) & 0xFF, () -> "minified alpha: " + describe(actual));
        assertNotEquals(CLEAR_COLOR, actual, () -> "minified is the clear color: " + describe(actual));
        assertNotEquals(LEVEL_COLORS[0], actual, () -> "minified is level 0: " + describe(actual));
        assertColorNear(LEVEL_COLORS[3], actual, 1, "minified");
    }

    @Test
    void perFrameDynamicLightmapModulatesTheAtlas() {
        final int[] frames = { 0xFFFFFFFF, 0xFFFF8040, 0xFF40FF80 };
        for (int frame = 0; frame < frames.length; frame++) {
            if (frame > 0) GlsmMetalHeadlessRig.beginFrameAndReset();

            uploadLightmapFrame(frames[frame]);
            bindAtlasUnit0();
            enableLightmap();
            GlsmMetalHeadlessRig.terrainRenderState();
            quad(1.0f, true, true);

            final int expected = modulate(LEVEL_COLORS[0], frames[frame]);
            final int actual = center();
            assertColorNear(expected, actual, 2, "dynamic lightmap frame " + frame);
        }
    }

    private static int centerAfterPushAttribWrappedCulledQuad(boolean frontFacing) {
        uploadLightmapFrame(0xFFFFFFFF);
        bindAtlasUnit0();
        enableLightmap();
        GlsmMetalHeadlessRig.terrainRenderState();

        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        GLSMRasterizer.glCullFace(GL11.GL_BACK);
        GLSMAttribStack.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        quad(1.0f, true, frontFacing);
        GLSMAttribStack.glPopAttrib();

        return center();
    }

    @Test
    void pushAttribWrappedCulledDrawKeepsFrontFace() {
        assertColorNear(LEVEL_COLORS[0], centerAfterPushAttribWrappedCulledQuad(true), 2, "front-face winding kept");
    }

    @Test
    void pushAttribWrappedCulledDrawDropsBackFace() {
        final int actual = centerAfterPushAttribWrappedCulledQuad(false);
        assertEquals(CLEAR_COLOR, actual, () -> "back-face winding was not culled: " + describe(actual));
    }

    @Test
    void displayListWithBindingsOutsideShowsTheModulatedTexel() {
        final int list = GLSMDraws.glGenLists(1);
        GLSMDraws.glNewList(list, GL11.GL_COMPILE);
        quad(1.0f, true, true);
        GLSMDraws.glEndList();

        uploadLightmapFrame(0xFFFF8040);
        bindAtlasUnit0();
        enableLightmap();
        GlsmMetalHeadlessRig.terrainRenderState();

        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glTranslatef(0.0f, 0.0f, 0.0f);
        GLSMDraws.glCallList(list);
        GLSMMatrices.glPopMatrix();

        final int expected = modulate(LEVEL_COLORS[0], 0xFFFF8040);
        final int actual = center();
        assertColorNear(expected, actual, 2, "display list with bindings outside");
    }

    private static void texImageEmpty(int level) {
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA8, 0, 0, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    @Test
    void zeroSizedLevelZeroDropsTheLevelAndRespecifyRecreatesIt() {
        final int tex = GlsmMetalHeadlessRig.newNearestTexture();
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2);
        GlsmMetalHeadlessRig.uploadSolidLevel(0, 4, LEVEL_COLORS[0]);
        assertEquals(4, levelWidth(0), "level 0 after the upload");

        texImageEmpty(1);
        assertEquals(4, levelWidth(0), "zero-sized level 1 must leave level 0 alone");

        texImageEmpty(0);
        assertEquals(0L, GlsmMetalHeadlessRig.textureImage(tex), "zero-sized level 0 must release the storage");
        texImageEmpty(1);
        assertEquals(0L, GlsmMetalHeadlessRig.textureImage(tex), "zero-sized level 1 on an empty texture must not allocate");

        GlsmMetalHeadlessRig.uploadSolidLevel(0, 4, LEVEL_COLORS[0]);
        assertEquals(4, levelWidth(0), "respecified level 0 must recreate storage");
        assertEquals(2, GLSMTextures.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL), "MAX_LEVEL survives the release");
        GLSMTextures.glDeleteTextures(tex);
    }

    private static int levelWidth(int level) {
        return GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
    }

    @Test
    void deletingA2DTextureKeepsTheUnitsNon2DBinding() {
        final int deleted = GlsmMetalHeadlessRig.newNearestTexture();
        GlsmMetalHeadlessRig.uploadSolidLevel(0, 4, LEVEL_COLORS[0]);
        final int volume = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, volume);

        GLSMTextures.glDeleteTextures(deleted);
        assertEquals(volume, BackendManager.RENDER_BACKEND.getInteger(GL12.GL_TEXTURE_BINDING_3D), "after the delete");
        for (int i = 0; i < RELEASE_DELAY_FRAMES; i++) GLSMTextureLifetime.onFrameEnd();

        assertEquals(volume, BackendManager.RENDER_BACKEND.getInteger(GL12.GL_TEXTURE_BINDING_3D), "after the release");
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GLSMTextures.glDeleteTextures(volume);
    }

}
