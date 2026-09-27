package com.k40webcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.rtspserver.server.RtspServer
import java.nio.ByteBuffer

/**
 * 相机管线：Camera2 直连 MediaCodec 输入 Surface（零拷贝，不经 OpenGL），
 * 编码为 H.265 后交由 RtspServer 分发。
 *
 * 关键设计一：MediaCodec 的输入 Surface 作为稳定锚点，切换相机时仅重绑相机，
 * 编码器与 Surface 全程不重建，从而同时取得最低占用与快速切换。
 *
 * 关键设计二：RtspServer.startServer() 内部仅等待 5 秒的视频参数集（CSD），
 * 超时则不会创建监听。故必须先启动编码器与相机，待 onVideoInfo 回调拿到
 * VPS/SPS/PPS 后再启动 RTSP 服务，否则端口不会监听。
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

    // 相机状态回调
    private val camCb = object : CameraCallbacks {
        override fun onCameraChanged(facing: CameraHelper.Facing) {
            Log.i(TAG, "相机已切换: $facing")
        }

        override fun onCameraError(error: String) {
            Log.e(TAG, "相机错误: $error")
        }

        override fun onCameraOpened() {
            Log.i(TAG, "相机已打开")
        }

        override fun onCameraDisconnected() {
            Log.w(TAG, "相机已断开")
        }
    }

    private val venc = VideoEncoder(this)
    private val cmgr = Camera2ApiManager(ctx)
    private val rtsp = RtspServer(connChk, port)

    @Volatile
    private var running = false

    // RTSP 服务是否已启动，避免重复启动
    @Volatile
    private var srvUp = false

    private var camId = "0"

    // 启动管线：先启动编码器与相机，RTSP 服务待参数集就绪后自动开启
    fun strtpipe(id: String): Boolean {
        if (running) return true
        camId = id
        srvUp = false

        venc.type = VideoCodec.H265
        val ok = venc.prepareVideoEncoder(
            Cfg.W, Cfg.H, Cfg.FPS, Cfg.BRATE, 0, Cfg.IFRM, FormatVideoEncoder.SURFACE
        )
        if (!ok) {
            Log.e(TAG, "编码器初始化失败，设备可能不支持 H.265")
            return false
        }
        // 注意：必须调用无参 start()，它经由 BaseEncoder.start(long) 触发 initCodec()，
        // 而 codec.start() 正是在 initCodec() 中执行。直接调用 start(boolean) 只设标志位，
        // 编码器不会真正启动。
        venc.start()

        // 此处不启动 RTSP：须等编码器输出 CSD，见 onVideoInfo
        rtsp.setVideoCodec(VideoCodec.H265)
        // 本管线仅视频，须显式关闭音频，否则 SDP 会广播一条永不供数的音轨，
        // 导致拉流端（如 ffmpeg）阻塞等待音频而收不到画面
        rtsp.setOnlyVideo(true)

        cmgr.setCameraCallbacks(camCb)
        cmgr.prepareCamera(venc.inputSurface, Cfg.FPS)
        cmgr.openCameraId(id)

        running = true
        Log.i(TAG, "管线已启动: 相机=$id 端口=$port（等待参数集）")
        return true
    }

    // 切换相机：保留编码器与输入 Surface，仅重绑相机，随后强制关键帧供对端立即解码
    fun switcam(id: String) {
        if (!running) return
        val surf = venc.inputSurface ?: run {
            Log.e(TAG, "输入 Surface 为空，无法切换")
            return
        }
        camId = id
        cmgr.closeCamera(false)
        cmgr.prepareCamera(surf, Cfg.FPS)
        cmgr.setCameraId(id)
        cmgr.openCameraId(id)
        venc.requestKeyframe()
        Log.i(TAG, "已切换到相机 $id")
    }

    // 停止管线并释放全部资源
    fun stppipe() {
        if (!running) return
        running = false
        srvUp = false
        cmgr.closeCamera(true)
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

    // 枚举设备上全部相机，返回 (ID, 显示名)
    //
    // 注意：MIUI 会把副摄（超广角/微距）从 cameraIdList 中隐藏，仅暴露逻辑主摄与前置。
    // 但这些 ID 仍可被 getCameraCharacteristics/openCamera 访问，故此处直接探测
    // 0..8 号 ID，把可访问的全部列出，以达成「自由调用所有摄像头」。
    fun lstcams(): List<Pair<String, String>> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf<Pair<String, String>>()
        val listed = mgr.cameraIdList.toSet()

        for (i in 0..8) {
            val id = i.toString()
            try {
                val ch = mgr.getCameraCharacteristics(id)
                val face = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                    else -> "未知"
                }
                // 用最高分辨率与支持数辅助区分副摄
                val res = try {
                    cmgr.getCameraResolutions(id)
                } catch (_: Exception) {
                    emptyArray()
                }
                val maxRes = res.maxByOrNull { it.width * it.height }
                val maxTxt = maxRes?.let { "${it.width}x${it.height}" } ?: "?"
                // 逻辑相机（公开列出）与隐藏副摄分别标注
                val tag = if (id in listed) "逻辑" else "副摄"
                out.add(id to "相机 $id · $face · $tag · 最高$maxTxt")
            } catch (_: Exception) {
                // 该 ID 不可访问，跳过
            }
        }

        // 若探测范围内一个都没找到，退回官方列表，避免界面空白
        if (out.isEmpty()) {
            for (id in listed) out.add(id to "相机 $id")
        }
        return out
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
            // 传感器方向与可用旋转模式，用于修正画面朝向
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION)
            w("相机 $id SENSOR_ORIENTATION=$orient")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rc = ch.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                w("相机 $id 可用旋转裁剪模式=${rc?.toList()}")
            }
            try {
                val res = cmgr.getCameraResolutions(id)
                val has1080 = res.any { it.width == 1920 && it.height == 1080 }
                val has4k = res.any { it.width == 3840 && it.height == 2160 }
                w("相机 $id 分辨率数=${res.size}, 支持1080p=$has1080, 支持4K=$has4k")
                w("相机 $id 前10个分辨率=${res.take(10).joinToString()}")
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

        // 决定性测试：逐个探测 0-7 号 ID，确认副摄是否可被第三方打开
        w("--- 逐 ID 探测（验证副摄能否绕过 cameraIdList 访问）---")
        for (i in 0..7) {
            val sid = i.toString()
            try {
                val ch = mgr.getCameraCharacteristics(sid)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                val res = cmgr.getCameraResolutions(sid)
                w("ID $sid: 可访问 ✅ facing=$facing 分辨率数=${res.size}")
            } catch (e: Exception) {
                w("ID $sid: 不可访问 ❌ ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        w("=== 相机诊断结束 ===")

        // 落盘，供 adb 拉取
        try {
            val f = java.io.File(ctx.getExternalFilesDir(null), "camdiag.txt")
            f.writeText(sb.toString())
            Log.i(TAG, "诊断已写入: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "诊断写文件失败: ${e.message}")
        }
    }

    // 查询指定相机是否支持目标分辨率（切换前校验，避免会话创建失败）
    fun supres(id: String): Boolean =
        cmgr.getCameraResolutions(id).any { it.width == Cfg.W && it.height == Cfg.H }

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
