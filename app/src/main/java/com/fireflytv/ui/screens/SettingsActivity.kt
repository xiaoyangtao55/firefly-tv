package com.fireflytv.ui.screens

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireflytv.data.PlayerType
import com.fireflytv.ui.components.TvSegmented
import com.fireflytv.ui.components.TvSettingRow
import com.fireflytv.ui.theme.FireflyTVTheme
import com.fireflytv.ui.theme.TvDimens
import com.fireflytv.ui.theme.TvSafeArea
import kotlinx.coroutines.delay

/** 播放器选项。顺序即分段条从左到右的顺序，index 与 PlayerType 不是同一套编号。 */
private val PLAYER_OPTIONS = listOf("原生播放器", "网页播放")

/** 字号档位。存的是字符串，因为原版本就是按字符串写进 prefs 的，不动存储格式。 */
private val FONT_SIZES = listOf("18", "22", "25", "30")

/** 浮层层时长范围，与播放页的默认值保持一致。 */
private val OVERLAY_RANGE = 2..10

/**
 * 破坏性操作的二次确认窗口。
 * 3 秒：短于这个值，用户看完标题变化再按第二下会来不及；
 * 长于这个值，误触后隔很久再按一次也会被当成确认，就失去防误触的意义了。
 * 「恢复默认设置」和「重启应用」共用。
 */
private const val CONFIRM_WINDOW_MS = 3_000L

/** 点击重启后留给 Toast 上屏的时间，之后才结束进程 */
private const val RESTART_EXIT_DELAY_MS = 1_200L

private val KEYMAP = listOf(
    "↑ / ↓" to "上/下一个频道（或列表移动）",
    "← / →" to "调出选台面板；面板内左右移动",
    "确认 / OK" to "打开选台面板 / 选择频道",
    "菜单" to "打开功能菜单",
    "数字键 0-9" to "输入频道号快速换台",
    "返回" to "关闭浮层 / 返回"
)

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FireflyTVTheme {
                SettingsScreen(
                    onBack = { finish() },
                    onRestart = { restartApp() }
                )
            }
        }
    }

    /**
     * 重启应用，让磁盘上已装好的 X5 内核在下一个进程里生效。
     *
     * 为什么必须真的退出：TBS 内核是进程启动时一次性加载的，同一个进程里
     * 再调 initX5Environment 也是空操作，换内核只能换进程。
     *
     * 为什么退出后不会自动回来：targetSdk 36 有后台启动限制，闹钟/PendingIntent
     * 拉起自己会被判 BAL_BLOCK（实测 START_CANCELED）。所以这里提前用文案和
     * Toast 把"需要手动重新打开"讲清楚，而不是让用户对着桌面发愣。
     */
    private fun restartApp() {
        Toast.makeText(this, "已退出，请重新打开应用", Toast.LENGTH_SHORT).show()
        // 先退到桌面，避免进程被杀时还停在设置页
        finishAffinity()
        // 留一点时间让 Toast 上屏；进程一死 Toast 也就没了
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, RESTART_EXIT_DELAY_MS)
    }
}

