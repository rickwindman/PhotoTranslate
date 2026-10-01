# 拍照翻译（PhotoTranslate）

Android 原生拍照翻译 App：拍照 → 本地 PaddleOCR(NCNN) 离线识别 → 文本翻译 → 译文按原文位置叠加回显。体验类似微信"拍照翻译在原位"。

- 包名：`com.destinywind.dcim`；应用名：拍照翻译
- 最低支持 Android 12（minSdk 31），targetSdk 36，仅 `arm64-v8a`
- 版本：v1.0.0 (versionCode 1)（每次更新变更版本号）

## 流水线

```
拍照(CameraX) → 本地 OCR（PaddleOCR mobile + NCNN，模型不打包进 APK）→ 文本翻译（4 类引擎可选）→ Canvas 叠加回原图
```

所有翻译引擎（本地/免费/常规/AI）都以 OCR 输出文本为输入；OCR 固定使用本地模型，不提供云端 OCR。

## 引擎与降级

| 引擎 | 翻译方式 | 联网 | 说明 |
|---|---|---|---|
| 本地翻译 | ML Kit On-Device Translation（59 语言） | 首次下载语言包后完全离线 | `com.google.mlkit:translate`，语言包由设置页管理 |
| 免费翻译（默认） | MyMemory API | 需网络 | 匿名可用，无需密钥 |
| 常规翻译 | 百度(MD5签名) / DeepL(Free/Pro) / 微软 Azure(region) / 腾讯(TC3 v3 签名) | 需网络 | 用户自配密钥，支持测试连接 |
| AI 翻译 | OpenAI Chat Completion 兼容接口 | 需网络 | Base URL/模型/Key/提示词/温度/max_tokens 全可配 |

降级链：当前引擎失败 → 免费翻译（MyMemory）→ 仅展示 OCR 原文并提示原因。引擎切换后立即对当前图片重译。

## 工程结构

```
app/src/main/
├── cpp/                        # C++ 推理层（仅 arm64-v8a 构建）
│   ├── ocr_jni.cpp             # JNI 入口：init/detect/release（管线改造自参考实现）
│   ├── common.cpp/.h           # 检测框处理/旋转裁剪（复用）
│   ├── clipper.cpp/.hpp        # unClip 多边形扩张（复用）
│   ├── ncnn-sdk/               # NCNN 20241226 android-vulkan 预编译包
│   └── opencv-sdk/             # opencv-mobile 4.13.0 android（core+imgproc 精简）
├── assets/models.json          # 内置模型清单（多镜像直链 + SHA256 + 体积）
└── java/com/destinywind/dcim/
    ├── core/ocr/               # OcrEngine(JNI 封装) / OcrRepository(EXIF·识别)
    ├── core/model/             # ModelRepository（内置清单 + 自定义清单）
    ├── core/download/          # WorkManager 下载器（断点续传/多镜像/哈希/zip 安全解压）
    ├── translate/              # TranslateEngine 接口与 6 个引擎实现 + 降级管理
    ├── data/                   # DataStore Preferences + EncryptedSharedPreferences
    └── ui/                     # camera / result / settings 三页面（Navigation Compose 单栈）
```

## 依赖清单（app/build.gradle.kts）

| 依赖 | 用途 |
|---|---|
| compose-bom 2024.09 + material3 | UI |
| androidx.camera 1.3.4 | 拍照/预览/前后摄 |
| navigation-compose 2.8.1 | 单栈导航 |
| hilt 2.51.1 + KSP | 依赖注入（含 hilt-work） |
| okhttp 4.12 / retrofit 2.11 / kotlinx-serialization | 网络与 JSON |
| work-runtime-ktx 2.9.1 | 模型后台下载 |
| com.google.mlkit:translate 17.0.2 | 本地离线翻译 |
| datastore-preferences / security-crypto | 配置与密钥加密存储 |
| exifinterface | 拍摄方向修正 |

Maven 仓库配置为阿里云镜像优先（`maven.aliyun.com/google|central`），官方源兜底；Gradle 发行版走腾讯云镜像。

## models.json 索引结构

```jsonc
{
  "version": 1,
  "models": [{
    "modelId": "ppocrv4-mobile",        // 唯一 ID，模型安装目录名
    "name": "PP-OCRv4 mobile 中英通用",
    "version": "v4.0",
    "languages": ["中","英"],
    "sizeBytes": 38573551,              // 全部文件总体积
    "description": "速度与精度均衡，推荐默认",
    "recHeight": 48,                     // rec 输入高度：v3/v4=48，旧 sim 模型=32
    "recommended": true,
    "files": [                           // 每个文件独立下载
      {"name":"det.param", "url":"https://raw.githubusercontent.com/.../det.param",
       "sha256":"...", "sizeBytes":121492}
    ],
    "mirrorPrefixes": [                  // 依次尝试的镜像前缀（拼在 url 前，"" 为官方兜底）
      "https://gh-proxy.org/", "https://v4.gh-proxy.org/",
      "https://cdn.gh-proxy.org/", ""
    ]
  }]
}
```

- 应用启动/进入设置时可拉取远程 `models.json` 索引（失败自动回退内置清单）。
- 自定义镜像 Base URL（设置页可填）会作为**最高优先级前缀**插入 mirrorPrefixes。
- 自定义模型（"通过链接添加"）持久化在 `files/custom_models.json`，与内置清单并存；"恢复默认"不删除。

## 模型转换（PaddleOCR → NCNN）

