package com.example.guet_map.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.example.guet_map.model.GuideStep
import com.example.guet_map.model.Location
import com.example.guet_map.model.Resource
import com.example.guet_map.model.WalkRouteInfo
import com.example.guet_map.repository.LegacyFavoriteRepository
import com.example.guet_map.repository.GuideRepository
import com.example.guet_map.repository.LocationRepository
import com.example.guet_map.ui.map.state.ErrorType
import com.example.guet_map.ui.map.state.MapUiEvent
import com.example.guet_map.ui.map.state.MapUiState
import com.example.guet_map.util.CampusBuildingCatalog
import com.example.guet_map.util.CampusGeo
import com.example.guet_map.util.CampusLocationResolver
import com.example.guet_map.util.CampusSearchMatcher
import com.example.guet_map.util.CampusSearchQueryNormalizer
import com.example.guet_map.util.CampusWalkRoutePlanner
import com.example.guet_map.util.GuetCampusAmapSdkPoiLoader
import com.example.guet_map.data.UserPrefs
import com.example.guet_map.ui.map.model.MapThemeType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val guideRepository: GuideRepository,
    private val favoriteRepository: LegacyFavoriteRepository,
    private val socialFavoriteRepository: com.example.guet_map.module.social.data.repository.FavoriteRepository,
    private val walkRoutePlanner: CampusWalkRoutePlanner,
    private val userPrefs: UserPrefs,
    private val sdkPoiLoader: GuetCampusAmapSdkPoiLoader  // 添加SDK搜索
) : ViewModel() {

    // ============================================================
    // 统一状态管理
    // ============================================================

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Idle)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<MapUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    // ============================================================
    // 兼容旧代码的状态
    // ============================================================

    private val _walkRoute = MutableStateFlow<WalkRouteInfo?>(null)
    val walkRoute: StateFlow<WalkRouteInfo?> = _walkRoute.asStateFlow()

    private val _routeLoading = MutableStateFlow(false)
    val routeLoading: StateFlow<Boolean> = _routeLoading.asStateFlow()

    private val _routeError = MutableSharedFlow<String>()
    val routeError = _routeError.asSharedFlow()

    init {
        favoriteRepository.switchUser(userPrefs.userId)
        viewModelScope.launch {
            try {
                locationRepository.getLocations().first { it !is Resource.Loading }
                if (userPrefs.isLoggedIn) {
                    favoriteRepository.syncFromServer()
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "map_privacy"
        private const val KEY_PRIVACY_AGREED = "privacy_agreed"
    }

    // ── 隐私 ─────────────────────────────────────────────────

    val isPrivacyAgreed: Boolean
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_PRIVACY_AGREED, false)
        }

    fun setPrivacyAgreed() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRIVACY_AGREED, true)
            .apply()
    }

    // ── 地图 ─────────────────────────────────────────────────

    var aMap: AMap? = null

    // ── 地点数据 ─────────────────────────────────────────────

    val cachedLocations: StateFlow<List<Location>> = locationRepository
        .observeCachedLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _locationsResource = MutableStateFlow<Resource<List<Location>>>(Resource.Loading)
    val locationsResource: StateFlow<Resource<List<Location>>> = _locationsResource.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    // ── 图文指引 ─────────────────────────────────────────────

    private val _guideStepsResource = MutableStateFlow<Resource<List<GuideStep>>>(Resource.Loading)
    val guideStepsResource: StateFlow<Resource<List<GuideStep>>> = _guideStepsResource.asStateFlow()

    private val _localGuideSteps = MutableStateFlow<List<GuideStep>>(emptyList())
    val localGuideSteps: StateFlow<List<GuideStep>> = _localGuideSteps.asStateFlow()

    private val _selectedLocation = MutableStateFlow<Location?>(null)
    val selectedLocation: StateFlow<Location?> = _selectedLocation.asStateFlow()

    // 地图主题
    private val _currentTheme = MutableStateFlow(MapThemeType.fromValue(userPrefs.mapTheme))
    val currentTheme: StateFlow<MapThemeType> = _currentTheme.asStateFlow()

    val favoriteIds: StateFlow<Set<String>> = favoriteRepository
        .observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // ── 搜索 ─────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // SDK实时搜索结果（用于获取高德精确坐标）
    private val _sdkSearchResults = MutableStateFlow<List<Location>>(emptyList())
    val sdkSearchResults: StateFlow<List<Location>> = _sdkSearchResults.asStateFlow()

    val searchResults: StateFlow<List<Location>> = _searchQuery
        .combine(cachedLocations) { query, locations ->
            CampusSearchMatcher.filterAndSort(
                locations,
                query,
                limit = CampusSearchQueryNormalizer.MAX_SEARCH_RESULTS
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 位置详情状态 ──────────────────────────────────────

    private val _locationDetailState = MutableStateFlow<LocationDetailState?>(null)
    val locationDetailState: StateFlow<LocationDetailState?> = _locationDetailState.asStateFlow()

    data class LocationDetailState(
        val location: Location,
        val isFavorite: Boolean,
        val guideSteps: List<GuideStep> = emptyList(),
        val isGuideLoading: Boolean = false,
        val errorMessage: String? = null
    )

    // ============================================================
    // 公开方法
    // ============================================================

    private fun updateState(state: MapUiState) {
        _uiState.value = state
    }

    private fun sendEvent(event: MapUiEvent) {
        viewModelScope.launch {
            _uiEvent.emit(event)
        }
    }

    fun showError(message: String, type: ErrorType = ErrorType.UNKNOWN) {
        updateState(MapUiState.Error(message, type))
    }

    fun clearError() {
        val currentState = _uiState.value
        if (currentState is MapUiState.Error) {
            updateState(MapUiState.Idle)
        }
    }

    // ============================================================
    // 搜索相关
    // ============================================================

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        // 实时SDK搜索获取精确坐标
        if (query.length >= 2) {
            performSdkSearch(query)
        }
    }

    /**
     * 执行高德SDK搜索，获取实时精确位置
     */
    private fun performSdkSearch(query: String) {
        viewModelScope.launch {
            try {
                val results = sdkPoiLoader.searchLocation(query)
                if (results.isNotEmpty()) {
                    _sdkSearchResults.value = results
                }
            } catch (_: Exception) {
                // 静默失败，使用本地缓存
            }
        }
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
        _sdkSearchResults.value = emptyList()
    }

    fun submitSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        _searchQuery.value = q

        // 优先使用SDK搜索结果（高德精确坐标），但用已知坐标校正
        val sdkResults = _sdkSearchResults.value
        if (sdkResults.isNotEmpty()) {
            // 对SDK结果进行坐标校正
            val correctedResults = correctLocationsWithKnownCoords(sdkResults)
            val matched = CampusSearchMatcher.filterAndSort(correctedResults, q, limit = 5)
            if (matched.isNotEmpty()) {
                pickFromSearch(matched.first())
                return
            }
        }

        updateState(MapUiState.SearchResult(q, searchResults.value))

        val exactMatch = resolveSearchLocation(q)
        val bestVisibleMatch = CampusSearchMatcher.resolveBest(searchResults.value, q)
        val firstVisibleMatch = searchResults.value.firstOrNull()
        val match = exactMatch ?: bestVisibleMatch ?: firstVisibleMatch

        if (match != null) {
            pickFromSearch(match)
        }
    }

    /**
     * 使用 CampusBuildingCatalog 的已知准确坐标校正地点列表
     */
    private fun correctLocationsWithKnownCoords(locations: List<Location>): List<Location> {
        return locations.map { loc ->
            // 查找是否有匹配的已知地点
            val entry = CampusBuildingCatalog.findEntryByAlias(loc.name)
            if (entry != null && entry.matchesName(loc.name)) {
                // 使用已知坐标
                loc.copy(
                    latitude = entry.fallbackLat,
                    longitude = entry.fallbackLng,
                    locationId = entry.locationId
                )
            } else {
                loc
            }
        }
    }

    fun pickFromSearch(location: Location) {
        val target = cachedLocations.value.find { it.locationId == location.locationId } ?: location
        _highlightedLocationId.value = target.locationId
        selectLocation(target)
        clearWalkRoute()
        viewModelScope.launch {
            _uiEvent.emit(MapUiEvent.FocusMap(target.latitude, target.longitude))
            _uiEvent.emit(MapUiEvent.DismissSearchInput)
        }
    }

    fun resolveSearchLocation(query: String): Location? =
        CampusLocationResolver.resolveForQuery(query, cachedLocations.value)

    fun focusOnLocation(location: Location) {
        // 使用 CampusLocationResolver 校正坐标
        val target = CampusLocationResolver.preferAmapCoordinates(
            cachedLocations.value.find { it.locationId == location.locationId }
                ?: location,
            cachedLocations.value
        )
        _highlightedLocationId.value = target.locationId
        selectLocation(target)
        viewModelScope.launch {
            _uiEvent.emit(MapUiEvent.FocusMap(target.latitude, target.longitude))
        }
    }

    fun updateMapMarkersFromCache() {
        addMarkersForLocations(cachedLocations.value)
    }

    // ============================================================
    // 旧版兼容方法
    // ============================================================

    fun loadLocations() {
        viewModelScope.launch {
            updateState(MapUiState.Loading)
            locationRepository.getLocations().collect { resource ->
                _locationsResource.value = resource
                when (resource) {
                    is Resource.Loading -> updateState(MapUiState.Loading)
                    is Resource.Success -> {
                        updateState(
                            MapUiState.LocationsLoaded(
                                locations = resource.data,
                                filteredLocations = resource.data,
                                selectedCategory = _selectedCategory.value
                            )
                        )
                        addMarkersForLocations(resource.data)
                    }
                    is Resource.Error -> {
                        updateState(
                            MapUiState.Error(
                                message = resource.message,
                                type = ErrorType.LOAD_DATA_FAILED
                            )
                        )
                    }
                }
            }
        }
    }

    fun filterByCategory(category: String?) {
        _selectedCategory.value = category
        viewModelScope.launch {
            if (category != null) {
                locationRepository.getLocationsByCategory(category).collect { resource ->
                    _locationsResource.value = resource
                    if (resource is Resource.Success) {
                        updateState(
                            MapUiState.LocationsLoaded(
                                locations = cachedLocations.value,
                                filteredLocations = resource.data,
                                selectedCategory = category
                            )
                        )
                        addMarkersForLocations(resource.data)
                    } else if (resource is Resource.Error) {
                        updateState(
                            MapUiState.Error(
                                message = resource.message,
                                type = ErrorType.LOAD_DATA_FAILED
                            )
                        )
                    }
                }
            } else {
                loadLocations()
            }
        }
    }

    fun loadGuideSteps(locationId: String) {
        viewModelScope.launch {
            _locationDetailState.value = _locationDetailState.value?.copy(isGuideLoading = true)
            guideRepository.getGuideSteps(locationId).collect { resource ->
                when (resource) {
                    is Resource.Loading -> {
                        _locationDetailState.value = _locationDetailState.value?.copy(isGuideLoading = true)
                    }
                    is Resource.Success -> {
                        _locationDetailState.value = _locationDetailState.value?.copy(
                            guideSteps = resource.data,
                            isGuideLoading = false
                        )
                        _guideStepsResource.value = resource
                    }
                    is Resource.Error -> {
                        _locationDetailState.value = _locationDetailState.value?.copy(
                            isGuideLoading = false,
                            errorMessage = resource.message
                        )
                        _guideStepsResource.value = resource
                    }
                }
            }
        }
    }

    fun selectLocation(location: Location) {
        _selectedLocation.value = location
        val isFav = location.locationId in favoriteIds.value

        _locationDetailState.value = LocationDetailState(
            location = location,
            isFavorite = isFav
        )

        observeLocalGuideSteps(location.locationId)
        if (location.hasGuide) {
            loadGuideSteps(location.locationId)
        }

        viewModelScope.launch {
            _uiEvent.emit(MapUiEvent.ShowLocationSheet(location.locationId))
        }
    }

    private fun observeLocalGuideSteps(locationId: String) {
        viewModelScope.launch {
            guideRepository.observeCachedGuideSteps(locationId).collect { steps ->
                _localGuideSteps.value = steps
            }
        }
    }

    fun selectLocationById(locationId: String) {
        viewModelScope.launch {
            resolveAndSelectLocation(locationId)
        }
    }

    suspend fun resolveAndSelectLocation(locationId: String): Location? {
        val resolved = resolveLocation(locationId)
        resolved?.let { selectLocation(it) }
        return resolved
    }

    private suspend fun resolveLocation(locationId: String): Location? {
        // 精确 ID 匹配
        cachedLocations.value.find { it.locationId == locationId }?.let { return it }
        locationRepository.getCachedLocationById(locationId)?.let { return it }
        // 确保数据已加载
        if (cachedLocations.value.isEmpty()) {
            locationRepository.getLocations().first { it !is Resource.Loading }
        }
        cachedLocations.value.find { it.locationId == locationId }?.let { return it }
        // 按名称模糊匹配（AI 导航传入的是"五教"等名称而非 ID）
        val byId = locationRepository.getCachedLocationById(locationId)
            ?: favoriteRepository.enrichFavoriteFromCache(locationId)
        if (byId != null) return byId
        return com.example.guet_map.util.CampusSearchMatcher.resolveBest(
            cachedLocations.value, locationId
        )
    }

    suspend fun toggleFavorite(location: Location): Boolean {
        val result = favoriteRepository.toggleFavorite(location)
        
        // 同时保存到社交模块的收藏系统
        if (result) {
            viewModelScope.launch {
                try {
                    val userId = userPrefs.userId.ifBlank { com.example.guet_map.data.UserPrefs.GUEST_USER_ID }
                    socialFavoriteRepository.addFavorite(
                        userId = userId,
                        locationId = location.locationId,
                        locationName = location.name,
                        locationCategory = location.category,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } catch (_: Exception) {
                    // 忽略错误，保持主要收藏系统正常工作
                }
            }
        } else {
            // 取消收藏时也从社交模块删除
            viewModelScope.launch {
                try {
                    val userId = userPrefs.userId.ifBlank { com.example.guet_map.data.UserPrefs.GUEST_USER_ID }
                    socialFavoriteRepository.removeFavorite(userId, location.locationId)
                } catch (_: Exception) {
                    // 忽略错误
                }
            }
        }
        
        return result
    }

    fun planWalkRouteTo(destination: Location, start: LatLng) {
        // 使用 CampusLocationResolver 校正坐标，确保使用 CampusBuildingCatalog 的准确坐标
        val dest = CampusLocationResolver.preferAmapCoordinates(
            cachedLocations.value.find { it.locationId == destination.locationId }
                ?: destination,
            cachedLocations.value
        )
        _routeLoading.value = true
        updateState(
            MapUiState.Navigating(
                target = dest,
                isLoading = true
            )
        )
        walkRoutePlanner.planWalkRoute(
            start = start,
            end = LatLng(dest.latitude, dest.longitude),
            targetName = dest.name,
            onSuccess = { route ->
                _walkRoute.value = route
                _routeLoading.value = false
                updateState(
                    MapUiState.Navigating(
                        target = dest,
                        route = route,
                        isLoading = false
                    )
                )
            },
            onError = { message ->
                // 即使出错也生成兜底路线，避免 UI 卡在"正在规划路线"
                val fallback = WalkRouteInfo(
                    targetName = dest.name,
                    distanceMeters = 0,
                    durationSeconds = 0,
                    polyline = listOf(start, LatLng(dest.latitude, dest.longitude))
                )
                _walkRoute.value = fallback
                _routeLoading.value = false
                updateState(
                    MapUiState.Navigating(
                        target = dest,
                        route = fallback,
                        isLoading = false,
                        errorMessage = message
                    )
                )
                viewModelScope.launch {
                    _uiEvent.emit(MapUiEvent.ShowToast(message))
                }
            }
        )
    }

    fun clearWalkRoute() {
        _walkRoute.value = null
        if (_uiState.value is MapUiState.Navigating) {
            updateState(MapUiState.Idle)
        }
    }

    // 地图主题切换
    fun changeMapTheme(theme: MapThemeType) {
        _currentTheme.value = theme
        userPrefs.mapTheme = theme.value
        viewModelScope.launch {
            _uiEvent.emit(MapUiEvent.MapThemeChanged(theme))
        }
    }

    fun campusCenterLatLng(): LatLng = LatLng(CampusGeo.CENTER_LAT, CampusGeo.CENTER_LNG)

    // ── Marker 管理 ──────────────────────────────────────────

    private var addedMarkers: List<com.amap.api.maps.model.Marker> = emptyList()
    private var highlightMarker: com.amap.api.maps.model.Marker? = null

    private val _highlightedLocationId = MutableStateFlow<String?>(null)
    val highlightedLocationId: StateFlow<String?> = _highlightedLocationId.asStateFlow()

    private fun addMarkersForLocations(locations: List<Location>) {
        val map = aMap ?: return
        addedMarkers.forEach { it.remove() }
        highlightMarker?.remove()
        highlightMarker = null

        addedMarkers = locations.map { loc ->
            // 根据地点分类获取对应的Marker颜色
            val markerHue = com.example.guet_map.util.MarkerColorHelper.getMarkerHueByCategory(loc.category)
            
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(loc.latitude, loc.longitude))
                    .title(loc.name)
                    .snippet(loc.category)
                    .icon(BitmapDescriptorFactory.defaultMarker(markerHue))
            )
            marker.`object` = loc
            marker
        }

        _highlightedLocationId.value?.let { id ->
            locations.find { it.locationId == id }?.let { showHighlightMarker(it) }
        }
    }

    fun showHighlightMarker(location: Location) {
        val map = aMap ?: return
        highlightMarker?.remove()
        highlightMarker = map.addMarker(
            MarkerOptions()
                .position(LatLng(location.latitude, location.longitude))
                .title(location.name)
                .snippet("已选中")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .zIndex(2f)
        )
        highlightMarker?.`object` = location
    }

    override fun onCleared() {
        super.onCleared()
        aMap = null
    }
}
