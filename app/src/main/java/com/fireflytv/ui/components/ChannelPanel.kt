package com.fireflytv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireflytv.data.Channel
import com.fireflytv.data.ChannelCategory
import com.fireflytv.data.ChannelEpg
import com.fireflytv.data.EpgProgram
import com.fireflytv.ui.theme.TvDimens
import com.fireflytv.ui.theme.TvSafeArea

/**
 * 选台面板（2026-11 重构，替换左侧竖向抽屉）。
 *
 * 布局参考 yaoxieyoulei/mytv-android 的 `panel/PanelScreen`：不是抽屉，是**底部升起**的
 * 两段式浮层 —— 左下是当前频道的大字信息，底部是「分组标题 + 横向卡片」的频道带。
 * 所有分组纵向排列、同屏可见，不再有「先切分类再看频道」这一步。
 *
 * 为什么放弃抽屉：
 * - 抽屉占满全高，一屏只能看到 12 个台，还要先按左右键选分类，找台是二段式操作；
 * - 台名之外的节目信息完全没地方放，用户看不到「这个台现在在播什么」；
 * - 侧边栏形态在 16:9 画面上天然切掉最左侧一条，遮挡的是画面里信息最密的位置。
 *
 * 视觉约定（与前几轮的取舍保持一致）：
 * - 焦点态是**白底黑字反白**，不用主题蓝。视频里蓝色太多（跑马灯、比分条），
 *   蓝底聚焦会被画面吃掉；反白是唯一不会被吃掉的选中态。
 * - 大台名和节目文字**不垫底板**，只加一层黑色文字阴影。底板会在亮画面上变成一块
 *   明显的黑砖，而阴影在暗画面上看不见、在亮画面上刚好托住字。
 * - 卡片未聚焦时 78% 黑底 —— 比纯黑透一点，让下面的画面还能透出来，
 *   这样才知道「面板是浮在正在播的东西上面的」。
 */
