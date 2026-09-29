package com.k40webcam

// 全局配置常量：编码参数与 RTSP 端口集中于此，避免散落各处
object Cfg {

    // 视频分辨率（宽）—— 默认竖屏输出，与手机实际摆放一致
    const val W = 1080

    // 视频分辨率（高）
    const val H = 1920

    // 目标帧率
    const val FPS = 30

    // 目标码率：25 Mbps，占 USB 2.0 保守预算（100 Mbps）的四分之一
    const val BRATE = 25_000_000

    // 关键帧间隔（秒），影响切换速度与首帧延迟
    const val IFRM = 2

    // RTSP 服务端口（554 需 root 绑定，故用 8554）
    const val PORT = 8554

    // 启动时默认使用的相机 ID
    const val DEFCAM = "1"

    // 画面旋转角覆盖：-1 = 按传感器方向自动推算；0/90/180/270 = 强制指定
    // 本机 HAL 不支持旋转，故由发送端经 GL 校正，此项即该校正角的可调入口
    const val ROT = -1
}
