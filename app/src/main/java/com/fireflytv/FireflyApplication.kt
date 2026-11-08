package com.fireflytv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.hearthappy.x5core.X5CoreManager
import com.hearthappy.x5core.interfaces.X5CoreListener

/**
 * 应用入口：初始化腾讯 X5（TBS）内核。
 *
 * 这里用的是 WebX5Core 封装库（com.github.HeartHappy.webX5Core），
 * 它底层仍是官方 com.tencent.smtt.sdk，并按 ABI 把内核 apk 打包进 assets，
 * 离线设备会自动从 assets 安装本地内核（解决“线上拉取内核受限”）。
 *
 * 注意：
 * 1. 通过 JitPack 引入 webx5core_arm64_v8a（电视盒子多为 arm64），
 *    如需兼容 32 位旧盒子可再引入 webx5core_armeabi_v7a。
 * 2. 离线内核首次安装成功（onInstallFinish 返回 200）后必须重启 App 才生效，
 *    这里收到 200 会用 AlarmManager 拉起 MainActivity 并结束当前进程。
 */
class FireflyApplication : android.app.Application() {

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
        val prefs = getSharedPreferences("fireflytv", MODE_PRIVATE)
        if (!prefs.getBoolean("use_x5_kernel", true)) return
        x5Initialized = true

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
                    // 200 = 本地内核安装成功，需重启 App 才能生效
                    if (stateCode == 200) {
                        Log.i(TAG, "X5 本地内核安装成功，重启 App 生效")
                        restartApp(this@FireflyApplication)
                    }
                }
            }
        )
    }

    /**
     * 通过 AlarmManager 在 1 秒后重新拉起 MainActivity，并结束当前进程，
     * 使离线安装好的 X5 内核生效。直接 finish 当前进程而不用 alarm，
     * 系统不会自动重启，所以必须用 alarm 挂一个 PendingIntent。
     */
    private fun restartApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setExact(
            AlarmManager.RTC,
            SystemClock.elapsedRealtime() + 1000,
            pending
        )
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "FireflyApplication"
    }
}
