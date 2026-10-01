# 拍照翻译 OCR 推理层（NCNN + PaddleOCR 三段流水线）
# 推理管线复用自 FeiGeChuanShu/ncnn_paddleocr（BSD-3-Clause）与 ncnn demo，
# 由本项目适配为"从应用私有目录加载模型文件"版本（模型不打包进 APK）。
cmake_minimum_required(VERSION 3.18)
project(paddleocrncnn)

set(CMAKE_CXX_STANDARD 17)
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-sdk/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-sdk/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(paddleocrncnn SHARED ocr_jni.cpp common.cpp clipper.cpp)

target_link_libraries(paddleocrncnn ncnn ${OpenCV_LIBS} jnigraphics log)
