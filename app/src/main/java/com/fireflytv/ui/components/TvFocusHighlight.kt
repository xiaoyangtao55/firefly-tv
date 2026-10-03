package com.fireflytv.ui.components

import androidx.compose.foundation.border
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
 * （设置页尤其明显）。这里在焦点进入时画一圈主题色边框，
 * 未聚焦时画一圈透明边框占位，避免聚焦瞬间布局抖动。
 *
 * 两个关键点，改动前请先读完：
 *
 * 1. 用 hasFocus 而不是 isFocused。hasFocus 表示"自己或任一子节点"获得焦点，
 *    所以把本修饰符套在卡片这类容器上时，内层真正可聚焦的控件
 *    （Switch / FilterChip / 自定义步进器）拿到焦点，整张卡片就会亮起边框。
 *    若用 isFocused，容器自己不是焦点目标，边框永远不会出现。
 *
 * 2. **不要**在这里加 focusable()。加了之后容器本身会变成额外的焦点停留点，
 *    和内层控件抢焦点（遥控器要多按一次才能进到控件上）；而且当内层控件
 *    自己也画边框时会叠出双层边框。需要自己作为焦点目标的控件，
 *    请显式调用 Modifier.focusable()。
 *
 * 注意：内部用 remember 记录焦点状态，因此必须标记为 @Composable。
 */
@Composable
fun Modifier.tvFocusHighlight(): Modifier {
    var hasFocus by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { hasFocus = it.hasFocus }
        .border(
            width = if (hasFocus) 4.dp else 0.dp,
            color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(12.dp)
        )
}
