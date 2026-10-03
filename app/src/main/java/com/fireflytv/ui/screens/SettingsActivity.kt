package com.fireflytv.ui.screens

import android.view.KeyEvent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireflytv.data.PlayerType
import com.fireflytv.ui.components.tvFocusHighlight
import com.fireflytv.ui.theme.FireflyTVTheme

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FireflyTVTheme {
                SettingsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("fireflytv", Context.MODE_PRIVATE)

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
    var kernelStatus by remember {
        val version = com.tencent.smtt.sdk.QbSdk.getTbsVersion(context)
        mutableStateOf(
            if (version > 0) "X5 内核版本 $version（已就绪）"
            else "本机暂无 X5 内核，将回退系统 WebView"
        )
    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 播放器设置
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "播放器",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "选择播放方式，原生播放器兼容性更好",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "原生播放器" to PlayerType.EXOPLAYER,
                                "网页播放" to PlayerType.WEBVIEW
                            ).forEach { (label, type) ->
                                val isSelected = playerType == type
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        playerType = type
                                        prefs.edit().putInt("player_type", type.ordinal).apply()
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (playerType == PlayerType.EXOPLAYER)
                                "使用 ExoPlayer 原生播放 m3u8 直播流，不依赖 WebView，低版本系统也能用"
                            else
                                "使用 WebView 加载网页播放，兼容更多频道但需要较新版本 WebView",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // X5 内核设置
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "X5 内核",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "X5 自带统一内核，不依赖系统 WebView，可改善老旧盒子的网页兼容性",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (useX5Kernel) "已启用 X5 内核" else "已关闭，使用系统 WebView",
                                    fontSize = 16.sp
                                )
                                Text(
                                    kernelStatus,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Switch(
                                checked = useX5Kernel,
                                onCheckedChange = {
                                    useX5Kernel = it
                                    prefs.edit().putBoolean("use_x5_kernel", it).apply()
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "切换后会自动重建播放页。若提示“X5 未生效”，" +
                                "多为离线内核刚装好需重启 App（首次安装成功后会自动重启一次）。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 字体大小设置
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "字体大小",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "当前: ${fontSize}sp（频道信息浮层）",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("18", "22", "25", "30").forEach { size ->
                                FilterChip(
                                    selected = fontSize == size,
                                    onClick = {
                                        fontSize = size
                                        prefs.edit().putString("text_size", size).apply()
                                    },
                                    label = { Text("${size}sp") },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 直接换台模式
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "直接换台模式",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (directChannelChange) "上下键直接换台" else "上下键显示频道列表",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Switch(
                            checked = directChannelChange,
                            onCheckedChange = {
                                directChannelChange = it
                                prefs.edit().putBoolean("direct_channel_change", it).apply()
                            }
                        )
                    }
                }
            }

            // 显示节目信息
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "显示频道信息",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (showProgramInfo) "切换频道时显示频道名称" else "不显示频道信息",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Switch(
                            checked = showProgramInfo,
                            onCheckedChange = {
                                showProgramInfo = it
                                prefs.edit().putBoolean("show_program_info", it).apply()
                            }
                        )
                    }
                }
            }

            // 浮层显示时长
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "浮层显示时长",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${overlayDuration} 秒",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        DurationStepper(
                            value = overlayDuration,
                            range = 2..10,
                            onValueChange = {
                                overlayDuration = it
                                prefs.edit().putInt("overlay_duration", it).apply()
                            }
                        )
                    }
                }
            }

            // 按键说明
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "按键说明",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf(
                            "↑ / ↓" to "上/下一个频道（或列表移动）",
                            "← / →" to "调出频道列表（或切换分类）",
                            "确认/OK" to "打开频道列表 / 选择",
                            "菜单" to "打开功能菜单",
                            "数字键 0-9" to "输入频道号快速换台",
                            "返回" to "关闭浮层 / 返回"
                        ).forEach { (key, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    key,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(100.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    desc,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // 关于
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "关于",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "萤火 TV v1.1.0\n专为 Android TV 优化\n支持原生播放 + WebView 双模式",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // 重置按钮
            item {
                Button(
                    onClick = {
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
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("恢复默认设置", fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * 时长步进控件（替代 Slider）。
 *
 * 为什么不用 Slider：Material3 的 Slider 会把上下键也当作增减来消费，
 * 结果焦点一旦落到它上面，遥控器上下键就永远在调数值、移不出去，
 * 设置页因此"卡死"在这一项。
 *
 * 这里改成只响应左右键，上下键一律不消费（return false），
 * 交还给 LazyColumn 做焦点移动；并给出明确的焦点高亮与操作提示。
 */
@Composable
private fun DurationStepper(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 自己就是焦点目标：显式 focusable()。焦点高亮由外层"浮层显示时长"卡片
            // 通过 tvFocusHighlight() 提供，这里不再自己画边框，避免叠出双层边框。
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onValueChange((value - 1).coerceIn(range.first, range.last))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onValueChange((value + 1).coerceIn(range.first, range.last))
                        true
                    }
                    // 上下键不消费，交给外层做焦点移动，避免焦点被锁死在这里
                    else -> false
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "－",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                "$value 秒",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 进度条可视化：不依赖 LinearProgressIndicator（不同 BOM 版本签名不同），
            // 自己画更可控
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = 0.35f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((value - range.first).toFloat() / (range.last - range.first))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "← / → 调整，↑ / ↓ 切换设置项",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            "＋",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
