# My Podcast

A minimal offline-first podcast & voice-recording app for Android. It is a WebView shell around a single HTML file plus a small native bridge for recording — 41 KB APK, no third-party libraries, no telemetry.

一个极简的安卓播客播放 + 口语录音 App：单文件 HTML 界面 + 原生录音桥，安装包只有 41 KB；音频全部存在手机本地，可选把自己录的音上传到 **你自己部署的** faster-whisper 服务转成文字。

## 功能

- **媒体库**：导入 mp3（可多选），按 `2026-11-13_标题.mp3` 自动拆日期与标题；播放进度自动记住、±15 秒、0.75×～2× 倍速、单曲循环、锁屏控制、搜索、删除
- **录音**：App 内直接录音（MediaRecorder，AAC/.m4a），自动进媒体库
- **录音转文字**：把录音 POST 到自建的 faster-whisper 服务（`server/`），返回文字，可复制。服务不填也能正常录音播放
- **微信直接导入**（v1.2）：微信里长按音频文件 →「用其他应用打开」→ My Podcast，自动进媒体库，不用先保存到手机
- **网页版**：`web/index.html` 浏览器直接打开也能用（导入/播放/进度记忆）

## 安装

安装包：`dist/MyPodcast-v1.2.apk`（41 KB）

1. 传到手机（微信文件传输助手 / 数据线均可）→ 点开安装 → 允许「未知来源应用」
2. 第一次录音时允许麦克风权限
3. 覆盖安装不用卸载；用自己重新打包的 apk 覆盖会因签名不同而失败，需先卸载

| 项 | 值 |
|----|-----|
| 包名 | `com.yili.podcast` |
| 版本 | 1.2（versionCode 3） |
| 系统要求 | Android 6.0（API 23）及以上 |
| 权限 | 麦克风、网络 |
| 数据 | 音频存 App 私有存储（IndexedDB），卸载即清空 |

## 转写服务（可选，自己部署）

```
server/
  transcribe_server.py   ← Flask + faster-whisper，POST /api/transcribe
  requirements.txt
  部署说明.md            ← systemd + nginx 反代完整步骤
```

要点：机器 2 核 1.8G 即可跑 `small.en`（int8，约 500MB 内存）。部署后在 App「设置」页填服务器地址和 `API_TOKEN`，点「测试连接」应返回 `small.en`。

> 未部署时 App 的录音、播放、导入等功能完全不受影响，只是「转成文字」不可用。

## 重新打包

需要 **JDK 17** 和 **Android SDK**（platform android-34 + build-tools 34.0.0）：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK=/path/to/Android/sdk
cd android
bash build.sh          # 产物：../dist/MyPodcast-v1.2.apk
```

构建不依赖 Gradle：`aapt2 compile/link → javac → d8 → 写入 classes.dex → zipalign → apksigner`。

## 源码结构

```
my-podcast/
  android/                  ← App 工程（WebView 外壳 + 原生录音桥）
    assets/index.html       ← 全部界面逻辑（媒体库/播放器/录音/转写/导入）
    src/com/yili/podcast/MainActivity.java
    build.sh                ← 一键打包
  server/                   ← 自建转写服务
  web/index.html            ← 网页版
  docs/使用说明.md           ← 用户手册
  dist/                     ← 打包好的 apk
```

## 隐私

- 不采集、不上传任何数据；音频只存手机本地
- 「转成文字」只在用户点击时把当次录音 POST 到**用户自己填写的**服务器
- 仓库里不含任何服务器地址、域名或口令，全部由使用者自行填写

## License

MIT
