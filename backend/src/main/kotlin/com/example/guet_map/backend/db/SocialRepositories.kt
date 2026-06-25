package com.example.guet_map.backend.db

import com.example.guet_map.backend.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class FriendRepository {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun sendFriendRequest(fromUserId: Long, toUserId: Long, message: String? = null): SendFriendRequestResult {
        return transaction {
            val alreadyFriends = Friends.select {
                (Friends.userId eq fromUserId) and (Friends.friendId eq toUserId)
            }.any()

            if (alreadyFriends) {
                return@transaction SendFriendRequestResult.AlreadyFriends
            }

            val pendingRequest = FriendRequests.select {
                (FriendRequests.fromUserId eq fromUserId) and (FriendRequests.toUserId eq toUserId) and
                (FriendRequests.status eq "pending")
            }.any()

            if (pendingRequest) {
                return@transaction SendFriendRequestResult.AlreadySent
            }

            val reversePending = FriendRequests.select {
                (FriendRequests.fromUserId eq toUserId) and (FriendRequests.toUserId eq fromUserId) and
                (FriendRequests.status eq "pending")
            }.any()

            if (reversePending) {
                return@transaction SendFriendRequestResult.ReceivedPending
            }

            val nowStr = LocalDateTime.now().format(dateFormatter)
            val id = FriendRequests.insert {
                it[FriendRequests.fromUserId] = fromUserId
                it[FriendRequests.toUserId] = toUserId
                it[FriendRequests.status] = "pending"
                it[FriendRequests.message] = message
                it[FriendRequests.createdAt] = nowStr
            } get FriendRequests.id

            SendFriendRequestResult.Success(FriendRequest(id = id, fromUserId = fromUserId, toUserId = toUserId, status = "pending", message = message, createdAt = nowStr))
        }
    }

    sealed class SendFriendRequestResult {
        data object AlreadyFriends : SendFriendRequestResult()
        data object AlreadySent : SendFriendRequestResult()
        data object ReceivedPending : SendFriendRequestResult()
        data class Success(val request: FriendRequest) : SendFriendRequestResult()
    }

    fun handleFriendRequest(requestId: Long, accept: Boolean): Boolean {
        return transaction {
            val request = FriendRequests.select { FriendRequests.id eq requestId }.firstOrNull() ?: return@transaction false
            val reqFromUserId = request[FriendRequests.fromUserId]
            val reqToUserId = request[FriendRequests.toUserId]

            if (accept) {
                val nowStr = LocalDateTime.now().format(dateFormatter)
                Friends.insert {
                    it[Friends.userId] = reqFromUserId
                    it[Friends.friendId] = reqToUserId
                    it[Friends.status] = "accepted"
                    it[Friends.createdAt] = nowStr
                }
                Friends.insert {
                    it[Friends.userId] = reqToUserId
                    it[Friends.friendId] = reqFromUserId
                    it[Friends.status] = "accepted"
                    it[Friends.createdAt] = nowStr
                }
            }

            FriendRequests.update({ FriendRequests.id eq requestId }) {
                it[FriendRequests.status] = if (accept) "accepted" else "rejected"
            }
            true
        }
    }

    fun getReceivedRequests(userId: Long): List<FriendRequestWithUser> {
        return transaction {
            FriendRequests.select { FriendRequests.toUserId eq userId }
                .orderBy(FriendRequests.createdAt, SortOrder.DESC)
                .map { row ->
                    val fromUser = Users.select { Users.id eq row[FriendRequests.fromUserId] }.firstOrNull()
                    FriendRequestWithUser(
                        request = FriendRequest(
                            id = row[FriendRequests.id],
                            fromUserId = row[FriendRequests.fromUserId],
                            toUserId = row[FriendRequests.toUserId],
                            status = row[FriendRequests.status],
                            message = row[FriendRequests.message],
                            createdAt = row[FriendRequests.createdAt]
                        ),
                        fromUserInfo = fromUser?.let {
                            FriendInfo(
                                userId = it[Users.id],
                                nickname = it[Users.nickname],
                                avatar = it[Users.avatar],
                                email = it[Users.email]
                            )
                        }
                    )
                }
        }
    }

    fun getFriends(userId: Long): List<FriendInfo> {
        return transaction {
            Friends.innerJoin(Users, { Friends.friendId }, { Users.id })
                .select { Friends.userId eq userId }
                .map { row ->
                    FriendInfo(
                        userId = row[Users.id],
                        nickname = row[Users.nickname],
                        avatar = row[Users.avatar],
                        email = row[Users.email]
                    )
                }
        }
    }

    fun removeFriend(userId: Long, friendId: Long): Boolean {
        return transaction {
            val condition1 = Op.build { (Friends.userId eq userId) and (Friends.friendId eq friendId) }
            val condition2 = Op.build { (Friends.userId eq friendId) and (Friends.friendId eq userId) }
            Friends.deleteWhere { condition1 }
            Friends.deleteWhere { condition2 }
            true
        }
    }

    fun findUserByEmail(email: String): FriendInfo? {
        return transaction {
            Users.select { Users.email eq email }
                .firstOrNull()?.let { row ->
                    FriendInfo(
                        userId = row[Users.id],
                        nickname = row[Users.nickname],
                        avatar = row[Users.avatar],
                        email = row[Users.email]
                    )
                }
        }
    }

    fun findUserById(userId: Long): FriendInfo? {
        return transaction {
            Users.select { Users.id eq userId }
                .firstOrNull()?.let { row ->
                    FriendInfo(
                        userId = row[Users.id],
                        nickname = row[Users.nickname],
                        avatar = row[Users.avatar],
                        email = row[Users.email]
                    )
                }
        }
    }

    fun isFriend(userId: Long, friendId: Long): Boolean {
        return transaction {
            Friends.select { (Friends.userId eq userId) and (Friends.friendId eq friendId) }.any()
        }
    }

    private fun parseDateTime(str: String): LocalDateTime {
        return try {
            LocalDateTime.parse(str, dateFormatter)
        } catch (e: Exception) {
            LocalDateTime.now()
        }
    }
}

