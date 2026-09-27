package com.k40webcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.NetworkInterface

/**
 * 主界面：申请相机权限、列出全部相机、启动与切换推流。
 * 界面以代码构建，避免引入布局资源与额外依赖。
 */
class MainAct : Activity() {

    private companion object {
        const val TAG = "MainAct"
        const val REQ_CAM = 100
    }

    private var pipe: CamPipe? = null
    private var autoDone = false
    private lateinit var status: TextView
    private lateinit var camBox: LinearLayout

    // 界面初始化：申请权限并构建控件
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持屏幕常亮，避免测试期间被系统节流
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildui())

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        } else {
            refrcams()
        }
    }

    // 权限申请结果回调
    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CAM) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                refrcams()
            } else {
                status.text = "未获得相机权限，无法工作"
            }
        }
    }

    // 界面销毁时不停止管线：管线由前台服务持有，需在后台持续推流
    override fun onDestroy() {
        super.onDestroy()
        pipe = null
    }

    // 构建界面控件
    private fun buildui(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            text = "准备中…"
        }
        root.addView(status)

        camBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(camBox)

        return ScrollView(this).apply { addView(root) }
    }

    // 刷新状态显示与相机按钮列表
    private fun refrcams() {
        val p = pipe ?: CamPipe(this, Cfg.PORT).also {
            pipe = it
            // 交由前台服务持有，使管线在界面退到后台后继续运行
            CamSrv.start(this, it)
        }

        // 首次进入时打印完整相机诊断，便于排查副摄可访问性
        if (!autoDone) p.diagcams()

        status.text = buildString {
            append("地址: rtsp://").append(getip()).append(':').append(Cfg.PORT).append('\n')
            append("分辨率: ").append(Cfg.W).append('x').append(Cfg.H)
            append(" @ ").append(Cfg.FPS).append("fps  ")
            append(Cfg.BRATE / 1_000_000).append("Mbps H.265\n")
            append("当前: ").append(if (p.isrun()) "运行中 · 相机 ${p.curcam()}" else "已停止")
        }

        camBox.removeAllViews()
        for ((id, label) in p.lstcams()) {
            val ok = p.supres(id)
            val btn = Button(this).apply {
                text = if (ok) label else "$label（不支持 ${Cfg.W}x${Cfg.H}）"
                isEnabled = ok
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { oncam(id) }
            }
            camBox.addView(btn)
        }

        // 首次进入时自动开始推流，使本应用可作为常驻服务直接工作
        if (!autoDone) {
            autoDone = true
            if (!p.isrun()) {
                val target = if (p.supres(Cfg.DEFCAM)) Cfg.DEFCAM
                else p.lstcams().firstOrNull { p.supres(it.first) }?.first
                target?.let { oncam(it) }
            }
        }
    }

    // 点击相机按钮：未运行则启动，已运行则切换
    private fun oncam(id: String) {
        val p = pipe ?: return
        try {
            if (!p.isrun()) {
                if (p.strtpipe(id)) refrcams() else status.text = "启动失败，请查看日志"
            } else if (p.curcam() != id) {
                p.switcam(id)
                refrcams()
            }
        } catch (e: Exception) {
            Log.e(TAG, "操作失败", e)
            status.text = "操作失败: ${e.message}"
        }
    }

    // 获取本机第一个非回环 IPv4 地址，用于展示 RTSP 地址
    private fun getip(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}
