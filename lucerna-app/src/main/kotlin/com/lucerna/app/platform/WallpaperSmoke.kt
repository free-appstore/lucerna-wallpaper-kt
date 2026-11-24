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
 * 冒烟测试：真的改系统壁纸，回读校验后恢复。
 *
 * 每次设置都必须满足两条：
 * 1. 壁纸库里**每个桌面**（每个屏幕、每个 Space，含调度中心里没显示出来的）都换成新图；
 * 2. **每块连接的屏幕**回读到的壁纸路径都是新图。
 *
 * 为了证明不是「一会儿行一会儿不行」，会连续换三张图（含换回同一张），每一轮都校验。
 * [simulateFullscreen] 为 true 时另起一个子进程显示假的全屏窗口，模拟微信全屏的情形。
 *
 * 只改变壁纸十几秒，结束后壁纸库与原壁纸都会恢复。
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
        val originalScreenImages = (0 until screens).map { MacNative.desktopImagePath(it) }
        val storeBackup = MacWallpaperStore.snapshot()
        val originalStoreImages = MacWallpaperStore.configuredImagePaths()
        println(
            "[Lucerna] smoke wallpaper: backend=${backend.name}, screens=$screens, " +
                "store-entries=${originalStoreImages.size}, simulate-fullscreen=$simulateFullscreen",
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
                val screensMatched = waitForScreens(image, screens)
                val (storeMatched, storeTotal) = countStore(image)
                val elapsed = System.currentTimeMillis() - startedAt
                val roundOk = applied &&
                    storeTotal > 0 && storeMatched == storeTotal &&
                    (screens == 0 || screensMatched == screens)
                results.add(roundOk)
                println(
                    "[Lucerna] smoke round ${index + 1}: applied=$applied " +
                        "desktops=$storeMatched/$storeTotal screens=$screensMatched/$screens " +
                        "elapsed=${elapsed}ms ${if (roundOk) "OK" else "FAIL"}",
                )
                if (!roundOk) {
                    val readback = (0 until screens).map { MacNative.desktopImagePath(it) }
                    println("[Lucerna] smoke round ${index + 1}: screens=$readback expected=${image.absolutePath}")
                }
            }
        } catch (error: Exception) {
            println("[Lucerna] smoke wallpaper failed: ${error.message}")
            results.add(false)
        } finally {
            fake?.let { stop(it) }
            restore(storeBackup, originalScreenImages, screens)
        }

        val finalScreenImages = (0 until screens).map { MacNative.desktopImagePath(it) }
        val screensRestored = (0 until screens).count {
            samePath(finalScreenImages[it], originalScreenImages[it])
        }
        val finalStoreImages = MacWallpaperStore.configuredImagePaths()
        val smokeLeft = finalStoreImages.count { image -> rounds.any { samePath(image, it.absolutePath) } }
        val foreign = finalStoreImages.count { image -> originalStoreImages.none { samePath(image, it) } }
        val storeRestored = smokeLeft == 0 && foreign == 0
        val restoreOk = screensRestored == screens && storeRestored
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

    /** (指向这张图的桌面配置数, 桌面配置总数)。 */
    private fun countStore(image: File): Pair<Int, Int> {
        val paths = MacWallpaperStore.configuredImagePaths()
        return paths.count { samePath(it, image.absolutePath) } to paths.size
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
