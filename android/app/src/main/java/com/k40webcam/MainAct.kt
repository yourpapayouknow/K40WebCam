package com.k40webcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

import android.content.Context
import android.graphics.Typeface
import android.widget.BaseAdapter
import android.widget.SpinnerAdapter
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
        const val TAB_ABT = 2

        val ROTS = listOf("自动", "0°", "90°", "180°", "270°")
        val CODECS = listOf("H.265", "H.264", "MJPEG HTTP")
    }

    private var pipe: CamPipe? = null
    private var autoDone = false
    private var tab = TAB_PAR

    // 监看视窗与参数叠加层
    private lateinit var monitorCard: LinearLayout
    private lateinit var monitorBox: AspectRatioFrameLayout
    private lateinit var monitorSurface: SurfaceView
    private lateinit var overlayBox: LinearLayout
    private lateinit var overlayUrlTx: TextView
    private lateinit var overlaySpecsTx: TextView
    private lateinit var overlayBtn: TextView
    private lateinit var toggleBtn: TextView
    private var isOverlayVisible = false
    private var lastAr = 0f

    // 状态条与内容区
    private lateinit var statusTx: TextView
    private lateinit var content: LinearLayout
    private lateinit var tabBtns: MutableList<TextView>

    // 参数控件
    private var camSpin: Spinner? = null
    private var resSpin: Spinner? = null
    private var rotSpin: Spinner? = null
    private var cdcSpin: Spinner? = null
    private var brSeek: SeekBar? = null
    private var brTx: TextView? = null
    private var camIds: List<String> = emptyList()
    private var suppressEvents = false

    data class ResItem(val text: String, val isHeader: Boolean, val w: Int = 0, val h: Int = 0)

    private class SectionedResAdapter(
        private val ctx: Context,
        private val items: List<ResItem>,
        private val cTx: Int,
        private val cTx3: Int,
        private val cSurf: Int,
        private val dpFn: (Int) -> Int
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ResItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun isEnabled(position: Int): Boolean = !items[position].isHeader
        override fun areAllItemsEnabled(): Boolean = false
        override fun getViewTypeCount(): Int = 1
        override fun getItemViewType(position: Int): Int = 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(cTx)
                setPadding(dpFn(2), dpFn(4), dpFn(2), dpFn(4))
            }
            tv.text = items[position].text
            return tv
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val tv = if (convertView is TextView && convertView.tag == item.isHeader) {
                convertView
            } else {
                TextView(ctx).apply {
                    tag = item.isHeader
                    if (item.isHeader) {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(cTx3)
                        setTypeface(null, Typeface.BOLD)
                        setBackgroundColor(cSurf)
                        setPadding(dpFn(10), dpFn(8), dpFn(10), dpFn(3))
                    } else {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(cTx)
                        setBackgroundColor(cSurf)
                        setPadding(dpFn(20), dpFn(8), dpFn(10), dpFn(8))
                    }
                }
            }
            tv.text = item.text
            return tv
        }
    }

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

        // 1. 状态条占位
        statusTx = TextView(this).apply {
            visibility = View.GONE
        }
        root.addView(statusTx)

        // 2. 监看视窗
        monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bgbox(true, cLine)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val monitorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val headerTitle = TextView(this).apply {
            text = "监看视窗"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cTx3)
            setPadding(dp(4), 0, 0, 0)
        }
        monitorHeader.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        overlayBtn = TextView(this).apply {
            text = "参数浮层"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = bgbox(false, cLine)
            setTextColor(cTx2)
            setOnClickListener {
                isOverlayVisible = !isOverlayVisible
                refr()
            }
        }
        monitorHeader.addView(overlayBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })

        toggleBtn = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                pipe?.let { p ->
                    runjob {
                        if (p.isrun()) {
                            p.stppipe()
                        } else {
                            p.strtpipe(p.curcam())
                        }
                    }
                }
            }
        }
        monitorHeader.addView(toggleBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        monitorCard.addView(monitorHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val monitorH = (resources.displayMetrics.heightPixels * 0.38f).toInt()

        monitorBox = AspectRatioFrameLayout(this).apply {
            setBackgroundColor(cBg)
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }
        monitorSurface = SurfaceView(this)
        monitorBox.addView(
            monitorSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
        )

        overlayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(205, 14, 14, 14))
                setStroke(dp(1), cLine)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
        }
        overlayUrlTx = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cAcc)
            setTypeface(null, Typeface.BOLD)
        }
        overlaySpecsTx = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(cTx)
            setPadding(0, dp(3), 0, 0)
        }
        overlayBox.addView(overlayUrlTx)
        overlayBox.addView(overlaySpecsTx)

        monitorBox.addView(
            overlayBox,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(8)
                leftMargin = dp(8)
            }
        )
        monitorCard.addView(
            monitorBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                monitorH
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        monitorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = bindpvw(h, monitorSurface.width, monitorSurface.height)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = bindpvw(h, w, ht)
            override fun surfaceDestroyed(h: SurfaceHolder) {
                pipe?.detachpvw()
            }
        })

        val monitorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(monitorCard)
        }
        root.addView(monitorContainer)


        // 4. 紧凑分页标签
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        tabBtns = mutableListOf()
        for ((i, name) in listOf("参数", "预设", "关于").withIndex()) {
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

    // 绑定监看输出并调整视窗比例
    private fun bindpvw(h: SurfaceHolder, w: Int, ht: Int) {
        val c = pipe?.cfgnow() ?: intArrayOf(Cfg.W, Cfg.H)
        fitpvw(c[0], c[1])
        pipe?.let { if (it.glready()) it.attachpvw(h.surface, w, ht) }
    }

    // 设置监看视窗宽高比：仅当比例发生实质性变化时才更新布局，同比例免刷新
    private fun fitpvw(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val ar = w.toFloat() / h.toFloat()
        if (lastAr > 0f && kotlin.math.abs(ar - lastAr) < 0.01f) {
            return
        }
        lastAr = ar
        monitorBox.setAspectRatio(ar)
        val screenW = resources.displayMetrics.widthPixels - dp(40)
        val maxH = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        val targetH = (screenW / ar).toInt().coerceAtMost(maxH)
        val lp = monitorBox.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            monitorBox.layoutParams = lp
        }

        val scale = 960f / maxOf(w, h)
        val pw = ((w * scale) / 2).toInt() * 2
        val ph = ((h * scale) / 2).toInt() * 2
        monitorSurface.holder.setFixedSize(pw, ph)
    }

    private fun swtab(i: Int) {
        tab = i
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(160).start()
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

        val isRunning = p.isrun()
        if (isRunning) {
            toggleBtn.text = "关闭推流"
            toggleBtn.setTextColor(cTx)
            toggleBtn.background = bgbox(false, cLine)
        } else {
            toggleBtn.text = "开启推流"
            toggleBtn.setTextColor(Color.WHITE)
            toggleBtn.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(cAcc)
            }
        }

        val c = p.cfgnow()
        val cdcName = when (c[5]) {
            2 -> "MJPEG HTTP"
            1 -> "H.264"
            else -> "H.265"
        }
        val rateText = if (c[5] == 2) "质量${c[3]}%" else "${c[3]}Mbps"
        val proto = if (c[5] == 2) "http" else "rtsp"
        val activePort = c[6]
        val statusSuffix = if (isRunning) "推流中" else "已暂停"

        if (isOverlayVisible) {
            overlayBtn.text = "隐藏浮层"
            overlayBtn.setTextColor(Color.WHITE)
            overlayBtn.background = bgbox(true)
            overlayBox.visibility = View.VISIBLE
        } else {
            overlayBtn.text = "参数浮层"
            overlayBtn.setTextColor(cTx2)
            overlayBtn.background = bgbox(false, cLine)
            overlayBox.visibility = View.GONE
        }

        overlayUrlTx.text = "$proto://${getip()}:$activePort · $statusSuffix"
        overlaySpecsTx.text = "CAM${p.curcam()} · ${c[0]}×${c[1]} · $rateText · $cdcName"

        fitpvw(c[0], c[1])

        if (isRunning && p.glready()) {
            try {
                p.attachpvw(monitorSurface.holder.surface, monitorSurface.width, monitorSurface.height)
            } catch (_: Exception) {}
        }

        for ((i, t) in tabBtns.withIndex()) {
            t.setTextColor(if (i == tab) cAcc else cTx2)
            t.background = if (i == tab) bgbox(true) else null
        }

        content.removeAllViews()
        when (tab) {
            TAB_PAR -> mkpartab(p)
            TAB_PST -> mkpsttab(p)
            TAB_ABT -> mkabttab()
        }
    }

    // 将相机支持的分辨率按画幅比例结构化分组
    private fun buildResItems(rawList: List<String>): List<ResItem> {
        val pairs = rawList.mapNotNull {
            val parts = it.split("×", "x")
            val w = parts.getOrNull(0)?.toIntOrNull()
            val h = parts.getOrNull(1)?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) Pair(w, h) else null
        }.distinct()

        val groups = linkedMapOf<String, MutableList<Pair<Int, Int>>>()
        groups["9:16 竖屏"] = mutableListOf()
        groups["16:9 横屏"] = mutableListOf()
        groups["3:4 竖屏"] = mutableListOf()
        groups["4:3 横屏"] = mutableListOf()
        groups["1:1 正方"] = mutableListOf()
        val otherList = mutableListOf<Pair<Int, Int>>()

        for (pr in pairs) {
            val r = pr.first.toFloat() / pr.second.toFloat()
            when {
                r in 0.55f..0.58f -> groups["9:16 竖屏"]?.add(pr)
                r in 1.74f..1.80f -> groups["16:9 横屏"]?.add(pr)
                r in 0.73f..0.77f -> groups["3:4 竖屏"]?.add(pr)
                r in 1.30f..1.36f -> groups["4:3 横屏"]?.add(pr)
                r in 0.98f..1.02f -> groups["1:1 正方"]?.add(pr)
                else -> otherList.add(pr)
            }
        }
        if (otherList.isNotEmpty()) {
            groups["其他画幅"] = otherList
        }

        val result = mutableListOf<ResItem>()
        for ((groupName, list) in groups) {
            if (list.isNotEmpty()) {
                result.add(ResItem(groupName, isHeader = true))
                list.sortByDescending { it.first * it.second }
                for (item in list) {
                    result.add(ResItem("${item.first}×${item.second}", isHeader = false, w = item.first, h = item.second))
                }
            }
        }
        return result
    }

    // 参数分页：直通控制
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
            val idx = camIds.indexOf(p.curcam())
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    val sel = camIds.getOrNull(pos) ?: return
                    if (sel != p.curcam()) {
                        runjob {
                            p.reconf(sel, c[0], c[1], Cfg.FPS, c[3], c[4], c[5])
                            runOnUiThread { refr() }
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col1.addView(camSpin, spinlpCompact())
        row1.addView(col1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 列 2：分辨率（原生单下拉框分组分类）
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        col2.addView(lbl("分辨率"))
        val resItems = buildResItems(p.lstres(p.curcam()))
        resSpin = Spinner(this).apply {
            adapter = SectionedResAdapter(this@MainAct, resItems, cTx, cTx3, cSurf) { dp(it) }
            val curIdx = resItems.indexOfFirst { !it.isHeader && it.w == c[0] && it.h == c[1] }
            if (curIdx >= 0) setSelection(curIdx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    val item = resItems.getOrNull(pos) ?: return
                    if (item.isHeader) return
                    if (item.w != c[0] || item.h != c[1]) {
                        runjob {
                            p.reconf(p.curcam(), item.w, item.h, Cfg.FPS, c[3], c[4], c[5])
                            runOnUiThread { refr() }
                        }
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
            val cur = when (c[4]) {
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
                    if (rot != c[4]) {
                        runjob {
                            p.reconf(p.curcam(), c[0], c[1], Cfg.FPS, c[3], rot, c[5])
                            runOnUiThread { refr() }
                        }
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
            setSelection(c[5].coerceIn(0, 2))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (suppressEvents) return
                    if (pos != c[5]) {
                        runjob {
                            val newRate = if (pos == 2 && c[3] < 30) 75 else if (pos != 2 && c[3] > 50) 10 else c[3]
                            val defaultPort = if (pos == 2) 8080 else Cfg.PORT
                            val targetPort = if (c[6] == 8080 || c[6] == Cfg.PORT) defaultPort else c[6]
                            p.reconf(p.curcam(), c[0], c[1], Cfg.FPS, newRate, c[4], pos, targetPort)
                            runOnUiThread { refr() }
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col4.addView(cdcSpin, spinlpCompact())
        row2.addView(col4, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row2)

        suppressEvents = false

        // ===== 紧凑网格第 3 行：[码率调节 / 质量调节] =====
        val isMjpeg = (c[5] == 2)
        val brHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        brHeader.addView(lbl(if (isMjpeg) "质量" else "码率"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        brTx = TextView(this).apply {
            text = if (isMjpeg) "${c[3]}%" else "${c[3]} Mbps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(cAcc)
        }
        brHeader.addView(brTx)
        content.addView(brHeader)

        brSeek = SeekBar(this).apply {
            if (isMjpeg) {
                max = 65
                progress = (c[3] - 30).coerceIn(0, 65)
            } else {
                max = 49
                progress = (c[3] - 1).coerceIn(0, 49)
            }
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) {
                        if (isMjpeg) {
                            val q = v + 30
                            brTx?.text = "$q%"
                            p.setbrate(q)
                            overlaySpecsTx.text = "CAM${p.curcam()} · ${c[0]}×${c[1]} · 质量${q}% · MJPEG HTTP"
                        } else {
                            val mbps = v + 1
                            brTx?.text = "$mbps Mbps"
                            p.setbrate(mbps)
                            val cdcName = if (c[5] == 1) "H.264" else "H.265"
                            overlaySpecsTx.text = "CAM${p.curcam()} · ${c[0]}×${c[1]} · ${mbps}Mbps · $cdcName"
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        content.addView(brSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ===== 紧凑网格第 4 行：[自定义端口] =====
        val row4 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        row4.addView(lbl("服务端口"))

        val portRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val portEdit = EditText(this).apply {
            setText("${c[6]}")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cTx)
            background = bgbox(false, cLine)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            filters = arrayOf(InputFilter.LengthFilter(5))
        }
        portRow.addView(portEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val applyPortBtn = TextView(this).apply {
            text = "应用"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(cAcc)
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                val input = portEdit.text.toString().toIntOrNull()
                if (input != null && input in 1024..65535) {
                    runjob {
                        p.reconf(p.curcam(), c[0], c[1], Cfg.FPS, c[3], c[4], c[5], input)
                        runOnUiThread { refr() }
                    }
                } else {
                    portEdit.setText("${c[6]}")
                }
            }
        }
        portRow.addView(applyPortBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })

        row4.addView(portRow)
        content.addView(row4)
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
                    runjob {
                        p.reconf(ps.camId, ps.w, ps.h, Cfg.FPS, ps.mbps, ps.rot, ps.cdc)
                        runOnUiThread { refr() }
                    }
                }
            }
            card.addView(TextView(this).apply {
                text = ps.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(cTx)
            })
            val cdcName = when (ps.cdc) {
                2 -> "MJPEG HTTP"
                1 -> "H.264"
                else -> "H.265"
            }
            val rateTxt = if (ps.cdc == 2) "质量${ps.mbps}%" else "${ps.mbps}Mbps"
            card.addView(TextView(this).apply {
                text = "CAM${ps.camId} · ${ps.w}×${ps.h} · $rateTxt · $cdcName"
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
        val c = p.cfgnow()
        val cdcName = if (c[5] == 0) "H.265" else "H.264"
        val name = "CAM${p.curcam()} · ${c[0]}×${c[1]} · ${c[3]}Mbps · $cdcName"
        PstStore.put(this, Preset(name, p.curcam(), c[0], c[1], Cfg.FPS, c[3], c[4], c[5]))
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

    // 构建关于页面
    private fun mkabttab() {
        val heroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(16), 0, dp(20))
        }

        val logoBox = TextView(this).apply {
            text = "CAM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(cAcc)
            }
        }
        heroBox.addView(logoBox, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            bottomMargin = dp(10)
        })

        val titleTx = TextView(this).apply {
            text = "K40WebCam"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(cTx)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        heroBox.addView(titleTx)

        val verTx = TextView(this).apply {
            text = "1.0.0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cTx3)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        heroBox.addView(verTx)

        content.addView(heroBox)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgbox(false, cLine)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }

        fun addRow(label: String, value: String, isLink: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                if (isLink) {
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                        } catch (_: Exception) {}
                    }
                }
            }
            val lbl = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cTx3)
            }
            val v = TextView(this).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (isLink) cAcc else cTx)
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(lbl)
            row.addView(v)
            card.addView(row)
        }

        addRow("应用名称", "K40WebCam")
        addRow("当前版本", "1.0.0")
        addRow("开发者", "https://github.com/yourpapayouknow", true)
        addRow("源码主页", "https://github.com/yourpapayouknow/K40WebCam", true)

        content.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
}
