package com.example.guet_map.ui.map.component

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.guet_map.R
import com.example.guet_map.databinding.LayoutNavigationPanelBinding
import com.example.guet_map.model.Location
import com.example.guet_map.model.WalkRouteInfo

/**
 * 导航面板组件
 * 负责：导航面板显示、路线信息展示、外部导航跳转、上拉收起
 */
class NavigationPanelComponent(
    private val context: Context,
    private val parent: ViewGroup
) {
    private var binding: LayoutNavigationPanelBinding? = null
    private var currentRoute: WalkRouteInfo? = null
    private var currentTarget: Location? = null

    var onCloseNavigation: (() -> Unit)? = null
    var onStartNavigation: ((Location) -> Unit)? = null

    // 上拉收起
    private var dragStartY = 0f
    private var cardStartTranslationY = 0f
    private var isDraggingHandle = false
    private val dismissThreshold = 150f

    init {
        inflate()
    }

    private fun inflate() {
        val navPanelView = LayoutNavigationPanelBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        binding = navPanelView
        parent.addView(navPanelView.root)
        setupClickListeners()
        setupDragHandle()
    }

    private fun setupClickListeners() {
        binding?.btnCloseNavigation?.setOnClickListener {
            hide()
            onCloseNavigation?.invoke()
        }
    }

    private fun setupDragHandle() {
        binding?.dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    cardStartTranslationY = binding?.cardNavigationPanel?.translationY ?: 0f
                    isDraggingHandle = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingHandle) return@setOnTouchListener true
                    val deltaY = event.rawY - dragStartY
                    if (deltaY < 0) {
                        binding?.cardNavigationPanel?.translationY = deltaY
                        binding?.cardNavigationPanel?.alpha =
                            (1f - kotlin.math.abs(deltaY) / dismissThreshold).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingHandle = false
                    val translationY = binding?.cardNavigationPanel?.translationY ?: 0f
                    if (kotlin.math.abs(translationY) > dismissThreshold) {
                        animateDismiss()
                    } else {
                        animateReset()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateDismiss() {
        val card = binding?.cardNavigationPanel ?: return
        val currentY = card.translationY
        val targetY = -card.height.toFloat()
        ValueAnimator.ofFloat(currentY, targetY).apply {
            duration = 200
            addUpdateListener { animator ->
                card.translationY = animator.animatedValue as Float
                card.alpha = 1f - kotlin.math.abs(animator.animatedValue as Float) / kotlin.math.abs(targetY)
            }
            start()
        }
        card.postDelayed({
            hide()
            onCloseNavigation?.invoke()
        }, 200)
    }

    private fun animateReset() {
        val card = binding?.cardNavigationPanel ?: return
        ValueAnimator.ofFloat(card.translationY, 0f).apply {
            duration = 200
            addUpdateListener { animator ->
                card.translationY = animator.animatedValue as Float
                card.alpha = 1f
            }
            start()
        }
    }

    /**
     * 显示导航面板，可选设置顶部边距（放在搜索栏下方）
     */
    fun show(target: Location, route: WalkRouteInfo? = null, topMargin: Int = 0, currentLocText: String? = null) {
        currentTarget = target
        currentRoute = route

        binding?.apply {
            cardNavigationPanel.visibility = View.VISIBLE
            cardNavigationPanel.translationY = 0f
            cardNavigationPanel.alpha = 1f
            if (topMargin > 0) {
                val params = cardNavigationPanel.layoutParams as? FrameLayout.LayoutParams
                    ?: FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                params.topMargin = topMargin
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                cardNavigationPanel.layoutParams = params
            }
            if (currentLocText != null) {
                tvCurrentLocation.text = currentLocText
            }
            if (route != null) {
                updateRoute(route)
            }
        }
    }

    fun updateRoute(route: WalkRouteInfo) {
        currentRoute = route
        binding?.apply {
            val distanceText = if (route.distanceMeters >= 1000) {
                String.format("%.1f", route.distanceMeters / 1000f)
            } else {
                route.distanceMeters.toString()
            }
            val unitText = if (route.distanceMeters >= 1000) "公里" else "米"

            tvRouteDistance.text = distanceText
            tvRouteDuration.text = unitText

            val minutes = (route.durationSeconds / 60).coerceAtLeast(1)
            tvNextStep.text = "预计步行 $minutes 分钟"
        }
    }

    fun showLoading(topMargin: Int = 0) {
        binding?.apply {
            cardNavigationPanel.visibility = View.VISIBLE
            cardNavigationPanel.translationY = 0f
            cardNavigationPanel.alpha = 1f
            if (topMargin > 0) {
                val params = cardNavigationPanel.layoutParams as? FrameLayout.LayoutParams
                    ?: FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                params.topMargin = topMargin
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                cardNavigationPanel.layoutParams = params
            }
            tvNextStep.text = context.getString(R.string.route_planning)
        }
    }

    fun hide() {
        binding?.cardNavigationPanel?.visibility = View.GONE
        currentRoute = null
        currentTarget = null
    }

    fun isShowing(): Boolean =
        binding?.cardNavigationPanel?.visibility == View.VISIBLE

    fun openExternalNavigation(target: Location) {
        val uriBuilder = StringBuilder("androidamap://route/plan/?")
        uriBuilder.append("dlat=${target.latitude}&dlon=${target.longitude}")
        uriBuilder.append("&dname=${Uri.encode(target.name)}")
        uriBuilder.append("&dev=0&t=2")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
            setPackage("com.autonavi.minimap")
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            openGenericMap(target)
        }
    }

    private fun openGenericMap(target: Location) {
        val geoUri = Uri.parse(
            "geo:${target.latitude},${target.longitude}?q=${Uri.encode(target.name)}"
        )
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)

        if (geoIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(Intent.createChooser(geoIntent, context.getString(R.string.nav_amap_app)))
        } else {
            copyToClipboard(target)
        }
    }

    private fun copyToClipboard(target: Location) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(
            "坐标",
            "${target.name}: ${target.latitude}, ${target.longitude}"
        )
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(
            context,
            "未找到导航应用，坐标已复制",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    fun destroy() {
        parent.removeView(binding?.root)
        binding = null
    }
}
