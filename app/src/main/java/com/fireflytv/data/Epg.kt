package com.fireflytv.data

/**
 * 节目单里的一档节目。
 *
 * `startSec` / `endSec` 是秒级 epoch，直接来自央视 EPG 接口（原样保留，不做时区换算）——
 * 接口给的就是绝对时间戳，跨时区设备也能算对"现在播到哪了"。
 */
data class EpgProgram(
    val title: String,
    val startSec: Long,
    val endSec: Long,
) {
    val durationSec: Long get() = (endSec - startSec).coerceAtLeast(1L)

    fun contains(nowSec: Long): Boolean = nowSec >= startSec && nowSec < endSec

    /**
     * 已播进度 0f..1f。
     * 用 Long 先算再转 Float：直接 `(now - start) / (end - start)` 在 Float 上精度不够，
     * 长时间节目（例如 3 小时）会算出 0.9999 或 1.0001 这种边界值。
     */
    fun progressAt(nowSec: Long): Float {
        val elapsed = (nowSec - startSec).coerceIn(0L, durationSec)
        return elapsed.toFloat() / durationSec.toFloat()
    }

    fun remainingSec(nowSec: Long): Long = (endSec - nowSec).coerceAtLeast(0L)
}

/** 单个频道的当天节目单。 */
data class ChannelEpg(
    val channelNumber: Int,
    val programs: List<EpgProgram>,
) {
    fun liveAt(nowSec: Long): EpgProgram? = programs.firstOrNull { it.contains(nowSec) }

    /** 当前节目之后的第一档。找不到说明今天已经播完（跨零点前后会出现）。 */
    fun nextAfter(nowSec: Long): EpgProgram? = programs.firstOrNull { it.startSec > nowSec }
}
