#ifndef PROCESS_KERNEL_H
#define PROCESS_KERNEL_H

#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>
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

inline uint32_t fast_rand(uint32_t* state) {
    uint32_t x = *state; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *state = x; return x;
}

// ==========================================
// ADVANCED GRAIN ENGINE (ATLAS & NOISE MATH)
// ==========================================
inline uint32_t hash2d(uint32_t x, uint32_t y, uint32_t seed) {
    uint32_t h = x * 0x9E3779B1u;
    h ^= y * 0x85EBCA77u;
    h ^= seed * 0xC2B2AE3Du;
    h ^= h >> 16;
    h *= 0x7FEB352Du;
    h ^= h >> 15;
    h *= 0x846CA68Bu;
    h ^= h >> 16;
    return h;
}

inline uint32_t next_rng(uint32_t& state) {
    state = state * 1664525u + 1013904223u;
    return state;
}

inline float rand_unit(uint32_t& state) {
    return float((next_rng(state) >> 8) & 0x00FFFFFFu) * (1.0f / 16777215.0f);
}

inline float gaussianish(uint32_t& state) {
    float sum = 0.0f;
    for (int i = 0; i < 6; ++i) sum += rand_unit(state);
    return (sum - 3.0f) * 1.41421356f;
}

inline int signed_hash8_xy(int x, int y, uint32_t seed) {
    return (int)(hash2d((uint32_t)x, (uint32_t)y, seed) & 255u) - 128;
}

inline int grain_amount_mask(int y);

struct BakedGrainAtlas {
    int size;
    int mask;
    bool ready;
    std::vector<int8_t> fine;
    std::vector<int8_t> medium;
    std::vector<int8_t> coarse;
    std::vector<int8_t> point;
    std::vector<int8_t> pointLarge;
    std::vector<int8_t> pointHuge;
    
    // --- RESTORED: Standard profiles needed by the engine ---
    std::vector<int8_t> deepDense;
    std::vector<int8_t> shadowDense;
    std::vector<int8_t> midOpen;
    std::vector<int8_t> brightOpen;
    std::vector<int8_t> highDense;
    
    // --- NEW: The Custom Portra Textures ---
    std::vector<int8_t> portra160;
    std::vector<int8_t> portra400;
    std::vector<int8_t> portra800;

    BakedGrainAtlas() : size(256), mask(255), ready(false) {}
};

inline void blur_field(std::vector<float>& field, int size, int passes) {
    const int count = size * size;
    std::vector<float> tmp(count, 0.0f);

    for (int pass = 0; pass < passes; ++pass) {
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                const int y0 = (y - 1 + size) % size;
                const int y1 = y;
                const int y2 = (y + 1) % size;
                const int x0 = (x - 1 + size) % size;
                const int x1 = x;
                const int x2 = (x + 1) % size;
                const int idx = y * size + x;

                const float blur =
                    (field[y0 * size + x0] + field[y0 * size + x2] +
                     field[y2 * size + x0] + field[y2 * size + x2] +
                     2.0f * (field[y0 * size + x1] + field[y1 * size + x0] + field[y1 * size + x2] + field[y2 * size + x1]) +
                     4.0f * field[y1 * size + x1]) * (1.0f / 16.0f);

                tmp[idx] = blur;
            }
        }
        field.swap(tmp);
    }
}

inline void generate_correlated_template(std::vector<int8_t>& out, int size, uint32_t seed, int blurA, int blurB, int blurC, float mixB, float mixC, int targetStd) {
    const int count = size * size;
    std::vector<float> a(count);
    std::vector<float> b(count);
    std::vector<float> c(count);

    uint32_t seedA = hash2d((uint32_t)size, (uint32_t)targetStd, seed ^ 0xA341316Cu);
    uint32_t seedB = hash2d((uint32_t)(size * 3), (uint32_t)(targetStd * 7), seed ^ 0xC8013EA4u);
    uint32_t seedC = hash2d((uint32_t)(size * 5), (uint32_t)(targetStd * 11), seed ^ 0xAD90777Du);

    for (int i = 0; i < count; ++i) {
        a[i] = gaussianish(seedA);
        b[i] = gaussianish(seedB);
        c[i] = gaussianish(seedC);
    }

    blur_field(a, size, std::max(1, blurA));
    blur_field(b, size, std::max(1, blurB));
    blur_field(c, size, std::max(1, blurC));

    double mean = 0.0;
    for (int i = 0; i < count; ++i) {
        float shaped = a[i] * (1.0f - mixB) + b[i] * mixB - c[i] * mixC;
        shaped /= (1.0f + 0.22f * std::fabs(shaped));
        a[i] = shaped;
        mean += shaped;
    }
    mean /= double(count);

    double var = 0.0;
    for (int i = 0; i < count; ++i) {
        const double d = double(a[i]) - mean;
        var += d * d;
    }
    var /= double(std::max(1, count - 1));
    const double stddev = std::sqrt(std::max(1e-9, var));
    const double scale = double(targetStd) / stddev;

    out.resize(count);
    for (int i = 0; i < count; ++i) {
        int v = (int)lround((double(a[i]) - mean) * scale);
        if (v < -127) v = -127;
        if (v > 127) v = 127;
        out[i] = (int8_t)v;
    }
}

