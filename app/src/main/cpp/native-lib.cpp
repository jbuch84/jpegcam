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

// --- PERSISTENT WORKER ARCHITECTURE ---
struct ThreadWorkspace {
    int* work_0; int* work_1; int* work_2; int* work_h; int* h_line;
};

struct WorkerData {
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t cond_start;
    pthread_cond_t cond_done;
    bool start;
    bool done;
    bool terminate;

    int start_i, end_i, proc_rows_base, width, scaleDenom;
    unsigned char** rows; unsigned char** out_rows;
    bool use_rgb; int bloom, halation; long long start_time, cx, cy_center, vig_coef;
    int shadowToe, rollOff, colorChrome, chromeBlue, subSat, grain, grainSize, opac_m;
    int* map; uint8_t* roll_lut; const uint8_t* extTex; bool is_1024;
    bool applyCrop; int skip_top, final_h;
    ThreadWorkspace ws;
};

static void free_workspace(ThreadWorkspace* ws) {
    if (!ws) return;
    free(ws->work_0); ws->work_0 = NULL;
    free(ws->work_1); ws->work_1 = NULL;
    free(ws->work_2); ws->work_2 = NULL;
    free(ws->work_h); ws->work_h = NULL;
    free(ws->h_line); ws->h_line = NULL;
}

static bool alloc_workspace(ThreadWorkspace* ws, int ws_s) {
    if (!ws) return false;
    ws->work_0 = (int*)malloc(ws_s);
    ws->work_1 = (int*)malloc(ws_s);
    ws->work_2 = (int*)malloc(ws_s);
    ws->work_h = (int*)malloc(ws_s);
    ws->h_line = (int*)malloc(ws_s);
    if (!ws->work_0 || !ws->work_1 || !ws->work_2 || !ws->work_h || !ws->h_line) {
        free_workspace(ws);
        return false;
    }
    return true;
}

static void process_row_span(WorkerData* d) {
    for (int i = d->start_i; i < d->end_i; i++) {
        int ay = d->proc_rows_base + i;
        if (!d->applyCrop || (ay >= d->skip_top && ay < d->skip_top + d->final_h)) {
            unsigned char* win[21];
            for (int w = 0; w < 21; w++) win[w] = d->rows[i + w];
            memcpy(d->out_rows[i], win[10], d->width * 3);
            if (d->bloom > 0 || d->halation > 0) {
                apply_bloom_halation(win, d->out_rows[i], d->width, ay, !d->use_rgb, d->bloom, d->halation,
                    d->ws.work_0, d->ws.work_1, d->ws.work_2, d->ws.work_h, d->ws.h_line, d->scaleDenom);
            }
            int tx = d->start_time % 1021;
            int ty = (d->start_time / 13) % 1021;
            if (d->use_rgb) {
                process_row_rgb(d->out_rows[i], d->width, ay, d->cx, d->cy_center, d->vig_coef,
                    d->shadowToe, d->rollOff, d->colorChrome, d->chromeBlue, d->subSat, 0, 0,
                    d->grain, d->grainSize, d->scaleDenom, d->opac_m, d->map, nativeLut.data(),
                    nativeLutSize, nativeLutSize - 1, nativeLutSize * nativeLutSize,
                    d->extTex, d->is_1024, tx, ty);
            } else {
                process_row_yuv(d->out_rows[i], d->width, ay, d->cx, d->cy_center, d->vig_coef,
                    d->shadowToe, d->rollOff, d->colorChrome, d->chromeBlue, d->subSat, 0, 0,
                    d->grain, d->grainSize, d->scaleDenom, d->roll_lut, d->extTex, d->is_1024, tx, ty);
            }
        }
    }
}

