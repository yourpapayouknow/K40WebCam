<div align="center">

<img src="assets/readme-badge.png" alt="K40WebCam 项目徽章" />

# K40WebCam

*将 Redmi K40 转化为低延迟 USB 与局域网虚拟摄像头的高性能推流工具*

<a href="https://developer.android.com"><img src="https://img.shields.io/badge/平台-Android%2011%2B-blue.svg?style=flat-square" alt="平台" /></a>
<a href="https://www.qualcomm.com"><img src="https://img.shields.io/badge/芯片-Snapdragon%20870-orange.svg?style=flat-square" alt="芯片" /></a>
<a href="android/"><img src="https://img.shields.io/badge/语言-Kotlin-purple.svg?style=flat-square" alt="语言" /></a>
<img src="https://img.shields.io/badge/协议-HTTP%20MJPEG-green.svg?style=flat-square" alt="协议" />
<a href="LICENSE"><img src="https://img.shields.io/badge/许可证-GPL--3.0-blue.svg?style=flat-square" alt="许可证" /></a>

</div>

---

K40WebCam 将闲置的 Redmi K40 改造为高画质有线与无线 WebCam。应用深度对接 Android Camera2 底层管道，支持主摄与前摄的硬件直通采集，内置轻量 HTTP 服务器输出零缓存流媒体，供 OBS Studio 等工具调用为系统虚拟摄像头。

---

## 快速开始

如果正在使用具备终端执行能力的智能编程助手，可以直接向其发送以下指令：

```text
帮我安装这个仓库：检查 Android 构建环境，编译 K40WebCam 的 Debug APK，并通过 adb 安装到手机，随后配置 USB 端口映射。
```

> [!TIP]
> 智能代理将自动完成环境检测、Gradle 构建与 APK 安装，免去手动输入命令。

---

## 构建与安装

系统要求：Android 11 或更高版本，电脑端需安装 adb 工具。

```zsh
# 进入工程目录并编译
cd android
./gradlew assembleDebug

# 安装至手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以前往 Releases 页面直接下载预编译安装包。

---

## 使用指南

### 1. 启动服务

打开手机应用并授予相机权限，在控制台选择镜头与分辨率，点击启动服务。

### 2. 建立连接

推荐通过 USB 数据线直连以获得最低延迟与稳定带宽：

```zsh
adb forward tcp:8080 tcp:8080
```

无线局域网环境下可直接使用手机界面提示的局域网地址。

### 3. 流媒体接口

| 路径 | 协议 | 用途 |
|---|---|---|
| /video | HTTP MJPEG | 实时连续视频流 |
| /snapshot | JPEG | 当前单帧画面抓拍 |

电脑本地接入地址为 http://127.0.0.1:8080 加上述路径。

### 4. OBS 虚拟摄像头配置

在 OBS Studio 中添加浏览器源，URL 填入视频流地址，尺寸设定为手机端分辨率数值，点击启动虚拟摄像机即可作为通用摄像头供会议软件选用。

---

## 功能特性

- 双摄平滑切换：支持在运行期间热切换主摄与前摄。
- 零缓存推流：基于无缓存 HTTP 引擎避免队列累积延迟。
- 现代化控制台：提供实时监视视口、动态参数叠层与前台保活服务。

---

## 架构

```text
[ 物理传感器：主摄与前摄 ]
            │ Camera2
            ▼
 [ CamPipe 硬件加速管线 ]
            │
            ▼
[ MjpegStreamer 传输引擎 ]
            │
  ┌─────────┴─────────┐
  ▼ USB 直连          ▼ 局域网 Wi-Fi
[ 127.0.0.1:8080 ] [ 局域网IP:8080 ]
            │
            ▼
[ OBS Studio 与虚拟摄像机 ]
```

---

## 项目结构

```text
K40WebCam/
├── android/                         # Android 应用工程源码
│   ├── app/src/main/java/com/k40webcam/
│   │   ├── CamPipe.kt               # 采集管线与渲染控制
│   │   ├── CamSrv.kt                # 前台保活服务
│   │   ├── Cfg.kt                   # 运行时配置存储
│   │   ├── MainAct.kt               # 控制台界面
│   │   ├── MjpegStreamer.kt         # 流媒体传输服务
│   │   └── Preset.kt                # 分辨率与帧率预设
│   ├── build.gradle.kts             # 构建脚本
│   └── settings.gradle.kts          # 模块配置
├── assets/
│   └── readme-badge.png             # 项目展示徽章
└── LICENSE                          # GPL-3.0 许可证
```
