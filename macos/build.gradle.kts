import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.Path

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    sourceSets.main {
        kotlin.srcDir("../desktop/src/main/kotlin")
        kotlin.srcDir("../server/src/main/kotlin")
        kotlin.include("com/yunx/macos/**", "com/yunx/app/AppContext.kt")
        kotlin.include("com/yunx/app/data/db/**", "com/yunx/app/data/security/**")
        kotlin.include("com/yunx/app/data/backup/AuthCrypto.kt")
        kotlin.include("com/yunx/app/data/network/**", "com/yunx/app/data/download/**", "com/yunx/app/data/prefs/**")
        kotlin.exclude("com/yunx/app/data/network/SystemProxy.kt")
        kotlin.include("com/yunx/app/data/repository/*ResolveRepository.kt")
        kotlin.include("com/yunx/app/data/repository/TransferSpaceGuard.kt", "com/yunx/app/data/repository/ILanzouAccountRepository.kt")
        kotlin.include("com/yunx/app/util/Log.kt", "com/yunx/app/util/LogRedactor.kt", "com/yunx/app/util/DiagnosticLog.kt")
        kotlin.include("com/yunx/app/util/DesktopActions.kt")
        kotlin.include("com/yunx/server/ServerService.kt", "com/yunx/server/Credentials.kt", "com/yunx/server/DownloadHeaders.kt")
        kotlin.include("com/yunx/server/GitHubBrowser.kt")
        kotlin.include("com/yunx/server/AccountBackup.kt")
        kotlin.include("com/yunx/server/CloudMutation.kt")
    }
    sourceSets.test {
        kotlin.srcDir("../desktop/src/test/kotlin")
        kotlin.include("com/yunx/macos/**", "com/yunx/app/data/download/**")
        kotlin.include("com/yunx/app/data/db/SecureAccountDaosTest.kt", "com/yunx/app/data/network/ShareLinkParserTest.kt")
        kotlin.include("com/yunx/app/data/network/BaiduFileManagementTest.kt")
        kotlin.include("com/yunx/app/util/LogRedactorTest.kt")
    }
}
// DesktopActions has a platform implementation in this module.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude { it.file.absolutePath.endsWith("/desktop/src/main/kotlin/com/yunx/app/util/DesktopActions.kt") }
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test {
    environment("YUNX_DESKTOP_DATA_DIR", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    systemProperty("yunx.preferenceRoot", "yunx-macos-tests")
}
tasks.processResources {
    from("../desktop/src/main/resources/icon.png")
    from("../server/src/main/resources/web/fonts") { into("fonts") }
}
compose.desktop {
    application {
        mainClass = "com.yunx.macos.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx1536m", "-Dapple.awt.application.name=YunX")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "YunX"
            packageVersion = "1.0.0"
            description = "YunX local netdisk downloader"
            vendor = "YunX contributors"
            copyright = "Copyright (C) 2026 tidain and YunX contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql", "java.naming", "java.prefs", "jdk.crypto.ec", "jdk.unsupported")
            macOS {
                bundleID = "com.yunx.macos"
                dockName = "YunX"
                iconFile.set(project.file("src/main/resources/YunX.icns"))
            }
        }
    }
}

// jpackage's default DMG layout script asks for Finder automation permission.
// Build a plain drag-to-Applications image without invoking AppleScript.
tasks.matching { it.name == "packageDmg" }.configureEach {
    setActions(emptyList())
    dependsOn("createDistributable")
    doLast {
        val stage = layout.buildDirectory.dir("dmg-stage").get().asFile
        delete(stage)
        stage.mkdirs()
        copy {
            from(layout.buildDirectory.dir("compose/binaries/main/app/YunX.app"))
            into(File(stage, "YunX.app"))
        }
        Files.createSymbolicLink(File(stage, "Applications").toPath(), Path.of("/Applications"))
        val output = layout.buildDirectory.file("compose/binaries/main/dmg/YunX-1.0.0.dmg").get().asFile
        output.parentFile.mkdirs()
        exec {
            commandLine("/usr/bin/hdiutil", "create", "-volname", "YunX", "-srcfolder", stage.absolutePath,
                "-ov", "-format", "UDZO", output.absolutePath)
        }
    }
}
