package com.example.guet_map.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.guet_map.databinding.FragmentChatBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()

    private lateinit var adapter: ChatMessageAdapter
    private var lastMessage: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupInput()
        observeState()
        // 标记消息为已读
        viewModel.markMessagesAsRead()
    }

    private fun setupToolbar() {
        binding.toolbar.title = viewModel.uiState.value.friendName.ifBlank { "聊天" }
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(
            onOwnMessage = { viewModel.isOwnMessage(it) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = this@ChatFragment.adapter
        }
    }

    private fun setupInput() {
        binding.fabSend.setOnClickListener {
            sendMessage()
        }

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun sendMessage() {
        val content = binding.etMessage.text?.toString().orEmpty()
        if (content.isNotBlank()) {
            viewModel.sendMessage(content)
            binding.etMessage.text?.clear()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: ChatUiState) {
        binding.progressBar.isVisible = state.isLoading

        val hasMessages = state.messages.isNotEmpty()
        binding.recyclerView.isVisible = hasMessages && !state.isLoading
        binding.textViewEmpty.isVisible = !hasMessages && !state.isLoading

        adapter.submitList(state.messages) {
            if (state.messages.isNotEmpty()) {
                binding.recyclerView.scrollToPosition(state.messages.size - 1)
            }
        }

        if (state.messageSent) {
            binding.recyclerView.scrollToPosition(state.messages.size - 1)
        }

        val msg = state.message
        if (!msg.isNullOrBlank() && msg != lastMessage) {
            lastMessage = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
