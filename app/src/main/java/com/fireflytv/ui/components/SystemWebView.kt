package com.fireflytv.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.fireflytv.data.Channel

/**
 * 系统 WebView 播放（X5 内核之外的备用路径）。
 *
 * 与 TVWebView（X5）的区别只在 WebView 的类和少量 API 差异，
 * 播放逻辑（注入 JS 取节目信息、自动全屏、暂停/恢复 <video>）保持一致。
 * 当用户在设置里关掉 X5 内核，或 X5 在本机不可用时走这里。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SystemWebView(
    channel: Channel?,
    reloadToken: Int,
    isForeground: Boolean,
    isPaused: Boolean,
    onPageFinished: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val loadFailed = remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    loadFailed.value = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    if (failingUrl != null && failingUrl != view?.url) return
                    loadFailed.value = true
                    onError(describeSystemWebError(errorCode))
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    loadFailed.value = true
                    handler?.cancel()
                    onError("网站证书校验失败，无法加载（系统 WebView 版本过旧时容易遇到）")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == "about:blank") return
                    if (loadFailed.value) return

                    val jsCode = WebViewScripts.PROGRAM_INFO_JS
                    view?.evaluateJavascript(jsCode) { result ->
                        val info = result
                            .orEmpty()
                            .replace("\"", "")
                            .trim()
                            .let { if (it == "null") "" else it }
                        onPageFinished(info)
                    }

                    view?.evaluateJavascript(WebViewScripts.AUTO_FULLSCREEN_JS, null)
                }
            }

            webChromeClient = WebChromeClient()
            isFocusable = false
        }
    }

    LaunchedEffect(channel, reloadToken) {
        channel?.let {
            loadFailed.value = false
            webView.loadUrl(it.url)
        }
    }

    DisposableEffect(isForeground, isPaused) {
        if (isForeground && !isPaused) {
            webView.onResume()
            webView.evaluateJavascript(WebViewScripts.PLAY_VIDEO_JS, null)
        } else {
            webView.onPause()
            webView.evaluateJavascript(WebViewScripts.PAUSE_VIDEO_JS, null)
        }
        onDispose { }
    }

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

private fun describeSystemWebError(code: Int): String = when (code) {
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
