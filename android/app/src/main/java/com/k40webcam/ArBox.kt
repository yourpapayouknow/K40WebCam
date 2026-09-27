package com.k40webcam

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 按视频宽高比测量自身的容器。
 *
 * 做法与 androidx.media3 的 AspectRatioFrameLayout 一致：**容器负责锁定比例**，
 * 只约束一个维度，另一维由视频比例推导，然后 setMeasuredDimension；
 * 子视图（SurfaceView）再以 EXACTLY 填满该尺寸，从而其 Surface 比例与视频一致。
 *
 * 参考实现：
 *  - androidx/media3  AspectRatioFrameLayout.onMeasure（Android 官方标准）
 *  - alexeyvasilyev/rtsp-client-android  LiveScreen：容器 aspectRatio + 内层 fillMaxSize
 *
 * 关键点：比例取自**实际视频流的解析度**，未就绪时用 16:9 兜底；
 * 且**不因画面旋转而互换** —— 旋转只改变画面内容朝向，不改变流的分辨率比例。
 */
class ArBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // 视频宽高比（宽 / 高）。<=0 表示未设置，此时不做干预。
    var ar: Float = 16f / 9f
        set(v) {
            if (v > 0f && v != field) {
                field = v
                requestLayout()
            }
        }

    // 高度上限（像素），0 表示不限制。用于避免监看区挤占下方设置区。
    var maxH: Int = 0
        set(v) {
            if (v != field) {
                field = v
                requestLayout()
            }
        }

    // 按比例测量自身，再让子视图精确填满
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (maxH > 0 && (availH <= 0 || maxH < availH)) maxH else availH

        // 先铺满可用宽度，再由比例推高度
        var w = availW
        var h = if (ar > 0f) (w / ar).toInt() else cap

        // 高度受限时改为由高度反推宽度，保证绝不超出
        if (cap > 0 && h > cap) {
            h = cap
            w = (h * ar).toInt()
        }

        setMeasuredDimension(w, h)

        // 子视图以精确尺寸填满，使其 Surface 与视频比例一致
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(cw, ch)
        }
    }
}
