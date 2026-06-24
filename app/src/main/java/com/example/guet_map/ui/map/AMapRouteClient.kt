package com.example.guet_map.ui.map

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 高德地图路径规划客户端
 * 使用高德 Web API 实现步行、驾车路径规划
 */
class AMapRouteClient(private val context: Context) {

    private val TAG = "AMapRouteClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    // 高德地图 API Key（从 Manifest meta-data 读取）
    private val apiKey: String by lazy {
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        appInfo.metaData?.getString("com.amap.api.v2.apikey").orEmpty()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var onRouteResult: ((RouteResult) -> Unit)? = null
    var onRouteError: ((String) -> Unit)? = null

    /**
     * 步行路径规划
     */
    fun searchWalkingRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ) {
        scope.launch {
            try {
                val origin = "$originLng,$originLat"  // 高德API格式：经度,纬度
                val destination = "$destLng,$destLat"

                val url = "https://restapi.amap.com/v3/direction/walking?origin=$origin&destination=$destination&key=$apiKey"

                Log.d(TAG, "路径规划 URL: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "路径规划响应: $body")

                    if (body != null) {
                        val routeResponse = gson.fromJson(body, AmapRouteResponse::class.java)

                        if (routeResponse.status == "1" && routeResponse.route != null) {
                            val route = routeResponse.route
                            val paths = route.paths ?: emptyList()

                            if (paths.isNotEmpty()) {
                                val firstPath = paths.first()
                                val stepList = firstPath.steps ?: emptyList()
                                val steps = stepList.map { step ->
                                    RouteStep(
                                        instruction = step.instruction ?: "",
                                        road = step.road ?: "",
                                        distance = step.distance ?: "0",
                                        duration = step.duration ?: "0",
                                        polyline = parsePolyline(step.polyline)
                                    )
                                }

                                val result = RouteResult(
                                    distance = firstPath.distance ?: "0",
                                    duration = firstPath.duration ?: "0",
                                    strategy = firstPath.strategy ?: "步行",
                                    steps = steps
                                )

                                mainHandler.post {
                                    onRouteResult?.invoke(result)
                                }
                            } else {
                                mainHandler.post {
                                    onRouteError?.invoke("未找到可行路径")
                                }
                            }
                        } else {
                            mainHandler.post {
                                onRouteError?.invoke(routeResponse.info ?: "路径规划失败")
                            }
                        }
                    } else {
                        mainHandler.post {
                            onRouteError?.invoke("网络响应为空")
                        }
                    }
                } else {
                    Log.e(TAG, "HTTP 错误: ${response.code}")
                    mainHandler.post {
                        onRouteError?.invoke("网络错误: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "路径规划异常: ${e.message}", e)
                mainHandler.post {
                    onRouteError?.invoke("路径规划失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 解析高德 polyline 字符串为 LatLng 列表
     * 格式：经度,纬度;经度,纬度;...
     */
    private fun parsePolyline(polyline: String?): List<Pair<Double, Double>> {
        if (polyline.isNullOrEmpty()) return emptyList()

        return try {
            polyline.split(";").mapNotNull { point ->
                val parts = point.split(",")
                if (parts.size >= 2) {
                    val lng = parts[0].toDoubleOrNull()
                    val lat = parts.getOrNull(1)?.toDoubleOrNull()
                    if (lng != null && lat != null) Pair(lat, lng) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 polyline 失败: ${e.message}")
            emptyList()
        }
    }

    fun destroy() {
        // 清理资源
    }
}

/**
 * 高德路径规划响应
 */
data class AmapRouteResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("info") val info: String?,
    @SerializedName("count") val count: String?,
    @SerializedName("route") val route: AmapRoute?
)

data class AmapRoute(
    @SerializedName("origin") val origin: String?,
    @SerializedName("destination") val destination: String?,
    @SerializedName("paths") val paths: List<AmapPath>?
)

data class AmapPath(
    @SerializedName("distance") val distance: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("strategy") val strategy: String?,
    @SerializedName("steps") val steps: List<AmapStep>?
)

data class AmapStep(
    @SerializedName("instruction") val instruction: String?,
    @SerializedName("road") val road: String?,
    @SerializedName("distance") val distance: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("polyline") val polyline: String?,
    @SerializedName("action") val action: String?,
    @SerializedName("assistant_action") val assistantAction: String?
)

/**
 * 路径规划结果
 */
data class RouteResult(
    val distance: String,      // 总距离（米）
    val duration: String,     // 总时间（秒）
    val strategy: String,     // 规划策略
    val steps: List<RouteStep>
) {
    val distanceInt: Int get() = distance.toIntOrNull() ?: 0
    val durationInt: Int get() = duration.toIntOrNull() ?: 0

    val distanceKm: String
        get() {
            val meters = distanceInt
            return if (meters >= 1000) {
                String.format("%.1f km", meters / 1000.0)
            } else {
                "$meters m"
            }
        }

    val durationText: String
        get() {
            val seconds = durationInt
            return when {
                seconds >= 3600 -> "${seconds / 3600}小时${(seconds % 3600) / 60}分钟"
                seconds >= 60 -> "${seconds / 60}分钟"
                else -> "$seconds 秒"
            }
        }
}

/**
 * 路径步骤
 */
data class RouteStep(
    val instruction: String?,  // 导航指示
    val road: String?,        // 道路名称
    val distance: String?,     // 距离（米）
    val duration: String?,    // 时间（秒）
    val polyline: List<Pair<Double, Double>>  // 坐标点列表 (纬度, 经度)
)
