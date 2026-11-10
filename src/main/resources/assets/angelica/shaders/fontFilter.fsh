#version 330 core

uniform sampler2D sampler;
uniform int aaMode;
uniform float strength;
uniform float fontBrightness;
uniform float alphaTestRef;
// 1 when the bound atlas is sampled nearest from level 0 alone, so a sample is the texel its coordinate lands in.
uniform int nearestLevel0;

in vec4 color;
in vec4 tB;
in vec2 texCoord;

out vec4 fragColor;

/*
Hacky multisampling and fake anisotropic filtering. Cursed beyond belief. There _may_ have been simpler
means of achieving the produced effects, but this appears to work without noticeable performance losses.
*/

float txSample(vec2 uv, float du, float dv, float factorU, float factorV) {
    float finalU = uv.x + factorU * du;
    float finalV = uv.y + factorV * dv;
    if (finalU < tB.x || finalU > tB.y || finalV < tB.z || finalV > tB.w) {
        return 0.0;
    }
    return texture(sampler, vec2(finalU, finalV)).a;
}

void main() {
    vec4 col = color;
    float original_alpha = col.a;
    if (texCoord.s != 0.0 || texCoord.t != 0.0) {
        if (aaMode == 0) {
            // No AA - single texture sample
            col.a = original_alpha * texture(sampler, texCoord).a;
        } else {
            vec2 texScaled = texCoord * strength;
            float fu = abs(dFdx(texScaled.x)) + abs(dFdy(texScaled.x));
            float fv = abs(dFdx(texScaled.y)) + abs(dFdy(texScaled.y));
            float res = 0.0;
            // Two kinds of pixel need none of the samples below, and get exactly what they would add up to. Where every tap misses the
            // glyph's bounds on one axis, as across most of the margin a quad keeps around its glyph, each adds nothing. Text drawn larger
            // than its atlas mostly lands each pixel's taps on one texel, or on a 2x2 block of equal texels, inside the glyph: with nearest
            // sampling from level 0 every tap then reads that value, so the sum is that value added once per tap. The block is widened by
            // a 256th of a texel each way for the sampler's own rounding of where a coordinate falls.
            float reach = aaMode == 1 ? 6.0 : 8.0;
            vec2 lo = texCoord - reach * vec2(fu, fv);
            vec2 hi = texCoord + reach * vec2(fu, fv);
            bool summed = hi.x < tB.x || lo.x > tB.y || hi.y < tB.z || lo.y > tB.w;
            if (!summed && nearestLevel0 != 0 && lo.x >= tB.x && hi.x <= tB.y && lo.y >= tB.z && hi.y <= tB.w) {
                vec2 size = vec2(textureSize(sampler, 0));
                ivec2 t0 = ivec2(floor(lo * size - 1.0 / 256.0));
                ivec2 t1 = ivec2(floor(hi * size + 1.0 / 256.0));
                if (t1.x - t0.x <= 1 && t1.y - t0.y <= 1 && t0.x >= 0 && t0.y >= 0 && t1.x < int(size.x) && t1.y < int(size.y)) {
                    float a = texelFetch(sampler, t0, 0).a;
                    if (t0 == t1 || texelFetch(sampler, t1, 0).a == a && texelFetch(sampler, ivec2(t0.x, t1.y), 0).a == a
                            && texelFetch(sampler, ivec2(t1.x, t0.y), 0).a == a) {
                        res = aaMode == 1 ? (a + a + a + a) / 4.0
                            : (a + a + a + a + a + a + a + a + a + a + a + a + a + a + a + a) / 16.0;
                        summed = true;
                    }
                }
            }
            if (summed) {
                // res holds the sum already
            } else if (aaMode == 1) {
                res += txSample(texCoord,  2.0,  6.0, fu, fv);
                res += txSample(texCoord,  6.0, -2.0, fu, fv);
                res += txSample(texCoord, -2.0, -6.0, fu, fv);
                res += txSample(texCoord, -6.0,  2.0, fu, fv);
                res /= 4.0;
            } else {
                res += txSample(texCoord,  1.0,  1.0, fu, fv);
                res += txSample(texCoord, -1.0, -3.0, fu, fv);
                res += txSample(texCoord, -3.0,  2.0, fu, fv);
                res += txSample(texCoord,  4.0, -1.0, fu, fv);
                res += txSample(texCoord, -5.0, -2.0, fu, fv);
                res += txSample(texCoord,  2.0,  5.0, fu, fv);
                res += txSample(texCoord,  5.0,  3.0, fu, fv);
                res += txSample(texCoord,  3.0, -5.0, fu, fv);
                res += txSample(texCoord, -2.0,  6.0, fu, fv);
                res += txSample(texCoord,  0.0, -7.0, fu, fv);
                res += txSample(texCoord, -4.0, -6.0, fu, fv);
                res += txSample(texCoord, -6.0,  4.0, fu, fv);
                res += txSample(texCoord, -8.0,  0.0, fu, fv);
                res += txSample(texCoord,  7.0, -4.0, fu, fv);
                res += txSample(texCoord,  6.0,  7.0, fu, fv);
                res += txSample(texCoord, -7.0, -8.0, fu, fv);
                res /= 16.0;
            }
            float b = fontBrightness;
            res = (b * res) / (b * res + (1 - b) * (1 - res));
            col.a = original_alpha * res;
        }
    }

    if (col.a <= alphaTestRef) {
        discard;
    }

    fragColor = col;
}