inline void draw_soft_dot(std::vector<float>& field, int size, float fx, float fy, float radius, float amplitude) {
    int ir = (int)std::ceil(radius * 1.85f);
    int cx = (int)std::floor(fx);
    int cy = (int)std::floor(fy);
    float invR2 = 1.0f / std::max(0.25f, radius * radius);

    for (int oy = -ir; oy <= ir; ++oy) {
        int y = cy + oy;
        while (y < 0) y += size;
        while (y >= size) y -= size;
        float dy = (float(cy + oy) + 0.5f) - fy;

        for (int ox = -ir; ox <= ir; ++ox) {
            int x = cx + ox;
            while (x < 0) x += size;
            while (x >= size) x -= size;
            float dx = (float(cx + ox) + 0.5f) - fx;
            float dist2 = dx * dx + dy * dy;
            float t = 1.0f - dist2 * invR2;
            if (t <= 0.0f) continue;
            t *= t;
            field[y * size + x] += amplitude * t;
        }
    }
}

inline void generate_point_template(std::vector<int8_t>& out, int size, uint32_t seed, int dotCount, int blurPasses, int targetStd) {
    const int count = size * size;
    std::vector<float> field(count, 0.0f);
    uint32_t state = hash2d((uint32_t)(size * 9), (uint32_t)dotCount, seed ^ 0xA24BAED4u);

    for (int i = 0; i < dotCount; ++i) {
        float x = float(next_rng(state) & 0xFFFFu) * (1.0f / 65536.0f) * size;
        float y = float((next_rng(state) >> 8) & 0xFFFFu) * (1.0f / 65536.0f) * size;
        float sign = (next_rng(state) & 1u) == 0 ? -1.0f : 1.0f;
        float strength = 1.0f + rand_unit(state) * 1.8f;
        float radius = 0.9f + rand_unit(state) * (1.0f + blurPasses * 0.45f);
        draw_soft_dot(field, size, x, y, radius, sign * strength);
    }

    if (blurPasses > 0) {
        blur_field(field, size, std::max(1, blurPasses >> 1));
    }

    double mean = 0.0;
    for (int i = 0; i < count; ++i) mean += field[i];
    mean /= double(count);

    double var = 0.0;
    for (int i = 0; i < count; ++i) {
        const double d = double(field[i]) - mean;
        var += d * d;
    }
    var /= double(std::max(1, count - 1));
    const double stddev = std::sqrt(std::max(1e-9, var));
    const double scale = double(targetStd) / stddev;

    out.resize(count);
    for (int i = 0; i < count; ++i) {
        int v = (int)lround((double(field[i]) - mean) * scale);
        if (v < -127) v = -127;
        if (v > 127) v = 127;
        out[i] = (int8_t)v;
    }
}

inline void build_carrier_template(std::vector<int8_t>& out, int size, const std::vector<int8_t>& fine, const std::vector<int8_t>& medium, const std::vector<int8_t>& coarse, const std::vector<int8_t>& point, int wPoint, int wFine, int wMedium, int wCoarse, int targetStd) {
    const int count = size * size;
    std::vector<float> field(count, 0.0f);
    double mean = 0.0;
    for (int i = 0; i < count; ++i) {
        float shaped =
            point[i] * (wPoint / 256.0f) +
            fine[i] * (wFine / 256.0f) +
            medium[i] * (wMedium / 256.0f) +
            coarse[i] * (wCoarse / 256.0f);
        shaped /= (1.0f + 0.16f * std::fabs(shaped));
        field[i] = shaped;
        mean += shaped;
    }
    mean /= double(count);

    double var = 0.0;
    for (int i = 0; i < count; ++i) {
        double d = double(field[i]) - mean;
        var += d * d;
    }
    var /= double(std::max(1, count - 1));
    double stddev = std::sqrt(std::max(1e-9, var));
    double scale = double(targetStd) / stddev;

    out.resize(count);
    for (int i = 0; i < count; ++i) {
        int v = (int)lround((double(field[i]) - mean) * scale);
        if (v < -127) v = -127;
        if (v > 127) v = 127;
        out[i] = (int8_t)v;
    }
}

