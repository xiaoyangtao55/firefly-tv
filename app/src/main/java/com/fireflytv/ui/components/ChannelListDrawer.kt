package com.fireflytv.ui.components

import android.view.KeyEvent
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireflytv.data.Channel
import com.fireflytv.data.ChannelCategory
import com.fireflytv.ui.theme.TvDimens
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
            // 70% → 60%。抽屉本身已经是不透明的纯黑面板，右侧画面再压到 70%
            // 会让整屏失去"还在放东西"的参照，换台时反而不知道自己在哪个台上。
            .background(Color.Black.copy(alpha = 0.6f))
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
            shape = RoundedCornerShape(
                topEnd = TvDimens.RowRadius,
                bottomEnd = TvDimens.RowRadius
            ),
            // 用 background（纯黑）而不是 surface：surface 是 #1E1E1E，
            // 和左侧遮罩叠在一起会发灰，看起来像"半透明的灰玻璃"而不是面板。
            // 纯黑 + 遮罩，边界靠两侧亮度差，反而更干净。
            // tonalElevation 也一并去掉 —— M3 会给面板叠一层主色调 tint。
            color = MaterialTheme.colorScheme.background
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

                CategoryTabs(
                    titles = listOf("央视频道", "地方频道"),
                    selectedIndex = tabIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = TvSafeArea.Horizontal,
                            end = 16.dp,
                            top = 8.dp
                        )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = TvSafeArea.Horizontal, end = 16.dp)
                )

                // 列表顶边和分隔线之间留 8dp。不留的话，滚动时被裁掉的那半行会
                // 直接贴在下划线上，看起来像"字压在线上"；留了之后裁切边界落在
                // 一片纯黑里，半行就是"被列表顶部截断"，不会误读成布局错位。
                Spacer(modifier = Modifier.height(8.dp))

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
 * 分类导航（tab）。
 *
 * 为什么不用设置页那套 [TvSegmented]：那是"值选择器"，用一块底色表达"当前值"。
 * 这里是"第几页"，用下划线表达。两者混用会让抽屉里的分类看起来像被选中了两次
 * （浅灰块 + 列表首项反白），而分段条在黑面板上只有 12% 白，实测截图里
 * 几乎看不出边界，等于白画。
 *
 * 为什么仍然不可聚焦：焦点必须始终留在频道列表里。左右键由抽屉面板的
 * onPreviewKeyEvent 在预览阶段截获后直接切 tabIndex，tab 只是状态指示器。
 * 一旦 tab 可聚焦，从首项按上键会跳到 tab 上，就出现"焦点在 tab、左右键却在
 * 切分类"的第二层脱节 —— 这也是重构前那套假光标的老毛病。
 *
 * 也不挂 clickable：clickable 自带 focusable 语义，同样会把 tab 拉进焦点树。
 * 遥控器是唯一主路径，切分类只走左右键。
 */
@Composable
private fun CategoryTabs(
    titles: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvDimens.RowGap)
    ) {
        titles.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                // 3dp 白条紧跟下面的 1dp 分隔线，选中段看起来是"加粗的那一截"。
                // 未选中段留空（透明），所以整条 tab 栏底部就是一条细灰线，
                // 不会出现"每段都被框起来"的按钮感。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                Color.Transparent
                            }
                        )
                )
            }
        }
    }
}

/**
 * 频道行。
 *
 * 焦点态与「正在播放」态用两套完全不同的视觉通道，避免原来「alpha 0.5 对 alpha 0.3」
 * 那种电视上分不出来的差别：
 * - 聚焦：onBackground 实色底（白）+ background 文字（黑），整块反白，10-foot 外一眼可见
 * - 正在播放：8% 白底 + ▶ 标记 + 台号/台名加粗
 *
 * 不再用 primary 蓝做聚焦底。视频画面上蓝色到处都是（新闻跑马灯、体育比分条），
 * 蓝底聚焦在换台场景里会被画面吃掉；反白是唯一不会被吃掉的选中态。
 * 也因此去掉了原来的 3dp 白色描边 —— 白底上的白边肉眼不可见，纯属浪费一次绘制。
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

    // 未聚焦时不要用纯白做"正在播放"的底：整条列表里只有一项是白的，
    // 用户会把它误认成焦点。8% 白是"比周围亮一点"，还够不上"选中"。
    val containerColor = when {
        focused -> MaterialTheme.colorScheme.onBackground
        isPlaying -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val contentColor = if (focused) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.onBackground
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvSafeArea.Horizontal, end = 16.dp, top = 3.dp, bottom = 3.dp),
        shape = RoundedCornerShape(TvDimens.RowRadius),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 台号单独成列并右对齐：原来是 "1  CCTV-1" 和 "46  广东珠江" 拼一个字符串，
            // 位数不同，台名整体是参差的。固定 44dp 一列后所有台名落在同一条竖线上，
            // 扫视列表时不用逐个读数字。
            Text(
                text = channel.number.toString(),
                style = MaterialTheme.typography.bodyLarge,
                // 台号是索引不是内容，未聚焦时压暗一档，视线先落到台名上
                color = if (focused || isPlaying) {
                    contentColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                fontWeight = if (isPlaying || focused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
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

    val contentColor = if (focused) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.onBackground
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvSafeArea.Horizontal, end = 16.dp, top = 3.dp, bottom = 3.dp),
        shape = RoundedCornerShape(TvDimens.RowRadius),
        color = if (focused) {
            MaterialTheme.colorScheme.onBackground
        } else {
            Color.Transparent
        },
        contentColor = contentColor,
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
                // tint 留空即取 LocalContentColor，聚焦反白时自动变黑
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
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
                    contentColor.copy(alpha = 0.75f)
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
