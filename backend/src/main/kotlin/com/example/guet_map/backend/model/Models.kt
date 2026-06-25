package com.example.guet_map.backend.model

import java.time.LocalDateTime

data class User(
    val id: Long = 0,
    val email: String,
    val passwordHash: String,
    val nickname: String,
    val avatar: String? = null, // 本地头像路径
    val points: Int = 0,
    val contributionCount: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class VerificationCode(
    val id: Long = 0,
    val email: String,
    val code: String,
    val type: CodeType,
    val expiresAt: LocalDateTime,
    val used: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class CodeType {
    REGISTER,
    RESET_PASSWORD,
    LOGIN
}

data class Notification(
    val id: Long = 0,
    val userId: Long,
    val type: String, // review, points, announcement, system
    val title: String,
    val body: String,
    val locationId: String? = null,
    val isRead: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

// ========== 好友相关模型 ==========

data class Friend(
    val id: Long = 0,
    val userId: Long,
    val friendId: Long,
    val status: String = "accepted",
    val createdAt: LocalDateTime = LocalDateTime.now()
)

data class FriendRequest(
    val id: Long = 0,
    val fromUserId: Long,
    val toUserId: Long,
    val status: String = "pending",
    val message: String? = null,
    val createdAt: String = ""  // 使用字符串而非 LocalDateTime，避免 Gson 序列化问题
)

data class FriendInfo(
    val userId: Long,
    val nickname: String,
    val avatar: String?,
    val email: String
)

// ========== 聊天消息模型 ==========

data class Message(
    val id: Long = 0,
    val senderId: Long,
    val receiverId: Long,
    val content: String,
    val type: String = "text",
    val isRead: Boolean = false,
    val createdAt: String = ""  // 使用字符串而非 LocalDateTime，避免 Gson 序列化问题
)

// ========== 朋友圈帖子模型 ==========

data class Post(
    val id: Long = 0,
    val userId: Long,
    val content: String,
    val locationId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val images: String? = null,
    val visibility: String = "public",
    val createdAt: LocalDateTime = LocalDateTime.now()
)

data class PostComment(
    val id: Long = 0,
    val postId: Long,
    val userId: Long,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

data class PostLike(
    val id: Long = 0,
    val postId: Long,
    val userId: Long,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

// ========== 用户位置模型 ==========

data class UserLocation(
    val id: Long = 0,
    val userId: Long,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
