package com.gtnewhorizons.angelica.glemu.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IEEE doubles in GLSL, for devices without them. A double is held as the words packDouble2x32 takes, the low word first, and a vector or matrix as
 * arrays of those. Results round to nearest even, denormals included.
 */
final class SoftDouble {
    private static final String SCALARS = """
        const uvec2 AD_NAN = uvec2(0u, 0x7FF80000u);
        const uvec2 AD_ONE = uvec2(0u, 0x3FF00000u);
        const uvec2 AD_TWO = uvec2(0u, 0x40000000u);
        const uvec2 AD_THREE = uvec2(0u, 0x40080000u);
        const uvec2 AD_HALF = uvec2(0u, 0x3FE00000u);
        const uvec2 AD_ZERO = uvec2(0u, 0u);
AD_WORDS        int ad_u64clz(uvec2 a) {
            if (a.y != 0u) return 31 - findMSB(a.y);
            if (a.x != 0u) return 63 - findMSB(a.x);
            return 64;
        }
AD_PRODUCTS        const uint AD_RECIP_0[16] = uint[](0xFFC4u, 0xF0BEu, 0xE363u, 0xD76Fu, 0xCCADu, 0xC2F0u, 0xBA16u, 0xB201u, 0xAA97u, 0xA3C6u, 0x9D7Au, 0x97A6u, 0x923Cu, 0x8D32u, 0x887Eu, 0x8417u);
        const uint AD_RECIP_1[16] = uint[](0xF0F1u, 0xD62Cu, 0xBFA1u, 0xAC77u, 0x9C0Au, 0x8DDBu, 0x8185u, 0x76BAu, 0x6D3Bu, 0x64D4u, 0x5D5Cu, 0x56B1u, 0x50B6u, 0x4B55u, 0x4679u, 0x4211u);
        const uint AD_RSQRT_0[16] = uint[](0xB4C9u, 0xFFABu, 0xAA7Du, 0xF11Cu, 0xA1C5u, 0xE4C7u, 0x9A43u, 0xDA29u, 0x93B5u, 0xD0E5u, 0x8DEDu, 0xC8B7u, 0x88C6u, 0xC16Du, 0x8424u, 0xBAE1u);
        const uint AD_RSQRT_1[16] = uint[](0xA5A5u, 0xEA42u, 0x8C21u, 0xC62Du, 0x788Fu, 0xAA7Fu, 0x6928u, 0x94B6u, 0x5CC7u, 0x8335u, 0x52A6u, 0x74E2u, 0x4A3Eu, 0x68FEu, 0x432Bu, 0x5EFDu);
        uint ad_recip32(uint a) {
            uint index = (a >> 27) & 0xFu, eps = (a >> 11) & 0xFFFFu;
            uint r0 = (AD_RECIP_0[index] - ((AD_RECIP_1[index] * eps) >> 20)) & 0xFFFFu;
            uint sigma0 = ~ad_u64shr(ad_mul32(r0, a), 7).x;
            uint r = (r0 << 16) + ad_u64shr(ad_mul32(r0, sigma0), 24).x;
            uint square = ad_mul32(sigma0, sigma0).y;
            return r + (ad_mul32(r, square).y >> 16);
        }
        uint ad_rsqrt32(uint odd, uint a) {
            uint index = ((a >> 27) & 0xEu) + odd, eps = (a >> 12) & 0xFFFFu;
            uint r0 = (AD_RSQRT_0[index] - ((AD_RSQRT_1[index] * eps) >> 20)) & 0xFFFFu;
            uint e = r0 * r0;
            if (odd == 0u) e <<= 1;
            uint sigma0 = ~ad_u64shr(ad_mul32(e, a), 23).x;
            uint r = (r0 << 16) + ad_u64shr(ad_mul32(r0, sigma0), 25).x;
            uint square = ad_mul32(sigma0, sigma0).y;
            r += ad_mul32((r >> 1) + (r >> 3) - (r0 << 14), square).y >> 16;
            return (r & 0x80000000u) == 0u ? 0x80000000u : r;
        }
        uvec2 ad_frac(uvec2 a) { return uvec2(a.x, a.y & 0xFFFFFu); }
        int ad_exp(uvec2 a) { return int((a.y >> 20) & 0x7FFu); }
        uint ad_signbit(uvec2 a) { return a.y >> 31; }
        uvec2 ad_pack(uint sign, uint exp, uvec2 sig) {
            return uvec2(sig.x, (sign << 31) + (exp << 20) + sig.y);
        }
        uvec2 ad_normsub(uvec2 frac, out int e) {
            int shift = ad_u64clz(frac) - 11;
            e = 1 - shift;
            return ad_u64shl(frac, shift);
        }
        bool ad_isnan(uvec2 a) { return ad_exp(a) == 0x7FF && ad_nonzero(ad_frac(a)); }
        bool ad_iszero(uvec2 a) { return (a.x | (a.y & 0x7FFFFFFFu)) == 0u; }
        bool ad_isinf(uvec2 a) { return ad_exp(a) == 0x7FF && !ad_nonzero(ad_frac(a)); }
        uvec2 ad_neg(uvec2 a) { return uvec2(a.x, a.y ^ 0x80000000u); }
        uvec2 ad_abs(uvec2 a) { return uvec2(a.x, a.y & 0x7FFFFFFFu); }
        uvec2 ad_round(uint sign, int zExp, uvec2 zSig) {
            if (zExp >= 0x7FD && (zExp > 0x7FD || (ad_u64add(zSig, uvec2(0x200u, 0u)).y >> 31) != 0u)) return ad_pack(sign, 0x7FFu, uvec2(0u));
            if (zExp < 0) {
                zSig = ad_u64shrj(zSig, -zExp);
                zExp = 0;
            }
            uint roundBits = zSig.x & 0x3FFu;
            uvec2 r = ad_u64shr(ad_u64add(zSig, uvec2(0x200u, 0u)), 10);
            if (roundBits == 0x200u) r.x &= 0xFFFFFFFEu;
            return ad_pack(sign, ad_nonzero(r) ? uint(zExp) : 0u, r);
        }
        uvec2 ad_normround(uint sign, int zExp, uvec2 zSig) {
            if (!ad_nonzero(zSig)) return ad_pack(sign, 0u, uvec2(0u));
            int shift = ad_u64clz(zSig) - 1;
            return ad_round(sign, zExp - shift, ad_u64shl(zSig, shift));
        }
        uvec2 ad_addsigs(uvec2 a, uvec2 b, uint sign) {
            int aExp = ad_exp(a), bExp = ad_exp(b);
            uvec2 aSig = ad_u64shl(ad_frac(a), 9), bSig = ad_u64shl(ad_frac(b), 9);
            int d = aExp - bExp, zExp;
            if (d > 0) {
                if (aExp == 0x7FF) return ad_nonzero(aSig) ? AD_NAN : a;
                if (bExp == 0) d--;
                else bSig.y |= 0x20000000u;
                bSig = ad_u64shrj(bSig, d);
                zExp = aExp;
            } else if (d < 0) {
                if (bExp == 0x7FF) return ad_nonzero(bSig) ? AD_NAN : ad_pack(sign, 0x7FFu, uvec2(0u));
                if (aExp == 0) d++;
                else aSig.y |= 0x20000000u;
                aSig = ad_u64shrj(aSig, -d);
                zExp = bExp;
            } else {
                if (aExp == 0x7FF) return ad_nonzero(uvec2(aSig.x | bSig.x, aSig.y | bSig.y)) ? AD_NAN : a;
                if (aExp == 0) return ad_pack(sign, 0u, ad_u64shr(ad_u64add(aSig, bSig), 9));
                return ad_round(sign, aExp, ad_u64add(uvec2(0u, 0x40000000u), ad_u64add(aSig, bSig)));
            }
            aSig.y |= 0x20000000u;
            uvec2 sum = ad_u64add(aSig, bSig);
            uvec2 zSig = ad_u64shl(sum, 1);
            zExp--;
            if ((zSig.y >> 31) != 0u) {
                zSig = sum;
                zExp++;
            }
            return ad_round(sign, zExp, zSig);
        }
        uvec2 ad_subsigs(uvec2 a, uvec2 b, uint sign) {
            int aExp = ad_exp(a), bExp = ad_exp(b);
            uvec2 aSig = ad_u64shl(ad_frac(a), 10), bSig = ad_u64shl(ad_frac(b), 10), zSig;
            int d = aExp - bExp, zExp;
            if (d > 0) {
                if (aExp == 0x7FF) return ad_nonzero(aSig) ? AD_NAN : a;
                if (bExp == 0) d--;
                else bSig.y |= 0x40000000u;
                bSig = ad_u64shrj(bSig, d);
                aSig.y |= 0x40000000u;
                zSig = ad_u64sub(aSig, bSig);
                zExp = aExp;
            } else if (d < 0) {
                if (bExp == 0x7FF) return ad_nonzero(bSig) ? AD_NAN : ad_pack(sign ^ 1u, 0x7FFu, uvec2(0u));
                if (aExp == 0) d++;
                else aSig.y |= 0x40000000u;
                aSig = ad_u64shrj(aSig, -d);
                bSig.y |= 0x40000000u;
                zSig = ad_u64sub(bSig, aSig);
                zExp = bExp;
                sign ^= 1u;
            } else {
                if (aExp == 0x7FF) return AD_NAN;
                if (aExp == 0) aExp = 1;
                if (ad_u64lt(bSig, aSig)) {
                    zSig = ad_u64sub(aSig, bSig);
                } else if (ad_u64lt(aSig, bSig)) {
                    zSig = ad_u64sub(bSig, aSig);
                    sign ^= 1u;
                } else {
                    return AD_ZERO;
                }
                zExp = aExp;
            }
            return ad_normround(sign, zExp - 1, zSig);
        }
        uvec2 ad_add(uvec2 a, uvec2 b) {
            uint sign = ad_signbit(a);
            return sign == ad_signbit(b) ? ad_addsigs(a, b, sign) : ad_subsigs(a, b, sign);
        }
        uvec2 ad_sub(uvec2 a, uvec2 b) { return ad_add(a, ad_neg(b)); }
        uvec2 ad_mul(uvec2 a, uvec2 b) {
            int aExp = ad_exp(a), bExp = ad_exp(b);
            uint sign = ad_signbit(a) ^ ad_signbit(b);
            uvec2 x = ad_frac(a), y = ad_frac(b);
            if (aExp == 0x7FF) {
                if (ad_nonzero(x) || ad_isnan(b) || ad_iszero(b)) return AD_NAN;
                return ad_pack(sign, 0x7FFu, uvec2(0u));
            }
            if (bExp == 0x7FF) {
                if (ad_nonzero(y) || ad_iszero(a)) return AD_NAN;
                return ad_pack(sign, 0x7FFu, uvec2(0u));
            }
            if (ad_iszero(a) || ad_iszero(b)) return ad_pack(sign, 0u, uvec2(0u));
            if (aExp == 0) x = ad_normsub(x, aExp);
            else x.y |= 0x100000u;
            if (bExp == 0) y = ad_normsub(y, bExp);
            else y.y |= 0x100000u;
            int zExp = aExp + bExp - 0x3FF;
            x = ad_u64shl(x, 10);
            y = ad_u64shl(y, 11);
            uvec2 z = ad_mulhi(x, y);
            if ((z.y & 0x40000000u) == 0u) {
                z = ad_u64shl(z, 1);
                zExp--;
            }
            return ad_round(sign, zExp, z);
        }
        uvec2 ad_div(uvec2 a, uvec2 b) {
            int aExp = ad_exp(a), bExp = ad_exp(b);
            uint sign = ad_signbit(a) ^ ad_signbit(b);
            uvec2 x = ad_frac(a), y = ad_frac(b);
            if (aExp == 0x7FF) {
                if (ad_nonzero(x) || bExp == 0x7FF) return AD_NAN;
                return ad_pack(sign, 0x7FFu, uvec2(0u));
            }
            if (bExp == 0x7FF) return ad_nonzero(y) ? AD_NAN : ad_pack(sign, 0u, uvec2(0u));
            if (ad_iszero(b)) return ad_iszero(a) ? AD_NAN : ad_pack(sign, 0x7FFu, uvec2(0u));
            if (ad_iszero(a)) return ad_pack(sign, 0u, uvec2(0u));
            if (aExp == 0) x = ad_normsub(x, aExp);
            else x.y |= 0x100000u;
            if (bExp == 0) y = ad_normsub(y, bExp);
            else y.y |= 0x100000u;
            // A reciprocal good to 32 bits gives the quotient in two steps, as SoftFloat 3 does, with a last look at the remainder only near a tie.
            int zExp = aExp - bExp + 0x3FE;
            if (ad_u64lt(x, y)) {
                zExp--;
                x = ad_u64shl(x, 11);
            } else {
                x = ad_u64shl(x, 10);
            }
            y = ad_u64shl(y, 11);
            uint recip = ad_recip32(y.y) - 2u;
            uint q32 = ad_mul32(x.y, recip).y;
            uint twice = q32 << 1;
            uvec2 rem = ad_u64sub(ad_u64shl(ad_u64sub(x, ad_mul32(twice, y.y)), 28), ad_mul32(twice, y.x >> 4));
            uint q = ad_mul32(rem.y, recip).y + 4u;
            uvec2 z = ad_u64add(uvec2(0u, q32), ad_u64shl(uvec2(q, 0u), 4));
            if ((z.x & 0x1FFu) < 64u) {
                q &= ~7u;
                z.x &= ~0x7Fu;
                twice = q << 1;
                rem = ad_u64sub(ad_u64shl(ad_u64sub(rem, ad_mul32(twice, y.y)), 28), ad_mul32(twice, y.x >> 4));
                if ((rem.y >> 31) != 0u) z = ad_u64sub(z, uvec2(0x80u, 0u));
                else if (ad_nonzero(rem)) z.x |= 1u;
            }
            return ad_round(sign, zExp, z);
        }
        uvec2 ad_sqrt(uvec2 a) {
            int aExp = ad_exp(a);
            if (ad_isnan(a)) return AD_NAN;
            if (ad_iszero(a)) return a;
            if (ad_signbit(a) != 0u) return AD_NAN;
            if (aExp == 0x7FF) return a;
            uvec2 m = ad_frac(a);
            if (aExp == 0) m = ad_normsub(m, aExp);
            else m.y |= 0x100000u;
            // From a reciprocal square root good to 32 bits, as SoftFloat 3 does.
            int zExp = ((aExp - 0x3FF) >> 1) + 0x3FE;
            uint odd = uint(aExp & 1);
            uint a32 = ad_u64shr(m, 21).x;
            uint recip = ad_rsqrt32(odd, a32);
            uint z32 = ad_mul32(a32, recip).y;
            if (odd != 0u) {
                m = ad_u64shl(m, 8);
                z32 >>= 1;
            } else {
                m = ad_u64shl(m, 9);
            }
            uvec2 rem = ad_u64sub(m, ad_mul32(z32, z32));
            uint q = ad_mul32(ad_u64shr(rem, 2).x, recip).y;
            uvec2 z = ad_u64add(uvec2(32u, z32), ad_u64shl(uvec2(q, 0u), 3));
            if ((z.x & 0x1FFu) < 0x22u) {
                z.x &= ~0x3Fu;
                uvec2 shifted = ad_u64shr(z, 6);
                rem = ad_u64sub(uvec2(0u, m.x << 20), ad_u64mullo(shifted, shifted));
                if ((rem.y >> 31) != 0u) z = ad_u64sub(z, uvec2(1u, 0u));
                else if (ad_nonzero(rem)) z.x |= 1u;
            }
            return ad_round(0u, zExp, z);
        }
        bool ad_eq(uvec2 a, uvec2 b) {
            if (ad_isnan(a) || ad_isnan(b)) return false;
            if (ad_iszero(a) && ad_iszero(b)) return true;
            return a.x == b.x && a.y == b.y;
        }
        bool ad_lt(uvec2 a, uvec2 b) {
            if (ad_isnan(a) || ad_isnan(b)) return false;
            if (ad_iszero(a) && ad_iszero(b)) return false;
            uint sa = ad_signbit(a), sb = ad_signbit(b);
            if (sa != sb) return sa != 0u;
            return sa != 0u ? ad_u64lt(ad_abs(b), ad_abs(a)) : ad_u64lt(a, b);
        }
        bool ad_le(uvec2 a, uvec2 b) { return ad_eq(a, b) || ad_lt(a, b); }
        bool ad_unordered(uvec2 a, uvec2 b) { return ad_isnan(a) || ad_isnan(b); }
        uvec2 ad_fromfloat(float f) {
            uint bits = floatBitsToUint(f), sign = bits >> 31, exponent = (bits >> 23) & 0xFFu, mantissa = bits & 0x7FFFFFu;
            if (exponent == 0u) {
                if (mantissa == 0u) return ad_pack(sign, 0u, uvec2(0u));
                int msb = findMSB(mantissa);
                return ad_pack(sign, uint(msb + 874), ad_frac(ad_u64shl(uvec2(mantissa, 0u), 52 - msb)));
            }
            if (exponent == 0xFFu) return ad_pack(sign, 0x7FFu, uvec2(mantissa << 29, mantissa >> 3));
            return ad_pack(sign, exponent + 896u, uvec2(mantissa << 29, mantissa >> 3));
        }
        float ad_tofloat(uvec2 a) {
            int aExp = ad_exp(a);
            uint sign = ad_signbit(a) << 31;
            if (aExp == 0x7FF) return uintBitsToFloat(ad_nonzero(ad_frac(a)) ? 0x7FC00000u : sign | 0x7F800000u);
            if (ad_iszero(a)) return uintBitsToFloat(sign);
            uvec2 m = ad_frac(a);
            if (aExp == 0) m = ad_normsub(m, aExp);
            else m.y |= 0x100000u;
            int e = aExp - 1023 + 127;
            if (e >= 255) return uintBitsToFloat(sign | 0x7F800000u);
            if (e <= 0) {
                // A float's denormal, rounded to nearest even like the rest.
                int shift = 30 - e;
                if (shift >= 64) return uintBitsToFloat(sign);
                uvec2 whole = ad_u64shr(m, shift), rest = ad_u64sub(m, ad_u64shl(whole, shift)), midway = ad_u64shl(uvec2(1u, 0u), shift - 1);
                uint kept = whole.x;
                if (ad_u64lt(midway, rest) || (rest == midway && (kept & 1u) != 0u)) kept++;
                return uintBitsToFloat(sign | kept);
            }
            uint kept = ad_u64shr(m, 29).x, low = m.x & 0x1FFFFFFFu;
            if (low > 0x10000000u || (low == 0x10000000u && (kept & 1u) != 0u)) kept++;
            return uintBitsToFloat(sign | ((uint(e) << 23) + (kept - 0x800000u)));
        }
        uvec2 ad_fromuint(uint u) {
            if (u == 0u) return AD_ZERO;
            int msb = findMSB(u);
            uvec2 sig = ad_u64shl(uvec2(u, 0u), 52 - msb);
            return ad_pack(0u, uint(1023 + msb), ad_frac(sig));
        }
        uvec2 ad_fromint(int i) {
            uvec2 d = ad_fromuint(i < 0 ? uint(-i) : uint(i));
            return i < 0 ? ad_neg(d) : d;
        }
        uint ad_magnitude(uvec2 a) {
            int e = ad_exp(a) - 1023;
            if (e < 0) return 0u;
            if (e >= 32) return 0xFFFFFFFFu;
            uvec2 m = ad_frac(a);
            m.y |= 0x100000u;
            return ad_u64shr(m, 52 - e).x;
        }
        uint ad_touint(uvec2 a) { return ad_signbit(a) != 0u ? 0u : ad_magnitude(a); }
        int ad_toint(uvec2 a) {
            uint m = ad_magnitude(a);
            if (ad_signbit(a) != 0u) return m >= 0x80000000u ? int(0x80000000u) : -int(m);
            return m >= 0x7FFFFFFFu ? 0x7FFFFFFF : int(m);
        }
        uvec2 ad_trunc(uvec2 a) {
            int e = ad_exp(a) - 1023;
            if (e < 0) return uvec2(0u, a.y & 0x80000000u);
            if (e >= 52) return a;
            int n = 52 - e;
            if (n >= 32) return uvec2(0u, a.y & ~((1u << (n - 32)) - 1u));
            return uvec2(a.x & ~((1u << n) - 1u), a.y);
        }
        uvec2 ad_floor(uvec2 a) {
            uvec2 t = ad_trunc(a);
            return ad_signbit(a) != 0u && !ad_eq(t, a) ? ad_sub(t, AD_ONE) : t;
        }
        uvec2 ad_ceil(uvec2 a) {
            uvec2 t = ad_trunc(a);
            return ad_signbit(a) == 0u && !ad_eq(t, a) ? ad_add(t, AD_ONE) : t;
        }
        uvec2 ad_fract(uvec2 a) { return ad_sub(a, ad_floor(a)); }
        uvec2 ad_roundeven(uvec2 a) {
            uvec2 t = ad_trunc(a);
            if (ad_exp(a) == 0x7FF) return a;
            uvec2 diff = ad_abs(ad_sub(a, t));
            uvec2 away = ad_signbit(a) != 0u ? ad_sub(t, AD_ONE) : ad_add(t, AD_ONE);
            if (ad_lt(diff, AD_HALF)) return t;
            if (ad_lt(AD_HALF, diff)) return away;
            uvec2 half_t = ad_trunc(ad_mul(t, AD_HALF));
            return ad_eq(ad_mul(half_t, AD_TWO), t) ? t : away;
        }
        uvec2 ad_mod(uvec2 x, uvec2 y) { return ad_sub(x, ad_mul(y, ad_floor(ad_div(x, y)))); }
        uvec2 ad_rem(uvec2 x, uvec2 y) { return ad_sub(x, ad_mul(y, ad_trunc(ad_div(x, y)))); }
        uvec2 ad_min(uvec2 x, uvec2 y) { return ad_lt(y, x) ? y : x; }
        uvec2 ad_max(uvec2 x, uvec2 y) { return ad_lt(x, y) ? y : x; }
        uvec2 ad_clamp(uvec2 x, uvec2 lo, uvec2 hi) { return ad_min(ad_max(x, lo), hi); }
        uvec2 ad_sign(uvec2 a) {
            if (ad_iszero(a)) return AD_ZERO;
            if (ad_isnan(a)) return a;
            return ad_signbit(a) != 0u ? ad_neg(AD_ONE) : AD_ONE;
        }
        uvec2 ad_mix(uvec2 x, uvec2 y, uvec2 a) { return ad_add(ad_mul(x, ad_sub(AD_ONE, a)), ad_mul(y, a)); }
        uvec2 ad_step(uvec2 edge, uvec2 x) { return ad_lt(x, edge) ? AD_ZERO : AD_ONE; }
        uvec2 ad_smoothstep(uvec2 e0, uvec2 e1, uvec2 x) {
            uvec2 t = ad_clamp(ad_div(ad_sub(x, e0), ad_sub(e1, e0)), AD_ZERO, AD_ONE);
            return ad_mul(ad_mul(t, t), ad_sub(AD_THREE, ad_mul(AD_TWO, t)));
        }
        uvec2 ad_fma(uvec2 a, uvec2 b, uvec2 c) { return ad_add(ad_mul(a, b), c); }
        uvec2 ad_inversesqrt(uvec2 a) { return ad_div(AD_ONE, ad_sqrt(a)); }
        uvec2 ad_frexp(uvec2 a, out int e) {
            int aExp = ad_exp(a);
            if (ad_iszero(a) || aExp == 0x7FF) {
                e = 0;
                return a;
            }
            uvec2 m = ad_frac(a);
            if (aExp == 0) m = ad_frac(ad_normsub(m, aExp));
            e = aExp - 1022;
            return ad_pack(ad_signbit(a), 1022u, m);
        }
        uvec2 ad_ldexp(uvec2 a, int n) {
            int aExp = ad_exp(a);
            if (ad_iszero(a) || aExp == 0x7FF) return a;
            uvec2 m = ad_frac(a);
            if (aExp == 0) m = ad_normsub(m, aExp);
            else m.y |= 0x100000u;
            return ad_round(ad_signbit(a), aExp + clamp(n, -4000, 4000) - 1, ad_u64shl(m, 10));
        }
        uvec2 ad_modf(uvec2 a, out uvec2 whole) {
            whole = ad_trunc(a);
            return ad_sub(a, whole);
        }
        uvec2 ad_length1(uvec2 a) { return ad_abs(a); }
        uvec2 ad_distance1(uvec2 a, uvec2 b) { return ad_abs(ad_sub(a, b)); }
        uvec2 ad_normalize1(uvec2 a) { return ad_sign(a); }
        uvec2 ad_faceforward1(uvec2 n, uvec2 i, uvec2 nref) { return ad_lt(ad_mul(nref, i), AD_ZERO) ? n : ad_neg(n); }
        uvec2 ad_reflect1(uvec2 i, uvec2 n) { return ad_sub(i, ad_mul(ad_mul(AD_TWO, ad_mul(n, i)), n)); }
        uvec2 ad_refract1(uvec2 i, uvec2 n, uvec2 eta) {
            uvec2 d = ad_mul(n, i);
            uvec2 k = ad_sub(AD_ONE, ad_mul(ad_mul(eta, eta), ad_sub(AD_ONE, ad_mul(d, d))));
            if (ad_lt(k, AD_ZERO)) return AD_ZERO;
            return ad_sub(ad_mul(eta, i), ad_mul(ad_add(ad_mul(eta, d), ad_sqrt(k)), n));
        }
        uvec2 ad_det2x2(uvec2 a, uvec2 b, uvec2 c, uvec2 d) { return ad_sub(ad_mul(a, d), ad_mul(b, c)); }
        uvec2 ad_determinant2(uvec2 m[2][2]) { return ad_det2x2(m[0][0], m[0][1], m[1][0], m[1][1]); }
        uvec2 ad_determinant3(uvec2 m[3][3]) {
            uvec2 x = ad_mul(m[0][0], ad_det2x2(m[1][1], m[1][2], m[2][1], m[2][2]));
            uvec2 y = ad_mul(m[1][0], ad_det2x2(m[0][1], m[0][2], m[2][1], m[2][2]));
            uvec2 z = ad_mul(m[2][0], ad_det2x2(m[0][1], m[0][2], m[1][1], m[1][2]));
            return ad_add(ad_sub(x, y), z);
        }
        uvec2 ad_minor4(uvec2 m[4][4], int column, int row) {
            uvec2 s[3][3];
            for (int c = 0, sc = 0; c < 4; c++) {
                if (c == column) continue;
                for (int r = 0, sr = 0; r < 4; r++) {
                    if (r == row) continue;
                    s[sc][sr] = m[c][r];
                    sr++;
                }
                sc++;
            }
            return ad_determinant3(s);
        }
        uvec2 ad_determinant4(uvec2 m[4][4]) {
            uvec2 a = ad_mul(m[0][0], ad_minor4(m, 0, 0));
            uvec2 b = ad_mul(m[1][0], ad_minor4(m, 1, 0));
            uvec2 c = ad_mul(m[2][0], ad_minor4(m, 2, 0));
            uvec2 d = ad_mul(m[3][0], ad_minor4(m, 3, 0));
            return ad_sub(ad_add(ad_sub(a, b), c), d);
        }
        uvec2[2][2] ad_inverse2(uvec2 m[2][2]) {
            uvec2 det = ad_determinant2(m);
            uvec2 r[2][2];
            r[0][0] = ad_div(m[1][1], det);
            r[0][1] = ad_div(ad_neg(m[0][1]), det);
            r[1][0] = ad_div(ad_neg(m[1][0]), det);
            r[1][1] = ad_div(m[0][0], det);
            return r;
        }
        uvec2[3][3] ad_inverse3(uvec2 m[3][3]) {
            uvec2 det = ad_determinant3(m);
            uvec2 r[3][3];
            r[0][0] = ad_div(ad_det2x2(m[1][1], m[1][2], m[2][1], m[2][2]), det);
            r[0][1] = ad_div(ad_neg(ad_det2x2(m[0][1], m[0][2], m[2][1], m[2][2])), det);
            r[0][2] = ad_div(ad_det2x2(m[0][1], m[0][2], m[1][1], m[1][2]), det);
            r[1][0] = ad_div(ad_neg(ad_det2x2(m[1][0], m[1][2], m[2][0], m[2][2])), det);
            r[1][1] = ad_div(ad_det2x2(m[0][0], m[0][2], m[2][0], m[2][2]), det);
            r[1][2] = ad_div(ad_neg(ad_det2x2(m[0][0], m[0][2], m[1][0], m[1][2])), det);
            r[2][0] = ad_div(ad_det2x2(m[1][0], m[1][1], m[2][0], m[2][1]), det);
            r[2][1] = ad_div(ad_neg(ad_det2x2(m[0][0], m[0][1], m[2][0], m[2][1])), det);
            r[2][2] = ad_div(ad_det2x2(m[0][0], m[0][1], m[1][0], m[1][1]), det);
            return r;
        }
        uvec2[4][4] ad_inverse4(uvec2 m[4][4]) {
            uvec2 det = ad_determinant4(m);
            uvec2 r[4][4];
            for (int c = 0; c < 4; c++) {
                for (int rw = 0; rw < 4; rw++) {
                    uvec2 cofactor = ad_minor4(m, rw, c);
                    r[c][rw] = ad_div((c + rw) % 2 != 0 ? ad_neg(cofactor) : cofactor, det);
                }
            }
            return r;
        }
        """;

