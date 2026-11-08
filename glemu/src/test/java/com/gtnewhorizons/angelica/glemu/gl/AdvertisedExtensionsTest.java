package com.gtnewhorizons.angelica.glemu.gl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvertisedExtensionsTest {

    private static Set<String> advertised(GpuCaps caps) {
        return GlBase.advertisedCapabilities(caps);
    }

    @Test
    void everyDeviceClaimsTheNewestVersion() {
        assertTrue(advertised(new GpuCaps() {}).containsAll(List.of("OpenGL32", "OpenGL40", "OpenGL46")));
    }

    @Test
    void whatDependsOnTheDeviceFollowsIt() {
        final Set<String> bare = advertised(new GpuCaps() {});
        for (String flag : List.of("GL_EXT_texture_compression_s3tc", "GL_ARB_texture_compression_bptc")) {
            assertFalse(bare.contains(flag), flag);
        }
        final GpuCaps full = new GpuCaps() {};
        full.textureCompressionBC = true;
        assertTrue(advertised(full).containsAll(List.of("GL_EXT_texture_compression_s3tc", "GL_ARB_texture_compression_bptc")));
    }

    @Test
    void stagesADeviceLacksAreStillClaimedBecauseTheyAreEmulated() {
        assertTrue(advertised(new GpuCaps() {}).containsAll(List.of("GL_ARB_geometry_shader4", "GL_ARB_tessellation_shader")));
    }

    /** These resolve to stubs or do less than they promise, so a flag would send code down a path that silently fails. */
    @Test
    void featuresThatOnlyLookImplementedStayUnflagged() {
        final GpuCaps full = new GpuCaps() {};
        full.geometryShader = full.tessellationShader = full.textureCompressionBC = true;
        final Set<String> flags = advertised(full);
        for (String flag : List.of("GL_ARB_blend_func_extended", "GL_ARB_color_buffer_float",
            "GL_ARB_point_parameters", "GL_ARB_sparse_buffer", "GL_ARB_sparse_texture",
            "GL_ARB_vertex_program",
            "GL_ARB_bindless_texture", "GL_EXT_framebuffer_multisample", "GL_EXT_mesh_shader", "GL_EXT_fragment_shading_rate", "GL_KHR_blend_equation_advanced")) {
            assertFalse(flags.contains(flag), flag);
        }
    }
}
