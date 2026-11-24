package com.lucerna.app.platform

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDate
import com.dd.plist.NSDictionary
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.lucerna.core.platform.DesktopOs
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date

/**
 * macOS 壁纸库（`com.apple.wallpaper/Store/Index.plist`）。
 *
 * NSWorkspace 只能改「当前正在显示的那个 Space」，调度中心里其它桌面（包括全屏 app
 * 占着的屏幕上的桌面）会保持旧壁纸。要做到「改壁纸时所有屏幕、所有桌面一起换」，
 * 必须直接改壁纸库里每个 Space / 每块屏幕的配置，然后重启 WallpaperAgent 让它重新读取。
 *
 * 流程：停掉壁纸服务 → 读它最后写下的配置 → 把所有桌面改成新图 → 原子写回 → 等它
 * 重新启动 → 回读校验；校验不通过就整体重来（最多 [MAX_SYNC_ATTEMPTS] 次）。
 */
internal object MacWallpaperStore {

    private const val IMAGE_PROVIDER = "com.apple.wallpaper.choice.image"

    /** 壁纸库用这个字符串表示「没有值」，写回时要原样保留。 */
    private const val NULL_VALUE = "\$null"

    private const val MAX_SYNC_ATTEMPTS = 3
    private const val AGENT_RESTART_TIMEOUT_MS = 5000L
    private const val AGENT_SETTLE_MS = 700L

    private val storeDir: File
        get() = File(
            System.getProperty("user.home"),
            "Library/Application Support/com.apple.wallpaper/Store",
        )

    private val storeFile: File
        get() = File(storeDir, "Index.plist")

    private val backupFile: File
        get() = File(storeDir, "Index.plist.lucerna-backup")

    private val tempFile: File
        get() = File(storeDir, "Index.plist.lucerna-tmp")

