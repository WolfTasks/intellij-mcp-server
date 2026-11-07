package com.example.mcpserver.auth

import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class McpAuthStoreTest {

    private val root: Path = Files.createTempDirectory("mcp-auth-test")
    private val store = McpAuthStore(root.resolve("state"))

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `token is generated once and reused`() {
        val first = store.loadOrCreateToken()
        assertTrue("token too short: ${first.length}", first.length >= 43)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(first, store.loadOrCreateToken())
        assertEquals(first, McpAuthStore(store.dir).loadOrCreateToken())
        assertEquals(first, Files.readString(store.tokenFile).trim())
    }

    @Test
    fun `generated tokens differ`() {
        assertNotEquals(McpAuthStore.generateToken(), McpAuthStore.generateToken())
    }

    @Test
    fun `empty or truncated token file is replaced`() {
        Files.createDirectories(store.dir)
        Files.writeString(store.tokenFile, "short\n")
        val token = store.loadOrCreateToken()
        assertNotEquals("short", token)
        assertEquals(token, Files.readString(store.tokenFile).trim())
    }

    @Test
    fun `token file and directory are restricted to the owner`() {
        store.loadOrCreateToken()
        val posix = Files.getFileAttributeView(store.tokenFile, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.tokenFile)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.dir)))
            return
        }
        for (path in listOf(store.tokenFile, store.dir)) {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: continue
            val principals = acl.acl.map { it.principal().name }
            assertEquals("ACL of $path: ${acl.acl}", 1, principals.size)
            assertTrue("ACL of $path: ${acl.acl}", principals.single().endsWith("\\" + System.getProperty("user.name"), ignoreCase = true))
        }
    }

    /**
     * Checks the effective Windows ACL with the OS's own tool rather than through the Java view that
     * wrote it: exactly one entry, for the account the test runs as (SID from `whoami /user`), the
     * state directory protected against inheritance from its parent, and inheritable entries on
     * directories. Also covers a token file that existed before with default permissions.
     */
    @Test
    fun `windows ACL grants only the current user, verified with icacls`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // A pre-existing token file with inherited permissions must be locked down too.
        Files.createDirectories(store.dir)
        Files.writeString(store.tokenFile, McpAuthStore.generateToken() + "\n")
        store.loadOrCreateToken()
        store.writeInstance(6792, "{}")

        val sid = run("whoami", "/user", "/fo", "csv", "/nh").trim().split(",").last().trim('"')
        assertTrue("unexpected SID: $sid", sid.startsWith("S-1-"))

        for ((path, isDir) in listOf(store.dir to true, store.tokenFile to false,
                store.instancesDir to true, store.instancesDir.resolve("6792.json") to false)) {
            // /save writes the raw SDDL ("D:(A;flags;rights;;;SID)") — language independent, SIDs not names.
            val out = root.resolve("acl-${path.fileName}.txt")
            run("icacls", path.toString(), "/save", out.toString())
            val sddl = Files.readAllBytes(out).toString(Charsets.UTF_16LE).lines().map { it.trim('﻿', ' ') }
                .first { it.contains("D:") }
            val aces = Regex("""\(([^)]*)\)""").findAll(sddl.substringAfter("D:")).map { it.groupValues[1].split(';') }.toList()
            assertEquals("ACEs of $path: $sddl", 1, aces.size)
            val (type, flags, _, _, _, trustee) = aces.single()
            assertEquals("ACE type of $path: $sddl", "A", type)
            assertEquals("trustee of $path: $sddl", sid, trustee)
            if (path == store.dir) {
                // The state directory itself is cut off from the home directory's ACL ("P" = protected)...
                assertTrue("DACL of $path not protected: $sddl", sddl.contains("D:P"))
                assertFalse("inherited ACE on $path: $sddl", flags.contains("ID"))
            }
            // ...so whatever lies below may inherit from it: that is the same single owner entry.
            if (isDir) assertTrue("dir ACE not inheritable: $sddl", flags.contains("OI") && flags.contains("CI"))
        }
    }

    private fun run(vararg cmd: String): String {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = proc.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals("${cmd.joinToString(" ")}: $text", 0, proc.waitFor())
        return text
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    @Test
    fun `no temp files are left behind`() {
        store.loadOrCreateToken()
        store.writeInstance(6789, "{}")
        Files.list(store.dir).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
        Files.list(store.instancesDir).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
    }

    @Test
    fun `instance records are written, replaced and removed`() {
        val file = store.writeInstance(6790, """{"port":6790}""")
        assertEquals(store.instancesDir.resolve("6790.json"), file)
        assertEquals("""{"port":6790}""", Files.readString(file))
        store.writeInstance(6790, """{"port":6790,"v":2}""")
        assertEquals("""{"port":6790,"v":2}""", Files.readString(file))
        store.removeInstance(6790)
        assertFalse(Files.exists(file))
        store.removeInstance(6790)   // idempotent
    }

    @Test
    fun `live instance detection uses the recorded pid`() {
        assertFalse(store.isLiveInstance(6791))
        store.writeInstance(6791, """{"port":6791,"pid":${ProcessHandle.current().pid()}}""")
        assertTrue(store.isLiveInstance(6791))
        store.writeInstance(6791, """{"port":6791,"pid":${Long.MAX_VALUE}}""")
        assertFalse(store.isLiveInstance(6791))
        store.writeInstance(6791, "not json")
        assertFalse(store.isLiveInstance(6791))
    }
}
