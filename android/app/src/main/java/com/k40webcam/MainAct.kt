package com.k40webcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.net.NetworkInterface

/**
 * 主界面：上方自适应比例的监看预览，下方为分页。
 *
 * 分页划分（相机选择本身也是参数，不是独立列表页）：
 *  - 参数：相机、输出分辨率、旋转角度、码率、编码类型，全部为可自由调节的控件
 *  - 预设：把整套参数存为卡片，点击即应用
 *
 * 视觉严格遵循项目根目录 DESIGN.md：纯黑底、哑光橙描边、统一 6dp 圆角。
 */
class MainAct : Activity() {

    private companion object {
        const val TAG = "MainAct"
        const val REQ_CAM = 100

        const val TAB_PAR = 0
        const val TAB_PST = 1

        // 参数控件的可选项
        val ROTS = listOf("自动（按传感器）", "0°", "90°", "180°", "270°")
        val CODECS = listOf("H.265 / HEVC", "H.264 / AVC")
    }

    private var pipe: CamPipe? = null
    private var autoDone = false
    private var tab = TAB_PAR

    private lateinit var pvwBox: FrameLayout
    private lateinit var pvw: SurfaceView
    private lateinit var statusTx: TextView
    private lateinit var content: LinearLayout
    private lateinit var tabBtns: MutableList<TextView>

    // 参数控件，切换分页时需保留状态
    private var camSpin: Spinner? = null
    private var resSpin: Spinner? = null
    private var rotSpin: Spinner? = null
    private var cdcSpin: Spinner? = null
    private var brSeek: SeekBar? = null
    private var brTx: TextView? = null
    private var camIds: List<String> = emptyList()

    private val cBg by lazy { getColor(R.color.bg) }
    private val cSurf by lazy { getColor(R.color.surface) }
    private val cLine by lazy { getColor(R.color.line) }
    private val cAcc by lazy { getColor(R.color.accent) }
    private val cTx by lazy { getColor(R.color.text) }
    private val cTx2 by lazy { getColor(R.color.text_2) }
    private val cTx3 by lazy { getColor(R.color.text_3) }

