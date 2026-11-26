package com.lucerna.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactoryLoader
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.playUri
import kotlin.time.Duration.Companion.seconds

/**
 * 用 MediaMP 播放本地视频。
 *
 * 桌面端由 MediaMP 自带的 mpv / FFmpeg 运行时解码，MP4、MOV、MKV、WebM
 * 等常见容器都能直接播放，不依赖系统安装额外的播放器。
 *
 * @param volume 0..1 的相对音量
 * @param muted 是否静音，与 [volume] 独立，切回有声时能恢复原音量
 * @param loop 播放结束后是否从头循环
 * @param onVideoSize 播放器读到媒体信息后回报视频显示宽高（预览窗口据此调整窗口比例）
 */
@Composable
fun VideoSurface(
    path: String,
    modifier: Modifier = Modifier,
    volume: Float = 1f,
    muted: Boolean = true,
    loop: Boolean = true,
    onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val creation = remember(path) {
        runCatching { MediampPlayerFactoryLoader.first().create(Any(), Dispatchers.Main) }
    }
    val player = creation.getOrNull()
    if (player == null) {
        VideoUnavailable(
            message = creation.exceptionOrNull()?.message,
            modifier = modifier,
        )
        return
    }

    val loopState = rememberUpdatedState(loop)

    DisposableEffect(player) {
        onDispose { runCatching { player.close() } }
    }

    LaunchedEffect(
        key1 = player,
        key2 = path,
    ) {
        // 直接把本地路径交给 mpv：不再经过 file: URI，路径里的空格 / 特殊字符不会被转义成 %20
        runCatching { player.playUri(path) }
    }

    // 视频宽高要等播放器解析完媒体信息才知道：读到后回报一次，预览窗口据此调整窗口比例
    val currentOnVideoSize by rememberUpdatedState(onVideoSize)
    LaunchedEffect(player, path) {
        val properties = withTimeoutOrNull(10.seconds) {
            player.mediaProperties.first { properties ->
                (properties?.videoWidth ?: 0) > 0 && (properties?.videoHeight ?: 0) > 0
            }
        } ?: return@LaunchedEffect
        val width = properties.videoWidth ?: return@LaunchedEffect
        val height = properties.videoHeight ?: return@LaunchedEffect
        currentOnVideoSize(width, height)
    }

    LaunchedEffect(
        key1 = player,
        key2 = muted,
        key3 = volume,
    ) {
        applyAudio(player, muted, volume)
    }

    // 预览按原始比例完整显示
    LaunchedEffect(player) {
        val aspect = runCatching { player.features.get(VideoAspectRatio.Key) }.getOrNull()
        runCatching { aspect?.setMode(AspectRatioMode.FIT) }
    }

    LaunchedEffect(player) {
        player.events.collect { event ->
            if (event is PlaybackEvent.MediaEnded && loopState.value) {
                runCatching {
                    player.seekTo(0L)
                    player.play()
                }
            }
        }
    }

    MediampPlayerSurface(player, modifier)
}

@Composable
private fun VideoUnavailable(
    message: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF0B0D13)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "视频解码器不可用" + (message?.let { "\n$it" } ?: ""),
            style = TextStyle(fontSize = 11.sp, color = Color(0xFFFF9E9E)),
        )
    }
}

/** MediaMP 的音量范围由后端决定（mpv 为 0..100），这里把 0..1 映射过去。 */
private fun applyAudio(player: MediampPlayer, muted: Boolean, volume: Float) {
    val audio = runCatching { player.features.get(AudioLevelController.Key) }.getOrNull() ?: return
    val safeVolume = volume.coerceIn(0f, 1f)
    audio.setMute(muted)
    audio.setVolume(safeVolume * audio.maxVolume)
}
