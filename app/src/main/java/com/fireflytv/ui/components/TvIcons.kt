package com.fireflytv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 项目实际用到、但不在 material-icons-core 里的图标。
 *
 * 之前依赖的是 material-icons-extended（5000+ 图标），实际只用了 7 个，
 * 却让 debug 包多背了 8–10MB 的 dex。现在换成 core（约 40 个常用图标）
 * + 这里手绘的这一个，path 数据取自 Material Icons 官方 24dp 资源。
 *
 * 如果以后还要新图标：先查它在不在 core（Warning / Refresh / Settings /
 * PlayArrow / ArrowBack 都在），不在再按同样格式加到这里，
 * 不要再把 extended 整体加回来。
 */
object TvIcons {

    /** 切换播放器内核用（swap_horiz） */
    val SwapHoriz: ImageVector by lazy {
        icon(
            name = "SwapHoriz",
            pathData = "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3z" +
                "M21 9l-3.99-4v3H10v2h7.01v3L21 9z"
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            // 填充色只是兜底：Icon() 会用 LocalContentColor 重新着色
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
            name = name
        ).build()
}
