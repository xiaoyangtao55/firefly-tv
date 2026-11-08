package com.fireflytv.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 10-foot 安全区（overscan）。
 *
 * 1080p 下 5% overscan ≈ 96px / 54px，按 xhdpi 换算就是 48dp / 27dp。老电视、AV 输入、
 * 部分运营商的机顶盒会把边缘切掉 3–5%，所以**任何贴着屏幕边缘的内容**都要用这两个值，
 * 不要写 16.dp / 32.dp 这种手机尺度。
 *
 * 用法：
 * - 整页滚动容器：`contentPadding = PaddingValues(horizontal = Horizontal, vertical = Vertical)`
 * - 浮层容器：`Modifier.padding(horizontal = Horizontal, vertical = Vertical)`
 * - 贴边面板（如左侧频道抽屉）：面板本身贴边，但内部的标题 / 列表项 / 分割线要用
 *   `padding(start = Horizontal, end = 16.dp)`
 */
object TvSafeArea {
    val Horizontal = 48.dp
    val Vertical = 27.dp
}
