#version 330 core

#ifdef LEGACY
#extension GL_EXT_gpu_shader4 : require
#endif

#import <sodium:include/fog.glsl>

in vec4 v_Color;
in vec4 v_RdhFactor;
in vec2 v_QuadCoord;
in vec2 v_TexCoord;

#if defined(USE_FOG) && defined(CHUNK_FADE_IN_DURATION_MS) && CHUNK_FADE_IN_DURATION_MS > 0
in float v_ChunkAgeMs;
#endif

in float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
in float v_MaterialAlphaCutoff;
#endif

#if defined(USE_FOG_POSTMODERN)
in float v_SphericalFragDistance;
in float v_CylindricalFragDistance;
#elif defined(USE_FOG)
in float v_FragDistance;
#endif

uniform sampler2D u_BlockTex; // The block texture

uniform int u_FogShape;
uniform vec4 u_FogColor; // The color of the shader fog

#ifdef USE_FOG_SMOOTH
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog
#endif

#ifdef USE_FOG_POSTMODERN
uniform float u_RenderDistFogStart;
uniform float u_RenderDistFogEnd;
uniform float u_EnvFogStart;
uniform float u_EnvFogEnd;
#endif

#if defined(USE_FOG_EXP) || defined(USE_FOG_EXP2)
uniform float u_FogDensity; // The density of the shader fog
#endif

#ifndef LEGACY
out vec4 fragColor; // The output fragment for the color framebuffer
#else
#define fragColor gl_FragColor
#endif

vec2 snapToTexelCentre(vec2 uv, vec2 texelSize, vec2 texelsPerPixel) {
    vec2 centreCoords = uv / texelSize - 0.5;
    vec2 lowerCentre = floor(centreCoords);
    vec2 alongSeam = centreCoords - lowerCentre;

    vec2 rampWidth = clamp(texelsPerPixel, vec2(1e-8), vec2(1.0));
    alongSeam = clamp((alongSeam - 0.5) / rampWidth + 0.5, 0.0, 1.0);

    return (lowerCentre + alongSeam + 0.5) * texelSize;
}

#ifdef USE_RGSS
#ifndef RGSS_MIP_BIAS
#define RGSS_MIP_BIAS -1.0
#endif

// Rotated-grid offsets, as fractions of the area the four taps spread over.
const vec2 RGSS_OFFSETS[4] = vec2[4](
    vec2( 0.125,  0.375),
    vec2(-0.125, -0.375),
    vec2( 0.375, -0.125),
    vec2(-0.375,  0.125)
);
#endif

vec4 sampleTerrain(sampler2D tex, vec2 uv) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 duTexels = du / texelSize;
    vec2 dvTexels = dv / texelSize;
    vec2 squaredAxes = duTexels * duTexels + dvTexels * dvTexels;
    vec2 texelsPerPixel = sqrt(squaredAxes);
    vec2 snapped = snapToTexelCentre(uv, texelSize, texelsPerPixel);

    // Most of the screen is magnified. The material's mip bias is never positive, so these pixels always read the top level.
    if (squaredAxes.x + squaredAxes.y <= 1.0) {
        return textureLod(tex, snapped, 0.0);
    }

#if defined(USE_ANISOTROPIC) || !defined(TERRAIN_NO_MIPS)
    float biasScale = exp2(v_MaterialMipBias);
#endif

#ifdef USE_ANISOTROPIC
    float duSquared = dot(duTexels, duTexels);
    float dvSquared = dot(dvTexels, dvTexels);
    float footprint = sqrt(max(duSquared, dvSquared));
    float plainScale = biasScale * min(1.0, TERRAIN_GUTTER / max(footprint * biasScale, 1e-8));
#endif

#ifdef USE_RGSS
    // The four taps fade in over one to two texels per pixel, sliding out from the centre as they do, so no fifth tap is needed to blend them.
    float rgss = smoothstep(1.0, 2.0, max(texelsPerPixel.x, texelsPerPixel.y));
#ifdef USE_ANISOTROPIC
    rgss *= 1.0 - smoothstep(0.75 * TERRAIN_GUTTER, TERRAIN_GUTTER, footprint);
