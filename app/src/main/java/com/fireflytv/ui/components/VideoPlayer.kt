package com.fireflytv.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.fireflytv.data.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 断流后最多自动重连几次。2 次 + 首次播放 = 总共 3 次尝试。 */
private const val MAX_AUTO_RETRY = 2

/**
 * 原生视频播放器 - 使用 ExoPlayer 播放 m3u8 直播流
 * 不依赖 WebView，兼容低版本 Android TV
 *
 * ExoPlayer / HlsMediaSource / DefaultHttpDataSource / PlayerView / HttpDataSource
 * 都带 media3 的 @UnstableApi（@RequiresOptIn level = ERROR），必须显式 opt-in，
 * 否则 lint 会以 UnsafeOptInUsageError 直接让构建失败。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    channel: Channel?,
    reloadToken: Int,
    isForeground: Boolean,
    isPaused: Boolean,
    onPlaybackStateChanged: (Boolean, String) -> Unit, // (isPlaying, message)
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            // 显式声明音频用途并申请音频焦点。不申请的话，本应用会和其它正在出声的
            // 应用（音乐、语音助手）直接混在一起；系统提示音、来电也不会让我们自动降音。
            // 这是电视盒子最常见的"两个 App 同时出声"投诉的根因。
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔 HDMI / 切外放时自动暂停，避免突然外放炸响
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // 直播流配置
                playWhenReady = true
            }
    }

    val playerView = remember {
        PlayerView(context).apply {
            player = exoPlayer
            useController = false // 不显示默认控制栏，用我们自己的
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // 断流自动重连的计数器：随频道/刷新令牌重置，播放成功（STATE_READY）后清零。
    // 直播 HLS 跑几十分钟必断一次，如果每次断流都直接弹错误页，用户就得一直按 OK。
    val retriedCount = remember(channel, reloadToken) { AtomicInteger(0) }
    val retryScope = rememberCoroutineScope()

    // 监听播放状态
    // 按 channel / reloadToken 重建：listener 里要知道"当前是哪条流"，
    // 否则重连协程可能在用户已经换台之后仍然去 prepare 旧的播放器状态。
    DisposableEffect(exoPlayer, channel, reloadToken) {
        var disposed = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        onPlaybackStateChanged(false, "缓冲中...")
                    }
                    Player.STATE_READY -> {
                        // 恢复正常了，下次断流重新给足重试次数
                        retriedCount.set(0)
                        onPlaybackStateChanged(true, "")
                    }
                    Player.STATE_ENDED -> {
                        onPlaybackStateChanged(false, "播放结束")
                    }
                    Player.STATE_IDLE -> {
                        onPlaybackStateChanged(false, "准备中...")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 先静默重连：直播断线绝大多数是瞬时的，用户不该看见错误页
                val attempt = retriedCount.incrementAndGet()
                val hasStream = !channel?.streamUrl.isNullOrEmpty()
                if (attempt <= MAX_AUTO_RETRY && hasStream) {
                    onPlaybackStateChanged(
                        false,
                        "连接中断，正在重连（$attempt/$MAX_AUTO_RETRY）…"
                    )
                    retryScope.launch {
                        // 递增退避：第一次 0.8s，第二次 2.5s，给网络/服务端一点恢复时间
                        delay(if (attempt == 1) 800L else 2500L)
                        // 期间可能已经被换台、被用户手动重试、或页面已销毁，
                        // 这三种情况下都不要再去动播放器
                        if (disposed || exoPlayer.playerError == null) return@launch
                        runCatching {
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        }
                    }
                    return
                }

                val errorMsg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> if (attempt > 1) {
                        "网络连接失败，已重试 $MAX_AUTO_RETRY 次"
                    } else {
                        "网络连接失败，请检查网络"
                    }
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                        // PlaybackException 上没有状态码字段，HTTP 状态码挂在 cause 上
                        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        if (code != null) "视频源不可用（HTTP $code）" else "视频源不可用"
                    }
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的视频格式"
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频解码失败"
                    else -> "播放错误: ${error.message ?: "未知错误"}"
                }
                onError(errorMsg)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            disposed = true
            exoPlayer.removeListener(listener)
        }
    }

    // 频道变化或主动刷新/重试时（reloadToken 改变）播放新的流
    DisposableEffect(channel, reloadToken) {
        val ch = channel
        when {
            ch == null -> Unit
            ch.streamUrl.isEmpty() ->
                onError("「${ch.name}」没有可用的直播流，请按菜单键切换到网页播放")
            else -> {
                // 停止之前的播放（出错状态下这同时也是恢复流程的第一步）
                exoPlayer.stop()
                exoPlayer.clearMediaItems()

                // 创建 HLS 媒体源（m3u8直播流）
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(30_000)

                val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(ch.streamUrl))

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }
        onDispose {
            // 切换频道时不释放，保持播放器实例
        }
    }

    // 前后台切换 / 用户暂停：两者任一不满足就暂停，避免退回后台或暂停时还在出声
    DisposableEffect(isForeground, isPaused) {
        if (isForeground && !isPaused) {
            // 直播暂停久了会落后于直播进度，回到前台时追到当前直播点，
            // 否则用户看到的是暂停那一刻的画面
            if (exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.seekToDefaultPosition()
            }
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
        onDispose { }
    }

    // 页面销毁时释放播放器
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { playerView },
        update = { },
        modifier = modifier
    )
}