// 原来这里有 @OptIn(ExperimentalMaterial3Api::class)，是给 TopAppBar 用的。
// 顶栏去掉后，剩下的 Scaffold / Text 都是 stable API，不再需要 opt-in。
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRestart: () -> Unit
) {
    val context = LocalContext.current
    // SharedPreferences 本身有缓存，但放在 remember 里能避免每次重组都走一遍
    // getSharedPreferences 的加锁查表。
    val prefs = remember(context) {
        context.getSharedPreferences("fireflytv", Context.MODE_PRIVATE)
    }

    var fontSize by remember {
        mutableStateOf(prefs.getString("text_size", "22") ?: "22")
    }
    var directChannelChange by remember {
        mutableStateOf(prefs.getBoolean("direct_channel_change", false))
    }
    var showProgramInfo by remember {
        mutableStateOf(prefs.getBoolean("show_program_info", true))
    }
    var useX5Kernel by remember {
        mutableStateOf(prefs.getBoolean("use_x5_kernel", true))
    }
    // X5 内核状态。设置页与播放页不在同一个 Activity，拿不到播放页的 ViewModel，
    // 这里直接查 QbSdk 的内核版本号：0 表示本机没有可用的 X5 内核。
    // 注意这个值只代表"磁盘上装没装内核"，不代表本进程已经初始化过它 ——
    // X5 现在是按开关惰性初始化的，所以关闭状态下不能再显示"已就绪"。
    var tbsVersion by remember { mutableIntStateOf(com.tencent.smtt.sdk.QbSdk.getTbsVersion(context)) }
    var overlayDuration by remember {
        mutableStateOf(prefs.getInt("overlay_duration", 5))
    }
    var playerType by remember {
        val typeIndex = prefs.getInt("player_type", PlayerType.WEBVIEW.ordinal)
        mutableStateOf(
            try {
                PlayerType.entries[typeIndex]
            } catch (e: Exception) {
                PlayerType.EXOPLAYER
            }
        )
    }

    // 「恢复默认设置」的二次确认状态。
    //
    // 放在页面级而不是那个 item 内部：LazyColumn 的 item 滚出视口会被回收，
    // 状态放里面会出现"滚走再回来，待确认状态莫名消失"或更糟的"回来后又保留着"。
    //
    // 3 秒自动取消：电视遥控器误触很常见，一次误按之后如果一直挂着待确认状态，
    // 用户过一会儿再按一次 OK 就把设置清空了 —— 那就等于没有确认。
    var resetPending by remember { mutableStateOf(false) }

    LaunchedEffect(resetPending) {
        if (resetPending) {
            delay(CONFIRM_WINDOW_MS)
            resetPending = false
        }
    }

    // 「重启应用」的二次确认，同样放在页面级：理由与上面一致。
    var restartPending by remember { mutableStateOf(false) }

    LaunchedEffect(restartPending) {
        if (restartPending) {
            delay(CONFIRM_WINDOW_MS)
            restartPending = false
        }
    }

    // 所有设置项统一走"改 state + 写 prefs"这一条路径，避免某一处只写了内存没落盘
    // （之前直接换台模式就有过"走了设置页再回来又变回去"的问题）。
    fun setDirectChannelChange(value: Boolean) {
        directChannelChange = value
        prefs.edit().putBoolean("direct_channel_change", value).apply()
    }

    fun setShowProgramInfo(value: Boolean) {
        showProgramInfo = value
        prefs.edit().putBoolean("show_program_info", value).apply()
    }

    fun setX5Kernel(value: Boolean) {
        useX5Kernel = value
        prefs.edit().putBoolean("use_x5_kernel", value).apply()
    }

    fun setOverlayDuration(value: Int) {
        val clamped = value.coerceIn(OVERLAY_RANGE.first, OVERLAY_RANGE.last)
        if (clamped == overlayDuration) return
        overlayDuration = clamped
        prefs.edit().putInt("overlay_duration", clamped).apply()
    }

    fun setPlayerType(type: PlayerType) {
        if (playerType == type) return
        playerType = type
        prefs.edit().putInt("player_type", type.ordinal).apply()
    }

    fun setFontSize(value: String) {
        if (fontSize == value) return
        fontSize = value
        prefs.edit().putString("text_size", value).apply()
    }

    fun resetAll() {
        // 显式写回每项默认值，而不是只 clear()：
        // 1. clear() 不一定为被清掉的每个键都发变更通知，播放页可能收不到；
        // 2. 顺手保留 last_channel_id —— 恢复默认设置不该丢掉"上次观看的频道"。
        prefs.edit()
            .clear()
            .putString("text_size", "22")
            .putBoolean("direct_channel_change", false)
            .putBoolean("show_program_info", true)
            .putInt("overlay_duration", 5)
            .putInt("player_type", PlayerType.WEBVIEW.ordinal)
            .putBoolean("use_x5_kernel", true)
            .putInt("last_channel_id", prefs.getInt("last_channel_id", 0))
            .apply()
        fontSize = "22"
        directChannelChange = false
        showProgramInfo = true
        overlayDuration = 5
        // 与上面写入的 player_type 默认值（WEBVIEW）保持一致，
        // 否则会出现"设置里存的是网页播放、界面上却高亮原生播放器"的错位。
        playerType = PlayerType.WEBVIEW
        useX5Kernel = true
    }

    /**
     * 重启的具体动作（退到桌面、结束进程）放在 Activity 里做：
     * Composable 拿不到 finishAffinity，也不该直接杀进程。
     */

    // 去掉顶栏的返回箭头之后，返回键必须显式接管。
    // targetSdk 36 起 predictive back 默认开启，返回事件不再作为普通按键下发到视图层，
    // 靠 Activity 默认的 finish() 也能退，但显式声明能让"哪一层负责关掉这个页面"
    // 一目了然 —— 将来加了二级页（比如"选择播放器"）才不会漏掉某一层。
    BackHandler { onBack() }

    Scaffold { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            // 10-foot 安全区：1080p 的 5% overscan ≈ 48dp / 27dp
            contentPadding = PaddingValues(
                horizontal = TvSafeArea.Horizontal,
                vertical = TvSafeArea.Vertical
            ),
            verticalArrangement = Arrangement.spacedBy(TvDimens.RowGap)
        ) {
            item { SettingsHeader() }

            item { SectionLabel("播放") }
            item {
                TvSettingRow(
                    title = "播放器",
                    supporting = if (playerType == PlayerType.EXOPLAYER) {
                        "原生解码，不依赖 WebView"
                    } else {
                        "网页加载，兼容更多频道"
                    },
                    trailing = { focused ->
                        TvSegmented(
                            options = PLAYER_OPTIONS,
                            selectedIndex = if (playerType == PlayerType.EXOPLAYER) 0 else 1,
                            focused = focused
                        )
                    },
                    // 左键直接选中第一项、右键选中第二项 —— 两项时这样比"循环切换"
                    // 更符合直觉：按左永远到左边那个，不会绕一圈。
                    onLeft = { setPlayerType(PlayerType.EXOPLAYER) },
                    onRight = { setPlayerType(PlayerType.WEBVIEW) },
                    onSelect = {
                        setPlayerType(
                            if (playerType == PlayerType.EXOPLAYER) PlayerType.WEBVIEW
                            else PlayerType.EXOPLAYER
                        )
                    }
                )
            }
            item {
                TvSettingRow(
                    title = "X5 内核",
                    value = if (useX5Kernel) "开" else "关",
                    // 只保留"现在是什么状态"，把原来那段"若提示未生效…"的长文删掉了：
                    // 那属于排障说明，常驻设置页会把这一行撑到三行，破坏整个列表的节奏。
                    //
                    // 文案只能陈述"已安装"，不能写"运行中"：getTbsVersion() 返回的是
                    // 磁盘上的内核版本，不代表本进程已经初始化过它（X5 是惰性初始化的）。
                    // 写"运行中"在"刚打开开关、还没重启"的场景下就是假话。
                    supporting = when {
                        useX5Kernel && tbsVersion > 0 -> "X5 内核 $tbsVersion 已安装"
                        useX5Kernel -> "本机暂无内核，自动回退系统 WebView"
                        tbsVersion > 0 -> "内核已安装（$tbsVersion），打开后生效"
                        else -> "本机无内核，使用系统 WebView"
                    },
                    onLeft = { setX5Kernel(false) },
                    onRight = { setX5Kernel(true) },
                    onSelect = { setX5Kernel(!useX5Kernel) }
                )
            }

            item { SectionLabel("显示") }
            item {
                val index = FONT_SIZES.indexOf(fontSize).coerceAtLeast(0)
                TvSettingRow(
                    title = "字体大小",
                    supporting = "频道信息浮层的字号",
                    trailing = { focused ->
                        TvSegmented(
                            options = FONT_SIZES.map { "${it}sp" },
                            selectedIndex = index,
                            focused = focused
                        )
                    },
                    onLeft = { if (index > 0) setFontSize(FONT_SIZES[index - 1]) },
                    onRight = { if (index < FONT_SIZES.lastIndex) setFontSize(FONT_SIZES[index + 1]) },
                    // 到端点后循环回另一头：四个档位没有"更合适"的方向，
                    // 停在端点只会让用户以为按键失灵。
                    onSelect = { setFontSize(FONT_SIZES[(index + 1) % FONT_SIZES.size]) }
                )
            }
            item {
                TvSettingRow(
                    title = "直接换台模式",
                    value = if (directChannelChange) "开" else "关",
                    supporting = if (directChannelChange) "上下键直接换台" else "上下键显示选台面板",
                    onLeft = { setDirectChannelChange(false) },
                    onRight = { setDirectChannelChange(true) },
                    onSelect = { setDirectChannelChange(!directChannelChange) }
                )
            }
            item {
                TvSettingRow(
                    title = "显示频道信息",
                    value = if (showProgramInfo) "开" else "关",
                    supporting = if (showProgramInfo) "切换频道时显示频道名称" else "不显示频道信息",
                    onLeft = { setShowProgramInfo(false) },
                    onRight = { setShowProgramInfo(true) },
                    onSelect = { setShowProgramInfo(!showProgramInfo) }
                )
            }
            item {
                TvSettingRow(
                    title = "浮层显示时长",
                    value = "$overlayDuration 秒",
                    supporting = "切换频道后浮层停留的时间",
                    onLeft = { setOverlayDuration(overlayDuration - 1) },
                    onRight = { setOverlayDuration(overlayDuration + 1) },
                    onSelect = {
                        setOverlayDuration(
                            if (overlayDuration >= OVERLAY_RANGE.last) OVERLAY_RANGE.first
                            else overlayDuration + 1
                        )
                    }
                )
            }

            item { SectionLabel("帮助") }
            item { KeymapBlock() }
            item { AboutBlock() }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                TvSettingRow(
                    title = if (restartPending) "再按一次确认" else "重启应用",
                    supporting = when {
                        restartPending -> "应用会退出，需要重新打开"
                        // 内核已经在磁盘上、但本次进程还没用上，重启才有意义
                        useX5Kernel && tbsVersion > 0 -> "播放内核已就绪，重启后启用"
                        else -> "退出并重新打开，让改动彻底生效"
                    },
                    danger = restartPending,
                    onSelect = {
                        if (restartPending) {
                            restartPending = false
                            onRestart()
                        } else {
                            restartPending = true
                        }
                    }
                )
            }
            item {
                TvSettingRow(
                    // 两次确认：第一次只把标题改掉，不改任何数据；第二次才真的清。
                    // 标题变化本身就是反馈 —— 不需要额外的 Toast，电视上也没有 Toast 的位置。
                    title = if (resetPending) "再按一次确认" else "恢复默认设置",
                    supporting = if (resetPending) {
                        "所有设置将回到默认值，3 秒内有效"
                    } else {
                        "按两次确认，防止误触"
                    },
                    danger = true,
                    onSelect = {
                        if (resetPending) {
                            resetAll()
                            resetPending = false
                        } else {
                            resetPending = true
                        }
                    }
                )
            }
        }
    }
}

