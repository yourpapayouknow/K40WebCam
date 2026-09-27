package com.k40webcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.view.GlStreamInterface
import com.pedro.rtspserver.server.RtspServer
import java.nio.ByteBuffer

/**
 * 相机管线：Camera2 取景 → GL 旋转/送帧 → MediaCodec 硬编 → RTSP 分发。
 *
 * 关于为何经 GL：实测本机两个相机的 `SCALER_AVAILABLE_ROTATE_AND_CROP_MODES` 均为 [0]
 * （仅支持 OFF），即 **HAL 层不具备旋转能力**；而传感器方向为后置 90、前置 270，
 * 直出画面是旋转的。要在发送端修正朝向，只能经 GPU 处理，故采用 GL 路径
 * （GlStreamInterface 仅做旋转与送帧，不引入滤镜等额外处理）。
 *
 * 相机切换：保留编码器与 GL 上下文，仅重绑相机并更新旋转角，无需重建管线。
 *
 * 注意：Kotlin 属性按声明顺序初始化，回调对象须早于依赖它们的成员声明。
 */
class CamPipe(private val ctx: Context, private val port: Int) : GetVideoData {

    private companion object {
        const val TAG = "CamPipe"
    }

    // RTSP 客户端连接状态回调
    private val connChk = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() {
            Log.i(TAG, "客户端已连接")
        }

        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "客户端连接失败: $reason")
        }

        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() {
            Log.i(TAG, "客户端已断开")
        }

        override fun onAuthError() {}
        override fun onAuthSuccess() {}
    }

    // 相机状态回调；异常时自动尝试恢复，避免管线静默失效
    private val camCb = object : CameraCallbacks {
        override fun onCameraChanged(facing: CameraHelper.Facing) {
            Log.i(TAG, "相机已切换: $facing")
        }

        override fun onCameraError(error: String) {
            Log.e(TAG, "相机错误: $error")
            recovr()
        }

        override fun onCameraOpened() {
            Log.i(TAG, "相机已打开")
        }

        override fun onCameraDisconnected() {
            Log.w(TAG, "相机已断开")
            recovr()
        }
    }

    private val venc = VideoEncoder(this)
    private val cmgr = Camera2ApiManager(ctx)

    // GL 桥接：相机帧经此旋转后送入编码器
    private val gl by lazy { GlStreamInterface(ctx) }

    private val rtsp = RtspServer(connChk, port)

    @Volatile
    private var running = false

    // RTSP 服务是否已启动，避免重复启动
    @Volatile
    private var srvUp = false

    // 是否正在执行相机恢复，防止错误回调递归触发
    @Volatile
    private var recovering = false

    private var camId = "0"

    // 运行时配置：分辨率/帧率/码率/旋转角均可独立调节，初值取自 Cfg
    @Volatile
    private var vw = Cfg.W

    @Volatile
    private var vh = Cfg.H

    @Volatile
    private var vfps = Cfg.FPS

    @Volatile
    private var vbrate = Cfg.BRATE

    @Volatile
    private var vrot = Cfg.ROT

    // 编码类型：H.265 或 H.264，可运行时切换（需重建编码器）
    @Volatile
    private var vcodec = VideoCodec.H265

    // 当前生效的配置快照 [w, h, fps, mbps, rot, codecIndex]
    // codecIndex: 0=H.265, 1=H.264
    fun cfgnow(): IntArray =
        intArrayOf(vw, vh, vfps, vbrate / 1_000_000, rotdeg(camId), if (vcodec == VideoCodec.H265) 0 else 1)

    // 启动管线：编码器 → GL 桥接 → 相机；RTSP 服务待参数集就绪后自动开启
    fun strtpipe(id: String): Boolean {
        if (running) return true
        camId = id
        srvUp = false

        // 编码尺寸即设定分辨率：GL 会通过 SurfaceTexture 变换矩阵处理传感器朝向，
        // 残余角度由 setRotation 补足，故无需互换宽高（实测互换反而产生黑边）。
        venc.type = vcodec
        val ok = venc.prepareVideoEncoder(
            vw, vh, vfps, vbrate, 0, Cfg.IFRM, FormatVideoEncoder.SURFACE
        )
        if (!ok) {
            Log.e(TAG, "编码器初始化失败，设备可能不支持所选编码格式")
            return false
        }
        venc.start()

        // GL 桥接：设定输出尺寸后启动。
        // 注意：GlStreamInterface 的 SurfaceTexture 在 start() 的异步任务中创建，
        // 必须等 isRunning 为真后再取用，否则拿到 null。此方法应在后台线程调用。
        gl.setEncoderSize(vw, vh)
        gl.start()
        var waited = 0
        while (!gl.isRunning && waited < 3000) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
            }
            waited += 50
        }
        if (!gl.isRunning) {
            Log.e(TAG, "GL 初始化超时，管线未启动")
            venc.stop()
            return false
        }

        gl.addMediaCodecSurface(venc.inputSurface)
        // 用 setRotation（内部作用到相机纹理）而非 setStreamRotation：
        // 本机相机输出本身即为旋转状态，需在纹理采样阶段校正
        gl.setRotation(rotdeg(id))

        // 此处不启动 RTSP：须等编码器输出 CSD，见 onVideoInfo
        rtsp.setVideoCodec(vcodec)
        // 本管线仅视频，须显式关闭音频，否则 SDP 会广播一条永不供数的音轨，
        // 导致拉流端（如 ffmpeg）阻塞等待音频而收不到画面
        rtsp.setOnlyVideo(true)

        cmgr.setCameraCallbacks(camCb)
        // 相机输出到 GL 的 SurfaceTexture，由 GL 完成旋转后送编码器
        cmgr.prepareCamera(gl.surfaceTexture, vw, vh, vfps)
        cmgr.openCameraId(id)

        running = true
        Log.i(TAG, "管线已启动: 相机=$id 旋转=${rotdeg(id)}° 端口=$port（等待参数集）")
        return true
    }

    // 切换相机：保留编码器与 GL 上下文，仅重绑相机并更新旋转角
    fun switcam(id: String) {
        if (!running) return
        camId = id
        val rot = rotdeg(id)
        cmgr.closeCamera(false)
        // 用同一份 SurfaceTexture 重新准备，切换旋转角
        cmgr.prepareCamera(gl.surfaceTexture, vw, vh, vfps)
        cmgr.setCameraId(id)
        cmgr.openCameraId(id)
        gl.setStreamRotation(rot)
        venc.requestKeyframe()
        Log.i(TAG, "已切换到相机 $id，旋转=$rot°")
    }

    // 相机异常后自动重开，最多重试 3 次；重试期间由 recovering 标志阻断递归
    private fun recovr() {
        if (!running || recovering) return
        recovering = true
        Thread {
            var ok = false
            for (i in 1..3) {
                try {
                    Thread.sleep(1000L * i)
                    if (!running) break
                    val id = camId
                    Log.i(TAG, "尝试恢复相机 $id（第 $i 次）")
                    cmgr.closeCamera(false)
                    cmgr.prepareCamera(gl.surfaceTexture, vw, vh, vfps)
                    cmgr.setCameraId(id)
                    cmgr.openCameraId(id)
                    venc.requestKeyframe()
                    ok = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "恢复第 $i 次失败: ${e.message}")
                }
            }
            recovering = false
            Log.i(TAG, if (ok) "相机已恢复" else "相机恢复失败，管线保持待机")
        }.start()
    }

    // 运行时调整码率（对流的有限调节之一），立即生效无需重启管线
    fun setbrate(mbps: Int) {
        if (!running) return
        val bps = mbps * 1_000_000
        try {
            venc.setVideoBitrateOnFly(bps)
            vbrate = bps
            Log.i(TAG, "码率已调整为 $mbps Mbps")
        } catch (e: Exception) {
            Log.e(TAG, "码率调整失败: ${e.message}")
        }
    }

    // 当前码率（Mbps）
    fun curbrate(): Int = vbrate / 1_000_000

    // 绑定界面预览输出：相机画面经 GL 额外渲染一份到此 Surface，
    // 不经编码、不占网络，为最低开销的监看方式。
    // 预览分辨率必须等于 Surface 实际尺寸，否则 GL 视口与实际缓冲不一致，
    // 画面会被裁剪放大（只看到局部）。
    fun attachpvw(surface: Surface, sw: Int, sh: Int) {
        try {
            if (sw > 0 && sh > 0) gl.setPreviewResolution(sw, sh)
            gl.attachPreview(surface)
            Log.i(TAG, "预览已绑定")
        } catch (e: Exception) {
            Log.w(TAG, "预览绑定失败: ${e.message}")
        }
    }

    // 解绑预览输出
    fun detachpvw() {
        try {
            gl.deAttachPreview()
            Log.i(TAG, "预览已解绑")
        } catch (e: Exception) {
            Log.w(TAG, "预览解绑失败: ${e.message}")
        }
    }

    // 预览是否随管线就绪（供界面决定是否已可绑定）
    fun glready(): Boolean = gl.isRunning

    // 以新参数重建管线（分辨率变更须重建编码器与 GL，故整体重启）
    // camId 为相机 ID，mbps 为码率，rot 为旋转角（-1 表示按传感器自动推算）
    // 以新参数重建管线（分辨率/编码类型变更须重建编码器与 GL，故整体重启）
    // id 相机 ID；mbps 码率；rot 旋转角（-1 表示按传感器自动）；cdc 0=H.265 1=H.264
    fun reconf(id: String, w: Int, h: Int, fps: Int, mbps: Int, rot: Int, cdc: Int): Boolean {
        Log.i(TAG, "重配置: 相机=$id ${w}x$h@${fps} ${mbps}Mbps 旋转=$rot 编码=${if (cdc == 0) "H265" else "H264"}")
        stppipe()
        vw = w
        vh = h
        vfps = fps
        vbrate = mbps * 1_000_000
        vrot = rot
        vcodec = if (cdc == 0) VideoCodec.H265 else VideoCodec.H264
        return strtpipe(id)
    }

    // 停止管线并释放全部资源
    fun stppipe() {
        if (!running) return
        running = false
        srvUp = false
        cmgr.closeCamera(true)
        gl.removeMediaCodecSurface()
        // 停掉 GL 线程：其 SurfaceTexture 在下次 start() 时会重建，
        // 若不停，重启后相机会仍绑在旧的 SurfaceTexture 上
        gl.stop()
        venc.stop()
        rtsp.stopServer()
        Log.i(TAG, "管线已停止")
    }

    // 当前使用的相机 ID
    fun curcam(): String = camId

    // 管线是否运行中
    fun isrun(): Boolean = running

    // RTSP 服务是否已就绪
    fun issrvup(): Boolean = srvUp

    // 画面需顺时针旋转的角度。
    // vrot >= 0 时强制使用该值；否则由传感器方向推算（实测本机 HAL 不支持旋转）。
    fun rotdeg(id: String): Int {
        if (vrot >= 0) return vrot
        return try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val o = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            // GL 经 SurfaceTexture 变换矩阵已处理传感器朝向，此处仅补足残余角度。
            // 实证：前摄传感器 270° 时需补 180°（已目视确证），据此推得该式。
            (o - 90 + 360) % 360
        } catch (e: Exception) {
            Log.w(TAG, "读取相机 $id 方向失败: ${e.message}")
            0
        }
    }

    // 当前编码尺寸：与设定分辨率一致
    fun cursize(): Pair<Int, Int> = Pair(vw, vh)

    // 枚举厂商公开的相机，返回 (ID, 显示名)
    //
    // 只列出 cameraIdList 中的相机：实测被 MIUI 隐藏的副摄（ID 2..7）虽能读到参数，
    // 但 HAL 拒绝供流（Buffer/Metadata error 后断开），列出来只会造成误点，故不展示。
    fun lstcams(): List<Pair<String, String>> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf<Pair<String, String>>()
        for (id in mgr.cameraIdList) {
            try {
                val ch = mgr.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1
                val model = modname(facing)
                val res = try {
                    cmgr.getCameraResolutions(id)
                } catch (_: Exception) {
                    emptyArray()
                }
                val maxRes = res.maxByOrNull { it.width * it.height }
                val maxTxt = maxRes?.let { "${it.width}x${it.height}" } ?: "?"
                // 用 | 分隔主标题与次级信息，便于界面分两行排版
                out.add(id to "CAM$id · $model|最高 $maxTxt · 旋转 ${rotdeg(id)}°")
            } catch (_: Exception) {
                // 该 ID 不可访问，跳过
            }
        }
        return out
    }

    // 取传感器型号：从厂商属性中按镜头朝向匹配对应条目
    private fun modname(facing: Int): String {
        val key = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front_main" else "back_main"
        val raw = sysprop("persist.vendor.camera.mi.module.info") + ";" +
                sysprop("persist.vendor.camera.mi.module.infoext")
        for (seg in raw.split(";")) {
            val kv = seg.split("=")
            if (kv.size == 2 && kv[0].trim() == key) {
                return kv[1].trim().removeSuffix("_i").uppercase()
            }
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
    }

    // 读取系统属性（属隐藏 API，经反射调用）
    private fun sysprop(k: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            (m.invoke(null, k) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // 查询指定相机是否支持目标分辨率（切换前校验，避免会话创建失败）
    fun supres(id: String): Boolean =
        cmgr.getCameraResolutions(id).any { it.width == vw && it.height == vh }

    // 列出指定相机支持的分辨率，去重后按像素数降序，供界面选择器使用
    fun lstres(id: String): List<String> {
        return try {
            cmgr.getCameraResolutions(id)
                .map { "${it.width}x${it.height}" }
                .distinct()
                .sortedByDescending { s ->
                    val p = s.split("x")
                    (p.getOrNull(0)?.toIntOrNull() ?: 0) * (p.getOrNull(1)?.toIntOrNull() ?: 0)
                }
        } catch (e: Exception) {
            Log.w(TAG, "分辨率列表读取失败: ${e.message}")
            listOf("${vw}x${vh}")
        }
    }

    // 诊断：记录公开相机列表、各相机的物理镜头 ID 与支持的分辨率
    // 同时写入文件，避免被系统日志刷屏冲掉
    fun diagcams() {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        fun w(s: String) {
            Log.i(TAG, s)
            sb.append(s).append('\n')
        }

        w("=== 相机诊断开始 ===")
        w("公开 cameraIdList = ${mgr.cameraIdList.toList()}")
        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val phys = try {
                ch.physicalCameraIds.toList()
            } catch (e: Exception) {
                listOf("读取失败: ${e.message}")
            }
            w("相机 $id: facing=$facing, 物理镜头=$phys")
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION)
            w("相机 $id SENSOR_ORIENTATION=$orient 建议旋转=${rotdeg(id)}°")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rc = ch.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                w("相机 $id 可用旋转裁剪模式=${rc?.toList()}")
            }
            try {
                val res = cmgr.getCameraResolutions(id)
                val has1080 = res.any { it.width == 1920 && it.height == 1080 }
                w("相机 $id 分辨率数=${res.size}, 支持1080p=$has1080")
            } catch (e: Exception) {
                w("相机 $id 分辨率查询失败: ${e.message}")
            }
            try {
                val fpsList = cmgr.getSupportedFps(null, CameraHelper.Facing.BACK)
                w("相机 $id 可用帧率范围=$fpsList")
            } catch (e: Exception) {
                w("相机 $id 帧率查询失败: ${e.message}")
            }
        }
        try {
            w("getPhysicalCamerasAvailable() = ${cmgr.getPhysicalCamerasAvailable()}")
        } catch (e: Exception) {
            w("getPhysicalCamerasAvailable 失败: ${e.message}")
        }

        w("--- 逐 ID 探测 ---")
        for (i in 0..8) {
            val sid = i.toString()
            try {
                val ch = mgr.getCameraCharacteristics(sid)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                val res = cmgr.getCameraResolutions(sid)
                w("ID $sid: 可访问 ✅ facing=$facing 分辨率数=${res.size} 旋转=${rotdeg(sid)}°")
            } catch (e: Exception) {
                w("ID $sid: 不可访问 ❌ ${e.javaClass.simpleName}")
            }
        }

        w("=== 相机诊断结束 ===")

        try {
            val f = java.io.File(ctx.getExternalFilesDir(null), "camdiag.txt")
            f.writeText(sb.toString())
            Log.i(TAG, "诊断已写入: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "诊断写文件失败: ${e.message}")
        }
    }

    // ===== GetVideoData 回调：编码器 → RTSP =====

    // 编码参数集就绪（H.265 含 VPS/SPS/PPS）；此处才是启动 RTSP 服务的正确时机
    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        rtsp.setVideoInfo(sps, pps, vps)
        if (!srvUp) {
            srvUp = true
            rtsp.startServer()
            Log.i(TAG, "参数集就绪，RTSP 服务已启动: 端口=$port")
        }
    }

    // 编码后的视频帧
    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        rtsp.sendVideo(videoBuffer, info)
    }

    // 编码输出格式变化
    override fun onVideoFormat(mediaFormat: MediaFormat) {}
}
