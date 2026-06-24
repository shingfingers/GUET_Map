package com.example.guet_map.util

import com.example.guet_map.model.Location
import com.amap.api.maps.model.LatLng

/**
 * 花江校区教学楼/常用地点目录：
 * 1. 预定义所有已知地点的准确坐标 (fallbackLat/fallbackLng)
 * 2. 支持搜索别名（一教、5教等）
 * 3. mergeInto() 方法用准确坐标覆盖高德SDK返回的不准确坐标
 */
object CampusBuildingCatalog {

    data class BuildingEntry(
        val locationId: String,
        val displayName: String,
        val searchKeyword: String,
        val aliases: List<String>,
        val category: String = "教室",
        val fallbackLat: Double,
        val fallbackLng: Double,
        val hasGuide: Boolean = false
    ) {
        fun matchesName(name: String): Boolean {
            if (name.contains(displayName)) return true
            val num = locationId.removePrefix("building_").toIntOrNull()
            if (num != null) {
                val cn = chineseOrdinal(num)
                if (name.contains("第${cn}教学") || aliases.any { name.contains(it, ignoreCase = true) }) {
                    return true
                }
            }
            return aliases.any { name.contains(it, ignoreCase = true) }
        }

        fun matchesLocation(location: Location): Boolean =
            location.locationId == locationId || matchesName(location.name)
    }

    val teachingBuildings: List<BuildingEntry> = listOf(
        entry(1, 25.30325, 110.41475),
        entry(2, 25.30305, 110.41495, hasGuide = true),
        entry(3, 25.30590, 110.41650),
        entry(4, 25.30650, 110.41630),
        entry(5, 25.30700, 110.41600),
        entry(6, 25.30740, 110.41580),
        entry(7, 25.30780, 110.41560),
        entry(8, 25.30810, 110.41540),
        entry(9, 25.30850, 110.41720, hasGuide = true),
        entry(10, 25.30800, 110.41700),
        entry(11, 25.30780, 110.41750, hasGuide = true),
        entry(12, 25.30820, 110.41650),
        entry(13, 25.30760, 110.41620),
        entry(14, 25.30720, 110.41640),
        entry(15, 25.30680, 110.42080),
        entry(16, 25.30900, 110.41680),
        entry(17, 25.30940, 110.41660)
    ) + listOf(
        BuildingEntry(
            locationId = "building_11a",
            displayName = "第十一教学楼A区",
            searchKeyword = "桂林电子科技大学花江校区第十一教学楼A区",
            aliases = listOf("11教A", "十一教A", "11A"),
            fallbackLat = 25.30780,
            fallbackLng = 110.41750,
            hasGuide = true
        ),
        BuildingEntry(
            locationId = "building_11b",
            displayName = "第十一教学楼B区",
            searchKeyword = "桂林电子科技大学花江校区第十一教学楼B区",
            aliases = listOf("11教B", "十一教B", "11B"),
            fallbackLat = 25.30750,
            fallbackLng = 110.41780,
            hasGuide = true
        )
    )

