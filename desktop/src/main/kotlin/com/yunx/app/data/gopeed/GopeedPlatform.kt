package com.yunx.app.data.gopeed

data class GopeedPlatform(val system: String, val architecture: String) {
    val executableName get() = if (system == "windows") "gopeed.exe" else "gopeed"
    fun archiveName(version: String) = "gopeed-web-$version-$system-$architecture.zip"
    companion object {
        fun current() = detect(System.getProperty("os.name"), System.getProperty("os.arch"))
        fun detect(os: String, arch: String): GopeedPlatform {
            val system = when {
                os.lowercase().startsWith("windows") -> "windows"
                os.lowercase().startsWith("mac") || os.equals("darwin", true) -> "macos"
                os.lowercase().startsWith("linux") -> "linux"
                else -> error("不支持此系统的 Gopeed 内核")
            }
            val architecture = when (arch.lowercase()) {
                "amd64", "x86_64" -> "amd64"
                "aarch64", "arm64" -> "arm64"
                "x86", "i386", "i686" -> "386"
                else -> error("不支持此架构的 Gopeed 内核")
            }
            return GopeedPlatform(system, architecture)
        }
    }
}
