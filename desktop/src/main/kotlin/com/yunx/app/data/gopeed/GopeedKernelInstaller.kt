package com.yunx.app.data.gopeed

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

object GopeedKernelInstaller {
    fun install(archive: File, target: File, platform: GopeedPlatform): File {
        require(archive.isFile) { "内核文件不存在" }
        target.parentFile.mkdirs()
        val temporary = Files.createTempFile(target.parentFile.toPath(), ".gopeed-", ".tmp").toFile()
        try {
            if (archive.extension.equals("zip", true)) {
                var found = false
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.replace('\\', '/').substringAfterLast('/')
                                .equals(platform.executableName, true)) {
                            copyBounded(zip, temporary)
                            found = true
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
                check(found) { "压缩包中没有 ${platform.executableName}，请使用 ${platform.system}-${platform.architecture} 内核包" }
            } else archive.inputStream().use { copyBounded(it, temporary) }
            check(temporary.length() > 0) { "内核文件为空" }
            if (platform.system != "windows") check(temporary.setExecutable(true, true)) { "无法设置内核执行权限" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { temporary.delete() }
    }
    private fun copyBounded(input: InputStream, target: File) {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= 300L * 1024 * 1024) { "内核文件超过 300 MiB" }
                output.write(buffer, 0, count)
            }
        }
    }
}