@Composable
fun ChannelPanel(
    cctvChannels: List<Channel>,
    localChannels: List<Channel>,
    /** 频道号 → 当天节目单。只有央视有，卫视不在表里。 */
    epg: Map<Int, ChannelEpg>,
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

    val groups = remember(cctvChannels, localChannels) {
        listOf(
            ChannelGroup("央视频道", ChannelCategory.CCTV, cctvChannels),
            ChannelGroup("地方频道", ChannelCategory.LOCAL, localChannels)
        ).filter { it.channels.isNotEmpty() }
    }

    val allChannels = remember(groups) { groups.flatMap { it.channels } }
    val currentChannel = remember(currentChannelId, allChannels) {
        allChannels.firstOrNull { it.id == currentChannelId }
    }

    // 落点：上下键换台时传进来的预选台优先，否则是当前正在播的台。
    // 两者都不在列表里（比如预选的是个已下线的台）时退回第一组第一张。
    val landingId = initialSelectedChannelId.takeIf { it > 0 } ?: currentChannelId
    val landingGroupIndex = remember(landingId, groups) {
        groups.indexOfFirst { group -> group.channels.any { it.id == landingId } }
            .coerceAtLeast(0)
    }
    val landingCardIndex = remember(landingId, groups, landingGroupIndex) {
        groups.getOrNull(landingGroupIndex)
            ?.channels?.indexOfFirst { it.id == landingId }
            ?.coerceAtLeast(0) ?: 0
    }

    // 一组频道都没有时没有卡片能承载焦点，落点交给底部「设置」行 ——
    // 否则焦点掉回主界面，面板里的返回键就再也收不到了。
    val landingOnSettings = groups.isEmpty()

    // 面板是静态浮层，打开时才重建组合，所以时间取一次就够。
    // 每秒刷新会让 47 张卡片连带进度条整体重组，代价远大于收益 ——
    // 一档节目动辄 30 分钟，进度条在没有动画的几个面板停留期间看不出变化。
    val nowSec = remember { System.currentTimeMillis() / 1000 }

    val landingRequester = remember { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }

    // 720p 的盒子只有 360dp 高，按 0.78 算下来面板只有 281dp。信息区如果还是
    // 98dp（78dp 台号块 + 两行节目），频道带就只剩七十来 dp，一组都看不全。
    // 矮屏上收紧信息区，把纵向空间让给卡片。
    val compact = LocalConfiguration.current.screenHeightDp < 500

    Box(
        modifier = modifier
            .fillMaxSize()
            // 遮罩 50%：比抽屉的 60% 浅一档。面板只占下半屏，上半屏还得能看清在放什么，
            // 压太狠会让人以为播放已暂停。
            .background(Color.Black.copy(alpha = 0.5f))
            // 背景点击只负责关闭，不参与焦点：clickable 自带 focusable 语义，
            // 这个全屏 Box 一旦成为焦点目标就会跟卡片抢焦点。
            // focusProperties 必须写在 clickable 之前才作用于它。
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                // 按屏高比例而不是给固定高度：1080p 的 TV 是 960×540dp，
                // 720p 的盒子只有 640×360dp。写死 400dp 在后者上会直接超出屏幕，
                // 底部「设置」行永远看不到。0.78 在 1080p 上刚好容下两组频道，
                // 分辨率更低时频道带自动收窄、靠滚动。
                .fillMaxHeight(0.78f)
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        // 老设备（Android 15 及以下）仍会把 BACK 当普通按键下发；
                        // Android 16+ 的返回键由 PlayerScreen 的 BackHandler 接管。
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_MENU -> {
                            onDismiss()
                            true
                        }
                        // 左右键不拦。焦点在卡片之间横移就是靠它，交给 Compose 焦点系统
                        // 比手写索引可靠 —— 抽屉那套「左右键切分类」在这个布局里没有对应物。
                        else -> false
                    }
                }
        ) {
            if (currentChannel != null) {
                CurrentChannelInfo(
                    channel = currentChannel,
                    epg = epg[currentChannel.number],
                    totalCount = allChannels.size,
                    nowSec = nowSec,
                    compact = compact,
                    modifier = Modifier.padding(horizontal = TvSafeArea.Horizontal)
                )
                Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
            }

            // 频道带吃掉「面板高度 - 信息区 - 设置行」剩下的空间。
            // 用 weight 而不是固定高度，是为了让 1080p / 720p 两种屏都能算出自己的
            // 可视区；组数超过可视高度时靠纵向滚动。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(
                    items = groups,
                    key = { _, group -> "group-${group.category.name}" }
                ) { groupIndex, group ->
                    ChannelGroupRow(
                        group = group,
                        epg = epg,
                        currentChannelId = currentChannelId,
                        nowSec = nowSec,
                        isLandingGroup = groupIndex == landingGroupIndex && !landingOnSettings,
                        landingCardIndex = landingCardIndex,
                        landingRequester = landingRequester,
                        selectedCategory = selectedCategory,
                        onCategorySelected = onCategorySelected,
                        onChannelSelected = onChannelSelected
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SettingsEntry(
                onClick = onOpenSettings,
                modifier = if (landingOnSettings) {
                    Modifier.focusRequester(settingsRequester)
                } else {
                    Modifier
                }
            )

            // 底部留 overscan。面板贴底，不留的话最后一行会被老电视切掉半截。
            Spacer(Modifier.height(TvSafeArea.Vertical))
        }
    }

    LaunchedEffect(Unit) {
        if (landingOnSettings) {
            settingsRequester.requestFocusWhenReady()
        } else {
            // 卡片要先等 LazyRow 滚到目标位置才会被组合出来，逐帧重试兜住这段时间。
            landingRequester.requestFocusWhenReady(attempts = 12)
        }
    }
}

