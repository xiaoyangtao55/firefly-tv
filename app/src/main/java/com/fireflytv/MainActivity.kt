package com.fireflytv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fireflytv.ui.screens.PlayerScreen
import com.fireflytv.ui.screens.SettingsActivity
import com.fireflytv.ui.theme.FireflyTVTheme
import com.fireflytv.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    // MainViewModel 是 AndroidViewModel，Activity 的默认工厂就能创建，不需要自定义 Factory。
    // 这里与 PlayerScreen 里的 viewModel() 拿到的是同一个实例（同作用域、同默认 key）。
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FireflyTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    PlayerScreen(
                        onOpenSettings = { openSettings() }
                    )
                }
            }
        }
    }

    // 回到桌面或进入设置页时暂停播放，回来再继续
    override fun onStart() {
        super.onStart()
        viewModel.onForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onForegroundChanged(false)
        super.onStop()
    }

    /**
     * 兜底处理。能走到这里说明按键没有被 Compose 消费 —— 通常是焦点丢了，
     * 或者部分盒子在系统层吞掉了菜单键。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && event?.repeatCount == 0) {
            viewModel.toggleMenu()
            return true
        }
        // 请求播放界面重新拿回焦点，否则之后每次按键都会失效
        viewModel.requestFocusRecovery()
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
}
