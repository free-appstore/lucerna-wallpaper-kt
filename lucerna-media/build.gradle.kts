plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    api(project(":lucerna-core"))
    implementation(libs.bundles.lucerna.media)
    implementation(compose.desktop.currentOs)
    // MediaMP 的原生 mpv 运行时不会随 mediamp-all 传递，需要显式声明
    runtimeOnly(libs.bundles.lucerna.media.runtime)
}
