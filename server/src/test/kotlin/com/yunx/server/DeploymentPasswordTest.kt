package com.yunx.server

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import org.junit.Test

class DeploymentPasswordTest {
    private fun inDirectory(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("yunx-password-test").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }

    @Test
    fun `generated password is random private and stable across starts`() = inDirectory { dir ->
        val password = DeploymentPassword.load(dir, null)
        assertEquals(32, password.length)
        assertTrue(password.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(password, DeploymentPassword.load(dir, ""))
        val file = File(dir, "initial-password.txt")
        assertEquals(password, file.readText().trim())
        if (Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
        }
        inDirectory { other -> assertNotEquals(password, DeploymentPassword.load(other, null)) }
    }

    @Test
    fun `explicit password overrides generation without changing stored password`() = inDirectory { dir ->
        val configured = "explicit-test-password"
        assertEquals(configured, DeploymentPassword.load(dir, configured))
        assertFalse(File(dir, "initial-password.txt").exists())
        val generated = DeploymentPassword.load(dir, null)
        assertEquals(configured, DeploymentPassword.load(dir, configured))
        assertEquals(generated, DeploymentPassword.load(dir, null))
    }

    @Test
    fun `invalid password files and settings fail without silently rotating`() = inDirectory { dir ->
        assertFailsWith<IllegalArgumentException> { DeploymentPassword.load(dir, "short") }
        val file = File(dir, "initial-password.txt")
        file.writeText("corrupt")
        assertFailsWith<IllegalArgumentException> { DeploymentPassword.load(dir, null) }
        assertEquals("corrupt", file.readText())
        assertFailsWith<IllegalArgumentException> { DeploymentPassword.load(dir, "first-line\nsecond-line") }
    }
}
