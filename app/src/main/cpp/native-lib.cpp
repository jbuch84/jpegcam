#include <jni.h>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <errno.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "filmOS_Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut; 
int nativeLutSize = 0;

struct my_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    LOGE("libjpeg-turbo fatal error!");
    longjmp(myerr->setjmp_buffer, 1);
}

// YOUR ORIGINAL FAST RAND
inline uint32_t fast_rand(uint32_t* state) {
    uint32_t x = *state;
    x ^= x << 13; 
    x ^= x >> 17; 
    x ^= x << 5;
    *state = x;
    return x;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    if (!file) {
        env->ReleaseStringUTFChars(path, file_path);
        return JNI_FALSE;
    }

    nativeLut.clear(); 
    nativeLutSize = 0;
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
    fclose(file); 
    env->ReleaseStringUTFChars(path, file_path);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath, 
    jint scaleDenom, jint opacity, jint grain, jint grainSize, 
    jint vignette, jint rolloff) {
    
    const char *in_file = env->GetStringUTFChars(inPath, NULL); 
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    
    FILE *infile = fopen(in_file, "rb"); 
    FILE *outfile = fopen(out_file, "rb+"); // Open pre-created file
    
    if (!infile || !outfile) {
        if (infile) fclose(infile); 
        if (outfile) fclose(outfile);
        env->ReleaseStringUTFChars(inPath, in_file); 
        env->ReleaseStringUTFChars(outPath, out_file); 
        return JNI_FALSE;
    }

    struct jpeg_decompress_struct cinfo_d; 
    struct jpeg_compress_struct cinfo_c; 
    struct my_error_mgr jerr_d, jerr_c;
    
    // PRESERVED: YOUR MAPS
    int map[256]; 
    int rollMap[256];

    cinfo_d.err = jpeg_std_error(&jerr_d.pub); 
    jerr_d.pub.error_exit = my_error_exit;
    if (setjmp(jerr_d.setjmp_buffer)) { 
        jpeg_destroy_decompress(&cinfo_d); 
        fclose(infile); fclose(outfile); return JNI_FALSE; 
    }
    
    jpeg_create_decompress(&cinfo_d); 
    jpeg_stdio_src(&cinfo_d, infile);
    jpeg_read_header(&cinfo_d, TRUE); 
    cinfo_d.scale_num = 1; 
    cinfo_d.scale_denom = scaleDenom; 
    cinfo_d.out_color_space = JCS_RGB; 
    jpeg_start_decompress(&cinfo_d);

    cinfo_c.err = jpeg_std_error(&jerr_c.pub); 
    jerr_c.pub.error_exit = my_error_exit;
    if (setjmp(jerr_c.setjmp_buffer)) { 
        jpeg_destroy_compress(&cinfo_c); 
        jpeg_destroy_decompress(&cinfo_d); 
        fclose(infile); fclose(outfile); return JNI_FALSE; 
    }
    
    jpeg_create_compress(&cinfo_c); 
    jpeg_stdio_dest(&cinfo_c, outfile);
    cinfo_c.image_width = cinfo_d.output_width; 
    cinfo_c.image_height = cinfo_d.output_height; 
    cinfo_c.input_components = 3; 
    cinfo_c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo_c); 
    jpeg_set_quality(&cinfo_c, 95, TRUE); 
    jpeg_start_compress(&cinfo_c, TRUE);

    // PRESERVED: YOUR MAP INITIALIZATION
    int lutMax = nativeLutSize > 0 ? nativeLutSize - 1 : 0; 
    int lutSize2 = nativeLutSize * nativeLutSize;
    for (int i = 0; i < 256; i++) { 
        map[i] = lutMax > 0 ? (i * lutMax * 128) / 255 : 0; 
        if (i > 200 && rolloff > 0) {
            rollMap[i] = i - ((i - 200) * (i - 200) * rolloff) / 11000; 
        } else {
            rollMap[i] = i;
        }
    }
    
    int row_stride = cinfo_d.output_width * 3;
    long long cx = cinfo_d.output_width / 2; 
    long long cy_center = cinfo_d.output_height / 2;
    long long max_dist_sq = cx*cx + cy_center*cy_center;
    long long vig_coef = ((long long)((vignette * 256) / 100) << 24) / (max_dist_sq > 0 ? max_dist_sq : 1); 
    int opac_mapped = (opacity * 256) / 100;
    
    int chunk_size = 16; // Memory safety
    unsigned char* img_buffer = (unsigned char*)malloc(row_stride * chunk_size);
    if (!img_buffer) { return JNI_FALSE; }

    JSAMPROW row_pointer[1];
    uint32_t master_seed = 98765;

    while (cinfo_d.output_scanline < cinfo_d.output_height) {
        int lines_read = 0;
        int start_scanline = cinfo_d.output_scanline;

        while (lines_read < chunk_size && cinfo_d.output_scanline < cinfo_d.output_height) {
            row_pointer[0] = &img_buffer[lines_read * row_stride];
            jpeg_read_scanlines(&cinfo_d, row_pointer, 1);
            lines_read++;
        }

        // PRESERVED: ALL YOUR HEAVY PROCESSING LOGIC
        for (int local_y = 0; local_y < lines_read; local_y++) {
            int absolute_y = start_scanline + local_y;
            unsigned char* row = &img_buffer[local_y * row_stride];
            long long dy_sq = (long long)(absolute_y - cy_center) * (absolute_y - cy_center);
            int prev_noise = 0; 
            uint32_t seed = master_seed + (absolute_y * 1337); 

            for (int x = 0; x < row_stride; x += 3) {
                int origR = row[x], origG = row[x+1], origB = row[x+2];
                int outR = origR, outG = origG, outB = origB;

                // YOUR TETRAHEDRAL INTERPOLATION BLOCK
                if (nativeLutSize > 0) {
                    int fX = map[origR], fY = map[origG], fZ = map[origB];
                    int x0 = fX >> 7, y0 = fY >> 7, z0 = fZ >> 7;
                    int x1 = (x0 < lutMax) ? x0 + 1 : lutMax;
                    int y1 = (y0 < lutMax) ? y0 + 1 : lutMax;
                    int z1 = (z0 < lutMax) ? z0 + 1 : lutMax;
                    int dx = fX & 0x7F, dy_lut = fY & 0x7F, dz = fZ & 0x7F;
                    int i000 = x0 + y0*nativeLutSize + z0*lutSize2;
                    int i111 = x1 + y1*nativeLutSize + z1*lutSize2;
                    int v1, v2, w0, w1, w2, w3;
                    if (dx >= dy_lut) {
                        if (dy_lut >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dx; w1=dx-dy_lut; w2=dy_lut-dz; w3=dz; } 
                        else if (dx >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dx; w1=dx-dz; w2=dz-dy_lut; w3=dy_lut; } 
                        else { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dx; w2=dx-dy_lut; w3=dy_lut; }
                    } else {
                        if (dz >= dy_lut) { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dy_lut; w2=dy_lut-dx; w3=dx; } 
                        else if (dz >= dx) { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dy_lut; w1=dy_lut-dz; w2=dz-dx; w3=dx; } 
                        else { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dy_lut; w1=dy_lut-dx; w2=dx-dz; w3=dz; }
                    }
                    const uint8_t* p0 = &nativeLut[i000*3];
                    const uint8_t* p1 = &nativeLut[v1*3];
                    const uint8_t* p2 = &nativeLut[v2*3];
                    const uint8_t* p3 = &nativeLut[i111*3];
                    int lutR = (p0[0]*w0 + p1[0]*w1 + p2[0]*w2 + p3[0]*w3) >> 7;
                    int lutG = (p0[1]*w0 + p1[1]*w1 + p2[1]*w2 + p3[1]*w3) >> 7;
                    int lutB = (p0[2]*w0 + p1[2]*w1 + p2[2]*w2 + p3[2]*w3) >> 7;
                    outR = origR + (((lutR - origR) * opac_mapped) >> 8);
                    outG = origG + (((lutG - origG) * opac_mapped) >> 8);
                    outB = origB + (((lutB - origB) * opac_mapped) >> 8);
                }

                // YOUR ROLLOFF BLOCK
                if (rolloff > 0) { 
                    outR = (outR > 255) ? 255 : (outR < 0) ? 0 : rollMap[outR]; 
                    outG = (outG > 255) ? 255 : (outG < 0) ? 0 : rollMap[outG]; 
                    outB = (outB > 255) ? 255 : (outB < 0) ? 0 : rollMap[outB]; 
                }

                // YOUR VIGNETTE BLOCK
                if (vignette > 0) {
                    long long dist_sq = ((long long)(x/3)-cx)*((long long)(x/3)-cx) + dy_sq;
                    int vig_mult = 256 - (int)((dist_sq * vig_coef) >> 24);
                    if (vig_mult < 0) vig_mult = 0;
                    outR = (outR * vig_mult) >> 8; 
                    outG = (outG * vig_mult) >> 8; 
                    outB = (outB * vig_mult) >> 8;
                }

                // YOUR GRAIN BLOCK
                if (grain > 0) {
                    int raw_noise = (fast_rand(&seed) & 0xFF) - 128; 
                    int noise = (grainSize == 0) ? raw_noise : (grainSize == 1) ? (raw_noise + prev_noise) >> 1 : (raw_noise + prev_noise * 2) / 3;
                    prev_noise = raw_noise;
                    int lum = (outR*77 + outG*150 + outB*29) >> 8; 
                    int mask = (lum < 128) ? lum : 255 - lum; 
                    int grain_val = (noise * mask * grain) >> 15; 
                    outR += grain_val; outG += grain_val; outB += grain_val;
                }

                row[x] = (unsigned char)(outR < 0 ? 0 : outR > 255 ? 255 : outR);
                row[x+1] = (unsigned char)(outG < 0 ? 0 : outG > 255 ? 255 : outG);
                row[x+2] = (unsigned char)(outB < 0 ? 0 : outB > 255 ? 255 : outB);
            }
        }

        for (int i = 0; i < lines_read; i++) {
            row_pointer[0] = &img_buffer[i * row_stride];
            jpeg_write_scanlines(&cinfo_c, row_pointer, 1);
        }
    }

    free(img_buffer);
    jpeg_finish_compress(&cinfo_c); jpeg_destroy_compress(&cinfo_c);
    jpeg_finish_decompress(&cinfo_d); jpeg_destroy_decompress(&cinfo_d);
    fclose(infile); fclose(outfile);
    env->ReleaseStringUTFChars(inPath, in_file); 
    env->ReleaseStringUTFChars(outPath, out_file);
    return JNI_TRUE;
}