    /**
     * 把图片写进壁纸库里所有 Space / 所有屏幕的桌面配置，并让壁纸服务重新读取。
     *
     * 返回 true 表示回读确认「每个桌面都已经是这张图」。
     */
    fun applyToAllSpaces(imagePath: String): Boolean {
        if (DesktopOs.current != DesktopOs.MACOS) return false
        val store = storeFile
        if (!store.isFile) return false
        val target = runCatching { File(imagePath).canonicalPath }.getOrElse { imagePath }

        return runCatching {
            // 快路径：所有桌面已经是这张图，不必重启壁纸服务（也就没有灰屏）
            val (matchedAlready, totalAlready) = status(store, target)
            if (totalAlready == 0) return false
            if (matchedAlready == totalAlready) return true

            var backupTaken = false
            repeat(MAX_SYNC_ATTEMPTS) { attempt ->
                val root = PropertyListParser.parse(store) as? NSDictionary ?: return false
                val report = applyToRoot(root, target)
                if (report.total == 0) return false
                if (!backupTaken) {
                    Files.copy(store.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    backupTaken = true
                }
                // 先写盘再重启壁纸服务：服务启动时会读这个文件，
                // 反过来（先杀再写）会被 launchd 立刻拉起的新进程读到旧内容
                writeAtomically(BinaryPropertyListWriter.writeToArray(root))
                restartWallpaperAgent()

                val (matched, total) = status(store, target)
                if (matched == total) {
                    println("[Lucerna] all desktops updated ($matched/$total entries, attempt ${attempt + 1})")
                    return true
                }
                // 服务退出时把内存里的旧配置写回来了，重来一次
                println("[Lucerna] desktops not all updated ($matched/$total), retrying")
            }
            println("[Lucerna] wallpaper store sync failed after $MAX_SYNC_ATTEMPTS attempts")
            false
        }.getOrElse { error ->
            println("[Lucerna] wallpaper store failed: ${error.message}")
            restoreBackup()
            restartWallpaperAgent()
            false
        }
    }

    /** 补丁结果：改动了几条、总共有几条桌面配置。 */
    internal data class PatchReport(val changed: Int, val total: Int)

    /** 把图片写进 root 里所有桌面配置，不落盘（方便单测）。 */
    internal fun applyToRoot(root: NSDictionary, imagePath: String): PatchReport {
        val entries = desktopEntries(root)
        if (entries.isEmpty()) return PatchReport(0, 0)
        val data = configurationData(imagePath)
        val now = NSDate(Date())
        var changed = 0
        entries.forEach { entry ->
            if (entry.applyImage(data)) changed++
            entry.desktop.put("LastSet", now)
            entry.desktop.put("LastUse", now)
        }
        return PatchReport(changed, entries.size)
    }

    /** 读回 root 里每个桌面配置的图片路径，不落盘（方便单测）。 */
    internal fun imagePaths(root: NSDictionary): List<String> =
        desktopEntries(root).mapNotNull { it.imagePath() }

    /** 读回壁纸库里每个 Space / 屏幕的桌面图片路径，用于校验同步结果。 */
    fun configuredImagePaths(): List<String> {
        if (DesktopOs.current != DesktopOs.MACOS) return emptyList()
        val store = storeFile
        if (!store.isFile) return emptyList()
        return runCatching {
            val root = PropertyListParser.parse(store) as? NSDictionary ?: return emptyList()
            imagePaths(root)
        }.getOrElse { emptyList() }
    }

    /** 冒烟测试用：备份整个壁纸库文件。 */
    fun snapshot(): ByteArray? = runCatching {
        storeFile.takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    /** 冒烟测试用：把壁纸库恢复成之前备份的内容。 */
    fun restore(data: ByteArray): Boolean {
        if (DesktopOs.current != DesktopOs.MACOS) return false
        val expected = runCatching {
            imagePaths(PropertyListParser.parse(data) as NSDictionary).toSet()
        }.getOrElse { emptySet() }

        return runCatching {
            repeat(MAX_SYNC_ATTEMPTS) { attempt ->
                writeAtomically(data)
                restartWallpaperAgent()
                // 回读确认没有任何桌面还指向备份以外的图片
                val restored = configuredImagePaths().all { it in expected }
                if (restored) {
                    println("[Lucerna] wallpaper store restored (${data.size} bytes, attempt ${attempt + 1})")
                    return true
                }
                println("[Lucerna] wallpapers still referencing other images, retrying")
            }
            false
        }.getOrElse { error ->
            println("[Lucerna] wallpaper store restore failed: ${error.message}")
            false
        }
    }

    /** 壁纸库里已经有几个桌面配置（含已不存在的 Space，历史条目也一起改，避免残留旧图）。 */
    private fun status(store: File, target: String): Pair<Int, Int> {
        val root = runCatching { PropertyListParser.parse(store) as? NSDictionary }.getOrNull()
            ?: return 0 to 0
        val entries = desktopEntries(root)
        val matched = entries.count { entry ->
            val path = entry.imagePath() ?: return@count false
            runCatching { File(path).canonicalPath == target }.getOrElse { path == target }
        }
        return matched to entries.size
    }

    /**
     * 收集壁纸库里所有桌面配置：SystemDefault、AllSpacesAndDisplays、
     * 每块屏幕，以及每个 Space 的默认配置与分屏配置。
     */
    private fun desktopEntries(root: NSDictionary): List<DesktopEntry> {
        val entries = ArrayList<DesktopEntry>()
        fun visit(container: NSObject?) {
            val dict = container as? NSDictionary ?: return
            val desktop = dict.objectForKey("Desktop") as? NSDictionary ?: return
            val content = desktop.objectForKey("Content") as? NSDictionary ?: return
            entries.add(DesktopEntry(desktop, content))
        }
        visit(root.objectForKey("SystemDefault"))
        visit(root.objectForKey("AllSpacesAndDisplays"))
        (root.objectForKey("Displays") as? NSDictionary)?.values?.forEach { visit(it) }
        (root.objectForKey("Spaces") as? NSDictionary)?.values?.forEach { space ->
            val dict = space as? NSDictionary ?: return@forEach
            visit(dict.objectForKey("Default"))
            (dict.objectForKey("Displays") as? NSDictionary)?.values?.forEach { visit(it) }
        }
        return entries
    }

    /** 一份桌面配置：`Desktop` 用来写时间戳，`Content` 里放图片选择项。 */
    private class DesktopEntry(
        val desktop: NSDictionary,
        private val content: NSDictionary,
    ) {
        /** 返回是否真的改动了内容。 */
        fun applyImage(data: NSData): Boolean {
            val choices = content.objectForKey("Choices") as? NSArray
            if (choices == null) {
                content.put("Choices", NSArray(imageChoice(data)))
                resetOptions()
                return true
            }
            var changed = false
            var hasImageChoice = false
            for (index in 0 until choices.count()) {
                val choice = choices.objectAtIndex(index) as? NSDictionary ?: continue
                if (choice.imageProvider != IMAGE_PROVIDER) continue
                hasImageChoice = true
                val existing = (choice.objectForKey("Configuration") as? NSData)?.bytes()
                if (existing == null || !existing.contentEquals(data.bytes())) {
                    choice.put("Configuration", data)
                    changed = true
                }
            }
            // 这个桌面用的是动态壁纸 / 纯色等其它 provider，整体换成图片选择项
            if (!hasImageChoice) {
                content.put("Choices", NSArray(imageChoice(data)))
                resetOptions()
                return true
            }
            return changed
        }

        fun imagePath(): String? {
            val choices = content.objectForKey("Choices") as? NSArray ?: return null
            for (index in 0 until choices.count()) {
                val choice = choices.objectAtIndex(index) as? NSDictionary ?: continue
                if (choice.imageProvider != IMAGE_PROVIDER) continue
                return choice.imagePath()
            }
            return null
        }

        /** 换成图片后，旧的动态壁纸选项与轮播设置必须清掉，否则壁纸服务解析会出错。 */
        private fun resetOptions() {
            content.put("EncodedOptionValues", NSString(NULL_VALUE))
            content.put("Shuffle", NSString(NULL_VALUE))
        }
    }

    private fun imageChoice(data: NSData): NSDictionary = NSDictionary().apply {
        put("Configuration", data)
        put("Files", NSArray())
        put("Provider", NSString(IMAGE_PROVIDER))
    }

    private fun configurationData(imagePath: String): NSData {
        val url = fileUrl(imagePath)
        val configuration = NSDictionary().apply {
            put("type", NSString("imageFile"))
            put("url", NSDictionary().apply { put("relative", NSString(url)) })
        }
        return NSData(BinaryPropertyListWriter.writeToArray(configuration))
    }

    /** 壁纸库自己写的都是 `file:///...`，直接照抄，别用 Java 的 `file:/...` 形式。 */
    private fun fileUrl(path: String): String =
        File(path).toURI().toString().replaceFirst("file:/", "file:///")

    private val NSDictionary.imageProvider: String?
        get() = objectForKey("Provider")?.toJavaObject() as? String

    private fun NSDictionary.imagePath(): String? = runCatching {
        val raw = (objectForKey("Configuration") as? NSData)?.bytes() ?: return null
        val configuration = PropertyListParser.parse(raw) as? NSDictionary ?: return null
        val relative = (configuration.objectForKey("url") as? NSDictionary)
            ?.objectForKey("relative") as? NSString ?: return null
        filePath(relative.content)
    }.getOrNull()

    /** 壁纸库存的是 `file://` 地址（路径里有 %20 之类的转义），转回本地路径。 */
    private fun filePath(url: String): String {
        if (!url.startsWith("file:")) return url
        return runCatching { URI(url).path ?: url }.getOrElse { url }
    }

    private fun writeAtomically(data: ByteArray) {
        val temp = tempFile
        temp.writeBytes(data)
        Files.move(
            temp.toPath(),
            storeFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun restoreBackup() {
        runCatching {
            if (!backupFile.isFile) return
            Files.copy(backupFile.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 让壁纸服务重新读取壁纸库。
     *
     * 优先用 SIGHUP：它会原地重新加载配置，不会像终止进程那样把内存里的旧配置
     * 再写一遍文件（那样会把我们刚写的补丁冲掉）。
     */
    private fun restartWallpaperAgent() {
        val before = agentPid()
        if (exec("killall", "-HUP", "WallpaperAgent") && waitForNewAgent(before)) {
            Thread.sleep(AGENT_SETTLE_MS)
            return
        }
        // 不支持 HUP 的系统：退回终止进程，让 launchd 拉起后再读文件
        exec("killall", "WallpaperAgent")
        if (waitForNewAgent(before)) {
            Thread.sleep(AGENT_SETTLE_MS)
            return
        }
        val uid = runCatching {
            ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        if (!uid.isNullOrBlank()) {
            exec("launchctl", "kickstart", "-k", "gui/$uid/com.apple.wallpaper.agent")
            waitForNewAgent(before)
        }
        Thread.sleep(AGENT_SETTLE_MS)
    }

    private fun waitForNewAgent(before: String?): Boolean =
        waitUntil(AGENT_RESTART_TIMEOUT_MS) {
            val pid = agentPid()
            pid != null && pid != before
        }

    private fun agentPid(): String? = runCatching {
        ProcessBuilder("pgrep", "-x", "WallpaperAgent")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun exec(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrElse { false }
}
