package com.fireflytv.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireflytv.ui.theme.TvDimens
import com.fireflytv.ui.theme.TvSafeArea

/**
 * 数字直接换台时的输入指示（顶部居中"换台：23"）。
 *
 * 视觉与 [ChannelOverlay] 的台号块保持一致：0.85 纯黑 + 8dp 圆角，无阴影。
 * 原来这里是 shadow(12dp) + 12dp 圆角 —— 一个只会停留两秒的提示框，
 * 却要每帧画一次高斯模糊，是这套 UI 里性价比最差的一处。
 */
@Composable
fun NumberInputOverlay(
    input: String,
    isVisible: Boolean,
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
                .padding(horizontal = TvSafeArea.Horizontal, vertical = TvSafeArea.Vertical),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.85f),
                        RoundedCornerShape(TvDimens.RowRadius)
                    )
                    .padding(horizontal = 32.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "换台：$input",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }
        }
    }
}