    val extraCampusPlaces: List<BuildingEntry> = listOf(
        BuildingEntry("foreign_language", "外国语学院", "桂林电子科技大学花江校区外国语学院", listOf("外国语学院", "外院"), "学院", 25.30788, 110.41892, true),
        BuildingEntry("information_communication", "信息与通信学院", "桂林电子科技大学花江校区信息与通信学院", listOf("信息与通信学院", "信通学院", "信息通信学院"), "学院", 25.30826, 110.41830, true),
        BuildingEntry("health_service", "卫生所", "桂林电子科技大学花江校区卫生所", listOf("卫生所", "校卫生所", "医务室"), "医疗", 25.30918, 110.41890, true),
        BuildingEntry("student_activity_center", "学生活动中心", "桂林电子科技大学花江校区学生活动中心", listOf("学生活动中心", "活动中心"), "活动中心", 25.30862, 110.41935),
        BuildingEntry("robot_center", "机器人中心", "桂林电子科技大学花江校区机器人中心", listOf("机器人中心", "机器人实验中心"), "科研", 25.30892, 110.41858),
        BuildingEntry("sichuang_center", "四创中心", "桂林电子科技大学花江校区四创中心", listOf("四创中心", "四创基地"), "科研", 25.30872, 110.41792),
        BuildingEntry("huajiang_huigu", "花江慧谷", "桂林电子科技大学花江校区花江慧谷", listOf("花江慧谷", "慧谷"), "商圈", 25.30922, 110.41985),
        BuildingEntry("yiyuan_canteen", "怡园餐厅", "桂林电子科技大学花江校区怡园餐厅", listOf("怡园餐厅", "怡园食堂", "怡园"), "食堂", 25.30672, 110.41862, true),
        BuildingEntry("zhongyuan_canteen", "仲园餐厅", "桂林电子科技大学花江校区仲园餐厅", listOf("仲园餐厅", "仲园食堂", "仲园"), "食堂", 25.30698, 110.41908, true),
        BuildingEntry("gate_west", "西门", "桂林电子科技大学花江校区西门", listOf("西门", "西大门"), "校门", 25.30450, 110.41300, true),
        BuildingEntry("gate_east", "东门", "桂林电子科技大学花江校区东门", listOf("东门", "东大门"), "校门", 25.30760, 110.42400, true),
        BuildingEntry("gate_south", "南门", "桂林电子科技大学花江校区南门", listOf("南门", "南大门"), "校门", CampusGeo.GATE_SOUTH_LAT, CampusGeo.GATE_SOUTH_LNG, true),
        BuildingEntry("gate_north", "北门", "桂林电子科技大学花江校区北门", listOf("北门", "北大门"), "校门", 25.31410, 110.41850, true),
        BuildingEntry("library", "花江校区图书馆", "桂林电子科技大学花江校区图书馆", listOf("图书馆", "图图", "校图书馆"), "图书馆", 25.30980, 110.41620, true),
        BuildingEntry("canteen_1", "第一学生食堂", "桂林电子科技大学花江校区第一食堂", listOf("一食堂", "1食堂"), "食堂", 25.30580, 110.41820),
        BuildingEntry("canteen_2", "第二学生食堂", "桂林电子科技大学花江校区第二食堂", listOf("二食堂", "2食堂"), "食堂", 25.30650, 110.41950, true),
        BuildingEntry("sports_center", "花江体育中心", "桂林电子科技大学花江校区体育中心", listOf("体育馆", "体育中心"), "运动场", 25.30680, 110.42050),
        BuildingEntry("sports_track", "田径运动场", "桂林电子科技大学花江校区田径场", listOf("操场", "田径场", "15教操场"), "运动场", 25.30720, 110.42100),
        BuildingEntry("innovation", "创新大楼", "桂林电子科技大学花江校区创新大楼", listOf("创新楼"), "教室", 25.30730, 110.41800)
    )

    val allEntries: List<BuildingEntry> = teachingBuildings + extraCampusPlaces

    /** 高德 POI 批量检索用的教学楼关键字 */
    fun amapTextKeywords(): List<String> = buildList {
        allEntries.forEach { add(it.searchKeyword) }
        add("桂林电子科技大学花江校区教学楼")
        add("桂电花江教学楼")
    }

    /**
     * 合并高德POI数据和已知地点数据
     * 关键：已知地点使用 CampusBuildingCatalog 中的准确坐标
     */
    fun mergeInto(existing: List<Location>): List<Location> {
        val amapPois = existing.filter { loc ->
            !CampusBuildingNumbers.shouldDropAmapTeachingPoi(loc.name)
        }.toMutableList()

        // 用准确坐标覆盖已知地点的坐标
        val result = amapPois.map { loc ->
            val entry = findEntryByAlias(loc.name)
            if (entry != null && entry.matchesName(loc.name)) {
                // 使用准确坐标
                locationFromEntry(entry)
            } else {
                loc
            }
        }.toMutableList()

        // 添加缺失的已知地点
        for (entry in teachingBuildings) {
            if (!entryAlreadyPresent(result, entry)) {
                result.add(locationFromEntry(entry))
            }
        }

        for (entry in extraCampusPlaces) {
            if (!entryAlreadyPresent(result, entry)) {
                result.add(locationFromEntry(entry))
            }
        }

        return result.sortedBy { it.name }
    }

    private fun entryAlreadyPresent(result: List<Location>, entry: BuildingEntry): Boolean {
        if (result.any { it.locationId == entry.locationId }) return true
        if (result.any { entry.matchesLocation(it) }) return true
        val idNum = entry.locationId.removePrefix("building_").toIntOrNull()
            ?: entry.locationId.removePrefix("building_").takeWhile { it.isDigit() }.toIntOrNull()
        if (idNum != null) {
            return result.any { loc ->
                CampusBuildingNumbers.parse(loc.name) == idNum ||
                    loc.locationId == "building_$idNum"
            }
        }
        return false
    }

