package com.lucerna.core.media

import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.platform.DesktopOs
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频壁纸的静态封面：从视频里抽一帧写成 PNG，缓存到 `renders/posters/<id>.png`。
 *
 * macOS 用系统自带的 Quick Look，其他平台尝试 ffmpeg；两者都不可用时返回 null。
 * 封面可以在导入后预先做好，这样点「设为壁纸」时不用再等抽帧。
 */
object VideoPoster {

    /** 同一个视频只抽一帧：导入后的预生成和「设为壁纸」可能同时进来，排队执行。 */
    private val locks = ConcurrentHashMap<String, Any>()

    /** 取封面文件；没有就生成一份。返回 null 表示抽取失败或文件不存在。 */
    fun ensure(paths: AppPaths, item: WallpaperItem): File? {
        val source = File(item.path)
        if (!source.isFile) return null
        val lock = locks.computeIfAbsent(item.id) { Any() }
        return synchronized(lock) { generate(paths, item, source) }
    }

    private fun generate(paths: AppPaths, item: WallpaperItem, source: File): File? {
        val dir = paths.renderDir.resolve("posters").toFile().apply { mkdirs() }
        val target = File(dir, "${item.id}.png")
        if (target.isFile && target.length() > 0) return target

        val ok = when (DesktopOs.current) {
            DesktopOs.MACOS -> runQuickLook(source, dir, target)
            else -> runFfmpegPoster(source, target)
        }
        return target.takeIf { ok && it.isFile && it.length() > 0 }
    }

    private fun runQuickLook(source: File, dir: File, target: File): Boolean {
        val commandOk = runCommand(
            "qlmanage", "-t", "-s", "1920", "-o", dir.absolutePath, source.absolutePath,
        )
        if (!commandOk) return false
        val generated = File(dir, source.name + ".png")
        if (!generated.isFile) return false
        return generated.renameTo(target) || runCatching {
            generated.copyTo(target, overwrite = true)
            generated.delete()
            true
        }.getOrElse { false }
    }

    private fun runFfmpegPoster(source: File, target: File): Boolean = runCommand(
        "ffmpeg", "-y", "-ss", "1", "-i", source.absolutePath, "-frames:v", "1", target.absolutePath,
    )

    private fun runCommand(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrElse { false }
}
