package com.example.guet_map.ui.map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import com.amap.api.maps.AMap
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.core.ServiceSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.guet_map.R
import com.example.guet_map.databinding.FragmentMapBinding
import com.example.guet_map.module.ai.ui.floating.FloatingWindowService
import com.example.guet_map.model.Location
import com.example.guet_map.model.Resource
import com.example.guet_map.ui.MainNavViewModel
import com.example.guet_map.ui.common.AnimationUtils
import com.example.guet_map.ui.map.component.FilterTagAdapter
import com.example.guet_map.ui.map.component.LocationBottomSheetComponent
import com.example.guet_map.ui.map.component.LocationDetailCardComponent
import com.example.guet_map.ui.map.component.NavigationPanelComponent
import com.example.guet_map.ui.map.component.SearchBarComponent
import com.example.guet_map.ui.map.state.ErrorType
import com.example.guet_map.ui.map.state.MapUiEvent
import com.example.guet_map.ui.map.state.MapUiState
import com.example.guet_map.util.CampusGeo
import com.example.guet_map.util.CoordinateUtil
import com.example.guet_map.module.social.domain.usecase.GetWeatherUseCase
import com.example.guet_map.module.social.data.model.Weather
import com.example.guet_map.module.social.data.model.WeatherType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MapFragment : Fragment() {

    @Inject lateinit var authRepository: com.example.guet_map.repository.AuthRepository
    @Inject lateinit var userPrefs: com.example.guet_map.data.UserPrefs
    @Inject lateinit var getWeatherUseCase: GetWeatherUseCase

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()
    private val mainNavViewModel: MainNavViewModel by activityViewModels()

    private var aMap: AMap? = null
    private var mapViewCreated = false

    private lateinit var locationManager: LocationManager
    private var myLocationMarker: com.amap.api.maps.model.Marker? = null
    private var latestLocation: android.location.Location? = null
    private var latestGcjLatLng: LatLng? = null
    private var hasAutoCenteredOnLocation = false
    private var pendingCenterOnLocation = false
    private var routePolyline: com.amap.api.maps.model.Polyline? = null

    private lateinit var filterAdapter: FilterTagAdapter
    private lateinit var searchBarComponent: SearchBarComponent
    private lateinit var navigationPanelComponent: NavigationPanelComponent
    private lateinit var locationDetailCardComponent: LocationDetailCardComponent
    private lateinit var bottomSheetComponent: LocationBottomSheetComponent

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            startSystemLocation()
        }
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchVoiceRecognizer() else Toast.makeText(context, "需要麦克风权限才能使用语音搜索", Toast.LENGTH_SHORT).show()
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = matches?.firstOrNull()
        if (!text.isNullOrBlank()) {
            binding.etSearch.setText(text)
            viewModel.submitSearch(text)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        locationManager = requireContext().getSystemService(LocationManager::class.java)

        initComponents()
        setupViews()
        setupClickListeners()
        observeViewModel()
        loadWeatherBanner()

        if (viewModel.isPrivacyAgreed) {
            initMapView(savedInstanceState)
        } else {
            showPrivacyDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mapViewCreated) {
            binding.mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (mapViewCreated) {
            binding.mapView.onPause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationServices()
        if (mapViewCreated) {
            binding.mapView.onDestroy()
            mapViewCreated = false
        }
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            if (mapViewCreated && _binding != null) {
                binding.mapView.onSaveInstanceState(outState)
            }
        } catch (_: Exception) {
        }
    }

    private fun initComponents() {
        searchBarComponent = SearchBarComponent(
            context = requireContext(),
            binding = binding,
            onQueryChanged = { query -> viewModel.setSearchQuery(query) },
            onSearchSubmit = { query -> viewModel.submitSearch(query) },
            onLocationPicked = { location -> viewModel.pickFromSearch(location) }
        )

        navigationPanelComponent = NavigationPanelComponent(
            context = requireContext(),
            parent = binding.mapContainer
        ).apply {
            onCloseNavigation = { viewModel.clearWalkRoute() }
            onStartNavigation = { location -> openExternalNavigation(location) }
        }

        locationDetailCardComponent = LocationDetailCardComponent(
            context = requireContext(),
            parent = binding.mapContainer
        ).apply {
            onNavigate = { location -> startWalkNavigation(location) }
            onFavorite = { location -> viewLifecycleOwner.lifecycleScope.launch { toggleFavorite(location) } }
        }

        bottomSheetComponent = LocationBottomSheetComponent(binding).apply {
            onNavigate = { location -> startWalkNavigation(location) }
            onFavorite = { location -> viewLifecycleOwner.lifecycleScope.launch { toggleFavorite(location) } }
            onShare = { location -> shareLocation(location) }
            onContributeGuide = { mainNavViewModel.requestTab(R.id.nav_contribute) }
        }
    }

    private fun setupViews() {
        val filterTags = listOf("食堂", "教室", "咖啡", "图书馆", "宿舍", "校门", "商店", "运动场")
        filterAdapter = FilterTagAdapter(filterTags) { tag ->
            viewModel.filterByCategory(tag)
        }
        binding.rvFilterTags.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = filterAdapter
        }
    }

    private fun setupClickListeners() {
        binding.fabMyLocation.setOnClickListener {
            centerOnMyLocation()
        }

        binding.fabAiChat.setOnClickListener {
            requestOverlayPermissionAndStartFloatingWindow()
        }

        binding.ivMenu.setOnClickListener {
            if (authRepository.isLoggedIn) {
                mainNavViewModel.requestTab(R.id.nav_profile)
            } else {
                mainNavViewModel.requestTab(R.id.nav_login)
            }
        }
        binding.ivAvatar.setOnClickListener {
            if (authRepository.isLoggedIn) {
                mainNavViewModel.requestTab(R.id.nav_profile)
            } else {
                mainNavViewModel.requestTab(R.id.nav_login)
            }
        }

        searchBarComponent.setup()

        binding.ivVoice.setOnClickListener {
            startVoiceSearch()
        }

        binding.cardWeatherSafety.setOnClickListener {
            mainNavViewModel.requestWeatherDetail()
        }
    }

    private fun startVoiceSearch() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchVoiceRecognizer()
    }

    private fun launchVoiceRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要搜索的地点")
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.uiState.collect { state ->
                        handleUiState(state)
                    }
                }

                launch {
                    viewModel.uiEvent.collect { event ->
                        handleUiEvent(event)
                    }
                }

                launch {
                    viewModel.cachedLocations.collectLatest { locations ->
                        if (locations.isNotEmpty()) {
                            viewModel.updateMapMarkersFromCache()
                        }
                    }
                }

                launch {
                    viewModel.searchResults.collectLatest { results ->
                        searchBarComponent.updateSearchResults(results)
                    }
                }

                // 监听SDK实时搜索结果，用于显示高德精确位置
                launch {
                    viewModel.sdkSearchResults.collectLatest { sdkResults ->
                        if (sdkResults.isNotEmpty()) {
                            // 优先使用SDK搜索结果（包含高德精确坐标）
                            searchBarComponent.updateSearchResults(sdkResults)
                        }
                    }
                }

                launch {
                    viewModel.selectedLocation.collect { location ->
                        location?.let {
                            bottomSheetComponent.show(it, it.locationId in viewModel.favoriteIds.value)
                        }
                    }
                }

                launch {
                    viewModel.favoriteIds.collectLatest { ids ->
                        bottomSheetComponent.updateFavoriteState(
                            viewModel.selectedLocation.value?.locationId in ids
                        )
                    }
                }

                launch {
                    viewModel.guideStepsResource.collect { resource ->
                        when (resource) {
                            is Resource.Loading -> bottomSheetComponent.showGuideLoading()
                            is Resource.Success -> bottomSheetComponent.showGuideSteps(resource.data)
                            is Resource.Error -> bottomSheetComponent.showGuideError(resource.message)
                        }
                    }
                }

                launch {
                    viewModel.walkRoute.collect { route ->
                        if (route != null) {
                            showWalkRouteOnMap(route)
                        } else {
                            clearWalkRouteFromMap()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavViewModel.pendingLocationId.collectLatest { locationId ->
                    locationId ?: return@collectLatest
                    val id = mainNavViewModel.consumePendingLocation() ?: return@collectLatest
                    openLocationOnMap(id)
                }
            }
        }
    }

    private fun handleUiState(state: MapUiState) {
        when (state) {
            is MapUiState.Idle -> {}
            is MapUiState.Loading -> {}
            is MapUiState.LocationsLoaded -> {}
            is MapUiState.SearchResult -> {}
            is MapUiState.LocationDetail -> {}
            is MapUiState.Navigating -> {
                // 用 post 确保 cardSearch 已完成布局（从探索页跳转时可能尚未 measure）
                binding.cardSearch.post {
                    val topMargin = binding.cardSearch.bottom + (8 * resources.displayMetrics.density).toInt()
                    val locText = latestLocation?.let { loc ->
                        "当前位置: %.5f, %.5f (精度%.0fm)".format(loc.latitude, loc.longitude, loc.accuracy)
                    }
                    if (state.isLoading) {
                        navigationPanelComponent.showLoading(topMargin)
                    } else if (state.route != null) {
                        navigationPanelComponent.show(state.target, state.route, topMargin, locText)
                    }
                }
            }
            is MapUiState.Error -> {
                handleError(state.message, state.type)
            }
        }
    }

    private fun handleUiEvent(event: MapUiEvent) {
        when (event) {
            is MapUiEvent.ShowToast -> {
                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
            }
            is MapUiEvent.ShowLocationSheet -> {}
            is MapUiEvent.HideLocationSheet -> {
                bottomSheetComponent.hide()
            }
            is MapUiEvent.FocusMap -> {
                focusMap(event.latitude, event.longitude, event.zoom)
            }
            is MapUiEvent.DismissSearchInput -> {
                searchBarComponent.dismissSearchResults()
            }
            is MapUiEvent.ShowLoading -> {}
            is MapUiEvent.HideLoading -> {}
            is MapUiEvent.NavigateToExternal -> {
                openExternalNavigationByCoords(event.latitude, event.longitude, event.name)
            }
            is MapUiEvent.RequestLocationPermission -> {
                requestLocationPermission()
            }
            is MapUiEvent.ShowLocationAccuracy -> {}
        }
    }

    private fun handleError(message: String, type: ErrorType) {
        val contextMessage = when (type) {
            ErrorType.NETWORK_ERROR -> getString(R.string.error_network)
            ErrorType.LOCATION_PERMISSION_DENIED -> getString(R.string.error_location_permission)
            ErrorType.LOCATION_FAILED -> getString(R.string.error_location)
            ErrorType.ROUTE_PLAN_FAILED -> getString(R.string.error_route_planning)
            ErrorType.LOAD_DATA_FAILED -> getString(R.string.error_load_data)
            ErrorType.UNKNOWN -> message
        }
        Toast.makeText(requireContext(), contextMessage, Toast.LENGTH_SHORT).show()
        viewModel.clearError()
    }

    private fun initMapView(savedInstanceState: Bundle?) {
        if (mapViewCreated) return

        binding.mapView.onCreate(savedInstanceState)
        mapViewCreated = true

        binding.mapView.map?.let { map ->
            aMap = map
            viewModel.aMap = map
            configureMap(map)
            setupMarkerClickListener(map)
            initNavigationClient()
            viewModel.loadLocations()
            requestLocationPermissionIfNeeded()
        }
    }

    private fun configureMap(map: AMap) {
        map.isMyLocationEnabled = false
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = true
            isScaleControlsEnabled = true
            isMyLocationButtonEnabled = false
            isRotateGesturesEnabled = true
            isScrollGesturesEnabled = true
            isTiltGesturesEnabled = true
            isZoomGesturesEnabled = true
            setAllGesturesEnabled(true)
        }

        map.mapType = AMap.MAP_TYPE_NORMAL

        val cameraUpdate = com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
            LatLng(CampusGeo.CENTER_LAT, CampusGeo.CENTER_LNG), 16f
        )
        map.moveCamera(cameraUpdate)
    }

    private fun setupMarkerClickListener(map: AMap) {
        map.setOnMarkerClickListener { marker ->
            val location = marker.`object` as? Location
            if (location != null) {
                viewModel.selectLocation(location)
            }
            false
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        val hasFine = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            startSystemLocation()
            return
        }

        val shouldShowRationale =
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (shouldShowRationale) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("需要定位权限")
                .setMessage("GUET地图需要获取您的位置信息，以便在校园地图上显示您的当前位置，提供精准的导航服务。")
                .setPositiveButton("去授权") { _, _ ->
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                .setNegativeButton("暂不", null)
                .show()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun requestLocationPermission() {
        requestLocationPermissionIfNeeded()
    }

    private val locationListener = android.location.LocationListener { location ->
        onLocationReceived(location)
    }

    private fun startSystemLocation() {
        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider, 2000L, 5f, locationListener
                    )
                } catch (_: SecurityException) {}
            }
        } catch (e: SecurityException) {}
    }

    private fun stopLocationServices() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }

    private fun onLocationReceived(location: android.location.Location) {
        val gcj = CoordinateUtil.wgs84ToGcj02(
            requireContext(),
            location.latitude,
            location.longitude
        )
        latestLocation = location
        latestGcjLatLng = LatLng(gcj.latitude, gcj.longitude)
        val map = aMap ?: return
        val latLng = latestGcjLatLng!!

        if (myLocationMarker == null) {
            val icon = createMyLocationIcon()
            myLocationMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
                    .zIndex(10f)
            )
        } else {
            myLocationMarker?.position = latLng
        }

        if (pendingCenterOnLocation) {
            pendingCenterOnLocation = false
            val update = com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(latLng, 17f)
            map.animateCamera(update, 500, null)
        }

        if (!hasAutoCenteredOnLocation && location.accuracy < 50) {
            hasAutoCenteredOnLocation = true
            val update = com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(latLng, 17f)
            map.animateCamera(update, 500, null)
        }
    }

    private fun createMyLocationIcon(): com.amap.api.maps.model.BitmapDescriptor {
        val avatarUrl = userPrefs.avatar
        return if (!avatarUrl.isNullOrEmpty()) {
            try {
                val loader = ImageLoader(requireContext())
                val request = ImageRequest.Builder(requireContext())
                    .data(avatarUrl)
                    .size(120, 120)
                    .transformations(CircleCropTransformation())
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                if (result != null) {
                    BitmapDescriptorFactory.fromBitmap(result.toBitmap())
                } else {
                    defaultLocationIcon()
                }
            } catch (_: Exception) {
                defaultLocationIcon()
            }
        } else {
            defaultLocationIcon()
        }
    }

    private fun defaultLocationIcon(): com.amap.api.maps.model.BitmapDescriptor {
        val size = dpToPx(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4285F4")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(3).toFloat()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dpToPx(1.5f), borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(20).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val nickname = userPrefs.nickname
        val initial = nickname.firstOrNull()?.uppercaseChar() ?: '?'
        val textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial.toString(), size / 2f, textY, textPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun Bitmap.toBitmap(): Bitmap {
        if (this is BitmapDrawable) return bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun centerOnMyLocation() {
        val map = aMap
        if (map == null) {
            requestLocationPermissionIfNeeded()
            Toast.makeText(requireContext(), "正在获取位置…", Toast.LENGTH_SHORT).show()
            return
        }
        val loc = latestLocation
        if (loc != null) {
            val gcj = CoordinateUtil.wgs84ToGcj02(requireContext(), loc.latitude, loc.longitude)
            val update = com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
                LatLng(gcj.latitude, gcj.longitude), 17f
            )
            map.animateCamera(update)
        } else {
            pendingCenterOnLocation = true
            requestLocationPermissionIfNeeded()
            Toast.makeText(requireContext(), "正在获取位置…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initNavigationClient() {
        // Navigation client initialization placeholder
    }

    private fun startWalkNavigation(location: Location) {
        val start = latestGcjLatLng ?: viewModel.campusCenterLatLng()
        viewModel.planWalkRouteTo(location, start)
    }

    private fun openExternalNavigation(location: Location) {
        val pm = requireContext().packageManager
        try {
            val uriBuilder = StringBuilder("androidamap://route/plan/?")
            uriBuilder.append("dlat=${location.latitude}&dlon=${location.longitude}")
            uriBuilder.append("&dname=${Uri.encode(location.name)}")
            uriBuilder.append("&dev=0&t=2")

            if (latestGcjLatLng != null) {
                uriBuilder.append("&slat=${latestGcjLatLng?.latitude}&slon=${latestGcjLatLng?.longitude}")
                uriBuilder.append("&sname=${Uri.encode("我的位置")}")
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
                setPackage("com.autonavi.minimap")
            }

            if (intent.resolveActivity(pm) != null) {
                startActivity(intent)
            } else {
                openGenericMap(location)
            }
        } catch (_: Exception) {
            openGenericMap(location)
        }
    }

    private fun openExternalNavigationByCoords(lat: Double, lng: Double, name: String) {
        val pm = requireContext().packageManager
        try {
            val uriBuilder = StringBuilder("androidamap://route/plan/?")
            uriBuilder.append("dlat=$lat&dlon=$lng")
            uriBuilder.append("&dname=${Uri.encode(name)}")
            uriBuilder.append("&dev=0&t=2")

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
                setPackage("com.autonavi.minimap")
            }

            if (intent.resolveActivity(pm) != null) {
                startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun openGenericMap(location: Location) {
        val geoUri = Uri.parse(
            "geo:${location.latitude},${location.longitude}?q=${Uri.encode(location.name)}"
        )
        val intent = Intent(Intent.ACTION_VIEW, geoUri)
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(intent, getString(R.string.nav_amap_app)))
        } else {
            copyLocationToClipboard(location)
        }
    }

    private fun copyLocationToClipboard(location: Location) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(
            "坐标",
            "${location.name}: ${location.latitude}, ${location.longitude}"
        )
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "坐标已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun showWalkRouteOnMap(route: com.example.guet_map.model.WalkRouteInfo) {
        val map = aMap ?: return
        clearWalkRouteFromMap()

        routePolyline = map.addPolyline(
            com.amap.api.maps.model.PolylineOptions()
                .addAll(route.polyline)
                .width(12f)
                .color(ContextCompat.getColor(requireContext(), R.color.primary))
        )

        // 导航显示时收起筛选标签和搜索结果
        binding.rvFilterTags.visibility = View.GONE
        searchBarComponent.dismissSearchResults()

        val builder = com.amap.api.maps.model.LatLngBounds.builder()
        route.polyline.forEach { builder.include(it) }
        map.animateCamera(
            com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(builder.build(), 120)
        )
    }

    private fun clearWalkRouteFromMap() {
        routePolyline?.remove()
        routePolyline = null
        binding.rvFilterTags.visibility = View.VISIBLE
    }

    private fun focusMap(lat: Double, lng: Double, zoom: Float) {
        aMap?.moveCamera(
            com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom)
        )
    }

    private suspend fun toggleFavorite(location: Location) {
        val nowFavorite = viewModel.toggleFavorite(location)
        Toast.makeText(
            requireContext(),
            if (nowFavorite) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareLocation(location: Location) {
        val text = buildString {
            appendLine(location.name)
            appendLine("分类：${location.category}")
            appendLine("坐标：${location.latitude}, ${location.longitude}")
            append("来自 GUET Map 校园导航")
        }
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        )
    }

    private fun openLocationOnMap(locationId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val loc = viewModel.resolveAndSelectLocation(locationId)
            if (loc != null) {
                aMap?.moveCamera(
                    com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
                        LatLng(loc.latitude, loc.longitude), 17f
                    )
                )
                startWalkNavigation(loc)
            }
        }
    }

    private fun requestOverlayPermissionAndStartFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("需要悬浮窗权限")
                .setMessage("AI 助手需要悬浮窗权限才能显示在屏幕上。请在设置中开启此权限。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            FloatingWindowService.start(requireContext())
        }
    }

    private fun showPrivacyDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("隐私政策与用户协议")
            .setMessage(
                "欢迎使用GUET地图！\n\n" +
                        "我们将使用高德地图SDK为您提供定位与地图导航服务。" +
                        "在使用过程中，我们需要收集您的位置信息以提供精准的校内导航指引。\n\n" +
                        "您的位置数据仅用于本应用内的地图展示与导航功能，" +
                        "不会用于其他商业用途。\n\n" +
                        "点击「同意」即表示您已阅读并接受我们的《隐私政策》与《用户协议》。"
            )
            .setPositiveButton("同意") { _, _ ->
                viewModel.setPrivacyAgreed()
                MapsInitializer.updatePrivacyShow(requireContext(), true, true)
                MapsInitializer.updatePrivacyAgree(requireContext(), true)
                ServiceSettings.updatePrivacyShow(requireContext(), true, true)
                ServiceSettings.updatePrivacyAgree(requireContext(), true)
                initMapView(null)
            }
            .setNegativeButton("拒绝") { _, _ ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("功能受限")
                    .setMessage("您需要同意隐私政策才能使用完整的地图服务。")
                    .setPositiveButton("确定") { _, _ ->
                        requireActivity().finish()
                    }
                    .show()
            }
            .setCancelable(false)
            .show()
    }

    // ── 天气 Banner ──────────────────────────────────────────

    private fun loadWeatherBanner() {
        binding.pbBannerWeather.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = getWeatherUseCase()) {
                is Resource.Success -> updateWeatherBanner(result.data)
                is Resource.Error -> {
                    binding.pbBannerWeather.visibility = View.GONE
                    binding.tvWeatherSafety.text = getString(R.string.weather_unknown)
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun updateWeatherBanner(weather: Weather) {
        binding.apply {
            pbBannerWeather.visibility = View.GONE
            tvBannerTemp.text = "${weather.temperature}°"
            tvWeatherSafety.text = weather.description

            val iconRes = when (weather.weatherType) {
                WeatherType.SUNNY -> R.drawable.ic_weather_sunny
                WeatherType.CLOUDY -> R.drawable.ic_weather_cloudy
                WeatherType.OVERCAST -> R.drawable.ic_weather_overcast
                WeatherType.LIGHT_RAIN -> R.drawable.ic_weather_light_rain
                WeatherType.MODERATE_RAIN -> R.drawable.ic_weather_moderate_rain
                WeatherType.HEAVY_RAIN -> R.drawable.ic_weather_moderate_rain
                WeatherType.THUNDERSTORM -> R.drawable.ic_weather_thunderstorm
                WeatherType.SNOW -> R.drawable.ic_weather_snow
                WeatherType.FOG -> R.drawable.ic_weather_fog
                WeatherType.WINDY -> R.drawable.ic_weather_windy
                WeatherType.UNKNOWN -> R.drawable.ic_weather_unknown
            }
            ivBannerWeatherIcon.setImageResource(iconRes)
        }
    }

    companion object
}
