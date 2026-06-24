package com.example.guet_map.repository

import com.example.guet_map.BuildConfig
import com.example.guet_map.core.dao.LocationDao
import com.example.guet_map.core.entity.LocationEntity
import com.example.guet_map.model.Location
import com.example.guet_map.model.Resource
import com.example.guet_map.network.ApiService
import com.example.guet_map.util.CampusBuildingCatalog
import com.example.guet_map.util.CampusDormitoryCatalog
import com.example.guet_map.util.GuetCampusPoiLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor(
    private val apiService: ApiService,
    private val locationDao: LocationDao,
    private val campusPoiLoader: GuetCampusPoiLoader
) {

    // 修复崩溃3：Flow异常透明性违反
    // 使用单独的flow构建器，避免在catch块中emit
    fun getLocations(): Flow<Resource<List<Location>>> = flow {
        emit(Resource.Loading)

        try {
            val locations = loadRemoteLocations()
            locationDao.deleteAll()
            locationDao.insertAll(locations.map { it.toEntity() })
            emit(Resource.Success(locations))
        } catch (e: Exception) {
            // 不在catch块中直接emit，而是重新抛出，让外层的catch操作符处理
            throw LocationLoadException(e)
        }
    }.catch { e ->
        // 使用catch操作符处理所有异常
        when (e) {
            is LocationLoadException -> {
                val hasCached = try {
                    locationDao.count() > 0
                } catch (dbException: Exception) {
                    false
                }

                if (hasCached) {
                    try {
                        val cachedData = locationDao.getAllLocations().first().map { it.toDomain() }
                        emit(Resource.Success(cachedData))
                    } catch (dbException: Exception) {
                        emit(Resource.Error("数据加载失败: ${dbException.localizedMessage}"))
                    }
                } else {
                    val message = when (e.cause) {
                        is IOException -> "网络不可用: ${e.cause!!.localizedMessage}"
                        else -> "加载失败: ${e.cause?.localizedMessage ?: "未知错误"}"
                    }
                    emit(Resource.Error(message))
                }
            }
            else -> {
                emit(Resource.Error("未知错误: ${e.localizedMessage}"))
            }
        }
    }

    fun getLocationsByCategory(category: String): Flow<Resource<List<Location>>> = flow {
        emit(Resource.Loading)

        try {
            val cachedAll = locationDao.getAllLocations().first()
            if (cachedAll.isNotEmpty()) {
                val filtered = cachedAll.map { it.toDomain() }.filter { it.category == category }
                emit(Resource.Success(filtered))
                return@flow
            }

            val remote = loadRemoteLocations()
            locationDao.insertAll(remote.map { it.toEntity() })
            val filteredRemote = remote.filter { it.category == category }
            emit(Resource.Success(filteredRemote))
        } catch (e: Exception) {
            throw LocationLoadException(e)
        }
    }.catch { e ->
        when (e) {
            is LocationLoadException -> {
                try {
                    val cached = locationDao.getLocationsByCategory(category).first()
                    if (cached.isNotEmpty()) {
                        emit(Resource.Success(cached.map { it.toDomain() }))
                    } else {
                        emit(Resource.Error("加载失败: ${e.cause?.localizedMessage ?: "未知错误"}"))
                    }
                } catch (dbException: Exception) {
                    emit(Resource.Error("数据加载失败: ${dbException.localizedMessage}"))
                }
            }
            else -> {
                emit(Resource.Error("未知错误: ${e.localizedMessage}"))
            }
        }
    }

    fun observeCachedLocations(): Flow<List<Location>> =
        locationDao.getAllLocations().map { entities -> entities.map { it.toDomain() } }

    fun observeCachedLocationsByCategory(category: String): Flow<List<Location>> =
        locationDao.getLocationsByCategory(category).map { entities -> entities.map { it.toDomain() } }

    suspend fun getCachedLocationById(locationId: String): Location? =
        locationDao.getLocationById(locationId)?.toDomain()

    private suspend fun loadRemoteLocations(): List<Location> {
        if (!BuildConfig.USE_MOCK_API) {
            try {
                val fromApi = apiService.getLocations()
                if (fromApi.isNotEmpty()) return fromApi
            } catch (_: Exception) {}
        }
        val fromAmap = campusPoiLoader.loadGuetCampusLocations()
        if (fromAmap.isNotEmpty()) {
            // 使用 CampusBuildingCatalog 的准确坐标覆盖已知地点
            return CampusBuildingCatalog.mergeInto(fromAmap)
        }
        return CampusBuildingCatalog.toMockLocations()
    }

    private fun Location.toEntity() = LocationEntity(
        locationId = locationId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        category = category,
        rating = rating,
        openingHours = openingHours,
        imageUrl = imageUrl,
        hasGuide = hasGuide
    )

    private fun LocationEntity.toDomain() = Location(
        locationId = locationId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        category = category,
        rating = rating,
        openingHours = openingHours,
        imageUrl = imageUrl,
        hasGuide = hasGuide
    )
}

/**
 * 自定义异常类，用于包装原始异常，避免在flow的try-catch块中直接emit
 */
private class LocationLoadException(
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    constructor(cause: Throwable) : this(cause.localizedMessage, cause)
}