package com.lucerna.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lucerna.app.platform.*
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.ui.panel.LucernaPanel
import com.lucerna.ui.preview.PreviewWindowContent
import com.lucerna.ui.state.PanelTab
import com.lucerna.ui.theme.LucernaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.time.Duration.Companion.seconds

private const val PANEL_WIDTH = 392
/** 高度正好容纳「色彩」页：头部 66 + 分页 36 + 上下 12 + 页内容 610。 */
private const val PANEL_HEIGHT = 736
private const val PREVIEW_WIDTH = 1040
private const val PREVIEW_HEIGHT = 700

fun main(args: Array<String>) {
    // 先设置再初始化 AWT：隐藏 Dock 图标；托盘使用彩色实心图标
    System.setProperty("apple.awt.UIElement", "true")
    System.setProperty("apple.awt.enableTemplateImages", "false")

    if (args.contains("--detect-fullscreen")) {
        val covered = MacFullscreenDetector.fullscreenScreens()
        MacFullscreenDetector.screenBounds().forEachIndexed { index, bounds ->
            println("[Lucerna] screen[$index]=$bounds fullscreen=${covered.any { it == bounds }}")
        }
        println(
            "[Lucerna] pointer-screen-fullscreen=${MacFullscreenDetector.isPointerScreenFullscreen()} " +
                    "any-screen-fullscreen=${covered.isNotEmpty()}",
        )
        kotlin.system.exitProcess(0)
    }

    val fakeIndex = args.indexOf("--fake-fullscreen")
    if (fakeIndex >= 0) {
        val seconds = args.getOrNull(fakeIndex + 1)?.toIntOrNull() ?: 20
        val screen = args.getOrNull(fakeIndex + 2)?.toIntOrNull() ?: 0
        FakeFullscreenWindow.show(seconds, screen)
        kotlin.system.exitProcess(0)
    }

    if (args.contains("--enable-shared")) {
        println("[Lucerna] enable-shared=${MacSharedWallpaper.ensure()}")
        kotlin.system.exitProcess(0)
    }

    val applyAllIndex = args.indexOf("--apply-all-spaces")
    if (applyAllIndex >= 0) {
        val path = args.getOrNull(applyAllIndex + 1)
        println("[Lucerna] apply-all-spaces=${path?.let { MacWallpaperStore.applyToAllSpaces(it) } ?: false}")
        kotlin.system.exitProcess(0)
    }

    if (args.contains("--smoke-wallpaper") || args.contains("--smoke-fullscreen")) {
        val ok = WallpaperSmoke.run(simulateFullscreen = args.contains("--smoke-fullscreen"))
        kotlin.system.exitProcess(if (ok) 0 else 1)
    }

    val seedIndex = args.indexOf("--seed-dir")
    if (seedIndex >= 0 && seedIndex + 1 < args.size) {
        SeedDemo.run(args[seedIndex + 1])
        kotlin.system.exitProcess(0)
    }

    val smoke = System.getenv("LUCERNA_SMOKE") == "1"

    application {
        val environment = remember { AppEnvironment() }
        // 没有系统托盘时（少数 Linux 桌面）至少先把面板显示出来
        val panelStartsVisible = smoke || !java.awt.SystemTray.isSupported()
        var panelVisible by remember { mutableStateOf(panelStartsVisible) }
        // 面板的展开状态（用户意图）；panelVisible 决定窗口是否还存在
        var panelOpen by remember { mutableStateOf(panelStartsVisible) }
        val panelProgress = remember { Animatable(if (panelStartsVisible) 1f else 0f) }
        val panelScope = rememberCoroutineScope()
        var panelShownAt by remember { mutableStateOf(0L) }
        var panelHasFocused by remember { mutableStateOf(false) }
        // 面板的 AWT 窗口。窗口隐藏时内容不会重组，所以「抢焦点」不能放在
        // 窗口内容里的 LaunchedEffect(panelVisible) 里（第二次打开时它不会重跑），
        // 这里保存窗口引用，由 showPanel 每次打开时主动请求焦点。
        var panelWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var previewItem by remember { mutableStateOf<WallpaperItem?>(null) }
        val dismissGuard = remember { DismissGuard() }
        val state = remember {
            environment.createState { request -> previewItem = request.item }
        }
        val initialBounds = remember { PanelPlacement.panelBounds(PANEL_WIDTH, PANEL_HEIGHT) }
        val panelWindowState = rememberWindowState(
            position = WindowPosition.Absolute(initialBounds.x.dp, initialBounds.y.dp),
            size = DpSize(PANEL_WIDTH.dp, PANEL_HEIGHT.dp),
        )

        fun showPanel() {
            val bounds = PanelPlacement.panelBounds(PANEL_WIDTH, PANEL_HEIGHT)
            panelWindowState.position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp)
            panelWindowState.size = DpSize(bounds.width.dp, bounds.height.dp)
            panelShownAt = System.currentTimeMillis()
            val wasVisible = panelVisible
            panelOpen = true
            panelVisible = true
            panelHasFocused = false
            panelScope.launch {
                // 窗口刚出现时从 0 开始；从收起动画中途再打开则接着当前进度走
                if (!wasVisible) panelProgress.snapTo(0f)
                panelProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                )
            }
            // 每次打开都要重新抢焦点（窗口隐藏时 isVisible=false，重新显示后
            // macOS 不会自动把它变成 key window；不成为 key window 就收不到失焦事件，
            // 点面板外面也就关不掉了）。
            panelScope.launch {
                repeat(10) {
                    val window = panelWindow
                    if (window == null) {
                        delay(60)
                        return@repeat
                    }
                    if (window.isShowing) {
                        MacNative.activateApp()
                        window.toFront()
                        window.requestFocus()
                        window.requestFocusInWindow()
                    }
                    delay(60)
                    if (window.isFocused && panelOpen) {
                        panelHasFocused = true
                        return@launch
                    }
                    if (!panelOpen) return@launch
                }
            }
        }

        /** 收起面板：缩放 / 透明度 1 -> 0，动画结束后才真正隐藏窗口（不退出程序）。 */
        fun hidePanel() {
            if (!panelOpen && !panelVisible) return
            panelOpen = false
            panelScope.launch {
                panelProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing),
                )
                // 动画途中又打开了面板时保持窗口存在
                if (!panelOpen) panelVisible = false
            }
        }

        DisposableEffect(Unit) {
            val tray = TrayController(
                initialStyle = environment.settingsStore.load().trayIconStyle,
                onToggle = {
                    // 点击状态栏：已打开就收起；刚因失焦收起时忽略这次点击
                    if (panelOpen) {
                        dismissGuard.markHidden()
                        hidePanel()
                    } else if (!dismissGuard.consumeRecent()) {
                        showPanel()
                    }
                },
            )
            val installed = tray.install()
            if (!installed) {
                panelOpen = true
                panelVisible = true
                panelScope.launch { panelProgress.snapTo(1f) }
            }
            environment.tray = tray
            environment.quitAction = { exitApplication() }
            println("[Lucerna] started backend=${environment.backend.name} tray=$installed")

            onDispose {
                println("[Lucerna] application disposed")
                tray.remove()
                environment.shutdown()
            }
        }

        // 面板窗口始终存在于组合中，只用 visible 控制显隐，
        // 这样收起面板时应用不会因为「没有窗口」而退出。
        // 另外保留一个 1×1 的全透明窗口，保证启动时（面板尚未展开）进程不会退出。
        key("keeper") {
            Window(
                onCloseRequest = {},
                state = rememberWindowState(
                    size = DpSize(1.dp, 1.dp),
                    position = WindowPosition(0.dp, 0.dp),
                ),
                visible = true,
                title = "Lucerna",
                undecorated = true,
                transparent = true,
                resizable = false,
                focusable = false,
                alwaysOnTop = false,
            ) {
                LaunchedEffect(window) {
                    window.opacity = 0f
                }
                Box(Modifier.size(1.dp))
            }
        }

        key("panel") {
            Window(
                onCloseRequest = { hidePanel() },
                state = panelWindowState,
                visible = panelVisible,
                title = "Lucerna",
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                focusable = true,
                onPreviewKeyEvent = { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        hidePanel()
                        true
                    } else {
                        false
                    }
                },
            ) {
                val windowScope = rememberCoroutineScope()
                LaunchedEffect(window) {
                    panelWindow = window
                    window.background = java.awt.Color(0, 0, 0, 0)
                    window.minimumSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)
                    window.maximumSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)
                    window.isAlwaysOnTop = true
                    window.addWindowFocusListener(object : WindowFocusListener {
                        override fun windowGainedFocus(event: WindowEvent?) {
                            panelHasFocused = true
                        }

                        override fun windowLostFocus(event: WindowEvent?) {
                            if (previewItem != null || !panelOpen || !panelHasFocused) return
                            // 切换瞬间可能只是短暂失焦：稍等一下，确认确实没有焦点再收起
                            windowScope.launch {
                                delay(120)
                                val settled = System.currentTimeMillis() - panelShownAt > 250
                                if (settled && panelOpen && !window.isFocused) {
                                    dismissGuard.markHidden()
                                    hidePanel()
                                }
                            }
                        }
                    })
                }
                LucernaTheme {
                    // 展开 / 收起：缩放与透明度一起从 0 到 1
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val progress = panelProgress.value
                                scaleX = progress
                                scaleY = progress
                                alpha = progress
                                // 从面板顶部水平居中的位置展开 / 收起
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            },
                    ) {
                        LucernaPanel(state, Modifier.fillMaxSize())
                    }
                }
            }
        }

        previewItem?.let { item ->
            key("preview", item.id) {
                Window(
                    onCloseRequest = { previewItem = null },
                    state = rememberWindowState(size = DpSize(PREVIEW_WIDTH.dp, PREVIEW_HEIGHT.dp)),
                    title = "预览 - ${item.name}",
                    undecorated = true,
                    transparent = true,
                    resizable = true,
                    alwaysOnTop = true,
                    focusable = true,
                    onPreviewKeyEvent = { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            previewItem = null
                            true
                        } else {
                            false
                        }
                    },
                ) {
                    LaunchedEffect(window) {
                        window.background = java.awt.Color(0, 0, 0, 0)
                        window.minimumSize = Dimension(560, 400)
                        window.setBounds(PanelPlacement.centeredBounds(PREVIEW_WIDTH, PREVIEW_HEIGHT))
                        window.toFront()
                        window.requestFocus()
                        MacNative.activateApp()
                    }
                    LucernaTheme {
                        PreviewWindowContent(
                            item = item,
                            volume = state.settings.volume,
                            muted = !state.settings.audioEnabled,
                            onClose = { previewItem = null },
                        )
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            when (System.getenv("LUCERNA_SMOKE_TAB")?.lowercase()) {
                "color" -> state.selectTab(PanelTab.COLOR)
                "import" -> state.selectTab(PanelTab.IMPORT)
                "settings" -> state.selectTab(PanelTab.SETTINGS)
                "mine" -> state.selectTab(PanelTab.MINE)
            }
            when (System.getenv("LUCERNA_SMOKE_PREVIEW")?.lowercase()) {
                "video" -> state.items.firstOrNull { it.type == WallpaperType.VIDEO }?.let {
                    previewItem = it
                    panelVisible = false
                }

                "image" -> state.items.firstOrNull { it.type == WallpaperType.IMAGE }?.let {
                    previewItem = it
                    panelVisible = false
                }

                "first" -> state.items.firstOrNull()?.let {
                    previewItem = it
                    panelVisible = false
                }
            }
            when (System.getenv("LUCERNA_SMOKE_TRAY")?.lowercase()) {
                "mono" -> state.setTrayIconStyle(TrayIconStyle.MONO)
                "color" -> state.setTrayIconStyle(TrayIconStyle.COLOR)
            }
            when (System.getenv("LUCERNA_SMOKE_APPLY")?.lowercase()) {
                "video" -> state.items.firstOrNull { it.type == WallpaperType.VIDEO }?.let {
                    panelVisible = false
                    state.applyItem(it)
                }

                "image" -> state.items.firstOrNull { it.type == WallpaperType.IMAGE }?.let {
                    panelVisible = false
                    state.applyItem(it)
                }

                "first" -> state.items.firstOrNull()?.let {
                    panelVisible = false
                    state.applyItem(it)
                }
            }
            if (smoke) {
                delay(1.5.seconds)
                println("[Lucerna] SMOKE_PANEL_READY")
                if (System.getenv("LUCERNA_SMOKE_REOPEN") == "1") {
                    delay(1.2.seconds)
                    dismissGuard.markHidden()
                    hidePanel()
                    delay(1.4.seconds)
                    showPanel()
                    delay(0.6.seconds)
                    println("[Lucerna] SMOKE_REOPENED")
                }
            }
        }
    }
}