private data class ChannelGroup(
    val title: String,
    val category: ChannelCategory,
    val channels: List<Channel>
)

// ---------------- 当前频道信息 ----------------

@Composable
private fun CurrentChannelInfo(
    channel: Channel,
    epg: ChannelEpg?,
    totalCount: Int,
    nowSec: Long,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val live = epg?.liveAt(nowSec)
    val next = epg?.nextAfter(nowSec)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ChannelBadge(number = channel.number, size = if (compact) 60.dp else 78.dp)

        Spacer(Modifier.width(if (compact) 16.dp else 22.dp))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    style = if (compact) {
                        MaterialTheme.typography.titleLarge.copy(shadow = textShadow())
                    } else {
                        MaterialTheme.typography.headlineMedium.copy(shadow = textShadow())
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(12.dp))
                // 「第 18 个 / 共 47 个」。抽屉原来只在列表里给台号，
                // 这里顺手补上总数，用户能判断"我还要翻多久"。
                Text(
                    text = "${channel.number}/$totalCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .background(
                            Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

            when {
                live != null -> {
                    ProgramLine("正在播放", live.title)
                    // 矮屏上「稍后播放」先砍掉：它是最不影响找台的一条信息，
                    // 想看完整节目单可以按菜单键进节目单（后续做的方向）
                    if (next != null && !compact) {
                        Spacer(Modifier.height(4.dp))
                        ProgramLine("稍后播放", next.title)
                    }
                }
                // 央视台有 epgId 但表里还没有它 —— 首帧刚开机、或者请求还在路上
                channel.epgId != null -> ProgramLine("正在播放", "节目单加载中…")
                // 卫视没有公开 EPG 接口，直说，别让用户以为界面坏了
                else -> ProgramLine("节目单", "该频道暂无节目单")
            }
        }
    }
}

@Composable
private fun ProgramLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow()),
            color = Color.White.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow()),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------- 分组 + 卡片 ----------------

