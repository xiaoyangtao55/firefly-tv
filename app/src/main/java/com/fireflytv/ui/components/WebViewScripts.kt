package com.fireflytv.ui.components

/**
 * X5 内核（TVWebView）与系统内核（SystemWebView）共用的注入脚本。
 * 抽出来是为了保证两条播放路径的行为完全一致，避免以后只改一边导致行为分叉。
 */
object WebViewScripts {

    /** 取节目信息（只有央视网那套页面有 #jiemu 结构，其它站点拿不到就为空） */
    const val PROGRAM_INFO_JS = """
        (function() {
            var now = document.querySelector('#jiemu > li.cur.act');
            var next = document.querySelector('#jiemu > li:nth-child(4)');
            var nowText = now ? now.innerText : '';
            var nextText = next ? next.innerText : '';
            return nowText + (nextText ? '\n' + nextText : '');
        })();
    """

    /** 自动全屏：点页面上的全屏按钮并拉满音量 */
    const val AUTO_FULLSCREEN_JS = """
        (function() {
            // 最多尝试 20 次（500ms 一次，约 10 秒）。
            // 页面改版或换到别的站点后，全屏按钮可能永远不存在，
            // 没有上限的话这个定时器会在页面整个生命周期里一直空转。
            var attempts = 0;
            var MAX_ATTEMPTS = 20;
            function autoFullscreen() {
                if (attempts++ >= MAX_ATTEMPTS) return;
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
    """

    /**
     * 页面裁剪：把直播页里跟播放无关的东西清掉，减少布局与绘制的开销。
     *
     * 央视网和央视频的直播页是整套门户页（导航、推荐位、轮播、登录浮层），
     * 真正需要的只有一个播放器。盒子的 GPU 本来就紧张，让这些节点留在 DOM 里
     * 意味着每次重排都要算它们。
     *
     * 参考了 Eanya-Tonic/CCTV_Viewer 的 FastLoading()，但有两处刻意不同：
     *
     * 1. **200ms 轮询，不是 4ms 递归**。那边是每秒 250 次全量 DOM 查询，
     *    在低端盒子上这个清理器本身就是负载。图片已经被 blockNetworkImage
     *    挡在网络层，200ms 的窗口足够赶在解码前摘掉 src。
     * 2. **不按关键字清 <script>**。那边的名单（index/daohang/jquery/grey）
     *    是针对 tv.cctv.com 调出来的，央视频那套播放器依赖 cctvh5-openapi，
     *    一刀切下去两边只能保一个。这里只摘图片和广告容器，风险可控。
     *
     * 终止条件照抄了那边的思路：播放器一出来就停，页面改版导致条件永不满足时
     * 靠 20 秒硬上限兜底 —— 不留一个跑到页面生命尽头的定时器。
     */
    const val TRIM_PAGE_JS = """
        (function() {
            if (window.__ffTrim) return;
            window.__ffTrim = { timer: 0, start: Date.now() };

            function stop() {
                if (window.__ffTrim.timer) {
                    clearInterval(window.__ffTrim.timer);
                    window.__ffTrim.timer = 0;
                }
            }

            function playerReady() {
                return !!(document.querySelector('#player_pagefullscreen_yes_player') ||
                          document.querySelector('.videoFull'));
            }

            function strip() {
                if (playerReady()) { stop(); return; }
                if (Date.now() - window.__ffTrim.start > 20000) { stop(); return; }

                // 台标、推荐位、角标广告：整个直播页没有一处图片是播放必需的。
                // 用 removeAttribute 而不是 src='' —— 空 src 在老 WebKit 上会被
                // 解析成当前文档地址，反而又发一次请求。
                var imgs = document.getElementsByTagName('img');
                for (var i = 0; i < imgs.length; i++) {
                    if (imgs[i].getAttribute('src')) imgs[i].removeAttribute('src');
                }

                // 央视直播页的导航条与推荐位容器，id/class 是页面自己的命名。
                // newmap/newtopbz 在新版页面上已经查不到了（2026-11 实测 0 命中），
                // 留着只是兼容老模板 —— 真正生效的是 column_wrapper（推荐位横幅）。
                var junk = ['newmap', 'newtopbz', 'newtopbzTV', 'column_wrapper'];
                for (var j = 0; j < junk.length; j++) {
                    var nodes = document.getElementsByClassName(junk[j]);
                    for (var k = 0; k < nodes.length; k++) {
                        // 关键保护：这些 class 名不是为播放器保留的，万一哪天它们
                        // 包住了播放器，清空 innerHTML 就等于把画面删了。宁可少清一个。
                        if (nodes[k].querySelector('video, iframe, [id*=player]')) continue;
                        nodes[k].innerHTML = '';
                    }
                }
            }

            window.__ffTrim.timer = setInterval(strip, 200);
            strip();
        })();
    """

    /**
     * 播放看门狗：每 3 秒查一次 <video>，处理两种"页面自己把播放搞停"的情况。
     *
     * 1. **静音**。央视频的卫视页默认 `muted` 起播，表现为画面在动但没声音，
     *    用户只会以为台有问题。
     * 2. **自暂停**。页面切清晰度、插广告、或者切后台再回来之后，偶尔会停在
     *    paused 状态再也不恢复。只在 `readyState >= 2`（有可播数据）时补 play，
     *    缓冲中不碰 —— 否则会和加载器互相拖，越补越慢。
     *
     * 用户主动暂停时由 Kotlin 侧先调 [STOP_WATCHDOG_JS] 再暂停，所以这里
     * 不会把用户的暂停操作顶回去。
     */
    const val PLAY_WATCHDOG_JS = """
        (function() {
            if (window.__ffWatchdog) return;
            window.__ffWatchdog = setInterval(function() {
                var v = document.querySelector('video');
                if (!v) return;
                if (v.muted || v.volume === 0) {
                    v.muted = false;
                    v.volume = 1;
                }
                if (v.paused && v.readyState >= 2) {
                    try { v.play(); } catch (e) {}
                }
            }, 3000);
        })();
    """

    /** 停掉看门狗（用户暂停、退到后台、页面卸载前都要先调这个） */
    const val STOP_WATCHDOG_JS = """
        (function() {
            if (window.__ffWatchdog) {
                clearInterval(window.__ffWatchdog);
                window.__ffWatchdog = 0;
            }
        })();
    """

    /** 恢复播放页面里的 <video>（WebView.onResume() 本身不会恢复它） */
    const val PLAY_VIDEO_JS =
        "(function(){var v=document.querySelector('video'); if(v){v.play();}})();"

    /** 暂停播放页面里的 <video>（WebView.onPause() 本身不会停掉它，否则退后台还有声音） */
    const val PAUSE_VIDEO_JS =
        "(function(){var v=document.querySelector('video'); if(v){v.pause();}})();"
}
