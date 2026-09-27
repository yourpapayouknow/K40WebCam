package com.k40webcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 前台服务：唯一职责是让进程长期驻留，避免应用退到后台后管线被系统回收。
 * 管线实例由界面创建后交由此处持有，服务本身不做取景与网络工作。
 */
class CamSrv : Service() {

    companion object {
        private const val TAG = "CamSrv"
        private const val CHID = "k40webcam"
        private const val NID = 1001

        // 由界面创建后交付的管线实例，供服务在通知中展示状态
        var pipe: CamPipe? = null

        // 启动前台服务并把管线交给它持有
        fun start(ctx: Context, p: CamPipe) {
            pipe = p
            val it = Intent(ctx, CamSrv::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(it)
            } else {
                ctx.startService(it)
            }
        }

        // 通知管线停止并结束服务
        fun stop(ctx: Context) {
            pipe = null
            ctx.stopService(Intent(ctx, CamSrv::class.java))
        }
    }

    // 服务创建时建立通知渠道（Android 8+ 必需）
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHID, "相机推流", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    // 进入前台，附带常驻通知
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "前台服务启动")
        startForeground(NID, mknoti())
        // 被系统回收后自动重建，保证长时间可用
        return START_STICKY
    }

    // 服务销毁时停掉管线，避免相机句柄泄漏
    override fun onDestroy() {
        Log.i(TAG, "前台服务停止")
        pipe?.stppipe()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 构造常驻通知
    private fun mknoti(): Notification {
        val p = pipe
        val txt = if (p != null && p.isrun()) {
            "推流中 · 相机 ${p.curcam()} · 端口 ${Cfg.PORT}"
        } else {
            "待机中"
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("K40 WebCam")
            .setContentText(txt)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
