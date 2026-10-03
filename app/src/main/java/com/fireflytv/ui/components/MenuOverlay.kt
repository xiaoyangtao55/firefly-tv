package com.fireflytv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MenuItem(
    val id: Int,
    val name: String,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit
)

/**
 * 底部功能菜单。
 *
 * 焦点模型：整个菜单**只有外层 Surface 一个焦点目标**，菜单项不可聚焦
 * （见 MenuButton 的注释）。左右键由外层 onKeyEvent 直接改 selectedIndex，
 * 所以"高亮的那一项"和"确认键作用的那一项"永远是同一个，不会脱节。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MenuOverlay(
    items: List<MenuItem>,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var lastKeyTime by remember { mutableLongStateOf(0L) }

    // 菜单项显示后自动请求焦点
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        // 布局还没完成时第一次请求可能落空，等一帧再试一次
        if (!focusRequester.requestFocus()) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }

    fun navigate(direction: Int) {
        if (items.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastKeyTime < 200) return
        lastKeyTime = currentTime

        selectedIndex = ((selectedIndex + direction) % items.size + items.size) % items.size
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .shadow(8.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false

                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            navigate(-1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            navigate(1)
                            true
                        }
                        // 菜单是横向的，上下键没有语义，必须消费掉。
                        // 以前这里返回 false，事件会被交给系统做焦点搜索，
                        // 焦点可能从菜单逃到别处，之后按确认键就作用在错误目标上。
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN -> true
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            if (selectedIndex in items.indices) {
                                items[selectedIndex].onClick()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_MENU -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items.forEachIndexed { index, item ->
                    MenuButton(
                        item = item,
                        isSelected = index == selectedIndex,
                        // 刻意不传 onClick：Surface 一旦带 onClick 就会变成可聚焦节点，
                        // 和外层 Surface 抢焦点，导致高亮与实际焦点脱节。
                        // 确认键由外层 onKeyEvent 统一分发，菜单项只负责显示。
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 菜单项：只负责显示，**不可聚焦**。
 *
 * 它是纯展示单元，高亮完全由 isSelected 决定。若让它自己可聚焦，
 * 它和外层 Surface 会形成两个焦点目标，遥控器可能在两者之间跳，
 * 出现"高亮在 A、焦点在 B"的状态。
 */
@Composable
fun MenuButton(
    item: MenuItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = if (isSelected) 0.dp else 4.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.size(32.dp)) {
                item.icon()
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                item.name,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
