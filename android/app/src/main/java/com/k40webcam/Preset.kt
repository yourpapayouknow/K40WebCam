package com.k40webcam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一组可一键应用的参数组合：相机、分辨率、帧率、码率、旋转角。
 * 用户可在界面上把当前设定存为预设，之后点卡片即恢复该组合。
 */
data class Preset(
    val name: String,
    val camId: String,
    val w: Int,
    val h: Int,
    val fps: Int,
    val mbps: Int,
    val rot: Int,
    val cdc: Int
) {
    // 单行摘要，用于卡片副标题
    fun summ(): String = "${w}x$h@${fps} · ${mbps}M · 旋转$rot° · ${if (cdc == 0) "H.265" else "H.264"}"
}

// 预设的持久化存储：SharedPreferences 中存一个 JSON 数组
object PstStore {

    private const val FILE = "k40presets"
    private const val KEY = "list"

    // 读取全部预设
    fun lst(ctx: Context): MutableList<Preset> {
        val out = mutableListOf<Preset>()
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Preset(
                        o.getString("name"),
                        o.getString("camId"),
                        o.getInt("w"),
                        o.getInt("h"),
                        o.getInt("fps"),
                        o.getInt("mbps"),
                        o.getInt("rot"),
                        o.optInt("cdc", 0)
                    )
                )
            }
        } catch (e: Exception) {
            // 数据损坏时返回已解析出的部分，不阻塞界面
        }
        return out
    }

    // 写入全部预设（同名覆盖）
    fun save(ctx: Context, list: List<Preset>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("camId", p.camId)
                    put("w", p.w)
                    put("h", p.h)
                    put("fps", p.fps)
                    put("mbps", p.mbps)
                    put("rot", p.rot)
                    put("cdc", p.cdc)
                }
            )
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    // 新增或覆盖一个预设
    fun put(ctx: Context, p: Preset) {
        val list = lst(ctx)
        list.removeAll { it.name == p.name }
        list.add(p)
        save(ctx, list)
    }

    // 按名称删除
    fun del(ctx: Context, name: String) {
        val list = lst(ctx)
        list.removeAll { it.name == name }
        save(ctx, list)
    }
}
