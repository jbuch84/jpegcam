#ifndef PROCESS_KERNEL_H
#define PROCESS_KERNEL_H

#include <stdint.h>
#include <stdlib.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <math.h>
#include <algorithm>
#include <cmath>
#include <vector>

#define CLAMP(x) ((x) < 0 ? 0 : ((x) > 255 ? 255 : (x)))

// Fast Integer Approximation of CSS 'Overlay' Blend Mode
inline int blend_overlay(int base, int blend) {
    base = CLAMP(base);
    blend = CLAMP(blend);
    if (base < 128) {
        return (base * blend) >> 7;
    } else {
        return 255 - (((255 - base) * (255 - blend)) >> 7);
    }
}

#define LOG_TAG "COOKBOOK_NATIVE"
#ifdef __ANDROID__
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#include <stdio.h>
#define LOGD(...) printf(__VA_ARGS__); printf("\n")
#endif

// === KERNEL_UI_METADATA ===
// shadowToe, 0, 2
// rollOff, 0, 5
// colorChrome, 0, 2
// chromeBlue, 0, 2
// subtractiveSat, 0, 2
// halation, 0, 2
// vignette, 0, 5
// grain, 0, 5
// grainSize, 0, 2
// bloom, 0, 6
// === END_METADATA ===

inline long long get_vig_coef(int vignette, long long max_dist_sq) {
    int s_vig = vignette * 12;
    return ((long long)((s_vig * 256) / 100) << 24) / (max_dist_sq > 0 ? max_dist_sq : 1);
}

inline void generate_rolloff_lut(uint8_t* lut, int rollOff) {
    int s_roll = rollOff * 20;
    for (int i = 0; i < 256; i++) {
        int r_t = (i > 200) ? i - ((i - 200) * (i - 200) * s_roll) / 11000 : i;
        lut[i] = (uint8_t)CLAMP(r_t);
    }
}

