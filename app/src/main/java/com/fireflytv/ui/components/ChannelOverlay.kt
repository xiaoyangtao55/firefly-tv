package com.fireflytv.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireflytv.ui.theme.TvSafeArea

@Composable
fun ChannelOverlay(
    message: String,
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
                .padding(horizontal = TvSafeArea.Horizontal, vertical = TvSafeArea.Vertical),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier
                    // 原来这里是 shadow(8.dp)：一张浮在全屏视频上的小卡片，为了这点阴影
                    // 每帧都要在低端 SoC 上跑一次高斯模糊。电视端用描边区分层次就够，
                    // 而且也避免阴影在视频画面上糊成一团脏边。
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp)
                    )
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
    }
}