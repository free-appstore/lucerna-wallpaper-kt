package com.lucerna.core.actions

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem

/**
 * 界面可以触发的平台动作。
 *
 * 实现放在 `lucerna-app`，UI 只依赖这个接口，方便预览与测试时替换成假实现。
 */
interface WallpaperActions {
    fun applyLibrary(item: WallpaperItem)
    fun applyColor(fill: ColorFill)
    fun setVolume(volume: Float)
    fun setAudioEnabled(enabled: Boolean)
    fun setVideoPlaybackSpeed(speed: Float)
    fun setDisabled(disabled: Boolean)
    fun setLaunchAtLogin(enabled: Boolean)
    fun setScreensaverWallpaper(enabled: Boolean)
    fun setAllSpacesShared(enabled: Boolean)
    fun setTrayIconStyle(style: TrayIconStyle)
    fun openWallpaperSettings()
    fun testScreensaver()
    fun openFileLocation(path: String)
    fun openDirectory(path: String)
    fun quit()
}
