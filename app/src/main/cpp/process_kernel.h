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
    if (grainSize == 1) s_grain = (s_grain * 3) >> 1;
    if (grainSize == 2) s_grain = (s_grain * 5) >> 2;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

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

        // --- FILM DENSITY & PHYSICS ---
        int currentY = (outR*77 + outG*150 + outB*29) >> 8;
        int targetY = currentY;
        
        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (targetY < lift) targetY += ((lift - targetY) * (lift - targetY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0 && targetY > 200) targetY -= ((targetY - 200) * (targetY - 200) * s_roll) / 11000;
        
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
        }
        
        // --- GRAIN (ORGANIC JITTERED FBM) ---
        if (s_grain > 0) {
            // Salt (Sharp high-frequency noise)
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;

            if (grainSize > 0) {
                // Clump (Low-frequency organic grouping)
                // Use a chaotic hash that includes the raw x/y to "jitter" the blocks
                uint32_t bx = (grainSize == 1) ? (x >> 1) : ((x * 21845) >> 16);
                uint32_t by = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
                
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h >> 13)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                
                // Mixing: 40% sharp salt, 60% soft clump
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
    const uint8_t* rolloff_lut) 
{
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) >> 1; 
    if (grainSize == 2) s_grain = (s_grain * 5) >> 2; 

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for(int x = 0; x < width; x++) {
        int i = x * 3;
        int oldY = row[i];
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
        }
        
        int cb = row[i+1] - 128, cr = row[i+2] - 128;
        int sat = (cb >= 0 ? cb : -cb) + (cr >= 0 ? cr : -cr);

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
        
        if (outY < 8) outY = 8;
        if (halation > 0 && outY > 245) {
            int push = (outY - 245) * (halation == 1 ? 3 : 6); 
            cr += push; cb -= (push >> 1); 
        }

        if (oldY != outY) {
            int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
            cb = (cb * r256) >> 8; cr = (cr * r256) >> 8;
        }
        
        // --- GRAIN (ORGANIC RESTORED) ---
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
            if (outY < 64) mask = (mask * outY) >> 6; // MUST BE outY
            outY += (noise * mask * s_grain) >> 15; 
        }
        
        row[i] = (uint8_t)CLAMP(outY); row[i+1] = (uint8_t)CLAMP(128+cb); row[i+2] = (uint8_t)CLAMP(128+cr);
        d_sq += d_sq_step; d_sq_step += 2;
    }
}

#endif