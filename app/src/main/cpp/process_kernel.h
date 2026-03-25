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

// --- SHARED HELPERS (The Truth) ---

// Tuned: Level 5 = 60% darkening (Stops the "mask" look)
inline long long get_vig_coef(int vignette, long long max_dist_sq) {
    int s_vig = vignette * 12; 
    return ((long long)((s_vig * 256) / 100) << 24) / (max_dist_sq > 0 ? max_dist_sq : 1);
}

// Tuned: Level 5 = 100% Strength
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
// PATH A: RGB + LUT + ANALOG PHYSICS 
// ==========================================
inline void process_pixel_rgb(
    uint8_t& r_ref, uint8_t& g_ref, uint8_t& b_ref,
    int px, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue, 
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, uint32_t& seed, int& prev_noise,
    int opac_mapped, const int* map, 
    const uint8_t* nativeLut, int nativeLutSize, int lutMax, int lutSize2) 
{
    // INTERNAL SCALING
    int s_roll   = rollOff * 20;
    int s_chrome = colorChrome * 40; 
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    
    // VARIANCE COMPENSATION: Averaging random noise reduces its visual intensity.
    // We dynamically boost the gain to keep the visual intensity perfectly flat across sizes.
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) >> 1; // Boost by 1.5x (Counteracts 30% loss)
    if (grainSize == 2) s_grain = (s_grain * 5) >> 2; // Boost by 1.25x (Counteracts 20% loss)

    int r = r_ref, g = g_ref, b = b_ref;
    int outR = r, outG = g, outB = b;

    // LUT Logic
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
    const uint8_t* p0 = &nativeLut[(x0 + y0*nativeLutSize + z0*lutSize2)*3];
    const uint8_t* p1 = &nativeLut[v1*3];
    const uint8_t* p2 = &nativeLut[v2*3];
    const uint8_t* p3 = &nativeLut[(x1 + y1*nativeLutSize + z1*lutSize2)*3];
    int lR = (p0[0]*w0 + p1[0]*w1 + p2[0]*w2 + p3[0]*w3) >> 7;
    int lG = (p0[1]*w0 + p1[1]*w1 + p2[1]*w2 + p3[1]*w3) >> 7;
    int lB = (p0[2]*w0 + p1[2]*w1 + p2[2]*w2 + p3[2]*w3) >> 7;
    outR = r + (((lR - r) * opac_mapped) >> 8);
    outG = g + (((lG - g) * opac_mapped) >> 8);
    outB = b + (((lB - b) * opac_mapped) >> 8);

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
    
    // --- FIX: Strict Clipping Halation ---
    // Ignores clouds entirely. ONLY applies to extremely bright/clipped pixels (>245 luma)
    if (halation > 0 && targetY > 245) {
        int push = (targetY - 245) * (halation == 1 ? 3 : 6); 
        outR += push;         // Push red (safely clamps at 255)
        outG -= (push >> 2);  // Gentle pull on green
        outB -= (push >> 1);  // Stronger pull on blue for authentic warmth
    }

    if (vignette > 0) {
        long long d_sq = ((long long)px-cx)*((long long)px-cx) + (long long)(abs_y-cy_center)*(abs_y-cy_center);
        int v_m = 256 - (int)((d_sq * vig_coef) >> 24); 
        if (v_m < 0) v_m = 0;
        outR = (outR * v_m) >> 8; outG = (outG * v_m) >> 8; outB = (outB * v_m) >> 8;
    }
    
    if (s_grain > 0) {
        int raw_noise = (fast_rand(&seed) & 0xFF) - 128;
        int noise;
        
        if (grainSize == 0) {
            noise = raw_noise;
        } else {
            // FRACTAL BROWNIAN MOTION (FBM) APPROXIMATION
            // Generates larger background clumps using the avalanche hash
            uint32_t block_x = (grainSize == 1) ? (px >> 1) : ((px * 21845) >> 16);
            uint32_t block_y = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
            
            uint32_t h = seed + block_x * 1274126177U + block_y * 2654435761U;
            h = (h ^ (h >> 13)) * 374761393U;
            int block_noise = (h & 0xFF) - 128;
            
            // Mix fine static over the big clumps to soften them organically
            if (grainSize == 1) {
                noise = (raw_noise + block_noise) >> 1; // 50% fine, 50% big
            } else {
                noise = (raw_noise + block_noise * 3) >> 2; // 25% fine, 75% big
            }
        }
        
        int mask = (targetY < 128) ? targetY : 255 - targetY; 
        if (targetY < 64) mask = (mask * targetY) >> 6; 
        
        int gv = (noise * mask * s_grain) >> 15; 
        outR += gv; outG += gv; outB += gv; 
    }
    
    r_ref = (uint8_t)CLAMP(outR); g_ref = (uint8_t)CLAMP(outG); b_ref = (uint8_t)CLAMP(outB);
}

