package com.example.guet_map.ui.map.state

/**
 * 地图界面一次性事件
 */
sealed class MapUiEvent {

    /** 显示吐司消息 */
    data class ShowToast(
        val message: String,
        val duration: Int = android.widget.Toast.LENGTH_SHORT
    ) : MapUiEvent()

    /** 展开 BottomSheet 显示地点详情 */
    data class ShowLocationSheet(
        val locationId: String
    ) : MapUiEvent()

    /** 隐藏 BottomSheet */
    data object HideLocationSheet : MapUiEvent()

    /** 地图聚焦到指定位置 */
    data class FocusMap(
        val latitude: Double,
        val longitude: Double,
        val zoom: Float = 17f
    ) : MapUiEvent()

    /** 收起搜索框 */
    data object DismissSearchInput : MapUiEvent()

    /** 显示加载中 */
    data class ShowLoading(
        val message: String? = null
    ) : MapUiEvent()

    /** 隐藏加载中 */
    data object HideLoading : MapUiEvent()

    /** 导航到外部地图应用 */
    data class NavigateToExternal(
        val latitude: Double,
        val longitude: Double,
        val name: String
    ) : MapUiEvent()

    /** 请求定位权限 */
    data object RequestLocationPermission : MapUiEvent()

    /** 显示定位精度提示 */
    data class ShowLocationAccuracy(
        val accuracyMeters: Float
    ) : MapUiEvent()

    /** 地图主题切换 */
    data class MapThemeChanged(
        val theme: com.example.guet_map.ui.map.model.MapThemeType
    ) : MapUiEvent()
}
