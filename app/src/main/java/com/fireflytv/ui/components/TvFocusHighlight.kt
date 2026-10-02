package com.fireflytv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * TV 遥控器用的焦点高亮。
 *
 * 电视上没有鼠标，若没有明显的焦点指示，用户根本看不出当前选中的是哪一项
 * （设置页尤其明显）。这里在获得焦点时画一圈主题色边框，
 * 未聚焦时画一圈透明边框占位，避免聚焦瞬间布局抖动。
 *
 * 注意：内部用 remember 记录焦点状态，因此必须标记为 @Composable。
 */
@Composable
fun Modifier.tvFocusHighlight(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .border(
            width = if (focused) 4.dp else 0.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(12.dp)
        )
}