    // 界面初始化：保持常亮、构建控件、申请权限
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildui())

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        } else {
            refr()
        }
    }

    // 权限回调
    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CAM) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) refr()
            else statusTx.text = "未获得相机权限"
        }
    }

    // 界面销毁时不停止管线：管线由前台服务持有，需在后台持续推流
    override fun onDestroy() {
        super.onDestroy()
        pipe = null
    }

    // dp 转像素
    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // 统一 6dp 圆角，激活态用强调色描边（DESIGN.md 第 5、6 节）
    private fun bgbox(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(cSurf)
        setStroke(dp(if (active) 2 else 1), if (active) cAcc else cLine)
    }

    // 构建整体界面
    private fun buildui(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // 监看预览：不经编码、不占网络，为最低开销的监看方式
        pvwBox = FrameLayout(this).apply { setBackgroundColor(cBg) }
        pvw = SurfaceView(this)
        pvwBox.addView(
            pvw,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            pvwBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
        )
        pvw.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = bindpvw(h)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = bindpvw(h)
            override fun surfaceDestroyed(h: SurfaceHolder) {
                pipe?.detachpvw()
            }
        })

        // 状态条
        statusTx = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(cTx2)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(statusTx)

        // 分页标签
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        tabBtns = mutableListOf()
        for ((i, name) in listOf("参数", "预设").withIndex()) {
            val t = TextView(this).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { swtab(i) }
            }
            tabBtns.add(t)
            tabBar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabBar)

        // 分页内容
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        return root
    }

    // 绑定预览输出到 GL
    private fun bindpvw(h: SurfaceHolder) {
        pipe?.let { if (it.glready()) it.attachpvw(h.surface) }
    }

    // 切换分页，带 180ms 淡入（DESIGN.md 7.5：允许简单过渡）
    private fun swtab(i: Int) {
        tab = i
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(180).start()
        refr()
    }

    // 刷新状态、预览比例、分页样式与当前分页内容
    private fun refr() {
        val p = pipe ?: CamPipe(this, Cfg.PORT).also {
            pipe = it
            CamSrv.start(this, it)
        }

        if (!autoDone) {
            autoDone = true
            p.diagcams()
            if (!p.isrun()) {
                val target = if (p.supres(Cfg.DEFCAM)) Cfg.DEFCAM
                else p.lstcams().firstOrNull { p.supres(it.first) }?.first
                target?.let { runjob { p.strtpipe(it) } }
            }
        }

        // 状态条
        val c = p.cfgnow()
        statusTx.text = buildString {
            append("rtsp://").append(getip()).append(':').append(Cfg.PORT).append('\n')
            append(if (p.isrun()) "推流中" else "已停止")
            append(" · CAM").append(p.curcam())
            append(" · ").append(c[0]).append('x').append(c[1]).append('@').append(c[2])
            append(" · ").append(c[3]).append("Mbps · ").append(if (c[5] == 0) "H.265" else "H.264")
        }

        // 预览比例：按输出流宽高设定。旋转只改画面内容朝向，不改流分辨率，
        // 故此处不做宽高互换；并限制高度上限，保证下方分页始终可见。
        pvwBox.post {
            val wpx = pvwBox.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            var hpx = (wpx.toLong() * c[1] / c[0]).toInt()
            val cap = (resources.displayMetrics.heightPixels * 0.4).toInt()
            if (hpx > cap) hpx = cap
            if (hpx < dp(120)) hpx = dp(120)
            pvwBox.layoutParams = pvwBox.layoutParams.apply { height = hpx }
            pvwBox.requestLayout()
        }

        // 分页标签样式
        for ((i, t) in tabBtns.withIndex()) {
            t.setTextColor(if (i == tab) cAcc else cTx2)
            t.background = if (i == tab) bgbox(true) else null
        }

        content.removeAllViews()
        when (tab) {
            TAB_PAR -> mkpartab(p)
            TAB_PST -> mkpsttab(p)
        }

        // 管线可能在重启后使预览失效，重新绑定
        if (p.isrun() && p.glready()) {
            try {
                p.attachpvw(pvw.holder.surface)
            } catch (_: Exception) {
            }
        }
    }

    // 参数分页：相机、分辨率、旋转、码率、编码类型，全部可自由调节
    private fun mkpartab(p: CamPipe) {
        val c = p.cfgnow()

        // 相机：作为参数项，用下拉选择
        content.addView(lbl("相机"))
        val cams = p.lstcams()
        camIds = cams.map { it.first }
        val camNames = cams.map { it.second.substringBefore("|") }
        camSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainAct, android.R.layout.simple_spinner_dropdown_item, camNames
            )
            val idx = camIds.indexOf(p.curcam())
            if (idx >= 0) setSelection(idx)
        }
        content.addView(camSpin, spinlp())

        // 输出分辨率：取当前相机支持的分辨率
        content.addView(lbl("输出分辨率"))
        val resList = try {
            p.lstres(p.curcam())
        } catch (_: Exception) {
            listOf("${c[0]}x${c[1]}")
        }
        resSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainAct, android.R.layout.simple_spinner_dropdown_item, resList
            )
            val idx = resList.indexOf("${c[0]}x${c[1]}")
            if (idx >= 0) setSelection(idx)
        }
        content.addView(resSpin, spinlp())

        // 旋转角度
        content.addView(lbl("旋转角度"))
        rotSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainAct, android.R.layout.simple_spinner_dropdown_item, ROTS
            )
            val cur = if (Cfg.ROT < 0 && c[4] == 0) 0 else ROTS.indexOf("${c[4]}°")
            if (cur >= 0) setSelection(cur)
        }
        content.addView(rotSpin, spinlp())

        // 编码类型
        content.addView(lbl("编码类型"))
        cdcSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainAct, android.R.layout.simple_spinner_dropdown_item, CODECS
            )
            setSelection(c[5].coerceIn(0, 1))
        }
        content.addView(cdcSpin, spinlp())

        // 码率：连续可调，不是固定档位
        content.addView(lbl("码率"))
        brTx = TextView(this).apply {
            text = "${c[3]} Mbps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(cAcc)
        }
        content.addView(brTx)
        brSeek = SeekBar(this).apply {
            max = 49
            progress = (c[3] - 1).coerceIn(0, 49)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    brTx?.text = "${v + 1} Mbps"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        content.addView(brSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(TextView(this).apply {
            text = "1 Mbps ————————— 50 Mbps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cTx3)
        })

        // 应用：重建管线使全部参数生效
        content.addView(mkbtn("应用参数", true) {
            val cid = camIds.getOrNull(camSpin?.selectedItemPosition ?: 0) ?: p.curcam()
            val res = resSpin?.selectedItem?.toString() ?: "${c[0]}x${c[1]}"
            val parts = res.split("x")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: c[0]
            val h = parts.getOrNull(1)?.toIntOrNull() ?: c[1]
            val rotSel = rotSpin?.selectedItemPosition ?: 0
            val rot = if (rotSel == 0) -1 else listOf(0, 0, 90, 180, 270)[rotSel]
            val mbps = (brSeek?.progress ?: 24) + 1
            val cdc = cdcSpin?.selectedItemPosition ?: 0
            runjob { p.reconf(cid, w, h, Cfg.FPS, mbps, rot, cdc) }
        })
    }

    // 预设分页：卡片形式，点击即应用整套参数
    private fun mkpsttab(p: CamPipe) {
        content.addView(mkbtn("把当前设定存为预设", false) { savepst(p) })

        val list = PstStore.lst(this)
        if (list.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "暂无预设。在「参数」页调好后点上方按钮保存。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(cTx3)
                setPadding(0, dp(16), 0, 0)
            })
            return
        }

        val c = p.cfgnow()
        content.addView(lbl("预设"))
        for (ps in list) {
            val active = ps.camId == p.curcam() && ps.w == c[0] && ps.h == c[1] &&
                    ps.mbps == c[3] && ps.cdc == c[5]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bgbox(active)
                setPadding(dp(12), dp(14), dp(12), dp(14))
                setOnClickListener {
                    runjob { p.reconf(ps.camId, ps.w, ps.h, ps.fps, ps.mbps, ps.rot, ps.cdc) }
                }
            }
            card.addView(TextView(this).apply {
                text = ps.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(cTx)
            })
            card.addView(TextView(this).apply {
                text = "CAM${ps.camId} · ${ps.summ()}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(cTx3)
            })
            card.addView(TextView(this).apply {
                text = "删除"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(cTx2)
                setPadding(0, dp(8), 0, 0)
                setOnClickListener {
                    PstStore.del(this@MainAct, ps.name)
                    refr()
                }
            })
            content.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
    }

    // 把当前设定存为预设，名称取参数摘要
    private fun savepst(p: CamPipe) {
        val c = p.cfgnow()
        val name = "CAM${p.curcam()} ${c[0]}x${c[1]} ${c[3]}M ${if (c[5] == 0) "H265" else "H264"}"
        PstStore.put(this, Preset(name, p.curcam(), c[0], c[1], c[2], c[3], c[4], c[5]))
        refr()
    }

    // 下拉控件的统一布局参数
    private fun spinlp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(16) }

    // 分组小标题
    private fun lbl(s: String): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(cTx3)
        setPadding(0, dp(8), 0, dp(8))
    }

    // 统一样式按钮：主操作用强调色描边
    private fun mkbtn(s: String, primary: Boolean, f: () -> Unit): Button = Button(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(if (primary) cAcc else cTx)
        background = bgbox(primary)
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) }
    }

    // 在后台线程执行管线操作，完成后回主线程刷新
    private fun runjob(f: () -> Unit) {
        statusTx.text = "处理中…"
        Thread {
            try {
                f()
            } catch (e: Exception) {
                Log.e(TAG, "操作失败", e)
            }
            runOnUiThread { refr() }
        }.start()
    }

    // 取本机第一个非回环 IPv4 地址
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