@Composable
private fun ChannelGroupRow(
    group: ChannelGroup,
    epg: Map<Int, ChannelEpg>,
    currentChannelId: Int,
    nowSec: Long,
    isLandingGroup: Boolean,
    landingCardIndex: Int,
    landingRequester: FocusRequester,
    selectedCategory: ChannelCategory,
    onCategorySelected: (ChannelCategory) -> Unit,
    onChannelSelected: (Channel) -> Unit
) {
    // 初始滚动位置直接定在目标卡片上，而不是先组合第一屏再 scrollToItem ——
    // 后者开关面板时会看到一次明显的横向跳动。往前留两张做上下文，
    // 让人知道自己停在这一组的什么位置。
    val rowState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (isLandingGroup) {
            (landingCardIndex - 2).coerceAtLeast(0)
        } else {
            0
        }
    )

    Column {
        Row(
            modifier = Modifier.padding(horizontal = TvSafeArea.Horizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.labelMedium.copy(shadow = textShadow()),
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${group.channels.size} 个频道",
                style = MaterialTheme.typography.labelMedium.copy(shadow = textShadow()),
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = TvSafeArea.Horizontal)
        ) {
            itemsIndexed(
                items = group.channels,
                key = { _, channel -> "channel-${channel.id}" }
            ) { index, channel ->
                ChannelCard(
                    channel = channel,
                    live = epg[channel.number]?.liveAt(nowSec),
                    nowSec = nowSec,
                    isPlaying = channel.id == currentChannelId,
                    onClick = { onChannelSelected(channel) },
                    onFocused = {
                        if (channel.category != selectedCategory) {
                            onCategorySelected(channel.category)
                        }
                    },
                    modifier = if (isLandingGroup && index == landingCardIndex) {
                        Modifier.focusRequester(landingRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

/**
 * 频道卡片。
 *
 * 与抽屉里的频道行相比多了一行节目名和一条 3dp 进度条 —— 这是放弃抽屉换来的收益：
 * 找台时不用先切过去才知道那个台在播什么。
 *
 * 三种状态用三条不同的视觉通道，避免「正在播」和「聚焦」被认错：
 * - 聚焦：纯白底 + 黑字，整块反白
 * - 正在播：8% 白底（比周围亮一档，但远不到"选中"）
 * - 其余：78% 黑底，透出一点画面
 */
@Composable
private fun ChannelCard(
    channel: Channel,
    live: EpgProgram?,
    nowSec: Long,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // 焦点进来时同步分类。放在 LaunchedEffect 里而不是组合体内直接写：
    // 组合期间写状态会立刻触发一次重组，在 LazyRow 里这是滚动卡顿的常见来源。
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    val contentColor = if (focused) Color.Black else Color.White
    val containerColor = when {
        focused -> Color.White
        isPlaying -> Color.White.copy(alpha = 0.08f)
        else -> Color.Black.copy(alpha = 0.78f)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(146.dp)
            .height(64.dp),
        shape = RoundedCornerShape(TvDimens.RowRadius),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Surface 会把内容裁到 shape，但进度条贴着底边画，圆角处还是会
                // 露出一个直角缺口。这里再裁一次，代价可以忽略。
                .clip(RoundedCornerShape(TvDimens.RowRadius))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = live?.title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (live != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(live.progressAt(nowSec))
                        .height(3.dp)
                        .background(
                            if (focused) {
                                Color.Black.copy(alpha = 0.7f)
                            } else {
                                Color.White.copy(alpha = 0.85f)
                            }
                        )
                )
            }
        }
    }
}

// ---------------- 设置入口 ----------------

/**
 * 面板底部的「设置」行。
 *
 * 存在的唯一理由：Google TV / Chromecast 这类遥控器**没有菜单键**，
 * 功能菜单调不出来，设置页就成了不可达页面（播放器切换、X5 开关、字号全在里面）。
 * 放在频道带下面，光标按到底就能选中，任何遥控器都能用。
 */
@Composable
private fun SettingsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TvSafeArea.Horizontal)
            .height(48.dp),
        shape = RoundedCornerShape(TvDimens.RowRadius),
        color = if (focused) Color.White else Color.Transparent,
        contentColor = if (focused) Color.Black else Color.White,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
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
                Spacer(Modifier.width(12.dp))
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
                    Color.Black.copy(alpha = 0.75f)
                } else {
                    Color.White.copy(alpha = 0.6f)
                }
            )
        }
    }
}

// ---------------- 工具 ----------------

/**
 * 文字阴影。
 *
 * 面板不垫底板，白字直接压在海量视频画面上 —— 碰到雪景、白墙、新闻演播室这种
 * 高亮背景，白字会整行消失。一层 8px 黑阴影只影响字形边缘，既不形成方正的
 * 黑砖（底板方案的问题），也能在亮画面上托住文字。
 *
 * 和 [ChannelOverlay] 里"不用阴影"的结论不冲突：那里反对的是给整块面板加
 * 高斯模糊投影（每帧一次，会糊出脏边），文字阴影是文字渲染的一部分，很便宜。
 */
private fun textShadow() = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 2f),
    blurRadius = 8f
)

/**
 * 等焦点目标真正附着后再请求焦点。
 *
 * LazyRow / LazyColumn 的 item 要等布局完成才进入焦点树，而且这里的目标卡片
 * 往往还在视口外 —— 首次 requestFocus() 通常会抛 `FocusRequester is not initialized`。
 * 逐帧重试比 `delay(100)` 稳：快机器上不必白等，慢机器上也不会因为一帧没赶上就放弃。
 */
private suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 8): Boolean {
    repeat(attempts) {
        withFrameNanos { }
        // 未附着时 requestFocus() 抛 IllegalStateException，附着但拿不到焦点时返回
        // false —— 两种情况都要继续重试，所以不能只看返回值。
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
    }
    return false
}
