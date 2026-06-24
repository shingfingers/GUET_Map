package com.example.guet_map.module.ai.ui.bubble

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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.guet_map.MainActivity
import com.example.guet_map.R
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs

/**
 * 悬浮球服务 - 类似微信的悬浮球
 * 特性：
 * 1. 可拖拽到屏幕边缘
 * 2. 自动贴边动画
 * 3. 点击展开 AI 聊天窗口
 * 4. 未读消息数量显示
 */
@AndroidEntryPoint
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var unreadCount = 0

    companion object {
        const val CHANNEL_ID = "floating_bubble_channel"
        const val NOTIFICATION_ID = 10001
        const val ACTION_SHOW = "com.example.guet_map.action.SHOW_BUBBLE"
        const val ACTION_HIDE = "com.example.guet_map.action.HIDE_BUBBLE"
        const val ACTION_UPDATE_BADGE = "com.example.guet_map.action.UPDATE_BADGE"
        const val EXTRA_BADGE_COUNT = "badge_count"

        fun show(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun updateBadge(context: Context, count: Int) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_UPDATE_BADGE
                putExtra(EXTRA_BADGE_COUNT, count)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            ACTION_UPDATE_BADGE -> {
                unreadCount = intent.getIntExtra(EXTRA_BADGE_COUNT, 0)
                updateBadge()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮球服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮球在后台运行"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI 助手")
                .setContentText("悬浮球已启动，点击可打开 AI 助手")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        // 如果已存在但可能在屏幕外，先移除再重建
        if (bubbleView != null) {
            try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
            bubbleView = null
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        bubbleParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = getScreenWidth() - dpToPx(80)
            y = getScreenHeight() / 3
        }

        val bubble = bubbleView!!.findViewById<FrameLayout>(R.id.bubbleView)
        setupDragBehavior(bubble)

        bubble.setOnClickListener {
            openMainActivity()
        }

        bubbleView?.let {
            windowManager?.addView(it, bubbleParams)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragBehavior(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams?.x ?: 0
                    initialY = bubbleParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(deltaX) > 10 || abs(deltaY) > 10)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        bubbleParams?.x = initialX + deltaX
                        bubbleParams?.y = (initialY + deltaY).coerceIn(0, getScreenHeight() - dpToPx(56))
                        windowManager?.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 自动贴边动画
                        animateToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateToEdge() {
        val screenWidth = getScreenWidth()
        val currentX = bubbleParams?.x ?: 0
        val targetX = if (currentX > screenWidth / 2) {
            screenWidth - dpToPx(64)
        } else {
            -dpToPx(8)
        }
        val startX = currentX
        val startTime = System.currentTimeMillis()
        val duration = 200L

        val animator = android.animation.ValueAnimator.ofInt(startX, targetX)
        animator.duration = duration
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            bubbleParams?.x = animation.animatedValue as Int
            windowManager?.updateViewLayout(bubbleView, bubbleParams)
        }
        animator.start()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_ai_chat", true)
        }
        startActivity(intent)
    }

    private fun updateBadge() {
        val tvBadge = bubbleView?.findViewById<TextView>(R.id.tvUnreadBadge)
        if (unreadCount > 0) {
            tvBadge?.visibility = View.VISIBLE
            tvBadge?.text = if (unreadCount > 99) "99+" else unreadCount.toString()
        } else {
            tvBadge?.visibility = View.GONE
        }
    }

    private fun hideBubble() {
        bubbleView?.let {
            windowManager?.removeView(it)
            bubbleView = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getScreenWidth(): Int {
        return resources.displayMetrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        return resources.displayMetrics.heightPixels
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }
}