    /** The geometric functions, for each size of vector. */
    private static String vectors() {
        final StringBuilder out = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            final String t = "uvec2[" + n + "]", p = "[" + n + "]";
            out.append("uvec2 ad_dot").append(n).append("(uvec2 a").append(p).append(", uvec2 b").append(p).append(") {\n    uvec2 r = AD_ZERO;\n")
                .append("    for (int i = 0; i < ").append(n).append("; i++) r = ad_add(r, ad_mul(a[i], b[i]));\n    return r;\n}\n");
            out.append("uvec2 ad_length").append(n).append("(uvec2 a").append(p).append(") { return ad_sqrt(ad_dot").append(n).append("(a, a)); }\n");
            out.append("uvec2 ad_distance").append(n).append("(uvec2 a").append(p).append(", uvec2 b").append(p).append(") {\n    uvec2 d").append(p).append(";\n")
                .append("    for (int i = 0; i < ").append(n).append("; i++) d[i] = ad_sub(a[i], b[i]);\n    return ad_length").append(n).append("(d);\n}\n");
            out.append(t).append(" ad_normalize").append(n).append("(uvec2 a").append(p).append(") {\n    uvec2 l = ad_length").append(n).append("(a);\n    uvec2 r").append(p).append(";\n")
                .append("    for (int i = 0; i < ").append(n).append("; i++) r[i] = ad_div(a[i], l);\n    return r;\n}\n");
            out.append(t).append(" ad_faceforward").append(n).append("(uvec2 nv").append(p).append(", uvec2 iv").append(p).append(", uvec2 nref").append(p).append(") {\n    if (ad_lt(ad_dot").append(n)
                .append("(nref, iv), AD_ZERO)) return nv;\n    uvec2 r").append(p).append(";\n    for (int i = 0; i < ").append(n).append("; i++) r[i] = ad_neg(nv[i]);\n    return r;\n}\n");
            out.append(t).append(" ad_reflect").append(n).append("(uvec2 iv").append(p).append(", uvec2 nv").append(p).append(") {\n    uvec2 s = ad_mul(AD_TWO, ad_dot").append(n)
                .append("(nv, iv));\n    uvec2 r").append(p).append(";\n    for (int i = 0; i < ").append(n).append("; i++) r[i] = ad_sub(iv[i], ad_mul(s, nv[i]));\n    return r;\n}\n");
            out.append(t).append(" ad_refract").append(n).append("(uvec2 iv").append(p).append(", uvec2 nv").append(p).append(", uvec2 eta) {\n    uvec2 d = ad_dot").append(n).append("(nv, iv);\n")
                .append("    uvec2 k = ad_sub(AD_ONE, ad_mul(ad_mul(eta, eta), ad_sub(AD_ONE, ad_mul(d, d))));\n    uvec2 r").append(p).append(";\n")
                .append("    if (ad_lt(k, AD_ZERO)) {\n        for (int i = 0; i < ").append(n).append("; i++) r[i] = AD_ZERO;\n        return r;\n    }\n")
                .append("    uvec2 s = ad_add(ad_mul(eta, d), ad_sqrt(k));\n    for (int i = 0; i < ").append(n).append("; i++) r[i] = ad_sub(ad_mul(eta, iv[i]), ad_mul(s, nv[i]));\n    return r;\n}\n");
        }
        out.append("""
            uvec2[3] ad_cross3(uvec2 a[3], uvec2 b[3]) {
                uvec2 r[3];
                r[0] = ad_sub(ad_mul(a[1], b[2]), ad_mul(b[1], a[2]));
                r[1] = ad_sub(ad_mul(a[2], b[0]), ad_mul(b[2], a[0]));
                r[2] = ad_sub(ad_mul(a[0], b[1]), ad_mul(b[0], a[1]));
                return r;
            }
            """);
        return out.toString();
    }

    private static final Pattern FUNCTION = Pattern.compile("(?m)^(?:\\S+)\\s+(ad_\\w+)\\((.*)\\)\\s*\\{");

    /** A compiler only keeps what main reaches, so main reaches everything; the lowering takes what a shader uses. */
    private static String withMain(String functions) {
        final StringBuilder main = new StringBuilder("void main() {\n    uvec2 u = uvec2(0u), v2[2], v3[3], v4[4], m2[2][2], m3[3][3], m4[4][4];\n    bool b;\n    int i = 0;\n    uint n = 0u;\n    float f = 0.0;\n");
        final Matcher m = FUNCTION.matcher(functions);
        while (m.find()) {
            main.append("    ").append(m.group(1)).append('(');
            final String[] params = m.group(2).split(",");
            for (int k = 0; k < params.length; k++) {
                final String param = params[k].trim();
                main.append(k == 0 ? "" : ", ");
                if (param.contains("[2][2]")) main.append("m2");
                else if (param.contains("[3][3]")) main.append("m3");
                else if (param.contains("[4][4]")) main.append("m4");
                else if (param.contains("[2]")) main.append("v2");
                else if (param.contains("[3]")) main.append("v3");
                else if (param.contains("[4]")) main.append("v4");
                else if (param.contains("uvec2")) main.append("u");
                else if (param.contains("int") && !param.contains("uint")) main.append("i");
                else if (param.contains("uint")) main.append("n");
                else if (param.contains("float")) main.append("f");
                else throw new IllegalStateException(param);
            }
            main.append(");\n");
        }
        return main.append("}\n").toString();
    }

    /** The 64 bit arithmetic a double's words need, as pairs of 32 bit words or, on a device with them, 64 bit integers. */
    private static final String NARROW_WORDS = """
        uvec2 ad_u64add(uvec2 a, uvec2 b) {
            uint lo = a.x + b.x;
            return uvec2(lo, a.y + b.y + (lo < a.x ? 1u : 0u));
        }
        uvec2 ad_u64sub(uvec2 a, uvec2 b) {
            return uvec2(a.x - b.x, a.y - b.y - (a.x < b.x ? 1u : 0u));
        }
        bool ad_u64lt(uvec2 a, uvec2 b) {
            return a.y < b.y || (a.y == b.y && a.x < b.x);
        }
        bool ad_nonzero(uvec2 a) {
            return (a.x | a.y) != 0u;
        }
        uvec2 ad_u64shl(uvec2 a, int n) {
            if (n <= 0) return a;
            if (n >= 64) return uvec2(0u);
            if (n >= 32) return uvec2(0u, a.x << (n - 32));
            return uvec2(a.x << n, (a.y << n) | (a.x >> (32 - n)));
        }
        uvec2 ad_u64shr(uvec2 a, int n) {
            if (n <= 0) return a;
            if (n >= 64) return uvec2(0u);
            if (n >= 32) return uvec2(a.y >> (n - 32), 0u);
            return uvec2((a.x >> n) | (a.y << (32 - n)), a.y >> n);
        }
        uvec2 ad_u64shrj(uvec2 a, int n) {
            if (n <= 0) return a;
            if (n >= 64) return uvec2(ad_nonzero(a) ? 1u : 0u, 0u);
            uvec2 r = ad_u64shr(a, n);
            if (ad_nonzero(ad_u64shl(a, 64 - n))) r.x |= 1u;
            return r;
        }
""", NARROW_PRODUCTS = """
        uvec2 ad_mul32(uint a, uint b) {
            uint hi, lo;
            umulExtended(a, b, hi, lo);
            return uvec2(lo, hi);
        }
        uvec2 ad_u64mullo(uvec2 a, uvec2 b) {
            uvec2 low = ad_mul32(a.x, b.x);
            return uvec2(low.x, low.y + a.x * b.y + a.y * b.x);
        }
        /** The top 64 bits of a 128 bit product, the lowest set when anything below them is. */
        uvec2 ad_mulhi(uvec2 x, uvec2 y) {
            uint hi00, lo00, hi01, lo01, hi10, lo10, hi11, lo11;
            umulExtended(x.x, y.x, hi00, lo00);
            umulExtended(x.x, y.y, hi01, lo01);
            umulExtended(x.y, y.x, hi10, lo10);
            umulExtended(x.y, y.y, hi11, lo11);
            uint w1 = hi00 + lo01;
            uint carry = w1 < lo01 ? 1u : 0u;
            w1 += lo10;
            carry += w1 < lo10 ? 1u : 0u;
            uint w2 = hi01 + hi10;
            uint carry2 = w2 < hi10 ? 1u : 0u;
            w2 += lo11;
            carry2 += w2 < lo11 ? 1u : 0u;
            w2 += carry;
            carry2 += w2 < carry ? 1u : 0u;
            uvec2 z = uvec2(w2, hi11 + carry2);
            if ((lo00 | w1) != 0u) z.x |= 1u;
            return z;
        }
""", WIDE_WORDS = """
        uvec2 ad_u64add(uvec2 a, uvec2 b) { return unpackUint2x32(packUint2x32(a) + packUint2x32(b)); }
        uvec2 ad_u64sub(uvec2 a, uvec2 b) { return unpackUint2x32(packUint2x32(a) - packUint2x32(b)); }
        bool ad_u64lt(uvec2 a, uvec2 b) { return packUint2x32(a) < packUint2x32(b); }
        bool ad_nonzero(uvec2 a) {
            return (a.x | a.y) != 0u;
        }
        uvec2 ad_u64shl(uvec2 a, int n) {
            if (n <= 0) return a;
            if (n >= 64) return uvec2(0u);
            return unpackUint2x32(packUint2x32(a) << uint(n));
        }
        uvec2 ad_u64shr(uvec2 a, int n) {
            if (n <= 0) return a;
            if (n >= 64) return uvec2(0u);
            return unpackUint2x32(packUint2x32(a) >> uint(n));
        }
        uvec2 ad_u64shrj(uvec2 a, int n) {
            if (n <= 0) return a;
            if (n >= 64) return uvec2(ad_nonzero(a) ? 1u : 0u, 0u);
            uint64_t v = packUint2x32(a);
            uint64_t r = v >> uint(n);
            if ((v & ((uint64_t(1) << uint(n)) - uint64_t(1))) != uint64_t(0)) r |= uint64_t(1);
            return unpackUint2x32(r);
        }
""", WIDE_PRODUCTS = """
        uvec2 ad_mul32(uint a, uint b) { return unpackUint2x32(uint64_t(a) * uint64_t(b)); }
        uvec2 ad_u64mullo(uvec2 a, uvec2 b) { return unpackUint2x32(packUint2x32(a) * packUint2x32(b)); }
        uvec2 ad_mulhi(uvec2 x, uvec2 y) {
            uint64_t p00 = uint64_t(x.x) * uint64_t(y.x), p01 = uint64_t(x.x) * uint64_t(y.y), p10 = uint64_t(x.y) * uint64_t(y.x), p11 = uint64_t(x.y) * uint64_t(y.y);
            uint64_t mid = (p00 >> 32) + (p01 & 0xFFFFFFFFul) + (p10 & 0xFFFFFFFFul);
            uint64_t hi = p11 + (p01 >> 32) + (p10 >> 32) + (mid >> 32);
            if (((p00 | mid) & 0xFFFFFFFFul) != 0ul) hi |= 1ul;
            return unpackUint2x32(hi);
        }
""";

    /** Whether shaders may use 64 bit integers, set by the backend from what the device offers. */
    static volatile boolean int64;
    private static int[] compiled;
    private static boolean compiledWide;

    static synchronized int[] words() {
        final boolean wide = int64;
        if (compiled != null && compiledWide == wide) return compiled;
        final String functions = SCALARS.replace("AD_WORDS", wide ? WIDE_WORDS : NARROW_WORDS).replace("AD_PRODUCTS", wide ? WIDE_PRODUCTS : NARROW_PRODUCTS)
            .lines().map(line -> line.startsWith("        ") ? line.substring(8) : line).collect(java.util.stream.Collectors.joining("\n", "", "\n")) + vectors();
        final String source = "#version 460\n" + (wide ? "#extension GL_ARB_gpu_shader_int64 : require\n" : "") + "layout(local_size_x = 1) in;\n" + functions + withMain(functions);
        final String[] error = new String[1];
        final byte[] spirv = ShaderCompiler.compileLibrary(source, error);
        if (spirv == null) throw new IllegalStateException("the double library did not compile: " + error[0]);
        final int[] out = new int[spirv.length / 4];
        ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
        compiled = out;
        compiledWide = wide;
        return out;
    }

    private SoftDouble() {}
}
