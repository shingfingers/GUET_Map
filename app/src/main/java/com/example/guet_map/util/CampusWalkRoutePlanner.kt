package com.example.guet_map.util

import android.content.Context
import android.content.pm.PackageManager
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResult
import com.example.guet_map.model.WalkRouteInfo
import com.example.guet_map.network.AmapWalkDirectionResponse
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 步行路径：优先高德 RouteSearch SDK（与地图同一套服务），失败时回退 Web API。
 */
@Singleton
class CampusWalkRoutePlanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun planWalkRoute(
        start: LatLng,
        end: LatLng,
        targetName: String,
        onSuccess: (WalkRouteInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        // 直线距离超过 5km 直接兜底，不浪费时间去调 SDK/API（步行路线规划不支持超长距离）
        val directDistance = haversineMeters(start, end)
        if (directDistance > 5000) {
            onSuccess(buildFallbackRoute(start, end, targetName))
            return
        }

        scope.launch {
            try {
                val route = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    // Web API 优先（比 SDK 更可靠），在 IO 线程
                    val webRoute = try {
                        withContext(Dispatchers.IO) {
                            requestWalkRouteWeb(start, end, targetName)
                        }
                    } catch (_: Exception) { null }

                    // Web API 失败则回退 SDK
                    if (webRoute != null) webRoute else {
                        try {
                            withContext(Dispatchers.Main) {
                                requestWalkRouteSdk(start, end, targetName)
                            }
                        } catch (_: Exception) { null }
                    }
                } ?: buildFallbackRoute(start, end, targetName)

                onSuccess(route)
            } catch (e: Exception) {
                onError(e.message ?: "路径规划失败")
            }
        }
    }

    private suspend fun requestWalkRouteSdk(
        start: LatLng,
        end: LatLng,
        targetName: String
    ): WalkRouteInfo? = suspendCancellableCoroutine { cont ->
        try {
            val routeSearch = RouteSearch(context)
            routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onWalkRouteSearched(result: WalkRouteResult?, errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode != 1000 || result == null || result.paths.isNullOrEmpty()) {
                        cont.resume(null)
                        return
                    }
                    try {
                        cont.resume(pathToWalkRouteInfo(result.paths[0], targetName))
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }

                override fun onBusRouteSearched(p0: com.amap.api.services.route.BusRouteResult?, p1: Int) = Unit
                override fun onDriveRouteSearched(p0: com.amap.api.services.route.DriveRouteResult?, p1: Int) = Unit
                override fun onRideRouteSearched(p0: com.amap.api.services.route.RideRouteResult?, p1: Int) = Unit
            })

            val fromAndTo = RouteSearch.FromAndTo(
                LatLonPoint(start.latitude, start.longitude),
                LatLonPoint(end.latitude, end.longitude)
            )
            routeSearch.calculateWalkRouteAsyn(
                RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WalkDefault)
            )
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun pathToWalkRouteInfo(path: WalkPath, targetName: String): WalkRouteInfo {
        val points = mutableListOf<LatLng>()
        path.steps.orEmpty().forEach { step ->
            step.polyline.orEmpty().forEach { pt ->
                points.add(LatLng(pt.latitude, pt.longitude))
            }
        }
        if (points.isEmpty()) throw IllegalStateException("路线坐标为空")
        return WalkRouteInfo(
            targetName = targetName,
            distanceMeters = path.distance.toInt(),
            durationSeconds = path.duration.toInt(),
            polyline = points
        )
    }

    private fun requestWalkRouteWeb(start: LatLng, end: LatLng, targetName: String): WalkRouteInfo {
        val key = readAmapWebKey()
        if (key.isBlank()) throw IllegalStateException("未配置高德 API Key")

        val origin = "${start.longitude},${start.latitude}"
        val destination = "${end.longitude},${end.latitude}"
        val url = "https://restapi.amap.com/v3/direction/walking" +
            "?key=$key&origin=$origin&destination=$destination"

        val response = okHttpClient.newCall(Request.Builder().url(url).get().build()).execute()
        val body = response.body?.string() ?: throw IllegalStateException("空响应")
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")

        val parsed = gson.fromJson(body, AmapWalkDirectionResponse::class.java)
        if (parsed.status != "1") throw IllegalStateException(parsed.info ?: "路径规划失败")
        val path = parsed.route?.paths?.firstOrNull() ?: throw IllegalStateException("无可用路径")

        val points = mutableListOf<LatLng>()
        path.steps.orEmpty().forEach { step ->
            decodePolyline(step.polyline).forEach { points.add(it) }
        }
        if (points.isEmpty()) throw IllegalStateException("路线坐标为空")

        return WalkRouteInfo(
            targetName = targetName,
            distanceMeters = path.distance?.toIntOrNull() ?: 0,
            durationSeconds = path.duration?.toIntOrNull() ?: 0,
            polyline = points
        )
    }

    private fun buildFallbackRoute(start: LatLng, end: LatLng, targetName: String): WalkRouteInfo {
        val distance = haversineMeters(start, end)
        val duration = ((distance / 80.0) * 60).toInt().coerceAtLeast(60)
        val points = listOf(start, end)
        return WalkRouteInfo(
            targetName = targetName,
            distanceMeters = distance,
            durationSeconds = duration,
            polyline = points
        )
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Int {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val sinLat = kotlin.math.sin(dLat / 2)
        val sinLng = kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.asin(
            kotlin.math.sqrt(sinLat * sinLat + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinLng * sinLng)
        )
        return (earthRadius * c).toInt()
    }

    /**
     * 高德 Web API 返回格式：经度,纬度;经度,纬度;...
     * AMap SDK 返回 LatLonPoint(lat, lon) 格式。
     * 统一转为 LatLng(lat, lng)。
     */
    private fun decodePolyline(polyline: String?): List<LatLng> {
        if (polyline.isNullOrBlank()) return emptyList()
        return polyline.split(";").mapNotNull { segment ->
            val parts = segment.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lng = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            LatLng(lat, lng)
        }
    }

    private fun readAmapWebKey(): String {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        return appInfo.metaData?.getString("com.amap.api.v2.apikey").orEmpty()
    }
}
