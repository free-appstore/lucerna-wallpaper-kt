package com.lucerna.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediampPlayerFactoryLoader
import org.openani.mediamp.playUri
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 在打开预览窗口之前先读一次视频的显示宽高。
 *
 * 先探测再开窗，窗口一次就能按视频比例出现，不会先按 16:9 打开再跳一下；
 * 代价是要等播放器加载媒体信息（通常几百毫秒），调用方需要允许这段延迟。
 *
 * 读不到（格式不支持 / 超时）返回 null，调用方退回占位比例，等实际播放器回报后再校正。
 */
suspend fun probeVideoSize(
    path: String,
    timeout: Duration = 5.seconds,
): Pair<Int, Int>? {
    val player = runCatching {
        MediampPlayerFactoryLoader.first().create(Any(), Dispatchers.Main)
    }.getOrNull() ?: return null
    return try {
        withTimeoutOrNull(timeout) {
            // 直接把本地路径交给 mpv；playWhenReady = false：只加载媒体信息，不出声、不开始播放
            runCatching { player.playUri(path, playWhenReady = false) }
            val properties = player.mediaProperties.first { properties ->
                (properties?.videoWidth ?: 0) > 0 && (properties?.videoHeight ?: 0) > 0
            } ?: return@withTimeoutOrNull null
            val width = properties.videoWidth ?: return@withTimeoutOrNull null
            val height = properties.videoHeight ?: return@withTimeoutOrNull null
            width to height
        }
    } finally {
        runCatching { player.close() }
    }
}