    /** Mock API 离线兜底数据 */
    fun toMockLocations(): List<Location> = allEntries.map { entry ->
        Location(
            locationId = entry.locationId,
            name = entry.displayName,
            latitude = entry.fallbackLat,
            longitude = entry.fallbackLng,
            category = entry.category,
            rating = 4.2f,
            openingHours = if (entry.category == "教室") "07:00-22:30" else "",
            imageUrl = "",
            hasGuide = entry.hasGuide
        )
    }

    fun findTeachingBuilding(n: Int): BuildingEntry? =
        teachingBuildings.firstOrNull { it.locationId == "building_$n" }

    fun findEntryByAlias(query: String): BuildingEntry? {
        val q = query.trim()
        if (q.isEmpty()) return null

        val qNumEarly = CampusBuildingNumbers.parse(q)
        if (qNumEarly != null && CampusBuildingNumbers.isCatalogBuilding(qNumEarly)) {
            return resolveBuildingNumber(qNumEarly, q)
        }

        allEntries.firstOrNull { entry ->
            entry.aliases.any { q.equals(it, ignoreCase = true) } ||
                q.equals(entry.displayName, ignoreCase = true)
        }?.let { entry ->
            val num = entry.locationId.removePrefix("building_").toIntOrNull()
            if (num != null) return resolveBuildingNumber(num, q) ?: entry
            return entry
        }

        val qNum = CampusBuildingNumbers.parse(q) ?: return null
        if (!CampusBuildingNumbers.isCatalogBuilding(qNum)) return null
        return resolveBuildingNumber(qNum, q)
    }

    private fun resolveBuildingNumber(n: Int, q: String): BuildingEntry? {
        if (n == 11) {
            when {
                q.contains("A", ignoreCase = true) || q.contains("a区") ->
                    return allEntries.find { it.locationId == "building_11a" }
                q.contains("B", ignoreCase = true) || q.contains("b区") ->
                    return allEntries.find { it.locationId == "building_11b" }
                else ->
                    return allEntries.find { it.locationId == "building_11b" }
                        ?: allEntries.find { it.locationId == "building_11a" }
                        ?: allEntries.find { it.locationId == "building_11" }
            }
        }
        return allEntries.firstOrNull { it.locationId == "building_$n" }
            ?: allEntries.firstOrNull { entry ->
                entry.locationId.removePrefix("building_").toIntOrNull() == n
            }
    }

    fun locationFromEntry(entry: BuildingEntry): Location = Location(
        locationId = entry.locationId,
        name = entry.displayName,
        latitude = entry.fallbackLat,
        longitude = entry.fallbackLng,
        category = entry.category,
        rating = 4.2f,
        openingHours = if (entry.category == "教室") "07:00-22:30" else "",
        imageUrl = "",
        hasGuide = entry.hasGuide
    )

    private fun entry(
        n: Int,
        lat: Double,
        lng: Double,
        hasGuide: Boolean = false
    ): BuildingEntry {
        val cn = chineseOrdinal(n)
        val short = shortAlias(n)
        return BuildingEntry(
            locationId = "building_$n",
            displayName = "第${cn}教学楼",
            searchKeyword = "桂林电子科技大学花江校区第${cn}教学楼",
            aliases = listOf(short, "${n}教", "教学楼$n"),
            fallbackLat = lat,
            fallbackLng = lng,
            hasGuide = hasGuide
        )
    }

    private fun shortAlias(n: Int): String = when (n) {
        1 -> "一教"
        2 -> "二教"
        3 -> "三教"
        4 -> "四教"
        5 -> "五教"
        6 -> "六教"
        7 -> "七教"
        8 -> "八教"
        9 -> "九教"
        10 -> "十教"
        11 -> "十一教"
        12 -> "十二教"
        13 -> "十三教"
        14 -> "十四教"
        15 -> "十五教"
        16 -> "十六教"
        17 -> "十七教"
        else -> "${n}教"
    }

    private fun chineseOrdinal(n: Int): String = when (n) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "七"
        8 -> "八"
        9 -> "九"
        10 -> "十"
        11 -> "十一"
        12 -> "十二"
        13 -> "十三"
        14 -> "十四"
        15 -> "十五"
        16 -> "十六"
        17 -> "十七"
        else -> n.toString()
    }

    private fun Location.dedupeKey(): String =
        "${name.trim()}@${"%.5f".format(latitude)},${"%.5f".format(longitude)}"
}
