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
 * 高德地图搜索客户端
 * 使用高德 Web API 进行 POI 搜索
 */
class AMapSearchClient(private val context: Context) {

    private val TAG = "AMapSearchClient"
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

    var onPoiSearchResult: ((List<PoiSearchResult>) -> Unit)? = null
    var onSearchError: ((String) -> Unit)? = null

    /**
     * 搜索关键词
     * @param keyword 搜索关键词
     * @param city 搜索城市
     */
    fun searchKeyword(keyword: String, city: String = "桂林") {
        scope.launch {
            try {
                // 构建高德地图 POI 搜索 URL
                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val encodedCity = URLEncoder.encode(city, "UTF-8")
                val url = "https://restapi.amap.com/v3/place/text?keywords=$encodedKeyword&city=$encodedCity&output=json&key=$apiKey"

                Log.d(TAG, "搜索 URL: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "响应: $body")

                    if (body != null) {
                        val searchResponse = gson.fromJson(body, AmapSearchResponse::class.java)

                        if (searchResponse.status == "1" && searchResponse.pois != null) {
                            val results = searchResponse.pois.map { poi ->
                                // 高德返回 location 格式为 "经度,纬度"
                                val locationStr = poi.location ?: ""
                                val parts = locationStr.split(",")
                                val longitude = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                                val latitude = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0

                                PoiSearchResult(
                                    id = poi.id ?: "",
                                    name = poi.name ?: "",
                                    address = poi.address ?: "",
                                    latitude = latitude,
                                    longitude = longitude,
                                    province = poi.pname ?: "",
                                    city = poi.cityname ?: "",
                                    district = poi.adname ?: ""
                                )
                            }

                            mainHandler.post {
                                onPoiSearchResult?.invoke(results)
                            }
                        } else {
                            mainHandler.post {
                                onSearchError?.invoke(searchResponse.info ?: "搜索失败")
                            }
                        }
                    } else {
                        mainHandler.post {
                            onSearchError?.invoke("网络响应为空")
                        }
                    }
                } else {
                    Log.e(TAG, "HTTP 错误: ${response.code}")
                    mainHandler.post {
                        onSearchError?.invoke("网络错误: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "搜索异常: ${e.message}", e)
                mainHandler.post {
                    onSearchError?.invoke("搜索失败: ${e.message}")
                }
            }
        }
    }

    fun destroy() {
        // 清理资源
    }
}

/**
 * 高德搜索响应
 */
data class AmapSearchResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("info") val info: String?,
    @SerializedName("count") val count: String?,
    @SerializedName("pois") val pois: List<AmapPoi>?
)

data class AmapPoi(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("location") val location: String?,
    @SerializedName("address") val address: String?,
    @SerializedName("pname") val pname: String?,
    @SerializedName("cityname") val cityname: String?,
    @SerializedName("adname") val adname: String?,
    @SerializedName("type") val type: String?
)

/**
 * 高德搜索结果数据类
 */
data class PoiSearchResult(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val province: String,
    val city: String,
    val district: String
) {
    val displayAddress: String
        get() = buildString {
            if (province.isNotEmpty() && province != city) append(province)
            if (city.isNotEmpty()) append(city)
            if (district.isNotEmpty()) append(district)
            if (address.isNotEmpty() && address != "[]") append(" ").append(address)
        }.trim()
}
