package com.fireflytv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import com.fireflytv.ui.theme.TvDimens

/**
 * TV 设置页的一行：左边标题（可带说明），右边一个值。
 *
 * 替代"一张 Card 里塞好几个控件"的写法。手机上一张卡能装下三行设置，
 * 是因为手指可以精确点；电视上整行就是一个焦点目标，一屏放 6~7 行刚好，
 * 再多就找不到自己在哪。
 *
 * 三个设计上的刻意选择，改动前请先读完：
 *
 * 1. **不用 Switch**。遥控器切换开关只能按 OK，但 Switch 的焦点态画在 thumb 上，
 *    用户会以为要按方向键拨它。改成右端一个「开 / 关」文字，左右键直接切 ——
 *    语义明确，而且整行只有这一个焦点目标，方向键不会被控件吃掉。
 *
 * 2. **反白用 background / onBackground 对调**，不是硬编码 White/Black。
 *    FireflyTVTheme 留了 darkTheme=false 的口子，硬编码的话浅色主题下
 *    会变成白底白字。这里用的是你自己主题里已有的一对语义色：
 *    background=Black / onBackground=White，反白就是把这俩对调。
 *
 * 3. **左右键只在真的绑了动作时才消费**。纯展示的行（onLeft/onRight 都为 null）
 *    必须返回 false，否则焦点会卡死在这一行，上下键也出不去。
 *    这就是 Slider 那个坑的通用解法：不要吞掉自己用不上的按键。
 */
@Composable
fun TvSettingRow(
    title: String,
    value: String = "",
    modifier: Modifier = Modifier,
    supporting: String? = null,
    /**
     * 右端的自定义控件（例如分段条）。它**不是焦点目标**，只是显示；
     * 左右键仍然由本行的 onLeft/onRight 消费，所以焦点图里这一行依然只有一个点。
     * 参数 focused 用于让控件跟着整行反白。
     */
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
    /** 危险项（如恢复默认）。未聚焦时标题用 error 色，聚焦反白后回到正常前景色。 */
    danger: Boolean = false,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }

    val background = if (focused) {
        MaterialTheme.colorScheme.onBackground
    } else {
        MaterialTheme.colorScheme.surface
    }
    val foreground = if (focused) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (supporting == null) TvDimens.RowHeight else TvDimens.RowHeightTall)
            // 背景在最外层：反白要铺满整行（含内边距），而不是只包住文字。
            // 圆角 8dp：只做"边界不硬"，不做手机端那种大圆角（见 TvDimens.RowRadius）。
            .background(background, RoundedCornerShape(TvDimens.RowRadius))
            // onKeyEvent 在 focusable 外层，按键从焦点节点冒泡上来才能收到。
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let { it(); true } ?: false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let { it(); true } ?: false
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> onSelect?.let { it(); true } ?: false
                    else -> false
                }
            }
            // onFocusChanged 必须在 focusable 之前（外层）才能观察到它创建的焦点目标。
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // 内边距放最内层：焦点区和反白块都覆盖整行，只有文字缩进。
            .padding(horizontal = TvDimens.RowPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                // 反白时不能用红色：白底上的红字对比度不够，而且整块白底已经把
                // "当前焦点"这个信息表达清楚了，危险色只在未聚焦时起作用。
                color = if (danger && !focused) MaterialTheme.colorScheme.error else foreground
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    // 反白时主次仍然要分层：纯黑说明文字会和标题一样重，看着像两行标题。
                    color = if (focused) {
                        foreground.copy(alpha = 0.64f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        if (trailing != null) {
            trailing(focused)
        } else if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = foreground
            )
        }
    }
}
