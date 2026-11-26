package com.lucerna.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.lucerna.core.actions.WallpaperActions
import com.lucerna.core.media.Importer
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.Rgb
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.db.LucernaDb
import com.lucerna.ui.panel.LucernaPanel
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.state.PreviewRequest
import com.lucerna.ui.state.MineFilter
import com.lucerna.ui.state.PanelTab
import com.lucerna.ui.theme.LucernaTheme
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PanelSmokeTest {

    @Test
    fun `四个分页可以切换且色彩编辑器可用`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-smoke").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val state = LucernaState(
            paths = paths,
            settingsStore = db.settingsStore,
            libraryStore = library,
            actions = actions,
            importer = Importer(paths, library),
        )

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 我的页
        onNodeWithText("我的").assertExists()
        onNodeWithText("图库还是空的").assertExists()

        // 色彩页：默认渐变，切到色阶后出现阶数输入
        onNodeWithText("色彩").performClick()
        onNodeWithText("渐变").assertExists()
        onNodeWithText("添加并应用").assertExists()
        onNodeWithText("应用色彩壁纸").assertDoesNotExist()
        onNodeWithText("阶数").assertDoesNotExist()
        onNodeWithText("色阶").performClick()
        onNodeWithText("阶数").assertExists()

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
    fun `我的页按图片视频色彩切换`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-sections").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
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
            settingsStore = db.settingsStore,
            libraryStore = library,
            actions = actions,
            importer = Importer(paths, library),
        )
        state.addAndApplyColor(ColorFill(kind = ColorKind.STEPS, steps = 8))

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 顶部二级分类带数量；默认显示图片
        onNodeWithText("图片 2").assertExists()
        onNodeWithText("视频 1").assertExists()
        onNodeWithText("色彩 1").assertExists()
        onNodeWithText("one.png").assertExists()
        onNodeWithText("clip.mp4").assertDoesNotExist()

        // 切到视频：只显示视频
        onNodeWithText("视频 1").performClick()
        assertEquals(MineFilter.VIDEO, state.mineFilter)
        onNodeWithText("clip.mp4").assertExists()
        onNodeWithText("one.png").assertDoesNotExist()

        // 切到色彩：只显示色彩壁纸
        onNodeWithText("色彩 1").performClick()
        assertEquals(MineFilter.COLOR, state.mineFilter)
        onNodeWithText("色彩壁纸").assertExists()
        onNodeWithText("clip.mp4").assertDoesNotExist()

        // 切回图片
        onNodeWithText("图片 2").performClick()
        onNodeWithText("one.png").assertExists()
    }

    @Test
    fun `应用色彩会写设置并触发动作为色阶`() {
        val root = Files.createTempDirectory("lucerna-ui-state").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val settings = db.settingsStore
        val state = LucernaState(paths, settings, library, actions, Importer(paths, library))

        val fill = ColorFill(kind = ColorKind.STEPS, steps = 8)
        state.updateColorDraft(fill)
        state.applyColor(fill)

        assertEquals(ColorKind.STEPS, settings.load().colorFill.kind)
        assertEquals(8, settings.load().colorFill.steps)
        assertEquals(1, actions.appliedColors.size)
        assertTrue(state.toast?.contains("色阶") == true)
    }

    @Test
    fun `色彩页正好铺满面板且阶数输入受范围约束`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-fit").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val state = LucernaState(paths, db.settingsStore, library, actions, Importer(paths, library))
        state.selectTab(PanelTab.COLOR)
        state.updateColorDraft(
            ColorFill(
                kind = ColorKind.STEPS,
                colors = listOf(Rgb(0.9f, 0.4f, 0.2f), Rgb(0.2f, 0.4f, 0.9f), Rgb(0.3f, 0.8f, 0.5f)),
                steps = 5,
            ),
        )

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 预览固定 16:9
        val preview = onNodeWithContentDescription("色彩预览").getUnclippedBoundsInRoot()
        assertEquals(360f, (preview.right - preview.left).value, 0.5f)
        assertEquals(202.5f, (preview.bottom - preview.top).value, 0.5f)

        // 方向、阶数与按钮都在面板内，底部不需要滚动
        val steps = onNodeWithText("阶数").getUnclippedBoundsInRoot()
        val button = onNodeWithText("添加并应用").getUnclippedBoundsInRoot()
        assertTrue(button.bottom.value in 700f..730f)
        assertTrue(steps.bottom.value < button.top.value)

        // 角度 / 阶数并排，两个 label 顶部对齐（左右各自距卡片 10dp）
        val angleLabel = onNodeWithText("角度").getUnclippedBoundsInRoot()
        assertEquals(angleLabel.top.value, steps.top.value, 0.5f)

        // 左下角刷新按钮：方形，高度与右侧主按钮一致
        val randomButton = onNodeWithContentDescription("随机配色").getUnclippedBoundsInRoot()
        val randomHeight = (randomButton.bottom - randomButton.top).value
        assertEquals(38f, randomHeight, 0.5f)
        assertEquals(randomHeight, (randomButton.right - randomButton.left).value, 0.5f)
        assertEquals(button.bottom.value, randomButton.bottom.value, 0.5f)

        // 阶数输入框：2–20，且不少于 3 个颜色
        onNodeWithText("5").performTextReplacement("12")
        assertEquals(12, state.colorDraft.steps)
        onNodeWithText("12").performTextReplacement("30")
        assertEquals(12, state.colorDraft.steps)
        onNodeWithText("30").performTextReplacement("2")
        assertEquals(12, state.colorDraft.steps)
        onNodeWithText("2").performTextReplacement("3")
        assertEquals(3, state.colorDraft.steps)

        // 随机按钮：只换编辑区里的配色，不直接改壁纸；类型必须保持色阶
        onNodeWithContentDescription("随机配色").performClick()
        assertEquals(0, actions.appliedColors.size)
        assertEquals(ColorKind.STEPS, state.colorDraft.kind)
        assertTrue(state.colorDraft.colors.size in 2..5)

        // 只有点「添加并应用」才真正换壁纸
        onNodeWithText("添加并应用").performClick()
        assertEquals(1, actions.appliedColors.size)
    }

    @Test
    fun `阶数可以用上下按钮调节`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-steps").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val state = LucernaState(paths, db.settingsStore, library, RecordingActions(), Importer(paths, library))
        state.selectTab(PanelTab.COLOR)
        state.updateColorDraft(
            ColorFill(
                kind = ColorKind.STEPS,
                colors = listOf(Rgb(0.2f, 0.4f, 0.8f), Rgb(0.9f, 0.1f, 0.1f)),
                steps = 4,
            ),
        )

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 输入框先拿到焦点，再点上下按钮：框里的数字必须跟着变
        onNodeWithText("4").performClick()
        onNodeWithContentDescription("阶数加一").performClick()
        assertEquals(5, state.colorDraft.steps)
        onNodeWithText("5").assertExists()
        onNodeWithContentDescription("阶数减一").performClick()
        assertEquals(4, state.colorDraft.steps)
        onNodeWithText("4").assertExists()
        onNodeWithText("5").assertDoesNotExist()
    }

    @Test
    fun `颜色输入支持十六进制和十进制切换`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-hex").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val state = LucernaState(paths, db.settingsStore, library, RecordingActions(), Importer(paths, library))
        state.selectTab(PanelTab.COLOR)
        state.updateColorDraft(
            ColorFill(
                kind = ColorKind.STEPS,
                colors = listOf(Rgb(0.2f, 0.4f, 0.8f), Rgb(0.9f, 0.1f, 0.1f)),
                steps = 4,
            ),
        )

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 十六进制：'#' 前缀 + 6 位色值
        onNodeWithText("#").assertExists()
        onNodeWithText("3366CC").assertExists()
        onAllNodesWithText("R").assertCountEquals(1)

        // 十进制：R / G / B 三个输入框（滑杆标签 + 输入框标签各一个）
        onNodeWithText("10进制").performClick()
        onAllNodesWithText("R").assertCountEquals(2)
        onAllNodesWithText("102").assertCountEquals(2)
    }

    @Test
    fun `拖动分量滑杆只修改当前选中的颜色`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-slider").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val state = LucernaState(paths, db.settingsStore, library, RecordingActions(), Importer(paths, library))
        state.selectTab(PanelTab.COLOR)
        val first = Rgb(0.2f, 0.4f, 0.8f)
        val second = Rgb(0.9f, 0.3f, 0.1f)
        state.updateColorDraft(ColorFill(kind = ColorKind.STEPS, colors = listOf(first, second), steps = 4))

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 先切到第二个颜色，再拖 R：不应写回第一个颜色，也不该改动 G / B
        state.selectedColorIndex = 1
        waitForIdle()
        onNodeWithContentDescription("R 分量滑杆").performTouchInput { click(percentOffset(0.8f, 0.5f)) }
        waitForIdle()

        assertEquals(first, state.colorDraft.colors[0])
        val changed = state.colorDraft.colors[1]
        assertTrue(changed.r > 0.7f)
        assertEquals(second.g, changed.g)
        assertEquals(second.b, changed.b)
    }

    @Test
    fun `添加并应用会把色彩存进我的色彩列表`() {
        val root = Files.createTempDirectory("lucerna-ui-colors").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val settings = db.settingsStore
        val state = LucernaState(paths, settings, library, actions, Importer(paths, library))

        val fill = ColorFill(kind = ColorKind.SOLID, colors = listOf(Rgb(0.2f, 0.4f, 0.8f)))
        state.updateColorDraft(fill)
        state.addAndApplyColor(fill)

        assertEquals(listOf(fill), state.savedColors)
        assertEquals(listOf(fill), settings.load().savedColors)
        assertEquals(fill, settings.load().colorFill)
        assertEquals(1, actions.appliedColors.size)
        assertEquals(fill, state.activeColor)
        assertContains(state.toast.orEmpty(), "添加并应用")

        // 同一份色彩再点一次不会重复入列
        state.addAndApplyColor(fill)
        assertEquals(1, state.savedColors.size)

        // 新的一份排在最前
        val second = fill.copy(colors = listOf(Rgb(0.9f, 0.3f, 0.1f)))
        state.addAndApplyColor(second)
        assertEquals(listOf(second, fill), state.savedColors)

        // 移除后列表与磁盘同步，正在使用的壁纸保持不动
        state.removeSavedColor(fill)
        assertEquals(listOf(second), state.savedColors)
        assertEquals(listOf(second), settings.load().savedColors)
        assertEquals(second, settings.load().colorFill)
    }

    @Test
    fun `添加并应用后我的页能在色彩分类看到`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-color-grid").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val settings = db.settingsStore
        val state = LucernaState(paths, settings, library, actions, Importer(paths, library))

        state.addAndApplyColor(ColorFill(kind = ColorKind.SOLID, colors = listOf(Rgb(0.2f, 0.4f, 0.8f))))

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 只有色彩时默认停在「图片」，切到「色彩」才能看到
        onNodeWithText("图片 0").assertExists()
        onNodeWithText("色彩 1").performClick()
        onNodeWithText("使用中").assertExists()

        // 编辑会回到色彩页继续调色
        state.editColor(ColorFill(kind = ColorKind.STEPS, steps = 6))
        assertEquals(PanelTab.COLOR, state.tab)
        assertEquals(ColorKind.STEPS, state.colorDraft.kind)
        assertEquals(6, state.colorDraft.steps)
    }
    @Test
    fun `设置页可以调整视频播放速度`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-video-speed").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        val state = LucernaState(
            paths = paths,
            settingsStore = db.settingsStore,
            libraryStore = library,
            actions = actions,
            importer = Importer(paths, library),
        )
        state.selectTab(PanelTab.SETTINGS)

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        onNodeWithText("播放速度").assertExists()
        onNodeWithContentDescription("视频播放速度").performTouchInput {
            click(percentOffset(0.75f, 0.5f))
        }

        assertEquals(2f, state.settings.videoSpeed)
        assertEquals(listOf(2f), actions.videoSpeeds)
    }

    @Test
    fun `导入页文件右键可以预览和在系统中定位`() = runComposeUiTest {
        val root = Files.createTempDirectory("lucerna-ui-import-menu").toFile()
        val paths = AppPaths.at(root).also { it.ensure() }
        val actions = RecordingActions()
        val db = LucernaDb.inMemory()
        val library = db.libraryStore
        var previewed: PreviewRequest? = null
        val state = LucernaState(
            paths = paths,
            settingsStore = db.settingsStore,
            libraryStore = library,
            actions = actions,
            importer = Importer(paths, library),
            onPreviewRequest = { previewed = it },
        )
        val browseDir = Files.createTempDirectory("lucerna-ui-import-files").toFile()
        val photo = File(browseDir, "photo.png").apply { writeText("x") }
        state.rememberImportDir(browseDir)
        state.selectTab(PanelTab.IMPORT)

        setContent {
            LucernaTheme {
                LucernaPanel(
                    state = state,
                    modifier = Modifier.size(392.dp, 736.dp),
                )
            }
        }

        // 文件夹没有右键菜单，文件才有
        onNodeWithText("photo.png").performMouseInput { rightClick() }
        onNodeWithText("预览").assertExists()
        onNodeWithText("打开文件位置").assertExists()

        onNodeWithText("预览").performClick()
        assertEquals(photo.absolutePath, previewed?.item?.path)
        assertEquals(WallpaperType.IMAGE, previewed?.item?.type)

        onNodeWithText("photo.png").performMouseInput { rightClick() }
        onNodeWithText("打开文件位置").performClick()
        assertEquals(listOf(photo.absolutePath), actions.revealedPaths)
    }
}

