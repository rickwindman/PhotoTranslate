// 拍照翻译 OCR JNI 层
// 管线复用自 FeiGeChuanShu/ncnn_paddleocr（BSD-3-Clause，检测→方向分类→识别→CTC 解码），
// 改动点：模型/字典从应用私有目录文件路径加载（不打包进 APK），输出 blob 名动态取末尾，
// 增加方向分类(cls)推理与阅读顺序排序。许可证：BSD-3-Clause。
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <opencv2/core/core.hpp>
#include "layer.h"
#include "net.h"
#include "common.h"

#define TAG "PhotoTranslateOcr"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;

static ncnn::Net dbNet;
static ncnn::Net clsNet;
static ncnn::Net crnnNet;
static std::vector<std::string> keys;
static bool g_hasCls = false;
static int g_recHeight = 48; // PP-OCRv3/v4 rec 输入高度 48；旧版 sim 模型为 32

// ---------- blob 名动态解析（不同模型转换产物命名不一：input/input0、output/out1） ----------
// 喂输入：优先 "input"，失败取第一个 blob（param 首层 Input 的输出）
static bool feedInput(ncnn::Extractor& ex, const ncnn::Net& net, const ncnn::Mat& in)
{
    if (ex.input("input", in) == 0) return true;
    if (!net.blobs().empty() && ex.input(net.blobs().front().name.c_str(), in) == 0) return true;
    LOGW("feed input blob failed");
    return false;
}

// 取输出：优先 "output"，失败取最后一个 blob；两者都空返回 false
static bool extractOutput(ncnn::Extractor& ex, const ncnn::Net& net, ncnn::Mat& out)
{
    ex.extract("output", out);
    if (!out.empty()) return true;
    if (!net.blobs().empty())
        ex.extract(net.blobs().back().name.c_str(), out);
    if (!out.empty()) return true;
    LOGW("extract output blob failed");
    return false;
}