/**
 * 页面标题，顶替原来的 TopAppBar。
 *
 * 去掉顶栏不是为了好看，是因为它在电视上没有任何功能：navigationIcon 那个返回箭头
 * 是给手指点的，遥控器按返回键走的是系统 finish()，这个按钮永远不会被聚焦到。
 * 它却占掉 64dp 高度，还带着一整条 primaryContainer 蓝底，和列表的黑白风格直接打架。
 *
 * 标题放进 LazyColumn 当第一个 item，是为了不改动所有 item 的缩进层级
 * （嵌套 Column 要多缩一层，所有块重排，diff 会脏得没法 review）。
 */
@Composable
private fun SettingsHeader() {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            // 与行内文字左对齐（行有 28dp 内边距）。不缩进的话标题会比
            // 下面所有文字更靠左，看起来像两个不同的排版栅格。
            modifier = Modifier.padding(start = TvDimens.RowPaddingH)
        )
        Spacer(modifier = Modifier.height(16.dp))
        // 手绘 1dp 细线，不用 HorizontalDivider：这个 API 在不同 BOM 版本里
        // 改过名字（Divider → HorizontalDivider），手绘没有版本风险，代价只有一行。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

/** 小节标题。只承接层级，不可聚焦 —— 焦点停在"播放"上没有任何意义。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TvDimens.RowPaddingH, top = 12.dp)
    )
}

/**
 * 按键说明。纯只读，不参与焦点图。
 *
 * 原版本把它装在一张 Card 里，卡片本身不可聚焦但占着 16dp 内边距 + 圆角 + 阴影，
 * 视觉重量比真正的设置项还大。这里去掉卡片，只用文字对齐和行高来组织。
 */
@Composable
private fun KeymapBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TvDimens.RowPaddingH)
    ) {
        KEYMAP.forEach { (key, desc) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(160.dp)
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/** 关于。同样只读，三行分开写比一段带 \n 的字符串更好调行距。 */
@Composable
private fun AboutBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TvDimens.RowPaddingH),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "萤火 TV v1.1.0",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "专为 Android TV 优化",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "原生播放 + WebView 双模式",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
