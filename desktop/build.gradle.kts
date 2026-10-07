import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // 多平台版 lifecycle（Compose Desktop 可用 viewModel()）
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // BackHandler 由本项目自带桌面实现（ui/BackHandler.kt，Escape 键）

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Room -> 纯 JDBC SQLite
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Android 内置 org.json 的 JVM 等价物
    implementation("org.json:json:20240303")
    // 主题种子色（material-color-utilities 无公开仓库坐标；从 material 1.14.0 AAR 解包 classes.jar 本地引入）
    implementation(files("libs/material-color-utilities-1.0.0.jar"))

    // GFM Markdown 渲染（README / 更新说明）。注意：必须停留在 0.33.x ——
    // 0.34.0 起改用 Compose 1.8+ 的 BasicText 签名（含 TextAutoSize），与项目锁定的
    // Compose Multiplatform 1.7.3 二进制不兼容，会在运行时抛 NoSuchMethodError
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")

    // 方案 A：一键导入浏览器 Cookie（DPAPI 解密 Chromium 系浏览器的加密 Cookie）
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // 方案 B：内嵌 Chromium 浏览器（JCEF），jcefmaven 负责原生库装载
    implementation("me.friwi:jcefmaven:132.3.1")
    implementation("me.friwi:jcef-natives-windows-amd64:jcef-1770317+cef-132.3.1+g144febe+chromium-132.0.6834.83")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(if (System.getProperty("os.name").lowercase().contains("mac")) 21 else 17)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ---- 版本号单一来源：仓库根目录 version.txt（jpackage / 便携包 / 安装包 / 应用内版本全部由它派生）----
val appVersion = rootProject.file("version.txt").readText().trim()
require(appVersion.matches(Regex("""\d+(\.\d+)*"""))) { "version.txt 内容非法: '$appVersion'" }

// 生成 Kotlin 常量供 UpdateChecker 使用，避免应用内再维护一份硬编码
val generateAppVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val version = appVersion
    outputs.dir(outDir)
    inputs.property("version", version)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/yunx/app")
        pkgDir.mkdirs()
        pkgDir.resolve("AppVersion.kt").writeText(
            """
            |package com.yunx.app
            |
            |/** 由根目录 version.txt 生成，请勿手改 */
            |internal const val APP_VERSION = "$version"
            |""".trimMargin() + "\n"
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateAppVersion)

tasks.withType<Test> {
    useJUnit()
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

// 编译 native/darkmode.cpp → build/native/darkmode.dll（托盘原生菜单暗色桥接层）
val nativeSrcDir = file("native")
val nativeOutDir = layout.buildDirectory.dir("native").get().asFile
val darkModeSrc = File(nativeSrcDir, "darkmode.cpp")
val darkModeOut = File(nativeOutDir, "darkmode.dll")

// 定位 MSVC 的 vcvars64.bat（cl 直接调会找不到 windows.h / CRT 库，必须先 call 它）
fun findMsvcVcvars(): File? {
    val roots = listOf(
        "C:/Program Files/Microsoft Visual Studio/2022",
        "C:/Program Files (x86)/Microsoft Visual Studio/2022",
        "C:/Program Files/Microsoft Visual Studio/2019",
        "C:/Program Files (x86)/Microsoft Visual Studio/2019"
    )
    for (root in roots) {
        val f = File(root)
        if (!f.isDirectory) continue
        val editions = f.listFiles() ?: continue
        for (ed in editions) {
            val bat = File(ed, "VC/Auxiliary/Build/vcvars64.bat")
            if (bat.isFile) return bat
        }
    }
    return null
}

val compileDarkMode by tasks.registering(Exec::class) {
    val vcvars = findMsvcVcvars()
    inputs.file(darkModeSrc)
    outputs.file(darkModeOut)
    doFirst {
        nativeOutDir.mkdirs()
    }
    if (vcvars != null) {
        commandLine(
            "cmd", "/c",
            "call \"${vcvars.absolutePath}\" >nul && " +
                "cl /nologo /utf-8 /LD \"${darkModeSrc.absolutePath}\" " +
                "/Fe:\"${darkModeOut.absolutePath}\" " +
                "/Fo:\"${nativeOutDir.absolutePath}\\\\\" " +
                "/link /NOENTRY kernel32.lib user32.lib"
        )
    } else {
        // 回退 MinGW
        commandLine(
            "g++", "-shared", "-o", darkModeOut.absolutePath,
            darkModeSrc.absolutePath, "-lkernel32", "-luser32"
        )
    }
}

// 开发运行（:desktop:run）时把 DLL 目录并入 java.library.path，并保留 JDK bin（jawt 等原生库）
tasks.withType<JavaExec> {
    dependsOn(compileDarkMode)
    val jvmBin = File(System.getProperty("java.home"), "bin").absolutePath
    systemProperty("java.library.path", "${nativeOutDir.absolutePath};$jvmBin")
}

// 收集运行时依赖 jar 路径（供便携版 app-image 打包）
tasks.register("printRuntimeClasspath") {
    doLast {
        val cp = configurations.runtimeClasspath.get().files
            .joinToString(";") { it.absolutePath }
        println("RUNTIME_CP=$cp")
    }
}

// 导出全部运行时依赖 jar 到 build/exportLibs（供便携版打包脚本使用，规避 gradle 缓存路径漂移）
tasks.register<Sync>("exportRuntimeLibs") {
    from(configurations.runtimeClasspath)
    into(layout.projectDirectory.dir("build/exportLibs"))
}

compose.desktop {
    application {
        mainClass = "com.yunx.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            // sqlite-jdbc 通过反射加载 java.sql.Driver，jlink 自动检测不到，必须显式声明
            modules("java.sql", "java.naming")
            // 每用户安装（%LOCALAPPDATA%\Programs），无需管理员权限
            windows {
                perUserInstall = true
            }
            packageName = "YunX-Desktop"
            packageVersion = appVersion
            // 注意：jpackage 参数文件解析不支持非 ASCII 描述（本机报 "Input length = 1"），描述保持纯英文
            description = "YunX-Desktop - netdisk share-link parser and high-speed downloader (desktop port of YunX for Android)"
            vendor = "YunX-Desktop"
            copyright = "Copyright (C) 2026 tidain"
        }
    }
}
