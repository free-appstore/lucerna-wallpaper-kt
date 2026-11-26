# Lucerna · 灵萤桌面壁纸

状态栏常驻的桌面壁纸应用，使用 Kotlin + Compose Multiplatform 编写，支持 macOS、Windows 与 Linux。
点击菜单栏 / 系统托盘图标弹出面板，点击面板外部自动收起，不占用 Dock 或任务栏。

## 模块

| 模块            | 职责                                                             |
|-----------------|------------------------------------------------------------------|
| `lucerna-core`  | 色彩模型与渲染、存储接口、导入与文件浏览、平台抽象               |
| `lucerna-db`    | Room 3 数据库：设置、色彩、图库元数据，以及旧版 JSON 迁移        |
| `lucerna-media` | MediaMP 视频播放封装（自带 mpv/FFmpeg 运行时，支持主流视频容器） |
| `lucerna-ui`    | 面板、四个分页、色彩编辑器、右键菜单、图片/视频预览              |
| `lucerna-app`   | 状态栏图标、弹出窗口、系统壁纸后端、登录启动、屏保               |

第三方依赖统一登记在 `gradle/libs.versions.toml`，按模块通过
`libs.bundles.lucerna.core`、`libs.bundles.lucerna.ui`、`libs.bundles.lucerna.app`
等 bundle 引用。

## 功能

- **我的**：顶部二级分类「图片 / 视频 / 色彩」平铺切换（无动画，尺寸与配色都比一级分页更轻），一次只显示所选类型，分类上带数量
- **色彩**：纯色、渐变、色阶；方向圆盘 0–360°，阶数用数字输入（2–20，且不少于颜色数量），颜色 2–20 个可增删改（纯色只有 1
  个）；「添加并应用」会把当前色彩存进「我的 - 色彩」并立刻设为壁纸；左下角随机按钮会保持当前类型，按色彩和谐规律随机一组配色（随机颜色数量 / 8 个方向之一 / 阶数），只更新编辑区、不直接改壁纸，点「添加并应用」才生效
- **导入**：面板内嵌文件浏览器（不是系统对话框），只列出 Coil3 / MediaMP 能解码的格式（JPG/PNG/WebP/BMP/GIF 与 MP4/MOV/MKV/WebM/AVI 等，HEIC/HEIF/AVIF/TIFF 不会出现）；文件右键可预览或在系统文件管理器中定位
- **设置**：菜单栏图标（彩色 / 跟随菜单栏黑白的单色，左右滑动切换）、视频声音与音量、视频播放速度（暂停 / x0.5 / x1 / x1.5 / x2 / x3，带滑动动画）、禁用壁纸、登录时启动、屏幕保护程序与立即测试
- **预览**：图片用 Coil3，视频用 MediaMP；先在后台读好媒体尺寸再开窗，窗口高为屏幕一半、宽按媒体宽高比自适应，居中在当前屏幕，只能拖拽不能缩放；打开 / 关闭带「从光标位置展开 / 收回」的缩放淡入淡出动画；⌘+滚轮 / 双指捏合以鼠标为中心缩放 1–8 倍，拖拽平移
- 图片/色彩壁纸画在每块屏幕的「桌面壁纸层」窗口里（macOS）；视频壁纸用 AVFoundation 单播放器 + 多块屏幕共享 AVPlayerLayer，视频只硬件解码一次，锁屏继续使用系统壁纸（系统动态壁纸可以正常在锁屏播放）
- 右上角电源按钮退出程序

## 视频壁纸