// OPTICAL BLOOM & TRUE HALATION ENGINE V5 (PHYSICALLY BASED)
// ==========================================
inline void apply_bloom_halation(
    unsigned char** rows, uint8_t* out_row, int width, int abs_y, bool is_yuv, int bloom, int halation,
    int* work_0, int* work_1, int* work_2, int* work_h, int* h_line, int scaleDenom)
{
    // 1. Resolution-Aware Alphas & Intensities
    int alpha, b_mix;
    
    if (bloom == 5) {        // Local 1/8 (Tight radius, extremely subtle mix)
        alpha = (scaleDenom == 4) ? 180 : ((scaleDenom == 2) ? 210 : 230);
        b_mix = 45;
    } else if (bloom == 6) { // Full 1/8 (Wide radius, extremely subtle mix)
        alpha = (scaleDenom == 4) ? 230 : ((scaleDenom == 2) ? 245 : 252);
        b_mix = 45;
    } else if (bloom == 1) { // Local 1/4 (Tight radius, subtle mix)
        alpha = (scaleDenom == 4) ? 180 : ((scaleDenom == 2) ? 210 : 230);
        b_mix = 90;
    } else if (bloom == 2) { // Full 1/4 (Wide radius, subtle mix)
        alpha = (scaleDenom == 4) ? 230 : ((scaleDenom == 2) ? 245 : 252);
        b_mix = 90;
    } else if (bloom == 3) { // Local 1/2 (Tight radius, heavy mix)
        alpha = (scaleDenom == 4) ? 180 : ((scaleDenom == 2) ? 210 : 230);
        b_mix = 160;
    } else if (bloom == 4) { // Full 1/2 (Wide radius, heavy mix)
        alpha = (scaleDenom == 4) ? 230 : ((scaleDenom == 2) ? 245 : 252);
        b_mix = 160;
    } else {
        alpha = 0; b_mix = 0;
    }
    int inv_alpha = 256 - alpha;

    // Halation alpha remains independent
    int h_alpha;
    if (halation == 1) h_alpha = (scaleDenom == 4) ? 140 : ((scaleDenom == 2) ? 180 : 220);
    else               h_alpha = (scaleDenom == 4) ? 197 : ((scaleDenom == 2) ? 221 : 240);
    int inv_h = 256 - h_alpha;

    if (!work_0 || !work_h) return;

    // 2. Vertical Summation: Extracting Pure Light Maps (High Precision)
    for (int x = 0; x < width; x++) {
        long long s0 = 0, sh = 0;
        for (int y = 0; y <= 20; y++) {
            int w = (y <= 10) ? (y + 1) : (21 - y); // Triangle weight
            int v0 = rows[y][x*3], v1 = rows[y][x*3+1], v2 = rows[y][x*3+2];
            
            // Calculate true brightness (Luma)
            int lum = is_yuv ? v0 : ((v0*77 + v1*150 + v2*29) / 256);

            // --- V6 "SMART" LUMINANCE-DEPENDENT BLOOM EMISSION ---
            int bloom_emission;
            
            if (bloom > 0 && bloom % 2 == 0) {
                // FULL BLOOM (Evens: 2, 4, 6): Leaves shadows linear to maintain global image softening, 
                // but violently boosts highlights into the HDR range.
                if (lum < 128) {
                    bloom_emission = lum; 
                } else {
                    bloom_emission = lum + (((lum - 128) * (lum - 128)) >> 6); 
                }
            } else {
                // LOCAL BLOOM (Odds: 1, 3, 5): Crushes shadow emission geometrically for deep contrast, 
                // only glowing from bright practical light sources.
                if (lum < 128) {
                    bloom_emission = (lum * lum) >> 7; 
                } else {
                    bloom_emission = lum + (((lum - 128) * (lum - 128)) >> 6); 
                }
            }

            s0 += bloom_emission * w;
            
            // Halation remains untouched (it only pulls from extreme highlights)
            if (lum > 210) {
                sh += (lum - 210) * 5 * w; 
            }
        }
        
        work_0[x] = (int)s0; 
        work_h[x] = (int)sh; 
    }

    // 3. Horizontal IIR Blur (Spreading the high-precision light maps)
    if (bloom > 0) {
        int a0 = work_0[0];
        for (int x = 1; x < width; x++) {
            a0 = (a0 * alpha + work_0[x] * inv_alpha + 128) / 256;
            work_0[x] = a0;
        }
        a0 = work_0[width-1];
        for (int x = width-2; x >= 0; x--) {
            a0 = (a0 * alpha + work_0[x] * inv_alpha + 128) / 256;
            work_0[x] = a0;
        }
    }

    if (halation > 0) {
        int ah = work_h[0];
        for (int x = 1; x < width; x++) {
            ah = (ah * h_alpha + work_h[x] * inv_h + 128) / 256;
            work_h[x] = ah;
        }
        ah = work_h[width-1];
        for (int x = width-2; x >= 0; x--) {
            ah = (ah * h_alpha + work_h[x] * inv_h + 128) / 256;
            work_h[x] = ah;
        }
    }

    // 4. Volumetric Reconstruction
    int h_mix = (halation == 1) ? 120 : 200; 

    for (int x = 0; x < width; x++) {
        int v0_o = rows[10][x*3], v1_o = rows[10][x*3+1], v2_o = rows[10][x*3+2];
        int orig_y = is_yuv ? v0_o : ((v0_o*77 + v1_o*150 + v2_o*29)/256);

        int blur_y = work_0[x] / 121;
        int halation_y = work_h[x] / 121;

        int b_bleed = blur_y - orig_y;
        if (b_bleed < 0) b_bleed = 0;

        int h_eff = (halation_y * h_mix) / 256;
        h_eff = (h_eff * (255 - orig_y)) / 256; 

        if (is_yuv) {
            int y_res = v0_o, cb_res = v1_o, cr_res = v2_o;

            if (bloom > 0 && b_bleed > 0) {
                int add_y = (b_bleed * b_mix) / 256;
                y_res += add_y;
                cb_res = cb_res + ((128 - cb_res) * add_y) / 256;
                cr_res = cr_res + ((128 - cr_res) * add_y) / 256;
            }

            if (halation > 0 && h_eff > 0) {
                y_res += h_eff / 3;     
                cr_res += h_eff;        
                cb_res -= h_eff / 2;    
            }

            out_row[x*3]   = (uint8_t)CLAMP(y_res);
            out_row[x*3+1] = (uint8_t)CLAMP(cb_res);
            out_row[x*3+2] = (uint8_t)CLAMP(cr_res);
        } else {
            // RGB Path
            int r_res = v0_o, g_res = v1_o, b_res = v2_o;

            if (bloom > 0 && b_bleed > 0) {
                int add = (b_bleed * b_mix) / 256;
                r_res += add;
                g_res += add;
                b_res += add;
            }

            if (halation > 0 && h_eff > 0) {
                r_res += h_eff;         
                g_res += h_eff / 5;     
                b_res -= h_eff / 5;     
            }

            out_row[x*3]   = (uint8_t)CLAMP(r_res);
            out_row[x*3+1] = (uint8_t)CLAMP(g_res);
            out_row[x*3+2] = (uint8_t)CLAMP(b_res);
        }
    }
}