void* persistent_worker_func(void* arg) {
    WorkerData* d = (WorkerData*)arg;
    while (true) {
        pthread_mutex_lock(&d->mutex);
        while (!d->start && !d->terminate) pthread_cond_wait(&d->cond_start, &d->mutex);
        if (d->terminate) { pthread_mutex_unlock(&d->mutex); break; }
        d->start = false;
        pthread_mutex_unlock(&d->mutex);

        process_row_span(d);

        pthread_mutex_lock(&d->mutex);
        d->done = true;
        pthread_cond_signal(&d->cond_done);
        pthread_mutex_unlock(&d->mutex);
    }
    return NULL;
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
        FILE *f = fopen(fp, "r"); if(f){ char l[256]; while(fgets(l, 256, f)){ if(strncmp(l,"LUT_3D_SIZE",11)==0){ sscanf(l,"LUT_3D_SIZE %d",&nativeLutSize); nativeLut.resize(nativeLutSize*nativeLutSize*nativeLutSize*3); continue; } float r,g,b; if(nativeLutSize>0 && sscanf(l,"%f %f %f",&r,&g,&b)==3){ static size_t c=0; if(c+2<nativeLut.size()){ nativeLut[c++]=(uint8_t)(r*255); nativeLut[c++]=(uint8_t)(g*255); nativeLut[c++]=(uint8_t)(b*255); } } } fclose(f); }
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
    // Scale-aware chunk size and thread cap.
    // Larger chunks = fewer wake/sleep cycles per image = real-world speedup.
    // Full-res (scale=1) is memory-bandwidth limited on single-channel LPDDR ARM.
    // Capping at 2 threads for full-res actually outperforms 4 threads by halving
    // bus contention. Proxy/half-res are more cache-friendly so more threads help.
    // numCores comes from Java's Runtime.availableProcessors() — correct per camera.
    int CHK = (scaleDenom >= 2) ? 200 : 120;
    int BUF = CHK + 20; // r[256] and orw[256] safe: BUF max=220, CHK max=200 < 256
    int safeCores = std::max((int)numCores, 1);
    int maxTs = (scaleDenom >= 4) ? safeCores :
                (scaleDenom >= 2) ? std::min(safeCores, 3) :
                                    std::min(safeCores, 2);
    int ts = std::max(1, std::min(maxTs, 4)); // Hard cap of 4

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

    int ws_s = cd.output_width*sizeof(int);
    ThreadWorkspace fallbackWs = {0};
    if (!alloc_workspace(&fallbackWs, ws_s)) {
        free(rb); free(ob);
        jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc);
        jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd);
        fclose(inf); fclose(ouf);
        env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn);
        return JNI_FALSE;
    }

    std::vector<WorkerData> wks(ts);
    bool workersReady = true;
    int createdWorkers = 0;
    for(int i=0; i<ts; i++){
        WorkerData& w=wks[i];
        memset(&w.ws, 0, sizeof(w.ws));
        if (pthread_mutex_init(&w.mutex,NULL) != 0 ||
            pthread_cond_init(&w.cond_start,NULL) != 0 ||
            pthread_cond_init(&w.cond_done,NULL) != 0 ||
            !alloc_workspace(&w.ws, ws_s) ||
            pthread_create(&w.thread,NULL,persistent_worker_func,&w) != 0) {
            workersReady = false;
            free_workspace(&w.ws);
            break;
        }
        w.start=w.done=w.terminate=false;
        createdWorkers++;
    }
    if (!workersReady) {
        for (int i = 0; i < createdWorkers; i++) {
            WorkerData& w = wks[i];
            pthread_mutex_lock(&w.mutex);
            w.terminate = true;
            pthread_cond_signal(&w.cond_start);
            pthread_mutex_unlock(&w.mutex);
            pthread_join(w.thread, NULL);
            free_workspace(&w.ws);
            pthread_cond_destroy(&w.cond_start);
            pthread_cond_destroy(&w.cond_done);
            pthread_mutex_destroy(&w.mutex);
        }
    }

    const uint8_t* tex = nativeGrainTexture.empty() ? NULL : nativeGrainTexture.data(); bool is1k = nativeGrainTexture.size()>1000000; JSAMPROW rpx[1];
    if(cd.output_height>0){ rpx[0]=r[10]; jpeg_read_scanlines(&cd,rpx,1); for(int i=0; i<10; i++) memcpy(r[i],r[10],rs); }
    for(int i=11; i<BUF; i++){ if(cd.output_scanline < cd.output_height){ rpx[0]=r[i]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[i],r[i-1],rs); }

    WorkerData fallbackWorker;
    memset(&fallbackWorker, 0, sizeof(fallbackWorker));
    fallbackWorker.scaleDenom = scaleDenom;
    fallbackWorker.width = cd.output_width;
    fallbackWorker.use_rgb = (nativeLutSize>0&&opacity>0);
    fallbackWorker.bloom = bloom;
    fallbackWorker.halation = halation;
    fallbackWorker.start_time = st;
    fallbackWorker.cx = cd.output_width/2;
    fallbackWorker.cy_center = cd.output_height/2;
    fallbackWorker.vig_coef = get_vig_coef(vignette, fallbackWorker.cx*fallbackWorker.cx+fallbackWorker.cy_center*fallbackWorker.cy_center);
    fallbackWorker.shadowToe = shadowToe;
    fallbackWorker.rollOff = rollOff;
    fallbackWorker.colorChrome = colorChrome;
    fallbackWorker.chromeBlue = chromeBlue;
    fallbackWorker.subSat = subtractiveSat;
    fallbackWorker.grain = grain;
    fallbackWorker.grainSize = grainSize;
    fallbackWorker.opac_m = (opacity*256)/100;
    fallbackWorker.map = map;
    fallbackWorker.roll_lut = roll;
    fallbackWorker.extTex = tex;
    fallbackWorker.is_1024 = is1k;
    fallbackWorker.applyCrop = applyCrop;
    fallbackWorker.skip_top = sk;
    fallbackWorker.final_h = fh;
    fallbackWorker.ws = fallbackWs;

    int pr = 0; while(pr < (int)cd.output_height){
        int rtp = std::min(CHK, (int)cd.output_height-pr);
        if (workersReady) {
            for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); w.start_i=i*rtp/ts; w.end_i=(i+1)*rtp/ts; w.proc_rows_base=pr; w.rows=r; w.out_rows=orw; w.width=cd.output_width; w.scaleDenom=scaleDenom; w.use_rgb=(nativeLutSize>0&&opacity>0); w.bloom=bloom; w.halation=halation; w.start_time=st; w.cx=cd.output_width/2; w.cy_center=cd.output_height/2; w.vig_coef=get_vig_coef(vignette, w.cx*w.cx+w.cy_center*w.cy_center); w.shadowToe=shadowToe; w.rollOff=rollOff; w.colorChrome=colorChrome; w.chromeBlue=chromeBlue; w.subSat=subtractiveSat; w.grain=grain; w.grainSize=grainSize; w.opac_m=(opacity*256)/100; w.map=map; w.roll_lut=roll; w.extTex=tex; w.is_1024=is1k; w.applyCrop=applyCrop; w.skip_top=sk; w.final_h=fh; w.start=true; w.done=false; pthread_cond_signal(&w.cond_start); pthread_mutex_unlock(&w.mutex); }
            for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); while(!w.done) pthread_cond_wait(&w.cond_done,&w.mutex); pthread_mutex_unlock(&w.mutex); }
        } else {
            fallbackWorker.start_i = 0;
            fallbackWorker.end_i = rtp;
            fallbackWorker.proc_rows_base = pr;
            fallbackWorker.rows = r;
            fallbackWorker.out_rows = orw;
            process_row_span(&fallbackWorker);
        }
        for(int i=0; i<rtp; i++){ int ay=pr+i; if(!applyCrop||(ay>=sk && ay<sk+fh)){ rpx[0]=orw[i]; jpeg_write_scanlines(&cc,rpx,1); } }
        unsigned char* tmpx[256]; for(int i=0; i<rtp; i++) tmpx[i]=r[i]; for(int i=0; i<BUF-rtp; i++) r[i]=r[i+rtp];
        for(int i=0; i<rtp; i++){ int di=BUF-rtp+i; r[di]=tmpx[i]; if(cd.output_scanline<cd.output_height){ rpx[0]=r[di]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[di],r[di-1],rs); }
        pr += rtp;
    }
    if (workersReady) {
        for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); w.terminate=true; pthread_cond_signal(&w.cond_start); pthread_mutex_unlock(&w.mutex); pthread_join(w.thread,NULL); free_workspace(&w.ws); pthread_cond_destroy(&w.cond_start); pthread_cond_destroy(&w.cond_done); pthread_mutex_destroy(&w.mutex); }
    }
    free_workspace(&fallbackWs);
    free(rb); free(ob); jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc); jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_TRUE;
}