inline BakedGrainAtlas& baked_grain_atlas() {
    static BakedGrainAtlas t;
    if (!t.ready) {
        const int size = 256;
        t.size = size;
        t.mask = size - 1;
        generate_correlated_template(t.fine, size, 0x91E10DA5u, 1, 2, 6, 0.30f, 0.22f, 64);
        generate_correlated_template(t.medium, size, 0xC79E7B1Du, 1, 3, 7, 0.24f, 0.18f, 28);
        generate_correlated_template(t.coarse, size, 0xD1B54A35u, 2, 4, 9, 0.18f, 0.12f, 10);
        generate_point_template(t.point, size, 0xA24BAED4u, (size * size) / 22, 2, 72);
        generate_point_template(t.pointLarge, size, 0x4C7F0B29u, (size * size) / 34, 4, 104);
        generate_point_template(t.pointHuge, size, 0x19E377A1u, (size * size) / 44, 5, 118);
        build_carrier_template(t.deepDense, size, t.fine, t.medium, t.coarse, t.pointLarge, 116, 104, 20, 6, 70);
        build_carrier_template(t.shadowDense, size, t.fine, t.medium, t.coarse, t.pointHuge, 150, 70, 18, 6, 86);
        build_carrier_template(t.midOpen, size, t.fine, t.medium, t.coarse, t.pointHuge, 196, 52, 18, 6, 112);
        build_carrier_template(t.brightOpen, size, t.fine, t.medium, t.coarse, t.pointLarge, 152, 70, 18, 6, 88);
        build_carrier_template(t.highDense, size, t.fine, t.medium, t.coarse, t.point, 84, 112, 18, 6, 58);

        // ==========================================
        // GENERATE THE KODAK PORTRA SAMPLES
        // Parameters: (out, size, fine, med, coarse, point, wPoint, wFine, wMed, wCoarse, targetStd)
        // ==========================================
        
        // PORTRA 160: 90% fine T-grain, tiny bit of medium for depth. Target standard deviation: 60.
        build_carrier_template(t.portra160, size, t.fine, t.medium, t.coarse, t.point, 20, 200, 36, 0, 60);
        
        // PORTRA 400: Benchmark emulsion. Heavy medium chunks, supported by fine filler. Target Std: 85.
        build_carrier_template(t.portra400, size, t.fine, t.medium, t.coarse, t.pointLarge, 40, 80, 160, 16, 85);
        
        // PORTRA 800: Massive, coarse dye clouds organically suspended in medium/fine filler. Target Std: 110.
        build_carrier_template(t.portra800, size, t.fine, t.medium, t.coarse, t.pointHuge, 60, 30, 90, 136, 110);
        t.ready = true;
    }
    return t;
}

inline int sample_atlas_template(const std::vector<int8_t>& tpl, int sizeMask, int x, int y) {
    return tpl[((y & sizeMask) * (sizeMask + 1)) + (x & sizeMask)];
}

// --- NEW: Kills the "Cheetah Print" using Hermite Smoothstep ---
inline int sample_atlas_bilinear(const std::vector<int8_t>& tpl, int sizeMask, int x_fp8, int y_fp8) {
    int cx = (x_fp8 >> 8) & sizeMask;
    int cy = (y_fp8 >> 8) & sizeMask;
    int nx = (cx + 1) & sizeMask;
    int ny = (cy + 1) & sizeMask;
    int fx = x_fp8 & 255;
    int fy = y_fp8 & 255;

    int c00 = tpl[(cy * (sizeMask + 1)) + cx];
    int c10 = tpl[(cy * (sizeMask + 1)) + nx];
    int c01 = tpl[(ny * (sizeMask + 1)) + cx];
    int c11 = tpl[(ny * (sizeMask + 1)) + nx];

    int interp_x1 = c00 + (((c10 - c00) * fx) >> 8);
    int interp_x2 = c01 + (((c11 - c01) * fx) >> 8);
    return interp_x1 + (((interp_x2 - interp_x1) * fy) >> 8);
}

