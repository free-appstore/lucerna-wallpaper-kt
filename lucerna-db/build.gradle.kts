plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

dependencies {
    api(project(":lucerna-core"))
    implementation(libs.bundles.lucerna.core)
    implementation(libs.room3.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room3.compiler)

    testImplementation(libs.bundles.lucerna.test)
}

// Room 3 只提供 KSP 处理器，schema 由 Room Gradle 插件复制到仓库里
room3 {
    schemaDirectory("$projectDir/schemas")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
