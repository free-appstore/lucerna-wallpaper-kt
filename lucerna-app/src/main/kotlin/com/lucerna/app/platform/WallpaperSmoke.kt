package com.lucerna.app.platform

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Rgb
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.platform.DesktopOs
import com.lucerna.core.render.ColorRenderer
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 冒烟测试：真的换壁纸，回读校验后恢复。
 *
 * 桌面壁纸层在工作时（macOS 新方案）校验：
 * 1. 壁纸层里当前显示的图 = 新图；
 * 2. 每块屏幕都有一个壁纸窗口，**层级在系统壁纸之上、桌面图标之下**（-2147483622 左右）；
 * 3. 连续换三张图（含换回同一张）都不需要重启任何系统服务。
 *
 * 走系统壁纸兜底时，则回读壁纸库里每个桌面的配置与每块屏幕的壁纸路径。
 * [simulateFullscreen] 为 true 时另起一个子进程显示假的全屏窗口，模拟微信全屏的情形。
 */
object WallpaperSmoke {

    private const val SCREEN_WAIT_MS = 8000L
    private const val FULLSCREEN_WAIT_MS = 10_000L
    private const val FAKE_FULLSCREEN_SECONDS = 120

    fun run(simulateFullscreen: Boolean = false): Boolean {
        val paths = AppPaths.system().also { it.ensure() }
        val backend = PlatformWallpapers.create()
        val macos = DesktopOs.current == DesktopOs.MACOS
        val screens = if (macos) MacNative.screenCount() else 0
        val layerBackend = backend as? MacWallpaperBackend
        val originalScreenImages = (0 until screens).map { MacNative.desktopImagePath(it) }
        val desktopLevel = MacNative.desktopLayerLevel()
        val iconLevel = MacNative.desktopIconLevel()
        // 桌面层模式：记下原来层里那张图，结束时画回去（全程不碰系统壁纸）
        val originalLayerImage = layerBackend?.layerImagePath
        val storeBackup = MacWallpaperStore.snapshot()
        val originalStoreImages = MacWallpaperStore.configuredImagePaths()
        println(
            "[Lucerna] smoke wallpaper: backend=${backend.name}, screens=$screens, " +
                "store-entries=${originalStoreImages.size}, " +
                "desktopLevel=$desktopLevel iconLevel=$iconLevel " +
                "simulate-fullscreen=$simulateFullscreen",
        )

        val images = listOf(
            render(paths, "smoke-a.png", ColorFill(ColorKind.STEPS, ColorFill.DEFAULT_COLORS, 45f, 6)),
            render(paths, "smoke-b.png", ColorFill(ColorKind.STEPS, listOf(Rgb(0.1f, 0.5f, 1f), Rgb(0.02f, 0.05f, 0.3f)), 135f, 4)),
            null,
        )
        if (images[0] == null || images[1] == null) {
            println("[Lucerna] smoke wallpaper: render failed")
            return false
        }
        // 第三轮换回第一张，验证重复设置同样的图也没问题
        val rounds = listOf(images[0]!!, images[1]!!, images[0]!!)

        val fake = if (simulateFullscreen) startFakeFullscreen() else null
        var fullscreenDetected = false
        val results = ArrayList<Boolean>()
        try {
            if (simulateFullscreen) {
                fullscreenDetected = waitForFullscreen().isNotEmpty()
                println("[Lucerna] smoke wallpaper: simulated fullscreen=$fullscreenDetected")
            }

            rounds.forEachIndexed { index, image ->
                val startedAt = System.currentTimeMillis()
                val applied = backend.applyImage(image.absolutePath)
                val elapsed = System.currentTimeMillis() - startedAt

                // 桌面壁纸层模式：校验层里的图 + 每块屏幕都有一个层级正确的壁纸窗口
                val layer = layerBackend?.takeIf { it.layerActive }
                val roundOk: Boolean
                val detail: String
                if (layer != null) {
                    // WindowServer 记录窗口层级有一点点延迟，等一小会儿再数
                    val onLevel = waitForLayerWindows(screens, desktopLevel)
                    val layerImage = layer.layerImagePath
                    // 垫底系统壁纸也要是同一张（当前 Space）
                    val underlay = waitForSystemWallpaper(image, screens)
                    // 壁纸库里每个 Space / 每块屏幕的系统壁纸也要是同一张
                    val (storeMatched, storeTotal) = waitForStore(image)
                    detail = "layerWindows=$onLevel/${layer.layerWindowCount} level=$desktopLevel " +
                        "underlay=$underlay/$screens store=$storeMatched/$storeTotal " +
                        "image=${layerImage?.let { File(it).name }}"
                    roundOk = applied &&
                        screens == layer.layerWindowCount &&
                        onLevel >= screens &&
                        underlay >= screens &&
                        storeTotal > 0 && storeMatched == storeTotal &&
                        layerImage != null &&
                        samePath(layerImage, image.absolutePath)
                } else {
                    val screensMatched = waitForScreens(image, screens)
                    // 壁纸库同步在后台线程里做（还要重启 WallpaperAgent），等它落盘再比对
                    val (storeMatched, storeTotal) = waitForStore(image)
                    detail = "desktops=$storeMatched/$storeTotal screens=$screensMatched/$screens"
                    roundOk = applied &&
                        (screens == 0 || screensMatched == screens) &&
                        storeTotal > 0 && storeMatched == storeTotal
                }
                results.add(roundOk)
                println(
                    "[Lucerna] smoke round ${index + 1}: applied=$applied layer=${layer != null} " +
                        "$detail elapsed=${elapsed}ms ${if (roundOk) "OK" else "FAIL"}",
                )
            }
        } catch (error: Exception) {
            println("[Lucerna] smoke wallpaper failed: ${error.message}")
            results.add(false)
        } finally {
            fake?.let { stop(it) }
            layerBackend?.let { backend ->
                // 不管壁纸层现在显示没显示（有屏幕全屏时窗口是藏起来的），
                // 都要先把后台的系统壁纸同步排空，否则它会把下面的恢复结果覆盖掉
                backend.awaitStoreSync()
                originalLayerImage?.let { backend.applyImage(it) }
                backend.awaitStoreSync()
            }
            restore(storeBackup, originalScreenImages, screens)
        }

        val finalScreenImages = (0 until screens).map { MacNative.desktopImagePath(it) }
        // 测试开始前层里没有图（第一次启动）时不需要恢复
        val layerRestored = when {
            layerBackend == null -> true
            originalLayerImage == null -> true
            else -> layerBackend.layerImagePath?.let { samePath(it, originalLayerImage) } == true
        }
        val screensRestored = (0 until screens).count {
            samePath(finalScreenImages[it], originalScreenImages[it])
        }
        val finalStoreImages = MacWallpaperStore.configuredImagePaths()
        val smokeLeft = finalStoreImages.count { image -> rounds.any { samePath(image, it.absolutePath) } }
        val foreign = finalStoreImages.count { image -> originalStoreImages.none { samePath(image, it) } }
        val storeRestored = smokeLeft == 0 && foreign == 0
        val restoreOk = screensRestored == screens && storeRestored && layerRestored
        val detectedOk = !simulateFullscreen || fullscreenDetected
        val ok = results.isNotEmpty() && results.all { it } && restoreOk && detectedOk

        println(
            "[Lucerna] SMOKE_WALLPAPER rounds=${results.count { it }}/${results.size} " +
                "restored-screens=$screensRestored/$screens restored-store=$storeRestored " +
                "foreign-entries=$foreign " +
                "fullscreen=$fullscreenDetected ${if (ok) "PASS" else "FAIL"}",
        )
        return ok
    }