inline int grain_amount_mask(int y) {
    if (y < 16 || y > 236) return 0;
    if (y < 40) return ((y - 16) * 52) >> 3;
    if (y < 96) return 156 + ((y - 40) * 88) / 56;
    if (y < 148) return 244 - ((y - 96) * 36) / 52;
    if (y < 196) return 208 - ((y - 148) * 120) / 48;
    return 88 - ((y - 196) * 88) / 40;
}

inline int apply_density_style_grain_y(int y, int grainTerm) {
    int out = y;
    if (grainTerm > 0) {
        out -= (grainTerm * (104 + y)) >> 8;
    } else if (grainTerm < 0) {
        out += ((-grainTerm) * (260 - y)) >> 10;
    }
    return CLAMP(out);
}

inline int grain_profile_index(int densityY) {
    if (densityY < 56) return 0;
    if (densityY < 104) return 1;
    if (densityY < 156) return 2;
    if (densityY < 208) return 3;
    return 4;
}

inline int select_profile_full_res(int densityY, int x, int y, uint32_t seed, const BakedGrainAtlas& atlas) {
    const int d = CLAMP(densityY + 8);
    const int transitionWidth = 20;
    int lo = grain_profile_index(d);
    int hi = lo;
    int mix = 0;

    if (d >= 56 - transitionWidth && d < 56 + transitionWidth) {
        lo = 0; hi = 1;
        mix = ((d - (56 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
    } else if (d >= 104 - transitionWidth && d < 104 + transitionWidth) {
        lo = 1; hi = 2;
        mix = ((d - (104 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
    } else if (d >= 156 - transitionWidth && d < 156 + transitionWidth) {
        lo = 2; hi = 3;
        mix = ((d - (156 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
    } else if (d >= 208 - transitionWidth && d < 208 + transitionWidth) {
        lo = 3; hi = 4;
        mix = ((d - (208 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
    }

    if (lo == hi) return lo;

    int phase1x = int((seed >> 16) & (uint32_t)atlas.mask);
    int phase1y = int((seed >> 24) & (uint32_t)atlas.mask);
    int threshold = sample_atlas_template(atlas.fine, atlas.mask, x + phase1x, y + phase1y) + 128;
    return threshold < mix ? hi : lo;
}

inline const std::vector<int8_t>& atlas_profile_for_index(const BakedGrainAtlas& atlas, int profileIndex) {
    switch (profileIndex) {
        case 0: return atlas.deepDense;
        case 1: return atlas.shadowDense;
        case 2: return atlas.midOpen;
        case 3: return atlas.brightOpen;
        default: return atlas.highDense;
    }
}


// --- THE TRUE ISO EMULSION MIXER ---
inline int grain_profile_sample(int sx, int sy, int flatness, int amp, uint32_t seed, int grainSize) {
    if (amp <= 0) return 0;
    const BakedGrainAtlas& atlas = baked_grain_atlas();
    
    // Organic emulsion warp
    int warp = sample_atlas_template(atlas.coarse, atlas.mask, (sy >> 9), (sx >> 9)) >> 3;

    int p0x = int(seed & (uint32_t)atlas.mask) + warp;
    int p0y = int((seed >> 8) & (uint32_t)atlas.mask) - warp;

    // Pull the raw spatial frequency maps
    int s_fine = sample_atlas_template(atlas.fine, atlas.mask, sx + p0x, sy + p0y);
    int s_med = sample_atlas_template(atlas.medium, atlas.mask, sx + p0x, sy + p0y);
    int s_coarse = sample_atlas_template(atlas.coarse, atlas.mask, sx + p0x, sy + p0y);
    
    int sample = 0;
    
    // ==========================================
    // THE KODAK PORTRA ISO PROFILES
    // ==========================================
    if (grainSize == 0) {
        // PORTRA 160: Extremely fine, tight. Almost entirely high-frequency.
        sample = (s_fine * 230 + s_med * 26) >> 8; 
        
    } else if (grainSize == 1) {
        // PORTRA 400: Balanced thickness. The medium clumps become present.
        sample = (s_fine * 80 + s_med * 140 + s_coarse * 36) >> 8;
        
    } else {
        // PORTRA 800: Heavy, thick, aggressive. Dominated by coarse, low-frequency 
        // energy, but filled in with fine grain so there are NO empty spaces.
        sample = (s_fine * 30 + s_med * 90 + s_coarse * 136) >> 8;
    }

    int gate = 126 + ((flatness * 3) >> 3);
    return (sample * gate * amp + (1 << 15)) >> 16;
}

// =========================================================
// V13 TRUE CRYSTAL ENGINE (REPLACES BAKED ATLAS CAMO)
// =========================================================

// 1. FAST SHARP NOISE GENERATOR (Microscopic Crystals)
inline int halide_crystal(int x, int y, uint32_t seed) {
    uint32_t h = (uint32_t)x * 374761393U + (uint32_t)y * 668265263U + seed;
    h = (h ^ (h >> 13)) * 1274126177U;
    return (int)((h ^ (h >> 16)) & 255) - 128;
}

// 2. KODAK PORTRA STRUCTURAL CLUSTERING (No spacing, no gaps, no blur)
inline int portra_emulsion_sample(int sx, int sy, int grainSize, uint32_t seed) {
    int c1 = halide_crystal(sx, sy, seed);
    
    if (grainSize == 0) { 
        // PORTRA 160: Extremely fine independent crystals.
        return c1; 
    } 
    
    // Portra 400 & 800: Crystals physically clump together. 
    // We simulate this by checking neighbors and letting the largest crystals "bleed" over.
    int c2 = halide_crystal(sx + 1, sy, seed ^ 0x9E3779B1u);
    int c3 = halide_crystal(sx, sy + 1, seed ^ 0x85EBCA77u);
    
    if (grainSize == 1) { 
        // PORTRA 400: Small, sharp 3-crystal clusters.
        int clump = c1;
        if (c2 > clump) clump = c2;
        if (c3 > clump) clump = c3;
        
        // Re-center mean to 0 and boost contrast so it hits as hard as 160
        clump = ((clump - 60) * 170) / 100; 
        return (clump * 180 + c1 * 76) >> 8;
    }
    
    // PORTRA 800: Massive, dense 6-crystal clusters.
    int c4 = halide_crystal(sx + 1, sy + 1, seed ^ 0xC2B2AE3Du);
    int c5 = halide_crystal(sx - 1, sy + 1, seed ^ 0x7FEB352Du);
    int c6 = halide_crystal(sx + 1, sy - 1, seed ^ 0x846CA68Bu);
    
    int clump = c1;
    if (c2 > clump) clump = c2;
    if (c3 > clump) clump = c3;
    if (c4 > clump) clump = c4;
    if (c5 > clump) clump = c5;
    if (c6 > clump) clump = c6;
    
    clump = ((clump - 90) * 240) / 100;
    return (clump * 220 + c1 * 36) >> 8;
}

inline int form_grain_luma_core(int centerY, int leftY, int rightY, int x, int abs_y, int s_grain, int grainSize, int scaleDenom, uint32_t seed) {
    if (s_grain <= 0) return centerY;

    // Strict 1:1 Pixel Mapping. No stretching = NO CAMO, NO PAINT SPLATTERS.
    int sx = x * scaleDenom;
    int sy = abs_y * scaleDenom;

    int blurY = (leftY + (centerY << 1) + rightY + 2) >> 2;
    int edge = leftY > rightY ? leftY - rightY : rightY - leftY;
    int flat = 256 - std::min(160, edge * 6);
    if (flat < 96) flat = 96;
    if (flat > 255) flat = 255;

    int densityY = (centerY + blurY * 3 + 2) >> 2;
    int amountY = centerY;

    // ISO 800 physically reaches deeper into the shadows
    if (grainSize == 2) {
        int surfaceLift = std::max(0, 144 - densityY);
        surfaceLift = (surfaceLift * std::max(0, flat - 152) + 128) >> 8;
        surfaceLift -= std::min(32, edge << 1);
        if (surfaceLift < 0) surfaceLift = 0;
        if (surfaceLift > 56) surfaceLift = 56;
        amountY += surfaceLift;
        if (amountY > 255) amountY = 255;
    }

    int env = grain_amount_mask(amountY);
    if (env <= 0) return centerY;
    
    int rawAmp = (s_grain * env + 128) >> 8;
    
    // Balance the amplitude response for the different stocks
    if (grainSize == 0) rawAmp = (rawAmp * 150) >> 8;      // Portra 160
    else if (grainSize == 1) rawAmp = (rawAmp * 200) >> 8; // Portra 400
    else rawAmp = (rawAmp * 280) >> 8;                     // Portra 800

    int edgeAtten = 256 - std::min(176, edge * 4);
    if (edgeAtten < 84) edgeAtten = 84;
    int amp = (rawAmp * edgeAtten + 128) >> 8;

    const BakedGrainAtlas& atlas = baked_grain_atlas();
    
    // Organic Warp Phase
    int warp = sample_atlas_template(atlas.coarse, atlas.mask, (sy >> 9), (sx >> 9)) >> 3;
    int p0x = int(seed & (uint32_t)atlas.mask) + warp;
    int p0y = int((seed >> 8) & (uint32_t)atlas.mask) - warp;

    // --- YOUR SOLUTION: Just pull from the custom samples we built ---
    int raw_grain = 0;
    if (grainSize == 0)      raw_grain = sample_atlas_template(atlas.portra160, atlas.mask, sx + p0x, sy + p0y);
    else if (grainSize == 1) raw_grain = sample_atlas_template(atlas.portra400, atlas.mask, sx + p0x, sy + p0y);
    else                     raw_grain = sample_atlas_template(atlas.portra800, atlas.mask, sx + p0x, sy + p0y);

    int gate = 126 + ((flat * 3) >> 3);
    int grainTerm = (raw_grain * gate * amp + (1 << 15)) >> 16;

    // Soft limiter
    int limit = 64; 
    if (grainTerm > limit) {
        grainTerm = limit + ((grainTerm - limit) * 3) / 8;
    } else if (grainTerm < -limit) {
        grainTerm = -limit - ((-limit - grainTerm) * 3) / 8;
    }

    // Shadow Integration
    int darkBase = std::max(0, 156 - densityY);
    int sceneExposure = std::max(0, blurY - 48);
    int lateDarkSurfaceBoost = ((darkBase * sceneExposure + 128) >> 8) + (std::max(0, flat - 168) >> 2) - (edge << 1);
    if (lateDarkSurfaceBoost < 0) lateDarkSurfaceBoost = 0;
    if (lateDarkSurfaceBoost > 72) lateDarkSurfaceBoost = 72;
    
    if (lateDarkSurfaceBoost > 0) {
        if (grainSize == 2) lateDarkSurfaceBoost = (lateDarkSurfaceBoost * 3) >> 1; 
        grainTerm += (grainTerm * lateDarkSurfaceBoost + 128) >> 8;
    }

    int formedY = apply_density_style_grain_y(centerY, grainTerm);
    
    int softBlend = 8 + (amp >> 4) + std::max(0, flat - 160) / 32;
    if (softBlend > 20) softBlend = 20;
    formedY = ((formedY * (256 - softBlend)) + (centerY * softBlend) + 128) >> 8;

    int residual = (grainTerm * 7) >> 4;
    if (residual < -24) residual = -24;
    if (residual > 24) residual = 24;
    if (lateDarkSurfaceBoost > 0) {
        int darkResidual = (grainTerm * lateDarkSurfaceBoost) >> 8;
        if (darkResidual < -14) darkResidual = -14;
        if (darkResidual > 14) darkResidual = 14;
        residual += darkResidual;
    }

    return CLAMP(formedY + residual);
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

// Helper for smooth legacy noise
inline int hash_coord(int cx, int cy, uint32_t seed) {
    uint32_t h = ((uint32_t)cx * 1274126177U) ^ ((uint32_t)cy * 2654435761U) ^ seed;
    h = (h ^ (h >> 13)) * 374761393U;
    return (int)(h & 0xFF) - 128;
}

inline int legacy_grain_noise(int x, int abs_y, int grainSize, uint32_t& seed) {
    uint32_t salt_raw = fast_rand(&seed);
    int salt = (int)(salt_raw & 0xFF) - 128;

    if (grainSize > 0) {
        int scale = (grainSize == 1) ? 2 : 4; 
        int cx = x / scale; int cy = abs_y / scale;
        
        // Map fractions back to a 0-255 scale for the Smoothstep math
        int fx_raw = ((x % scale) * 256) / scale;
        int fy_raw = ((abs_y % scale) * 256) / scale;

        // Smoothstep to kill grid artifacts
        int fx = (fx_raw * fx_raw * (768 - (fx_raw << 1))) >> 16;
        int fy = (fy_raw * fy_raw * (768 - (fy_raw << 1))) >> 16;

        int c00 = hash_coord(cx, cy, seed);
        int c10 = hash_coord(cx + 1, cy, seed);
        int c01 = hash_coord(cx, cy + 1, seed);
        int c11 = hash_coord(cx + 1, cy + 1, seed);

        int nx0 = c00 + (((c10 - c00) * fx) >> 8);
        int nx1 = c01 + (((c11 - c01) * fx) >> 8);
        int clump = nx0 + (((nx1 - nx0) * fy) >> 8);
        
        // --- THE REVERSE SLIDER FIX ---
        // Interpolation halves the contrast. We multiply by 1.5x to 2x 
        // to restore the deep, heavy density of the larger clumps.
        clump = (clump * ((grainSize == 1) ? 190 : 250)) >> 7; 
        
        // Blend clumps with a tiny bit of salt for sharp texture
        return (salt * 40 + clump * 216) >> 8;
    }

    return salt;
}

// ==========================================
// OPTICAL BLOOM & TRUE HALATION ENGINE V5 (PHYSICALLY BASED)
// ==========================================
inline void apply_bloom_halation(
    unsigned char** rows, uint8_t* out_row, int width, int abs_y, bool is_yuv, int bloom, int halation, uint32_t seed,
    int* work_0, int* work_1, int* work_2, int* work_h, int* h_line, int scaleDenom)
{
    // 1. Resolution-Aware Alphas & Intensities
    int alpha, b_mix;
    
    if (bloom == 1) {        // Local 1/8 (Tight radius, extremely subtle mix)
        alpha = (scaleDenom == 4) ? 180 : ((scaleDenom == 2) ? 210 : 230);
        b_mix = 45;
    } else if (bloom == 2) { // Full 1/8 (Wide radius, extremely subtle mix)
        alpha = (scaleDenom == 4) ? 230 : ((scaleDenom == 2) ? 245 : 252);
        b_mix = 45;
    } else if (bloom == 3) { // Local 1/4 (Tight radius, subtle mix)
        alpha = (scaleDenom == 4) ? 180 : ((scaleDenom == 2) ? 210 : 230);
        b_mix = 90;
    } else if (bloom == 4) { // Full 1/4 (Wide radius, subtle mix)
        alpha = (scaleDenom == 4) ? 230 : ((scaleDenom == 2) ? 245 : 252);
        b_mix = 90;
    } else if (bloom == 5) { // Local 1/2 (Tight radius, heavy mix)
        alpha = (scaleDenom == 4) ? 180 : ((scaleDenom == 2) ? 210 : 230);
        b_mix = 160;
    } else if (bloom == 6) { // Full 1/2 (Wide radius, heavy mix)
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

// ==========================================
// PATH A: RGB + LUT + ANALOG PHYSICS
// ==========================================
inline void process_row_rgb(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, int scaleDenom, int advancedGrainExperimental, uint32_t& seed,
    int opac_mapped, const int* map,
    const uint8_t* nativeLut, int nativeLutSize, int lutMax, int lutSize2,
    const uint8_t* externalGrainTexture = NULL) // <-- CHANGED from nullptr
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

        // CHANGE: Isolate Engine 1 (True Crystal Engine)
        if (advancedGrainExperimental == 1 && s_grain > 0) {
            int formedY = form_grain_luma_core(currRawY, prevRawY, nextRawY, x, abs_y, s_grain, grainSize, scaleDenom, seed);
            if (formedY != currRawY) {
                // --- PORTRA T-GRAIN DYE COUPLING (V7) ---
                int luma_diff = formedY - currRawY;

                int shift_r = luma_diff - ((luma_diff * 35) >> 8); 
                int shift_g = luma_diff - ((luma_diff * 10) >> 8); 
                int shift_b = luma_diff + ((luma_diff * 45) >> 8); 

                int scale_r = ((currRawY + shift_r) * 256) / (currRawY > 0 ? currRawY : 1);
                int scale_g = ((currRawY + shift_g) * 256) / (currRawY > 0 ? currRawY : 1);
                int scale_b = ((currRawY + shift_b) * 256) / (currRawY > 0 ? currRawY : 1);

                r = CLAMP((r * scale_r) >> 8);
                g = CLAMP((g * scale_g) >> 8);
                b = CLAMP((b * scale_b) >> 8);
            }
        }

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

        // NEW: Engine 2 (Texture Overlay)
        if (advancedGrainExperimental == 2 && externalGrainTexture != NULL && grain > 0) {
            // --- NEW: LUMINANCE MASKING ---
            // Grab the optical density mask from Engine 1 to protect shadows and highlights
            int env = grain_amount_mask(targetY);
            
            // Optimization: If the mask is 0 (pure black or pure white), skip the heavy math!
            if (env > 0) {
                int tr, tg, tb;

                if (scaleDenom == 1) {
                    // --- FAST PATH (FULL RES): 1:1 Pixel Mapping ---
                    int tx = x & 1023;
                    int ty = abs_y & 1023;
                    int tex_idx = (ty * 1024 + tx) * 3;
                    tr = externalGrainTexture[tex_idx];
                    tg = externalGrainTexture[tex_idx + 1];
                    tb = externalGrainTexture[tex_idx + 2];
                } else {
                    // --- HQ PATH (HALF/PROXY): Bilinear Interpolation ---
                    int gRGB[3];
                    sample_tex_bilinear_1024(externalGrainTexture, (x * scaleDenom) << 8, (abs_y * scaleDenom) << 8, gRGB);
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
            
        } else if (advancedGrainExperimental == 0 && s_grain > 0) {
            int noise = legacy_grain_noise(x, abs_y, grainSize, seed);
            
            // --- THE CLASSIC BELL CURVE (Clean Shadows & Highlights) ---
            // High-precision mask: 0 at black, peaks at 128, 0 at white.
            int mask = (targetY < 128) ? targetY : (255 - targetY);
            
            // Calculate balanced grain value
            int gv = (noise * mask * s_grain) >> 15;
            
            // --- BALANCED DENSITY MULTIPLIER ---
            // Symmetrical but soft impact
            gv = (gv * 220) >> 8; 

            outR = CLAMP(outR + gv); 
            outG = CLAMP(outG + gv); 
            outB = CLAMP(outB + gv);
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
    int grain, int grainSize, int scaleDenom, int advancedGrainExperimental, uint32_t& seed,
    const uint8_t* rolloff_lut,
    const uint8_t* externalGrainTexture = NULL) // <-- CHANGED from nullptr
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
        
        // CHANGE: Isolate Engine 1 (True Crystal Engine)
        int outY = (advancedGrainExperimental == 1 && s_grain > 0)
                ? form_grain_luma_core(oldY, prevInputY, nextInputY, x, abs_y, s_grain, grainSize, scaleDenom, seed)
                : oldY;

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

        // NEW: Engine 2 (Texture Overlay)
        if (advancedGrainExperimental == 2 && externalGrainTexture != NULL && grain > 0) {
            // --- NEW: LUMINANCE MASKING ---
            int env = grain_amount_mask(outY);

            if (env > 0) {
                int tr, tg, tb;

                if (scaleDenom == 1) {
                    // --- FAST PATH (FULL RES): 1:1 Pixel Mapping ---
                    int tx = x & 1023;
                    int ty = abs_y & 1023;
                    int tex_idx = (ty * 1024 + tx) * 3;
                    tr = externalGrainTexture[tex_idx];
                    tg = externalGrainTexture[tex_idx + 1];
                    tb = externalGrainTexture[tex_idx + 2];
                } else {
                    // --- HQ PATH (HALF/PROXY): Bilinear Interpolation ---
                    int gRGB[3];
                    sample_tex_bilinear_1024(externalGrainTexture, (x * scaleDenom) << 8, (abs_y * scaleDenom) << 8, gRGB);
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
            
        } else if (advancedGrainExperimental == 0 && s_grain > 0) {
            int noise = legacy_grain_noise(x, abs_y, grainSize, seed);
            
            // --- THE CLASSIC BELL CURVE ---
            // Use outY for the mask in the YUV path
            int mask = (outY < 128) ? outY : (255 - outY);
            
            // Calculate balanced grain value
            int gv = (noise * mask * s_grain) >> 15;
            
            // Balanced Softening
            gv = (gv * 220) >> 8;
            
            // Apply to Y only and CLAMP
            outY = CLAMP(outY + gv);
        }

        row[i] = (uint8_t)CLAMP(outY); row[i+1] = (uint8_t)CLAMP(128+cb); row[i+2] = (uint8_t)CLAMP(128+cr);

        prevInputY = currInputY;
        currInputY = nextInputY;
        nextInputY = (x + 2 < width) ? row[(x + 2) * 3] : currInputY;
    }
}

#endif