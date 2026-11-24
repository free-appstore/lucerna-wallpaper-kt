package com.lucerna.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 当前壁纸来自图库还是色彩填充。 */
@Serializable
enum class WallpaperSource {
    @SerialName("library")
    LIBRARY,

    @SerialName("color")
    COLOR,
}

/**
 * 持久化的用户偏好。
 *
 * 所有字段都带默认值，老版本配置缺少字段时按默认值补齐。
 */
@Serializable
data class Settings(
    val version: Int = 1,
    val currentWallpaperId: String? = null,
    val currentSource: WallpaperSource = WallpaperSource.LIBRARY,
    val colorFill: ColorFill = ColorFill.default(),
    /** 壁纸音量，0..1。 */
    val volume: Float = 0.8f,
    /** 是否播放视频壁纸的声音。 */
    val audioEnabled: Boolean = false,
    /** 禁用后停止动态壁纸播放并隐藏视频窗口。 */
    val disabled: Boolean = false,
    val launchAtLogin: Boolean = false,
    /** 进入屏保时是否保留当前壁纸。 */
    val screensaverWallpaper: Boolean = true,
    /** 用户已在系统设置中打开「在所有空间中显示」。 */
    val allSpacesShared: Boolean = false,
    /** 导入页上次浏览的目录。 */
    val importDir: String? = null,
) {
    fun normalized(): Settings = copy(
        volume = volume.coerceIn(0f, 1f),
        colorFill = colorFill.normalized(),
    )
}