    private fun render(paths: AppPaths, name: String, fill: ColorFill): File? {
        val target = paths.renderDir.resolve(name).toFile()
        return ColorRenderer.writePng(fill, 1600, 900, target)
    }

    /** 等壁纸库里每个 Space / 屏幕的系统壁纸也变成目标图，返回（匹配数, 总数）。 */
    private fun waitForStore(image: File): Pair<Int, Int> {
        val deadline = System.currentTimeMillis() + 6000
        var matched = 0
        var total = 0
        while (System.currentTimeMillis() < deadline) {
            val images = MacWallpaperStore.configuredImagePaths()
            total = images.size
            matched = images.count { samePath(it, image.absolutePath) }
            if (total > 0 && matched == total) return matched to total
            Thread.sleep(200)
        }
        return matched to total
    }

    /** 等系统壁纸（垫底那张）也变成目标图，返回匹配的屏幕数。 */
    private fun waitForSystemWallpaper(image: File, screens: Int): Int {
        val deadline = System.currentTimeMillis() + 3000
        var matched = 0
        while (System.currentTimeMillis() < deadline) {
            matched = (0 until screens).count { samePath(MacNative.desktopImagePath(it), image.absolutePath) }
            if (matched >= screens) return matched
            Thread.sleep(150)
        }
        return matched
    }