data class FriendRequestWithUser(
    val request: FriendRequest,
    val fromUserInfo: FriendInfo?
)

class MessageRepository {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun sendMessage(senderId: Long, receiverId: Long, content: String, type: String = "text"): Message {
        return transaction {
            val nowStr = LocalDateTime.now().format(dateFormatter)
            val id = Messages.insert {
                it[Messages.senderId] = senderId
                it[Messages.receiverId] = receiverId
                it[Messages.content] = content
                it[Messages.type] = type
                it[Messages.isRead] = false
                it[Messages.createdAt] = nowStr
            } get Messages.id
            Message(
                id = id,
                senderId = senderId,
                receiverId = receiverId,
                content = content,
                type = type,
                isRead = false,
                createdAt = nowStr
            )
        }
    }

    fun getMessages(userId: Long, otherUserId: Long, limit: Int = 50): List<Message> {
        return transaction {
            Messages.select {
                ((Messages.senderId eq userId) and (Messages.receiverId eq otherUserId)) or
                ((Messages.senderId eq otherUserId) and (Messages.receiverId eq userId))
            }
                .orderBy(Messages.createdAt, SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    Message(
                        id = row[Messages.id],
                        senderId = row[Messages.senderId],
                        receiverId = row[Messages.receiverId],
                        content = row[Messages.content],
                        type = row[Messages.type],
                        isRead = row[Messages.isRead],
                        createdAt = row[Messages.createdAt]  // 直接使用字符串
                    )
                }
        }
    }

    fun markAsRead(userId: Long, otherUserId: Long) {
        transaction {
            Messages.update({
                (Messages.senderId eq otherUserId) and (Messages.receiverId eq userId) and (Messages.isRead eq false)
            }) {
                it[Messages.isRead] = true
            }
        }
    }

    fun getUnreadCount(userId: Long): Int {
        return transaction {
            Messages.select { (Messages.receiverId eq userId) and (Messages.isRead eq false) }.count().toInt()
        }
    }

    fun getUnreadCountFrom(userId: Long, fromUserId: Long): Int {
        return transaction {
            Messages.select { (Messages.senderId eq fromUserId) and (Messages.receiverId eq userId) and (Messages.isRead eq false) }.count().toInt()
        }
    }

