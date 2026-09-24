package com.cctv_view.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cctv_view.data.Channel
import com.cctv_view.data.ChannelCategory
import com.cctv_view.data.ChannelRepository
import com.cctv_view.data.PlayerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentChannel: Channel? = null,
    val isChangingChannel: Boolean = false,
    val isPlaying: Boolean = false,
    val showChannelList: Boolean = false,
    val showMenu: Boolean = false,
    val showNumberInput: Boolean = false,
    val numberInputBuffer: String = "",
    val showOverlay: Boolean = false,
    val overlayMessage: String = "",
    val channelCategory: ChannelCategory = ChannelCategory.CCTV,
    val programInfo: String = "",
    // 每次需要真正重建媒体源（换台 / 刷新 / 重试）时递增。
    // 播放器组件必须把它作为 key 的一部分，否则传入同一个 Channel 实例时
    // data class 相等会让 DisposableEffect/LaunchedEffect 不重新执行。
    val reloadToken: Int = 0,
    val overlayDuration: Int = 5,
    // 频道信息浮层的字号（sp），来自设置页的"字体大小"，默认 22
    val overlayTextSize: Int = 22,
    val playerType: PlayerType = PlayerType.EXOPLAYER,
    val errorMessage: String = "",
    val showError: Boolean = false,
    // 用上下键打开频道列表时要预选/滚动到的频道；null 表示预选当前播放的频道
    val pendingSelectionId: Int? = null,
    // App 是否处于前台。离开前台要暂停播放，避免退回桌面后还在出声
    val isForeground: Boolean = true,
    // 递增表示"请播放界面重新获取焦点"：回到前台、或发现按键没被消费（焦点丢了）时
    val focusRequestToken: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChannelRepository(application)
    private val prefs = application.getSharedPreferences("cctv_view", android.content.Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var numberInputJob: kotlinx.coroutines.Job? = null

    /**
     * 设置页写的是同一个 SharedPreferences（同进程、同名），
     * 监听它就能让字号 / 浮层时长 / 播放器的改动立刻生效，而不用等下次启动。
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // key 为 null 表示整个文件被 clear()（恢复默认设置）
            if (key == null || key in SETTING_KEYS) loadSettings()
        }

    init {
        loadSettings()
        loadLastChannel()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    private fun loadSettings() {
        val overlayDuration = prefs.getInt("overlay_duration", 5)
        val playerTypeIndex = prefs.getInt("player_type", PlayerType.EXOPLAYER.ordinal)
        val playerType = try {
            PlayerType.entries[playerTypeIndex]
        } catch (e: Exception) {
            PlayerType.EXOPLAYER
        }
        // 设置页用字符串存字号（18/22/25/30），这里做一次容错解析
        val overlayTextSize = prefs.getString("text_size", null)
            ?.toIntOrNull()
            ?.takeIf { it in 12..48 }
            ?: DEFAULT_OVERLAY_TEXT_SIZE

        _uiState.update {
            it.copy(
                overlayDuration = overlayDuration,
                playerType = playerType,
                overlayTextSize = overlayTextSize
            )
        }
    }

    private companion object {
        /** 与 PlayerUiState.overlayTextSize 的默认值、设置页的默认值保持一致 */
        const val DEFAULT_OVERLAY_TEXT_SIZE = 22

        /** 会影响播放界面表现的设置项 */
        val SETTING_KEYS = setOf("overlay_duration", "player_type", "text_size")
    }

    fun savePlayerType(type: PlayerType) {
        prefs.edit().putInt("player_type", type.ordinal).apply()
        _uiState.update { it.copy(playerType = type) }
    }

    private fun loadLastChannel() {
        val lastChannelId = prefs.getInt("last_channel_id", 0)
        val channel = repository.getChannelById(lastChannelId) ?: repository.getAllChannels().firstOrNull()
        _uiState.update { it.copy(currentChannel = channel) }
    }

    fun saveLastChannel() {
        _uiState.value.currentChannel?.let { channel ->
            prefs.edit().putInt("last_channel_id", channel.id).apply()
        }
    }

    fun changeChannel(channel: Channel) {
        _uiState.update {
            it.copy(
                currentChannel = channel,
                isChangingChannel = true,
                reloadToken = it.reloadToken + 1,
                programInfo = "",
                overlayMessage = "${channel.name}\n加载中...",
                showOverlay = true,
                showError = false,
                errorMessage = "",
                isPlaying = false
            )
        }
        saveLastChannel()

        // 本次加载的令牌。换台/刷新/重试都会产生新令牌，
        // 旧的异步任务发现令牌已过期就直接放弃，避免"上一个频道的超时"误伤当前频道。
        val token = _uiState.value.reloadToken

        // 原生播放器会自动回调状态，这里设置超时保护
        viewModelScope.launch {
            delay(8000)
            val state = _uiState.value
            if (state.reloadToken == token && state.isChangingChannel && !state.isPlaying) {
                _uiState.update {
                    it.copy(
                        isChangingChannel = false,
                        showError = true,
                        errorMessage = "加载超时，请尝试切换播放源或检查网络",
                        showOverlay = false
                    )
                }
            }
        }

        // 定时隐藏浮层
        val duration = (_uiState.value.overlayDuration * 1000L)
        viewModelScope.launch {
            delay(duration)
            val state = _uiState.value
            if (state.reloadToken == token && state.showOverlay && state.isPlaying) {
                _uiState.update { it.copy(showOverlay = false) }
            }
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean, message: String) {
        if (isPlaying) {
            val channelName = _uiState.value.currentChannel?.name ?: ""
            val showProgramInfo = prefs.getBoolean("show_program_info", true)
            _uiState.update {
                it.copy(
                    isChangingChannel = false,
                    isPlaying = true,
                    showError = false,
                    errorMessage = "",
                    overlayMessage = if (showProgramInfo) channelName else "",
                    showOverlay = showProgramInfo
                )
            }
            // 定时隐藏浮层
            if (showProgramInfo) {
                val duration = (_uiState.value.overlayDuration * 1000L)
                viewModelScope.launch {
                    delay(duration)
                    _uiState.update { state -> state.copy(showOverlay = false) }
                }
            }
        } else if (message.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    overlayMessage = message,
                    showOverlay = true
                )
            }
        }
    }

    fun onPlaybackError(errorMsg: String) {
        _uiState.update {
            it.copy(
                isChangingChannel = false,
                isPlaying = false,
                showError = true,
                errorMessage = errorMsg,
                showOverlay = false
            )
        }
    }

    fun retryPlayback() {
        _uiState.value.currentChannel?.let { channel ->
            changeChannel(channel)
        }
    }

    fun nextChannel() {
        val current = _uiState.value.currentChannel ?: return
        val directChange = prefs.getBoolean("direct_channel_change", false)
        if (directChange) {
            val next = repository.getNextChannel(current.id)
            next?.let { changeChannel(it) }
        } else {
            val next = repository.getNextChannel(current.id)
            next?.let {
                showChannelListWithSelection(it.id)
            }
        }
    }

    fun previousChannel() {
        val current = _uiState.value.currentChannel ?: return
        val directChange = prefs.getBoolean("direct_channel_change", false)
        if (directChange) {
            val prev = repository.getPreviousChannel(current.id)
            prev?.let { changeChannel(it) }
        } else {
            val prev = repository.getPreviousChannel(current.id)
            prev?.let {
                showChannelListWithSelection(it.id)
            }
        }
    }

    private fun showChannelListWithSelection(channelId: Int) {
        val channel = repository.getChannelById(channelId)
        val category = if (channel?.category == ChannelCategory.CCTV) ChannelCategory.CCTV else ChannelCategory.LOCAL
        _uiState.update {
            it.copy(
                showChannelList = true,
                channelCategory = category,
                // 让抽屉直接停在目标频道上：按上下键打开列表后，再按确认就能切过去，不用按两下
                pendingSelectionId = channelId
            )
        }
    }

    fun onPageFinished(programInfo: String) {
        val showProgramInfo = prefs.getBoolean("show_program_info", true)
        val message = if (showProgramInfo && programInfo.isNotEmpty()) {
            "${_uiState.value.currentChannel?.name ?: ""}\n$programInfo"
        } else {
            _uiState.value.currentChannel?.name ?: ""
        }
        _uiState.update {
            it.copy(
                isChangingChannel = false,
                isPlaying = true,
                // 页面加载慢时 8 秒超时可能已经弹出错误页了，这里真正加载好就要把它收掉
                showError = false,
                errorMessage = "",
                programInfo = programInfo,
                overlayMessage = message,
                showOverlay = showProgramInfo
            )
        }
        if (showProgramInfo) {
            val duration = (_uiState.value.overlayDuration * 1000L)
            viewModelScope.launch {
                delay(duration)
                _uiState.update { state -> state.copy(showOverlay = false) }
            }
        }
    }

    fun toggleChannelList() {
        _uiState.update {
            it.copy(
                showChannelList = !it.showChannelList,
                showMenu = false,
                showNumberInput = false,
                // 手动打开列表时预选当前正在播放的频道
                pendingSelectionId = null
            )
        }
    }

    fun toggleMenu() {
        _uiState.update {
            it.copy(
                showMenu = !it.showMenu,
                showChannelList = false,
                showNumberInput = false,
                pendingSelectionId = null
            )
        }
    }

    fun hideAllOverlays() {
        _uiState.update {
            it.copy(
                showChannelList = false,
                showMenu = false,
                showNumberInput = false,
                numberInputBuffer = "",
                pendingSelectionId = null
            )
        }
    }

    /** Activity 前后台切换：离开前台时暂停播放，回来再继续 */
    fun onForegroundChanged(foreground: Boolean) {
        if (_uiState.value.isForeground == foreground) return
        _uiState.update {
            it.copy(
                isForeground = foreground,
                // 回到前台时顺带把焦点拿回来（例如从设置页返回）
                focusRequestToken = if (foreground) it.focusRequestToken + 1 else it.focusRequestToken
            )
        }
    }

    /**
     * 按键没有被播放界面消费时调用（多半是焦点丢了）。
     * 通知界面重新请求焦点，让下一次按键能生效；
     * 浮层打开时不动，避免把焦点从频道列表/菜单上抢走。
     */
    fun requestFocusRecovery() {
        val state = _uiState.value
        if (!state.isForeground) return
        if (state.showChannelList || state.showMenu || state.showNumberInput) return
        _uiState.update { it.copy(focusRequestToken = it.focusRequestToken + 1) }
    }

    fun appendNumber(number: Int) {
        val newBuffer = _uiState.value.numberInputBuffer + number.toString()
        _uiState.update { it.copy(numberInputBuffer = newBuffer, showNumberInput = true) }

        numberInputJob?.cancel()

        // 已经输满一个完整频道号的位数，直接换台，不用再等 2 秒
        if (newBuffer.length >= repository.maxChannelNumberDigits) {
            processNumberInput()
            return
        }

        numberInputJob = viewModelScope.launch {
            delay(2000) // 缩短到2秒，响应更快
            processNumberInput()
        }
    }

    /**
     * 确认键立即换台。
     * 之前这里复用了 appendNumber(-1) 来"触发处理"，结果缓冲区被拼成 "1-1" 这种
     * 解析不出来的字符串，toIntOrNull() 返回 null，按确认键永远换不了台。
     */
    fun confirmNumberInput() {
        if (_uiState.value.numberInputBuffer.isEmpty()) return
        numberInputJob?.cancel()
        processNumberInput()
    }

    private fun processNumberInput() {
        val buffer = _uiState.value.numberInputBuffer
        _uiState.update { it.copy(showNumberInput = false, numberInputBuffer = "") }
        if (buffer.isEmpty()) return

        val channel = buffer.toIntOrNull()?.let { repository.getChannelByNumber(it) }
        if (channel != null) {
            changeChannel(channel)
        } else {
            // 以前这里是静默失败：用户按了 99 什么反应都没有
            showTransientMessage("无此频道：$buffer")
        }
    }

    /** 短暂提示（例如数字换台找不到频道），按设置的浮层时长自动隐藏 */
    private fun showTransientMessage(message: String) {
        _uiState.update { it.copy(showOverlay = true, overlayMessage = message) }
        viewModelScope.launch {
            delay(_uiState.value.overlayDuration * 1000L)
            val state = _uiState.value
            // 期间如果换了台，overlayMessage 已被改写，就不要误关浮层
            if (state.showOverlay && state.overlayMessage == message) {
                _uiState.update { it.copy(showOverlay = false) }
            }
        }
    }

    fun clearNumberInput() {
        numberInputJob?.cancel()
        _uiState.update { it.copy(showNumberInput = false, numberInputBuffer = "") }
    }

    fun setChannelCategory(category: ChannelCategory) {
        _uiState.update { it.copy(channelCategory = category) }
    }

    /**
     * 刷新当前频道。
     * reloadToken 会在 changeChannel 里递增，播放器/WebView 据此真正重建媒体源；
     * 之前这里只递增了一个没有任何组件读取的 key，导致刷新是空操作。
     */
    fun refreshPage() {
        val channel = _uiState.value.currentChannel
        if (channel != null) {
            changeChannel(channel)
        } else {
            _uiState.update { it.copy(showOverlay = true, overlayMessage = "没有正在播放的频道") }
        }
    }

    fun getCCTVChannels(): List<Channel> = repository.getCCTVChannels()
    fun getLocalChannels(): List<Channel> = repository.getLocalChannels()
    fun getAllChannels(): List<Channel> = repository.getAllChannels()
}
