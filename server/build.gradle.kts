import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    application
}
version = file("version.txt").readText().trim()
require(version.toString().matches(Regex("""\d+\.\d+\.\d+"""))) { "Invalid server/version.txt" }

// Compile the existing non-Compose core directly, so protocol fixes serve both editions.
kotlin.sourceSets["main"].kotlin.apply {
    srcDir("../desktop/src/main/kotlin")
    include("com/yunx/server/**")
    include("com/yunx/app/AppContext.kt")
    include("com/yunx/app/data/db/**", "com/yunx/app/data/security/**")
    include("com/yunx/app/data/network/**", "com/yunx/app/data/download/**")
    include("com/yunx/app/data/repository/*ResolveRepository.kt")
    include("com/yunx/app/data/repository/TransferSpaceGuard.kt")
    include("com/yunx/app/data/repository/ILanzouAccountRepository.kt")
    include("com/yunx/app/data/gopeed/GopeedEngine.kt", "com/yunx/app/data/prefs/**")
    include("com/yunx/app/util/Log.kt", "com/yunx/app/util/LogRedactor.kt")
    include("com/yunx/app/util/DiagnosticLog.kt", "com/yunx/app/util/DesktopActions.kt")
    include("com/yunx/app/util/WindowsToastNotifier.kt", "com/yunx/app/util/WindowsKeepAwake.kt")
    include("com/yunx/app/util/WindowsTitleBar.kt", "com/yunx/app/util/WindowsFolderPicker.kt")
    include("com/yunx/app/util/WindowsFilePicker.kt")
}
kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
kotlin.sourceSets["test"].kotlin.apply {
    srcDir("../desktop/src/test/kotlin")
    include("com/yunx/server/**")
    include("com/yunx/app/data/download/DownloadPathPolicyTest.kt")
    include("com/yunx/app/data/download/HttpRangePolicyTest.kt")
    include("com/yunx/app/data/download/HlsRequestPolicyTest.kt")
    include("com/yunx/app/data/network/ShareLinkParserTest.kt")
    include("com/yunx/app/util/LogRedactorTest.kt")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
application {
    mainClass.set("com.yunx.server.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
        "-Djava.util.prefs.userRoot=/data/preferences",
        "-XX:MaxRAMPercentage=60.0"
    )
}
tasks.test {
    environment("YUNX_DESKTOP_DATA_DIR", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
}
tasks.processResources {
    from("../desktop/src/main/resources/icon.png") { into("web") }
}
