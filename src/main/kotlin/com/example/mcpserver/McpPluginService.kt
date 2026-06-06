package com.example.mcpserver

import com.example.mcpserver.server.HttpMcpServer
import com.example.mcpserver.tools.McpToolRegistry
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

class McpPluginService(private val project: Project) : Disposable {

    private var server: HttpMcpServer? = null

    init {
        startServer(6789)
    }

    private fun startServer(port: Int) {
        val registry = McpToolRegistry(emptyList())
        val httpServer = HttpMcpServer(project, registry, port)
        try {
            httpServer.start()
            server = httpServer
            notify("MCP server started on port $port", NotificationType.INFORMATION)
        } catch (e: java.net.BindException) {
            if (port < 6800) {
                startServer(port + 1)
            } else {
                notify("MCP server could not start: all ports 6789–6800 busy", NotificationType.ERROR)
            }
        }
    }

    override fun dispose() {
        server?.stop()
        server = null
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MCP Server")
            ?.createNotification("IntelliJ MCP Server", message, type)
            ?.notify(project)
    }
}
