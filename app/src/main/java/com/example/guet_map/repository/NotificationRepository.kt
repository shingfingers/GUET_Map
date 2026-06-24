package com.example.guet_map.repository

import com.example.guet_map.core.dao.NotificationDao
import com.example.guet_map.core.entity.NotificationEntity
import com.example.guet_map.data.UserPrefs
import com.example.guet_map.model.AppNotification
import com.example.guet_map.network.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val apiService: ApiService,
    private val notificationDao: NotificationDao,
    private val userPrefs: UserPrefs
) {

    private val currentUserId: String
        get() = userPrefs.userId.ifBlank { UserPrefs.GUEST_USER_ID }

    fun observeNotifications(): Flow<List<AppNotification>> =
        notificationDao.observeAll(currentUserId).map { list -> list.map { it.toDomain() } }

    fun observeUnreadCount(): Flow<Int> = notificationDao.observeUnreadCount(currentUserId)

    suspend fun seedMockIfEmpty() {
        val count = notificationDao.countByUserId(currentUserId)
        if (count > 0) return
        notificationDao.insertAll(
            listOf(
                NotificationEntity(
                    id = 1, type = "review", title = "指引审核通过",
                    body = "您提交的「第十一教学楼A区」步骤已通过，+5积分",
                    locationId = null, isRead = false,
                    createdAt = "2026-06-02T10:00:00", userId = currentUserId
                ),
                NotificationEntity(
                    id = 2, type = "points", title = "积分到账",
                    body = "您的校园贡献积分已更新为 15",
                    locationId = null, isRead = false,
                    createdAt = "2026-06-01T18:30:00", userId = currentUserId
                ),
                NotificationEntity(
                    id = 3, type = "announcement", title = "欢迎使用 GUET Map",
                    body = "花江校区实景导航已上线，欢迎贡献指路！",
                    locationId = null, isRead = true,
                    createdAt = "2026-05-30T09:00:00", userId = currentUserId
                )
            )
        )
    }

    suspend fun refresh() {
        try {
            val remote = apiService.getNotifications()
            notificationDao.deleteAllForUser(currentUserId)
            notificationDao.insertAll(remote.map { it.toEntity() })
        } catch (_: Exception) {
            // 使用缓存; seedMockIfEmpty 已提供兜底数据
        }
    }

    suspend fun markAllRead() {
        notificationDao.markAllRead(currentUserId)
        if (userPrefs.isLoggedIn) {
            try {
                apiService.markAllNotificationsRead()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun markRead(id: Long) {
        notificationDao.markRead(id, currentUserId)
        if (userPrefs.isLoggedIn) {
            try {
                apiService.markNotificationRead(id)
            } catch (_: Exception) {
            }
        }
    }

    private fun AppNotification.toEntity() = NotificationEntity(
        id = id,
        type = type,
        title = title,
        body = body,
        locationId = locationId,
        isRead = isRead,
        createdAt = createdAt,
        userId = currentUserId
    )

    private fun NotificationEntity.toDomain() = AppNotification(
        id = id,
        type = type,
        title = title,
        body = body,
        locationId = locationId,
        isRead = isRead,
        createdAt = createdAt,
        userId = userId
    )
}
