#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <stdio.h>
#include <setjmp.h>
#include <algorithm>
#include "jpeglib.h"
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

    unsigned char** rows;
    unsigned char** out_rows;
    int start_i, end_i, proc_rows_base;
    int width;
    int scaleDenom;
    bool use_rgb;
    int bloom, halation;
    double start_time;
    int cx, cy_center;
    float vig_coef;
    int shadowToe, rollOff, colorChrome, chromeBlue, subSat, grain, grainSize, opac_m;
    int* map;
    uint8_t* roll_lut;
    const uint8_t* extTex;
    bool is_1024;
    bool applyCrop;
    int skip_top, final_h;

    ThreadWorkspace ws;
};

#include "process_kernel.h"

void* persistent_worker_func(void* arg) {
    WorkerData* w = (WorkerData*)arg;
    while(true) {
        pthread_mutex_lock(&w->mutex);
        while(!w->start && !w->terminate) pthread_cond_wait(&w->cond_start, &w->mutex);
        if(w->terminate) { pthread_mutex_unlock(&w->mutex); break; }
        
        for(int i=w->start_i; i<w->end_i; i++) {
            process_row_impl(i, w->proc_rows_base, w->rows, w->out_rows, w->width, w->scaleDenom, w->use_rgb, w->bloom, w->halation, w->start_time, w->cx, w->cy_center, w->vig_coef, w->shadowToe, w->rollOff, w->colorChrome, w->chromeBlue, w->subSat, w->grain, w->grainSize, w->opac_m, w->map, w->roll_lut, w->extTex, w->is_1024, w->applyCrop, w->skip_top, w->final_h, w->ws, nativeLut, nativeLutSize);
        }
        
        w->start = false; w->done = true;
        pthread_cond_signal(&w->cond_done);
        pthread_mutex_unlock(&w->mutex);
    }
    return NULL;
}

extern "C" JNIEXPORT void JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_setNativeLut(JNIEnv* env, jobject obj, jintArray lut, jint size) {
    nativeLutSize = size; jint* data = env->GetIntArrayElements(lut, 0);
    nativeLut.assign((uint8_t*)data, (uint8_t*)data + size*size*size*4);
    env->ReleaseIntArrayElements(lut, data, 0);
}

