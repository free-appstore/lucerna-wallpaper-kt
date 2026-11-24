package com.lucerna.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactoryLoader
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.playFile
import java.nio.file.Path

/**
 * 用 MediaMP 播放本地视频。
 *
 * 桌面端由 MediaMP 自带的 mpv / FFmpeg 运行时解码，MP4、MOV、MKV、WebM
 * 等常见容器都能直接播放，不依赖系统安装额外的播放器。
 *
 * @param volume 0..1 的相对音量
 * @param muted 是否静音，与 [volume] 独立，切回有声时能恢复原音量
 * @param loop 播放结束后是否从头循环
 * @param crop true 时按「铺满并裁切」显示，适合壁纸；false 时完整显示画面，适合预览
 */
@Composable
fun VideoSurface(
    path: String,
    modifier: Modifier = Modifier,
    volume: Float = 1f,
    muted: Boolean = true,
    loop: Boolean = true,
    crop: Boolean = false,
) {
    val creation = remember(path) {
        runCatching { MediampPlayerFactoryLoader.first().create(Any(), Dispatchers.Main) }
    }
    val player = creation.getOrNull()
    if (player == null) {
        VideoUnavailable(modifier, creation.exceptionOrNull()?.message)
        return
    }

    val loopState = rememberUpdatedState(loop)

    DisposableEffect(player) {
        onDispose { runCatching { player.close() } }
    }

    LaunchedEffect(player, path) {
        runCatching { player.openFile(Path.of(path)) }
    }

    LaunchedEffect(player, muted, volume) {
        applyAudio(player, muted, volume)
    }

    LaunchedEffect(player, crop) {
        val aspect = runCatching { player.features.get(VideoAspectRatio.Key) }.getOrNull()
        aspect?.setMode(if (crop) AspectRatioMode.CROP else AspectRatioMode.FIT)
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
private fun VideoUnavailable(modifier: Modifier, message: String?) {
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

private suspend fun MediampPlayer.openFile(path: Path) {
    playFile(path)
}

/** MediaMP 的音量范围由后端决定（mpv 为 0..100），这里把 0..1 映射过去。 */
fun applyAudio(player: MediampPlayer, muted: Boolean, volume: Float) {
    val audio = runCatching { player.features.get(AudioLevelController.Key) }.getOrNull() ?: return
    val safeVolume = volume.coerceIn(0f, 1f)
    audio.setMute(muted)
    audio.setVolume(safeVolume * audio.maxVolume)
}
