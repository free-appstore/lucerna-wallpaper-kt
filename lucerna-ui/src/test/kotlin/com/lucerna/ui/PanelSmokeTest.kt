package com.lucerna.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.lucerna.core.actions.WallpaperActions
import com.lucerna.core.media.Importer
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.store.LibraryStore
import com.lucerna.core.store.SettingsStore
import com.lucerna.ui.panel.LucernaPanel
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.theme.LucernaTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PanelSmokeTest {

    @Test
    fun `四个分页可以切换且色彩编辑器可用`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-smoke").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val library = LibraryStore(paths)
        val state = LucernaState(
            paths = paths,
            settingsStore = SettingsStore(paths),
            libraryStore = library,
            actions = actions,
            importer = Importer(paths, library),
        )

        setContent {
            LucernaTheme {
                LucernaPanel(state, Modifier.size(392.dp, 640.dp))
            }
        }

        // 我的页
        onNodeWithText("我的").assertExists()
        onNodeWithText("图库还是空的").assertExists()

        // 色彩页：默认渐变，切到色阶后出现阶梯数量
        onNodeWithText("色彩").performClick()
        onNodeWithText("渐变").assertExists()
        onNodeWithText("阶梯数量").assertDoesNotExist()
        onNodeWithText("色阶").performClick()
        onNodeWithText("阶梯数量").assertExists()

        // 设置页
        onNodeWithText("设置").performClick()
        onNodeWithText("视频声音").assertExists()
        onNodeWithText("登录时启动").assertExists()
        onNodeWithText("屏幕保护程序").assertExists()
        onNodeWithText("在所有空间中显示").assertExists()
        // 菜单栏图标样式：默认彩色，点「单色」后切换
        onNodeWithText("菜单栏图标").assertExists()
        onNodeWithText("单色").performClick()
        assertEquals(TrayIconStyle.MONO, state.settings.trayIconStyle)
        assertEquals(TrayIconStyle.MONO, actions.trayIconStyles.last())

        // 导入页
        onNodeWithText("导入").performClick()
        onNodeWithText("全部").assertExists()
    }

    @Test
    fun `我的页按照片视频分类折叠`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-sections").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val library = LibraryStore(paths)
        library.save(
            LibraryData(
                listOf(
                    WallpaperItem("p1", "one.png", WallpaperType.IMAGE, "/tmp/one.png", 1),
                    WallpaperItem("p2", "two.png", WallpaperType.IMAGE, "/tmp/two.png", 2),
                    WallpaperItem("v1", "clip.mp4", WallpaperType.VIDEO, "/tmp/clip.mp4", 3),
                ),
            ),
        )
        val state = LucernaState(
            paths = paths,
            settingsStore = SettingsStore(paths),
            libraryStore = library,
            actions = actions,
            importer = Importer(paths, library),
        )

        setContent {
            LucernaTheme {
                LucernaPanel(state, Modifier.size(392.dp, 640.dp))
            }
        }

        onNodeWithText("照片（2）").assertExists()
        onNodeWithText("视频（1）").assertExists()
        // 没有色彩壁纸时不显示色彩分组
        onNodeWithText("色彩（1）").assertDoesNotExist()

        onNodeWithText("照片（2）").performClick()
        mainClock.advanceTimeBy(400)
        waitForIdle()
        onNodeWithText("one.png").assertDoesNotExist()

        onNodeWithText("照片（2）").performClick()
        mainClock.advanceTimeBy(400)
        waitForIdle()
        onNodeWithText("one.png").assertExists()
    }

    @Test
    fun `应用色彩会写设置并触发动作为色阶`() {
        val root = Files.createTempDirectory("lucerna-ui-state").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val library = LibraryStore(paths)
        val settings = SettingsStore(paths)
        val state = LucernaState(paths, settings, library, actions, Importer(paths, library))

        val fill = ColorFill(kind = ColorKind.STEPS, steps = 8)
        state.updateColorDraft(fill)
        state.applyColor(fill)

        assertEquals(ColorKind.STEPS, settings.load().colorFill.kind)
        assertEquals(8, settings.load().colorFill.steps)
        assertEquals(1, actions.appliedColors.size)
        assertTrue(state.toast?.contains("色阶") == true)
    }
}

private class RecordingActions : WallpaperActions {
    val appliedItems = mutableListOf<WallpaperItem>()
    val appliedColors = mutableListOf<ColorFill>()
    val trayIconStyles = mutableListOf<TrayIconStyle>()

    override fun applyLibrary(item: WallpaperItem) {
        appliedItems += item
    }

    override fun applyColor(fill: ColorFill) {
        appliedColors += fill
    }

    override fun setVolume(volume: Float) = Unit
    override fun setAudioEnabled(enabled: Boolean) = Unit
    override fun setDisabled(disabled: Boolean) = Unit
    override fun setLaunchAtLogin(enabled: Boolean) = Unit
    override fun setScreensaverWallpaper(enabled: Boolean) = Unit
    override fun setAllSpacesShared(enabled: Boolean) = Unit
    override fun setTrayIconStyle(style: TrayIconStyle) {
        trayIconStyles += style
    }
    override fun openWallpaperSettings() = Unit
    override fun testScreensaver() = Unit
    override fun openFileLocation(path: String) = Unit
    override fun openDirectory(path: String) = Unit
    override fun quit() = Unit
}