// --- FULL RESOLUTION DIPTYCH STITCH ENGINE (FULL STABILITY) ---
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_DiptychManager_stitchDiptychNative(
    JNIEnv* env, jobject obj, jstring path1, jstring path2, jstring outPath, jboolean firstShotLeft, jint quality) {
    
    const char *p1 = env->GetStringUTFChars(path1, NULL);
    const char *p2 = env->GetStringUTFChars(path2, NULL);
    const char *po = env->GetStringUTFChars(outPath, NULL);
    FILE *f1 = fopen(p1, "rb"), *f2 = fopen(p2, "rb"), *fo = fopen(po, "wb");
    if (!f1 || !f2 || !fo) { if(f1)fclose(f1); if(f2)fclose(f2); if(fo)fclose(fo); return JNI_FALSE; }

    struct jpeg_decompress_struct c1, c2; struct my_error_mgr j1, j2;
    c1.err = jpeg_std_error(&j1.pub); j1.pub.error_exit = my_error_exit;
    c2.err = jpeg_std_error(&j2.pub); j2.pub.error_exit = my_error_exit;
    if(setjmp(j1.setjmp_buffer) || setjmp(j2.setjmp_buffer)) { fclose(f1); fclose(f2); fclose(fo); return JNI_FALSE; }
    
    jpeg_create_decompress(&c1); jpeg_stdio_src(&c1, f1); jpeg_read_header(&c1, TRUE);
    jpeg_create_decompress(&c2); jpeg_stdio_src(&c2, f2); jpeg_read_header(&c2, TRUE);
    
    // Smart scale: if images are large (full/half-res processed files), decode at 1/2
    // to prevent OOM. Effects are already baked in by processImageNative before stitching.
    // Small proxy files (width <= 3000) decode at full scale to preserve quality.
    c1.scale_denom = (c1.image_width > 3000) ? 2 : 1;
    c2.scale_denom = (c2.image_width > 3000) ? 2 : 1;
    c1.out_color_space = JCS_RGB; jpeg_start_decompress(&c1);
    c2.out_color_space = JCS_RGB; jpeg_start_decompress(&c2);
    
    struct jpeg_compress_struct co; struct my_error_mgr jo; co.err = jpeg_std_error(&jo.pub); jo.pub.error_exit = my_error_exit;
    if(setjmp(jo.setjmp_buffer)) { return JNI_FALSE; }
    jpeg_create_compress(&co); jpeg_stdio_dest(&co, fo);
    
    int w1 = c1.output_width, h1 = c1.output_height;
    int w2 = c2.output_width, h2 = c2.output_height;
    int half1 = w1 / 2, half2 = w2 / 2;
    int finalW = half1 + half2, finalH = std::min(h1, h2);
    
    co.image_width = finalW; co.image_height = finalH; co.input_components = 3; co.in_color_space = JCS_RGB;
    jpeg_set_defaults(&co); jpeg_set_quality(&co, quality, TRUE); jpeg_start_compress(&co, TRUE);
    
    unsigned char *row1 = (unsigned char*)malloc(w1 * 3);
    unsigned char *row2 = (unsigned char*)malloc(w2 * 3);
    unsigned char *combined = (unsigned char*)malloc(finalW * 3);
    JSAMPROW rp1[1], rp2[1], rpo[1]; rp1[0] = row1; rp2[0] = row2; rpo[0] = combined;
    
    for (int y = 0; y < finalH; y++) {
        jpeg_read_scanlines(&c1, rp1, 1); jpeg_read_scanlines(&c2, rp2, 1);
        if (firstShotLeft) {
            memcpy(combined, row1, half1 * 3);
            memcpy(combined + half1 * 3, row2 + half2 * 3, half2 * 3);
        } else {
            memcpy(combined, row2 + half2 * 3, half2 * 3);
            memcpy(combined + half2 * 3, row1, half1 * 3);
        }
        // Draw Divider
        int dividerX = firstShotLeft ? half1 : half2;
        for(int d=-2; d<=2; d++) {
            int dx = dividerX + d;
            if (dx >= 0 && dx < finalW) {
                int di = dx * 3;
                combined[di]=combined[di+1]=combined[di+2]=0;
            }
        }
        jpeg_write_scanlines(&co, rpo, 1);
    }
    
    free(row1); free(row2); free(combined);
    jpeg_finish_compress(&co); jpeg_destroy_compress(&co);
    jpeg_finish_decompress(&c1); jpeg_destroy_decompress(&c1);
    jpeg_finish_decompress(&c2); jpeg_destroy_decompress(&c2);
    fclose(f1); fclose(f2); fclose(fo);
    env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
    return JNI_TRUE;
}
