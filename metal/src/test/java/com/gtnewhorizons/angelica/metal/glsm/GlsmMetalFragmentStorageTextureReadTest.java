package com.gtnewhorizons.angelica.metal.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.metal.glsm.GlsmMetalHeadlessRig.SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-metal")
@ExtendWith(GlsmMetalFrameExtension.class)
class GlsmMetalFragmentStorageTextureReadTest {

    private static final int VOL = 8;

    private static String fragment(int samplerCount) {
        final StringBuilder sb = new StringBuilder("#version 430 core\n");
        for (int i = 0; i < samplerCount; i++) sb.append("uniform sampler2D tex").append(i).append(";\n");
        sb.append("layout(r16ui, binding = 3) readonly uniform uimage3D voxel_img;\n");
        sb.append("layout(location = 0) out vec4 fragColor;\n");
        sb.append("void main() {\n");
        sb.append("    ivec2 p = ivec2(gl_FragCoord.xy);\n");
        sb.append("    ivec3 cell = ivec3(p.x % 8, p.y % 8, (p.x / 8 + p.y / 8) % 8);\n");
        sb.append("    uint v = imageLoad(voxel_img, cell).x;\n");
        sb.append("    vec4 acc = vec4(0.0);\n");
        for (int i = 0; i < samplerCount; i++) sb.append("    acc += texture(tex").append(i).append(", vec2(0.5)) * 0.0;\n");
        sb.append("    fragColor = vec4(float(v) / 255.0, float(v & 15u) / 15.0, float(v >> 4u) / 15.0, 1.0) + acc;\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static int patternValue(int x, int y, int z) {
        return x == y ? 255 - z * 16 : z * 32 + x * 2 + y;
    }

    private static int voxelTexture() {
        final int tex = GLSMTextures.glGenTextures();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GLSMTextures.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        final ByteBuffer data = MemoryUtil.memAlloc(VOL * VOL * VOL * 2);
        try {
            for (int z = 0; z < VOL; z++) for (int y = 0; y < VOL; y++) for (int x = 0; x < VOL; x++) data.putShort((short) patternValue(x, y, z));
            data.flip();
            GLSMTextures.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 2);
            GLSMTextures.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R16UI, VOL, VOL, VOL, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, data);
            GLSMTextures.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        } finally {
            MemoryUtil.memFree(data);
        }
        GLSMTextures.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return tex;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 8})
    void imageLoadSeesTheVoxelsBesideSamplers(int samplerCount) {
        final int voxels = voxelTexture();
        final int[] samplers = new int[samplerCount];
        for (int i = 0; i < samplerCount; i++) samplers[i] = GlsmMetalHeadlessRig.createSolidTexture(GlsmMetalHeadlessRig.BLUE);
        final int program = GlTestSupport.program(GlsmMetalHeadlessRig.FULLSCREEN_VERTEX, fragment(samplerCount));
        final int vao = GLSMVertexInput.glGenVertexArrays();
        try {
            GLSMPrograms.glUseProgram(program);
            for (int i = 0; i < samplerCount; i++) {
                GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, samplers[i]);
                GLSMPrograms.glUniform1i(GLSMPrograms.glGetUniformLocation(program, "tex" + i), i);
            }
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMTextures.glBindImageTexture(3, voxels, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_R16UI);
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GLSMBuffers.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);
            GLSMPrograms.glUseProgram(0);

            final int[] pixels = GlsmMetalHeadlessRig.readTarget();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    final int v = patternValue(x % 8, y % 8, (x / 8 + y / 8) % 8);
                    final int argb = pixels[y * SIZE + x];
                    final int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    final int px = x, py = y;
                    assertEquals(v, r, 1, () -> "red at (" + px + "," + py + ") with " + samplerCount + " samplers");
                    assertEquals(Math.round((v & 15) * 255f / 15f), g, 1, () -> "green at (" + px + "," + py + ")");
                    assertEquals(Math.round((v >> 4) * 255f / 15f), b, 1, () -> "blue at (" + px + "," + py + ")");
                }
            }
        } finally {
            GLSMVertexInput.glBindVertexArray(0);
            GLSMVertexInput.glDeleteVertexArrays(vao);
            GLSMPrograms.glDeleteProgram(program);
            for (int i = samplerCount - 1; i >= 0; i--) {
                GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GLSMTextures.glDeleteTextures(samplers[i]);
            }
            GLSMTextures.glDeleteTextures(voxels);
        }
    }
}
