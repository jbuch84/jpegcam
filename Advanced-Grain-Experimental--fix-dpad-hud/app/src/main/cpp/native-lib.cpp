#include <jni.h>
#include <vector>
#include <string>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include "jpeglib.h"
#include <android/log.h>
#include "process_kernel.h" // <-- YOUR NEW SHARED KERNEL
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"


#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut;
int nativeLutSize = 0;
static const int kMaxRuntimeLutSize = 96;
static const int kMaxRuntimeLutPixels = kMaxRuntimeLutSize * kMaxRuntimeLutSize * kMaxRuntimeLutSize;
static const int kHeaderScanBytes = 262144;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

long long get_time_ms() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    nativeLut.clear(); 
    nativeLut.shrink_to_fit();
    nativeLutSize = 0;

    const char *file_path = env->GetStringUTFChars(path, NULL);
    std::string path_str(file_path);
    
    // --- FIX: Safely find the actual extension using the last dot ---
    std::string ext = "";
    size_t dot_pos = path_str.find_last_of('.');
    if (dot_pos != std::string::npos) {
        ext = path_str.substr(dot_pos);
        for(size_t i = 0; i < ext.length(); i++) ext[i] = tolower(ext[i]);
    }

    // --- ROUTE A: PNG (Standard Square HaldCLUT or Horizontal Strip) ---
    if (ext == ".png") {
        int w = 0, h = 0, c = 0;
        if (!stbi_info(file_path, &w, &h, &c)) {
            LOGD("ERROR: stbi_info failed to inspect the PNG file");
        } else {
            int total_pixels = w * h;
            if (total_pixels <= 0 || total_pixels > kMaxRuntimeLutPixels) {
                LOGD("ERROR: PNG LUT exceeds safe memory budget (%d pixels > %d max). Rejecting.", total_pixels, kMaxRuntimeLutPixels);
            } else {
                int best_level = 1;
                int min_diff = total_pixels;
                for (int l = 1; l <= kMaxRuntimeLutSize; l++) {
                    int diff = (l * l * l) - total_pixels;
                    if (diff < 0) diff = -diff;
                    if (diff < min_diff) {
                        min_diff = diff;
                        best_level = l;
                    }
                }

                if (best_level > kMaxRuntimeLutSize) {
                    LOGD("ERROR: PNG LUT level %d exceeds safe runtime cap %d. Rejecting.", best_level, kMaxRuntimeLutSize);
                } else {
                    unsigned char *img_data = stbi_load(file_path, &w, &h, &c, 3);
                    if (img_data) {
                        nativeLutSize = best_level;
                        int total_bytes = nativeLutSize * nativeLutSize * nativeLutSize * 3;
                        nativeLut.resize(total_bytes);

                        int tiles_per_row = w / nativeLutSize;
                        if (tiles_per_row == 0) tiles_per_row = 1;

                        for (int b = 0; b < nativeLutSize; b++) {
                            int cell_x = b % tiles_per_row;
                            int cell_y = b / tiles_per_row;
                            for (int g = 0; g < nativeLutSize; g++) {
                                int img_y = cell_y * nativeLutSize + g;
                                for (int r = 0; r < nativeLutSize; r++) {
                                    int img_x = cell_x * nativeLutSize + r;
                                    if (img_x >= w) img_x = w - 1;
                                    if (img_y >= h) img_y = h - 1;

                                    int src_idx = (img_y * w + img_x) * 3;
                                    int dst_idx = (r + g * nativeLutSize + b * nativeLutSize * nativeLutSize) * 3;
                                    nativeLut[dst_idx]     = img_data[src_idx];
                                    nativeLut[dst_idx + 1] = img_data[src_idx + 1];
                                    nativeLut[dst_idx + 2] = img_data[src_idx + 2];
                                }
                            }
                        }
                        stbi_image_free(img_data);
                        LOGD("SUCCESS: Loaded PNG HaldCLUT size %d under memory cap", nativeLutSize);
                    } else {
                        LOGD("ERROR: stbi_load failed to read the PNG file");
                    }
                }
            }
        }
    }
    // --- ROUTE B: .CUBE (Text) ---
    else if (ext == ".cube" || ext == ".cub") {
        FILE *file = fopen(file_path, "r");
        if (file) {
            char line[256];
            size_t count = 0;
            while(fgets(line, sizeof(line), file)) {
                if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
                    sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
                    if (nativeLutSize <= 0 || nativeLutSize > kMaxRuntimeLutSize) {
                        LOGD("ERROR: .cube LUT size %d exceeds safe runtime cap %d. Rejecting.", nativeLutSize, kMaxRuntimeLutSize);
                        nativeLut.clear();
                        nativeLutSize = 0;
                        break;
                    }
                    nativeLut.resize(nativeLutSize * nativeLutSize * nativeLutSize * 3);
                    continue;
                }
                float r, g, b;
                if (nativeLutSize > 0 && sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
                    if (count + 2 < nativeLut.size()) {
                        nativeLut[count++] = (uint8_t)(r * 255.0f); 
                        nativeLut[count++] = (uint8_t)(g * 255.0f); 
                        nativeLut[count++] = (uint8_t)(b * 255.0f); // Fixed count here
                    }
                }
            }
            fclose(file);
            LOGD("SUCCESS: Loaded .cube file size %d", nativeLutSize);
        }
    }

    env->ReleaseStringUTFChars(path, file_path);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_clearLutNative(JNIEnv* env, jobject obj) {
    nativeLut.clear();
    nativeLut.shrink_to_fit();
    nativeLutSize = 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath, 
    jint scaleDenom, jint opacity, jint grain, jint grainSize, 
    jint vignette, jint rollOff, jint colorChrome, jint chromeBlue,
    jint shadowToe, jint subtractiveSat, jint halation, jint advancedGrainExperimental, jint jpegQuality) { 
    
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

    unsigned char* header = (unsigned char*)malloc(kHeaderScanBytes); 
    if (header) {
        int readLen = fread(header, 1, kHeaderScanBytes, infile);
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

    int row_stride = cinfo_d.output_width * 3;
    long long cx = cinfo_d.output_width / 2; 
    long long cy_center = cinfo_d.output_height / 2;
    long long max_dist_sq = cx*cx + cy_center*cy_center;
    long long vig_coef = get_vig_coef(vignette, max_dist_sq);
    int opac_mapped = (opacity * 256) / 100;
    const int advancedOn = advancedGrainExperimental != 0 ? 1 : 0;
    const int effectiveGrain = advancedOn ? 5 : grain;
    const int effectiveGrainSize = advancedOn ? 2 : grainSize;
    
    unsigned char* row_buf = (unsigned char*)malloc(row_stride);
    if (!row_buf) {
        jpeg_finish_compress(&cinfo_c); jpeg_destroy_compress(&cinfo_c);
        jpeg_finish_decompress(&cinfo_d); jpeg_destroy_decompress(&cinfo_d);
        fclose(infile); fclose(outfile);
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }
    JSAMPROW row_pointer[1];

    int map[256]; 
    int lutMax = nativeLutSize - 1; 
    int lutSize2 = nativeLutSize * nativeLutSize;
    if (use_rgb_path) {
        for (int i = 0; i < 256; i++) { map[i] = (i * lutMax * 128) / 255; }
    }

    uint8_t rolloff_lut[256];
    if (!use_rgb_path) {
        generate_rolloff_lut(rolloff_lut, rollOff);
    }

    // --- CONTINUOUS GRAIN SETUP ---
    uint32_t seed = (uint32_t)(start_time & 0xFFFFFFFF);
    if (seed == 0) seed = 98765; 
    int prev_noise = 0;

    // --- MAIN PROCESSING LOOP ---
    while (cinfo_d.output_scanline < cinfo_d.output_height) {
        int abs_y = cinfo_d.output_scanline;
        row_pointer[0] = row_buf;
        jpeg_read_scanlines(&cinfo_d, row_pointer, 1);

        if (use_rgb_path) {
            // ==========================================
            // PATH A: ROUTE ENTIRE ROW TO SHARED KERNEL
            // ==========================================
            process_row_rgb(
                row_buf, cinfo_d.output_width, abs_y, cx, cy_center, vig_coef,
                shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, halation, vignette,
                effectiveGrain, effectiveGrainSize, finalScale, advancedOn, seed,
                opac_mapped, map, nativeLut.data(), nativeLutSize, lutMax, lutSize2
            );
        } else {
            // ==========================================
            // PATH B: ROUTE ENTIRE ROW TO SHARED KERNEL
            // ==========================================
            process_row_yuv(
                row_buf, cinfo_d.output_width, abs_y, cx, cy_center, vig_coef,
                shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, halation, vignette,
                effectiveGrain, effectiveGrainSize, finalScale, advancedOn, seed,
                rolloff_lut
            );
        }
        jpeg_write_scanlines(&cinfo_c, row_pointer, 1);
    }
    
    free(row_buf); jpeg_finish_compress(&cinfo_c); jpeg_destroy_compress(&cinfo_c);
    jpeg_finish_decompress(&cinfo_d); jpeg_destroy_decompress(&cinfo_d);
    fclose(infile); fclose(outfile); env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file); 
    return JNI_TRUE;
}