// Allow the kernel to check the size of the loaded texture
extern std::vector<uint8_t> nativeGrainTexture;

// High-fidelity sampler for 1024x1024 textures to prevent aliasing on Proxy/Half
inline void sample_tex_bilinear_1024(const uint8_t* tex, int x_fp8, int y_fp8, int* outRGB) {
    int x0 = (x_fp8 >> 8) & 1023;
    int y0 = (y_fp8 >> 8) & 1023;
    int x1 = (x0 + 1) & 1023;
    int y1 = (y0 + 1) & 1023;
    int fx = x_fp8 & 255;
    int fy = y_fp8 & 255;

    for (int c = 0; c < 3; c++) {
        int c00 = tex[(y0 * 1024 + x0) * 3 + c];
        int c10 = tex[(y0 * 1024 + x1) * 3 + c];
        int c01 = tex[(y1 * 1024 + x0) * 3 + c];
        int c11 = tex[(y1 * 1024 + x1) * 3 + c];
        
        int top = c00 + (((c10 - c00) * fx) >> 8);
        int bot = c01 + (((c11 - c01) * fx) >> 8);
        outRGB[c] = top + (((bot - top) * fy) >> 8);
    }
}

// High-fidelity sampler for 512x512 textures with built-in XOR Mirroring
inline void sample_tex_bilinear_512_xor(const uint8_t* tex, int x_fp8, int y_fp8, int* outRGB) {
    int px0 = x_fp8 >> 8;
    int py0 = y_fp8 >> 8;
    int px1 = px0 + 1;
    int py1 = py0 + 1;
    int fx = x_fp8 & 255;
    int fy = y_fp8 & 255;

    int x00 = px0 & 511; int y00 = py0 & 511;
    if (((px0 >> 9) ^ (py0 >> 9)) & 1) x00 = 511 - x00;
    if ((((px0 >> 9) * 3) ^ (py0 >> 9)) & 2) y00 = 511 - y00;

    int x10 = px1 & 511; int y10 = py0 & 511;
    if (((px1 >> 9) ^ (py0 >> 9)) & 1) x10 = 511 - x10;
    if ((((px1 >> 9) * 3) ^ (py0 >> 9)) & 2) y10 = 511 - y10;

    int x01 = px0 & 511; int y01 = py1 & 511;
    if (((px0 >> 9) ^ (py1 >> 9)) & 1) x01 = 511 - x01;
    if ((((px0 >> 9) * 3) ^ (py1 >> 9)) & 2) y01 = 511 - y01;

    int x11 = px1 & 511; int y11 = py1 & 511;
    if (((px1 >> 9) ^ (py1 >> 9)) & 1) x11 = 511 - x11;
    if ((((px1 >> 9) * 3) ^ (py1 >> 9)) & 2) y11 = 511 - y11;

    for (int c = 0; c < 3; c++) {
        int c00 = tex[(y00 * 512 + x00) * 3 + c];
        int c10 = tex[(y10 * 512 + x10) * 3 + c];
        int c01 = tex[(y01 * 512 + x01) * 3 + c];
        int c11 = tex[(y11 * 512 + x11) * 3 + c];
        
        int top = c00 + (((c10 - c00) * fx) >> 8);
        int bot = c01 + (((c11 - c01) * fx) >> 8);
        outRGB[c] = top + (((bot - top) * fy) >> 8);
    }
}

