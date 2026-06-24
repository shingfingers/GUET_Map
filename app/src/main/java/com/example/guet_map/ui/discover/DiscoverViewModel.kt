package com.example.guet_map.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guet_map.data.UserPrefs
import com.example.guet_map.model.Location
import com.example.guet_map.model.PostWithDetails
import com.example.guet_map.model.Resource
import com.example.guet_map.repository.LegacyFavoriteRepository
import com.example.guet_map.repository.SocialRepository
import com.example.guet_map.ui.discover.model.CampusEvent
import com.example.guet_map.ui.discover.model.CheckInPost
import com.example.guet_map.ui.discover.model.EventCategory
import com.example.guet_map.ui.discover.model.EventStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val favoriteRepository: LegacyFavoriteRepository,
    private val socialRepository: SocialRepository,
    private val userPrefs: UserPrefs
) : ViewModel() {

    private val _checkInPosts = MutableStateFlow<List<CheckInPost>>(emptyList())
    val checkInPosts: StateFlow<List<CheckInPost>> = _checkInPosts.asStateFlow()

    private val _events = MutableStateFlow<List<CampusEvent>>(emptyList())
    val events: StateFlow<List<CampusEvent>> = _events.asStateFlow()

    private val _topics = MutableStateFlow<List<String>>(emptyList())
    val topics: StateFlow<List<String>> = _topics.asStateFlow()

    private val _selectedTopic = MutableStateFlow<String?>(null)
    val selectedTopic: StateFlow<String?> = _selectedTopic.asStateFlow()

    val favorites: StateFlow<List<Location>> = favoriteRepository
        .observeFavoriteLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedDateMillis = MutableStateFlow(todayStartMillis())
    val selectedDateMillis: StateFlow<Long> = _selectedDateMillis.asStateFlow()

    private val likedPosts = mutableSetOf<Long>()

    init {
        loadMockData()
        loadPostsFromServer()
        viewModelScope.launch {
            favoriteRepository.syncFromServer()
        }
    }

    private fun loadPostsFromServer() {
        viewModelScope.launch {
            socialRepository.getPosts().collect { resource ->
                when (resource) {
                    is Resource.Success -> {
                        val posts = resource.data.map { it.toCheckInPost() }
                        _checkInPosts.value = posts
                    }
                    is Resource.Error -> {
                        // 静默失败，使用本地数据
                    }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    private fun PostWithDetails.toCheckInPost(): CheckInPost {
        val post = this.post
        return CheckInPost(
            id = post.id.toString(),
            userId = post.userId.toString(),
            userName = this.userInfo?.nickname ?: "匿名用户",
            userAvatar = this.userInfo?.avatar,
            locationId = post.locationId ?: "",
            locationName = post.locationId ?: "未知地点",
            content = post.content,
            imageUrls = emptyList(),
            topics = emptyList(),
            timestamp = try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    .parse(post.createdAt)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            },
            likeCount = this.likeCount,
            commentCount = this.commentCount,
            isLiked = this.isLiked
        )
    }

    private fun todayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun shiftSelectedDate(days: Int) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = _selectedDateMillis.value
        cal.add(Calendar.DAY_OF_YEAR, days)
        _selectedDateMillis.value = cal.timeInMillis
    }

    fun resetToToday() {
        _selectedDateMillis.value = todayStartMillis()
    }

    private fun loadMockData() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val day = 24 * 60 * 60 * 1000L

            // Mock data 已移除，真实帖子由 loadPostsFromServer() 从服务器加载
            _checkInPosts.value = emptyList()

            _topics.value = listOf("学习", "日常", "体育", "美食", "校园活动")

            _events.value = listOf(
                CampusEvent(
                    id = "1",
                    title = "校园歌手大赛",
                    description = "一年一度的校园歌手大赛，展示你的歌喉！",
                    location = "大学生活动中心",
                    startTime = now + 2 * day,
                    endTime = now + 2 * day + 4 * 60 * 60 * 1000,
                    organizer = "校学生会",
                    category = EventCategory.CULTURE,
                    status = EventStatus.UPCOMING,
                    attendeeCount = 156,
                    maxAttendees = 200,
                    registrationRequired = true
                ),
                CampusEvent(
                    id = "2",
                    title = "篮球友谊赛",
                    description = "各院系篮球队友谊赛，欢迎观战！",
                    location = "花江校区体育馆",
                    startTime = now - 1 * 60 * 60 * 1000,
                    endTime = now + 3 * 60 * 60 * 1000,
                    organizer = "体育学院",
                    category = EventCategory.SPORTS,
                    status = EventStatus.ONGOING,
                    attendeeCount = 89,
                    maxAttendees = null,
                    registrationRequired = false
                ),
                CampusEvent(
                    id = "3",
                    title = "AI技术分享会",
                    description = "前沿AI技术分享，探讨大语言模型的应用",
                    location = "信息中心报告厅",
                    startTime = now + 5 * day,
                    endTime = now + 5 * day + 3 * 60 * 60 * 1000,
                    organizer = "计算机协会",
                    category = EventCategory.ACADEMIC,
                    status = EventStatus.UPCOMING,
                    attendeeCount = 45,
                    maxAttendees = 100,
                    registrationRequired = true
                ),
                CampusEvent(
                    id = "4",
                    title = "校园志愿者招募",
                    description = "加入志愿者团队，参与社区服务",
                    location = "学生活动中心",
                    startTime = now + 7 * day,
                    endTime = now + 7 * day + 2 * 60 * 60 * 1000,
                    organizer = "青年志愿者协会",
                    category = EventCategory.VOLUNTEER,
                    status = EventStatus.UPCOMING,
                    attendeeCount = 23,
                    maxAttendees = 50,
                    registrationRequired = true
                )
            )
        }
    }

    fun selectTopic(topic: String?) {
        _selectedTopic.value = topic
    }

    fun getFilteredPosts(): List<CheckInPost> {
        val topic = _selectedTopic.value
        return if (topic == null) {
            _checkInPosts.value
        } else {
            _checkInPosts.value.filter { topic in it.topics }
        }
    }

    fun getFilteredEvents(): List<CampusEvent> {
        val selectedDay = _selectedDateMillis.value
        val cal = Calendar.getInstance()
        cal.timeInMillis = selectedDay
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        val endOfDay = cal.timeInMillis

        return _events.value.filter { event ->
            event.startTime in selectedDay..endOfDay
        }
    }

    fun toggleLike(postId: String) {
        viewModelScope.launch {
            val postIdLong = postId.toLongOrNull() ?: return@launch

            socialRepository.togglePostLike(postIdLong).collect { resource ->
                when (resource) {
                    is Resource.Success -> {
                        val currentPosts = _checkInPosts.value.toMutableList()
                        val index = currentPosts.indexOfFirst { it.id == postId }
                        if (index != -1) {
                            val post = currentPosts[index]
                            val isNowLiked = resource.data
                            if (isNowLiked) {
                                likedPosts.add(postIdLong)
                            } else {
                                likedPosts.remove(postIdLong)
                            }
                            currentPosts[index] = post.copy(
                                isLiked = isNowLiked,
                                likeCount = if (isNowLiked) post.likeCount + 1 else post.likeCount - 1
                            )
                            _checkInPosts.value = currentPosts
                        }
                    }
                    is Resource.Error -> {
                        // 静默失败
                    }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    fun publishCheckIn(locationName: String, content: String, topics: List<String>) {
        viewModelScope.launch {
            val currentUserId = userPrefs.userId.ifBlank { "guest" }
            val currentNickname = userPrefs.nickname.ifBlank { "匿名用户" }

            // 先添加本地预览
            val newPost = CheckInPost(
                id = UUID.randomUUID().toString(),
                userId = currentUserId,
                userName = currentNickname,
                userAvatar = null,
                locationId = "",
                locationName = locationName,
                content = content,
                imageUrls = emptyList(),
                topics = topics,
                timestamp = System.currentTimeMillis(),
                likeCount = 0,
                commentCount = 0,
                isLiked = false
            )

            val updated = listOf(newPost) + _checkInPosts.value
            _checkInPosts.value = updated

            // Update topics if new ones were added
            val allTopics = _topics.value.toMutableList()
            topics.forEach { topic ->
                if (topic !in allTopics) {
                    allTopics.add(topic)
                }
            }
            _topics.value = allTopics

            // 调用 API 发布到服务器
            socialRepository.createPost(
                content = content,
                locationId = locationName,
                visibility = "public"
            ).collect { resource ->
                when (resource) {
                    is Resource.Success -> {
                        // API 发布成功，可以做后续处理
                    }
                    is Resource.Error -> {
                        // 静默失败，本地已添加
                    }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    fun removeFavorite(locationId: String) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(locationId)
        }
    }
}
