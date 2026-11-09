package com.fireflytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.fireflytv.ui.theme.TvDimens

/**
 * 分段选择条（替代 FilterChip）。
 *
 * **它不可聚焦，也不处理按键。** 焦点属于外面那行 [TvSettingRow]，
 * 左右键由那行的 onLeft/onRight 消费后改 selectedIndex。这样做的原因：
 * FilterChip 每个 chip 都是一个可聚焦节点，4 个字号选项会在一行里制造 4 个焦点点，
 * 而上下键在这种情况下语义很模糊（是换行还是换选项？）。
 * 整个控件只有一个焦点点，方向键的语义就永远明确。
 *
 * 配色遵循"与行背景反色"，所以它必须知道自己所在的整行是否聚焦：
 * - 行聚焦（白底黑字）→ 选中段用黑底白字，像白条里挖出一块黑
 * - 行不聚焦（深底白字）→ 选中段用白底黑字
 *
 * 硬编码 White/Black 会在浅色主题下失效，这里全部走语义色。
 *
 * 圆角做在**每个段自身**，不是只裁整条外框。
 * 只裁外框的话，首末段会跟着圆，中间段却是直角 —— 实测在聚焦行上会看到
 * 一块直角黑方块贴在白色圆角行里，像贴上去的补丁，非常生硬。
 * 每段自己带 6dp 圆角后，选中段无论落在哪一段，都是一块完整的小圆角块。
 */
@Composable
fun TvSegmented(
    options: List<String>,
    selectedIndex: Int,
    focused: Boolean,
    modifier: Modifier = Modifier,
    itemWidth: Dp = TvDimens.SegmentWidth,
    itemHeight: Dp = TvDimens.SegmentHeight,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(modifier = modifier) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex

            // 未聚焦时不要用纯白：截图实测发现，整行还是深灰的时候，一个纯白块
            // 会比"整行反白"更抢眼，用户会误以为那块白就是焦点。
            // 改成 12% 白的浅灰块 —— 能看出选的是哪个，但视觉重量低于反白行。
            val background = when {
                selected && focused -> colorScheme.background
                selected -> colorScheme.onSurface.copy(alpha = 0.12f)
                else -> Color.Transparent
            }
            val foreground = when {
                selected && focused -> colorScheme.onBackground
                selected -> colorScheme.onSurface
                // 行反白（白底）时必须是纯黑。第一版用了 55% alpha 的黑，
                // 截图里「原生播放器」几乎消失在白底上，3 米外完全读不出来。
                focused -> colorScheme.background
                else -> colorScheme.onSurfaceVariant
            }

            Box(
                modifier = Modifier
                    .width(itemWidth)
                    .height(itemHeight)
                    .background(background, RoundedCornerShape(TvDimens.SegmentRadius)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground
                )
            }
        }
    }
}