- 桌面：每块屏幕一个 App 桌面层窗口，Windows/AVPlayerLayer 共享**同一个 AVPlayer**：视频只硬件解码一次，三块屏显示同一帧、同一播放进度。
- 壁纸层带 `fullScreenAuxiliary`，会进入全屏 Space 的过渡快照；系统桌面壁纸同步成同一视频封面，调度中心 / 切桌面的动画第一帧就能看到这段视频，不会露出旧系统壁纸。
- 视频播放期间不再因为全屏覆盖/调度中心暂停共享播放器；降级/恢复只由「禁用壁纸」「暂停」「倍速」触发。
- 锁屏：只写 `Desktop` 系统壁纸作为过渡动画垫底，**Idle（锁屏）配置保持不动**。macOS 系统壁纸自己的动态壁纸/视频壁纸可以继续在锁屏播放，由系统控制，不经过 Lucerna 的播放器。
- 桌面播放的是「我的 - 视频」里选中的视频；锁屏播放的是系统壁纸。两者可以不是同一个视频：macOS 没有公开接口把任意用户视频注册成系统视频壁纸。
- 播放速度 / 暂停只作用于桌面 App 窗口里的共享播放器；锁屏系统壁纸的播放速度由 macOS 管理。
- 当前原生共享播放器支持 MP4 / M4V / MOV（AVFoundation 能直接硬件解码的容器）；MKV / WebM / AVI 会退回静态封面，避免重新引入每屏一个播放器。
- 之前每块屏幕一个 MediaMP/mpv 播放器会解码三遍、渲染三遍，这是多屏 GPU 占用高的主因；MediaMP 本身已经请求 `hwdec=auto` 并走 macOS Metal。

## 运行

```bash
./gradlew :lucerna-app:run
```

启动后应用只在状态栏出现。菜单栏图标是一只萤火虫（会自动放大到菜单栏允许的最大尺寸），
图标配色在「设置 → 外观」里切换：彩色是萤火虫本来的暖色，单色会跟随系统菜单栏自动显示黑 / 白（macOS 用 template 图标）。

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

`--smoke-spaces` 是切 Space 的回归测试：从普通桌面开始，按 Ctrl+→ 找一个全屏 Space，
在全屏里应用测试照片和「我的」里的视频，再切回桌面、开关一次调度中心；每一步都检查
桌面壁纸层窗口是否**真的还在屏上**（而不是 `isVisible=true` 却被移出了 Space）。
需要辅助功能权限，结束后会恢复壁纸和原始 Space：

