package com.example.guet_map.util

import com.amap.api.maps.model.BitmapDescriptorFactory

/**
 * 地图Marker颜色管理
 * 根据不同地点分类返回对应的Marker颜色
 */
object MarkerColorHelper {

    /**
     * 根据地点分类获取Marker颜色色值
     * 
     * @param category 地点分类名称
     * @return Marker颜色色值 (HUE)
     */
    fun getMarkerHueByCategory(category: String?): Float {
        return when (category) {
            "教室", "教学楼", "实验室" -> BitmapDescriptorFactory.HUE_BLUE        // 蓝色
            "食堂", "餐厅", "堂" -> BitmapDescriptorFactory.HUE_ORANGE          // 橙色
            "图书馆" -> BitmapDescriptorFactory.HUE_RED                         // 红色
            "宿舍", "学生公寓" -> BitmapDescriptorFactory.HUE_GREEN                // 绿色
            "校门" -> BitmapDescriptorFactory.HUE_YELLOW                        // 黄色
            "商店", "便利店", "超市" -> BitmapDescriptorFactory.HUE_MAGENTA      // 品红色
            "运动场", "操场", "体育馆" -> BitmapDescriptorFactory.HUE_VIOLET      // 紫色
            "咖啡", "咖啡厅" -> BitmapDescriptorFactory.HUE_ROSE                // 棕红色
            else -> BitmapDescriptorFactory.HUE_AZURE                           // 默认蓝绿色
        }
    }

    /**
     * 根据地点分类获取Marker颜色描述
     * 
     * @param category 地点分类名称
     * @return 颜色描述文本
     */
    fun getColorDescription(category: String?): String {
        return when (category) {
            "教室", "教学楼", "实验室" -> "蓝色"
            "食堂", "餐厅", "堂" -> "橙色"
            "图书馆" -> "红色"
            "宿舍", "学生公寓" -> "绿色"
            "校门" -> "黄色"
            "商店", "便利店", "超市" -> "品红色"
            "运动场", "操场", "体育馆" -> "紫色"
            "咖啡", "咖啡厅" -> "棕红色"
            else -> "蓝绿色"
        }
    }
}
