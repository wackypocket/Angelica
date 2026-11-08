package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import java.util.LinkedHashMap;
import java.util.Map;

/** A fragment shader that really uses each language extension the backends advertise. */
public final class ShaderExtensionSnippets {
    private ShaderExtensionSnippets() {}

    public static final Map<String, String> ALL = new LinkedHashMap<>();

    private static void add(String extension, String declarations, String color) {
        ALL.put(extension, "#version 330 core\n#extension GL_" + extension + " : require\n" + declarations + "\nout vec4 fragColor;\nvoid main() { fragColor = " + color + "; }\n");
    }

    static {
        add("ARB_conservative_depth", "layout(depth_any) out float gl_FragDepth;", "vec4(1.0)");
        add("ARB_derivative_control", "", "vec4(dFdxFine(gl_FragCoord.x), dFdyCoarse(gl_FragCoord.y), 0.0, 1.0)");
        add("ARB_explicit_uniform_location", "#extension GL_ARB_explicit_attrib_location : require\nlayout(location = 3) uniform vec4 tint;", "tint");
        add("ARB_fragment_coord_conventions", "layout(origin_upper_left) in vec4 gl_FragCoord;", "vec4(gl_FragCoord.y)");
        add("ARB_gpu_shader5", "uniform sampler2D s;", "textureGather(s, vec2(0.5), 1) + vec4(bitCount(7))");
        add("ARB_shader_bit_encoding", "", "vec4(floatBitsToUint(1.0) == 0x3F800000u ? 1.0 : 0.0)");
        ALL.put("ARB_shader_texture_lod", "#version 120\n#extension GL_ARB_shader_texture_lod : require\nuniform sampler2D s;\nvoid main() { gl_FragColor = texture2DLod(s, vec2(0.5), 0.0); }\n");
        add("ARB_shading_language_420pack", "layout(binding = 2) uniform sampler2D s;", "texture(s, vec2(0.5))");
        add("ARB_shading_language_packing", "", "unpackUnorm4x8(packUnorm4x8(vec4(0.5)))");
        add("ARB_texture_gather", "uniform sampler2D s;", "textureGather(s, vec2(0.5))");
        add("ARB_texture_query_lod", "uniform sampler2D s;", "textureQueryLOD(s, vec2(0.5)).xyxy");
        add("EXT_shader_integer_mix", "", "vec4(float(mix(1, 2, true)))");
    }
}