#endif

    if (rgss > 0.0) {
        vec4 sum = vec4(0.0);
#ifdef USE_ANISOTROPIC
        float fit = min(1.0, 0.75 * TERRAIN_GUTTER / max(footprint, 1e-8));
        vec2 spreadU = du * fit * rgss;
        vec2 spreadV = dv * fit * rgss;
        float tapScale = mix(plainScale, biasScale * fit * exp2(RGSS_MIP_BIAS), rgss);
        vec2 gradU = du * tapScale;
        vec2 gradV = dv * tapScale;

        for (int i = 0; i < 4; i++) {
            sum += textureGrad(tex, snapped + RGSS_OFFSETS[i].x * spreadU + RGSS_OFFSETS[i].y * spreadV, gradU, gradV);
        }
#else
#ifdef TERRAIN_NO_MIPS
        const float lod = 0.0;
#else
        float lod = max(0.0, 0.25 * log2(dot(duTexels, duTexels) * dot(dvTexels, dvTexels)) + v_MaterialMipBias + rgss * RGSS_MIP_BIAS);
#endif

        for (int i = 0; i < 4; i++) {
            sum += textureLod(tex, snapped + RGSS_OFFSETS[i] * rgss * texelSize, lod);
        }
#endif
        return sum * 0.25;
    }
#endif

#if defined(TERRAIN_NO_MIPS) && !defined(USE_ANISOTROPIC)
    return textureLod(tex, snapped, 0.0);
#elif defined(USE_ANISOTROPIC)
    return textureGrad(tex, snapped, du * plainScale, dv * plainScale);
#else
    return textureGrad(tex, snapped, du * biasScale, dv * biasScale);
#endif
}

void main() {
#ifdef USE_TEXEL_SNAP
    vec4 diffuseColor = sampleTerrain(u_BlockTex, v_TexCoord);
#else
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
#endif

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    vec4 m_color = v_Color;
    // Apply correction factor to make the resulting color very close to what true bilinear interpolation would obtain
    // min(x, y) * (1 - max(x, y)) == min(x, y) - (x * y)
    float correctionWeight = min(v_QuadCoord.x, v_QuadCoord.y) - (v_QuadCoord.x * v_QuadCoord.y);
    m_color += v_RdhFactor * correctionWeight;

#ifdef USE_VANILLA_COLOR_FORMAT
    // Apply per-vertex color. AO shade is applied ahead of time on the CPU.
    diffuseColor *= m_color;
#else
    // Apply per-vertex color
    diffuseColor.rgb *= m_color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= m_color.a;
#endif

#ifdef USE_FOG

#if defined(CHUNK_FADE_IN_DURATION_MS) && CHUNK_FADE_IN_DURATION_MS > 0
    // Make chunk fade in over a short duration
    diffuseColor = vec4(mix(u_FogColor.rgb, diffuseColor.rgb, (clamp(v_ChunkAgeMs, 0, CHUNK_FADE_IN_DURATION_MS) / CHUNK_FADE_IN_DURATION_MS)), diffuseColor.a);
#endif

#if defined(USE_FOG_POSTMODERN)
    float fogValue = max(_linearFogValue(v_CylindricalFragDistance, u_RenderDistFogStart, u_RenderDistFogEnd),
                         _linearFogValue(v_SphericalFragDistance, u_EnvFogStart, u_EnvFogEnd));

    fragColor = vec4(mix(diffuseColor.rgb, u_FogColor.rgb, fogValue * u_FogColor.a), diffuseColor.a);
#else // Legacy fog
    float fragDistance;
    if (u_FogShape == FOG_SHAPE_PLANAR) {
        fragDistance = gl_FragCoord.z / gl_FragCoord.w;
    } else {
        fragDistance = v_FragDistance;
    }
#if defined(USE_FOG_EXP2)
    fragColor = _exp2Fog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_EXP)
    fragColor = _expFog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_SMOOTH)
    fragColor = _linearFog(diffuseColor, fragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif
#endif

#else // No fog
    fragColor = diffuseColor;
#endif
}
