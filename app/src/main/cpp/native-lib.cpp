#include <jni.h>
#include <vector>
#include <string>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include <pthread.h>
#include "jpeglib.h"
#include <android/log.h>
#include "process_kernel.h"
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut;
int nativeLutSize = 0;
std::vector<uint8_t> nativeGrainTexture;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };
METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

long long get_time_ms() { struct timeval tv; gettimeofday(&tv, NULL); return (long long)tv.tv_sec*1000 + tv.tv_usec/1000; }

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    nativeLut.clear(); nativeLutSize = 0; const char *fp = env->GetStringUTFChars(path, NULL); std::string ps(fp); std::string ex = ""; size_t dp = ps.find_last_of('.');
    if (dp != std::string::npos) { ex = ps.substr(dp); for(size_t i=0; i<ex.length(); i++) ex[i]=tolower(ex[i]); }
    if (ex == ".png") {
        int w, h, c; unsigned char *id = stbi_load(fp, &w, &h, &c, 3);
        if (id) {
            if (w*h <= 4000000) {
                int bl=1, md=w*h; for(int l=1; l<=150; l++){ int diff = abs((l*l*l)-(w*h)); if (diff < md) { md = diff; bl = l; } }
                nativeLutSize=bl; nativeLut.resize(bl*bl*bl*3); int tr = w / bl; if (tr == 0) tr = 1;
                for(int b=0; b<bl; b++){ int cx=b%tr, cy=b/tr; for(int g=0; g<bl; g++){ int iy=cy*bl+g; for(int r=0; r<bl; r++){ int ix=cx*bl+r; if(ix>=w)ix=w-1; if(iy>=h)iy=h-1; int s=(iy*w+ix)*3, d=(r+g*bl+b*bl*bl)*3; nativeLut[d]=id[s]; nativeLut[d+1]=id[s+1]; nativeLut[d+2]=id[s+2]; } } }
            }
            stbi_image_free(id);
        }
    } else if (ex==".cube"||ex==".cub") {
        FILE *f = fopen(fp, "r"); if(f){ char l[256]; size_t c=0; while(fgets(l, 256, f)){ if(strncmp(l,"LUT_3D_SIZE",11)==0){ sscanf(l,"LUT_3D_SIZE %d",&nativeLutSize); nativeLut.resize(nativeLutSize*nativeLutSize*nativeLutSize*3); continue; } float r,g,b; if(nativeLutSize>0 && sscanf(l,"%f %f %f",&r,&g,&b)==3){ if(c+2<nativeLut.size()){ nativeLut[c++]=(uint8_t)(r*255); nativeLut[c++]=(uint8_t)(g*255); nativeLut[c++]=(uint8_t)(b*255); } } } fclose(f); }
    }
    env->ReleaseStringUTFChars(path, fp); return nativeLutSize>0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadGrainTextureNative(JNIEnv* env, jobject obj, jstring path) {
    nativeGrainTexture.clear(); if(!path) return JNI_FALSE; const char *fp=env->GetStringUTFChars(path, NULL); int w,h,c; unsigned char *id=stbi_load(fp,&w,&h,&c,3); env->ReleaseStringUTFChars(path,fp);
    if(id){ if((w==512||w==1024)&&w==h){ nativeGrainTexture.assign(id, id+(w*h*3)); stbi_image_free(id); return JNI_TRUE; } stbi_image_free(id); } return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath,
    jint scaleDenom, jint opacity, jint grain, jint grainSize,
    jint vignette, jint rollOff, jint colorChrome, jint chromeBlue,
    jint shadowToe, jint subtractiveSat, jint halation,
    jint bloom, jint jpegQuality, jboolean applyCrop, jint numCores) {

    long long st = get_time_ms(); const char *ifn = env->GetStringUTFChars(inPath, NULL); const char *ofn = env->GetStringUTFChars(outPath, NULL);
    FILE *inf = fopen(ifn, "rb"), *ouf = fopen(ofn, "wb");
    if(!inf||!ouf){ if(inf)fclose(inf); if(ouf)fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }

    struct jpeg_decompress_struct cd; struct my_error_mgr jd; cd.err = jpeg_std_error(&jd.pub); jd.pub.error_exit = my_error_exit;
    if(setjmp(jd.setjmp_buffer)){ jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }
    jpeg_create_decompress(&cd); jpeg_stdio_src(&cd, inf); 
    
    // --- PRESERVE ALL SONY MARKERS ---
    for (int i = 0; i < 16; i++) jpeg_save_markers(&cd, JPEG_APP0 + i, 0xFFFF);
    jpeg_save_markers(&cd, JPEG_COM, 0xFFFF);

    jpeg_read_header(&cd, TRUE);
    cd.scale_denom = scaleDenom; cd.out_color_space = JCS_RGB; jpeg_start_decompress(&cd);

    struct jpeg_compress_struct cc; struct my_error_mgr jc; cc.err = jpeg_std_error(&jc.pub); jc.pub.error_exit = my_error_exit;
    if(setjmp(jc.setjmp_buffer)){ jpeg_destroy_compress(&cc); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }
    jpeg_create_compress(&cc); jpeg_stdio_dest(&cc, ouf);
    
    int fh = cd.output_height, sk = 0; if(applyCrop){ fh=(int)(cd.output_width/2.71f); sk=(cd.output_height-fh)/2; }
    cc.image_width = cd.output_width; cc.image_height = fh; cc.input_components = 3; cc.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cc); jpeg_set_quality(&cc, jpegQuality, TRUE); 
    
    // --- COPY ALL MARKERS BACK (Fixes Review Error) ---
    jpeg_start_compress(&cc, TRUE);
    jpeg_saved_marker_ptr mark = cd.marker_list;
    while (mark) {
        jpeg_write_marker(&cc, mark->marker, mark->data, mark->data_length);
        mark = mark->next;
    }

    int rs = cd.output_width*3;
    
    // Extremely cache-friendly chunk size for single-core execution on older L2 caches
    int CHK = 64; 
    int BUF = CHK + 20;

    unsigned char* rb = (unsigned char*)malloc(BUF*rs);
    unsigned char* ob = (unsigned char*)malloc(CHK*rs);
    if (!rb || !ob) {
        if (rb) free(rb);
        if (ob) free(ob);
        jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc);
        jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd);
        fclose(inf); fclose(ouf);
        env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn);
        return JNI_FALSE;
    }
    unsigned char* r[256];
    unsigned char* orw[256];
    for(int i=0; i<BUF; i++) r[i]=rb+(i*rs);
    for(int i=0; i<CHK; i++) orw[i]=ob+(i*rs);

    int map[256]; for(int i=0; i<256; i++) map[i]=(i*(nativeLutSize-1)*128)/255;
    uint8_t roll[256]; generate_rolloff_lut(roll, rollOff);

    int inv_y[256];
    for (int i = 0; i < 256; i++) inv_y[i] = 65536 / (i == 0 ? 1 : i);

    int ws_s = cd.output_width * sizeof(int);
    int* work_0 = NULL; int* work_1 = NULL; int* work_2 = NULL; int* work_h = NULL; int* h_line = NULL;
    if (bloom > 0 || halation > 0) {
        work_0 = (int*)malloc(ws_s); work_1 = (int*)malloc(ws_s); work_2 = (int*)malloc(ws_s);
        work_h = (int*)malloc(ws_s); h_line = (int*)malloc(ws_s);
        if (!work_0 || !work_1 || !work_2 || !work_h || !h_line) {
            free(rb); free(ob);
            if(work_0) free(work_0); if(work_1) free(work_1); if(work_2) free(work_2);
            if(work_h) free(work_h); if(h_line) free(h_line);
            jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc);
            jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd);
            fclose(inf); fclose(ouf);
            env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn);
            return JNI_FALSE;
        }
    }

    const uint8_t* tex = nativeGrainTexture.empty() ? NULL : nativeGrainTexture.data();
    bool is1k = nativeGrainTexture.size() > 1000000;
    JSAMPROW rpx[1];
    if(cd.output_height>0){ rpx[0]=r[10]; jpeg_read_scanlines(&cd,rpx,1); for(int i=0; i<10; i++) memcpy(r[i],r[10],rs); }
    for(int i=11; i<BUF; i++){ if(cd.output_scanline < cd.output_height){ rpx[0]=r[i]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[i],r[i-1],rs); }

    int opac_m = (opacity * 256) / 100;
    long long cx = cd.output_width / 2;
    long long cy_center = cd.output_height / 2;
    long long vig_coef = get_vig_coef(vignette, cx * cx + cy_center * cy_center);
    
    // Seed grain randomization with a fixed but distinct value per image
    long long current_time = get_time_ms();
    int tx_base = (int)(current_time % 1021);
    int ty_base = (int)((current_time / 13) % 1021);

    int pr = 0; while(pr < (int)cd.output_height){
        int rtp = std::min(CHK, (int)cd.output_height-pr);
        for (int i = 0; i < rtp; i++) {
            int ay = pr + i;
            if (!applyCrop || (ay >= sk && ay < sk + fh)) {
                unsigned char* win[21];
                for (int w = 0; w < 21; w++) win[w] = r[i + w];
                memcpy(orw[i], win[10], cd.output_width * 3);
                
                if (bloom > 0 || halation > 0) {
                    apply_bloom_halation(win, orw[i], cd.output_width, ay, false, bloom, halation,
                        work_0, work_1, work_2, work_h, h_line, scaleDenom);
                }
                
                process_row_rgb(orw[i], cd.output_width, ay, cx, cy_center, vig_coef,
                    shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, halation, vignette,
                    grain, grainSize, scaleDenom, opac_m, map, nativeLut.data(),
                    nativeLutSize, nativeLutSize - 1, nativeLutSize * nativeLutSize,
                    inv_y, tex, is1k, tx_base, ty_base);
            }
        }
        for(int i=0; i<rtp; i++){ int ay=pr+i; if(!applyCrop||(ay>=sk && ay<sk+fh)){ rpx[0]=orw[i]; jpeg_write_scanlines(&cc,rpx,1); } }
        unsigned char* tmpx[256]; for(int i=0; i<rtp; i++) tmpx[i]=r[i]; for(int i=0; i<BUF-rtp; i++) r[i]=r[i+rtp];
        for(int i=0; i<rtp; i++){ int di=BUF-rtp+i; r[di]=tmpx[i]; if(cd.output_scanline<cd.output_height){ rpx[0]=r[di]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[di],r[di-1],rs); }
        pr += rtp;
    }
    
    if (work_0) { free(work_0); free(work_1); free(work_2); free(work_h); free(h_line); }
    free(rb); free(ob); jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc); jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_TRUE;
}

