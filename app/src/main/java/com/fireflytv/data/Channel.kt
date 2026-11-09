package com.fireflytv.data

data class Channel(
    val id: Int,
    val number: Int,
    val name: String,
    val url: String,           // 网页播放地址（WebView备用）
    val streamUrl: String,     // 直播流地址（m3u8，原生播放器用）
    val category: ChannelCategory,
    val logo: String? = null,
    /**
     * 央视 EPG 接口里的频道键，例如 "cctv1"、"cctvjilu"。
     *
     * 为什么不用 number 直接拼：CCTV-9 的键是 cctvjilu（纪录），CCTV-14 是 cctvchild（少儿），
     * 和频道序号对不上。硬拼只会在两个台上静默拿不到节目单。
     *
     * 卫视频道留 null —— 它们没有统一的公开 EPG 接口，见 EpgRepository 的说明。
     */
    val epgId: String? = null
)

enum class ChannelCategory {
    CCTV,
    LOCAL
}

enum class PlayerType {
    EXOPLAYER,  // 原生播放器（备用，不依赖WebView）
    WEBVIEW     // WebView 网页播放（默认）
}
