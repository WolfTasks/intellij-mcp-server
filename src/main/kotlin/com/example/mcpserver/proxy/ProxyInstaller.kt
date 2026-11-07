package com.example.mcpserver.proxy

import com.example.mcpserver.auth.McpAuthStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies the bundled stdio proxy to `<state dir>/bin/intellij_mcp_proxy.py` so MCP clients have a
 * stable path to launch. Rewrites the file only when its content differs (plugin update).
 */
class ProxyInstaller(
    private val store: McpAuthStore,
    private val resource: String = DEFAULT_RESOURCE,
) {
    companion object {
        const val FILE_NAME = "intellij_mcp_proxy.py"
        const val DEFAULT_RESOURCE = "/proxy/intellij_mcp_proxy.py"
    }

    fun install(): Path {
        val bundled = ProxyInstaller::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw IllegalStateException("Bundled proxy resource $resource not found")
        val target = store.binDir.resolve(FILE_NAME)
        synchronized(McpAuthStore.WRITE_LOCK) {
            val current = if (Files.isRegularFile(target)) runCatching { Files.readAllBytes(target) }.getOrNull() else null
            if (current != null && current.contentEquals(bundled)) return target
            // The script is UTF-8 (ASCII today), so the String round trip is lossless.
            store.writePrivateFile(target, String(bundled, Charsets.UTF_8))
        }
        return target
    }
}
