package com.k40webcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import java.net.NetworkInterface

/**
 * 主界面：导播级双监视窗（左侧 PVW 预监，右侧 PGM 直播主输出）+ CUT 切换上屏 + 紧凑双列控制网格。
 *
 * 视觉严格遵循项目根目录 DESIGN.md：纯黑底、哑光橙描边、统一 6dp 圆角。
 */
class MainAct : Activity() {

    private companion object {
        const val TAG = "MainAct"
        const val REQ_CAM = 100

        const val TAB_PAR = 0
        const val TAB_PST = 1

        val ROTS = listOf("自动", "0°", "90°", "180°", "270°")
        val CODECS = listOf("H.265", "H.264")
    }

    private var pipe: CamPipe? = null
    private var autoDone = false
    private var tab = TAB_PAR

    // 监看视窗：合并单视窗，平时显示 PGM，调参即刻显示 PVW，CUT 后切回 PGM
    private lateinit var monitorCard: LinearLayout
    private lateinit var monitorBox: AspectRatioFrameLayout
    private lateinit var monitorSurface: SurfaceView
    private lateinit var modeBadge: TextView
    private lateinit var modeSubTx: TextView
    private var showingPvw = false

    // 导播控制与状态条
    private lateinit var statusTx: TextView
    private lateinit var cutHintTx: TextView
    private lateinit var cutBtn: Button

    private lateinit var content: LinearLayout
    private lateinit var tabBtns: MutableList<TextView>

    // 导播 Staging (预监) 参数状态
    private var stgCam = Cfg.DEFCAM
    private var stgW = Cfg.W
    private var stgH = Cfg.H
    private var stgRot = Cfg.ROT
    private var stgCdc = 0
    private var stgMbps = Cfg.BRATE / 1_000_000
    private var stgInited = false

    // 参数控件
    private var camSpin: Spinner? = null
    private var resSpin: Spinner? = null
    private var rotSpin: Spinner? = null
    private var cdcSpin: Spinner? = null
    private var brSeek: SeekBar? = null
    private var brTx: TextView? = null
    private var camIds: List<String> = emptyList()
    private var suppressEvents = false

