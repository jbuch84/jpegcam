#ifndef PROCESS_KERNEL_H
#define PROCESS_KERNEL_H

#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>
#include <math.h>

#define CLAMP(x) ((x) < 0 ? 0 : ((x) > 255 ? 255 : (x)))
#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

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
// bloom, 0, 2
// === END_METADATA ===

// --- SHARED HELPERS ---
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

inline uint32_t fast_rand(uint32_t* state) {
    uint32_t x = *state; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *state = x; return x;
}

// ==========================================
// OPTICAL BLOOM & TRUE HALATION ENGINE V4
//
// FIXED: Handled YUV offsets and prevented integer overflow/wrap-around.
// ==========================================
// ==========================================
// OPTICAL BLOOM & TRUE HALATION ENGINE V5 (PHYSICALLY BASED)
// ==========================================
inline void apply_bloom_halation(
    unsigned char** rows, uint8_t* out_row, int width, int abs_y, bool is_yuv, int bloom, int halation, uint32_t seed,
    int* work_0, int* work_1, int* work_2, int* work_h, int* h_line, int scaleDenom)
{
    // 1. Resolution-Aware Alphas
    int alpha;
    if (bloom == 1) alpha = (scaleDenom == 4) ? 214 : ((scaleDenom == 2) ? 232 : 245);
    else            alpha = (scaleDenom == 4) ? 240 : ((scaleDenom == 2) ? 246 : 252);
    int inv_alpha = 256 - alpha;

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

            s0 += lum * w; // Bloom: Collect all light
            
            // Halation: Only collect extreme highlights (Luma > 210) and amplify
            if (lum > 210) {
                sh += (lum - 210) * 5 * w; 
            }
        }
        // Fix: Do NOT divide by 121 here. Store the raw massive number (up to ~30k) 
        // to give the IIR blur maximum floating-point-like precision.
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
    int b_mix = (bloom == 1) ? 90 : 160; 
    int h_mix = (halation == 1) ? 120 : 200; 

    for (int x = 0; x < width; x++) {
        int v0_o = rows[10][x*3], v1_o = rows[10][x*3+1], v2_o = rows[10][x*3+2];
        int orig_y = is_yuv ? v0_o : ((v0_o*77 + v1_o*150 + v2_o*29)/256);

        // Fix: Now we divide the blurred, high-precision map back down to 0-255 visual ranges
        int blur_y = work_0[x] / 121;
        int halation_y = work_h[x] / 121;

        // Bloom Bleed: How much brighter is the glow map than the original pixel?
        int b_bleed = blur_y - orig_y;
        if (b_bleed < 0) b_bleed = 0;

        // Halation Core Protection: Fade out the red dye if the base pixel is bright white.
        int h_eff = (halation_y * h_mix) / 256;
        h_eff = (h_eff * (255 - orig_y)) / 256; 

        if (is_yuv) {
            int y_res = v0_o, cb_res = v1_o, cr_res = v2_o;

            if (bloom > 0 && b_bleed > 0) {
                int add_y = (b_bleed * b_mix) / 256;
                y_res += add_y;
                
                // Cinematic Diffusion: Pull chroma naturally toward neutral gray (128) as it blooms white
                cb_res = cb_res + ((128 - cb_res) * add_y) / 256;
                cr_res = cr_res + ((128 - cr_res) * add_y) / 256;
            }

            if (halation > 0 && h_eff > 0) {
                y_res += h_eff / 3;     // Slight brightness bump
                cr_res += h_eff;        // Massive push to pure Red
                cb_res -= h_eff / 2;    // Pull blue away to keep it warm
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
                r_res += h_eff;         // Pure Red
                g_res += h_eff / 5;     // Tiny bit of green for orange fade
                b_res -= h_eff / 5;     
            }

            out_row[x*3]   = (uint8_t)CLAMP(r_res);
            out_row[x*3+1] = (uint8_t)CLAMP(g_res);
            out_row[x*3+2] = (uint8_t)CLAMP(b_res);
        }
    }
}

