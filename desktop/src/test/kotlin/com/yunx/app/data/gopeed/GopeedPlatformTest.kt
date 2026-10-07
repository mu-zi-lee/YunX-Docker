package com.yunx.app.data.gopeed

import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class GopeedPlatformTest {
    @Test fun officialAssetNamesMatchHostPlatforms() {
        assertEquals("gopeed-web-v1.9.3-macos-arm64.zip", GopeedPlatform.detect("Mac OS X", "aarch64").archiveName("v1.9.3"))
        assertEquals("gopeed-web-v1.9.3-linux-amd64.zip", GopeedPlatform.detect("Linux", "x86_64").archiveName("v1.9.3"))
        assertEquals("gopeed.exe", GopeedPlatform.detect("Windows 11", "amd64").executableName)
        assertEquals("gopeed", GopeedPlatform.detect("Darwin", "arm64").executableName)
        assertFailsWith<IllegalStateException> { GopeedPlatform.detect("Linux", "unknown") }
    }
    @Test fun installsNestedUnixExecutableWithoutExtractingOtherEntries() {
        val directory = Files.createTempDirectory("yunx-gopeed-test").toFile()
        try {
            val archive = directory.resolve("kernel.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt")); zip.write("ignored".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("bin/gopeed")); zip.write("executable".toByteArray()); zip.closeEntry()
            }
            val target = directory.resolve("installed/gopeed")
            GopeedKernelInstaller.install(archive, target, GopeedPlatform.detect("Mac OS X", "aarch64"))
            assertEquals("executable", target.readText())
            if (!System.getProperty("os.name").startsWith("Windows")) assertTrue(target.canExecute())
            assertFalse(directory.resolve("outside.txt").exists())
            assertEquals(listOf("gopeed"), target.parentFile.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
    @Test fun invalidArchivePreservesInstalledKernel() {
        val directory = Files.createTempDirectory("yunx-gopeed-test").toFile()
        try {
            val target = directory.resolve("gopeed")
            target.writeText("original")
            val archive = directory.resolve("bad.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("gopeed.exe")); zip.write("wrong-platform".toByteArray()); zip.closeEntry()
            }
            assertFailsWith<IllegalStateException> {
                GopeedKernelInstaller.install(archive, target, GopeedPlatform.detect("Linux", "amd64"))
            }
            assertEquals("original", target.readText())
            assertFalse(directory.listFiles()!!.any { it.name.startsWith(".gopeed-") })
        } finally { directory.deleteRecursively() }
    }
}
