package com.fireflytv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireflytv.data.Channel
import com.fireflytv.data.ChannelCategory
import com.fireflytv.ui.theme.TvSafeArea

/**
 * 频道列表抽屉。
 *
 * 焦点模型（2026-11 重构，替换原先的「假光标」）：
 * - 列表项是**真正的焦点目标**，上下移动交给 Compose 焦点系统，不再有
 *   `selectedChannelIndex` + 手工 `animateScrollToItem` 这套影子状态。
 * - 抽屉面板本身**不再持有焦点**。原先面板 `.focusRequester().focusable()` 把焦点
 *   锁在自己身上，列表项永远拿不到焦点，高亮全靠手算索引 —— 一旦焦点被系统弹窗、
 *   Activity 切换抢走，整个界面静默失效。
 * - 左右键切分类由面板的 `onPreviewKeyEvent` 在**预览阶段**截获。用 preview 而不是
 *   onKeyEvent，是因为方向键在焦点系统里默认会先尝试移动焦点，preview 阶段能保证
 *   在移动之前拿到事件，行为确定。
 * - 分类 Tab 显式 `canFocus = false`：它们只是状态指示器，不是焦点目标。否则从第一项
 *   按上键会跳到 Tab 上，出现「焦点在 Tab、左右键却在切分类」的第二层脱节。
 */
