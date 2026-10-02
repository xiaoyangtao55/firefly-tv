package com.fireflytv

import android.app.Application
import com.tencent.smtt.sdk.QbSdk

/**
 * 应用入口：初始化腾讯 X5（TBS）内核。
 *
 * 系统自带 WebView 在部分低版本电视盒子上内核过旧，加载央视/卫视那种
 * 重 JS 播放页会白屏或卡死。X5 自带统一内核，不依赖系统 WebView，
 * 能显著改善这类设备的兼容性。
 *
 * 注意：
 * 1. tbs_sdk_*.aar 需放到 app/libs/（不在 Google Maven，需手动下载放入）；
 * 2. X5 首次启动会从腾讯 CDN 拉取内核，离线设备需预置本地内核
 *    （把 .so/.jar 放进 assets/tencent_class/，见 README）。
 */
class FireflyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 预初始化：提前准备 X5 内核，避免首次打开播放页才临时加载。
        // 第三个参数填 applicationContext 即可，非视频内核场景传 false。
        QbSdk.initX5Environment(
            this,
            object : QbSdk.PreInitCallback {
                override fun onCoreInitFinished() {
                    // 内核就绪（可能是系统内核或已下载的 X5 内核），这里不需要额外处理，
                    // 真正播放时 com.tencent.smtt.sdk.WebView 会自动使用 X5。
                }

                override fun onViewInitFinished(p0: Boolean) {
                    // p0=true 表示成功使用 X5 内核；false 则回退到系统 WebView。
                    // 失败通常是因为离线环境且未预置本地内核，播放页会退化成系统 WebView。
                }
            }
        )
    }
}
