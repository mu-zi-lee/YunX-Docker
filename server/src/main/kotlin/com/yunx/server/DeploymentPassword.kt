package com.yunx.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

object DeploymentPassword {
    fun load(dataDir: File, configured: String?): String {
        configured?.takeIf { it.isNotBlank() }?.let {
            validate(it)
            return it
        }
        val file = File(dataDir, "initial-password.txt")
        if (file.exists()) {
            val password = file.readText(Charsets.UTF_8).trimEnd('\r', '\n')
            validate(password)
            makePrivate(file)
            return password
        }
        check(dataDir.isDirectory || dataDir.mkdirs()) { "Cannot create password directory" }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val password = encoder.encodeToString(bytes)
        val attributes = if (Files.getFileStore(dataDir.toPath()).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else emptyArray()
        val temporary = Files.createTempFile(dataDir.toPath(), ".initial-password-", ".tmp", *attributes).toFile()
        try {
            makePrivate(temporary)
            temporary.writeText("$password\n", Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        println("Generated login password saved to ${file.absolutePath}; read this file from the NAS container terminal.")
        return password
    }

    private fun validate(password: String) {
        require(password.length in 12..256) { "Login password must contain 12 to 256 characters" }
        require('\n' !in password && '\r' !in password) { "Login password cannot contain newlines" }
    }

    private fun makePrivate(file: File) {
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } else {
            check(file.setReadable(false, false) && file.setWritable(false, false) &&
                file.setReadable(true, true) && file.setWritable(true, true)) { "Cannot protect password file" }
        }
    }
}
