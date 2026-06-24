package com.example.guet_map.ui.map

import android.graphics.Color
import android.util.Log
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.example.guet_map.R

/**
 * 导航管理器
 * 负责在地图上绘制路径和标记
 */
class RouteNavigator(private val aMap: AMap) {

    private val TAG = "RouteNavigator"
    private var startMarker: com.amap.api.maps.model.Marker? = null
    private var endMarker: com.amap.api.maps.model.Marker? = null
    private var polyline: com.amap.api.maps.model.Polyline? = null
    private var guidePolyline: com.amap.api.maps.model.Polyline? = null

    var onRouteCalculated: ((RouteResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var currentStart: LatLng? = null
    private var currentEnd: LatLng? = null

    /**
     * 绘制高德路径规划结果
     */
    fun drawRoute(routeResult: RouteResult) {
        clearRoute()

        // 收集所有路径点
        val allPoints = mutableListOf<LatLng>()
        routeResult.steps.forEach { step ->
            step.polyline.forEach { (lat, lng) ->
                // Pair is (latitude, longitude) from AMapRouteClient.parsePolyline
                allPoints.add(LatLng(lat, lng))
            }
        }

        if (allPoints.size < 2) {
            Log.w(TAG, "路径点不足，无法绘制")
            onError?.invoke("路径数据不足")
            return
        }

        // 设置起点和终点
        currentStart = allPoints.first()
        currentEnd = allPoints.last()

        // 绘制路径线（蓝色）
        polyline = aMap.addPolyline(
            PolylineOptions()
                .add(*allPoints.toTypedArray())
                .color(Color.parseColor("#1A73E8"))
                .width(15f)
        )

        // 添加起点和终点标记
        addMarkers(currentStart!!, currentEnd!!)

        // 调整地图视野
        fitRouteView(allPoints)

        // 回调
        onRouteCalculated?.invoke(routeResult)
    }

    /**
     * 搜索步行路径（模拟）
     * @param startLatLng 起点经纬度
     * @param endLatLng 终点经纬度
     */
    fun searchRoute(startLatLng: LatLng, endLatLng: LatLng) {
        clearRoute()

        currentStart = startLatLng
        currentEnd = endLatLng

        // 计算直线距离
        val distance = calculateDistance(startLatLng, endLatLng)

        // 生成模拟路径点
        val routePoints = generateRoutePoints(startLatLng, endLatLng, 20)

        if (routePoints.size < 2) {
            onError?.invoke("路径生成失败")
            return
        }

        // 绘制路径线
        val points = routePoints.toTypedArray()
        val polylineOptions = PolylineOptions()
        polylineOptions.add(points[0])
        for (i in 1 until points.size) {
            polylineOptions.add(points[i])
        }
        polylineOptions.color(Color.parseColor("#1A73E8"))
        polylineOptions.width(15f)
        polyline = aMap.addPolyline(polylineOptions)

        // 添加起点和终点标记
        addMarkers(startLatLng, endLatLng)

        // 调整地图视野
        fitRouteView(routePoints)

        // 计算步行时间（按每秒1.2米估算）
        val duration = (distance / 1.2).toInt()

        // 回调
        val result = RouteResult(
            distance = distance.toInt().toString(),
            duration = duration.toString(),
            strategy = "步行",
            steps = emptyList()
        )
        onRouteCalculated?.invoke(result)
    }

    /**
     * 清除已绘制的路径和标记
     */
    fun clearRoute() {
        polyline?.remove()
        polyline = null
        guidePolyline?.remove()
        guidePolyline = null
        startMarker?.remove()
        endMarker?.remove()
        startMarker = null
        endMarker = null
    }

    /**
     * 将地图视野调整到适合显示整个路径
     */
    private fun fitRouteView(points: List<LatLng>) {
        if (points.size >= 2) {
            val boundsBuilder = com.amap.api.maps.model.LatLngBounds.Builder()
            for (point in points) {
                boundsBuilder.include(point)
            }
            val bounds = boundsBuilder.build()

            val update = com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(bounds, 100)
            aMap.animateCamera(update, 500, null)
        }
    }

    /**
     * 添加起点和终点标记
     */
    private fun addMarkers(start: LatLng, end: LatLng) {
        // 起点标记（蓝色）
        startMarker = aMap.addMarker(
            MarkerOptions()
                .position(start)
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_start_point))
                .title("起点")
        )

        // 终点标记（绿色）
        endMarker = aMap.addMarker(
            MarkerOptions()
                .position(end)
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_end_point))
                .title("终点")
        )
    }

    /**
     * 使用 Haversine 公式计算两点间的距离（米）
     */
    private fun calculateDistance(start: LatLng, end: LatLng): Double {
        val R = 6371000.0 // 地球半径（米）

        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val deltaLat = Math.toRadians(end.latitude - start.latitude)
        val deltaLon = Math.toRadians(end.longitude - start.longitude)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }

    /**
     * 生成两点之间的模拟路径点
     */
    private fun generateRoutePoints(start: LatLng, end: LatLng, numPoints: Int): List<LatLng> {
        val points = mutableListOf<LatLng>()

        for (i in 0..numPoints) {
            val t = i.toDouble() / numPoints
            val lat = start.latitude + (end.latitude - start.latitude) * t
            val lon = start.longitude + (end.longitude - start.longitude) * t
            points.add(LatLng(lat, lon))
        }

        return points
    }

    /**
     * 获取路径总距离
     */
    fun getRouteDistance(): Double {
        val start = currentStart ?: return 0.0
        val end = currentEnd ?: return 0.0
        return calculateDistance(start, end)
    }

    /**
     * 获取路径总耗时（秒）
     */
    fun getRouteDuration(): Int {
        return (getRouteDistance() / 1.2).toInt()
    }

    /**
     * 销毁资源
     */
    fun destroy() {
        clearRoute()
    }
}