```bash
./gradlew :lucerna-app:run --args="--smoke-spaces"
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

目录内有：

- `lucerna.db`：Room 数据库（SQLite），保存设置、色彩列表与图库元数据
- `settings.json` / `library.json`：旧版文件，首次启动会自动导入数据库，之后不再读写
- `media/`、`thumbnails/`、`renders/`：图片/视频本体、缩略图、色彩渲染与视频封面等文件

持久化使用 `androidx.room3`（Room 3）+ `androidx.sqlite:sqlite-bundled`，数据库操作全部在
`lucerna-db` 模块；图片、视频和渲染结果仍然是磁盘文件，数据库只存路径与元数据。

## 平台说明

- **macOS**：图片 / 色彩不改系统壁纸，自己在桌面上铺一层「壁纸层」；视频壁纸例外，只把封面交给系统壁纸并停用桌面层：
    - 每块屏幕一个无边框窗口，层级是 `CGWindowLevelForKey(kCGDesktopWindowLevelKey) + 1`
      （实测 `-2147483622`）：**系统壁纸之上、桌面图标（`-2147483603`）之下**，
      所以桌面图标、普通程序窗口、台前调度、程序坞全部压在它上面；
    - `ignoresMouseEvents = YES`：鼠标事件全部穿透给系统，壁纸层不拦截任何点击；
    - 只 `orderBack:`，从不 `orderFront:`：壁纸层永远不会被提到别人上面；
    - `canJoinAllSpaces + stationary + ignoresCycle`：出现在所有桌面（Mission Control 的每个 Space），
      切换桌面不重建、不闪；不进窗口切换器、无阴影、不抢焦点、失去激活也不隐藏；
    - 换壁纸 = 换窗口里的图片（等比铺满），**可见画面 0.2 秒左右就换好、零灰屏**，
      所有屏幕（每块连接的显示器各一个窗口）和所有桌面同时生效；
    - **系统壁纸也会同时设成同一张**（当前 Space 用 `NSWorkspace`；壁纸库里每个 Space / 每块屏幕的
      配置在后台线程同步，会重启 `WallpaperAgent`，但灰屏被壁纸层盖住看不到）。
      这样 Mission Control、切桌面、全屏 Space 的快照里也是同一张图；
    - 带 `fullScreenAuxiliary` 进入全屏 Space 的过渡快照，但窗口层级仍远低于全屏 app，
      所以全屏 app 绝不会被壁纸盖住；调度中心 / 切桌面的动画可以直接合成视频壁纸，
      不再等动画结束才出现；
    - 全屏时既不隐藏窗口，也不暂停共享播放器；隐藏再显示会丢失 Space 绑定，
      暂停又会让调度中心里的视频停在旧帧或反复启停；
    - 有 0.8 秒一次的屏幕巡检：插上/拔掉显示器、改分辨率都会自动补建/销毁窗口；
    - 建窗口时先把层级和「加入所有 Space」设好再显示：否则在某个屏幕被全屏 app 占着时
      建出来的窗口会被绑在那个全屏 Space 上，回到桌面后就看不到（那块屏会漏出系统壁纸）；
    - **系统壁纸会同步成同一张作为「垫底」**：调度中心 / 切空间的过渡动画是 macOS 用
      Space 快照合成的，桌面层窗口不在那份快照里，动画那几帧只会画系统壁纸；
      每次换壁纸顺手做一次 `NSWorkspace` 调用（约 0.1 秒，不重启服务、不闪屏），
      平时完全被壁纸层盖住，只有过渡动画漏出来的瞬间看到的也是同一张图。
    - 打开「显示菜单栏背景」时，macOS 只在**系统壁纸真正显示到屏幕上**时才重新采样菜单栏背景；
      壁纸层一直盖着桌面的话，菜单栏会停在上一张。换壁纸时把有菜单栏的那块屏的壁纸窗口
      顶部裁掉一条菜单栏的高度（只改窗口形状，不移动、不缩放，最多约 1.2 秒），
      只露出**被菜单栏挡住的那一条**系统壁纸：用户看不到，系统却会因此立即重采样，
      顶部跟着壁纸一起换，不再整屏闪一下。桌面上看得见的部分一个像素都不动。
      （全屏 Space 里的窗口也保持显示，裁出的那一条同样被全屏 app 挡住。）

  窗口层级只能在 AppKit 主线程上改：先用 AWT 的 `CPlatformWindow` 拿到 NSWindow 指针，
  再用 `objc_msgSend` 设层级/行为（JVM 需要
  `--add-opens java.desktop/java.awt,java.desktop/sun.lwawt,java.desktop/sun.lwawt.macosx`，
  已在 `lucerna-app/build.gradle.kts` 里配好）。万一拿不到 NSWindow 或被系统策略挡住，
  自动退回「系统壁纸 + 壁纸库同步」的兜底路径（那条路径会短暂灰屏）。
  视频封面用系统自带的 Quick Look 生成。
- **Windows**：`SystemParametersInfoW` 设置壁纸并写入「填充」样式。
- **Linux**：按桌面环境依次尝试 gsettings、plasma-apply-wallpaperimage、xfconf-query、feh、nitrogen。
- Windows / Linux 的视频封面会尝试调用 `ffmpeg`，没有安装时保留当前壁纸。

macOS 的 release 包**关掉了 ProGuard 压缩**（`buildTypes.release.proguard.isEnabled = false`）：
压缩会裁掉 JNA 自带的 native 库（`libjnidispatch`）和反射用到的类，导致
`com.sun.jna.NativeLibrary` 初始化失败 —— 壁纸窗口就调不成桌面层级，变成普通全屏窗口盖住所有 App
（这正是「debug 正常、release 把全屏 IDE 盖住」的原因）。
另外壁纸层有个兜底：任何窗口如果没能调成桌面层级，会立刻隐藏，绝不去遮挡别人的 App。

macOS 打包时会往 `Info.plist` 写入 `LSUIElement=1`（菜单栏附件 App）：这样安装后启动不会出现在 Dock、
也不会被 LaunchServices 激活 —— 否则启动瞬间系统会切到本 App 所在的桌面 Space，
正在全屏的 App 看起来就像「被壁纸侵占」了。

打包命令：`./gradlew :lucerna-app:packageDmg`（Windows/Linux 分别在对应系统上执行 `packageMsi` / `packageDeb`）。
