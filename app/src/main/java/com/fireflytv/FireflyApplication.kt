package com.fireflytv

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hearthappy.x5core.X5CoreManager
import com.hearthappy.x5core.interfaces.X5CoreListener

/**
 * 应用入口：初始化腾讯 X5（TBS）内核。
 *
 * 这里用的是 WebX5Core 封装库（com.github.HeartHappy.webX5Core），
 * 它底层仍是官方 com.tencent.smtt.sdk，并按 ABI 把内核 apk 打包进 assets，
 * 离线设备会自动从 assets 安装本地内核（解决“线上拉取内核受限”）。
 *
 * ## 关于“首次安装后退出一下”
 *
 * 旧实现的做法是：本地内核装好后用 AlarmManager 挂一个 1 秒后的
 * PendingIntent，然后 killProcess 自杀，靠系统把 MainActivity 再拉起来，
 * 好让新内核生效。在 targetSdk ≤ 30 的设备上这套能跑，但从此以后：
 *
 * 1. targetSdk ≥ 31 时 setExact() 需要 SCHEDULE_EXACT_ALARM，没声明就抛
 *    SecurityException。异常从 TBS 回调线程抛出，没人接 → 进程当场终止。
 *    用户看到的就是“点开就退出”，而第二次启动内核已在磁盘上，不再触发
 *    安装回调，于是“第二次就正常了”。这就是那个假闪退的真身。
 * 2. 把 setExact 换成不需要权限的 setAndAllowWhileIdle 之后确实不崩了，
 *    但 Android 14+ 的后台启动限制（BAL）会把闹钟触发的 startActivity
 *    直接拦掉 —— 实测返回 BAL_BLOCK / START_CANCELED，应用停在那里没人
 *    拉起来，比崩溃更糟。
 *
 * 所以现在不再主动结束进程：内核装好后只在界面上提示一句，本次会话继续
 * 用当前内核（TBS 会回退到系统内核，播放不受影响），下次冷启动自动启用
 * X5。用户永远看不到“退出”，也就不存在“看起来像闪退”。
 */
class FireflyApplication : android.app.Application() {

    /** 本进程内是否已经开始过 X5 初始化（避免 MainViewModel 再次触发） */
    private var x5Initialized = false

    override fun onCreate() {
        super.onCreate()
        initX5IfNeeded()
    }

    /**
     * 按设置决定是否初始化 X5（TBS）内核。
     *
     * 原来这里是无条件 init：不管用户选的是 ExoPlayer 还是系统 WebView，
     * 冷启动都要先把 TBS 的 dex 加载起来、校验上万个类 —— 在低端盒子上
     * 实测能拖慢启动数秒，慢到主线程 5 秒没响应就直接 ANR。
     *
     * 现在只有 use_x5_kernel = true 才付这份成本；默认值仍为 true，
     * 与设置页的默认状态一致。用户在设置里重新打开 X5 时，
     * MainViewModel 会再调用一次这里（幂等）。
     */
    fun initX5IfNeeded() {
        if (x5Initialized) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_USE_X5, true)) return

        // 本地内核只打包了 arm64-v8a 一份。跑在纯 x86 模拟器上必然装不上，
        // 却仍会先把几十兆的 tbs apk 从 assets 复制到 cache，白耗一次 IO。
        // 注意带 arm 转译的 x86 镜像 abilist 里同样有 arm64-v8a，不算误判。
        if (!hasArm64Support()) {
            Log.i(TAG, "ABI=${Build.SUPPORTED_ABIS.joinToString()} 不支持 arm64，跳过本地 X5 内核")
            return
        }

        x5Initialized = true
        try {
            X5CoreManager.initX5Core(
                this,
                listener = object : X5CoreListener {
                    override fun onCoreInitFinished() {
                        // 内核初始化完成（可能是 X5，也可能是系统内核）
                    }

                    override fun onViewInitFinished(isX5: Boolean) {
                        // isX5=true 表示成功使用 X5 内核；false 则回退系统 WebView。
                        // 离线盒子若未预置内核且无法联网，会走到这里为 false。
                        Log.i(TAG, "onViewInitFinished isX5=$isX5")
                    }

                    override fun onInstallFinish(stateCode: Int) {
                        // 200 = 本地内核安装成功。TBS 要求换新进程才生效，
                        // 这里不动进程，只提示用户下次启动即可用上。
                        if (stateCode == 200) {
                            Log.i(TAG, "X5 本地内核安装成功，下次启动生效")
                            getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_CORE_READY, true)
                                .apply()
                            toastOnMain(getString(R.string.x5_core_ready_toast))
                        } else {
                            Log.w(TAG, "X5 本地内核安装结束，stateCode=$stateCode")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            // X5 只影响网页播放这条链路，初始化失败不该拖垮整个 App。
            // 复位标记，允许后续（比如设置页重新打开开关时）再试一次。
            Log.e(TAG, "X5 初始化失败，本次回退系统 WebView", t)
            x5Initialized = false
        }
    }

    private fun hasArm64Support(): Boolean =
        Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }

    private fun toastOnMain(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Log.w(TAG, "Toast 显示失败", t)
            }
        }
    }

    private companion object {
        const val TAG = "FireflyApplication"

        const val PREFS = "fireflytv"
        const val KEY_USE_X5 = "use_x5_kernel"

        /** 本地内核是否已经从 assets 安装到磁盘（下次冷启动会真正启用） */
        const val KEY_CORE_READY = "x5_core_ready"
    }
}
