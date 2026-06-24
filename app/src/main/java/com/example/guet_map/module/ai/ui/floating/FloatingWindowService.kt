package com.example.guet_map.module.ai.ui.floating

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.guet_map.R
import com.example.guet_map.databinding.ItemTimetableNavCardBinding
import com.example.guet_map.databinding.LayoutFloatingWindowBinding
import com.example.guet_map.model.Resource
import com.example.guet_map.module.ai.data.model.ChatMessage
import com.example.guet_map.module.ai.data.model.ChatRole
import com.example.guet_map.module.ai.data.repository.ChatRepository
import com.example.guet_map.module.ai.domain.schedule.TimetableScheduleManager.NavigationTiming
import com.example.guet_map.module.ai.domain.service.AiService
import com.example.guet_map.module.ai.ui.chat.ChatMessageAdapter
import com.example.guet_map.module.ai.ui.chat.ChatUiEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI 悬浮窗服务
 * 提供悬浮窗形式的 AI 助手界面，支持：
 * - 靠边收起（收起时仅显示窄竖条，展开时显示完整窗口）
 * - 课表导航建议卡片（悬浮窗消息列表顶部）
 * - 课表导入入口
 */
@AndroidEntryPoint
class FloatingWindowService : Service(), LifecycleOwner {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var _binding: LayoutFloatingWindowBinding? = null
    private val binding get() = _binding!!

    private var _navCardBinding: ItemTimetableNavCardBinding? = null
    private val navCardBinding get() = _navCardBinding!!

    private lateinit var lifecycleRegistry: LifecycleRegistry

    @Inject
    lateinit var aiService: AiService

