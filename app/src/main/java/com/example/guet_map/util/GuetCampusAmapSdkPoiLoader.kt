package com.example.guet_map.util

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.example.guet_map.model.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 直接使用高德 Search SDK（PoiSearch）返回的 POI 原始数据。
 * 后续通过 CampusBuildingCatalog.mergeInto() 进行坐标校正，确保已知地点使用准确坐标。
 */
@Singleton
class GuetCampusAmapSdkPoiLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val campusBounds = CampusBounds(
        minLat = 25.3010,
        maxLat = 25.3145,
        minLng = 110.4110,
        maxLng = 110.4245
    )

    suspend fun loadGuetCampusLocations(): List<Location> = withContext(Dispatchers.IO) {
        val merged = linkedMapOf<String, Location>()

        for (keyword in listOf(
            "桂林电子科技大学花江校区",
            "桂电花江校区",
            "桂林电子科技大学"
        )) {
            loadKeywordPages(keyword, merged)
            delay(120)
        }

        for (keyword in CampusBuildingCatalog.amapTextKeywords()) {
            loadKeywordPages(keyword, merged)
            delay(100)
        }

        for (keyword in CampusDormitoryCatalog.amapKeywords()) {
            loadKeywordPages(keyword, merged)
            delay(100)
        }

        for (aroundKw in listOf(
            "教学楼", "宿舍", "食堂", "图书馆", "校门", "学院", "体育馆", "咖啡", "超市"
        )) {
            loadKeywordPages(aroundKw, merged, campusScoped = true)
            delay(120)
        }

        merged.values.sortedBy { it.name }
    }

    private suspend fun loadKeywordPages(
        keyword: String,
        merged: LinkedHashMap<String, Location>,
        campusScoped: Boolean = false
    ) {
        var page = 1
        repeat(MAX_PAGES) {
            val batch = searchKeyword(keyword, page, campusScoped)
            if (batch.isEmpty()) return
            batch.forEach { loc -> merged[loc.dedupeKey()] = loc }
            if (batch.size < PAGE_SIZE) return
            page++
            delay(100)
        }
    }

    private suspend fun searchKeyword(
        keyword: String,
        pageNum: Int,
        campusScoped: Boolean
    ): List<Location> = suspendCancellableCoroutine { cont ->
        try {
            val query = PoiSearch.Query(keyword, "", "桂林")
            query.cityLimit = true
            query.pageSize = PAGE_SIZE
            query.pageNum = pageNum

            val poiSearch = PoiSearch(context, query)
            val center = LatLonPoint(CampusGeo.CENTER_LAT, CampusGeo.CENTER_LNG)
            poiSearch.bound = PoiSearch.SearchBound(center, AROUND_RADIUS_METERS.toInt(), true)

            poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                    if (!cont.isActive) return
                    if (rCode != 1000 || result == null) {
                        cont.resume(emptyList())
                        return
                    }
                    val list = result.pois.orEmpty().mapNotNull { poi ->
                        poi.toLocationOrNull(requireCampusHint = campusScoped)
                    }
                    cont.resume(list)
                }

                override fun onPoiItemSearched(item: PoiItem?, rCode: Int) = Unit
            })
            poiSearch.searchPOIAsyn()
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    private fun PoiItem.toLocationOrNull(requireCampusHint: Boolean): Location? {
        val lat = latLonPoint?.latitude ?: return null
        val lng = latLonPoint?.longitude ?: return null
        if (!campusBounds.contains(lat, lng)) return null

        val title = title.orEmpty().ifBlank { return null }
        val address = snippet.orEmpty()
        if (requireCampusHint && !looksLikeCampusPoi(title, address)) return null

        return Location(
            locationId = poiId?.takeIf { it.isNotBlank() } ?: "poi_${title.hashCode()}_${lat.toBits()}",
            name = title,
            latitude = lat,
            longitude = lng,
            category = mapCategory(title, typeDes),
            rating = 4.0f,
            openingHours = "",
            imageUrl = "",
            hasGuide = false
        )
    }

    private fun looksLikeCampusPoi(name: String, address: String): Boolean {
        val text = name + address
        return text.contains("花江") || text.contains("桂电") ||
            text.contains("电子科技大学") || text.contains("桂林电子")
    }

    private fun mapCategory(name: String, typeDes: String?): String {
        val text = name + (typeDes.orEmpty())
        return when {
            text.contains("食堂") || text.contains("餐厅") -> "食堂"
            text.contains("图书馆") -> "图书馆"
            text.contains("宿舍") || text.contains("公寓") || text.contains("苑") -> "宿舍"
            text.contains("教学") -> "教室"
            text.contains("体育") || text.contains("运动") || text.contains("操场") -> "运动场"
            text.contains("门") && text.contains("校") -> "校门"
            text.contains("咖啡") -> "咖啡"
            text.contains("超市") || text.contains("商店") -> "商店"
            else -> "其他"
        }
    }

    private fun Location.dedupeKey(): String =
        locationId.ifBlank { "${name.trim()}@${"%.5f".format(latitude)},${"%.5f".format(longitude)}" }

    /**
     * 精确搜索特定地点，优先使用高德SDK返回的精确坐标
     * @param query 搜索关键词（如"第五教学楼"）
     * @return 搜索结果列表，按相关性排序
     */
    suspend fun searchLocation(query: String): List<Location> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. 先尝试精确搜索（不使用周边限制）
        val exactResults = searchExactKeyword(query)

        // 2. 再尝试校园内搜索
        val campusResults = searchKeyword(query, 1, campusScoped = true)

        // 合并结果，去重
        val merged = linkedMapOf<String, Location>()
        exactResults.forEach { merged[it.dedupeKey()] = it }
        campusResults.forEach { merged[it.dedupeKey()] = it }

        merged.values.toList()
    }

    /**
     * 精确搜索（无位置限制，返回桂林市内结果）
     */
    private suspend fun searchExactKeyword(keyword: String): List<Location> =
        suspendCancellableCoroutine { cont ->
            try {
                val query = PoiSearch.Query(keyword, "", "桂林")
                query.cityLimit = true
                query.pageSize = 10
                query.pageNum = 1

                val poiSearch = PoiSearch(context, query)
                // 不设置bound，进行全城搜索

                poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                    override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                        if (!cont.isActive) return
                        if (rCode != 1000 || result == null) {
                            cont.resume(emptyList())
                            return
                        }
                        val list = result.pois.orEmpty().mapNotNull { poi ->
                            poi.toLocationOrNull(exactMatch = true)
                        }
                        cont.resume(list)
                    }

                    override fun onPoiItemSearched(item: PoiItem?, rCode: Int) = Unit
                })
                poiSearch.searchPOIAsyn()
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    /**
     * 扩展 toLocationOrNull 支持精确匹配模式
     */
    private fun PoiItem.toLocationOrNull(requireCampusHint: Boolean = false, exactMatch: Boolean = false): Location? {
        val lat = latLonPoint?.latitude ?: return null
        val lng = latLonPoint?.longitude ?: return null

        val title = title.orEmpty().ifBlank { return null }
        val address = snippet.orEmpty()

        // 精确匹配模式：只在花江校区范围内
        if (exactMatch) {
            if (!campusBounds.contains(lat, lng)) return null
        } else {
            if (!campusBounds.contains(lat, lng)) return null
            if (requireCampusHint && !looksLikeCampusPoi(title, address)) return null
        }

        return Location(
            locationId = poiId?.takeIf { it.isNotBlank() } ?: "poi_${title.hashCode()}_${lat.toBits()}",
            name = title,
            latitude = lat,
            longitude = lng,
            category = mapCategory(title, typeDes),
            rating = 4.0f,
            openingHours = "",
            imageUrl = "",
            hasGuide = false
        )
    }

    private data class CampusBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    ) {
        fun contains(lat: Double, lng: Double): Boolean =
            lat in minLat..maxLat && lng in minLng..maxLng
    }

    companion object {
        private const val PAGE_SIZE = 25
        private const val MAX_PAGES = 5
        private const val AROUND_RADIUS_METERS = 2800
    }
}
