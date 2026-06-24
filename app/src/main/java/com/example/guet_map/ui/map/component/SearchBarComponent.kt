package com.example.guet_map.ui.map.component

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.guet_map.databinding.FragmentMapBinding
import com.example.guet_map.model.Location
import com.example.guet_map.ui.map.SearchResultAdapter

/**
 * 搜索栏组件
 * 负责：搜索输入、搜索结果展示、上拉收起
 */
class SearchBarComponent(
    private val context: Context,
    private val binding: FragmentMapBinding,
    private val onQueryChanged: (String) -> Unit,
    private val onSearchSubmit: (String) -> Unit,
    private val onLocationPicked: (Location) -> Unit
) {
    private lateinit var searchAdapter: SearchResultAdapter
    private var suppressSearchResultsUntilEdit = false

    // 上拉收起相关
    private var dragStartY = 0f
    private var cardStartTranslationY = 0f
    private var isDraggingHandle = false
    private val dismissThreshold = 120f

    fun setup() {
        setupSearchInput()
        setupSearchResults()
        setupDragHandle()
    }

    private fun setupSearchInput() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                suppressSearchResultsUntilEdit = false
                onQueryChanged(query)
                if (query.isBlank()) {
                    dismissSearchResults()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = binding.etSearch.text?.toString().orEmpty()
                if (q.isNotBlank()) {
                    onSearchSubmit(q)
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupSearchResults() {
        searchAdapter = SearchResultAdapter { location ->
            binding.etSearch.setText(location.name)
            binding.etSearch.setSelection(location.name.length)
            onLocationPicked(location)
        }
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchAdapter
        }
    }

    private fun setupDragHandle() {
        binding.dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    cardStartTranslationY = binding.cardSearchResults.translationY
                    isDraggingHandle = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingHandle) return@setOnTouchListener true
                    val deltaY = event.rawY - dragStartY
                    // 只允许向上拖 (deltaY < 0)
                    if (deltaY < 0) {
                        binding.cardSearchResults.translationY = deltaY
                        binding.cardSearchResults.alpha =
                            (1f - kotlin.math.abs(deltaY) / dismissThreshold).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingHandle = false
                    val deltaY = binding.cardSearchResults.translationY
                    if (kotlin.math.abs(deltaY) > dismissThreshold) {
                        // 超过阈值，收起
                        animateDismiss()
                    } else {
                        // 没超过阈值，弹回
                        animateReset()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateDismiss() {
        binding.cardSearchResults.animate()
            .translationY(-binding.cardSearchResults.height.toFloat())
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                dismissSearchResults()
                binding.cardSearchResults.translationY = 0f
                binding.cardSearchResults.alpha = 1f
            }
            .start()
    }

    private fun animateReset() {
        binding.cardSearchResults.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(150)
            .start()
    }

    fun updateSearchResults(results: List<Location>) {
        if (suppressSearchResultsUntilEdit) {
            binding.cardSearchResults.isVisible = false
            return
        }
        if (results.isNotEmpty()) {
            binding.cardSearchResults.apply {
                isVisible = true
                translationY = 0f
                alpha = 1f
            }
            searchAdapter.submitList(results)
        } else {
            binding.cardSearchResults.isVisible = false
        }
    }

    fun dismissSearchResults() {
        binding.cardSearchResults.isVisible = false
        binding.cardSearchResults.translationY = 0f
        binding.cardSearchResults.alpha = 1f
        suppressSearchResultsUntilEdit = true
        hideKeyboard()
    }

    fun clearSearchInput() {
        binding.etSearch.setText("")
        binding.etSearch.clearFocus()
    }

    fun getCurrentQuery(): String = binding.etSearch.text?.toString().orEmpty()

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }
}