extern "C" JNIEXPORT void JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadGrainTextureNative(JNIEnv* env, jobject obj, jstring path) {
    const char* p = env->GetStringUTFChars(path, 0); int w, h, n;
    unsigned char* d = stbi_load(p, &w, &h, &n, 1);
    if(d) { nativeGrainTexture.assign(d, d + w*h); stbi_image_free(d); }
    env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_applyLutToJpeg(JNIEnv* env, jobject obj, jstring inPath, jstring outPath, jint scaleDenom, jint opacity, jint grain, jint grainSize, jint vignette, jint rollOff, jint colorChrome, jint chromeBlue, jint shadowToe, jint subtractiveSat, jint halation, jint bloom, jint quality, jboolean applyCrop, jint numCores) {
    const char *ifn = env->GetStringUTFChars(inPath, 0), *ofn = env->GetStringUTFChars(outPath, 0);
    FILE *inf = fopen(ifn, "rb"), *ouf = fopen(ofn, "wb"); if(!inf || !ouf){ if(inf)fclose(inf); if(ouf)fclose(ouf); return JNI_FALSE; }
    
    struct jpeg_decompress_struct cd; struct my_error_mgr jd; cd.err = jpeg_std_error(&jd.pub); jd.pub.error_exit = my_error_exit;
    if(setjmp(jd.setjmp_buffer)){ fclose(inf); fclose(ouf); return JNI_FALSE; }
    jpeg_create_decompress(&cd); jpeg_stdio_src(&cd, inf); jpeg_read_header(&cd, TRUE);
    cd.scale_denom = scaleDenom; cd.out_color_space = JCS_RGB; jpeg_start_decompress(&cd);

    int sk = 0, fh = cd.output_height;
    if(applyCrop) {
        double r = (double)cd.output_width / cd.output_height;
        if (r > 1.51) { double tw = cd.output_height * 1.5; sk = (cd.output_width - (int)tw)/2; }
        else { double th = cd.output_width / 1.5; sk = (cd.output_height - (int)th)/2; fh = (int)th; }
    }

    struct jpeg_compress_struct cc; struct my_error_mgr jc; cc.err = jpeg_std_error(&jc.pub); jc.pub.error_exit = my_error_exit;
    if(setjmp(jc.setjmp_buffer)){ jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); return JNI_FALSE; }
    jpeg_create_compress(&cc); jpeg_stdio_dest(&cc, ouf);
    cc.image_width = applyCrop ? (int)(cd.output_height * 1.5) : cd.output_width;
    cc.image_height = fh; cc.input_components = 3; cc.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cc); jpeg_set_quality(&cc, quality, TRUE); jpeg_start_compress(&cc, TRUE);

    struct jpeg_marker_struct* mark = cd.marker_list;
    while(mark){
        if(mark->marker == JPEG_COM || (mark->marker >= JPEG_APP0 && mark->marker <= JPEG_APP15))
            jpeg_write_marker(&cc, mark->marker, mark->data, mark->data_length);
        mark = mark->next;
    }

    int rs = cd.output_width*3;
    int CHK = (scaleDenom >= 2) ? 200 : 120;
    int BUF = CHK + 20;
    int maxTs = (scaleDenom >= 4) ? numCores : (scaleDenom >= 2) ? std::min(numCores, 3) : std::min(numCores, 2);
    int ts = std::min(maxTs, 4);

    unsigned char* rb = (unsigned char*)malloc(BUF*rs); unsigned char* r[256]; for(int i=0; i<BUF; i++) r[i]=rb+(i*rs);
    unsigned char* ob = (unsigned char*)malloc(CHK*rs); unsigned char* orw[256]; for(int i=0; i<CHK; i++) orw[i]=ob+(i*rs);

    int map[256]; for(int i=0; i<256; i++) map[i]=(i*(nativeLutSize-1)*128)/255;
    uint8_t roll[256]; generate_rolloff_lut(roll, rollOff);
    double st = (double)time(NULL);

    int ws_s = cd.output_width*sizeof(int); std::vector<WorkerData> wks(ts);
    for(int i=0; i<ts; i++){
        WorkerData& w=wks[i]; pthread_mutex_init(&w.mutex,NULL); pthread_cond_init(&w.cond_start,NULL); pthread_cond_init(&w.cond_done,NULL);
        w.start=w.done=w.terminate=false;
        w.ws.work_0=(int*)malloc(ws_s); w.ws.work_1=(int*)malloc(ws_s); w.ws.work_2=(int*)malloc(ws_s); w.ws.work_h=(int*)malloc(ws_s); w.ws.h_line=(int*)malloc(ws_s);
        pthread_create(&w.thread,NULL,persistent_worker_func,&w);
    }

    const uint8_t* tex = nativeGrainTexture.empty() ? NULL : nativeGrainTexture.data(); bool is1k = nativeGrainTexture.size()>1000000; JSAMPROW rpx[1];
    if(cd.output_height>0){ rpx[0]=r[10]; jpeg_read_scanlines(&cd,rpx,1); for(int i=0; i<10; i++) memcpy(r[i],r[10],rs); }
    for(int i=11; i<BUF; i++){ if(cd.output_scanline < cd.output_height){ rpx[0]=r[i]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[i],r[i-1],rs); }

    int pr = 0; while(pr < (int)cd.output_height){
        int rtp = std::min(CHK, (int)cd.output_height-pr);
        for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); w.start_i=i*rtp/ts; w.end_i=(i+1)*rtp/ts; w.proc_rows_base=pr; w.rows=r; w.out_rows=orw; w.width=cd.output_width; w.scaleDenom=scaleDenom; w.use_rgb=(nativeLutSize>0&&opacity>0); w.bloom=bloom; w.halation=halation; w.start_time=st; w.cx=cd.output_width/2; w.cy_center=cd.output_height/2; w.vig_coef=get_vig_coef(vignette, w.cx*w.cx+w.cy_center*w.cy_center); w.shadowToe=shadowToe; w.rollOff=rollOff; w.colorChrome=colorChrome; w.chromeBlue=chromeBlue; w.subSat=subtractiveSat; w.grain=grain; w.grainSize=grainSize; w.opac_m=(opacity*256)/100; w.map=map; w.roll_lut=roll; w.extTex=tex; w.is_1024=is1k; w.applyCrop=applyCrop; w.skip_top=sk; w.final_h=fh; w.start=true; w.done=false; pthread_cond_signal(&w.cond_start); pthread_mutex_unlock(&w.mutex); }
        for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); while(!w.done) pthread_cond_wait(&w.cond_done,&w.mutex); pthread_mutex_unlock(&w.mutex); }
        for(int i=0; i<rtp; i++){ int ay=pr+i; if(!applyCrop||(ay>=sk && ay<sk+fh)){ rpx[0]=orw[i]; jpeg_write_scanlines(&cc,rpx,1); } }
        unsigned char* tmpx[256]; for(int i=0; i<rtp; i++) tmpx[i]=r[i]; for(int i=0; i<BUF-rtp; i++) r[i]=r[i+rtp];
        for(int i=0; i<rtp; i++){ int di=BUF-rtp+i; r[di]=tmpx[i]; if(cd.output_scanline<cd.output_height){ rpx[0]=r[di]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[di],r[di-1],rs); }
        pr += rtp;
    }
    for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); w.terminate=true; pthread_cond_signal(&w.cond_start); pthread_mutex_unlock(&w.mutex); pthread_join(w.thread,NULL); free(w.ws.work_0); free(w.ws.work_1); free(w.ws.work_2); free(w.ws.work_h); free(w.ws.h_line); }
    free(rb); free(ob); jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc); jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_TRUE;
}