#include <errno.h>
#include <unistd.h>

// Helper to open files with retries for handling OS locks
FILE* fopen_retry(const char* path, const char* mode) {
    for (int i = 0; i < 5; i++) {
        FILE* f = fopen(path, mode);
        if (f) return f;
        if (errno != EBUSY && errno != EAGAIN && errno != EINTR) break;
        LOGD("File %s busy, retrying... (%d)", path, i + 1);
        usleep(200000); // 200ms
    }
    return fopen(path, mode);
}

// --- FULL RESOLUTION DIPTYCH STITCH ENGINE (FULL STABILITY) ---
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_DiptychManager_stitchDiptychNative(
    JNIEnv* env, jobject obj, jstring path1, jstring path2, jstring outPath, jboolean firstShotLeft, jint quality) {
    
    const char *p1 = env->GetStringUTFChars(path1, NULL);
    const char *p2 = env->GetStringUTFChars(path2, NULL);
    const char *po = env->GetStringUTFChars(outPath, NULL);
    
    LOGD("NATIVE STITCH START: %s + %s -> %s", p1, p2, po);

    FILE *f1 = fopen_retry(p1, "rb");
    if (!f1) {
        LOGD("Diptych open failed (p1): %s, error: %s", p1, strerror(errno));
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    
    FILE *f2 = fopen_retry(p2, "rb");
    if (!f2) {
        LOGD("Diptych open failed (p2): %s, error: %s", p2, strerror(errno));
        fclose(f1);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }

    FILE *fo = fopen_retry(po, "wb");
    if (!fo) {
        LOGD("Diptych open failed (out): %s, error: %s", po, strerror(errno));
        fclose(f1); fclose(f2);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }

    struct jpeg_decompress_struct c1, c2; 
    struct my_error_mgr j1, j2;
    
    // Bullet-proof initialization so error handlers don't crash on NULL pointers
    memset(&c1, 0, sizeof(c1));
    memset(&c2, 0, sizeof(c2));
    
    c1.err = jpeg_std_error(&j1.pub); j1.pub.error_exit = my_error_exit;
    c2.err = jpeg_std_error(&j2.pub); j2.pub.error_exit = my_error_exit;
    if(setjmp(j1.setjmp_buffer) || setjmp(j2.setjmp_buffer)) {
        LOGD("Diptych jpeg decode setup failed");
        if (c1.mem) jpeg_destroy_decompress(&c1);
        if (c2.mem) jpeg_destroy_decompress(&c2);
        fclose(f1); fclose(f2); fclose(fo);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    
    jpeg_create_decompress(&c1); jpeg_stdio_src(&c1, f1); jpeg_read_header(&c1, TRUE);
    jpeg_create_decompress(&c2); jpeg_stdio_src(&c2, f2); jpeg_read_header(&c2, TRUE);
    LOGD("Diptych headers read: %dx%d and %dx%d", c1.image_width, c1.image_height, c2.image_width, c2.image_height);
    
    c1.scale_denom = (c1.image_width > 3000) ? 2 : 1;
    c2.scale_denom = (c2.image_width > 3000) ? 2 : 1;
    
    c1.dct_method = JDCT_IFAST; c1.do_fancy_upsampling = FALSE;
    c2.dct_method = JDCT_IFAST; c2.do_fancy_upsampling = FALSE;
    
    c1.out_color_space = JCS_RGB; jpeg_start_decompress(&c1);
    c2.out_color_space = JCS_RGB; jpeg_start_decompress(&c2);
    
    struct jpeg_compress_struct co; 
    struct my_error_mgr jo; 
    memset(&co, 0, sizeof(co));
    
    co.err = jpeg_std_error(&jo.pub); jo.pub.error_exit = my_error_exit;
    if(setjmp(jo.setjmp_buffer)) {
        LOGD("Diptych jpeg encode setup failed");
        if (co.mem) jpeg_destroy_compress(&co);
        if (c1.mem) jpeg_destroy_decompress(&c1);
        if (c2.mem) jpeg_destroy_decompress(&c2);
        fclose(f1); fclose(f2); fclose(fo);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    jpeg_create_compress(&co); jpeg_stdio_dest(&co, fo);
    
    int w1 = c1.output_width, h1 = c1.output_height;
    int w2 = c2.output_width, h2 = c2.output_height;
    int half1 = w1 / 2, half2 = w2 / 2;
    int q1 = w1 / 4, q2 = w2 / 4;
    int finalW = half1 + half2, finalH = std::min(h1, h2);
    
    co.image_width = finalW; co.image_height = finalH; co.input_components = 3; co.in_color_space = JCS_RGB;
    jpeg_set_defaults(&co); jpeg_set_quality(&co, quality, TRUE); jpeg_start_compress(&co, TRUE);
    
    unsigned char *row1 = (unsigned char*)malloc(w1 * 3);
    unsigned char *row2 = (unsigned char*)malloc(w2 * 3);
    unsigned char *combined = (unsigned char*)malloc(finalW * 3);
    if (!row1 || !row2 || !combined) {
        LOGD("Diptych malloc failed: r1=%p r2=%p c=%p", row1, row2, combined);
        if (row1) free(row1);
        if (row2) free(row2);
        if (combined) free(combined);
        if (co.mem) jpeg_destroy_compress(&co);
        if (c1.mem) jpeg_destroy_decompress(&c1);
        if (c2.mem) jpeg_destroy_decompress(&c2);
        fclose(f1); fclose(f2); fclose(fo);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    LOGD("Diptych buffers allocated. Starting loop.");
    JSAMPROW rp1[1], rp2[1], rpo[1]; rp1[0] = row1; rp2[0] = row2; rpo[0] = combined;
    
    for (int y = 0; y < finalH; y++) {
        jpeg_read_scanlines(&c1, rp1, 1); jpeg_read_scanlines(&c2, rp2, 1);
        if (firstShotLeft) {
            memcpy(combined, row1 + q1 * 3, half1 * 3);
            memcpy(combined + half1 * 3, row2 + q2 * 3, half2 * 3);
        } else {
            memcpy(combined, row2 + q2 * 3, half2 * 3);
            memcpy(combined + half2 * 3, row1 + q1 * 3, half1 * 3);
        }
        // Draw Divider
        int dividerX = firstShotLeft ? half1 : half2;
        for(int d=-1; d<=1; d++) {
            int dx = dividerX + d;
            if (dx >= 0 && dx < finalW) {
                int di = dx * 3;
                combined[di]=combined[di+1]=combined[di+2]=0;
            }
        }
        jpeg_write_scanlines(&co, rpo, 1);
        if (y % 500 == 0) LOGD("Diptych progress: %d/%d", y, finalH);
    }
    
    LOGD("Diptych loop finished. Cleaning up.");
    free(row1); free(row2); free(combined);
    jpeg_finish_compress(&co); jpeg_destroy_compress(&co);
    
    // Abort decompression immediately without finishing to avoid unread scanline errors
    jpeg_destroy_decompress(&c1);
    jpeg_destroy_decompress(&c2);
    
    LOGD("Diptych SUCCESS: %s", po);
    fclose(f1); fclose(f2); fclose(fo);
    env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
    return JNI_TRUE;
}