inline int grain_amount_mask(int y) {
    if (y < 16 || y > 236) return 0;
    if (y < 40) return ((y - 16) * 52) >> 3;
    if (y < 96) return 156 + ((y - 40) * 88) / 56;
    if (y < 148) return 244 - ((y - 96) * 36) / 52;
    if (y < 196) return 208 - ((y - 148) * 120) / 48;
    return 88 - ((y - 196) * 88) / 40;
}

inline int row_luma_rgb_at(const uint8_t* row, int width, int x) {
    if (x < 0) x = 0;
    if (x >= width) x = width - 1;
    int i = x * 3;
    return (row[i] * 77 + row[i + 1] * 150 + row[i + 2] * 29) >> 8;
}

inline int grain_resolution_scale256(int scaleDenom) {
    switch (scaleDenom) {
        case 2: return 169;
        case 4: return 85;
        default: return 256;
    }
}

// ==========================================
// PATH A: RGB + LUT + ANALOG PHYSICS
// ==========================================
inline void process_row_rgb(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, int scaleDenom,
    int opac_mapped, const int* map,
    const uint8_t* nativeLut, int nativeLutSize, int lutMax, int lutSize2,
    const uint8_t* externalGrainTexture = NULL,
    bool is_1024_grain = false, int t_off_x = 0, int t_off_y = 0)
{
    int s_roll   = rollOff * 20;
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain = (grain * 40) + (grain * grain * 12); 
    s_grain = (s_grain * grain_resolution_scale256(scaleDenom) + 128) >> 8;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    int prevRawY = row_luma_rgb_at(row, width, 0);
    int currRawY = prevRawY;
    int nextRawY = row_luma_rgb_at(row, width, 1);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int r = row[i], g = row[i+1], b = row[i+2];



        // --- LUT CALCS ---
        int fX = map[r], fY = map[g], fZ = map[b];
        int x0 = fX >> 7, y0 = fY >> 7, z0 = fZ >> 7;
        int x1 = (x0 < lutMax) ? x0 + 1 : lutMax;
        int y1 = (y0 < lutMax) ? y0 + 1 : lutMax;
        int z1 = (z0 < lutMax) ? z0 + 1 : lutMax;
        int dx = fX & 0x7F, dy_l = fY & 0x7F, dz = fZ & 0x7F;
        int v1, v2, w0, w1, w2, w3;

        if (dx >= dy_l) {
            if (dy_l >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dx; w1=dx-dy_l; w2=dy_l-dz; w3=dz; }
            else if (dx >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dx; w1=dx-dz; w2=dz-dy_l; w3=dy_l; }
            else { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dx; w2=dx-dy_l; w3=dy_l; }
        } else {
            if (dz >= dy_l) { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dy_l; w2=dy_l-dx; w3=dx; }
            else if (dz >= dx) { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dy_l; w1=dy_l-dz; w2=dz-dx; w3=dx; }
            else { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dy_l; w1=dy_l-dx; w2=dx-dz; w3=dz; }
        }

        const uint8_t* p = &nativeLut[(x0 + y0*nativeLutSize + z0*lutSize2)*3];
        const uint8_t* p1_v = &nativeLut[v1*3];
        const uint8_t* p2_v = &nativeLut[v2*3];
        const uint8_t* p3_v = &nativeLut[(x1 + y1*nativeLutSize + z1*lutSize2)*3];

        int outR = r + ((((p[0]*w0 + p1_v[0]*w1 + p2_v[0]*w2 + p3_v[0]*w3) >> 7) - r) * opac_mapped >> 8);
        int outG = g + ((((p[1]*w0 + p1_v[1]*w1 + p2_v[1]*w2 + p3_v[1]*w3) >> 7) - g) * opac_mapped >> 8);
        int outB = b + ((((p[2]*w0 + p1_v[2]*w1 + p2_v[2]*w2 + p3_v[2]*w3) >> 7) - b) * opac_mapped >> 8);

        int currentY = (outR*77 + outG*150 + outB*29) >> 8;
        int targetY = currentY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (targetY < lift) targetY += ((lift - targetY) * (lift - targetY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0 && targetY > 200) targetY -= ((targetY - 200) * (targetY - 200) * s_roll) / 11000;

        // --- OPTIMIZATION: Only run heavy color math if effects are ON ---
        if (s_chrome > 0 || s_blue > 0 || s_sat > 0) {
            int cb_p = ((-38 * outR - 74 * outG + 112 * outB) >> 8);
            int cr_p = ((112 * outR - 94 * outG - 18 * outB) >> 8);
            int sat_p = (cb_p < 0 ? -cb_p : cb_p) + (cr_p < 0 ? -cr_p : cr_p);

            if (s_chrome > 0 && sat_p > 15) {
                int drop = ((sat_p - 15) * s_chrome) >> 8;
                if (targetY > 160) { int fade = 255 - ((targetY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
                if (drop > (targetY >> 2)) drop = targetY >> 2;
                targetY -= drop;
            }
            if (s_blue > 0 && cb_p > 5 && cr_p < 25) {
                int drop = (cb_p * s_blue) >> 7;
                if (targetY > 160) { int fade = 255 - ((targetY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
                if (targetY < 50) { int fade = (targetY * 5); if (fade > 255) fade = 255; drop = (drop * fade) >> 8; }
                if (drop > (targetY >> 2)) drop = targetY >> 2;
                targetY -= drop;
            }
            if (s_sat > 0 && sat_p > 20) {
                int density = ((sat_p - 20) * s_sat) >> 8;
                if (targetY > 200) { int fade = 255 - ((targetY - 200) * 4); if (fade < 0) fade = 0; density = (density * fade) >> 8; }
                if (density > (targetY >> 2)) density = targetY >> 2;
                targetY -= density;
            }
        }

        if (targetY < 8) targetY = 8;
        if (targetY != currentY) {
            int r256 = (targetY * 256) / (currentY == 0 ? 1 : currentY);
            outR = (outR * r256) >> 8; outG = (outG * r256) >> 8; outB = (outB * r256) >> 8;
        }

        if (halation > 0 && targetY > 245) {
            int push = (targetY - 245) * (halation == 1 ? 3 : 6);
            outR += push; outG -= (push >> 2); outB -= (push >> 1);
        }

        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) >> 24);
            if (v_m < 0) v_m = 0;
            outR = (outR * v_m) >> 8; outG = (outG * v_m) >> 8; outB = (outB * v_m) >> 8;
            
            d_sq += d_sq_step; 
            d_sq_step += 2;
        }

        // Texture Overlay
        if (externalGrainTexture != NULL && grain > 0) {
            // --- NEW: LUMINANCE MASKING ---
            // Grab the optical density mask from Engine 1 to protect shadows and highlights
            int env = grain_amount_mask(targetY);
            
            if (env > 0) {
                // Determine if we loaded a 512 or 1024 texture based on parameter
                bool is_1024 = is_1024_grain;
                int tr, tg, tb;

                // Pre-calculate physical offset coordinates for this pixel
                int ox = x + t_off_x;
                int oy = abs_y + t_off_y;

                if (scaleDenom == 1) {
                    if (is_1024) {
                        // --- FAST PATH (FULL RES): 1024x1024 with Offset & XOR Mirroring ---
                        int tx = ox & 1023;
                        int ty = oy & 1023;
                        if (((ox >> 10) ^ (oy >> 10)) & 1) tx = 1023 - tx;
                        if ((((ox >> 10) * 3) ^ (oy >> 10)) & 2) ty = 1023 - ty;
                        int tex_idx = (ty * 1024 + tx) * 3;
                        tr = externalGrainTexture[tex_idx];
                        tg = externalGrainTexture[tex_idx + 1];
                        tb = externalGrainTexture[tex_idx + 2];
                    } else {
                        // --- FAST PATH (FULL RES): 512x512 with Offset & XOR Mirroring ---
                        int tx = ox & 511;
                        int ty = oy & 511;
                        if (((ox >> 9) ^ (oy >> 9)) & 1) tx = 511 - tx;
                        if ((((ox >> 9) * 3) ^ (oy >> 9)) & 2) ty = 511 - ty;
                        int tex_idx = (ty * 512 + tx) * 3;
                        tr = externalGrainTexture[tex_idx];
                        tg = externalGrainTexture[tex_idx + 1];
                        tb = externalGrainTexture[tex_idx + 2];
                    }
                } else {
                    // --- HQ PATH (HALF/PROXY): Bilinear Interpolation with Offset ---
                    int gRGB[3];
                    if (is_1024) {
                        sample_tex_bilinear_1024(externalGrainTexture, (ox * scaleDenom) << 8, (oy * scaleDenom) << 8, gRGB);
                    } else {
                        sample_tex_bilinear_512_xor(externalGrainTexture, (ox * scaleDenom) << 8, (oy * scaleDenom) << 8, gRGB);
                    }
                    tr = gRGB[0]; tg = gRGB[1]; tb = gRGB[2];
                }

                int blendedR = blend_overlay(outR, tr);
                int blendedG = blend_overlay(outG, tg);
                int blendedB = blend_overlay(outB, tb);

                int base_mix = (grain >= 5) ? 256 : (grain * 51);
                int mix = (base_mix * env) >> 8;

                outR = outR + (((blendedR - outR) * mix) >> 8);
                outG = outG + (((blendedG - outG) * mix) >> 8);
                outB = outB + (((blendedB - outB) * mix) >> 8);
            }
            
        }

        row[i] = (uint8_t)CLAMP(outR); row[i+1] = (uint8_t)CLAMP(outG); row[i+2] = (uint8_t)CLAMP(outB);

        prevRawY = currRawY;
        currRawY = nextRawY;
        nextRawY = row_luma_rgb_at(row, width, x + 2);
    }
}

// ==========================================
// PATH B: THE YUV EXPRESSWAY
// ==========================================
inline void process_row_yuv(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, int scaleDenom,
    const uint8_t* rolloff_lut,
    const uint8_t* externalGrainTexture = NULL,
    bool is_1024_grain = false, int t_off_x = 0, int t_off_y = 0)
{
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    
    // Exponential math: Slider 1 is subtle, Slider 5 is massive cinematic noise
    int s_grain = (grain * 40) + (grain * grain * 12); 
    s_grain = (s_grain * grain_resolution_scale256(scaleDenom) + 128) >> 8;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    int prevInputY = row[0];
    int currInputY = row[0];
    int nextInputY = (width > 1) ? row[3] : row[0];

    for(int x = 0; x < width; x++) {
        int i = x * 3;
        int oldY = currInputY;
        
        int outY = oldY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (outY < lift) outY += ((lift - outY) * (lift - outY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0) outY = rolloff_lut[outY];
        
        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) >> 24);
            if (v_m < 0) v_m = 0;
            outY = (outY * v_m) >> 8;
            
            d_sq += d_sq_step; 
            d_sq_step += 2;
        }

        int cb = row[i+1] - 128, cr = row[i+2] - 128;
        int sat = (cb >= 0 ? cb : -cb) + (cr >= 0 ? cr : -cr);

        // --- OPTIMIZATION: Only run heavy color math if effects are ON ---
        if (s_chrome > 0 || s_blue > 0 || s_sat > 0) {
            if (s_chrome > 0 && sat > 15) {
                int drop = ((sat - 15) * s_chrome) >> 8;
                if (outY > 160) { int fade = 255 - ((outY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
                if (drop > (outY >> 2)) drop = outY >> 2;
                outY -= drop;
            }
            if (s_blue > 0 && cb > 5 && cr < 25) {
                int drop = (cb * s_blue) >> 7;
                if (outY > 160) { int fade = 255 - ((outY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
                if (outY < 50) { int fade = (outY * 5); if (fade > 255) fade = 255; drop = (drop * fade) >> 8; }
                if (drop > (outY >> 2)) drop = outY >> 2;
                outY -= drop; cr -= (drop >> 1);
            }
            if (s_sat > 0 && sat > 20) {
                int density = ((sat - 20) * s_sat) >> 8;
                if (outY > 200) { int fade = 255 - ((outY - 200) * 4); if (fade < 0) fade = 0; density = (density * fade) >> 8; }
                if (density > (outY >> 2)) density = outY >> 2;
                outY -= density;
            }
        }

        if (outY < 8) outY = 8;
        if (halation > 0 && outY > 245) {
            int push = (outY - 245) * (halation == 1 ? 3 : 6);
            cr += push; cb -= (push >> 1);
        }

        if (oldY != outY) {
            int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
            cb = (cb * r256) >> 8; cr = (cr * r256) >> 8;
        }

        // Texture Overlay
        if (externalGrainTexture != NULL && grain > 0) {
            // --- NEW: LUMINANCE MASKING ---
            int env = grain_amount_mask(outY);

            if (env > 0) {
                bool is_1024 = is_1024_grain;
                int tr, tg, tb;

                // Pre-calculate physical offset coordinates for this pixel
                int ox = x + t_off_x;
                int oy = abs_y + t_off_y;

                if (scaleDenom == 1) {
                    if (is_1024) {
                        // --- FAST PATH (FULL RES): 1024x1024 with Offset & XOR Mirroring ---
                        int tx = ox & 1023;
                        int ty = oy & 1023;
                        if (((ox >> 10) ^ (oy >> 10)) & 1) tx = 1023 - tx;
                        if ((((ox >> 10) * 3) ^ (oy >> 10)) & 2) ty = 1023 - ty;
                        int tex_idx = (ty * 1024 + tx) * 3;
                        tr = externalGrainTexture[tex_idx];
                        tg = externalGrainTexture[tex_idx + 1];
                        tb = externalGrainTexture[tex_idx + 2];
                    } else {
                        // --- FAST PATH (FULL RES): 512x512 with Offset & XOR Mirroring ---
                        int tx = ox & 511;
                        int ty = oy & 511;
                        if (((ox >> 9) ^ (oy >> 9)) & 1) tx = 511 - tx;
                        if ((((ox >> 9) * 3) ^ (oy >> 9)) & 2) ty = 511 - ty;
                        int tex_idx = (ty * 512 + tx) * 3;
                        tr = externalGrainTexture[tex_idx];
                        tg = externalGrainTexture[tex_idx + 1];
                        tb = externalGrainTexture[tex_idx + 2];
                    }
                } else {
                    // --- HQ PATH (HALF/PROXY): Bilinear Interpolation with Offset ---
                    int gRGB[3];
                    if (is_1024) {
                        sample_tex_bilinear_1024(externalGrainTexture, (ox * scaleDenom) << 8, (oy * scaleDenom) << 8, gRGB);
                    } else {
                        sample_tex_bilinear_512_xor(externalGrainTexture, (ox * scaleDenom) << 8, (oy * scaleDenom) << 8, gRGB);
                    }
                    tr = gRGB[0]; tg = gRGB[1]; tb = gRGB[2];
                }
                
                // Fast YUV to RGB for blending
                int r = outY + ((cr * 359) >> 8);
                int g = outY - ((cb * 88 + cr * 183) >> 8);
                int b = outY + ((cb * 454) >> 8);

                int blendedR = blend_overlay(r, tr);
                int blendedG = blend_overlay(g, tg);
                int blendedB = blend_overlay(b, tb);

                int base_mix = (grain >= 5) ? 256 : (grain * 51);
                int mix = (base_mix * env) >> 8;
                
                r = r + (((blendedR - r) * mix) >> 8);
                g = g + (((blendedG - g) * mix) >> 8);
                b = b + (((blendedB - b) * mix) >> 8);

                // Fast RGB back to YUV
                outY = (r * 77 + g * 150 + b * 29) >> 8;
                cb = ((-38 * r - 74 * g + 112 * b) >> 8);
                cr = ((112 * r - 94 * g - 18 * b) >> 8);
            }
            
        }

        row[i] = (uint8_t)CLAMP(outY); row[i+1] = (uint8_t)CLAMP(128+cb); row[i+2] = (uint8_t)CLAMP(128+cr);

        prevInputY = currInputY;
        currInputY = nextInputY;
        nextInputY = (x + 2 < width) ? row[(x + 2) * 3] : currInputY;
    }
}

#endif