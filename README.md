# My Podcast

A minimal offline-first podcast & voice-recording app for Android. It is a WebView shell around a single HTML file plus a small native bridge for recording — about 126 KB APK, no third-party libraries, no telemetry.

一个极简的安卓播客播放 + 口语录音 App：单文件 HTML 界面 + 原生录音桥，安装包只有约 126 KB；音频全部存在手机本地，可选把自己录的音上传到 **你自己部署的** faster-whisper 服务转成文字。

## 功能

- **媒体库**：导入 mp3（可多选），按 `2026-11-13_标题.mp3` 自动拆日期与标题；播放进度自动记住、±15 秒、0.75×～2× 倍速、锁屏控制、搜索、删除
- **三种播放模式**（v1.3）：点一下循环切换 顺序播放 → 单曲循环 → 随机播放，选择自动记住
- **全屏播放页**（v1.3）：粉色唱片转盘 + 漂浮音符，整屏控制、进度拖动、上/下一首、倍速
- **实时字幕**（v1.4）：播放页字幕跟着播放走——当前句高亮、自动滚动、顶部实时条同步显示；新音频第一次播放时后台自动转写。服务器支持时边转边一句句出现；一键复制、点哪句从哪句播
- **可爱图标**（v1.4）：粉唱片 + 奶油爱心，自适应图标 + 各分辨率 PNG，App 内 logo/唱片/心形同步统一
- **长音频分块转写**（v1.5）：超过约 43 秒的音频先在手机上解码成 16 kHz 单声道，按 27 秒左右的静音点切块、逐段上传，每段单独显示进度，最后按时间偏移拼回完整字幕；避免整条播客超过服务器 300 秒上限
- **语音转字幕**（v1.3 起，需自建服务）：把当前音频 POST 到自建的 faster-whisper 服务，字幕逐句显示，一键复制，点哪句从哪句播（服务器返回分段时）
- **录音**：App 内直接录音（MediaRecorder，AAC/.m4a），自动进媒体库，可一键转文字
- **微信直接导入**（v1.2）：微信里长按音频文件 →「用其他应用打开」→ My Podcast，自动进媒体库，不用先保存到手机
- **网页版**：`web/index.html` 浏览器直接打开也能用（导入/播放/进度记忆）

## 安装

安装包：`dist/MyPodcast-v1.5.apk`（约 126 KB）

1. 传到手机（微信文件传输助手 / 数据线均可）→ 点开安装 → 允许「未知来源应用」
2. 第一次录音时允许麦克风权限
3. 覆盖安装不用卸载；用自己重新打包的 apk 覆盖会因签名不同而失败，需先卸载

| 项 | 值 |
|----|-----|
| 包名 | `com.yili.podcast` |
| 版本 | 1.5（versionCode 6） |
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

要点：机器 2 核 1.8G 即可跑 `small.en`（int8，约 500MB 内存）。部署后在 App「设置」页填服务器地址和 `API_TOKEN`，点「测试连接」应返回模型名。

v1.4 的「测试连接」不只看健康检查，还会多发一个空 POST 上传探针，能直接分辨口令错（401/403）、301 改 GET（405）、路径错（404）、连不上（超时/Failed to fetch）。

接口返回 `text` 和 `segments`（每句的起止时间），播放页用 `segments` 做逐句字幕和点句跳转；请求带 `?stream=1` 时服务器按句返回 NDJSON，App 边收边显示。老版本服务只返回 `text` 时 App 自动按句子切分，仍可复制。**要做逐句实时字幕，nginx 必须对转写路径关闭缓冲（`proxy_buffering off`），见部署说明「九」。**

v1.5 起，超过约 43 秒的音频会在手机端切成约 27 秒的小段逐段上传（每段都是普通 `POST /api/transcribe`），因此旧服务器也能用；单段返回后立刻按时间偏移并进字幕。这样每条请求都远低于 nginx 的 300 秒上限，长录音/长播客不会再整条超时。手机端分块上限约 48 MB，超过会尝试整条上传。

> 未部署时 App 的录音、播放、导入等功能完全不受影响，只是「转成文字」不可用。

## 重新打包

需要 **JDK 17** 和 **Android SDK**（platform android-34 + build-tools 34.0.0）：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK=/path/to/Android/sdk
cd android
bash build.sh          # 产物：../dist/MyPodcast-v1.5.apk
```

构建不依赖 Gradle：`aapt2 compile/link → javac → d8 → 写入 classes.dex → zipalign → apksigner`。

## 源码结构

```
my-podcast/
  android/                  ← App 工程（WebView 外壳 + 原生录音桥）
    assets/index.html       ← 全部界面逻辑（媒体库/播放器/播放页/字幕/录音/转写/导入）
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