    /** 等窗口服务器把壁纸层的窗口都登记到正确层级上，返回达到该层级的窗口数。 */
    private fun waitForLayerWindows(screens: Int, level: Int): Int {
        val deadline = System.currentTimeMillis() + 3000
        var matched = 0
        while (System.currentTimeMillis() < deadline) {
            matched = MacFullscreenDetector.ownWindows().count { it.second == level }
            if (matched >= screens) return matched
            Thread.sleep(120)
        }
        return matched
    }

    /** 等所有屏幕都回读成这张图，返回最终匹配的屏幕数。 */
    private fun waitForScreens(image: File, screens: Int): Int {
        if (screens == 0) return 0
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        var matched = 0
        while (System.currentTimeMillis() < deadline) {
            matched = (0 until screens).count { samePath(MacNative.desktopImagePath(it), image.absolutePath) }
            if (matched == screens) return matched
            Thread.sleep(200)
        }
        return matched
    }

    /** 恢复壁纸库（所有桌面）与每块屏幕原来看得见的壁纸。 */
    private fun restore(storeBackup: ByteArray?, originalScreenImages: List<String?>, screens: Int) {
        storeBackup?.let { MacWallpaperStore.restore(it) }
        Thread.sleep(1200)
        // 壁纸服务重启后偶尔会先按显示器默认值重绘，逐屏比对，不一致才设回原图
        originalScreenImages.forEachIndexed { index, path ->
            if (!path.isNullOrBlank() && !samePath(MacNative.desktopImagePath(index), path)) {
                MacNative.applyDesktopImageToScreen(index, path)
            }
        }
        if (screens > 0) {
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val matched = (0 until screens).count {
                    samePath(MacNative.desktopImagePath(it), originalScreenImages.getOrNull(it))
                }
                if (matched == screens) return
                Thread.sleep(200)
            }
        }
    }

    /** 等子进程的全屏窗口出现，最多等 [FULLSCREEN_WAIT_MS]。 */
    private fun waitForFullscreen(): List<Rectangle> {
        val deadline = System.currentTimeMillis() + FULLSCREEN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val screens = MacFullscreenDetector.fullscreenScreens()
            if (screens.isNotEmpty()) return screens
            Thread.sleep(250)
        }
        return emptyList()
    }

    /** 另起一个 JVM 显示假的全屏窗口，模拟微信之类的全屏 app。 */
    private fun startFakeFullscreen(screenIndex: Int = 0): Process? {
        val classpath = System.getProperty("java.class.path").orEmpty()
        if (classpath.isBlank()) return null
        val java = ProcessHandle.current().info().command().orElse(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
        )
        return runCatching {
            ProcessBuilder(
                java,
                "-cp",
                classpath,
                "com.lucerna.app.MainKt",
                "--fake-fullscreen",
                FAKE_FULLSCREEN_SECONDS.toString(),
                screenIndex.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull()
    }

    private fun stop(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
            }
        }
    }

    private fun samePath(actual: String?, expected: String?): Boolean {
        if (actual.isNullOrBlank() || expected.isNullOrBlank()) {
            return actual.isNullOrBlank() && expected.isNullOrBlank()
        }
        return runCatching {
            File(actual).canonicalPath == File(expected).canonicalPath
        }.getOrElse { false }
    }
}
