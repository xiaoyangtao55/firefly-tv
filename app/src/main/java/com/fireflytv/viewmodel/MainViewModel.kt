package com.fireflytv.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fireflytv.FireflyApplication
import com.fireflytv.data.Channel
import com.fireflytv.data.ChannelCategory
import com.fireflytv.data.ChannelEpg
import com.fireflytv.data.ChannelRepository
import com.fireflytv.data.EpgRepository
import com.fireflytv.data.PlayerType
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
    // 用户手动暂停（菜单“播放/暂停”）。与 isForeground 不同：
    // isForeground 是 App 前后台状态（系统驱动），isPaused 是用户主动行为。
    val isPaused: Boolean = false,
    val showChannelList: Boolean = false,
    val showMenu: Boolean = false,
    val showNumberInput: Boolean = false,
    val numberInputBuffer: String = "",
    val showOverlay: Boolean = false,
    val overlayMessage: String = "",
    /**
     * 非空时浮层画成完整的 HUD（台号块 / 台名 / 当前节目 / 进度条 / 接下来）。
     * 为空则退化成一行文字 —— 「无此频道 99」「没有正在播放的频道」这类提示
     * 没有对应的频道，只能走纯文本。
     */
    val overlayChannel: Channel? = null,
    val channelCategory: ChannelCategory = ChannelCategory.CCTV,
    val programInfo: String = "",
    /** 频道号 → 当天节目单。只有央视有，卫视不在表里。 */
    val epg: Map<Int, ChannelEpg> = emptyMap(),
    // 每次需要真正重建媒体源（换台 / 刷新 / 重试）时递增。
    // 播放器组件必须把它作为 key 的一部分，否则传入同一个 Channel 实例时
    // data class 相等会让 DisposableEffect/LaunchedEffect 不重新执行。
    val reloadToken: Int = 0,
    val overlayDuration: Int = 5,
    // 频道信息浮层的字号（sp），来自设置页的"字体大小"，默认 22
    val overlayTextSize: Int = 22,
    val playerType: PlayerType = PlayerType.WEBVIEW,
    // 网页播放时是否使用 X5 内核；false 则用系统 WebView。
    // 两者是不同的 WebView 类，切换时需要重建 WebView（靠 reloadToken 触发）。
    val useX5Kernel: Boolean = true,
    // 当前网页播放实际跑在 X5 上（由 TVWebView 的 getIsX5Core() 上报）
    val x5Active: Boolean = false,
    // X5 内核版本号，0 表示无 X5 内核（回退系统 WebView）
    val tbsVersion: Int = 0,
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
    private val epgRepository = EpgRepository(application, repository.getAllChannels())
    private val prefs = application.getSharedPreferences("fireflytv", android.content.Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** 上一次主动补拉节目单的时间（秒）。离线时避免每开一次 HUD 就请求一次。 */
    private var lastEpgAttemptSec = 0L

    private var numberInputJob: kotlinx.coroutines.Job? = null

    /**
     * 设置页写的是同一个 SharedPreferences（同进程、同名），
     * 监听它就能让字号 / 浮层时长 / 播放器的改动立刻生效，而不用等下次启动。
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // key 为 null 表示整个文件被 clear()（恢复默认设置）
            if (key == null || key in SETTING_KEYS) {
                loadSettings()
                // 设置页把 X5 从"关"改回"开"时，本进程可能从未初始化过 TBS
                // （启动时开关是关的，Application 里直接跳过了）。
                // 不在这里补一次，TVWebView 会 new 一个未初始化的 X5 WebView 直接抛异常。
                // setUseX5Kernel() 里也有一份同样的补初始化，两处都需要：
                // 那条路径是播放页内切换，这条是跨 Activity 从设置页切换。
                if ((key == null || key == "use_x5_kernel") &&
                    prefs.getBoolean("use_x5_kernel", true)
                ) {
                    (getApplication<Application>() as? FireflyApplication)?.initX5IfNeeded()
                }
            }
        }

    init {
        loadSettings()
        loadLastChannel()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // 进应用就后台拉一次节目单。这个请求不阻塞首帧：UI 先用空表跑起来，
        // 拿到数据后再通过 StateFlow 补上。断网时静默失败，HUD 退化成只有台名。
        refreshEpg()
    }

    /**
     * 拉节目单。
     *
     * 失败时**不覆盖**已有数据 —— 接口抽风的时候，昨天那份旧节目单
     * 也比 HUD 上突然空一块好。
     */
    private fun refreshEpg(force: Boolean = false) {
        viewModelScope.launch {
            val map = epgRepository.load(force)
            if (map.isNotEmpty()) {
                _uiState.update { it.copy(epg = map) }
            }
        }
    }

    /**
     * 打开 HUD 时补一次数据。
     *
     * 主要为了两个场景：一是应用在后台放了很久、跨了零点，缓存里已经是昨天的节目单；
     * 二是首次启动时网络还没就绪，那次请求失败了。
     *
     * 限频 5 分钟：离线设备上每次开 HUD 都发一次请求，除了耗电没有任何意义。
     */
    fun ensureEpgForOverlay(channel: Channel) {
        if (channel.epgId.isNullOrBlank()) return
        val nowSec = System.currentTimeMillis() / 1000
        val epg = _uiState.value.epg[channel.number]
        if (epg?.liveAt(nowSec) != null) return
        if (nowSec - lastEpgAttemptSec < EPG_RETRY_INTERVAL_SEC) return
        lastEpgAttemptSec = nowSec
        refreshEpg(force = true)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    private fun loadSettings() {
        val overlayDuration = prefs.getInt("overlay_duration", 5)
        val playerTypeIndex = prefs.getInt("player_type", PlayerType.WEBVIEW.ordinal)
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
                overlayTextSize = overlayTextSize,
                useX5Kernel = prefs.getBoolean("use_x5_kernel", true)
            )
        }
    }

    private companion object {
        /** 与 PlayerUiState.overlayTextSize 的默认值、设置页的默认值保持一致 */
        const val DEFAULT_OVERLAY_TEXT_SIZE = 22

        /** HUD 发现节目单缺失时，两次主动补拉的最小间隔 */
        const val EPG_RETRY_INTERVAL_SEC = 5 * 60L

        /** 会影响播放界面表现的设置项 */
        val SETTING_KEYS = setOf("overlay_duration", "player_type", "text_size", "use_x5_kernel")
    }

    fun savePlayerType(type: PlayerType) {
        prefs.edit().putInt("player_type", type.ordinal).apply()
        _uiState.update { it.copy(playerType = type) }
    }

    /**
     * 切换 X5 / 系统 WebView。
     * 两者是不同的 WebView 类（com.tencent.smtt.sdk.WebView vs android.webkit.WebView），
     * 不能在同一个 View 上切换，只能靠递增 reloadToken 让播放组件销毁重建。
     */
    fun setUseX5Kernel(useX5: Boolean) {
        prefs.edit().putBoolean("use_x5_kernel", useX5).apply()
        // 从"关闭"切到"开启"时，本进程可能还没初始化过 TBS（Application 里按设置跳过了）。
        // 这里补一次，否则 TVWebView 会去 new 一个未初始化的 X5 WebView。
        if (useX5) (getApplication<Application>() as? FireflyApplication)?.initX5IfNeeded()
        _uiState.update {
            it.copy(
                useX5Kernel = useX5,
                reloadToken = it.reloadToken + 1,
                // 切换内核时清空上一次的状态，避免显示旧内核的信息
                x5Active = false,
                tbsVersion = 0
            )
        }
    }

    /**
     * 播放组件上报实际内核状态。
     * 注意：用户开了 X5 但本机内核不可用时会回退系统 WebView，
     * 这时 x5Active=false，界面需要如实提示，而不是假装在用 X5。
     */
    fun onKernelStatus(isX5: Boolean, version: Int) {
        _uiState.update { it.copy(x5Active = isX5, tbsVersion = version) }
    }

    /** 给界面显示的内核状态文案 */
    fun kernelStatusText(): String = when {
        uiStateValue().playerType != PlayerType.WEBVIEW -> "当前为原生播放器，未使用网页内核"
        !uiStateValue().useX5Kernel -> "已手动关闭 X5，使用系统 WebView"
        uiStateValue().x5Active -> "X5 内核已生效（版本 ${uiStateValue().tbsVersion}）"
        else -> "X5 未生效，已回退系统 WebView（可尝试重启 App 让离线内核生效）"
    }

    private fun uiStateValue(): PlayerUiState = _uiState.value

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
                overlayChannel = channel,
                showOverlay = true,
                showError = false,
                errorMessage = "",
                isPlaying = false,
                // 换台即恢复播放：避免停留在“已暂停”状态却看着新频道的静止画面
                isPaused = false
            )
        }
        saveLastChannel()

        // 本次加载的令牌。换台/刷新/重试都会产生新令牌，
        // 旧的异步任务发现令牌已过期就直接放弃，避免"上一个频道的超时"误伤当前频道。
        val token = _uiState.value.reloadToken

        // 原生播放器会自动回调状态，这里设置超时保护。
        // 两条链路的耗时结构完全不同，不能共用一个值：
        // - ExoPlayer：connect 超时本身就是 10s（见 VideoPlayer 的 DefaultHttpDataSource），
        //   UI 超时必须比它长，否则播放器还没来得及报"网络连不上"，我们先弹了个语焉不详的超时页。
        // - WebView：要等页面 HTML + 播放器脚本 + 视频初始化，慢网下 8 秒必误报，
        //   表现为先闪一次错误页、加载好之后再自愈。
        val timeoutMs = if (_uiState.value.playerType == PlayerType.WEBVIEW) 15_000L else 12_000L
        viewModelScope.launch {
            delay(timeoutMs)
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

        // 定时隐藏浮层（只在本频道确实播起来之后才收，否则保留"加载中…"）
        scheduleOverlayHide(_uiState.value.overlayDuration * 1000L, onlyWhenPlaying = true)
    }

    fun onPlaybackStateChanged(isPlaying: Boolean, message: String) {
        if (isPlaying) {
            val channel = _uiState.value.currentChannel
            val showProgramInfo = prefs.getBoolean("show_program_info", true)
            _uiState.update {
                it.copy(
                    isChangingChannel = false,
                    isPlaying = true,
                    showError = false,
                    errorMessage = "",
                    // 播起来了就把"加载中…"这个临时文案清掉，HUD 的内容交给节目单去填
                    overlayMessage = "",
                    overlayChannel = channel,
                    showOverlay = showProgramInfo
                )
            }
            if (showProgramInfo) {
                channel?.let { ensureEpgForOverlay(it) }
                scheduleOverlayHide(_uiState.value.overlayDuration * 1000L)
            }
        } else if (message.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    overlayMessage = message,
                    // 这里不动 overlayChannel：这个分支是播放器自己报的状态
                    // （"缓冲中…"、"已暂停"），频道并没有变。清掉的话 HUD 会退化成
                    // 一行光秃秃的"缓冲中…"，台号和节目单全没了，比原来更糟。
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

    /**
     * 菜单“播放/暂停”的切换逻辑。
     * 只切换 isPaused 标记，真正的暂停/恢复由 VideoPlayer / TVWebView 监听它去执行；
     * 这样无论是原生播放器还是 WebView 都能统一处理，且切台（changeChannel 会重置 isPaused）
     * 后自动恢复。
     */
    fun togglePlayPause() {
        val next = !_uiState.value.isPaused
        _uiState.update { it.copy(isPaused = next) }
        // 给个明确提示，让用户在没有任何播放控件时也能知道当前状态
        showTransientMessage(if (next) "⏸ 已暂停" else "▶ 继续播放")
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
        val channel = _uiState.value.currentChannel
        _uiState.update {
            it.copy(
                isChangingChannel = false,
                isPlaying = true,
                // 页面加载慢时 8 秒超时可能已经弹出错误页了，这里真正加载好就要把它收掉
                showError = false,
                errorMessage = "",
                // 注入 JS 抠出来的节目名只对广东台那套模板有效，央视现在已经走 EPG 接口。
                // 保留它是为了让没有接口的卫视（广东珠江、大湾区等）HUD 上还能有一行节目名。
                programInfo = programInfo,
                overlayMessage = "",
                overlayChannel = channel,
                showOverlay = showProgramInfo
            )
        }
        if (showProgramInfo) {
            channel?.let { ensureEpgForOverlay(it) }
            scheduleOverlayHide(_uiState.value.overlayDuration * 1000L)
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
        // 这类提示不对应任何频道，必须把 overlayChannel 清掉：
        // 留着的话 HUD 会继续画上一个频道的台号和节目单，和「无此频道 99」并排显示
        _uiState.update {
            it.copy(showOverlay = true, overlayMessage = message, overlayChannel = null)
        }
        scheduleOverlayHide(_uiState.value.overlayDuration * 1000L)
    }

    /**
     * 浮层自动隐藏的令牌。
     *
     * 每安排一次隐藏就 +1，定时器到点后先比对令牌：不等于自己的说明期间
     * 已经来了新的浮层（换台、重新加载、又一条提示），直接放弃。
     *
     * 之前这两处（[onPlaybackStateChanged] / [onPageFinished]）只顾着 delay 完就关，
     * 于是"A 频道的 5 秒定时器"会把"B 频道刚弹出来的台标"提前关掉，
     * 表现就是换台后台标一闪而过。
     */
    private var overlayHideToken = 0L

    private fun scheduleOverlayHide(durationMs: Long, onlyWhenPlaying: Boolean = false) {
        val token = ++overlayHideToken
        viewModelScope.launch {
            delay(durationMs)
            // 期间浮层被重新安排过，让新的那次去决定什么时候关
            if (overlayHideToken != token) return@launch
            val state = _uiState.value
            if (!state.showOverlay) return@launch
            if (onlyWhenPlaying && !state.isPlaying) return@launch
            _uiState.update { it.copy(showOverlay = false) }
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
            _uiState.update {
                it.copy(
                    showOverlay = true,
                    overlayMessage = "没有正在播放的频道",
                    overlayChannel = null
                )
            }
        }
    }

    fun getCCTVChannels(): List<Channel> = repository.getCCTVChannels()
    fun getLocalChannels(): List<Channel> = repository.getLocalChannels()
    fun getAllChannels(): List<Channel> = repository.getAllChannels()
}
