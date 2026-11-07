package com.example.mcpserver

import com.example.mcpserver.auth.McpAuthStore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class McpPluginServiceTest : BasePlatformTestCase() {

    fun `test no server is started in unit test mode`() {
        assertNull(project.getService(McpPluginService::class.java).port)
    }

    fun `test tests never write to the real state directory`() {
        assertFalse(McpAuthStore.defaultDir().startsWith(Path.of(System.getProperty("user.home"), ".intellij-mcp-server")))
    }
}
