package com.fireflytv.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.fireflytv.data.Channel
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.WebSettings
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TVWebView(
    channel: Channel?,
    reloadToken: Int,
    isForeground: Boolean,
    isPaused: Boolean,
    onPageFinished: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 主文档加载失败的标记。WebView 加载失败时同样会回调 onPageFinished，
    // 不标记的话会把"错误页加载完成"误判成播放成功。
    val loadFailed = remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 关闭图片加载，电视盒子网络弱时更快出画面、少占带宽
                loadsImagesAutomatically = false
                blockNetworkImage = true
                // 允许自动播放（X5 下同样需要这一项，否则 <video> 不播）
                mediaPlaybackRequiresUserGesture = false
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // 新页面开始加载就清掉上次失败标记，避免上一台的失败态污染这一台
                    loadFailed.value = false
                }

                // X5 的 WebViewClient 只提供旧式 onReceivedError(view, errorCode, description, failingUrl)，
                // 没有基于 WebResourceRequest 的重载，这里用旧式签名。
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    // 只关心主文档失败：图片、广告等子资源加载不出来不影响播放
                    if (failingUrl != null && failingUrl != view?.url) return
                    loadFailed.value = true
                    onError(describeWebError(errorCode))
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // 默认行为是静默取消加载，用户只会看到一片黑，这里给个明确提示
                    loadFailed.value = true
                    handler?.cancel()
                    onError("网站证书校验失败，无法加载（X5 内核未就绪或证书不受信）")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == "about:blank") return
                    // 加载失败时 WebView 也会回调 onPageFinished，别把错误页当成播放成功
                    if (loadFailed.value) return

                    // 获取节目信息（只有央视网那套页面有 #jiemu 结构，其它站点拿不到就为空）
                    val jsCode = """
                        (function() {
                            var now = document.querySelector('#jiemu > li.cur.act');
                            var next = document.querySelector('#jiemu > li:nth-child(4)');
                            var nowText = now ? now.innerText : '';
                            var nextText = next ? next.innerText : '';
                            return nowText + (nextText ? '\n' + nextText : '');
                        })();
                    """

                    view?.evaluateJavascript(jsCode) { result ->
                        // 取不到节点时 JS 返回的是字符串 "null"，别把它当成节目信息显示出来
                        val info = result
                            .replace("\"", "")
                            .trim()
                            .let { if (it == "null") "" else it }
                        onPageFinished(info)
                    }

                    // 自动全屏
                    val fullscreenJs = """
                        (function() {
                            function autoFullscreen() {
                                var btn = document.querySelector('#player_pagefullscreen_yes_player') ||
                                          document.querySelector('.videoFull');
                                if (btn) {
                                    btn.click();
                                    var video = document.querySelector('video');
                                    if (video) video.volume = 1;
                                } else {
                                    setTimeout(autoFullscreen, 500);
                                }
                            }
                            autoFullscreen();
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(fullscreenJs, null)
                }
            }

            webChromeClient = WebChromeClient()
            isFocusable = false
        }
    }

    // 频道变化或主动刷新/重试时（reloadToken 改变）重新加载页面
    LaunchedEffect(channel, reloadToken) {
        channel?.let {
            loadFailed.value = false
            webView.loadUrl(it.url)
        }
    }

    // 前后台切换 / 用户暂停。注意 WebView.onPause() 并不会停掉页面里的 <video>，
    // 所以要额外暂停/恢复它，否则退回桌面后网页声音还在响。
    DisposableEffect(isForeground, isPaused) {
        if (isForeground && !isPaused) {
            webView.onResume()
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video'); if(v){v.play();}})();",
                null
            )
        } else {
            webView.onPause()
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video'); if(v){v.pause();}})();",
                null
            )
        }
        onDispose { }
    }

    // 组件离开组合（例如切到原生播放器、或退出 Activity）时销毁 WebView，
    // 否则 WebView 会持有 Context 引用导致内存泄漏。VideoPlayer 同理在 onDispose 里 release。
    DisposableEffect(Unit) {
        onDispose {
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        update = { },
        modifier = modifier
    )
}

/** 把 WebView 的错误码翻译成用户能看懂的提示（X5 与系统 WebView 的错误码一致） */
private fun describeWebError(code: Int): String = when (code) {
    WebViewClient.ERROR_HOST_LOOKUP -> "无法解析网站地址，请检查网络"
    WebViewClient.ERROR_CONNECT -> "连接网站失败，请检查网络"
    WebViewClient.ERROR_TIMEOUT -> "连接网站超时"
    WebViewClient.ERROR_IO -> "网络读写出错"
    WebViewClient.ERROR_BAD_URL -> "网址格式错误"
    WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "不支持的网址协议"
    WebViewClient.ERROR_REDIRECT_LOOP -> "重定向次数过多"
    WebViewClient.ERROR_UNKNOWN -> "网页加载失败（未知错误）"
    else -> "网页加载失败（错误码 $code）"
}
