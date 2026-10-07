package com.yunx.server

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class LoginAuth(private val file: File?, initial: String, val managed: Boolean = false) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private var record = if (!managed && file?.exists() == true) JSONObject(file.readText()) else encode(initial)
    private val sessions = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Pair<Int, Long>>()
    init { if (file != null && !file.exists() && !managed) persist(record) }

    private fun encode(password: String): JSONObject {
        val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return JSONObject().put("salt", encoder.encodeToString(salt)).put("hash", encoder.encodeToString(hash(password, salt)))
            .put("iterations", 210000).put("version", 1)
    }
    private fun hash(password: String, salt: ByteArray, iterations: Int = 210000): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    @Synchronized fun verify(password: String): Boolean {
        if (password.length !in 12..256) return false
        val decoder = Base64.getUrlDecoder()
        val iterations = record.optInt("iterations", 210000)
        check(iterations in 100000..1000000) { "Login password record is invalid" }
        return MessageDigest.isEqual(hash(password, decoder.decode(record.getString("salt")), iterations), decoder.decode(record.getString("hash")))
    }
    @Synchronized fun login(password: String, client: String): String? {
        val now = System.currentTimeMillis()
        failures.entries.removeIf { now - it.value.second > 600000 }
        if ((failures[client]?.first ?: 0) >= 10) return null
        if (!verify(password)) {
            if (failures.size > 1000) failures.clear()
            failures[client] = ((failures[client]?.first ?: 0) + 1) to now
            return null
        }
        failures.remove(client)
        sessions.entries.removeIf { it.value < now }
        if (sessions.size >= 100) sessions.remove(sessions.keys.first())
        val token = encoder.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        sessions[token] = now + 43200000
        return token
    }
    @Synchronized fun valid(token: String?): Boolean = token != null && (sessions[token] ?: 0) > System.currentTimeMillis()
    @Synchronized fun logout(token: String?) { sessions.remove(token) }
    @Synchronized fun change(current: String, next: String) {
        check(!managed) { "密码由 YUNX_PASSWORD 管理，请修改部署配置" }
        require(verify(current)) { "当前密码不正确" }
        require(next.length in 12..256 && '\n' !in next && '\r' !in next) { "新密码需要 12 到 256 个字符" }
        require(current != next) { "新密码不能与当前密码相同" }
        val updated = encode(next)
        persist(updated)
        record = updated
        sessions.clear()
        file?.parentFile?.resolve("initial-password.txt")?.delete()
    }
    private fun persist(value: JSONObject) {
        val target = file ?: return
        target.parentFile.mkdirs()
        val tmp = Files.createTempFile(target.parentFile.toPath(), ".login-", ".tmp")
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(tmp, value.toString())
            Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
    }
}