private class RecordingActions : WallpaperActions {
    val appliedItems = mutableListOf<WallpaperItem>()
    val appliedColors = mutableListOf<ColorFill>()
    val trayIconStyles = mutableListOf<TrayIconStyle>()
    val revealedPaths = mutableListOf<String>()
    val videoSpeeds = mutableListOf<Float>()

    override fun applyLibrary(item: WallpaperItem) {
        appliedItems += item
    }

    override fun applyColor(fill: ColorFill) {
        appliedColors += fill
    }

    override fun setVolume(volume: Float) = Unit
    override fun setAudioEnabled(enabled: Boolean) = Unit

    override fun setVideoPlaybackSpeed(speed: Float) {
        videoSpeeds += speed
    }

    override fun setDisabled(disabled: Boolean) = Unit
    override fun setLaunchAtLogin(enabled: Boolean) = Unit
    override fun setScreensaverWallpaper(enabled: Boolean) = Unit
    override fun setAllSpacesShared(enabled: Boolean) = Unit
    override fun setTrayIconStyle(style: TrayIconStyle) {
        trayIconStyles += style
    }
    override fun openWallpaperSettings() = Unit
    override fun testScreensaver() = Unit
    override fun openFileLocation(path: String) {
        revealedPaths += path
    }
    override fun openDirectory(path: String) = Unit
    override fun quit() = Unit
}
