@file:OptIn(ExperimentalFoundationApi::class)

package com.lucerna.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucerna.core.files.BrowserEntry
import com.lucerna.core.files.FileBrowser
import com.lucerna.core.files.ImportFilter
import com.lucerna.core.model.WallpaperType
import com.lucerna.ui.components.CheckGlyph
import com.lucerna.ui.components.FolderGlyph
import com.lucerna.ui.components.GhostButton
import com.lucerna.ui.components.PhotoGlyph
import com.lucerna.ui.components.PillButton
import com.lucerna.ui.components.PrimaryButton
import com.lucerna.ui.components.UpGlyph
import com.lucerna.ui.components.VideoGlyph
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.theme.LucernaColors
import com.lucerna.ui.theme.LucernaMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 「导入」页：面板内嵌的文件浏览器，不弹系统对话框。 */
@Composable
fun ImportPage(state: LucernaState) {
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(File(state.settings.importDir ?: System.getProperty("user.home"))) }
    var filter by remember { mutableStateOf(ImportFilter.ALL) }
    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 常用位置在进程内不会变，避免每次重组都去 stat 一遍文件系统
    val roots = remember { FileBrowser.roots() }

    LaunchedEffect(dir, filter) {
        loading = true
        selection = emptySet()
        entries = withContext(Dispatchers.IO) { FileBrowser.list(dir, filter) }
        loading = false
    }

    fun navigate(target: File) {
        if (!target.isDirectory) return
        dir = target
        state.rememberImportDir(target)
    }

    Column(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LucernaMetrics.SecondaryGap),
        ) {
            roots.forEach { (label, file) ->
                PillButton(
                    text = label,
                    selected = dir.absolutePath == file.absolutePath,
                    onClick = { navigate(file) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x12FFFFFF))
                    .border(1.dp, LucernaColors.Border, RoundedCornerShape(7.dp))
                    .clickable { navigate(FileBrowser.parentOf(dir)) },
                contentAlignment = Alignment.Center,
            ) {
                UpGlyph(Modifier.size(11.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                dir.absolutePath,
                fontSize = 10.sp,
                color = LucernaColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LucernaMetrics.SecondaryGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImportFilter.entries.forEach { option ->
                PillButton(
                    text = option.label,
                    selected = filter == option,
                    onClick = { filter = option },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${entries.count { !it.isDirectory }} 个文件", fontSize = 9.5.sp, color = LucernaColors.TextTertiary)
        }
        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x08FFFFFF))
                .border(1.dp, LucernaColors.Border, RoundedCornerShape(10.dp)),
        ) {
            when {
                loading -> Text(
                    "正在读取目录…",
                    fontSize = 11.sp,
                    color = LucernaColors.TextTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )

                entries.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("这里没有可导入的图片或视频", fontSize = 11.sp, color = LucernaColors.TextSecondary)
                    Text("支持 JPG / PNG / WebP / MP4 / MOV / MKV 等常见格式", fontSize = 9.5.sp, color = LucernaColors.TextTertiary)
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        BrowserRow(
                            entry = entry,
                            selected = entry.path in selection,
                            onClick = {
                                if (entry.isDirectory) {
                                    navigate(entry.file)
                                } else {
                                    selection = if (entry.path in selection) selection - entry.path else selection + entry.path
                                }
                            },
                        )
                    }
                }
            }

            val busy = state.busyMessage
            if (busy != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC0A0C12)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(busy, fontSize = 11.sp, color = LucernaColors.Accent)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selection.isEmpty()) "点选文件后可多选导入" else "已选 ${selection.size} 项",
                fontSize = 10.sp,
                color = LucernaColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            GhostButton("清空") { selection = emptySet() }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                text = "导入 ${selection.size} 项",
                enabled = selection.isNotEmpty() && state.busyMessage == null,
                modifier = Modifier.width(110.dp),
                onClick = {
                    val files = selection.map { File(it) }
                    scope.launch { state.importFiles(files) }
                },
            )
        }
    }
}

@Composable
private fun BrowserRow(entry: BrowserEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            FolderGlyph(Modifier.size(15.dp))
        } else if (entry.type == WallpaperType.VIDEO) {
            VideoGlyph(Modifier.size(15.dp), color = Color(0xFF9FB7FF))
        } else {
            PhotoGlyph(Modifier.size(15.dp), color = Color(0xFFB9E3C5))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            entry.name,
            fontSize = 11.sp,
            color = if (selected) LucernaColors.Accent else LucernaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory) {
            Text(FileBrowser.humanSize(entry.size), fontSize = 9.sp, color = LucernaColors.TextTertiary)
            Spacer(Modifier.width(9.dp))
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(if (selected) LucernaColors.Accent else Color.Transparent)
                    .border(1.dp, if (selected) LucernaColors.Accent else LucernaColors.BorderStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) CheckGlyph(Modifier.size(9.dp))
            }
        } else {
            Text("›", fontSize = 14.sp, color = LucernaColors.TextTertiary)
        }
    }
}