// --- FULL RESOLUTION DIPTYCH STITCH ENGINE ---
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_DiptychManager_stitchDiptychNative(
    JNIEnv* env, jobject obj, jstring path1, jstring path2, jstring outPath, jboolean firstShotLeft, jint quality) {

    const char *p1 = env->GetStringUTFChars(path1, NULL);
    const char *p2 = env->GetStringUTFChars(path2, NULL);
    const char *po = env->GetStringUTFChars(outPath, NULL);
    FILE *f1 = fopen(p1, "rb"), *f2 = fopen(p2, "rb"), *fo = fopen(po, "wb");

    jboolean result = JNI_FALSE;
    struct jpeg_decompress_struct c1, c2; struct my_error_mgr j1, j2;
    struct jpeg_compress_struct co; struct my_error_mgr jo;
    bool c1_init = false, c2_init = false, co_init = false;

    if (!f1 || !f2 || !fo) goto cleanup;

    c1.err = jpeg_std_error(&j1.pub); j1.pub.error_exit = my_error_exit;
    c2.err = jpeg_std_error(&j2.pub); j2.pub.error_exit = my_error_exit;
    co.err = jpeg_std_error(&jo.pub); jo.pub.error_exit = my_error_exit;

    if(setjmp(j1.setjmp_buffer) || setjmp(j2.setjmp_buffer) || setjmp(jo.setjmp_buffer)) goto cleanup;

    jpeg_create_decompress(&c1); c1_init = true; jpeg_stdio_src(&c1, f1); jpeg_read_header(&c1, TRUE);
    jpeg_create_decompress(&c2); c2_init = true; jpeg_stdio_src(&c2, f2); jpeg_read_header(&c2, TRUE);

    c1.scale_denom = (c1.image_width > 3000) ? 2 : 1;
    c2.scale_denom = (c2.image_width > 3000) ? 2 : 1;
    c1.out_color_space = JCS_RGB; jpeg_start_decompress(&c1);
    c2.out_color_space = JCS_RGB; jpeg_start_decompress(&c2);

    jpeg_create_compress(&co); co_init = true; jpeg_stdio_dest(&co, fo);

    int w1 = c1.output_width, h1 = c1.output_height;
    int w2 = c2.output_width, h2 = c2.output_height;
    
    // BOTH shots use the Center 50% crop to match the camera's fixed AF point.
    // This ensures what the user centered in the active viewfinder window is what is saved.
    int half1 = w1 / 2; 
    int startX1 = w1 / 4; 
    
    int half2 = w2 / 2;
    int startX2 = w2 / 4;
    
    int finalW = half1 + half2, finalH = std::min(h1, h2);

    co.image_width = finalW; co.image_height = finalH; co.input_components = 3; co.in_color_space = JCS_RGB;
    jpeg_set_defaults(&co); jpeg_set_quality(&co, quality, TRUE); jpeg_start_compress(&co, TRUE);

    unsigned char *row1 = (unsigned char*)malloc(w1 * 3);
    unsigned char *row2 = (unsigned char*)malloc(w2 * 3);
    unsigned char *combined = (unsigned char*)malloc(finalW * 3);
    if (!row1 || !row2 || !combined) { if(row1)free(row1); if(row2)free(row2); if(combined)free(combined); goto cleanup; }

    JSAMPROW rp1[1], rp2[1], rpo[1]; rp1[0] = row1; rp2[0] = row2; rpo[0] = combined;

    for (int y = 0; y < finalH; y++) {
        jpeg_read_scanlines(&c1, rp1, 1); jpeg_read_scanlines(&c2, rp2, 1);
        if (firstShotLeft) {
            // Shot 1 on LEFT (Center Crop), Shot 2 on RIGHT (Center Crop)
            memcpy(combined, row1 + startX1 * 3, half1 * 3);
            memcpy(combined + half1 * 3, row2 + startX2 * 3, half2 * 3);
        } else {
            // Shot 2 on LEFT (Center Crop), Shot 1 on RIGHT (Center Crop)
            memcpy(combined, row2 + startX2 * 3, half2 * 3);
            memcpy(combined + half2 * 3, row1 + startX1 * 3, half1 * 3);
        }
        // Draw Divider
        int seam = firstShotLeft ? half1 : half2;
        for(int d=-2; d<=2; d++) { 
            int di_px = seam + d;
            if (di_px >= 0 && di_px < finalW) {
                int di = di_px * 3;
                combined[di]=combined[di+1]=combined[di+2]=0; 
            }
        }
        jpeg_write_scanlines(&co, rpo, 1);
    }
    
    free(row1); free(row2); free(combined);
    jpeg_finish_compress(&co);
    result = JNI_TRUE;

cleanup:
    if(co_init) jpeg_destroy_compress(&co);
    if(c1_init) jpeg_destroy_decompress(&c1);
    if(c2_init) jpeg_destroy_decompress(&c2);
    if(f1) fclose(f1); if(f2) fclose(f2); if(fo) fclose(fo);
    env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
    return result;
}
