package com.example.guet_map.module.ai.domain.service

import com.example.guet_map.model.Resource
import com.example.guet_map.module.ai.data.local.AiPromptProvider
import com.example.guet_map.module.ai.data.model.AiAction
import com.example.guet_map.module.ai.data.model.AiResponse
import com.example.guet_map.module.ai.data.model.ChatMessage
import com.example.guet_map.module.ai.data.model.ChatRole
import com.example.guet_map.module.ai.data.remote.DeepSeekApi
import com.example.guet_map.module.ai.data.remote.DeepSeekConfigProvider
import com.example.guet_map.module.ai.data.remote.DeepSeekConstants
import com.example.guet_map.module.ai.data.remote.DeepSeekMessage
import com.example.guet_map.module.ai.data.remote.DeepSeekRequest
import com.example.guet_map.module.ai.data.repository.ChatRepository
import com.example.guet_map.module.ai.domain.parser.AiResponseParser
import com.example.guet_map.module.social.data.repository.WeatherRepository
import com.example.guet_map.util.FetchWeatherSafetyUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 服务实现：接入 DeepSeek deepseek-chat，并在失败时安全降级。
 */
@Singleton
class AiServiceImpl @Inject constructor(
    private val chatRepository: ChatRepository,
    private val promptProvider: AiPromptProvider,
    private val responseParser: AiResponseParser,
    private val deepSeekApi: DeepSeekApi,
    private val configProvider: DeepSeekConfigProvider,
    private val weatherRepository: WeatherRepository,
    private val fetchWeatherSafetyUseCase: FetchWeatherSafetyUseCase
) : AiService {

    override suspend fun sendMessage(
        sessionId: String,
        userMessage: String,
        locationContext: String?,
        navigationCallback: AiNavigationCallback?
    ): Resource<ChatMessage> {
        return try {
            chatRepository.saveMessage(sessionId, ChatRole.USER, userMessage)

            val parsed = requestAiResponse(
                userMessage = userMessage,
                locationContext = locationContext
            )

            // 处理 AI Action
            var responseText = parsed.text ?: "AI 已返回结果，但暂时无法展示完整内容。"
            if (parsed.responseType == AiResponse.ResponseType.ACTION && parsed.action != null) {
                handleNavigationAction(parsed.action, navigationCallback, locationContext)
            }

            val assistantMessage = chatRepository.saveMessage(
                sessionId = sessionId,
                role = ChatRole.ASSISTANT,
                content = responseText,
                locationId = locationContext
            )

            Resource.Success(assistantMessage)
        } catch (e: Exception) {
            // 超时或网络错误时，使用本地降级响应
            val fallbackText = fallbackLocalResponse(userMessage, locationContext)
            val assistantMessage = chatRepository.saveMessage(
                sessionId = sessionId,
                role = ChatRole.ASSISTANT,
                content = fallbackText,
                locationId = locationContext
            )
            Resource.Success(assistantMessage)
        }
    }

    private suspend fun handleNavigationAction(
        action: AiAction,
        callback: AiNavigationCallback?,
        locationContext: String?
    ) {
        when (action.action) {
            AiAction.ActionType.NAVIGATE_TO -> {
                val targetName = action.payload["targetName"]?.toString() ?: ""
                val locationId = action.payload["targetLocationId"]?.toString()
                val fallbackQuery = action.payload["fallbackQuery"]?.toString()
                callback?.navigateTo(locationId, targetName, fallbackQuery)
            }
            AiAction.ActionType.SHOW_ROUTE -> {
                val targetName = action.payload["targetName"]?.toString() ?: ""
                val fallbackQuery = action.payload["fallbackQuery"]?.toString()
                callback?.showRoute(locationContext, targetName, fallbackQuery)
            }
            else -> {
                // 其他 action 类型暂不处理
            }
        }
    }

    override suspend fun sendStructuredMessage(
        sessionId: String,
        userMessage: String,
        locationContext: String?
    ): Resource<AiResponse> {
        return try {
            chatRepository.saveMessage(sessionId, ChatRole.USER, userMessage)

            val parsed = requestAiResponse(
                userMessage = userMessage,
                locationContext = locationContext
            )
            chatRepository.saveMessage(
                sessionId = sessionId,
                role = ChatRole.ASSISTANT,
                content = parsed.text ?: "AI 已返回结构化结果。",
                locationId = locationContext
            )

            Resource.Success(parsed)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "AI 服务异常，请稍后再试")
        }
    }

    override fun sendMessageStream(
        sessionId: String,
        userMessage: String,
        locationContext: String?
    ): Flow<Resource<String>> = flow {
        emit(Resource.Loading)
        when (val result = sendMessage(sessionId, userMessage, locationContext)) {
            is Resource.Success -> emit(Resource.Success(result.data.content))
            is Resource.Error -> emit(Resource.Error(result.message))
            is Resource.Loading -> emit(Resource.Loading)
        }
    }

    override suspend fun generateGuidedQuestions(locationId: String?): Resource<List<String>> {
        return Resource.Success(
            listOf(
                "这个地点怎么走？",
                "带我去下一节课",
                "附近有什么校园设施？",
                "如果我要上课，应该怎么走？"
            )
        )
    }

    private suspend fun requestAiResponse(
        userMessage: String,
        locationContext: String?
    ): AiResponse {
        val apiKey = configProvider.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return responseParser.parse(fallbackLocalResponse(userMessage, locationContext))
        }

        val messages = promptProvider.buildMessages(
            userMessage = userMessage,
            locationContext = locationContext,
            history = emptyList()
        )
        val rawContent = callDeepSeekWithRetry(
            apiKey = apiKey,
            messages = messages
        )
        return responseParser.parse(rawContent)
    }

    private suspend fun callDeepSeekWithRetry(
        apiKey: String,
        messages: List<DeepSeekMessage>
    ): String {
        var lastError: String? = null
        repeat(DeepSeekConstants.MAX_RETRY + 1) { attempt ->
            val result = runCatching {
                val request = DeepSeekRequest(
                    model = configProvider.getModel(),
                    messages = messages
                )
                val response = deepSeekApi.createChatCompletion(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    val errorMsg = when (response.code()) {
                        401 -> "API Key 无效或已过期，请检查 DeepSeek API Key"
                        402 -> "账户余额不足，请访问 https://platform.deepseek.com 充值"
                        429 -> "请求过于频繁，请稍后再试"
                        500, 502, 503 -> "DeepSeek 服务暂时不可用"
                        else -> "DeepSeek 请求失败：${response.code()} ${response.message()}"
                    }
                    error("$errorMsg | 详情: $errorBody")
                }

                val body = response.body() ?: error("DeepSeek 返回为空")
                body.error?.message?.let { error(it) }
                body.choices
                    ?.firstOrNull()
                    ?.message
                    ?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: error("DeepSeek 未返回有效内容")
            }

            result.onSuccess { return it }
            result.onFailure { throwable ->
                lastError = throwable.message
                if (attempt < DeepSeekConstants.MAX_RETRY) {
                    delay(300)
                }
            }
        }
        error(lastError ?: "DeepSeek 服务暂时不可用")
    }

    private fun fallbackLocalResponse(message: String, locationContext: String?): String {
        return when {
            isWeatherQuery(message) -> buildWeatherFallbackResponse()

            message.contains("下一节课") || message.contains("去上课") || message.contains("上课") ->
                "AI 服务尚未配置 DeepSeek API Key。请先配置后再使用课表智能导航；如果你已经知道教室，也可以直接搜索目标地点。"

            message.contains("怎么走") || message.contains("路线") || message.contains("导航") ->
                "AI 服务尚未配置 DeepSeek API Key。你可以先使用地图搜索目标地点进行导航。"

            locationContext != null ->
                "AI 服务尚未配置 DeepSeek API Key。当前地点上下文：$locationContext。"

            else ->
                "你好，我是 GUET Map 校园 AI 助手。当前 DeepSeek API Key 未配置，暂时只能提供基础提示。"
        }
    }

    private fun isWeatherQuery(message: String): Boolean {
        val weatherKeywords = listOf(
            "天气", "气温", "温度", "下雨", "下雪", "刮风",
            "紫外线", "空气质量", "AQI", "pm2.5", "穿衣",
            "要不要带伞", "热不热", "冷不冷", "适合出门吗"
        )
        return weatherKeywords.any { keyword -> message.contains(keyword) }
    }

    private fun buildWeatherFallbackResponse(): String {
        return try {
            runBlocking {
                when (val result = weatherRepository.getWeather()) {
                    is com.example.guet_map.model.Resource.Success -> {
                        val weather = result.data
                        buildString {
                            appendLine("📍 桂林电子科技大学今日天气")
                            appendLine("━━━━━━━━━━━━━━━━")
                            appendLine("🌤️ ${weather.description}")
                            appendLine("🌡️ 气温：${weather.temperature}°C（体感 ${weather.feelsLike}°C）")
                            appendLine("💧 湿度：${weather.humidity}%")
                            appendLine("🌬️ 风力：${weather.windDirection} ${weather.windSpeed}级")
                            if (!weather.aqiLevel.isNullOrEmpty()) {
                                appendLine("🌿 空气质量：${weather.aqiLevel}（AQI ${weather.aqi}）")
                            }
                            weather.uvIndex?.let { appendLine("☀️ 紫外线指数：$it") }
                            weather.alertMessage?.let { appendLine("⚠️ $it") }

                            val tips = fetchWeatherSafetyUseCase.invoke(0.0, 0.0)
                            if (tips.isNotEmpty()) {
                                appendLine()
                                appendLine("💡 $tips")
                            }
                        }
                    }
                    is com.example.guet_map.model.Resource.Error -> {
                        "抱歉，暂时无法获取天气信息，请稍后再试。"
                    }
                    is com.example.guet_map.model.Resource.Loading -> {
                        "正在获取天气信息..."
                    }
                }
            }
        } catch (e: Exception) {
            "抱歉，AI 服务尚未配置，无法回答天气相关问题。"
        }
    }
}
