package com.example.guet_map.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guet_map.model.AppNotification
import com.example.guet_map.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository
) : ViewModel() {

    val notifications: StateFlow<List<AppNotification>> = notificationRepository
        .observeNotifications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadCount: StateFlow<Int> = notificationRepository
        .observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            notificationRepository.seedMockIfEmpty()
            notificationRepository.refresh()
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            notificationRepository.markAllRead()
        }
    }

    fun onNotificationClicked(notification: AppNotification) {
        viewModelScope.launch {
            notificationRepository.markRead(notification.id)
        }
    }
}
