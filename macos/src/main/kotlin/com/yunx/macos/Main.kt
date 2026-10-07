package com.yunx.macos

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Robot
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val smoke = args.any { it in listOf("--visual-smoke", "--lifecycle-smoke", "--resume-smoke") }
    require(!smoke || System.getenv("YUNX_DESKTOP_DATA_DIR") != null) { "Smoke tests require an isolated data directory" }
    MacRuntime.prepare(if (smoke) "yunx-macos-smoke" else "yunx-macos")
    val lockChannel = FileChannel.open(File(com.yunx.app.AppContext.dataDir, "instance.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val instanceLock = lockChannel.tryLock()
    if (instanceLock == null) {
        lockChannel.close()
        ProcessBuilder("/usr/bin/open", "-b", "com.yunx.macos").start()
        return
    }
    val runtime = MacRuntime()
    if (smoke) runtime.prefs.put("directory", File(com.yunx.app.AppContext.dataDir, "downloads").absolutePath)
    Runtime.getRuntime().addShutdownHook(Thread {
        try { runBlocking { runtime.shutdown() } }
        finally { instanceLock.release(); lockChannel.close() }
    })
    application {
        val scope = rememberCoroutineScope()
        var visible by remember { mutableStateOf(true) }
        var quitting by remember { mutableStateOf(false) }
        var previewPage by remember { mutableStateOf<String?>(null) }
        val quit: () -> Unit = {
            if (!quitting) {
                quitting = true
                scope.launch {
                    withContext(Dispatchers.IO) { runtime.shutdown() }
                    exitApplication()
                }
            }
        }
        Window(
            title = "云析 YunX",
            visible = visible,
            onCloseRequest = { visible = false },
            state = rememberWindowState(width = if ("--small" in args) 820.dp else 1100.dp, height = if ("--small" in args) 600.dp else 760.dp)
        ) {
            DisposableEffect(window) {
                window.minimumSize = Dimension(820, 600)
                val desktop = Desktop.getDesktop()
                val reopen = java.awt.desktop.AppReopenedListener { visible = true; window.toFront() }
                desktop.addAppEventListener(reopen)
                if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                    desktop.setQuitHandler { _, response -> response.cancelQuit(); quit() }
                }
                val listener = object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent?) { visible = false }
                }
                window.addWindowListener(listener)
                onDispose {
                    desktop.removeAppEventListener(reopen)
                    window.removeWindowListener(listener)
                }
            }
            Workspace(runtime, previewPage, when { "--dark" in args -> "dark"; "--light" in args -> "light"; else -> null }, quitting)
            if ("--visual-smoke" in args) {
                LaunchedEffect(Unit) {
                    val variant = "${if ("--dark" in args) "dark" else "light"}-${if ("--small" in args) "small" else "normal"}"
                    val output = File(System.getProperty("yunx.smokeDir", "macos/build/screenshots/$variant")).also { it.mkdirs() }
                    delay(1800)
                    for (page in listOf("resolve", "cloud", "tasks", "accounts", "settings")) {
                        previewPage = page
                        delay(700)
                        val bounds = window.bounds
                        ImageIO.write(Robot().createScreenCapture(bounds), "png", File(output, "$page.png"))
                    }
                    quit()
                }
            }
            if ("--lifecycle-smoke" in args || "--resume-smoke" in args) {
                LaunchedEffect(Unit) {
                    try {
                        delay(1200)
                        if ("--lifecycle-smoke" in args) lifecycleSmoke(runtime, window, { visible })
                        else resumeSmoke(runtime)
                        quit()
                    } catch (e: Exception) {
                        System.err.println("APP_SMOKE_FAILED: ${e.message}")
                        withContext(Dispatchers.IO) { runtime.shutdown() }
                        kotlin.system.exitProcess(1)
                    }
                }
            }
        }
    }
}