// ---------- 检测（DBNet）----------
std::vector<TextBox> findRsBoxes(const cv::Mat& fMapMat, const cv::Mat& norfMapMat,
    const float boxScoreThresh, const float unClipRatio)
{
    float minArea = 3;
    std::vector<TextBox> rsBoxes;
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(norfMapMat, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    for (size_t i = 0; i < contours.size(); ++i)
    {
        float minSideLen, perimeter;
        std::vector<cv::Point> minBox = getMinBoxes(contours[i], minSideLen, perimeter);
        if (minSideLen < minArea)
            continue;
        float score = boxScoreFast(fMapMat, contours[i]);
        if (score < boxScoreThresh)
            continue;
        std::vector<cv::Point> clipBox = unClip(minBox, perimeter, unClipRatio);
        std::vector<cv::Point> clipMinBox = getMinBoxes(clipBox, minSideLen, perimeter);
        if (minSideLen < minArea + 2)
            continue;
        for (auto& p : clipMinBox)
        {
            p.x = (std::min)((std::max)(p.x, 0), norfMapMat.cols);
            p.y = (std::min)((std::max)(p.y, 0), norfMapMat.rows);
        }
        rsBoxes.emplace_back(TextBox{ clipMinBox, score, "" });
    }
    reverse(rsBoxes.begin(), rsBoxes.end());
    return rsBoxes;
}

std::vector<TextBox> getTextBoxes(const cv::Mat& src, float boxScoreThresh, float boxThresh, float unClipRatio)
{
    int width = src.cols;
    int height = src.rows;
    int target_size = 640;
    int w = width, h = height;
    float scale = 1.f;
    if (w > h)
    {
        scale = (float)target_size / w; w = target_size; h = h * scale;
    }
    else
    {
        scale = (float)target_size / h; h = target_size; w = w * scale;
    }
    ncnn::Mat input = ncnn::Mat::from_pixels_resize(src.data, ncnn::Mat::PIXEL_RGB, width, height, w, h);
    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(input, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 0.f);

    const float meanValues[3] = { 0.485 * 255, 0.456 * 255, 0.406 * 255 };
    const float normValues[3] = { 1.0 / 0.229 / 255.0, 1.0 / 0.224 / 255.0, 1.0 / 0.225 / 255.0 };
    in_pad.substract_mean_normalize(meanValues, normValues);

    ncnn::Extractor ex = dbNet.create_extractor();
    ncnn::Mat out;
    if (!feedInput(ex, dbNet, in_pad) || !extractOutput(ex, dbNet, out))
    {
        // 输入/输出 blob 均无法解析：返回空结果，绝不包空指针（否则 cv::dilate 解引用 SIGSEGV）
        LOGW("det: no output from model, return empty boxes");
        return {};
    }

    cv::Mat fMapMat(in_pad.h, in_pad.w, CV_32FC1, (float*)out.data);
    cv::Mat norfMapMat;
    norfMapMat = fMapMat > boxThresh;
    cv::dilate(norfMapMat, norfMapMat, cv::Mat(), cv::Point(-1, -1), 1);

    std::vector<TextBox> result = findRsBoxes(fMapMat, norfMapMat, boxScoreThresh, 2.0f);
    for (auto& tb : result)
    {
        for (auto& p : tb.boxPoint)
        {
            float x = (p.x - (wpad / 2)) / scale;
            float y = (p.y - (hpad / 2)) / scale;
            p.x = (int)std::max(std::min(x, (float)(width - 1)), 0.f);
            p.y = (int)std::max(std::min(y, (float)(height - 1)), 0.f);
        }
    }
    return result;
}

// ---------- 方向分类 ----------
cv::Mat classifyRotate(const cv::Mat& src)
{
    if (!g_hasCls) return src;
    int targetW = 192;
    float ratio = (float)targetW / (float)src.cols;
    int dstH = (int)std::max(1.0f, (float)src.rows * ratio);
    cv::Mat rs;
    cv::resize(src, rs, cv::Size(targetW, 48));
    ncnn::Mat in = ncnn::Mat::from_pixels(rs.data, ncnn::Mat::PIXEL_RGB, rs.cols, rs.rows);
    const float mean_vals[3] = { 127.5f, 127.5f, 127.5f };
    const float norm_vals[3] = { 1.0f / 127.5f, 1.0f / 127.5f, 1.0f / 127.5f };
    in.substract_mean_normalize(mean_vals, norm_vals);
    ncnn::Extractor ex = clsNet.create_extractor();
    ncnn::Mat out;
    if (!feedInput(ex, clsNet, in) || !extractOutput(ex, clsNet, out) || out.w < 2) return src;
    const float* d = (const float*)out.data;
    if (d[1] > d[0])
        return matRotateClockWise180(src);
    return src;
}

// ---------- 识别（CRNN + CTC）----------
template<class ForwardIterator>
inline static size_t argmax(ForwardIterator first, ForwardIterator last)
{
    return std::distance(first, std::max_element(first, last));
}

TextLine scoreToTextLine(const std::vector<float>& outputData, int h, int w)
{
    int keySize = (int)keys.size();
    std::string strRes;
    std::vector<float> scores;
    int lastIndex = 0;
    for (int i = 0; i < h; i++)
    {
        int maxIndex = (int)argmax(outputData.begin() + i * w, outputData.begin() + i * w + w);
        float maxValue = *std::max_element(outputData.begin() + i * w, outputData.begin() + i * w + w);
        if (maxIndex > 0 && maxIndex < keySize && (!(i > 0 && maxIndex == lastIndex)))
        {
            scores.emplace_back(maxValue);
            strRes.append(keys[maxIndex - 1]);
        }
        lastIndex = maxIndex;
    }
    return { strRes, scores };
}

TextLine getTextLine(const cv::Mat& srcIn)
{
    cv::Mat src = classifyRotate(srcIn);
    float scale = (float)g_recHeight / (float)src.rows;
    int dstWidth = (int)((float)src.cols * scale);
    if (dstWidth < 1) dstWidth = 1;
    cv::Mat srcResize;
    cv::resize(src, srcResize, cv::Size(dstWidth, g_recHeight));
    ncnn::Mat in = ncnn::Mat::from_pixels(srcResize.data, ncnn::Mat::PIXEL_RGB, srcResize.cols, srcResize.rows);
    const float mean_vals[3] = { 127.5f, 127.5f, 127.5f };
    const float norm_vals[3] = { 1.0f / 127.5f, 1.0f / 127.5f, 1.0f / 127.5f };
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = crnnNet.create_extractor();
    ncnn::Mat out;
    if (!feedInput(ex, crnnNet, in) || !extractOutput(ex, crnnNet, out))
        return TextLine{ "", {} }; // 识别失败返回空文本，不崩溃
    std::vector<float> outputData((float*)out.data, (float*)out.data + out.h * out.w);
    return scoreToTextLine(outputData, out.h, out.w);
}

// 阅读顺序排序：按行分组（y 中心接近为同一行），行内按 x
void sortBoxes(std::vector<TextBox>& boxes)
{
    if (boxes.empty()) return;
    std::sort(boxes.begin(), boxes.end(), [](const TextBox& a, const TextBox& b) {
        float ay = 0, by = 0, amin = 1e9f, bmin = 1e9f;
        for (int i = 0; i < 4; i++) { ay += a.boxPoint[i].y; by += b.boxPoint[i].y;
            amin = (std::min)(amin, (float)a.boxPoint[i].y); bmin = (std::min)(bmin, (float)b.boxPoint[i].y); }
        ay /= 4; by /= 4;
        float aH = ay - amin, bH = by - bmin;
        float tol = (std::max)(aH, bH) * 0.5f + 4.f;
        if (std::fabs(ay - by) <= tol) {
            float ax = 1e9f, bx = 1e9f;
            for (int i = 0; i < 4; i++) { ax = (std::min)(ax, (float)a.boxPoint[i].x); bx = (std::min)(bx, (float)b.boxPoint[i].x); }
            return ax < bx;
        }
        return ay < by;
    });
}

// ---------- JNI ----------
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_destinywind_dcim_core_ocr_OcrEngine_nativeInit(
    JNIEnv* env, jobject, jstring jDetParam, jstring jDetBin,
    jstring jClsParam, jstring jClsBin,
    jstring jRecParam, jstring jRecBin, jstring jKeysPath, jint recHeight)
{
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;
    dbNet.opt = opt;
    clsNet.opt = opt;
    crnnNet.opt = opt;

    auto toPath = [&](jstring js) -> std::string {
        if (!js) return "";
        const char* p = env->GetStringUTFChars(js, nullptr);
        std::string s(p);
        env->ReleaseStringUTFChars(js, p);
        return s;
    };

    std::string detParam = toPath(jDetParam), detBin = toPath(jDetBin);
    std::string recParam = toPath(jRecParam), recBin = toPath(jRecBin);
    std::string keysPath = toPath(jKeysPath);
    g_recHeight = recHeight > 0 ? recHeight : 48;

    dbNet.clear();
    if (dbNet.load_param(detParam.c_str()) != 0) { LOGW("det load_param failed"); return JNI_FALSE; }
    if (dbNet.load_model(detBin.c_str()) != 0) { LOGW("det load_model failed"); return JNI_FALSE; }

    crnnNet.clear();
    if (crnnNet.load_param(recParam.c_str()) != 0) { LOGW("rec load_param failed"); return JNI_FALSE; }
    if (crnnNet.load_model(recBin.c_str()) != 0) { LOGW("rec load_model failed"); return JNI_FALSE; }

    // 方向分类可选
    std::string clsParam = toPath(jClsParam), clsBin = toPath(jClsBin);
    g_hasCls = false;
    if (!clsParam.empty() && !clsBin.empty())
    {
        clsNet.clear();
        if (clsNet.load_param(clsParam.c_str()) == 0 && clsNet.load_model(clsBin.c_str()) == 0)
            g_hasCls = true;
        else
            LOGW("cls model load failed, disabled");
    }

    // 字典
    keys.clear();
    FILE* f = fopen(keysPath.c_str(), "rb");
    if (!f) { LOGW("keys open failed: %s", keysPath.c_str()); return JNI_FALSE; }
    char line[512];
    while (fgets(line, sizeof(line), f))
    {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
        keys.emplace_back(line);
    }
    fclose(f);
    if (keys.empty()) { LOGW("keys empty"); return JNI_FALSE; }
    LOGI("ocr init ok, keys=%zu cls=%d recH=%d", keys.size(), g_hasCls, g_recHeight);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_destinywind_dcim_core_ocr_OcrEngine_nativeRelease(JNIEnv*, jobject)
{
    dbNet.clear();
    clsNet.clear();
    crnnNet.clear();
    g_hasCls = false;
}

JNIEXPORT jobjectArray JNICALL
Java_com_destinywind_dcim_core_ocr_OcrEngine_nativeDetect(JNIEnv* env, jobject, jobject bitmap)
{
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    const int width = info.width, height = info.height;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return NULL;

    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
    cv::Mat rgb = cv::Mat::zeros(in.h, in.w, CV_8UC3);
    in.to_pixels(rgb.data, ncnn::Mat::PIXEL_RGB);

    std::vector<TextBox> objects = getTextBoxes(rgb, 0.4f, 0.3f, 2.0f);
    std::vector<cv::Mat> partImages = getPartImages(rgb, objects);
    sortBoxes(objects);
    // 排序后按同样顺序裁剪
    partImages.clear();
    for (auto& tb : objects) partImages.push_back(getRotateCropImage(rgb, tb.boxPoint));

    jclass cls = env->FindClass("com/destinywind/dcim/core/ocr/OcrLine");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;F[F)V");
    jobjectArray arr = env->NewObjectArray((jsize)objects.size(), cls, NULL);
    for (size_t i = 0; i < objects.size(); i++)
    {
        TextLine tl = getTextLine(partImages[i]);
        float box[8];
        for (int k = 0; k < 4; k++) { box[k * 2] = objects[i].boxPoint[k].x; box[k * 2 + 1] = objects[i].boxPoint[k].y; }
        jfloatArray jbox = env->NewFloatArray(8);
        env->SetFloatArrayRegion(jbox, 0, 8, box);
        jstring jtext = env->NewStringUTF(tl.text.c_str());
        // 置信度取字符均值，无字符时用检测分
        float conf = tl.charScores.empty() ? objects[i].score : 0.f;
        if (tl.charScores.empty()) conf = objects[i].score;
        else { float s = 0; for (float v : tl.charScores) s += v; conf = s / (float)tl.charScores.size(); }
        jobject obj = env->NewObject(cls, ctor, jtext, conf, jbox);
        env->SetObjectArrayElement(arr, (jsize)i, obj);
        env->DeleteLocalRef(jtext); env->DeleteLocalRef(jbox); env->DeleteLocalRef(obj);
    }
    return arr;
}

} // extern "C"
