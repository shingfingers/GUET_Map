package com.example.guet_map.util

import com.example.guet_map.model.Location

/**
 * 地点坐标解析器：优先从 CampusBuildingCatalog 获取已知地点的准确坐标。
 * 对于已知地点，使用预定义的高精度坐标；对于未知地点，保留原始坐标。
 */
object CampusLocationResolver {

    /**
     * 使用 CampusBuildingCatalog 中的准确坐标覆盖 Location
     */
    fun preferAmapCoordinates(location: Location, pool: List<Location>): Location {
        val entry = CampusBuildingCatalog.findEntryByAlias(location.name)
        return if (entry != null && entry.matchesName(location.name)) {
            CampusBuildingCatalog.locationFromEntry(entry)
        } else {
            location
        }
    }

    fun resolveForQuery(query: String, pool: List<Location>): Location? {
        val matched = CampusSearchMatcher.filterAndSort(pool, query, limit = 1).firstOrNull()
        return matched?.let { preferAmapCoordinates(it, pool) }
    }
}
