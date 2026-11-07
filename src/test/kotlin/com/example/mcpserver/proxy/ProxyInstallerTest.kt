package com.example.mcpserver.proxy

import com.example.mcpserver.auth.McpAuthStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ProxyInstallerTest {

    private val root: Path = Files.createTempDirectory("mcp-proxy-test")
    private val store = McpAuthStore(root.resolve("state"))

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun bundled(): ByteArray =
        ProxyInstallerTest::class.java.getResourceAsStream("/proxy/intellij_mcp_proxy.py")!!.use { it.readBytes() }

    @Test
    fun `installs the bundled proxy`() {
        val file = ProxyInstaller(store).install()

        assertEquals(store.binDir.resolve(ProxyInstaller.FILE_NAME), file)
        assertTrue(Files.isRegularFile(file))
        assertArrayEquals(bundled(), Files.readAllBytes(file))
        assertTrue(Files.readString(file).startsWith("#!/usr/bin/env python3"))
    }

    @Test
    fun `reinstall with identical content does not rewrite`() {
        val installer = ProxyInstaller(store)
        val file = installer.install()
        val old = FileTime.fromMillis(1_000_000_000_000L)
        Files.setLastModifiedTime(file, old)

        installer.install()

        assertEquals(old, Files.getLastModifiedTime(file))
    }

    @Test
    fun `outdated proxy is replaced`() {
        val file = store.binDir.resolve(ProxyInstaller.FILE_NAME)
        Files.createDirectories(store.binDir)
        Files.writeString(file, "old")

        ProxyInstaller(store).install()

        assertArrayEquals(bundled(), Files.readAllBytes(file))
    }

    @Test
    fun `missing resource fails loudly`() {
        assertThrows(IllegalStateException::class.java) { ProxyInstaller(store, "/nope.py").install() }
    }

    @Test
    fun `concurrent installs do not corrupt the file or leave temp files`() {
        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<Path> { start.await(); ProxyInstaller(store).install() }
            }
            start.countDown()
            results.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertArrayEquals(bundled(), Files.readAllBytes(store.binDir.resolve(ProxyInstaller.FILE_NAME)))
        Files.list(store.binDir).use { files ->
            assertEquals(listOf(ProxyInstaller.FILE_NAME), files.map { it.fileName.toString() }.toList())
        }
    }
}
