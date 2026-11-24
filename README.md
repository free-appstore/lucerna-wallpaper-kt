# Lucerna · 灵萤桌面壁纸

状态栏常驻的桌面壁纸应用，使用 Kotlin + Compose Multiplatform 编写，支持 macOS、Windows 与 Linux。
点击菜单栏 / 系统托盘图标弹出面板，点击面板外部自动收起，不占用 Dock 或任务栏。

## 模块

| 模块 | 职责 |
| --- | --- |
| `lucerna-core` | 色彩模型与渲染、设置/图库 JSON 存储、导入与文件浏览、平台抽象 |
| `lucerna-media` | MediaMP 视频播放封装（自带 mpv/FFmpeg 运行时，支持主流视频容器） |
| `lucerna-ui` | 面板、四个分页、色彩编辑器、右键菜单、图片/视频预览 |
| `lucerna-app` | 状态栏图标、弹出窗口、系统壁纸后端、登录启动、屏保 |

第三方依赖统一登记在 `gradle/libs.versions.toml`，按模块通过
`libs.bundles.lucerna.core`、`libs.bundles.lucerna.ui`、`libs.bundles.lucerna.app`
等 bundle 引用。

## 功能

- **我的**：照片 / 视频 / 色彩三个二级折叠分组，数量写在标题里；空分组自动隐藏，点击标题带展开/收起动画
- **色彩**：纯色、渐变、色阶；方向圆盘 0–360°，阶梯数量 2–20，颜色最多 10 个可增删改
- **导入**：面板内嵌文件浏览器（不是系统对话框），支持 JPG/PNG/WebP/BMP/GIF/TIFF 与 MP4/MOV/MKV/WebM/AVI 等
- **设置**：菜单栏图标（彩色 / 单色纯白，左右滑动切换）、视频音量、禁用壁纸、登录时启动、屏幕保护程序与立即测试
- **预览**：图片用 Coil3，视频用 MediaMP；滚轮或 +/- 缩放 1–8 倍，拖拽平移
- 壁纸只写系统壁纸，不创建任何额外窗口；视频取一帧封面作为静态壁纸
- 右上角电源按钮退出程序

## 运行

```bash
./gradlew :lucerna-app:run
```

启动后应用只在状态栏出现。菜单栏图标是一只萤火虫（会自动放大到菜单栏允许的最大尺寸），
图标配色在「设置 → 外观」里切换：彩色是萤火虫本来的暖色，单色是纯白剪影。

## 测试

```bash
./gradlew :lucerna-core:test :lucerna-ui:test
```

应用冒烟测试（会短暂修改系统壁纸并恢复）：

```bash
./gradlew :lucerna-app:run --args="--smoke-wallpaper"
```

连续换三张图（含换回同一张），每一轮都要求：壁纸库里**每个桌面**（每块屏幕、每个 Space）
都是新图，且**每块连接的屏幕**回读到的壁纸也是新图。结束后恢复壁纸库与所有屏幕的原壁纸。
`--smoke-fullscreen` 会另起一个子进程显示一块假的全屏窗口，模拟微信这类全屏 app，
验证有屏幕处于全屏时结果一样：

```bash
./gradlew :lucerna-app:run --args="--smoke-fullscreen"
```

排查全屏检测（每块屏幕是否被全屏 app 覆盖）：

```bash
./gradlew :lucerna-app:run --args="--detect-fullscreen"
```

无界面启动面板并截图（调试用）：

```bash
LUCERNA_SMOKE=1 LUCERNA_SMOKE_TAB=color ./gradlew :lucerna-app:run
```

可用环境变量：`LUCERNA_DATA_DIR`（覆盖数据目录）、`LUCERNA_SMOKE`、
`LUCERNA_SMOKE_TAB=mine|color|import|settings`、`LUCERNA_SMOKE_PREVIEW=image|video`、
`LUCERNA_SMOKE_REOPEN=1`、`LUCERNA_SMOKE_APPLY=video`。


## 数据目录

- macOS：`~/Library/Application Support/Lucerna`
- Windows：`%APPDATA%\Lucerna`
- Linux：`~/.local/share/lucerna`

目录内有 `settings.json`、`library.json`、`media/`、`thumbnails/`、`renders/`。

## 平台说明

- **macOS**：`NSWorkspace` 只能改「当前显示的那个 Space」，所以设置壁纸分两步：
  1. `NSWorkspace` 把图片设到所有连接的屏幕（当前显示的桌面立刻生效）；
  2. 直接改壁纸库 `~/Library/Application Support/com.apple.wallpaper/Store/Index.plist`，
     把所有屏幕、所有桌面（Mission Control 里的每个 Space，包括被全屏 app 占着、
     当前没显示的桌面，以及 SystemDefault / AllSpacesAndDisplays）的图片配置一起换成新图，
     然后给 `WallpaperAgent` 发 `SIGHUP` 让它重新读取并重绘。

  改完会回读校验「每个桌面都是这张图」，不通过就自动重来（最多 3 次）；同一张图重复设置时
  走快路径，不重启壁纸服务。改之前自动备份 `Index.plist.lucerna-backup`，出错时回滚。
  设置页的「在所有空间中显示」开关仍然可用（会尝试自动打开系统设置里对应的选项）。
  视频封面用系统自带的 Quick Look 生成。
- **Windows**：`SystemParametersInfoW` 设置壁纸并写入「填充」样式。
- **Linux**：按桌面环境依次尝试 gsettings、plasma-apply-wallpaperimage、xfconf-query、feh、nitrogen。
- Windows / Linux 的视频封面会尝试调用 `ffmpeg`，没有安装时保留当前壁纸。

打包命令：`./gradlew :lucerna-app:packageDmg`（Windows/Linux 分别在对应系统上执行 `packageMsi` / `packageDeb`）。
