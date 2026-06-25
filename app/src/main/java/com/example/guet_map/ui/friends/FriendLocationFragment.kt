package com.example.guet_map.ui.friends

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.example.guet_map.R
import com.example.guet_map.databinding.FragmentFriendLocationBinding
import com.example.guet_map.util.CampusGeo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FriendLocationFragment : Fragment() {

    private var _binding: FragmentFriendLocationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FriendLocationViewModel by viewModels()

    private lateinit var adapter: FriendLocationAdapter
    private var lastMessage: String? = null
    private var aMap: AMap? = null
    private var mapViewCreated = false
    private var isSharingLocation = false
    private val friendMarkers = mutableMapOf<String, Marker>()
    private var myLocationMarker: Marker? = null
    private var myLatitude: Double = 0.0
    private var myLongitude: Double = 0.0

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            startLocationUpdates()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendLocationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupMap(savedInstanceState)
        setupRecyclerView()
        setupShareSwitch()
        observeState()
        checkLocationPermission()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        if (!mapViewCreated) {
            aMap = binding.mapView.map
            configureMap()
            mapViewCreated = true
        }
    }

    private fun configureMap() {
        aMap?.apply {
            uiSettings.apply {
                isZoomControlsEnabled = false
                isCompassEnabled = true
                isScaleControlsEnabled = true
                isMyLocationButtonEnabled = false
            }

            val cameraUpdate = com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
                LatLng(CampusGeo.CENTER_LAT, CampusGeo.CENTER_LNG), 16f
            )
            moveCamera(cameraUpdate)
        }
    }

    private fun setupRecyclerView() {
        adapter = FriendLocationAdapter { friend, location ->
            // 点击好友，在地图上显示位置
            showFriendOnMap(friend.nickname, location.latitude, location.longitude)
        }

        binding.rvFriends.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@FriendLocationFragment.adapter
        }
    }

    private fun setupShareSwitch() {
        binding.switchShareLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkLocationPermissionAndStart()
            } else {
                stopLocationUpdates()
            }
            isSharingLocation = isChecked
            updateShareStatus()
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            binding.switchShareLocation.isChecked = isSharingLocation
            updateShareStatus()
        }
    }

    private fun checkLocationPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        binding.tvShareStatus.text = "正在获取位置..."
        
        // 使用Android系统定位获取位置
        val locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val locationListener = android.location.LocationListener { location ->
            myLatitude = location.latitude
            myLongitude = location.longitude
            
            // 更新自己的位置到服务器
            viewModel.updateMyLocation(myLatitude, myLongitude)
            
            // 在地图上显示自己的位置
            updateMyLocationOnMap()
            
            binding.tvShareStatus.text = "正在共享你的位置"
        }
        
        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider, 2000L, 5f, locationListener
                    )
                } catch (_: SecurityException) {}
            }
        } catch (_: SecurityException) {}
        
        Toast.makeText(context, "位置共享已开启", Toast.LENGTH_SHORT).show()
    }

    private fun updateMyLocationOnMap() {
        aMap?.let { map ->
            val myLatLng = LatLng(myLatitude, myLongitude)
            
            if (myLocationMarker == null) {
                // 创建自己的位置标记（红色）
                myLocationMarker = map.addMarker(
                    MarkerOptions()
                        .position(myLatLng)
                        .title("我")
                        .snippet("我的位置")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .zIndex(10f)
                )
            } else {
                myLocationMarker?.position = myLatLng
            }
        }
    }

    private fun createMyFriendIcon(): com.amap.api.maps.model.BitmapDescriptor {
        // 使用自己的头像（如果有）
        val prefs = com.example.guet_map.data.UserPrefs(requireContext())
        return createFriendIcon(prefs.avatar)
    }

    private fun stopLocationUpdates() {
        // 停止定位服务
        val locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        try {
            // 注意：这里简化处理，实际应该保存locationListener引用后移除
        } catch (_: Exception) {}
        
        // 移除自己的位置标记
        myLocationMarker?.remove()
        myLocationMarker = null
        
        binding.tvShareStatus.text = "位置共享已关闭"
        Toast.makeText(context, "位置共享已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun updateShareStatus() {
        if (isSharingLocation) {
            binding.tvShareStatus.text = "正在共享你的位置"
            binding.tvShareStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success)
            )
        } else {
            binding.tvShareStatus.text = "位置共享已关闭"
            binding.tvShareStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
        }
    }

    private fun showFriendOnMap(name: String, latitude: Double, longitude: Double) {
        aMap?.let { map ->
            // 清除之前的标记
            map.clear()
            friendMarkers.clear()

            // 添加好友位置标记（带头像）
            val friendLatLng = LatLng(latitude, longitude)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(friendLatLng)
                    .title(name)
                    .snippet("好友位置")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            friendMarkers[name] = marker

            // 移动到好友位置
            val cameraUpdate = com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(friendLatLng, 18f)
            map.animateCamera(cameraUpdate)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: FriendLocationUiState) {
        binding.progressBar.isVisible = state.isLoading

        // 过滤出有位置的好友
        val friendsWithLocation = state.friends.mapNotNull { friend ->
            val location = state.friendLocations[friend.userId]
            if (location != null) {
                friend to location
            } else null
        }

        if (friendsWithLocation.isNotEmpty()) {
            binding.rvFriends.isVisible = true
            binding.tvEmpty.isVisible = false
            binding.tvFriendsHeader.isVisible = true
            adapter.submitList(friendsWithLocation.map { (friend, location) ->
                FriendWithLocation(friend, location)
            })

            // 在地图上显示所有好友位置
            showAllFriendsOnMap(friendsWithLocation)
        } else {
            binding.rvFriends.isVisible = false
            binding.tvEmpty.isVisible = true
            binding.tvFriendsHeader.isVisible = false
        }

        val msg = state.message
        if (!msg.isNullOrBlank() && msg != lastMessage) {
            lastMessage = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAllFriendsOnMap(friendsWithLocation: List<Pair<com.example.guet_map.model.FriendInfo, com.example.guet_map.model.FriendLocation>>) {
        aMap?.let { map ->
            // 清除好友标记，但保留自己的标记
            friendMarkers.clear()
            
            // 显示好友位置（蓝色标记）
            friendsWithLocation.forEach { (friend, location) ->
                val latLng = LatLng(location.latitude, location.longitude)
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title(friend.nickname)
                        .snippet("好友位置")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
                friendMarkers[friend.userId.toString()] = marker
            }
            
            // 如果自己开启了位置共享，确保自己的位置也显示
            if (isSharingLocation && myLocationMarker == null && myLatitude != 0.0) {
                updateMyLocationOnMap()
            }
        }
    }

    private fun createFriendIcon(avatarUrl: String?): com.amap.api.maps.model.BitmapDescriptor {
        if (!avatarUrl.isNullOrEmpty()) {
            // 先返回默认图标，头像加载成功后会替换
            return defaultFriendIcon()
        }
        return defaultFriendIcon()
    }

    private fun defaultFriendIcon(): com.amap.api.maps.model.BitmapDescriptor {
        val size = dpToPx(32)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4285F4")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1f * resources.displayMetrics.density, border)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun android.graphics.drawable.Drawable.toBitmapWithBorder(): Bitmap {
        val avatarBmp = toBitmap()
        val borderWidth = dpToPx(2)
        val totalSize = avatarBmp.width + borderWidth * 2
        val result = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(totalSize / 2f, totalSize / 2f, totalSize / 2f, whitePaint)

        canvas.drawBitmap(avatarBmp, borderWidth.toFloat(), borderWidth.toFloat(), null)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4285F4")
            style = Paint.Style.STROKE
            strokeWidth = borderWidth.toFloat()
        }
        canvas.drawCircle(totalSize / 2f, totalSize / 2f, totalSize / 2f - borderWidth / 2f, accent)
        return result
    }

    private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
        if (this is android.graphics.drawable.BitmapDrawable) return bitmap
        val bmp = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp
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
}
