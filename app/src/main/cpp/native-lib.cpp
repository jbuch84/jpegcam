#include <jni.h>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <pthread.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "filmOS_Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut; 
int nativeLutSize = 0;

struct my_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

inline uint32_t fast_rand(uint32_t* state) {
    uint32_t x = *state;
    x ^= x << 13; 
    x ^= x >> 17; 
    x ^= x << 5;
    *state = x;
    return x;
}

// Structure to pass variables into our parallel threads, handling absolute Y coordinates for chunking
struct ThreadData {
    int start_y;
    int end_y;
    int absolute_y_offset;
    unsigned char* img_buffer;
    int row_stride;
    long long cx;
    long long cy_center;
    int vignette;
    long long vig_coef;
    int opacity;
    int opac_mapped;
    int grain;
    int grainSize;
    int rolloff;
    int lutMax;
    int lutSize2;
    int* map;
    int* rollMap;
};

void* process_rows(void* arg) {
    ThreadData* td = (ThreadData*)arg;
    uint32_t master_seed = 98765;

    for (int local_y = td->start_y; local_y < td->end_y; local_y++) {
        int absolute_y = td->absolute_y_offset + local_y;
        unsigned char* row = &td->img_buffer[local_y * td->row_stride];
        long long dy_sq = (absolute_y - td->cy_center) * (absolute_y - td->cy_center);
        int prev_noise = 0; 
        uint32_t seed = master_seed + (absolute_y * 1337); 

        for (int x = 0; x < td->row_stride; x += 3) {
            int origR = row[x];
            int origG = row[x+1];
            int origB = row[x+2];
            int outR = origR, outG = origG, outB = origB;

            int fX = td->map[origR], fY = td->map[origG], fZ = td->map[origB];
            int x0 = fX >> 7, y0 = fY >> 7, z0 = fZ >> 7;
            int x1 = (x0 < td->lutMax) ? x0 + 1 : td->lutMax;
            int y1 = (y0 < td->lutMax) ? y0 + 1 : td->lutMax;
            int z1 = (z0 < td->lutMax) ? z0 + 1 : td->lutMax;

            int dx = fX & 0x7F, dy_lut = fY & 0x7F, dz = fZ & 0x7F;
            int i000 = x0 + y0*nativeLutSize + z0*td->lutSize2;
            int i111 = x1 + y1*nativeLutSize + z1*td->lutSize2;
            
            int v1, v2, w0, w1, w2, w3;
            if (dx >= dy_lut) {
                if (dy_lut >= dz) { 
                    v1=x1+y0*nativeLutSize+z0*td->lutSize2; 
                    v2=x1+y1*nativeLutSize+z0*td->lutSize2; 
                    w0=128-dx; w1=dx-dy_lut; w2=dy_lut-dz; w3=dz; 
                } 
                else if (dx >= dz) { 
                    v1=x1+y0*nativeLutSize+z0*td->lutSize2; 
                    v2=x1+y0*nativeLutSize+z1*td->lutSize2; 
                    w0=128-dx; w1=dx-dz; w2=dz-dy_lut; w3=dy_lut; 
                } 
                else { 
                    v1=x0+y0*nativeLutSize+z1*td->lutSize2; 
                    v2=x1+y0*nativeLutSize+z1*td->lutSize2; 
                    w0=128-dz; w1=dz-dx; w2=dx-dy_lut; w3=dy_lut; 
                }
            } else {
                if (dz >= dy_lut) { 
                    v1=x0+y0*nativeLutSize+z1*td->lutSize2; 
                    v2=x0+y1*nativeLutSize+z1*td->lutSize2; 
                    w0=128-dz; w1=dz-dy_lut; w2=dy_lut-dx; w3=dx; 
                } 
                else if (dz >= dx) { 
                    v1=x0+y1*nativeLutSize+z0*td->lutSize2; 
                    v2=x0+y1*nativeLutSize+z1*td->lutSize2; 
                    w0=128-dy_lut; w1=dy_lut-dz; w2=dz-dx; w3=dx; 
                } 
                else { 
                    v1=x0+y1*nativeLutSize+z0*td->lutSize2; 
                    v2=x1+y1*nativeLutSize+z0*td->lutSize2; 
                    w0=128-dy_lut; w1=dy_lut-dx; w2=dx-dz; w3=dz; 
                }
            }

            const uint8_t* p0 = &nativeLut[i000*3];
            const uint8_t* p1 = &nativeLut[v1*3];
            const uint8_t* p2 = &nativeLut[v2*3];
            const uint8_t* p3 = &nativeLut[i111*3];

            int lutR = (p0[0]*w0 + p1[0]*w1 + p2[0]*w2 + p3[0]*w3) >> 7;
            int lutG = (p0[1]*w0 + p1[1]*w1 + p2[1]*w2 + p3[1]*w3) >> 7;
            int lutB = (p0[2]*w0 + p1[2]*w1 + p2[2]*w2 + p3[2]*w3) >> 7;

            outR = origR + (((lutR - origR) * td->opac_mapped) >> 8);
            outG = origG + (((lutG - origG) * td->opac_mapped) >> 8);
            outB = origB + (((lutB - origB) * td->opac_mapped) >> 8);

            if (td->rolloff > 0) { 
                outR = (outR > 255) ? 255 : td->rollMap[outR]; 
                outG = (outG > 255) ? 255 : td->rollMap[outG]; 
                outB = (outB > 255) ? 255 : td->rollMap[outB]; 
            }

            if (td->vignette > 0) {
                long long dist_sq = ((x/3)-td->cx)*((x/3)-td->cx) + dy_sq;
                int vig_mult = 256 - (int)((dist_sq * td->vig_coef) >> 24);
                if (vig_mult < 0) vig_mult = 0;
                outR = (outR * vig_mult) >> 8; 
                outG = (outG * vig_mult) >> 8; 
                outB = (outB * vig_mult) >> 8;
            }

            if (td->grain > 0) {
                int raw_noise = (fast_rand(&seed) & 0xFF) - 128; 
                int noise = (td->grainSize == 0) ? raw_noise : (td->grainSize == 1) ? (raw_noise + prev_noise) >> 1 : (raw_noise + prev_noise * 2) / 3;
                prev_noise = raw_noise;
                int lum = (outR*77 + outG*150 + outB*29) >> 8; 
                int mask = (lum < 128) ? lum : 255 - lum; 
                int grain_val = (noise * mask * td->grain) >> 15; 
                outR += grain_val; 
                outG += grain_val; 
                outB += grain_val;
            }

            row[x]   = (unsigned char)(outR < 0 ? 0 : outR > 255 ? 255 : outR);
            row[x+1] = (unsigned char)(outG < 0 ? 0 : outG > 255 ? 255 : outG);
            row[x+2] = (unsigned char)(outB < 0 ? 0 : outB > 255 ? 255 : outB);
        }
    }
    return NULL;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject /* this */, jstring path) {
    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    env->ReleaseStringUTFChars(path, file_path);
    
    if (!file) return JNI_FALSE;

    nativeLut.clear(); 
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
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject /* this */, 
    jstring inPath, jstring outPath, 
    jint scaleDenom, jint opacity, 
    jint grain, jint grainSize, 
    jint vignette, jint rolloff) {
    
    const char *in_file = env->GetStringUTFChars(inPath, NULL); 
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    
    FILE *infile = fopen(in_file, "rb"); 
    FILE *outfile = fopen(out_file, "wb");
    
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
    
    int map[256]; 
    int rollMap[256];

    cinfo_d.err = jpeg_std_error(&jerr_d.pub); 
    jerr_d.pub.error_exit = my_error_exit;
    if (setjmp(jerr_d.setjmp_buffer)) { 
        jpeg_destroy_decompress(&cinfo_d); 
        fclose(infile); 
        fclose(outfile); 
        return JNI_FALSE; 
    }
    
    jpeg_create_decompress(&cinfo_d); 
    jpeg_stdio_src(&cinfo_d, infile);
    
    for (int m = 0; m < 16; m++) {
        jpeg_save_markers(&cinfo_d, JPEG_APP0 + m, 0xFFFF);
    }
    jpeg_save_markers(&cinfo_d, JPEG_COM, 0xFFFF);
    
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
        fclose(infile); 
        fclose(outfile); 
        return JNI_FALSE; 
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

    jpeg_saved_marker_ptr marker = cinfo_d.marker_list;
    while (marker) { 
        jpeg_write_marker(&cinfo_c, marker->marker, marker->data, marker->data_length); 
        marker = marker->next; 
    }

    int lutMax = nativeLutSize - 1; 
    int lutSize2 = nativeLutSize * nativeLutSize;
    for (int i = 0; i < 256; i++) { 
        map[i] = (i * lutMax * 128) / 255; 
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
    
    // SAFE MEMORY ALLOCATION: Max 128 rows at a time
    int chunk_size = 128;
    unsigned char* img_buffer = (unsigned char*)malloc(row_stride * chunk_size);
    if (!img_buffer) { 
        jpeg_destroy_compress(&cinfo_c); 
        jpeg_destroy_decompress(&cinfo_d); 
        fclose(infile); 
        fclose(outfile); 
        return JNI_FALSE; 
    }

    JSAMPROW row_pointer[1];

    // Stream through the image in chunks
    while (cinfo_d.output_scanline < cinfo_d.output_height) {
        int lines_read = 0;
        int start_scanline = cinfo_d.output_scanline;

        // 1. Read a chunk of rows
        while (lines_read < chunk_size && cinfo_d.output_scanline < cinfo_d.output_height) {
            row_pointer[0] = &img_buffer[lines_read * row_stride];
            jpeg_read_scanlines(&cinfo_d, row_pointer, 1);
            lines_read++;
        }

        // 2. Process the chunk using multiple CPU Cores
        int num_threads = 4; 
        pthread_t threads[4];
        ThreadData thread_data[4];
        int rows_per_thread = lines_read / num_threads;

        for (int i = 0; i < num_threads; i++) {
            thread_data[i].start_y = i * rows_per_thread;
            thread_data[i].end_y = (i == num_threads - 1) ? lines_read : (i + 1) * rows_per_thread;
            thread_data[i].absolute_y_offset = start_scanline; 
            thread_data[i].img_buffer = img_buffer; 
            thread_data[i].row_stride = row_stride;
            thread_data[i].cx = cx; 
            thread_data[i].cy_center = cy_center; 
            thread_data[i].vignette = vignette; 
            thread_data[i].vig_coef = vig_coef;
            thread_data[i].opacity = opacity; 
            thread_data[i].opac_mapped = opac_mapped;
            thread_data[i].grain = grain; 
            thread_data[i].grainSize = grainSize; 
            thread_data[i].rolloff = rolloff;
            thread_data[i].lutMax = lutMax; 
            thread_data[i].lutSize2 = lutSize2; 
            thread_data[i].map = map; 
            thread_data[i].rollMap = rollMap;

            pthread_create(&threads[i], NULL, process_rows, &thread_data[i]);
        }
        
        // Wait for all threads to finish this chunk
        for (int i = 0; i < num_threads; i++) { 
            pthread_join(threads[i], NULL); 
        }

        // 3. Write the processed chunk back to the compressor
        for (int i = 0; i < lines_read; i++) {
            row_pointer[0] = &img_buffer[i * row_stride];
            jpeg_write_scanlines(&cinfo_c, row_pointer, 1);
        }
    }

    // Clean up memory safely
    free(img_buffer);
    jpeg_finish_compress(&cinfo_c); 
    jpeg_destroy_compress(&cinfo_c);
    
    jpeg_finish_decompress(&cinfo_d); 
    jpeg_destroy_decompress(&cinfo_d);
    
    fclose(infile); 
    fclose(outfile);
    
    env->ReleaseStringUTFChars(inPath, in_file); 
    env->ReleaseStringUTFChars(outPath, out_file);
    
    return JNI_TRUE;
}