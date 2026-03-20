#include <jni.h>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// --- ADD THIS LINE TO FIX THE ERROR ---
#define CLAMP(x) ((x) < 0 ? 0 : ((x) > 255 ? 255 : (x)))

std::vector<uint8_t> nativeLut;
int nativeLutSize = 0;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

inline uint32_t fast_rand(uint32_t* state) {
    uint32_t x = *state; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *state = x; return x;
}

long long get_time_ms() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    nativeLut.clear(); 
    nativeLutSize = 0;

    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    if (!file) { env->ReleaseStringUTFChars(path, file_path); return JNI_FALSE; }
    char line[256];
    while(fgets(line, sizeof(line), file)) {
        if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
            sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
            nativeLut.reserve(nativeLutSize * nativeLutSize * nativeLutSize * 3);
        }
        float r, g, b;
        if (sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
            nativeLut.push_back((uint8_t)(r * 255.0f)); 
            nativeLut.push_back((uint8_t)(g * 255.0f)); 
            nativeLut.push_back((uint8_t)(b * 255.0f));
        }
    }
    fclose(file); env->ReleaseStringUTFChars(path, file_path);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath, 
    jint scaleDenom, jint opacity, jint grain, jint grainSize, 
    jint vignette, jint rollOff, jint colorChrome, jint chromeBlue,
    jint shadowToe, jint subtractiveSat, jint halation, jint jpegQuality) { // 14 Args Total
    
    long long start_time = get_time_ms();
    
    const char *in_file = env->GetStringUTFChars(inPath, NULL); 
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    FILE *infile = fopen(in_file, "rb"); 
    FILE *outfile = fopen(out_file, "wb"); 
    
    if (!infile || !outfile) {
        if (infile) fclose(infile); if (outfile) fclose(outfile);
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file); 
        return JNI_FALSE;
    }

    std::vector<uint8_t> exifData;
    int targetOffset = 0;
    int finalScale = scaleDenom;

    // --- 1. EXIF & DIMENSION SNIPER ---
    unsigned char* header = (unsigned char*)malloc(1048576); 
    if (header) {
        int readLen = fread(header, 1, 1048576, infile);
        for (int i = 0; i < readLen - 8; i++) {
            if (header[i] == 0xFF && header[i+1] == 0xE1) {
                if (header[i+4] == 'E' && header[i+5] == 'x' && header[i+6] == 'i' && header[i+7] == 'f') {
                    int len = (header[i+2] << 8) | header[i+3];
                    if (i + 2 + len <= readLen) exifData.assign(header + i + 4, header + i + 2 + len);
                    break; 
                }
            }
        }
        if (scaleDenom == 4) {
            for (int i = 1000; i < readLen - 10; i++) {
                if (header[i] == 0xFF && header[i+1] == 0xD8) {
                    for (int j = i + 2; j < i + 65536 && j < readLen - 10; j++) {
                        if (header[j] == 0xFF && header[j+1] == 0xC0) {
                            int width = (header[j+7] << 8) | header[j+8];
                            if (width >= 1000 && width <= 2000) { targetOffset = i; finalScale = 1; break; }
                        }
                    }
                }
                if (finalScale == 1) break; 
            }
        }
        free(header);
    }

    // --- 2. DECOMPRESSOR SETUP ---
    bool use_rgb_path = (nativeLutSize > 0 && opacity > 0);
    struct jpeg_decompress_struct cinfo_d; 
    struct jpeg_compress_struct cinfo_c; 
    struct my_error_mgr jerr_d, jerr_c;
    
    cinfo_d.err = jpeg_std_error(&jerr_d.pub); 
    jerr_d.pub.error_exit = my_error_exit;
    jpeg_create_decompress(&cinfo_d); 

    bool decoded = false;
    if (scaleDenom == 4 && targetOffset > 0) {
        fseek(infile, targetOffset, SEEK_SET);
        if (!setjmp(jerr_d.setjmp_buffer)) {
            jpeg_stdio_src(&cinfo_d, infile);
            jpeg_read_header(&cinfo_d, TRUE); 
            cinfo_d.scale_num = 1; cinfo_d.scale_denom = 1; 
            cinfo_d.out_color_space = use_rgb_path ? JCS_RGB : JCS_YCbCr; 
            jpeg_start_decompress(&cinfo_d);
            decoded = true;
        }
    }
    if (!decoded) {
        fseek(infile, 0, SEEK_SET);
        if (setjmp(jerr_d.setjmp_buffer)) { jpeg_destroy_decompress(&cinfo_d); fclose(infile); fclose(outfile); return JNI_FALSE; }
        jpeg_stdio_src(&cinfo_d, infile);
        jpeg_read_header(&cinfo_d, TRUE); 
        cinfo_d.scale_num = 1; cinfo_d.scale_denom = (scaleDenom == 4) ? 4 : scaleDenom; 
        cinfo_d.out_color_space = use_rgb_path ? JCS_RGB : JCS_YCbCr; 
        jpeg_start_decompress(&cinfo_d);
    }

    // --- 3. COMPRESSOR SETUP ---
    cinfo_c.err = jpeg_std_error(&jerr_c.pub); 
    jerr_c.pub.error_exit = my_error_exit;
    if (setjmp(jerr_c.setjmp_buffer)) { jpeg_destroy_compress(&cinfo_c); jpeg_destroy_decompress(&cinfo_d); fclose(infile); fclose(outfile); return JNI_FALSE; }
    
    jpeg_create_compress(&cinfo_c); 
    jpeg_stdio_dest(&cinfo_c, outfile);
    cinfo_c.image_width = cinfo_d.output_width; 
    cinfo_c.image_height = cinfo_d.output_height; 
    cinfo_c.input_components = 3; 
    cinfo_c.in_color_space = use_rgb_path ? JCS_RGB : JCS_YCbCr;
    jpeg_set_defaults(&cinfo_c); 
    jpeg_set_quality(&cinfo_c, jpegQuality, TRUE); 
    jpeg_start_compress(&cinfo_c, TRUE);

    if (!exifData.empty()) jpeg_write_marker(&cinfo_c, JPEG_APP0 + 1, exifData.data(), exifData.size());

    // --- 4. ASSETS & LUT PRE-CALCS ---
    int row_stride = cinfo_d.output_width * 3;
    long long cx = cinfo_d.output_width / 2; 
    long long cy_center = cinfo_d.output_height / 2;
    long long max_dist_sq = cx*cx + cy_center*cy_center;
    long long vig_coef = ((long long)((vignette * 256) / 100) << 24) / (max_dist_sq > 0 ? max_dist_sq : 1); 
    int opac_mapped = (opacity * 256) / 100;
    
    unsigned char* row_buf = (unsigned char*)malloc(row_stride);
    JSAMPROW row_pointer[1];
    uint32_t master_seed = 98765;

    int map[256]; 
    int lutMax = nativeLutSize - 1; 
    int lutSize2 = nativeLutSize * nativeLutSize;
    if (use_rgb_path) {
        for (int i = 0; i < 256; i++) { map[i] = (i * lutMax * 128) / 255; }
    }

    uint8_t rolloff_lut[256];
    if (!use_rgb_path) {
        for (int i = 0; i < 256; i++) {
            int r_t = (i > 200) ? i - ((i - 200) * (i - 200) * rollOff) / 11000 : i;
            rolloff_lut[i] = (uint8_t)(r_t < 0 ? 0 : (r_t > 255 ? 255 : r_t));
        }
    }

    // --- 5. MAIN PROCESSING LOOP ---
    while (cinfo_d.output_scanline < cinfo_d.output_height) {
        int abs_y = cinfo_d.output_scanline;
        row_pointer[0] = row_buf;
        jpeg_read_scanlines(&cinfo_d, row_pointer, 1);

        uint32_t seed = master_seed + (abs_y * 1337); 
        int prev_noise = 0; 

        if (use_rgb_path) {
            // ==========================================
            // PATH A: RGB + LUT + ANALOG PHYSICS
            // ==========================================
            for (int x = 0; x < row_stride; x += 3) {
                int r = row_buf[x], g = row_buf[x+1], b = row_buf[x+2];
                int outR = r, outG = g, outB = b;

                // 1. LUT INTERPOLATION
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

                // --- NEW PHYSICS ---
                int currentY = (outR*77 + outG*150 + outB*29) >> 8;
                int targetY = currentY;
                if (shadowToe > 0) {
                    int lift = (shadowToe == 1) ? 35 : 55;
                    if (targetY < lift) targetY += ((lift - targetY) * (lift - targetY)) / (shadowToe == 1 ? 140 : 180);
                }
                if (rollOff > 0 && targetY > 200) targetY -= ((targetY - 200) * (targetY - 200) * rollOff) / 11000;
                
                int cb_p = ((-38 * outR - 74 * outG + 112 * outB) >> 8); 
                int cr_p = ((112 * outR - 94 * outG - 18 * outB) >> 8);
                int sat_p = (cb_p < 0 ? -cb_p : cb_p) + (cr_p < 0 ? -cr_p : cr_p);
                if (colorChrome > 0 && targetY > 60 && sat_p > 30) targetY -= (sat_p * colorChrome * targetY) >> 15;
                if (chromeBlue > 0 && cb_p > 15 && targetY > 80) targetY -= (cb_p * chromeBlue) >> 2;
                if (subtractiveSat > 0 && sat_p > 20) targetY -= (sat_p >> (6 - subtractiveSat));
                if (targetY < 10) targetY = 10;

                if (targetY != currentY) {
                    int r256 = (targetY * 256) / (currentY == 0 ? 1 : currentY);
                    outR = (outR * r256) >> 8; outG = (outG * r256) >> 8; outB = (outB * r256) >> 8;
                }

                if (vignette > 0) {
                    long long d_sq = ((long long)(x/3)-cx)*((long long)(x/3)-cx) + (long long)(abs_y-cy_center)*(abs_y-cy_center);
                    int v_m = 256 - (int)((d_sq * vig_coef) >> 24); if (v_m < 0) v_m = 0;
                    outR = (outR * v_m) >> 8; outG = (outG * v_m) >> 8; outB = (outB * v_m) >> 8;
                }
                if (grain > 0) {
                    int rn = (fast_rand(&seed) & 0xFF) - 128;
                    int n = (grainSize == 0) ? rn : (grainSize == 1) ? (rn + prev_noise) >> 1 : (rn + prev_noise * 2) / 3;
                    int m = (targetY < 128) ? targetY : 255 - targetY; if (targetY < 64) m = (m * targetY) >> 6;
                    int gv = (n * m * grain) >> 15; outR += gv; outG += gv; outB += gv; prev_noise = rn;
                }
                row_buf[x] = (uint8_t)CLAMP(outR); row_buf[x+1] = (uint8_t)CLAMP(outG); row_buf[x+2] = (uint8_t)CLAMP(outB);
            }
        } else {
            // ==========================================
            // PATH B: THE YUV EXPRESSWAY
            // ==========================================
            for (int x = 0, px = 0; x < row_stride; x += 3, ++px) {
                int oldY = row_buf[x], outY = oldY;
                if (shadowToe > 0) {
                    int lift = (shadowToe == 1) ? 35 : 55;
                    if (outY < lift) outY += ((lift - outY) * (lift - outY)) / (shadowToe == 1 ? 140 : 180);
                }
                if (rollOff > 0) outY = rolloff_lut[outY];
                if (vignette > 0) {
                    long long d_sq = ((long long)px - cx) * ((long long)px - cx) + (long long)(abs_y - cy_center) * (abs_y - cy_center);
                    int v_m = 256 - (int)((d_sq * vig_coef) >> 24); outY = (outY * (v_m < 0 ? 0 : v_m)) >> 8;
                }
                int cb = row_buf[x+1] - 128, cr = row_buf[x+2] - 128;
                int sat = (cb < 0 ? -cb : cb) + (cr < 0 ? -cr : cr);
                if (colorChrome > 0 && outY > 60 && sat > 30) outY -= (sat * colorChrome * outY) >> 15;
                if (chromeBlue > 0 && cb > 15 && outY > 80) { outY -= (cb * chromeBlue) >> 2; cr -= (cb * chromeBlue) >> 4; }
                if (subtractiveSat > 0 && sat > 20) outY -= (sat >> (6 - subtractiveSat));
                if (outY < 8) outY = 8;

                if (oldY != outY) {
                    int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
                    cb = (cb * r256) >> 8; cr = (cr * r256) >> 8;
                }
                row_buf[x+1] = (uint8_t)CLAMP(128+cb); row_buf[x+2] = (uint8_t)CLAMP(128+cr);
                if (grain > 0) {
                    int rn = (fast_rand(&seed) & 0xFF) - 128;
                    int n = (grainSize == 0) ? rn : (grainSize == 1) ? (rn + prev_noise) >> 1 : (rn + prev_noise * 2) / 3;
                    int m = (outY < 128) ? outY : 255 - outY; outY += (n * m * grain) >> 15; prev_noise = rn;
                }
                row_buf[x] = (uint8_t)CLAMP(outY);
            }
        } // <--- This is the closing bracket of Path B (the `else` block)

        // --- 6. TRUE HALATION (Symmetric Horizontal Bleed) ---
        if (halation > 0) {
            int halo_energy = 0;
            int halo_decay = 200; // ~78% retention per pixel (spreads the glow smoothly)
            int halo_max = (halation == 1) ? 35 : 75; // Intensity of the red glow

            // Pass 1: Left-to-Right Smear
            for (int x = 0; x < row_stride; x += 3) {
                int y_est = use_rgb_path ? ((row_buf[x]*77 + row_buf[x+1]*150 + row_buf[x+2]*29) >> 8) : row_buf[x];
                
                if (y_est > 235) { // We hit a bright core
                    halo_energy = halo_max; // Charge the battery
                } else if (halo_energy > 0) { // We are in the shadows next to a core
                    if (use_rgb_path) {
                        row_buf[x]   = (uint8_t)CLAMP(row_buf[x] + halo_energy); // Boost Red
                        row_buf[x+1] = (uint8_t)CLAMP(row_buf[x+1] + (halo_energy >> 2)); // Tiny Green boost (makes it fiery orange-red)
                    } else {
                        row_buf[x+2] = (uint8_t)CLAMP(row_buf[x+2] + halo_energy); // Boost Cr (Red)
                        row_buf[x+1] = (uint8_t)CLAMP(row_buf[x+1] - (halo_energy >> 2)); // Drop Cb (pulls away from magenta)
                    }
                    halo_energy = (halo_energy * halo_decay) >> 8; // Decay the energy
                }
            }

            // Pass 2: Right-to-Left Smear
            halo_energy = 0;
            for (int x = row_stride - 3; x >= 0; x -= 3) {
                int y_est = use_rgb_path ? ((row_buf[x]*77 + row_buf[x+1]*150 + row_buf[x+2]*29) >> 8) : row_buf[x];
                
                if (y_est > 235) {
                    halo_energy = halo_max;
                } else if (halo_energy > 0) {
                    if (use_rgb_path) {
                        row_buf[x]   = (uint8_t)CLAMP(row_buf[x] + halo_energy); 
                        row_buf[x+1] = (uint8_t)CLAMP(row_buf[x+1] + (halo_energy >> 2)); 
                    } else {
                        row_buf[x+2] = (uint8_t)CLAMP(row_buf[x+2] + halo_energy); 
                        row_buf[x+1] = (uint8_t)CLAMP(row_buf[x+1] - (halo_energy >> 2)); 
                    }
                    halo_energy = (halo_energy * halo_decay) >> 8;
                }
            }
        }

        jpeg_write_scanlines(&cinfo_c, row_pointer, 1);
    }
    
    free(row_buf); jpeg_finish_compress(&cinfo_c); jpeg_destroy_compress(&cinfo_c);
    jpeg_finish_decompress(&cinfo_d); jpeg_destroy_decompress(&cinfo_d);
    fclose(infile); fclose(outfile); env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file); 
    return JNI_TRUE;
}