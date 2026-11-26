import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":lucerna-core"))
    implementation(project(":lucerna-db"))
    implementation(project(":lucerna-media"))
    implementation(project(":lucerna-ui"))
    implementation(libs.bundles.lucerna.app)
    implementation(compose.desktop.currentOs)
    runtimeOnly(libs.bundles.lucerna.media.runtime)

    testImplementation(libs.bundles.lucerna.test)
}

// 桌面包和热重载用的是 JavaExec（hotRun/run），同样需要能改 NSWindow 层级
tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        // 预览窗口的双指捏合缩放走 JDK 的 eAWT 手势（反射调用）
        "--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
    )
}

compose.desktop {
    application {
        mainClass = "com.lucerna.app.MainKt"

        // release 包默认会跑 ProGuard 压缩：它会把 JNA 自带的 native 库（libjnidispatch）
        // 和反射用到的类裁掉，导致 com.sun.jna.NativeLibrary 初始化失败 → 壁纸层调不成桌面层级，
        // 变成一张普通全屏窗口盖住所有 App（连全屏的 IDE 都被盖住）。
        // 本身体积不大，直接关掉压缩，保证 release 和 debug 行为一致。
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        // macOS 菜单栏常驻程序需要隐藏 Dock 图标，并允许调整原生窗口层级。
        // --add-opens 是壁纸层拿 NSWindow 指针改窗口层级（桌面层）用的。
        jvmArgs += listOf(
            "-Dapple.awt.UIElement=true",
            "-Dapple.awt.enableTemplateImages=false",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Lucerna"
            packageVersion = "1.0.0"
            description = "灵萤 · 桌面壁纸"
            vendor = "Lucerna"

            modules("java.desktop", "java.naming", "jdk.unsupported")
            macOS {
                bundleID = "app.lucerna.wallpaper"
                dockName = "Lucerna"
                // 打包后必须是「菜单栏附件 App」：LSUIElement 让它启动时不出现在 Dock、
                // 也不会被 LaunchServices 激活。否则启动的一瞬间系统会切到它所在的桌面 Space，
                // 正在全屏的 app（IDE / 浏览器 / 微信）看起来就像被壁纸「侵占」了。
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <string>1</string>
                        <key>NSHighResolutionCapable</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}
