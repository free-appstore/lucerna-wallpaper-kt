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
- 壁纸画在每块屏幕的「桌面壁纸层」窗口里（macOS），不写系统壁纸；视频取一帧封面作为静态壁纸
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

应用冒烟测试（会短暂换壁纸再恢复，不改系统壁纸）：

```bash
./gradlew :lucerna-app:run --args="--smoke-wallpaper"
```

连续换三张图（含换回同一张），每一轮都校验：壁纸层里显示的是新图，且**每块屏幕都有一个
层级正确的壁纸窗口**（在系统壁纸之上、桌面图标之下）。走系统壁纸兜底时则校验壁纸库里
每个桌面的配置和每块屏幕的壁纸路径。结束后恢复原壁纸。
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

- **macOS**：不再改系统壁纸，自己在桌面上铺一层「壁纸层」：
  - 每块屏幕一个无边框窗口，层级是 `CGWindowLevelForKey(kCGDesktopWindowLevelKey) + 1`
    （实测 `-2147483622`）：**系统壁纸之上、桌面图标（`-2147483603`）之下**，
    所以桌面图标、普通程序窗口、台前调度、程序坞全部压在它上面；
  - `ignoresMouseEvents = YES`：鼠标事件全部穿透给系统，壁纸层不拦截任何点击；
  - 只 `orderBack:`，从不 `orderFront:`：壁纸层永远不会被提到别人上面；
  - `canJoinAllSpaces + stationary + ignoresCycle`：出现在所有桌面（Mission Control 的每个 Space），
    切换桌面不重建、不闪；不进窗口切换器、无阴影、不抢焦点、失去激活也不隐藏；
  - 换壁纸 = 换窗口里的图片（等比铺满），**不重启任何系统服务**，实测 0.2 秒左右、零灰屏，
    所有屏幕（每块连接的显示器各一个窗口）和所有桌面同时生效；
  - 不进全屏 Space（不加 `fullScreenAuxiliary`）：全屏 app 绝不会被壁纸盖住；
    全屏 Space 里本来也看不到桌面，过渡动画靠「垫底的同款系统壁纸」兜住；
  - 有 2 秒一次的屏幕巡检：插上/拔掉显示器、改分辨率都会自动补建/销毁窗口；
  - 建窗口时先把层级和「加入所有 Space」设好再显示：否则在某个屏幕被全屏 app 占着时
    建出来的窗口会被绑在那个全屏 Space 上，回到桌面后就看不到（那块屏会漏出系统壁纸）；
  - **系统壁纸会同步成同一张作为「垫底」**：调度中心 / 切空间的过渡动画是 macOS 用
    Space 快照合成的，桌面层窗口不在那份快照里，动画那几帧只会画系统壁纸；
    每次换壁纸顺手做一次 `NSWorkspace` 调用（约 0.1 秒，不重启服务、不闪屏），
    平时完全被壁纸层盖住，只有过渡动画漏出来的瞬间看到的也是同一张图。

  窗口层级只能在 AppKit 主线程上改：先用 AWT 的 `CPlatformWindow` 拿到 NSWindow 指针，
  再用 `objc_msgSend` 设层级/行为（JVM 需要
  `--add-opens java.desktop/java.awt,java.desktop/sun.lwawt,java.desktop/sun.lwawt.macosx`，
  已在 `lucerna-app/build.gradle.kts` 里配好）。万一拿不到 NSWindow 或被系统策略挡住，
  自动退回「系统壁纸 + 壁纸库同步」的兜底路径（那条路径会短暂灰屏）。
  视频封面用系统自带的 Quick Look 生成。
- **Windows**：`SystemParametersInfoW` 设置壁纸并写入「填充」样式。
- **Linux**：按桌面环境依次尝试 gsettings、plasma-apply-wallpaperimage、xfconf-query、feh、nitrogen。
- Windows / Linux 的视频封面会尝试调用 `ffmpeg`，没有安装时保留当前壁纸。

打包命令：`./gradlew :lucerna-app:packageDmg`（Windows/Linux 分别在对应系统上执行 `packageMsi` / `packageDeb`）。
