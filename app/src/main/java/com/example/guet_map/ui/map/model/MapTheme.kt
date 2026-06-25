package com.example.guet_map.ui.map.model

/**
 * 地图主题类型
 */
enum class MapThemeType(
    val value: String,
    val displayName: String,
    val description: String,
    val iconRes: Int,
    val mapType: Int
) {
    /**
     * 标准地图 - 默认地图样式
     */
    NORMAL(
        value = "normal",
        displayName = "标准地图",
        description = "清晰的道路和建筑标注",
        iconRes = com.example.guet_map.R.drawable.ic_map_normal,
        mapType = com.amap.api.maps.AMap.MAP_TYPE_NORMAL
    ),

    /**
     * 卫星地图 - 卫星影像
     */
    SATELLITE(
        value = "satellite",
        displayName = "卫星地图",
        description = "真实的卫星影像视图",
        iconRes = com.example.guet_map.R.drawable.ic_map_satellite,
        mapType = com.amap.api.maps.AMap.MAP_TYPE_SATELLITE
    ),

    /**
     * 夜间模式 - 深色主题
     */
    NIGHT(
        value = "night",
        displayName = "夜间模式",
        description = "适合夜间使用的深色主题",
        iconRes = com.example.guet_map.R.drawable.ic_map_night,
        mapType = com.amap.api.maps.AMap.MAP_TYPE_NIGHT
    ),

    /**
     * 导航模式 - 简化视图
     */
    NAVI(
        value = "navi",
        displayName = "导航模式",
        description = "突出道路信息的导航视图",
        iconRes = com.example.guet_map.R.drawable.ic_map_navi,
        mapType = com.amap.api.maps.AMap.MAP_TYPE_NAVI
    );

    companion object {
        fun fromValue(value: String): MapThemeType {
            return entries.find { it.value == value } ?: NORMAL
        }
    }
}

/**
 * 地图主题信息
 */
data class MapThemeInfo(
    val type: MapThemeType,
    val isCurrent: Boolean = false
)
