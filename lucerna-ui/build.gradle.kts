plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    api(project(":lucerna-core"))
    implementation(project(":lucerna-media"))
    implementation(libs.bundles.lucerna.ui)
    implementation(compose.desktop.currentOs)

    testImplementation(libs.bundles.lucerna.test)
    testImplementation(libs.bundles.lucerna.ui.test)
    testImplementation(compose.desktop.currentOs)
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "failed", "skipped")
    }
}
