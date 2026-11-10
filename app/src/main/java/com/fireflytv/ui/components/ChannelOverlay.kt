package com.fireflytv.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireflytv.data.Channel
import com.fireflytv.data.ChannelEpg
import com.fireflytv.data.EpgProgram
import com.fireflytv.ui.theme.TvDimens
import com.fireflytv.ui.theme.TvSafeArea
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * 换台时左下角的信息浮层。
 *
 * 两种形态：
 * - **HUD**（[channel] 非空）：台号块 + 台名 + 当前节目 + 进度条 + 接下来，右上角带时钟。
 * - **纯文本**（[channel] 为空）：换台失败、找不到频道这类提示没有对应频道，只显示一行字。
 *
 * 为什么不给 HUD 加阴影：[ChannelOverlay] 原来的注释已经写过，阴影每帧都要在低端 SoC 上
 * 跑一次高斯模糊，而且叠在视频画面上会糊成一团脏边。这里用一块半透明底 + 8dp 圆角就够
 * 把文字从画面里分出来。
 */
@Composable
fun ChannelOverlay(
    channel: Channel?,
    epg: ChannelEpg?,
    message: String,
    programInfo: String,
    isVisible: Boolean,
    fontSize: Int = 22,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = TvSafeArea.Horizontal, vertical = TvSafeArea.Vertical)
        ) {
            if (channel == null) {
                PlainMessage(message, fontSize, Modifier.align(Alignment.BottomStart))
            } else {
                Hud(
                    channel = channel,
                    epg = epg,
                    message = message,
                    programInfo = programInfo,
                    fontSize = fontSize,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
                Clock(Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

// ---------------- HUD ----------------

@Composable
private fun Hud(
    channel: Channel,
    epg: ChannelEpg?,
    message: String,
    programInfo: String,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    val nowSec = rememberNowSeconds()
    val live = epg?.liveAt(nowSec)
    val next = epg?.nextAfter(nowSec)

    Row(
        modifier = modifier
            .widthIn(min = 520.dp, max = 660.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(TvDimens.RowRadius))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelBadge(number = channel.number, size = 88.dp)

        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = (fontSize + 6).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 顺序是有讲究的：换台那一刻宁可先显示"加载中…"，也不要先显示 EPG 里的节目名。
            // 画面还是黑的、底下却信誓旦旦写着"生活早参考"，用户会以为已经播上了。
            // 真正播起来后 ViewModel 会把 message 清空，这里自然切到节目单。
            val secondary = when {
                message.isNotBlank() -> message.lineSequence().last().trim()
                live != null -> live.title
                // 卫视没有接口，退回 WebView 注入 JS 抠出来的那一行
                programInfo.isNotBlank() -> programInfo.lineSequence().first()
                else -> ""
            }
            val isLoading = message.isNotBlank()

            if (secondary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = secondary,
                    // 当前节目用纯白，"加载中…"压暗一档，避免和台名抢注意力
                    color = if (isLoading) Color.White.copy(alpha = 0.6f) else Color.White,
                    fontSize = fontSize.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (live != null) {
                Spacer(Modifier.height(14.dp))
                ProgressBar(live.progressAt(nowSec))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatHm(live.startSec),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = (fontSize - 6).coerceAtLeast(12).sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatHm(live.endSec),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = (fontSize - 6).coerceAtLeast(12).sp
                    )
                }
            }

            if (next != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "接下来",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = (fontSize - 6).coerceAtLeast(12).sp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = next.title,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = (fontSize - 4).coerceAtLeast(13).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 台号块的实现已经提到 [com.fireflytv.ui.components.ChannelBadge]，
 * 因为选台面板也要用同一块形状 —— 两处各画一遍迟早会走形。
 */

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    // 轨道和进度条都用 5dp 高、2.5dp 圆角：再细在 1080p 上会缩成一根发丝，
    // 再粗就会和下面两行时间形成一个视觉块，抢走节目名的注意力
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.5.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(2.5.dp))
        )
    }
}

// ---------------- 纯文本形态 ----------------

@Composable
private fun PlainMessage(message: String, fontSize: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(TvDimens.RowRadius))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            // 行高随字号等比放大，多行消息不会挤在一起
            lineHeight = (fontSize * 1.35f).sp
        )
    }
}

// ---------------- 时钟 ----------------

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val nowSec = rememberNowSeconds()
    val calendar = remember(nowSec) {
        Calendar.getInstance().apply { timeInMillis = nowSec * 1000 }
    }
    val hh = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val mm = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')

    Text(
        text = "$hh:$mm",
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(TvDimens.RowRadius))
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

// ---------------- 时间 ----------------

/**
 * 每秒刷新一次的当前时间（秒）。
 *
 * 只在浮层可见时跑（[AnimatedVisibility] 的内容在 hidden 时不参与组合），
 * 显示时长默认 5 秒，最多跑几次就随浮层一起停。
 */
@Composable
private fun rememberNowSeconds(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    return now
}

/** epoch 秒 → "20:14"，按设备本地时区。 */
private fun formatHm(epochSec: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = epochSec * 1000 }
    val h = c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
    return "$h:$m"
}
