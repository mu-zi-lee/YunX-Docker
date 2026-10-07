package com.yunx.server

import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class LoginAuthTest {
    @Test fun `login attempts are bounded and tokens expire through logout`() {
        val auth = LoginAuth(null, "test-password-strong")
        repeat(10) { assertNull(auth.login("wrong-password", "blocked")) }
        assertNull(auth.login("test-password-strong", "blocked"))
        val session = auth.login("test-password-strong", "other")
        assertNotNull(session)
        assertTrue(auth.valid(session))
        assertFalse(auth.valid("forged"))
        auth.logout(session)
        assertFalse(auth.valid(session))
    }
    @Test fun `environment managed passwords cannot be changed in the UI`() {
        val auth = LoginAuth(null, "test-password-strong", true)
        assertFailsWith<IllegalStateException> { auth.change("test-password-strong", "new-test-password") }
        assertTrue(auth.verify("test-password-strong"))
    }
    @Test fun `environment override preserves the previously saved login password`() {
        val directory = Files.createTempDirectory("yunx-auth-test").toFile()
        try {
            val file = directory.resolve("login-auth.json")
            LoginAuth(file, "original-test-password")
            assertTrue(LoginAuth(file, "environment-test-password", true).verify("environment-test-password"))
            val restored = LoginAuth(file, "ignored-test-password")
            assertTrue(restored.verify("original-test-password"))
            assertFalse(restored.verify("environment-test-password"))
        } finally { directory.deleteRecursively() }
    }
}
