#ifndef PROCESS_KERNEL_H
#define PROCESS_KERNEL_H

#include <stdint.h>

#define CLAMP(x) ((x) < 0 ? 0 : ((x) > 255 ? 255 : (x)))

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
// TRUE 2D EMULSION SIMULATION (Dye Cloud Softening)
//
// Replaces 1D mathematical diffusion with a physical emulation of film.
// Uses a 3-Row Rolling Buffer to apply a 3x3 micro-blur.
//
// Scaling: Dynamic parametric weights ensure the perceptual blur radius
//          is identical across PROXY, HALF, and FULL resolutions.
// Color Space: Processed in YUV. Strong matrix applied to Chroma (dye clouds),
//              Microscopic matrix applied to Luma (silver-halide roll-off).
// ==========================================
inline void apply_emulsion(
    const uint8_t* prev_row, const uint8_t* curr_row, const uint8_t* next_row, uint8_t* out_row,
    int width, bool is_yuv, int emulsion)
{
    if (emulsion == 0 || width < 1) return;

    // Dynamic Parametric Weights based on Image Width (Scaling for PROXY/FULL)
    // At 6000px, center weight is lower (more blur). At 1500px, center weight is higher (less blur)
    // to maintain the same visual perceptual radius.
    int cw_chroma_base = (emulsion == 1) ? 64 : 32;
    int cw_luma_base   = (emulsion == 1) ? 220 : 190;

    int cw_c = 256 - (((256 - cw_chroma_base) * width) / 6000);
    if (cw_c > 256) cw_c = 256; if (cw_c < 0) cw_c = 0;
    
    int cw_l = 256 - (((256 - cw_luma_base) * width) / 6000);
    if (cw_l > 256) cw_l = 256; if (cw_l < 0) cw_l = 0;

    // Distribute remaining weight to neighbors to ensure PERFECT sum to 256
    // 4 sides get slightly more weight than 4 corners (approximate circle/gaussian)
    int rem_c = 256 - cw_c;
    int side_c = (rem_c * 5) / 32; 
    int diag_c = (rem_c * 3) / 32; 
    cw_c = 256 - (side_c * 4 + diag_c * 4); // Re-clamp center to ensure perfect normalization

    int rem_l = 256 - cw_l;
    int side_l = (rem_l * 5) / 32;
    int diag_l = (rem_l * 3) / 32;
    cw_l = 256 - (side_l * 4 + diag_l * 4);

    for (int x = 0; x < width; x++) {
        // Edge clamping for X-axis
        int xl = (x > 0) ? x - 1 : 0;
        int xr = (x < width - 1) ? x + 1 : width - 1;

        int xl3 = xl * 3; int x3 = x * 3; int xr3 = xr * 3;

        int r_tl = prev_row[xl3], g_tl = prev_row[xl3+1], b_tl = prev_row[xl3+2];
        int r_tc = prev_row[x3],  g_tc = prev_row[x3+1],  b_tc = prev_row[x3+2];
        int r_tr = prev_row[xr3], g_tr = prev_row[xr3+1], b_tr = prev_row[xr3+2];
        
        int r_cl = curr_row[xl3], g_cl = curr_row[xl3+1], b_cl = curr_row[xl3+2];
        int r_cc = curr_row[x3],  g_cc = curr_row[x3+1],  b_cc = curr_row[x3+2];
        int r_cr = curr_row[xr3], g_cr = curr_row[xr3+1], b_cr = curr_row[xr3+2];
        
        int r_bl = next_row[xl3], g_bl = next_row[xl3+1], b_bl = next_row[xl3+2];
        int r_bc = next_row[x3],  g_bc = next_row[x3+1],  b_bc = next_row[x3+2];
        int r_br = next_row[xr3], g_br = next_row[xr3+1], b_br = next_row[xr3+2];

        if (is_yuv) {
            // Already YUV: R=Y, G=Cb, B=Cr
            int y  = (r_cc * cw_l + (r_tc + r_bc + r_cl + r_cr) * side_l + (r_tl + r_tr + r_bl + r_br) * diag_l) / 256;
            int cb = (g_cc * cw_c + (g_tc + g_bc + g_cl + g_cr) * side_c + (g_tl + g_tr + g_bl + g_br) * diag_c) / 256;
            int cr = (b_cc * cw_c + (b_tc + b_bc + b_cl + b_cr) * side_c + (b_tl + b_tr + b_bl + b_br) * diag_c) / 256;
            
            out_row[x3]   = (uint8_t)CLAMP(y);
            out_row[x3+1] = (uint8_t)CLAMP(cb);
            out_row[x3+2] = (uint8_t)CLAMP(cr);
        } else {
            // RGB Path: Convert 3x3 neighborhood to YCbCr on the fly to avoid
            // blurring Luma and Chroma with the same weights.
#define CALC_YCBCR(r, g, b, y, cb, cr) \
    y  = (77 * r + 150 * g + 29 * b) / 256; \
    cb = (-43 * r - 85 * g + 128 * b) / 256; \
    cr = (128 * r - 107 * g - 21 * b) / 256

            int y_tl, cb_tl, cr_tl; CALC_YCBCR(r_tl, g_tl, b_tl, y_tl, cb_tl, cr_tl);
            int y_tc, cb_tc, cr_tc; CALC_YCBCR(r_tc, g_tc, b_tc, y_tc, cb_tc, cr_tc);
            int y_tr, cb_tr, cr_tr; CALC_YCBCR(r_tr, g_tr, b_tr, y_tr, cb_tr, cr_tr);
            
            int y_cl, cb_cl, cr_cl; CALC_YCBCR(r_cl, g_cl, b_cl, y_cl, cb_cl, cr_cl);
            int y_cc, cb_cc, cr_cc; CALC_YCBCR(r_cc, g_cc, b_cc, y_cc, cb_cc, cr_cc);
            int y_cr, cb_cr, cr_cr; CALC_YCBCR(r_cr, g_cr, b_cr, y_cr, cb_cr, cr_cr);
            
            int y_bl, cb_bl, cr_bl; CALC_YCBCR(r_bl, g_bl, b_bl, y_bl, cb_bl, cr_bl);
            int y_bc, cb_bc, cr_bc; CALC_YCBCR(r_bc, g_bc, b_bc, y_bc, cb_bc, cr_bc);
            int y_br, cb_br, cr_br; CALC_YCBCR(r_br, g_br, b_br, y_br, cb_br, cr_br);
#undef CALC_YCBCR

            int y  = (y_cc * cw_l + (y_tc + y_bc + y_cl + y_cr) * side_l + (y_tl + y_tr + y_bl + y_br) * diag_l) / 256;
            int cb = (cb_cc * cw_c + (cb_tc + cb_bc + cb_cl + cb_cr) * side_c + (cb_tl + cb_tr + cb_bl + cb_br) * diag_c) / 256;
            int cr = (cr_cc * cw_c + (cr_tc + cr_bc + cr_cl + cr_cr) * side_c + (cr_tl + cr_tr + cr_bl + cr_br) * diag_c) / 256;

            // DELTA RECONSTRUCTION to avoid rounding errors and systematic green casts
            int dy  = y  - y_cc;
            int dcb = cb - cb_cc;
            int dcr = cr - cr_cc;

            out_row[x3]   = (uint8_t)CLAMP(r_cc + dy + ((359 * dcr) / 256));
            out_row[x3+1] = (uint8_t)CLAMP(g_cc + dy - ((88 * dcb) / 256) - ((183 * dcr) / 256));
            out_row[x3+2] = (uint8_t)CLAMP(b_cc + dy + ((453 * dcb) / 256));
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
