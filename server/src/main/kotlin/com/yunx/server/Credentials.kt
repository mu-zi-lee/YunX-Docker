package com.yunx.server

import com.yunx.app.data.security.FileCredentialCipher
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A single encrypted document, atomically replaced and never returned to the browser. */
class Credentials(private val file: File) {
    private val cipher = FileCredentialCipher()
    // Create the key before HTTP/download workers can initialize their own cipher concurrently.
    init { cipher.encrypt("", "server-key-init") }
    private val entries = if (file.exists()) {
        JSONObject(cipher.decrypt(file.readText(), "server-credentials"))
    } else JSONObject()

    @Synchronized
    fun get(platform: String): JSONObject =
        JSONObject(entries.optJSONObject(platform)?.toString() ?: "{}")

    @Synchronized
    fun save(platform: String, value: JSONObject) {
        val next = JSONObject(entries.toString()).put(platform, value)
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(cipher.encrypt(next.toString(), "server-credentials"))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        entries.put(platform, JSONObject(value.toString()))
    }

    fun credential(platform: String): String {
        val entry = get(platform)
        return entry.optString("cookie").ifBlank { entry.optString("accessToken") }
    }
}
