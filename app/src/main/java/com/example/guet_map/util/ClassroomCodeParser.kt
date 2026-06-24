package com.example.guet_map.util

import com.example.guet_map.model.Location

/**
 * 解析教室代码并转换为教学楼导航
 *
 * 规则：
 * - 5 位教室代码：如 07101、42101
 * - 前两位（或更多）是教学楼号
 * - 07101 → 7教（去掉前导 0）
 * - 42101 → 42教（直接取前两位）
 *
 * 匹配逻辑：
 * 1. 尝试精确匹配教学楼编号（如 "7教"、"42教"）
 * 2. 如果教学楼在地图上有对应位置，返回该位置进行导航
 */
object ClassroomCodeParser {

    /**
     * 从教室代码解析教学楼名称
     * @param classroomCode 教室代码，如 "07101"、"42101"
     * @return 教学楼名称，如 "7教"、"42教"，无法解析时返回 null
     */
    fun parseToBuildingName(classroomCode: String): String? {
        val code = classroomCode.trim()
        if (code.length != 5 || !code.all { it.isDigit() }) {
            return null
        }

        // 前两位数字是教学楼号
        val prefix = code.substring(0, 2)
        val buildingNum = prefix.toIntOrNull() ?: return null

        // 42xx 表示 42 教学楼
        if (prefix.startsWith("4") && code[1].isDigit()) {
            return "${code.substring(0, 2)}教"
        }

        // 其他情况去掉前导 0：07xxx → 7教
        val cleanNum = prefix.toInt()
        return "${cleanNum}教"
    }

    /**
     * 查找与教学楼名称匹配的位置
     * @param buildingName 教学楼名称，如 "7教"、"42教"
     * @param locations 所有可用位置
     * @return 匹配的教学楼位置，未找到时返回 null
     */
    fun findBuildingLocation(buildingName: String, locations: List<Location>): Location? {
        if (buildingName.isBlank()) return null

        // 精确匹配 "X教" 或 "第X教学楼"
        val exactMatch = locations.find { loc ->
            loc.name.contains(buildingName) ||
            loc.name.contains("第${buildingName.replace("教", "教学")}")
        }
        if (exactMatch != null) return exactMatch

        // 解析数字后匹配
        val buildingNum = parseBuildingNumber(buildingName) ?: return null

        // 尝试各种命名变体
        val variants = listOf(
            "${buildingNum}教",
            "${buildingNum}教学楼",
            "第${buildingNum}教学楼",
            "第${buildingNum}教学",
            "桂林电子科技大学${buildingNum}教学楼"
        )

        for (variant in variants) {
            val match = locations.find { loc ->
                loc.name.contains(variant, ignoreCase = true)
            }
            if (match != null) return match
        }

        // 使用 CampusBuildingCatalog 查找
        CampusBuildingCatalog.findTeachingBuilding(buildingNum)?.let { entry ->
            val match = locations.find { loc ->
                loc.locationId == entry.locationId ||
                loc.name.contains(entry.displayName, ignoreCase = true)
            }
            if (match != null) return match
        }

        return null
    }

    /**
     * 解析教学楼名称中的数字
     */
    private fun parseBuildingNumber(buildingName: String): Int? {
        // 匹配 "42教"、"7教" 等格式
        Regex("(\\d+)教").find(buildingName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        // 匹配 "第42教学楼" 格式
        Regex("第(\\d+)教学楼").find(buildingName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /**
     * 将教室代码转换为导航目标
     * @param classroomCode 教室代码，如 "07101"
     * @param locations 所有可用位置
     * @return 导航建议，包含教学楼位置和教室代码
     */
    fun resolveNavigationTarget(classroomCode: String, locations: List<Location>): NavigationTarget? {
        val buildingName = parseToBuildingName(classroomCode) ?: return null
        val buildingLocation = findBuildingLocation(buildingName, locations)

        return NavigationTarget(
            classroomCode = classroomCode,
            buildingName = buildingName,
            buildingLocation = buildingLocation,
            buildingLatitude = buildingLocation?.latitude,
            buildingLongitude = buildingLocation?.longitude,
            locationId = buildingLocation?.locationId
        )
    }

    data class NavigationTarget(
        val classroomCode: String,
        val buildingName: String,
        val buildingLocation: Location?,
        val buildingLatitude: Double?,
        val buildingLongitude: Double?,
        val locationId: String?
    )
}
