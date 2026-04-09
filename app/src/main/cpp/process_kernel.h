#ifndef PROCESS_KERNEL_H
#define PROCESS_KERNEL_H

#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>

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
// emulsion, 0, 2
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
    // Xorshift for high-frequency "salt" noise
    uint32_t x = *state; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *state = x; return x;
}

// ==========================================
// TRUE 2D EMULSION SIMULATION V4 (Nuclear Diagnostic)
//
// Replaces IIR with a fixed Radius-10 Box Blur.
// THICK mode blurs 100% of Luma and Chroma to prove it is running.
// ==========================================
inline void apply_emulsion_v2(
    unsigned char** rows, uint8_t* out_row, int width, bool is_yuv, int emulsion)
{
    if (emulsion == 0 || width < 1) return;

    // FIXED RADIUS 10: 21x21 window centered on rows[10]
    const int radius = 10;
    const int diameter = 21;
    const int samples = diameter * diameter;

    // MAXED WEIGHTS: Level 2 (THICK) is 100% blurred. This will look like a heavy mist.
    int mix = (emulsion == 1) ? 128 : 255;

    // HEAP ALLOCATION
    int* vsum_r = (int*)malloc(width * sizeof(int));
    int* vsum_g = (int*)malloc(width * sizeof(int));
    int* vsum_b = (int*)malloc(width * sizeof(int));

    if (!vsum_r || !vsum_g || !vsum_b) {
        if (vsum_r) free(vsum_r); if (vsum_g) free(vsum_g); if (vsum_b) free(vsum_b);
        return;
    }

    // Vertical Summation (Pass 1)
    for (int x = 0; x < width; x++) {
        int r_acc = 0, g_acc = 0, b_acc = 0;
        for (int y = 0; y <= 20; y++) {
            r_acc += (int)rows[y][x*3];
            g_acc += (int)rows[y][x*3+1];
            b_acc += (int)rows[y][x*3+2];
        }
        vsum_r[x] = r_acc; vsum_g[x] = g_acc; vsum_b[x] = b_acc;
    }

    // Horizontal Summation (Pass 2) + Output
    for (int x = 0; x < width; x++) {
        int r_hsum = 0, g_hsum = 0, b_hsum = 0;
        for (int i = -radius; i <= radius; i++) {
            int xi = x + i;
            if (xi < 0) xi = 0; else if (xi >= width) xi = width - 1;
            r_hsum += vsum_r[xi];
            g_hsum += vsum_g[xi];
            b_hsum += vsum_b[xi];
        }

        int blur_r = r_hsum / samples;
        int blur_g = g_hsum / samples;
        int blur_b = b_hsum / samples;

        int r_o = rows[10][x*3], g_o = rows[10][x*3+1], b_o = rows[10][x*3+2];

        // Mix 100% of the blur back if THICK. This will be unmistakably blurry.
        out_row[x*3]   = (uint8_t)CLAMP((r_o * (255 - mix) + blur_r * mix) / 255);
        out_row[x*3+1] = (uint8_t)CLAMP((g_o * (255 - mix) + blur_g * mix) / 255);
        out_row[x*3+2] = (uint8_t)CLAMP((b_o * (255 - mix) + blur_b * mix) / 255);
    }

    free(vsum_r); free(vsum_g); free(vsum_b);
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
    const uint8_t* nativeLut, int nativeLutSize, int lutMax, int lutSize2)
{
    int s_roll   = rollOff * 20;
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) / 2;
    if (grainSize == 2) s_grain = (s_grain * 5) / 4;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int r = row[i], g = row[i+1], b = row[i+2];

        // --- LUT CALCS (Tetrahedral Trilinear Interpolation) ---
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
        int currentY = (outR*77 + outG*150 + outB*29) / 256;
        int targetY  = currentY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (targetY < lift) targetY += ((lift - targetY) * (lift - targetY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0 && targetY > 200) targetY -= ((targetY - 200) * (targetY - 200) * s_roll) / 11000;

        int cb_p = ((-38 * outR - 74 * outG + 112 * outB) / 256);
        int cr_p = ((112 * outR - 94 * outG - 18 * outB) / 256);
        int sat_p = (cb_p < 0 ? -cb_p : cb_p) + (cr_p < 0 ? -cr_p : cr_p);

        if (s_chrome > 0 && sat_p > 15) {
            int drop = ((sat_p - 15) * s_chrome) / 256;
            if (targetY > 160) { int fade = 255 - ((targetY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) / 256; }
            if (drop > (targetY / 4)) drop = targetY / 4;
            targetY -= drop;
        }
        if (s_blue > 0 && cb_p > 5 && cr_p < 25) {
            int drop = (cb_p * s_blue) / 128;
            if (targetY > 160) { int fade = 255 - ((targetY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) / 256; }
            if (targetY < 50) { int fade = (targetY * 5); if (fade > 255) fade = 255; drop = (drop * fade) / 256; }
            if (drop > (targetY / 4)) drop = targetY / 4;
            targetY -= drop;
        }
        if (s_sat > 0 && sat_p > 20) {
            int density = ((sat_p - 20) * s_sat) / 256;
            if (targetY > 200) { int fade = 255 - ((targetY - 200) * 4); if (fade < 0) fade = 0; density = (density * fade) / 256; }
            if (density > (targetY / 4)) density = targetY / 4;
            targetY -= density;
        }

        if (targetY < 8) targetY = 8;
        if (targetY != currentY) {
            int r256 = (targetY * 256) / (currentY == 0 ? 1 : currentY);
            outR = (outR * r256) / 256; outG = (outG * r256) / 256; outB = (outB * r256) / 256;
        }

        // --- FILM HALATION ---
        // Extended range: starts at 220 (was 245) for a gradual, realistic
        // warm red bloom that creeps in from specular highlights downward.
        // The lower threshold means halation is visible on bright windows,
        // chrome, and skin highlights — not just blown-out clipping.
        if (halation > 0 && targetY > 220) {
            int hl = (targetY - 220) * (halation == 1 ? 3 : 7);
            outR += hl / 2;    // warm red lift
            outG -= hl / 32;   // imperceptible green drop
            outB -= hl / 8;    // blue rolls off (shifts toward warm)
        }

        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) / 16777216); // 2^24
            if (v_m < 0) v_m = 0;
            outR = (outR * v_m) / 256; outG = (outG * v_m) / 256; outB = (outB * v_m) / 256;
        }

        // --- GRAIN (TRUE ORGANIC FBM LUMINANCE GRAIN) ---
        if (s_grain > 0) {
            // Salt (sharp high-frequency noise)
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;

            if (grainSize > 0) {
                // Clump (low-frequency organic grouping via spatial hash)
                uint32_t bx = (grainSize == 1) ? (x / 2) : ((x * 21845) / 65536); // >> 16
                uint32_t by = (grainSize == 1) ? (abs_y / 2) : ((abs_y * 21845) / 65536);
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h / 8192)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                // Mixing: 40% sharp salt, 60% soft clump
                noise = (salt * 100 + clump * 150) / 256;
            }

            int mask = (targetY < 128) ? targetY : 255 - targetY;
            if (targetY < 64) mask = (mask * targetY) / 64;

            // Organic Density Bias:
            // Shift noise additive in shadows and subtractive in highlights
            int bias = (128 - targetY) / 2;
            int biased_noise = noise + bias;

            int gv = (biased_noise * mask * s_grain) / 32768;

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
    const uint8_t* rolloff_lut)
{
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) / 2;
    if (grainSize == 2) s_grain = (s_grain * 5) / 4;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int oldY = row[i];
        int outY = oldY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (outY < lift) outY += ((lift - outY) * (lift - outY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0) outY = rolloff_lut[outY];
        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) / 16777216); // 2^24
            if (v_m < 0) v_m = 0;
            outY = (outY * v_m) / 256;
        }

        int cb = row[i+1] - 128, cr = row[i+2] - 128;
        int sat = (cb >= 0 ? cb : -cb) + (cr >= 0 ? cr : -cr);

        if (s_chrome > 0 && sat > 15) {
            int drop = ((sat - 15) * s_chrome) / 256;
            if (outY > 160) { int fade = 255 - ((outY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) / 256; }
            if (drop > (outY / 4)) drop = outY / 4;
            outY -= drop;
        }
        if (s_blue > 0 && cb > 5 && cr < 25) {
            int drop = (cb * s_blue) / 128;
            if (outY > 160) { int fade = 255 - ((outY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) / 256; }
            if (outY < 50) { int fade = (outY * 5); if (fade > 255) fade = 255; drop = (drop * fade) / 256; }
            if (drop > (outY / 4)) drop = outY / 4;
            outY -= drop; cr -= (drop / 2);
        }
        if (s_sat > 0 && sat > 20) {
            int density = ((sat - 20) * s_sat) / 256;
            if (outY > 200) { int fade = 255 - ((outY - 200) * 4); if (fade < 0) fade = 0; density = (density * fade) / 256; }
            if (density > (outY / 4)) density = outY / 4;
            outY -= density;
        }

        if (outY < 8) outY = 8;

        // --- FILM HALATION (YUV path) ---
        // Extended range: starts at 220 (was 245), gradual warm chroma bloom.
        if (halation > 0 && outY > 220) {
            int push = (outY - 220) * (halation == 1 ? 2 : 5);
            cr += push / 2;    // shift chroma toward warm/red
            cb -= push / 8;    // slight blue rolloff in hot highlights
        }

        if (oldY != outY) {
            int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
            cb = (cb * r256) / 256; cr = (cr * r256) / 256;
        }

        // --- GRAIN (TRUE ORGANIC FBM LUMINANCE GRAIN) ---
        if (s_grain > 0) {
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;

            if (grainSize > 0) {
                uint32_t bx = (grainSize == 1) ? (x / 2) : ((x * 21845) / 65536);
                uint32_t by = (grainSize == 1) ? (abs_y / 2) : ((abs_y * 21845) / 65536);
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h / 8192)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                noise = (salt * 100 + clump * 150) / 256;
            }

            int mask = (outY < 128) ? outY : 255 - outY;
            if (outY < 64) mask = (mask * outY) / 64;

            // Organic Density Bias:
            // Shift noise additive in shadows and subtractive in highlights
            int bias = (128 - outY) / 2;
            int biased_noise = noise + bias;

            outY += (biased_noise * mask * s_grain) / 32768;
        }

        row[i] = (uint8_t)CLAMP(outY); row[i+1] = (uint8_t)CLAMP(128+cb); row[i+2] = (uint8_t)CLAMP(128+cr);
        d_sq += d_sq_step; d_sq_step += 2;
    }
}

#endif
