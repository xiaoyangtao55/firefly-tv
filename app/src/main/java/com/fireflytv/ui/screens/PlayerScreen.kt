package com.fireflytv.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fireflytv.data.PlayerType
import com.fireflytv.ui.components.*
import com.fireflytv.viewmodel.MainViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    onOpenSettings: () -> Unit,
    // MainViewModel 是 AndroidViewModel，Activity 的默认工厂即可创建，无需自定义 Factory
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val cctvChannels = remember { viewModel.getCCTVChannels() }
    val localChannels = remember { viewModel.getLocalChannels() }

    // 防抖时间戳
    var lastKeyTime by remember { mutableLongStateOf(0L) }
    val keyDebounceMs = 150L // 按键防抖间隔

    // 菜单项
    val menuItems = remember {
        listOf(
            MenuItem(0, "刷新", { Icon(Icons.Default.Refresh, contentDescription = null) }) {
                viewModel.refreshPage()
                viewModel.hideAllOverlays()
            },
            MenuItem(1, "频道列表", { Icon(Icons.Default.List, contentDescription = null) }) {
                viewModel.toggleChannelList()
            },
            MenuItem(2, "播放/暂停", { Icon(Icons.Default.PlayArrow, contentDescription = null) }) {
                viewModel.hideAllOverlays()
            },
            MenuItem(3, "切换播放器", { Icon(Icons.Default.SwapHoriz, contentDescription = null) }) {
                // 切换播放器类型
                val newType = if (uiState.playerType == PlayerType.EXOPLAYER) {
                    PlayerType.WEBVIEW
                } else {
                    PlayerType.EXOPLAYER
                }
                viewModel.savePlayerType(newType)
                viewModel.hideAllOverlays()
                // 重新加载当前频道
                uiState.currentChannel?.let { viewModel.changeChannel(it) }
            },
            MenuItem(4, "设置", { Icon(Icons.Default.Settings, contentDescription = null) }) {
                viewModel.hideAllOverlays()
                onOpenSettings()
            }
        )
    }

    // 按键处理函数
    fun handleKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val currentTime = System.currentTimeMillis()

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            // 数字键：长按产生的重复事件会连续累加同一个数字，直接忽略；
            // 但按下瞬间不能做时间窗防抖，否则快速连按 "4""6" 换 46 台时第二位会被吞掉。
            if (event.repeatCount > 0) return true
        } else {
            // 方向键防抖（部分遥控器连击会抖动）
            val needsDebounce = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }

            if (needsDebounce && currentTime - lastKeyTime < keyDebounceMs) {
                return true // 防抖，忽略重复按键
            }
        }
        lastKeyTime = currentTime

        return when {
            // 有错误显示时，按确认键重试
            uiState.showError && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) -> {
                viewModel.retryPlayback()
                true
            }

            // 频道列表显示时，不在这里处理（频道列表自己处理）
            uiState.showChannelList -> false

            // 菜单显示时，不在这里处理（菜单自己处理）
            uiState.showMenu -> false

            // 数字输入显示时
            uiState.showNumberInput -> {
                when (keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        val number = keyCode - KeyEvent.KEYCODE_0
                        viewModel.appendNumber(number)
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        viewModel.clearNumberInput()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        // 确认立即切换
                        viewModel.confirmNumberInput()
                        true
                    }
                    else -> false
                }
            }

            // 正常播放状态
            else -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        viewModel.previousChannel()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        viewModel.nextChannel()
                        true
                    }
                    // 不少电视/盒子遥控器只有频道 +/- 键，没有上下方向键
                    KeyEvent.KEYCODE_CHANNEL_UP -> {
                        viewModel.nextChannel()
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        viewModel.previousChannel()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 左右键调出频道列表
                        viewModel.toggleChannelList()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        // 确认键调出频道列表
                        viewModel.toggleChannelList()
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        // 菜单键调出菜单
                        viewModel.toggleMenu()
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        // 返回键：如果有浮层就关闭，否则不处理（让系统处理）
                        if (uiState.showChannelList || uiState.showMenu || uiState.showNumberInput) {
                            viewModel.hideAllOverlays()
                            true
                        } else {
                            false
                        }
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        val number = keyCode - KeyEvent.KEYCODE_0
                        viewModel.appendNumber(number)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // 根布局 - 自己持有焦点，按键才能稳定到达 handleKeyDown
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    handleKeyDown(event.nativeKeyEvent)
                } else {
                    false
                }
            }
            // 必须显式 focusable()：只挂 focusRequester 不会创建焦点目标，requestFocus()
            // 会落空。原生播放器模式下还能靠 PlayerView 持焦点把按键冒泡上来，
            // 而 WebView 被设成不可聚焦，一旦没有子节点持焦点，遥控就整个失灵了。
            .focusable()
    ) {
        // 播放器区域
        Box(modifier = Modifier.fillMaxSize()) {
            // 根据播放器类型选择播放方式
            if (uiState.playerType == PlayerType.EXOPLAYER) {
                // 原生 ExoPlayer 播放
                VideoPlayer(
                    channel = uiState.currentChannel,
                    reloadToken = uiState.reloadToken,
                    isForeground = uiState.isForeground,
                    onPlaybackStateChanged = { isPlaying, message ->
                        viewModel.onPlaybackStateChanged(isPlaying, message)
                    },
                    onError = { errorMsg ->
                        viewModel.onPlaybackError(errorMsg)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // WebView 备用播放
                TVWebView(
                    channel = uiState.currentChannel,
                    reloadToken = uiState.reloadToken,
                    isForeground = uiState.isForeground,
                    onPageFinished = { info -> viewModel.onPageFinished(info) },
                    onError = { errorMsg -> viewModel.onPlaybackError(errorMsg) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 加载中指示器
            if (uiState.isChangingChannel) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(80.dp),
                        strokeWidth = 6.dp,
                        color = Color.White
                    )
                }
            }

            // 错误提示
            if (uiState.showError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .clickable { viewModel.retryPlayback() },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "播放失败",
                            color = Color.White,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Text(
                            text = uiState.errorMessage,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Button(
                            onClick = { viewModel.retryPlayback() },
                            modifier = Modifier.padding(top = 24.dp)
                        ) {
                            Text("重新加载")
                        }
                        Text(
                            text = "按菜单键可切换播放器",
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }

            // 频道信息浮层
            ChannelOverlay(
                message = uiState.overlayMessage,
                isVisible = uiState.showOverlay && !uiState.showError,
                fontSize = uiState.overlayTextSize,
                modifier = Modifier
            )

            // 数字输入浮层
            NumberInputOverlay(
                input = uiState.numberInputBuffer,
                isVisible = uiState.showNumberInput,
                modifier = Modifier
            )

            // 频道列表
            ChannelListDrawer(
                cctvChannels = cctvChannels,
                localChannels = localChannels,
                currentChannelId = uiState.currentChannel?.id ?: -1,
                // 上下键换台时打开列表，直接停在目标频道上（预选），按确认即可切过去
                initialSelectedChannelId = uiState.pendingSelectionId
                    ?: uiState.currentChannel?.id ?: -1,
                selectedCategory = uiState.channelCategory,
                onCategorySelected = { viewModel.setChannelCategory(it) },
                onChannelSelected = {
                    viewModel.changeChannel(it)
                    viewModel.hideAllOverlays()
                    // 关闭后把焦点还给主界面
                    focusRequester.requestFocus()
                },
                onDismiss = {
                    viewModel.hideAllOverlays()
                    focusRequester.requestFocus()
                },
                isVisible = uiState.showChannelList
            )

            // 菜单
            if (uiState.showMenu) {
                MenuOverlay(
                    items = menuItems,
                    isVisible = true,
                    onDismiss = {
                        viewModel.hideAllOverlays()
                        focusRequester.requestFocus()
                    },
                    modifier = Modifier
                )
            }
        }
    }

    // 需要时重新请求焦点：首次进入、从设置页回到前台、或发现按键没被消费（焦点丢了）
    LaunchedEffect(uiState.focusRequestToken) {
        // 布局还没完成时第一次请求可能落空，等一帧再试一次
        if (!focusRequester.requestFocus()) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
}