// ==========================================
// PATH A: RGB + LUT + ANALOG PHYSICS
// ==========================================
inline void process_row_rgb(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, uint32_t& seed,
    int opac_mapped, const int* map,
    const uint8_t* nativeLut, int nativeLutSize, int lutMax, int lutSize2, int scaleDenom)
{
    int s_roll   = rollOff * 20;
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) / 2;
    if (grainSize == 2) s_grain = (s_grain * 5) / 4;
    
    // Fix: Prevent chunky static on PROXY/HALF EVF previews
    if (scaleDenom > 1) s_grain /= scaleDenom;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int r = row[i], g = row[i+1], b = row[i+2];

        // --- LUT CALCS ---
        int fX = map[r], fY = map[g], fZ = map[b];
        int x0 = fX / 128, y0 = fY / 128, z0 = fZ / 128;
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

        const uint8_t* p    = &nativeLut[(x0 + y0*nativeLutSize + z0*lutSize2)*3];
        const uint8_t* p1_v = &nativeLut[v1*3];
        const uint8_t* p2_v = &nativeLut[v2*3];
        const uint8_t* p3_v = &nativeLut[(x1 + y1*nativeLutSize + z1*lutSize2)*3];

        int outR = r + ((((p[0]*w0 + p1_v[0]*w1 + p2_v[0]*w2 + p3_v[0]*w3) / 128) - r) * opac_mapped / 256);
        int outG = g + ((((p[1]*w0 + p1_v[1]*w1 + p2_v[1]*w2 + p3_v[1]*w3) / 128) - g) * opac_mapped / 256);
        int outB = b + ((((p[2]*w0 + p1_v[2]*w1 + p2_v[2]*w2 + p3_v[2]*w3) / 128) - b) * opac_mapped / 256);

        // --- FILM DENSITY & PHYSICS ---
        // ZERO-RISK OPTIMIZATION: Only exact powers of 2 are bit-shifted.
        // Complex divisions (/140, /180, /11000) are left completely untouched.
        int currentY = (outR*77 + outG*150 + outB*29) >> 8; 
        int targetY  = currentY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (targetY < lift) targetY += ((lift - targetY) * (lift - targetY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0 && targetY > 200) targetY -= ((targetY - 200) * (targetY - 200) * s_roll) / 11000;

        // Keep chroma values signed for subtractive calcs, but use shift for divide
        int cb_p = (-38 * outR - 74 * outG + 112 * outB) >> 8; 
        int cr_p = (112 * outR - 94 * outG - 18 * outB) >> 8;
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

        if (targetY < 8) targetY = 8;
        if (targetY != currentY) {
            int r256 = (targetY << 8) / (currentY == 0 ? 1 : currentY); 
            outR = (outR * r256) >> 8; outG = (outG * r256) >> 8; outB = (outB * r256) >> 8;
        }

        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) >> 24); 
            if (v_m < 0) v_m = 0;
            outR = (outR * v_m) >> 8; outG = (outG * v_m) >> 8; outB = (outB * v_m) >> 8;
        }

        // --- GRAIN ---
        if (s_grain > 0) {
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;
            if (grainSize > 0) {
                uint32_t bx = (grainSize == 1) ? (x >> 1) : ((x * 21845) >> 16);
                uint32_t by = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h >> 13)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                noise = (salt * 100 + clump * 150) >> 8; 
            }
            int mask = (targetY < 128) ? targetY : 255 - targetY;
            if (targetY < 64) mask = (mask * targetY) >> 6; 
            int gv = (noise * mask * s_grain) >> 15; 
            outR += gv; outG += gv; outB += gv;
        }

        row[i] = (uint8_t)CLAMP(outR); row[i+1] = (uint8_t)CLAMP(outG); row[i+2] = (uint8_t)CLAMP(outB);
        d_sq += d_sq_step; d_sq_step += 2;
    }
}

// ==========================================
// PATH B: THE YUV EXPRESSWAY
// ==========================================
inline void process_row_yuv(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, uint32_t& seed,
    const uint8_t* rolloff_lut, int scaleDenom)
{
    int s_roll = rollOff * 20;
    int s_grain = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) / 2;
    if (grainSize == 2) s_grain = (s_grain * 5) / 4;

    // Fix: Prevent chunky static on PROXY/HALF EVF previews
    if (scaleDenom > 1) s_grain /= scaleDenom;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int oldY = row[i], outY = oldY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (outY < lift) outY += ((lift - outY) * (lift - outY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0) outY = rolloff_lut[outY];
        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) / 16777216);
            if (v_m < 0) v_m = 0;
            outY = (outY * v_m) / 256;
        }

        int cb = row[i+1] - 128, cr = row[i+2] - 128;
        
        if (oldY != outY) {
            int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
            cb = (cb * r256) / 256; cr = (cr * r256) / 256;
        }

        // --- GRAIN ---
        if (s_grain > 0) {
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;
            if (grainSize > 0) {
                uint32_t bx = (grainSize == 1) ? (x >> 1) : ((x * 21845) >> 16);
                uint32_t by = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h >> 13)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                noise = (salt * 100 + clump * 150) >> 8;
            }
            int mask = (outY < 128) ? outY : 255 - outY;
            if (outY < 64) mask = (mask * outY) >> 6;
            outY += (noise * mask * s_grain) >> 15;
        }

        row[i] = (uint8_t)CLAMP(outY); row[i+1] = (uint8_t)CLAMP(128+cb); row[i+2] = (uint8_t)CLAMP(128+cr);
        d_sq += d_sq_step; d_sq_step += 2;
    }
}

#endif
