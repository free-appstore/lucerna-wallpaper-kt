package com.lucerna.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lucerna.core.actions.WallpaperActions
import com.lucerna.core.media.Importer
import com.lucerna.core.media.deleteLibraryFiles
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperSource
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.store.LibraryStore
import com.lucerna.core.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 面板顶部的四个分页。 */
enum class PanelTab(val label: String) {
    MINE("我的"),
    COLOR("色彩"),
    IMPORT("导入"),
    SETTINGS("设置"),
}

/** 「我的」页顶部的二级分类。 */
enum class MineFilter(val label: String) {
    IMAGE("图片"),
    VIDEO("视频"),
    COLOR("色彩"),
}

/** 请求打开预览窗口。 */
data class PreviewRequest(val item: WallpaperItem)

/**
 * 面板的界面状态。
 *
 * 只依赖 core 的存储与动作接口，测试时可以传入假实现直接渲染。
 */
class LucernaState(
    val paths: AppPaths,
    private val settingsStore: SettingsStore,
    private val libraryStore: LibraryStore,
    val actions: WallpaperActions,
    private val importer: Importer,
    private val onPreviewRequest: (PreviewRequest) -> Unit = {},
) {
    var tab by mutableStateOf(PanelTab.MINE)

    var settings by mutableStateOf(settingsStore.load())
        private set

    var library by mutableStateOf(libraryStore.load())
        private set

    /** 色彩页的实时草稿，点“应用”后才写进 settings。 */
    var colorDraft by mutableStateOf(settings.colorFill)
        private set

    var selectedColorIndex by mutableStateOf(0)

    var toast by mutableStateOf<String?>(null)

    var busyMessage by mutableStateOf<String?>(null)

    /** 「我的」页当前显示的类型。 */
    var mineFilter by mutableStateOf(MineFilter.IMAGE)
        private set

    init {
        // 旧版本只保存了「当前色彩」，没有列表：启动时把这一份补进「我的 - 色彩」
        if (settings.currentSource == WallpaperSource.COLOR && settings.colorFill !in settings.savedColors) {
            settings = settings.copy(savedColors = listOf(settings.colorFill) + settings.savedColors)
            persistSettings()
        }
    }

    val items: List<WallpaperItem> get() = library.items

    /** 「我的 - 色彩」里的色彩壁纸，最近添加的在前。 */
    val savedColors: List<ColorFill> get() = settings.savedColors

    /** 正在使用的色彩；当前壁纸不是色彩时为 null。 */
    val activeColor: ColorFill? get() = if (usingColor) settings.colorFill else null

    fun selectMineFilter(next: MineFilter) {
        mineFilter = next
    }

    val usingColor: Boolean
        get() = settings.currentSource == WallpaperSource.COLOR

    fun selectTab(next: PanelTab) {
        tab = next
    }

    fun applyItem(item: WallpaperItem) {
        settings = settings.copy(
            currentWallpaperId = item.id,
            currentSource = WallpaperSource.LIBRARY,
        )
        persistSettings()
        actions.applyLibrary(item)
        toast = if (item.type == WallpaperType.VIDEO) {
            "已应用 ${item.name} 的静态封面"
        } else {
            "已应用 ${item.name}"
        }
    }

    fun applyColor(fill: ColorFill) {
        val normalized = fill.normalized()
        colorDraft = normalized
        settings = settings.copy(colorFill = normalized, currentSource = WallpaperSource.COLOR)
        persistSettings()
        actions.applyColor(normalized)
        toast = "已应用${normalized.summary()}"
    }

    /** 「添加并应用」：把当前草稿存进「我的 - 色彩」，同时立刻设为壁纸。 */
    fun addAndApplyColor(fill: ColorFill) {
        val normalized = fill.normalized()
        colorDraft = normalized
        settings = settings.copy(
            colorFill = normalized,
            currentSource = WallpaperSource.COLOR,
            savedColors = listOf(normalized) + settings.savedColors.filterNot { it == normalized },
        )
        persistSettings()
        actions.applyColor(normalized)
        toast = "已添加并应用${normalized.summary()}"
    }

    /** 从「我的 - 色彩」移除一条；正在使用的壁纸保持不变。 */
    fun removeSavedColor(fill: ColorFill) {
        settings = settings.copy(savedColors = settings.savedColors.filterNot { it == fill })
        persistSettings()
        toast = "已从色彩列表移除${fill.summary()}"
    }

    /** 把一条已保存的色彩载入色彩页继续编辑。 */
    fun editColor(fill: ColorFill) {
        updateColorDraft(fill.normalized())
        tab = PanelTab.COLOR
    }

    fun updateColorDraft(fill: ColorFill) {
        colorDraft = fill
        selectedColorIndex = selectedColorIndex.coerceIn(0, (fill.wellCount - 1).coerceAtLeast(0))
    }

    fun removeItem(item: WallpaperItem) {
        library = libraryStore.remove(item.id)
        deleteLibraryFiles(paths, item)
        if (settings.currentWallpaperId == item.id) {
            settings = settings.copy(currentWallpaperId = null)
            persistSettings()
        }
        toast = "已从图库移除 ${item.name}"
    }

    fun openPreview(item: WallpaperItem) {
        onPreviewRequest(PreviewRequest(item))
    }

    fun openItemLocation(item: WallpaperItem) {
        actions.openFileLocation(item.path)
    }

    fun openRenders() {
        actions.openDirectory(paths.renderDir.toFile().absolutePath)
    }

    fun openMediaLibrary() {
        actions.openDirectory(paths.mediaDir.toFile().absolutePath)
    }

    fun setVolume(volume: Float) {
        val safe = volume.coerceIn(0f, 1f)
        settings = settings.copy(volume = safe)
        persistSettings()
        actions.setVolume(safe)
    }

    fun setAudioEnabled(enabled: Boolean) {
        settings = settings.copy(audioEnabled = enabled)
        persistSettings()
        actions.setAudioEnabled(enabled)
    }

    fun setDisabled(disabled: Boolean) {
        settings = settings.copy(disabled = disabled)
        persistSettings()
        actions.setDisabled(disabled)
        toast = if (disabled) "已禁用动态壁纸" else "已恢复动态壁纸"
    }

    fun setLaunchAtLogin(enabled: Boolean) {
        settings = settings.copy(launchAtLogin = enabled)
        persistSettings()
        actions.setLaunchAtLogin(enabled)
    }

    fun setScreensaverWallpaper(enabled: Boolean) {
        settings = settings.copy(screensaverWallpaper = enabled)
        persistSettings()
        actions.setScreensaverWallpaper(enabled)
    }

    fun setAllSpacesShared(enabled: Boolean) {
        settings = settings.copy(allSpacesShared = enabled)
        persistSettings()
        actions.setAllSpacesShared(enabled)
        toast = if (enabled) {
            "已开启；请在系统墙纸设置中确认「在所有空间中显示」"
        } else {
            "已关闭多空间同步"
        }
    }

    fun setTrayIconStyle(style: TrayIconStyle) {
        settings = settings.copy(trayIconStyle = style)
        persistSettings()
        actions.setTrayIconStyle(style)
        toast = "菜单栏图标已切换为${style.label}"
    }

    fun openWallpaperSettings() {
        actions.openWallpaperSettings()
    }

    fun testScreensaver() {
        actions.testScreensaver()
        toast = "正在启动屏幕保护程序"
    }

    fun rememberImportDir(dir: File) {
        settings = settings.copy(importDir = dir.absolutePath)
        persistSettings()
    }

    /** 导入文件，内部切到 IO 线程；进度通过 [busyMessage] 显示。 */
    suspend fun importFiles(files: List<File>) {
        if (files.isEmpty()) return
        busyMessage = "正在导入 ${files.size} 个文件…"
        val added = withContext(Dispatchers.IO) {
            importer.importFiles(files) { file ->
                busyMessage = "正在导入 ${file.name}…"
            }
        }
        // 导入器已经把合并后的结果写盘；这里直接在内存里按同样的规则合并，省掉一次读盘与解析
        if (added.isNotEmpty()) {
            library = LibraryData(
                (added + library.items)
                    .distinctBy { it.id }
                    .sortedByDescending { it.addedAt },
            )
        }
        busyMessage = null
        toast = if (added.isEmpty()) "没有可导入的图片或视频" else "已导入 ${added.size} 个壁纸"
        if (added.isNotEmpty()) tab = PanelTab.MINE
    }

    fun consumeToast() {
        toast = null
    }

    private fun persistSettings() {
        settingsStore.save(settings)
    }
}
