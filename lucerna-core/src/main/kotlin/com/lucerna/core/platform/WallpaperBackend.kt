package com.lucerna.core.platform

/** 把一张图片设为系统桌面壁纸。 */
interface WallpaperBackend {
    val name: String

    /** 成功返回 true。路径必须是绝对路径。 */
    fun applyImage(path: String): Boolean

    /**
     * 播放视频壁纸。[posterPath] 只在桌面层不可用时用作静态兜底。
     *
     * 默认实现没有动态桌面能力，直接设置封面。
     */
    fun applyVideo(
        videoPath: String,
        posterPath: String,
        volume: Float,
        muted: Boolean,
        speed: Float,
        paused: Boolean,
        isStale: () -> Boolean = { false },
    ): Boolean = if (isStale()) true else applyImage(posterPath)

    /** 视频壁纸正在播放时，实时更新音量 / 倍速 / 暂停。 */
    fun updateVideoPlayback(volume: Float, muted: Boolean, speed: Float, paused: Boolean) = Unit
}