    private fun parseDateTime(str: String): LocalDateTime {
        return try { LocalDateTime.parse(str, dateFormatter) } catch (e: Exception) { LocalDateTime.now() }
    }
}

class PostRepository {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun createPost(userId: Long, content: String, locationId: String? = null, latitude: Double? = null,
                   longitude: Double? = null, images: String? = null, visibility: String = "public"): Post {
        return transaction {
            val nowStr = LocalDateTime.now().format(dateFormatter)
            val id = Posts.insert {
                it[Posts.userId] = userId
                it[Posts.content] = content
                it[Posts.locationId] = locationId
                it[Posts.latitude] = latitude
                it[Posts.longitude] = longitude
                it[Posts.images] = images
                it[Posts.visibility] = visibility
                it[Posts.createdAt] = nowStr
            } get Posts.id
            Post(id = id, userId = userId, content = content, locationId = locationId,
                 latitude = latitude, longitude = longitude, images = images, visibility = visibility)
        }
    }

    fun getPosts(userId: Long, friendIds: List<Long>, limit: Int = 50, offset: Int = 0): List<PostWithDetails> {
        return transaction {
            val allRelevantIds = friendIds + userId
            Posts.select { Posts.userId inList allRelevantIds }
                .orderBy(Posts.createdAt, SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { row ->
                    val postUser = Users.select { Users.id eq row[Posts.userId] }.firstOrNull()
                    val postId = row[Posts.id]
                    PostWithDetails(
                        post = Post(
                            id = postId,
                            userId = row[Posts.userId],
                            content = row[Posts.content],
                            locationId = row[Posts.locationId],
                            latitude = row[Posts.latitude],
                            longitude = row[Posts.longitude],
                            images = row[Posts.images],
                            visibility = row[Posts.visibility],
                            createdAt = parseDateTime(row[Posts.createdAt])
                        ),
                        userInfo = postUser?.let {
                            FriendInfo(userId = it[Users.id], nickname = it[Users.nickname], avatar = it[Users.avatar], email = it[Users.email])
                        },
                        likeCount = PostLikes.select { PostLikes.postId eq postId }.count().toInt(),
                        commentCount = PostComments.select { PostComments.postId eq postId }.count().toInt(),
                        isLiked = PostLikes.select { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }.any()
                    )
                }
        }
    }

    fun getPostsByLocation(locationId: String, userId: Long, friendIds: List<Long>): List<PostWithDetails> {
        return transaction {
            Posts.select { Posts.locationId eq locationId }
                .orderBy(Posts.createdAt, SortOrder.DESC)
                .map { row ->
                    val postUser = Users.select { Users.id eq row[Posts.userId] }.firstOrNull()
                    val postOwnerId = row[Posts.userId]
                    val isOwner = postOwnerId == userId
                    val isFriend = postOwnerId in friendIds
                    val visibility = row[Posts.visibility]
                    val postId = row[Posts.id]
                    val canView = isOwner || (visibility == "public") || (visibility == "friends_only" && isFriend)

                    if (canView) {
                        PostWithDetails(
                            post = Post(id = postId, userId = postOwnerId, content = row[Posts.content],
                                locationId = row[Posts.locationId], latitude = row[Posts.latitude], longitude = row[Posts.longitude],
                                images = row[Posts.images], visibility = visibility, createdAt = parseDateTime(row[Posts.createdAt])),
                            userInfo = postUser?.let {
                                FriendInfo(userId = it[Users.id], nickname = it[Users.nickname], avatar = it[Users.avatar], email = it[Users.email])
                            },
                            likeCount = PostLikes.select { PostLikes.postId eq postId }.count().toInt(),
                            commentCount = PostComments.select { PostComments.postId eq postId }.count().toInt(),
                            isLiked = PostLikes.select { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }.any()
                        )
                    } else null
                }.filterNotNull()
        }
    }

    fun deletePost(postId: Long, userId: Long): Boolean {
        return transaction {
            val condition = Op.build { (Posts.id eq postId) and (Posts.userId eq userId) }
            val post = Posts.select { condition }.firstOrNull() ?: return@transaction false
            val cond1 = Op.build { PostComments.postId eq postId }
            val cond2 = Op.build { PostLikes.postId eq postId }
            val cond3 = Op.build { Posts.id eq postId }
            PostComments.deleteWhere { cond1 }
            PostLikes.deleteWhere { cond2 }
            Posts.deleteWhere { cond3 }
            true
        }
    }

    fun addComment(postId: Long, userId: Long, content: String): PostComment {
        return transaction {
            val nowStr = LocalDateTime.now().format(dateFormatter)
            val id = PostComments.insert {
                it[PostComments.postId] = postId
                it[PostComments.userId] = userId
                it[PostComments.content] = content
                it[PostComments.createdAt] = nowStr
            } get PostComments.id
            PostComment(id = id, postId = postId, userId = userId, content = content)
        }
    }

    fun getComments(postId: Long): List<CommentWithUser> {
        return transaction {
            PostComments.innerJoin(Users, { PostComments.userId }, { Users.id })
                .select { PostComments.postId eq postId }
                .orderBy(PostComments.createdAt, SortOrder.ASC)
                .map { row ->
                    CommentWithUser(
                        comment = PostComment(id = row[PostComments.id], postId = row[PostComments.postId],
                            userId = row[PostComments.userId], content = row[PostComments.content],
                            createdAt = parseDateTime(row[PostComments.createdAt])),
                        userInfo = FriendInfo(userId = row[Users.id], nickname = row[Users.nickname], avatar = row[Users.avatar], email = row[Users.email])
                    )
                }
        }
    }

    fun toggleLike(postId: Long, userId: Long): Boolean {
        return transaction {
            val condition = Op.build { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
            val existing = PostLikes.select { condition }.firstOrNull()
            if (existing != null) {
                PostLikes.deleteWhere { condition }
                false
            } else {
                val nowStr = LocalDateTime.now().format(dateFormatter)
                PostLikes.insert {
                    it[PostLikes.postId] = postId
                    it[PostLikes.userId] = userId
                    it[PostLikes.createdAt] = nowStr
                }
                true
            }
        }
    }

    private fun parseDateTime(str: String): LocalDateTime {
        return try { LocalDateTime.parse(str, dateFormatter) } catch (e: Exception) { LocalDateTime.now() }
    }
}

data class PostWithDetails(val post: Post, val userInfo: FriendInfo?, val likeCount: Int, val commentCount: Int, val isLiked: Boolean)
data class CommentWithUser(val comment: PostComment, val userInfo: FriendInfo?)

class UserLocationRepository {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun updateLocation(userId: Long, latitude: Double, longitude: Double) {
        transaction {
            val nowStr = LocalDateTime.now().format(dateFormatter)
            val existing = UserLocations.select { UserLocations.userId eq userId }.firstOrNull()
            if (existing != null) {
                UserLocations.update({ UserLocations.userId eq userId }) {
                    it[UserLocations.latitude] = latitude
                    it[UserLocations.longitude] = longitude
                    it[UserLocations.updatedAt] = nowStr
                }
            } else {
                UserLocations.insert {
                    it[UserLocations.userId] = userId
                    it[UserLocations.latitude] = latitude
                    it[UserLocations.longitude] = longitude
                    it[UserLocations.updatedAt] = nowStr
                }
            }
        }
    }

    fun getLocation(userId: Long): UserLocation? {
        return transaction {
            UserLocations.select { UserLocations.userId eq userId }
                .firstOrNull()?.let { row ->
                    UserLocation(
                        id = row[UserLocations.id],
                        userId = row[UserLocations.userId],
                        latitude = row[UserLocations.latitude],
                        longitude = row[UserLocations.longitude],
                        updatedAt = parseDateTime(row[UserLocations.updatedAt])
                    )
                }
        }
    }

    fun getLocations(userIds: List<Long>): List<UserLocation> {
        return transaction {
            UserLocations.select { UserLocations.userId inList userIds }
                .map { row ->
                    UserLocation(
                        id = row[UserLocations.id],
                        userId = row[UserLocations.userId],
                        latitude = row[UserLocations.latitude],
                        longitude = row[UserLocations.longitude],
                        updatedAt = parseDateTime(row[UserLocations.updatedAt])
                    )
                }
        }
    }

    private fun parseDateTime(str: String): LocalDateTime {
        return try { LocalDateTime.parse(str, dateFormatter) } catch (e: Exception) { LocalDateTime.now() }
    }
}
