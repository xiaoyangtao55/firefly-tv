package com.fireflytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireflytv.ui.theme.TvDimens

/**
 * 台号方块。
 *
 * 台标图片没做，原因写在 [com.fireflytv.data.EpgRepository] 的注释里：央视的直播页
 * 本身不放台标（只有播放器和推荐位），真台标在图床 CDN 上但没有公开接口能批量查。
 * 3 米外看，一个方块里写「13」比迷你台标图清楚得多，而且零网络依赖。
 *
 * 换台 HUD 用 88dp，选台面板用 76dp —— 同一套形状只是为了在两处之间建立
 * 「方块 = 台号」的视觉约定，尺寸按各自的信息密度调。
 */
@Composable
fun ChannelBadge(
    number: Int,
    size: Dp = 88.dp,
    fontSize: TextUnit = (size.value * 0.45f).sp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(TvDimens.RowRadius)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number.toString(),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium
        )
    }
}
