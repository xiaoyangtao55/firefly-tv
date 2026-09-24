package com.fireflytv.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
    onPlaybackStateChanged: (Boolean, String) -> Unit, // (isPlaying, message)
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
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

    // 监听播放状态
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        onPlaybackStateChanged(false, "缓冲中...")
                    }
                    Player.STATE_READY -> {
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
                val errorMsg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，请检查网络"
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

    // 前后台切换：回到桌面或进入设置页时暂停，避免退回后台后还在出声
    DisposableEffect(isForeground) {
        if (isForeground) {
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