// ==========================================
// PATH B: THE YUV EXPRESSWAY
// ==========================================
inline void process_pixel_yuv(
    uint8_t& y_ref, uint8_t& cb_ref, uint8_t& cr_ref,
    int px, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue, 
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, uint32_t& seed, int& prev_noise,
    const uint8_t* rolloff_lut) 
{
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    
    // VARIANCE COMPENSATION: Averaging random noise reduces its visual intensity.
    // We dynamically boost the gain to keep the visual intensity perfectly flat across sizes.
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) >> 1; // Boost by 1.5x (Counteracts 30% loss)
    if (grainSize == 2) s_grain = (s_grain * 5) >> 2; // Boost by 1.25x (Counteracts 20% loss)

    int oldY = y_ref;
    int outY = oldY;
    
    if (shadowToe > 0) {
        int lift = (shadowToe == 1) ? 35 : 55;
        if (outY < lift) outY += ((lift - outY) * (lift - outY)) / (shadowToe == 1 ? 140 : 180);
    }
    if (rollOff > 0) outY = rolloff_lut[outY];
    if (vignette > 0) {
        long long d_sq = ((long long)px - cx) * ((long long)px - cx) + (long long)(abs_y - cy_center) * (abs_y - cy_center);
        int v_m = 256 - (int)((d_sq * vig_coef) >> 24); 
        if (v_m < 0) v_m = 0;
        outY = (outY * v_m) >> 8;
    }
    
    int cb = cb_ref - 128, cr = cr_ref - 128;
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
    
    // --- FIX: Strict Clipping Halation ---
    if (halation > 0 && outY > 245) {
        int push = (outY - 245) * (halation == 1 ? 3 : 6); 
        cr += push;        
        cb -= (push >> 1); 
    }

    if (oldY != outY) {
        int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
        cb = (cb * r256) >> 8; cr = (cr * r256) >> 8;
    }
    cb_ref = (uint8_t)CLAMP(128+cb); cr_ref = (uint8_t)CLAMP(128+cr);
    
    if (s_grain > 0) { 
        int raw_noise = (fast_rand(&seed) & 0xFF) - 128;
        int noise;
        
        if (grainSize == 0) {
            noise = raw_noise;
        } else {
            // FRACTAL BROWNIAN MOTION (FBM) APPROXIMATION
            // Generates larger background clumps using the avalanche hash
            uint32_t block_x = (grainSize == 1) ? (px >> 1) : ((px * 21845) >> 16);
            uint32_t block_y = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
            
            uint32_t h = seed + block_x * 1274126177U + block_y * 2654435761U;
            h = (h ^ (h >> 13)) * 374761393U;
            int block_noise = (h & 0xFF) - 128;
            
            // Mix fine static over the big clumps to soften them organically
            if (grainSize == 1) {
                noise = (raw_noise + block_noise) >> 1; // 50% fine, 50% big
            } else {
                noise = (raw_noise + block_noise * 3) >> 2; // 25% fine, 75% big
            }
        }
        
        int mask = (outY < 128) ? outY : 255 - outY; 
        if (outY < 64) mask = (mask * outY) >> 6;
        
        outY += (noise * mask * s_grain) >> 15; 
    }
    
    y_ref = (uint8_t)CLAMP(outY);
}

#endif