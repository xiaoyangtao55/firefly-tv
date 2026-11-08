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

    /** 恢复播放页面里的 <video>（WebView.onResume() 本身不会恢复它） */
    const val PLAY_VIDEO_JS =
        "(function(){var v=document.querySelector('video'); if(v){v.play();}})();"

    /** 暂停播放页面里的 <video>（WebView.onPause() 本身不会停掉它，否则退后台还有声音） */
    const val PAUSE_VIDEO_JS =
        "(function(){var v=document.querySelector('video'); if(v){v.pause();}})();"
}