1. 用 PaddleOCR 官方导出推理模型（det/rec/cls inference model）。
2. 用 `paddle2onnx` 导出 ONNX，再用 `onnx2ncnn`/`paddle2ncnn`（ncnn 仓库工具）生成 `.param/.bin`。
3. 也可直接使用已转换的现成模型（本项目内置清单即指向
   FeiGeChuanShu/ncnn_paddleocr 与 ncnn_ppstructure 仓库中的转换产物）。
4. 字典文件：PP-OCRv3/v4 中英通用模型使用 `keys.txt`（6622 行字符表），重命名为 `keys.txt`。
5. rec 输入高度：PP-OCRv3/v4 为 48，旧版 sim 模型为 32（对应清单 `recHeight` 字段）。

## 模型目录组织（运行时，不打包进 APK）

```
filesDir/models/<modelId>/
├── det.param  det.bin      # 文本检测
├── rec.param  rec.bin      # 文本识别
├── cls.param  cls.bin      # 方向分类（可选）
└── keys.txt                # 字典
```

- APK 只含推理 so 与引导资源；首次使用需在设置页下载模型（未下载时拍照页提示并一键跳转）。
- 下载：WorkManager + OkHttp，HTTP Range 断点续传（`.part` 临时文件）、进度/速度回调、取消、
  失败自动切换镜像/备用链、SHA256 校验、`临时目录 → 校验 → 原子重命名替换`，断电可恢复。
- zip 模型安全解压：拒绝 `../` 与绝对路径、只写模型目录、总体积上限（默认 500MB 可调）。
- 默认仅 Wi-Fi 自动下载（可关）；"清除全部模型缓存"一键释放。
- so 仅 arm64-v8a：`ndk.abiFilters = ["arm64-v8a"]`，NCNN/opencv-mobile SDK 内其他 ABI 不会被打包。

## MyMemory 额度与缓存

- 长文本按 ≤450 字符分段请求后拼接；OCR 整页结果先去重，重复文本只请求一次。
- 结果缓存：内存 LRU + 磁盘 JSON（`cacheDir/translation_cache.json`，key = 原文+语言对+引擎），命中不耗配额。
- 当日字符数/请求数经 DataStore 统计，设置页显示"今日已用量/估算剩余"（匿名额度约 5000 字符/日）。
- 429 / MYMEMORY WARNING：提示"今日免费额度已用完"并引导切换；网络异常指数退避重试 2 次后降级。

## 图像方向与坐标映射

- CameraX `ImageCapture` 写入 EXIF 方向；`OcrRepository.decodeWithExif` 解码后按 EXIF 旋转，保证送入 OCR 的图像方向正确。
- OCR 返回四点框（原图像素坐标）。结果页按 `显示尺寸/图片尺寸` 等比缩放映射；检测内部的 640 缩放与 pad 已在 C++ 层还原到原图坐标。
- 译文块：半透明背景（透明度可在通用设置调），字号按块高自适应（`overlayFontScale` 可调）。

## 原文/译文滑动切换

`HorizontalPager`（2 页：原文页无叠加 / 译文页有叠加）+ 底部 FilterChip 双向同步（点击 chip 动画翻页，滑动同步选中态）。长按图片临时隐藏译文（松开恢复），点击单个译文块该块切回原文显示。

## 设置页与持久化

- LazyColumn 可展开分组卡片：OCR 模型 / 本地翻译(ML Kit) / 免费 / 常规 / AI / 通用；顶部 保存/取消/恢复默认。
- 普通配置 → DataStore Preferences（单 JSON blob）；密钥类（百度 Key/DeepL/Azure/腾讯 SecretKey/AI Key/AppID）→ EncryptedSharedPreferences（Jetpack Security，AES256），任何密钥不写入代码、资源或 build.gradle。
- 变更实时生效：UI 编辑副本，点保存后 `SettingsRepository.save()` 立即推送 StateFlow。

## 复用来源与许可

| 来源 | 许可 | 用途 |
|---|---|---|
| FeiGeChuanShu/ncnn_paddleocr | BSD-3-Clause | 检测→方向→识别→CTC 三段推理管线（common.cpp/clipper/pipeline），本项目适配为文件路径加载 |
| Tencent/ncnn | BSD-3-Clause | NCNN 推理框架与 Android 预编译包 |
| nihui/opencv-mobile | Apache-2.0 | 精简 OpenCV（core/imgproc） |
| PaddlePaddle/PaddleOCR | Apache-2.0 | PP-OCRv3/v4 mobile 模型与字典 |
| google/camera-samples | Apache-2.0 | CameraX 用法参考 |
| MyMemory / 百度 / DeepL / Azure / 腾讯云 | 各官方 API | 翻译接口协议按官方文档实现 |

## 运行步骤

1. Android Studio 打开工程根目录，同步 Gradle（依赖走阿里云镜像，无需代理）。
2. 运行到 Android 12+ arm64-v8a 真机（或 `./gradlew assembleDebug` 后安装 `app/build/outputs/apk/debug/`）。
3. 首次启动：授予相机权限 → 设置页「OCR 模型」下载一个模型并点"启用"。
4. 需要离线翻译时：设置页「本地翻译」下载语言包（如 英→中），结果页引擎切到"本地翻译"。
5. 需要云端/AI 翻译时：在对应分组填入密钥并"测试连接"。
6. 拍照 → 自动识别翻译 → 左右滑看原文/译文。
