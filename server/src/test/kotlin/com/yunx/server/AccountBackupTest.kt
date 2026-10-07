package com.yunx.server

import com.yunx.app.data.backup.AuthCrypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class AccountBackupTest {
    private val password = "local-backup-test"
    @Test fun windowsArchiveFieldsAreCompatible() {
        val root = JSONObject().put("app", "yunx_auth_backup").put("version", 1).put("githubToken", "github-secret")
            .put("accounts", JSONArray().put(JSONObject().put("platform", "ilanzou").put("appToken", "token")
                .put("uuid", "device").put("password", "unused-login-password"))
                .put(JSONObject().put("platform", "xunlei").put("accessToken", "app-token").put("authType", "webToken")))
        val decoded = AccountBackup.decode(AuthCrypto.encrypt(root.toString(), password), password)
        assertEquals("token", decoded.getValue("ILANZOU").getString("accessToken"))
        assertFalse(decoded.getValue("ILANZOU").has("password"))
        assertEquals("webToken", decoded.getValue("XUNLEI").getString("authType"))
        assertEquals("github-secret", decoded.getValue("GITHUB").getString("accessToken"))
        assertFailsWith<IllegalStateException> { AccountBackup.decode(AuthCrypto.encrypt(root.toString(), password), "wrong-password") }
    }
    @Test fun exportAndAtomicRestorePreserveOtherPlatforms() {
        val directory = Files.createTempDirectory("yunx-backup-test").toFile()
        try {
            val source = Credentials(directory.resolve("source.enc"))
            source.save("QUARK", JSONObject().put("cookie", "secret-cookie"))
            source.save("ILANZOU", JSONObject().put("accessToken", "secret-token").put("uuid", "device"))
            val archive = AccountBackup.export(source, password)
            assertFalse(archive.contains("secret"))
            val plain = JSONObject(AuthCrypto.decrypt(archive, password))
            assertEquals("secret-token", plain.getJSONArray("accounts").getJSONObject(1).getString("appToken"))
            val destination = Credentials(directory.resolve("destination.enc"))
            destination.save("UC", JSONObject().put("cookie", "keep"))
            destination.saveAll(AccountBackup.decode(archive, password))
            val reopened = Credentials(directory.resolve("destination.enc"))
            assertEquals("secret-cookie", reopened.credential("QUARK"))
            assertEquals("secret-token", reopened.credential("ILANZOU"))
            assertEquals("keep", reopened.credential("UC"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsInvalidWholeDocumentBeforeSaving() {
        val invalid = JSONObject().put("app", "yunx_auth_backup").put("version", 1)
            .put("accounts", JSONArray().put(JSONObject().put("platform", "quark").put("cookie", "valid"))
                .put(JSONObject().put("platform", "ilanzou").put("appToken", "missing-uuid")))
        assertFailsWith<IllegalArgumentException> { AccountBackup.decode(AuthCrypto.encrypt(invalid.toString(), password), password) }
    }
}
