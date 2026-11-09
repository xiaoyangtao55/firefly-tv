package com.fireflytv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

/**
 * 央视节目单（EPG）仓库。
 *
 * ## 数据从哪来
 * `https://api.cntv.cn/epg/epginfo?serviceId=tvcctv&c=cctv1,cctv2,...&d=20261110`
 *
 * 一次请求就能拿全部 CCTV 频道（实测 20 个台 ≈ 150 KB / 200 ms），返回体是：
 * ```
 * { "cctv1": { "channelName": "CCTV-1 综合",
 *              "isLive": "今日说法-2026-203",
 *              "program": [ { "t": "今日说法", "st": 1794240840, "et": 1794242730 }, ... ] } }
 * ```
 * `st` / `et` 是秒级 epoch，一档接一档铺满全天，足够画进度条和「接下来」。
 *
 * ## 为什么不用网页爬
 * 项目里原本有个 `PROGRAM_INFO_JS`，等 WebView 加载完再往页面里注入 JS 抠
 * `#jiemu > li.cur.act`。那条路有三个硬伤：只能拿到当前这一档（没有起止时间，画不出
 * 进度条）、只对广东台那套模板有效、页面一改版就静默失效。接口是 JSON，这些都没有。
 *
 * ## 卫视频道为什么不支持
 * 27 个地方台背后是央视频、广东台、各地广电几套完全不同的系统，没有统一的公开接口。
 * 它们的频道页是 SPA，源码里没有节目单（实测 `curl` 回来是 3 KB 的空壳）。
 * 硬做就得为每个站点写一套解析，然后跟着改版一起烂 —— 收益不抵成本，所以留空，
 * UI 那边降级成只显示台号台名。
 */
class EpgRepository(
    private val context: Context,
    private val channels: List<Channel>,
) {

    /** 只请求有 epgId 的频道，避免接口返回一堆用不上的 JSON。 */
    private val epgChannels: List<Channel> = channels.filter { !it.epgId.isNullOrBlank() }

    /**
     * 缓存有效期。
     *
     * 6 小时是个折中：节目单按天排，当天内基本不变；但跨零点后接口会换成新一天的数据，
     * 只按「日期变了」判断会让 23:59 打开的设备拿到已经播完的旧表。
     * 6 小时能保证凌晨那次一定刷新，白天又不至于反复请求。
     */
    private val cacheTtlMs = 6 * 60 * 60 * 1000L

    private val cacheFile: File by lazy { File(context.cacheDir, "epg_cache.json") }

    /**
     * 取节目单。**不会抛异常**：网络不通、接口改版、JSON 坏掉，一律退回缓存或空表。
     * 播放页不能因为拿不到节目单就黑屏 —— 那比没有节目单严重得多。
     */
    suspend fun load(forceRefresh: Boolean = false): Map<Int, ChannelEpg> =
        withContext(Dispatchers.IO) {
            if (epgChannels.isEmpty()) return@withContext emptyMap()

            val cached = readCache()
            val now = System.currentTimeMillis()
            val cacheUsable = cached != null &&
                cached.date == todayKey() &&
                now - cached.savedAt < cacheTtlMs
            if (!forceRefresh && cacheUsable) {
                return@withContext cached!!.epg
            }

            val fetched = try {
                fetch()
            } catch (e: Exception) {
                // 超时、DNS、证书、JSON 解析失败都会到这里。缓存过期也先用着，
                // 总比 HUD 上突然空一块好。
                Log.w(TAG, "拉取节目单失败：${e.javaClass.simpleName} ${e.message}")
                null
            }

            when {
                fetched != null && fetched.second.isNotEmpty() -> {
                    writeCache(fetched.first)
                    fetched.second
                }
                cached != null -> cached.epg
                else -> emptyMap()
            }
        }

    /** @return 原始响应 与 解析结果。原始响应留着写缓存，省得为了存盘再请求一次。 */
    private fun fetch(): Pair<String, Map<Int, ChannelEpg>> {
        val keys = epgChannels.mapNotNull { it.epgId }
        val url = URL("$ENDPOINT?serviceId=tvcctv&c=${keys.joinToString(",")}&d=${todayKey()}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "GET"
            // 实测不带 UA 也能拿到，但带上更稳：接口偶尔会按 UA 决定返回 HTML 还是 JSON
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return body to parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Map<Int, ChannelEpg> {
        val root = JSONObject(body)
        val result = HashMap<Int, ChannelEpg>(epgChannels.size)
        for (channel in epgChannels) {
            val key = channel.epgId ?: continue
            val node = root.optJSONObject(key) ?: continue
            val arr = node.optJSONArray("program") ?: continue

            val programs = ArrayList<EpgProgram>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val title = p.optString("t").trim()
                val start = p.optLong("st", 0L)
                val end = p.optLong("et", 0L)
                // 接口偶尔会给空标题或零时长的占位，丢掉比画一条空白横条好
                if (title.isEmpty() || start <= 0L || end <= start) continue
                programs.add(EpgProgram(title, start, end))
            }
            if (programs.isNotEmpty()) {
                result[channel.number] = ChannelEpg(channel.number, programs.sortedBy { it.startSec })
            }
        }
        Log.i(TAG, "节目单已更新：${result.size}/${epgChannels.size} 个台")
        return result
    }

    // ---------------- 磁盘缓存 ----------------

    private data class Cache(val date: String, val savedAt: Long, val epg: Map<Int, ChannelEpg>)

    /**
     * 存接口原始响应、读的时候再走同一条 parse，而不是把 Map 序列化成自定义格式。
     * 这样缓存和线上数据走完全相同的解析路径，不会出现"缓存能读、线上不能读"的偏差。
     */
    private fun readCache(): Cache? {
        if (!cacheFile.exists()) return null
        return try {
            val root = JSONObject(cacheFile.readText())
            val date = root.optString("date")
            val savedAt = root.optLong("savedAt", 0L)
            val raw = root.optString("raw")
            if (date.isEmpty() || raw.isEmpty()) return null
            Cache(date, savedAt, parse(raw))
        } catch (e: Exception) {
            Log.w(TAG, "节目单缓存损坏，已忽略：${e.message}")
            null
        }
    }

    private fun writeCache(raw: String) {
        try {
            val payload = JSONObject().apply {
                put("date", todayKey())
                put("savedAt", System.currentTimeMillis())
                put("raw", raw)
            }
            // 先写临时文件再 rename：盒子写着写着断电时，不会留下半截 JSON
            // 让下次启动读到损坏的缓存（虽然 readCache 有 try-catch，但白丢一次网络请求）
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(payload.toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(payload.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "写节目单缓存失败：${e.message}")
        }
    }

    private fun todayKey(): String {
        // 固定按北京时间算日期：接口的 d 参数认的是北京日期，
        // 设备时区不是 +8 时用本地时间会请求到前一天/后一天的节目单。
        val c = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"))
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return buildString {
            append(y)
            if (m < 10) append('0')
            append(m)
            if (d < 10) append('0')
            append(d)
        }
    }

    private companion object {
        const val TAG = "FireflyEpg"
        const val ENDPOINT = "https://api.cntv.cn/epg/epginfo"
    }
}