@Composable
fun ChannelListDrawer(
    cctvChannels: List<Channel>,
    localChannels: List<Channel>,
    currentChannelId: Int,
    initialSelectedChannelId: Int,
    selectedCategory: ChannelCategory,
    onCategorySelected: (ChannelCategory) -> Unit,
    onChannelSelected: (Channel) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    var tabIndex by remember {
        mutableIntStateOf(if (selectedCategory == ChannelCategory.CCTV) 0 else 1)
    }
    val channels = if (tabIndex == 0) cctvChannels else localChannels

    // 打开时要落到哪一项：上下键换台时传进来的目标频道，否则是当前正在播的频道。
    // 分类切换后同样用它重新定位 —— 目标频道可能不在新分类里，此时 indexOfFirst
    // 返回 -1，coerceAtLeast(0) 落到列表首项。
    val landingIndex = remember(initialSelectedChannelId, tabIndex) {
        channels.indexOfFirst { it.id == initialSelectedChannelId }.coerceAtLeast(0)
    }

    // 分类下一条频道都没有时（本地频道没配置过就是这种状态），列表里只剩「设置」，
    // 而 landingIndex 指向的 0 号频道并不存在 —— 没有节点可以承载 FocusRequester，
    // 焦点会掉回主界面，抽屉里的左右键就再也收不到了。这时把落点交给「设置」项。
    val landingIsSettingsEntry = channels.isEmpty()

    val listState = rememberLazyListState()
    val landingRequester = remember { FocusRequester() }

    fun switchTab(newIndex: Int) {
        if (newIndex == tabIndex) return
        tabIndex = newIndex
        onCategorySelected(if (newIndex == 0) ChannelCategory.CCTV else ChannelCategory.LOCAL)
    }

    // 首次打开 / 切换分类：滚到目标项，再把焦点放上去。
    // 必须先 scrollToItem —— LazyColumn 只为可见项创建组合，目标项在视口外时
    // FocusRequester 根本没有附着节点，requestFocus() 会直接抛异常。
    LaunchedEffect(tabIndex) {
        listState.scrollToItem(landingIndex)
        landingRequester.requestFocusWhenReady()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            // 背景点击只负责「关闭」，不参与焦点。clickable 本身带 focusable 语义，
            // 这个全屏 Box 一旦成为焦点目标就会跟列表项抢焦点（尤其在列表为空时），
            // 所以显式禁掉。focusProperties 必须写在 clickable 之前才作用于它。
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(400.dp)
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        // 左键 → 央视频道，右键 → 地方频道。定向而不是 toggle：
                        // 已经在左侧还按左，不该被切到右侧去。
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            switchTab(0)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            switchTab(1)
                            true
                        }
                        // 老设备（Android 15 及以下）仍会把 BACK 当普通按键下发；
                        // Android 16+ 的返回键由 PlayerScreen 的 BackHandler 接管。
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_MENU -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            // 原实现是 shadow(8.dp) 铺满整条 400dp×全高的抽屉，低端 SoC 上每帧都要
            // 画一层高斯模糊。tonalElevation 由主题色阶实现，零成本，视觉上够用。
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "频道列表",
                    // 面板本身贴左边（抽屉就该贴边），但内容必须让开 overscan
                    modifier = Modifier.padding(
                        start = TvSafeArea.Horizontal,
                        end = 16.dp,
                        top = TvSafeArea.Vertical,
                        bottom = 8.dp
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = TvSafeArea.Horizontal,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 8.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("央视频道", "地方频道").forEachIndexed { index, title ->
                        val isSelected = tabIndex == index
                        Button(
                            onClick = { switchTab(index) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                // Tab 不是焦点目标：焦点始终留在频道列表里，
                                // 左右键由上面的 onPreviewKeyEvent 负责切分类。
                                .focusProperties { canFocus = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(start = TvSafeArea.Horizontal, end = 16.dp)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = TvSafeArea.Vertical)
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { _, channel -> "channel-${channel.id}" }
                    ) { index, channel ->
                        ChannelListItem(
                            channel = channel,
                            isPlaying = channel.id == currentChannelId,
                            onClick = { onChannelSelected(channel) },
                            // 目标项挂 requester，其余项不挂
                            modifier = if (index == landingIndex) {
                                Modifier.focusRequester(landingRequester)
                            } else {
                                Modifier
                            }
                        )
                    }

                    // 末尾的「设置」项：光标按到底就能到，任何遥控器都可用
                    item(key = "settings-entry") {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = TvSafeArea.Horizontal,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 8.dp
                                )
                            )
                            SettingsEntryRow(
                                onClick = onOpenSettings,
                                modifier = if (landingIsSettingsEntry) {
                                    Modifier.focusRequester(landingRequester)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 频道行。
 *
 * 焦点态与「正在播放」态用两套完全不同的视觉通道，避免原来「alpha 0.5 对 alpha 0.3」
 * 那种电视上分不出来的差别：
 * - 聚焦：primary 实色底 + 3dp 白色边框 + onPrimary 文字（整块反白，10-foot 外一眼可见）
 * - 正在播放：淡 primary 底 + primary 文字 + ▶ 标记
 */
@Composable
fun ChannelListItem(
    channel: Channel,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val containerColor = when {
        focused -> MaterialTheme.colorScheme.primary
        isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val contentColor = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        isPlaying -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvSafeArea.Horizontal, end = 16.dp, top = 3.dp, bottom = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = contentColor,
        border = if (focused) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            null
        },
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${channel.number}  ${channel.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                fontWeight = if (isPlaying || focused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            if (isPlaying) {
                Text(
                    "▶",
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/**
 * 频道列表末尾的「设置」入口。
 *
 * 存在的唯一理由：Google TV / Chromecast 这类遥控器**没有菜单键**，
 * 功能菜单调不出来，设置页就成了不可达页面（播放器切换、X5 开关、字号全在里面）。
 * 这里把它做成「光标按到列表最底部就能选中」的普通列表项，任何遥控器都能用。
 *
 * 它现在是真正的焦点目标（Surface(onClick)），和 ChannelListItem 行为一致 ——
 * 重构前它靠 `selectedChannelIndex == settingsIndex` 假装被选中，OK 键还得在
 * 面板的 onKeyEvent 里单独判一个 if。
 */
@Composable
private fun SettingsEntryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvSafeArea.Horizontal, end = 16.dp, top = 3.dp, bottom = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (focused) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (focused) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            null
        },
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (focused) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = "播放器 / 内核 / 字号",
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 等焦点目标真正附着后再请求焦点。
 *
 * LazyColumn 的 item 要等到下一帧布局完成才进入焦点树，首次 requestFocus() 通常会抛
 * `FocusRequester is not initialized`。逐帧重试比 `delay(100)` 稳，也不会在快机器上白等。
 */
private suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 6): Boolean {
    repeat(attempts) {
        withFrameNanos { }
        // 未附着时 requestFocus() 抛 IllegalStateException，附着但拿不到焦点时返回
        // false —— 两种情况都要继续重试，所以不能只看 isSuccess。
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
    }
    return false
}