    @Inject
    lateinit var chatRepository: ChatRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var messageAdapter: ChatMessageAdapter

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    private val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>()
    private val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    // SharedPreferences 会话持久化
    private val prefs by lazy { getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE) }
    private val sessionId: String
        get() {
            var id = prefs.getString(KEY_SESSION_ID, null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_SESSION_ID, id).apply()
            }
            return id
        }

    // 窗口状态
    private var isExpanded = true
    private var isPinned = false
    private var pinnedSide = Gravity.END

    // 展开/收起尺寸（dp）
    private val expandedWidthDp = 280
    private val expandedHeightDp = 400
    private val collapsedWidthDp = 48
    private val collapsedHeightDp = 200

    // 触摸事件
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var activeTouchListener: View.OnTouchListener? = null

    // 是否正在编辑输入框
    private var isInputFocused = false

    // 当前显示的导航卡片
    private var currentNavCard: ChatUiEvent.ShowTimetableNavigationCard? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 助手运行中")
            .setContentText("点击可展开悬浮窗口")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        startFloatingWindow()
        return START_STICKY
    }

    private fun startFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 如果视图已存在（服务被重新触发），重置到可见的默认位置
        if (floatingView != null) {
            resetToDefaultPosition()
            return
        }

        // 使用 ContextThemeWrapper 确保 Material 主题可用（修复 MaterialButton 崩溃）
        _binding = LayoutFloatingWindowBinding.inflate(
            LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_GUET_Map))
        )
        floatingView = binding.root

        // 导航卡片 binding（通过 include 的 id 访问）
        _navCardBinding = ItemTimetableNavCardBinding.bind(binding.navCard.root)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val expW = dpToPx(expandedWidthDp)
        val expH = dpToPx(expandedHeightDp)

        floatingParams = WindowManager.LayoutParams(
            expW,
            expH,
            screenWidth - expW - dpToPx(16),
            screenHeight / 3,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            windowAnimations = android.R.style.Animation_Dialog
        }

        setupViews()
        loadChatHistory()
        observeState()

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        try {
            windowManager?.addView(floatingView, floatingParams)
        } catch (e: Exception) {
            Toast.makeText(this, "无法创建悬浮窗: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** 重置悬浮窗到默认可见位置（修复拖出屏幕后找不回来的问题） */
    private fun resetToDefaultPosition() {
        if (!isExpanded) {
            expandFromEdge()
        }
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        floatingParams?.let { params ->
            params.width = dpToPx(expandedWidthDp)
            params.height = dpToPx(expandedHeightDp)
            params.x = screenWidth - params.width - dpToPx(16)
            params.y = screenHeight / 3
            windowManager?.updateViewLayout(floatingView, params)
        }
        isExpanded = true
        binding.mainCardContent.visibility = View.VISIBLE
        binding.root.setBackgroundColor(0x00000000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        messageAdapter = ChatMessageAdapter()
        binding.recyclerViewMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@FloatingWindowService).apply {
                stackFromEnd = true
            }
        }

        // 发送按钮
        binding.buttonSend.setOnClickListener {
            val message = binding.editTextMessage.text.toString()
            if (message.isNotBlank()) {
                sendMessage(message)
                binding.editTextMessage.text?.clear()
                hideInputMethod()
            }
        }

        // 关闭按钮
        binding.buttonClose.setOnClickListener {
            stopSelf()
        }

        // 最小化/收起按钮
        binding.buttonMinimize.setOnClickListener {
            collapseToEdge()
        }

        // 课表导入按钮
        binding.buttonImportTimetable.setOnClickListener {
            openTimetableImport()
        }

        // 输入框焦点
        binding.editTextMessage.setOnFocusChangeListener { _, hasFocus ->
            isInputFocused = hasFocus
            updateWindowFocusability()
        }

        binding.editTextMessage.setOnClickListener {
            requestInputFocus()
        }

        // 标题栏拖动
        binding.titleBar.setOnTouchListener(createDragTouchListener())

        // 收起状态下把手区域点击/拖动展开
        setupRootTouchForCollapsed()

        // 导航卡片按钮
        setupNavCardButtons()
    }

    private fun createDragTouchListener(): View.OnTouchListener {
        var touchListener: View.OnTouchListener? = null
        touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams?.x ?: 0
                    initialY = floatingParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    activeTouchListener = touchListener
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeTouchListener != touchListener) return@OnTouchListener true
                    if (!isDragging &&
                        (kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                                kotlin.math.abs(event.rawY - initialTouchY) > 10)
                    ) {
                        isDragging = true
                    }
                    if (isDragging) {
                        floatingParams?.let { params ->
                            val displayMetrics = resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val currentRightEdge = screenWidth - params.x
                            val newRightEdge = (currentRightEdge + event.rawX - initialTouchX).toInt()
                            params.x = screenWidth - newRightEdge
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            clampPosition(params)
                            windowManager?.updateViewLayout(floatingView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (activeTouchListener == touchListener) {
                        if (!isDragging) {
                            if (v == binding.titleBar) {
                                binding.mainCard.performClick()
                            }
                        } else {
                            floatingParams?.let { clampPosition(it) }
                            windowManager?.updateViewLayout(floatingView, floatingParams)
                        }
                        isDragging = false
                        activeTouchListener = null
                    }
                    true
                }
                else -> false
            }
        }
        return touchListener!!
    }

    private fun setupRootTouchForCollapsed() {
        var touchListener: View.OnTouchListener? = null
        touchListener = View.OnTouchListener { _, event ->
            if (isExpanded) {
                false
            } else when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams?.x ?: 0
                    initialY = floatingParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    activeTouchListener = touchListener
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeTouchListener != touchListener) {
                        true
                    } else {
                        if (!isDragging &&
                            (kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                                    kotlin.math.abs(event.rawY - initialTouchY) > 10)
                        ) {
                            isDragging = true
                        }
                        if (isDragging) {
                            floatingParams?.let { params ->
                                val displayMetrics = resources.displayMetrics
                                val screenWidth = displayMetrics.widthPixels
                                val currentRightEdge = screenWidth - params.x
                                val newRightEdge = (currentRightEdge + event.rawX - initialTouchX).toInt()
                                params.x = screenWidth - newRightEdge
                                params.y = initialY + (event.rawY - initialTouchY).toInt()
                                clampPosition(params)
                                windowManager?.updateViewLayout(floatingView, params)
                            }
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (activeTouchListener == touchListener) {
                        if (isDragging) {
                            // 拖拽结束：吸附到最近边缘
                            snapToNearestEdge()
                        } else {
                            expandFromEdge()
                        }
                        isDragging = false
                        activeTouchListener = null
                    }
                    true
                }
                else -> false
            }
        }
        binding.root.setOnTouchListener(touchListener)
    }

    private fun setupNavCardButtons() {
        navCardBinding.buttonConfirm.setOnClickListener {
            val card = currentNavCard ?: return@setOnClickListener
            hideNavCard()
            // 通过广播通知 Activity，由 Activity 处理导航
            card.targetLocationId?.let { locId ->
                val intent = Intent(ACTION_TIMETABLE_NAVIGATE).apply {
                    putExtra(EXTRA_LOCATION_ID, locId)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        navCardBinding.buttonCancel.setOnClickListener {
            hideNavCard()
            currentNavCard = null
        }
    }

    /**
     * 显示课表导航建议卡片
     */
    private fun showNavCard(event: ChatUiEvent.ShowTimetableNavigationCard) {
        currentNavCard = event

        navCardBinding.textCourseName.text = event.courseName
        navCardBinding.textClassroom.text = event.classroomName
        navCardBinding.textDayPeriod.text = "${event.dayOfWeek} ${event.formatTime}"
        navCardBinding.textDepartureTime.text = event.departureTime
        navCardBinding.textArriveTime.text = event.arriveTime
        navCardBinding.textWalkingMinutes.text = "${event.walkingMinutes}分钟"
        navCardBinding.textWarning.text = event.warningMessage

        // 时机标签
        val timing = try {
            NavigationTiming.valueOf(event.timing)
        } catch (_: Exception) {
            NavigationTiming.Idle
        }
        val (tagText, tagColor) = when (timing) {
            NavigationTiming.BeforeClass30Min -> "即将上课" to "#F57C00"
            NavigationTiming.DuringClass -> "上课中" to "#388E3C"
            NavigationTiming.AfterClass -> "下课了" to "#1976D2"
            NavigationTiming.Idle -> "暂无课程" to "#9E9E9E"
        } ?: ("暂无课程" to "#9E9E9E")
        navCardBinding.textTiming.text = tagText
        navCardBinding.textTiming.background.setTint(android.graphics.Color.parseColor(tagColor))

        binding.navCard.root.visibility = View.VISIBLE

        val itemCount = messageAdapter.itemCount
        if (itemCount > 0) {
            binding.recyclerViewMessages.smoothScrollToPosition(itemCount - 1)
        }
    }

    /**
     * 隐藏导航卡片
     */
    private fun hideNavCard() {
        currentNavCard = null
        binding.navCard.root.visibility = View.GONE
    }

    /**
     * 收起悬浮窗到屏幕边缘（窄竖条）
     */
    private fun collapseToEdge() {
        if (!isExpanded) return
        isExpanded = false

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val targetW = dpToPx(collapsedWidthDp)
        val targetH = dpToPx(collapsedHeightDp)
        val targetX = if (pinnedSide == Gravity.END) {
            screenWidth - targetW - dpToPx(4)
        } else {
            dpToPx(4)
        }
        val targetY = floatingParams?.y ?: (screenHeight / 3)

        animateWindowResize(
            toWidth = targetW,
            toHeight = targetH,
            toX = targetX,
            toY = targetY
        ) {
            binding.mainCardContent.visibility = View.GONE
            binding.root.setBackgroundResource(android.R.drawable.edit_text)
            binding.root.setBackgroundColor(0xE86200EE.toInt())
        }
    }

    /**
     * 从边缘展开悬浮窗
     */
    private fun expandFromEdge() {
        if (isExpanded) return
        isExpanded = true

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        val targetW = dpToPx(expandedWidthDp)
        val targetH = dpToPx(expandedHeightDp)
        val targetX = if (pinnedSide == Gravity.END) {
            screenWidth - targetW - dpToPx(16)
        } else {
            dpToPx(16)
        }
        val targetY = floatingParams?.y ?: (displayMetrics.heightPixels / 3)

        binding.root.setBackgroundColor(0x00000000)
        binding.mainCardContent.visibility = View.VISIBLE

        animateWindowResize(
            toWidth = targetW,
            toHeight = targetH,
            toX = targetX,
            toY = targetY,
            onComplete = {
                binding.buttonMinimize.setImageResource(android.R.drawable.arrow_down_float)
            }
        )
    }

    private fun animateWindowResize(
        toWidth: Int,
        toHeight: Int,
        toX: Int,
        toY: Int,
        onComplete: (() -> Unit)? = null
    ) {
        val params = floatingParams ?: return
        val fromWidth = params.width
        val fromHeight = params.height
        val fromX = params.x
        val fromY = params.y

        val widthAnim = android.animation.ValueAnimator.ofInt(fromWidth, toWidth)
        val heightAnim = android.animation.ValueAnimator.ofInt(fromHeight, toHeight)
        val xAnim = android.animation.ValueAnimator.ofInt(fromX, toX)
        val yAnim = android.animation.ValueAnimator.ofInt(fromY, toY)

        widthAnim.duration = 200
        heightAnim.duration = 200
        xAnim.duration = 200
        yAnim.duration = 200

        widthAnim.addUpdateListener {
            params.width = it.animatedValue as Int
            windowManager?.updateViewLayout(floatingView, params)
        }
        heightAnim.addUpdateListener {
            params.height = it.animatedValue as Int
            windowManager?.updateViewLayout(floatingView, params)
        }
        xAnim.addUpdateListener {
            params.x = it.animatedValue as Int
            windowManager?.updateViewLayout(floatingView, params)
        }
        yAnim.addUpdateListener {
            params.y = it.animatedValue as Int
            windowManager?.updateViewLayout(floatingView, params)
        }

        xAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onComplete?.invoke()
            }
        })

        widthAnim.start()
        heightAnim.start()
        xAnim.start()
        yAnim.start()
    }

    private fun requestInputFocus() {
        binding.editTextMessage.requestFocus()
        showInputMethod()
    }

    private fun showInputMethod() {
        val imm = getSystemService<InputMethodManager>()
        imm?.showSoftInput(binding.editTextMessage, InputMethodManager.SHOW_IMPLICIT)
        isInputFocused = true
        updateWindowFocusability()
    }

    private fun hideInputMethod() {
        val imm = getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(binding.editTextMessage.windowToken, 0)
        binding.editTextMessage.clearFocus()
        isInputFocused = false
        updateWindowFocusability()
    }

    private fun updateWindowFocusability() {
        floatingParams?.let { params ->
            val newFlags = if (isInputFocused) {
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            if (params.flags != newFlags) {
                params.flags = newFlags
                windowManager?.updateViewLayout(floatingView, params)
            }
        }
    }

    private fun loadChatHistory() {
        serviceScope.launch {
            try {
                chatRepository.getMessages(sessionId)
            } catch (e: Exception) {
                android.util.Log.e("FloatingWindowService", "加载历史记录失败", e)
            }
        }
    }

    private fun observeState() {
        serviceScope.launch {
            chatRepository.getMessages(sessionId).collect { msgs ->
                messageAdapter.submitList(msgs) {
                    scrollToBottom()
                }
            }
        }

        serviceScope.launch {
            isLoading.collect { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                binding.buttonSend.isEnabled = !loading
            }
        }

        serviceScope.launch {
            events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun scrollToBottom() {
        val itemCount = messageAdapter.itemCount
        if (itemCount > 0) {
            binding.recyclerViewMessages.smoothScrollToPosition(itemCount - 1)
        }
    }

    private fun sendMessage(content: String) {
        if (content.isBlank() || _isLoading.value) return

        serviceScope.launch {
            _isLoading.value = true

            try {
                when (val result = aiService.sendStructuredMessage(sessionId, content)) {
                    is Resource.Success -> handleAiResponse(result.data)
                    is Resource.Error -> {
                        _events.emit(ChatUiEvent.ShowMessage("发送失败: ${result.message}"))
                    }
                    is Resource.Loading -> { /* 已在加载中 */ }
                }
            } catch (e: Exception) {
                _events.emit(ChatUiEvent.ShowMessage("发送失败: ${e.message}"))
            }

            _isLoading.value = false
        }
    }

    private suspend fun handleAiResponse(response: com.example.guet_map.module.ai.data.model.AiResponse) {
        val action = response.action
        if (response.responseType == com.example.guet_map.module.ai.data.model.AiResponse.ResponseType.CHAT || action == null) {
            response.text?.takeIf { it.isNotBlank() }?.let {
                _events.emit(ChatUiEvent.ShowMessage(it))
            }
            return
        }

        when (action.action) {
            com.example.guet_map.module.ai.data.model.AiAction.ActionType.NAVIGATE_TO -> {
                _events.emit(
                    ChatUiEvent.NavigateTo(
                        targetName = action.payload["targetName"]?.toString(),
                        targetLocationId = action.payload["targetLocationId"]?.toString(),
                        fallbackQuery = action.payload["fallbackQuery"]?.toString(),
                        mode = action.payload["mode"]?.toString() ?: "walking"
                    )
                )
            }
            else -> {
                response.text?.takeIf { it.isNotBlank() }?.let {
                    _events.emit(ChatUiEvent.ShowMessage(it))
                }
            }
        }
    }

    private fun handleEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.NavigateTo -> {
                val query = event.targetName ?: event.fallbackQuery ?: "目标地点"
                Toast.makeText(this, "准备导航到：$query", Toast.LENGTH_SHORT).show()
                // 通过启动 MainActivity 携带导航数据（比广播更可靠）
                val navIntent = Intent(this, com.example.guet_map.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("ai_nav_target_name", event.targetName)
                    putExtra("ai_nav_location_id", event.targetLocationId)
                    putExtra("ai_nav_fallback_query", event.fallbackQuery)
                }
                startActivity(navIntent)
            }
            is ChatUiEvent.ShowRoute -> {
                Toast.makeText(this, event.summary, Toast.LENGTH_SHORT).show()
            }
            is ChatUiEvent.AskPermission -> {
                Toast.makeText(this, event.reason, Toast.LENGTH_SHORT).show()
            }
            is ChatUiEvent.ShowClarifyQuestion -> {
                Toast.makeText(this, event.question, Toast.LENGTH_SHORT).show()
            }
            is ChatUiEvent.ShowMessage -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }
            is ChatUiEvent.ShowWeather -> {
                Toast.makeText(
                    this,
                    "天气：${event.description}，${event.temperature}°C",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is ChatUiEvent.ShowTimetableNavigationCard -> {
                showNavCard(event)
            }
            is ChatUiEvent.ShowTimetableNavigation -> {
                Toast.makeText(
                    this,
                    "课表导航：${event.courseName} @ ${event.classroomName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            ChatUiEvent.TimetableNavigationConfirmed,
            ChatUiEvent.TimetableNavigationCancelled -> Unit
        }
    }

    /**
     * 打开课表导入页面（通过广播通知 Activity）
     */
    private fun openTimetableImport() {
        val intent = Intent(ACTION_OPEN_TIMETABLE_IMPORT)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    /** 限制悬浮窗位置不超出屏幕边界 */
    private fun clampPosition(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val statusBarHeight = getStatusBarHeight()

        // x: 距离右边缘 (gravity=END)，合法范围 [0, screenWidth - width]
        params.x = params.x.coerceIn(0, screenWidth - params.width)
        // y: 距离顶部 (gravity=TOP)，合法范围 [statusBarHeight, screenHeight - params.height]
        params.y = params.y.coerceIn(statusBarHeight, screenHeight - params.height - dpToPx(16))
    }

    /** 拖拽结束后吸附到最近边缘 */
    private fun snapToNearestEdge() {
        val params = floatingParams ?: return
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val statusBarHeight = getStatusBarHeight()

        // 判断吸附到左还是右：当前窗口中心点位于屏幕左侧还是右侧
        val viewLeftEdge = screenWidth - params.x - params.width
        val viewCenterX = viewLeftEdge + params.width / 2
        val targetSide = if (viewCenterX < screenWidth / 2) Gravity.START else Gravity.END

        pinnedSide = targetSide

        val targetX = if (targetSide == Gravity.END) {
            screenWidth - params.width - dpToPx(4)
        } else {
            dpToPx(4) - params.width
        }

        params.y = params.y.coerceIn(statusBarHeight, displayMetrics.heightPixels - params.height - dpToPx(16))

        animateWindowResize(
            toWidth = params.width,
            toHeight = params.height,
            toX = targetX,
            toY = params.y
        )
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
        hideInputMethod()
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        floatingView = null
        _binding = null
        _navCardBinding = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_window_channel"
        private const val KEY_SESSION_ID = "chat_session_id"
        const val ACTION_OPEN_TIMETABLE_IMPORT = "com.example.guet_map.ACTION_OPEN_TIMETABLE_IMPORT"
        const val ACTION_TIMETABLE_NAVIGATE = "com.example.guet_map.ACTION_TIMETABLE_NAVIGATE"
        const val ACTION_AI_NAVIGATE = "com.example.guet_map.ACTION_AI_NAVIGATE"
        const val EXTRA_LOCATION_ID = "extra_location_id"
        const val EXTRA_TARGET_NAME = "extra_target_name"
        const val EXTRA_FALLBACK_QUERY = "extra_fallback_query"

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI 助手",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "悬浮窗 AI 助手通知"
                    setShowBadge(false)
                }
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
