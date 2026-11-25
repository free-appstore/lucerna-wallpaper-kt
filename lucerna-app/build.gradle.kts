import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":lucerna-core"))
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
    )
}

compose.desktop {
    application {
        mainClass = "com.lucerna.app.MainKt"

        // macOS 菜单栏常驻程序需要隐藏 Dock 图标，并允许调整原生窗口层级。
        // --add-opens 是壁纸层拿 NSWindow 指针改窗口层级（桌面层）用的。
        jvmArgs += listOf(
            "-Dapple.awt.UIElement=true",
            "-Dapple.awt.enableTemplateImages=false",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
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
            }
        }
    }
}