    private fun <T> mkAdapter(items: List<T>): ArrayAdapter<T> {
        return object : ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(cTx)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                v.setPadding(dp(2), dp(4), dp(2), dp(4))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(cTx)
                v.setBackgroundColor(cSurf)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                v.setPadding(dp(12), dp(10), dp(12), dp(10))
                return v
            }
        }
    }

    private val cBg by lazy { getColor(R.color.bg) }
    private val cSurf by lazy { getColor(R.color.surface) }
    private val cLine by lazy { getColor(R.color.line) }
    private val cAcc by lazy { getColor(R.color.accent) }
    private val cTx by lazy { getColor(R.color.text) }
    private val cTx2 by lazy { getColor(R.color.text_2) }
    private val cTx3 by lazy { getColor(R.color.text_3) }
    private val cPgmRed = Color.parseColor("#E53935")
    private val cPvwGreen = Color.parseColor("#2E7D32")

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

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CAM) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) refr()
            else statusTx.text = "未获得相机权限"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pipe = null
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun bgbox(active: Boolean, customBorderColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(cSurf)
            val strokeColor = customBorderColor ?: if (active) cAcc else cLine
            setStroke(dp(if (active) 2 else 1), strokeColor)
        }

    // 构建整体界面
    private fun buildui(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // 1. 紧凑状态条：置顶显示地址与连接状态
        statusTx = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cTx2)
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        root.addView(statusTx)

        // 2. 监看视窗：合并单视窗，平时显示 PGM，调参即刻显示 PVW，CUT 后切回 PGM
        monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgbox(true, cPgmRed)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val monitorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        modeBadge = TextView(this).apply {
            text = "PGM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(cPgmRed)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        monitorHeader.addView(modeBadge)

        modeSubTx = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cTx2)
            setPadding(dp(8), 0, 0, 0)
        }
        monitorHeader.addView(modeSubTx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        monitorCard.addView(monitorHeader)

        val monitorH = (resources.displayMetrics.heightPixels * 0.30f).toInt()

        monitorBox = AspectRatioFrameLayout(this).apply {
            setBackgroundColor(cBg)
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }
        monitorSurface = SurfaceView(this)
        monitorBox.addView(
            monitorSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        monitorCard.addView(
            monitorBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                monitorH
            ).apply { gravity = Gravity.CENTER }
        )
        monitorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })


        val monitorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(monitorCard)
        }
        root.addView(monitorContainer)

        // 3. CUT 切换控制条：兼具导播切换与当前状态指示
        val cutBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        cutHintTx = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cTx3)
        }
        cutBar.addView(cutHintTx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        cutBtn = Button(this).apply {
            text = "CUT"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { execCut() }
        }
        cutBar.addView(cutBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(cutBar)

        // 4. 紧凑分页标签
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        tabBtns = mutableListOf()
        for ((i, name) in listOf("参数", "预设").withIndex()) {
            val t = TextView(this).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { swtab(i) }
            }
            tabBtns.add(t)
            tabBar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabBar)

        // 5. 分页内容
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        return root
    }

    // 设置监看视窗的宽高比
    private fun fitMonitor(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            monitorBox.setAspectRatio(w.toFloat() / h.toFloat())
        }
    }

    private fun swtab(i: Int) {
        tab = i
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(160).start()
        refr()
    }

    // 执行 CUT 切换，将预监参数真正推送到主路直播，并恢复显示 PGM
    private fun execCut() {
        val p = pipe ?: return
        showingPvw = false
        runOnUiThread { refrStaging(p) }
        runjob {
            p.reconf(stgCam, stgW, stgH, Cfg.FPS, stgMbps, stgRot, stgCdc)
            runOnUiThread { refrStaging(p) }
        }
    }

    // 预监状态更新：调参即刻显示 PVW，CUT 后切回 PGM，平时显示 PGM
    private fun refrStaging(p: CamPipe) {
        val c = p.cfgnow()
        val curRot = if (Cfg.ROT < 0 && c[4] == p.rotdeg(p.curcam())) -1 else c[4]
        val isStaged = (stgCam != p.curcam() || stgW != c[0] || stgH != c[1] ||
                stgRot != curRot || stgMbps != c[3] || stgCdc != c[5])

        showingPvw = isStaged

        val targetW = if (showingPvw) stgW else c[0]
        val targetH = if (showingPvw) stgH else c[1]

        if (showingPvw) {
            modeBadge.text = "PVW"
            modeBadge.setTextColor(Color.WHITE)
            modeBadge.background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(cPvwGreen)
            }
            monitorCard.background = bgbox(true, cPvwGreen)
            val cdcName = if (stgCdc == 1) "H.264" else "H.265"
            modeSubTx.text = "CAM$stgCam · ${stgW}×${stgH} · ${stgMbps}Mbps · $cdcName"

            cutHintTx.text = "预监已修改"
            cutHintTx.setTextColor(cAcc)
            cutBtn.setTextColor(Color.BLACK)
            cutBtn.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(cAcc)
            }
            cutBtn.text = "CUT"
        } else {
            modeBadge.text = "PGM"
            modeBadge.setTextColor(Color.WHITE)
            modeBadge.background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(cPgmRed)
            }
            monitorCard.background = bgbox(true, cPgmRed)
            val cdcName = if (c[5] == 1) "H.264" else "H.265"
            modeSubTx.text = "CAM${p.curcam()} · ${c[0]}×${c[1]} · ${c[3]}Mbps · $cdcName"

            cutHintTx.text = "已同步"
            cutHintTx.setTextColor(cTx3)
            cutBtn.setTextColor(cTx3)
            cutBtn.background = bgbox(false)
            cutBtn.text = "已同步"
        }

        fitMonitor(targetW, targetH)

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

        val c = p.cfgnow()
        if (!stgInited) {
            stgInited = true
            stgCam = p.curcam()
            stgW = c[0]
            stgH = c[1]
            stgRot = Cfg.ROT
            stgMbps = c[3]
            stgCdc = c[5]
        }

        // 状态条：仅展示核心 RTSP 地址与状态，不堆叠重复参数
        statusTx.text = "rtsp://${getip()}:${Cfg.PORT} · ${if (p.isrun()) "推流中" else "已停止"}"

        refrStaging(p)

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


    }

    // 参数分页：导播台紧凑双列网格排版
    private fun mkpartab(p: CamPipe) {
        val c = p.cfgnow()
        suppressEvents = true

        // ===== 紧凑网格第 1 行：[相机选择] 与 [输出分辨率] =====
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }

        // 列 1：相机
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col1.addView(lbl("相机"))
        val cams = p.lstcams()
        camIds = cams.map { it.first }
        val camNames = cams.map { "CAM${it.first}" }
        camSpin = Spinner(this).apply {
            adapter = mkAdapter(camNames)
            val idx = camIds.indexOf(stgCam)
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    val sel = camIds.getOrNull(pos) ?: return
                    if (sel != stgCam) {
                        stgCam = sel
                        refrStaging(p)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col1.addView(camSpin, spinlpCompact())
        row1.addView(col1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 列 2：分辨率
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        col2.addView(lbl("分辨率"))
        val resList = try {
            p.lstres(stgCam).map { it.replace("x", "×") }
        } catch (_: Exception) {
            listOf("${stgW}×${stgH}")
        }
        resSpin = Spinner(this).apply {
            adapter = mkAdapter(resList)
            val idx = resList.indexOf("${stgW}×${stgH}")
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    val s = resList.getOrNull(pos) ?: return
                    val parts = s.split("×", "x")
                    val w = parts.getOrNull(0)?.toIntOrNull() ?: stgW
                    val h = parts.getOrNull(1)?.toIntOrNull() ?: stgH
                    if (w != stgW || h != stgH) {
                        stgW = w
                        stgH = h
                        refrStaging(p)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col2.addView(resSpin, spinlpCompact())
        row1.addView(col2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row1)

        // ===== 紧凑网格第 2 行：[旋转校正] 与 [编码格式] =====
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }

        // 列 1：旋转
        val col3 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col3.addView(lbl("旋转"))
        rotSpin = Spinner(this).apply {
            adapter = mkAdapter(ROTS)
            val cur = if (stgRot < 0) 0 else when (stgRot) {
                0 -> 1
                90 -> 2
                180 -> 3
                270 -> 4
                else -> 0
            }
            if (cur >= 0) setSelection(cur)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    val rot = listOf(-1, 0, 90, 180, 270).getOrElse(pos) { -1 }
                    if (rot != stgRot) {
                        stgRot = rot
                        refrStaging(p)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col3.addView(rotSpin, spinlpCompact())
        row2.addView(col3, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 列 2：编码类型
        val col4 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        col4.addView(lbl("编码"))
        cdcSpin = Spinner(this).apply {
            adapter = mkAdapter(CODECS)
            setSelection(stgCdc.coerceIn(0, 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    if (pos != stgCdc) {
                        stgCdc = pos
                        refrStaging(p)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col4.addView(cdcSpin, spinlpCompact())
        row2.addView(col4, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row2)

        suppressEvents = false

        // ===== 紧凑网格第 3 行：[码率调节] =====
        val brHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        brHeader.addView(lbl("码率"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        brTx = TextView(this).apply {
            text = "$stgMbps Mbps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(cAcc)
        }
        brHeader.addView(brTx)
        content.addView(brHeader)

        brSeek = SeekBar(this).apply {
            max = 49
            progress = (stgMbps - 1).coerceIn(0, 49)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    stgMbps = v + 1
                    brTx?.text = "$stgMbps Mbps"
                    if (fromUser) refrStaging(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        content.addView(brSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    // 预设分页
    private fun mkpsttab(p: CamPipe) {
        content.addView(mkbtn("保存当前预设", false) { savepst(p) })

        val list = PstStore.lst(this)
        if (list.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "暂无预设。在「参数」页调整后保存。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(cTx3)
                setPadding(0, dp(12), 0, 0)
            })
            return
        }

        val c = p.cfgnow()
        content.addView(lbl("已存预设"))
        for (ps in list) {
            val active = ps.camId == p.curcam() && ps.w == c[0] && ps.h == c[1] &&
                    ps.mbps == c[3] && ps.cdc == c[5]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bgbox(active)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    stgCam = ps.camId
                    stgW = ps.w
                    stgH = ps.h
                    stgMbps = ps.mbps
                    stgRot = ps.rot
                    stgCdc = ps.cdc
                    execCut()
                }
            }
            card.addView(TextView(this).apply {
                text = ps.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(cTx)
            })
            val cdcName = if (ps.cdc == 0) "H.265" else "H.264"
            card.addView(TextView(this).apply {
                text = "CAM${ps.camId} · ${ps.w}×${ps.h} · ${ps.mbps}Mbps · $cdcName"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cTx3)
            })
            card.addView(TextView(this).apply {
                text = "删除"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cTx2)
                setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    PstStore.del(this@MainAct, ps.name)
                    refr()
                }
            })
            content.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    // 把当前设定存为预设
    private fun savepst(p: CamPipe) {
        val cdcName = if (stgCdc == 0) "H.265" else "H.264"
        val name = "CAM${stgCam} · ${stgW}×${stgH} · ${stgMbps}Mbps · $cdcName"
        PstStore.put(this, Preset(name, stgCam, stgW, stgH, Cfg.FPS, stgMbps, stgRot, stgCdc))
        refr()
    }

    private fun spinlpCompact(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(4) }

    private fun lbl(s: String): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(cTx3)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun mkbtn(s: String, primary: Boolean, f: () -> Unit): Button = Button(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (primary) cAcc else cTx)
        background = bgbox(primary)
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ).apply { topMargin = dp(12) }
    }

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

    private fun getip(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            for (itf in ifs) {
                if (!itf.isUp || itf.isLoopback) continue
                val addrs = itf.inetAddresses ?: continue
                for (a in addrs) {
                    if (a.isLoopbackAddress) continue
                    val h = a.hostAddress ?: continue
                    if (h.contains(':')) continue
                    return h
                }
            }
        } catch (_: Exception) {
        }
        return "0.0.0.0"
    }
}
