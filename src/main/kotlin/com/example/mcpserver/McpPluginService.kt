package com.example.mcpserver

import com.example.mcpserver.server.HttpMcpServer
import com.example.mcpserver.tools.DiagnosticsTool
import com.example.mcpserver.tools.ExternalDepsTool
import com.example.mcpserver.tools.FileContentTool
import com.example.mcpserver.tools.InternalDepsTool
import com.example.mcpserver.tools.McpToolRegistry
import com.example.mcpserver.tools.ProjectStructureTool
import com.example.mcpserver.tools.SymbolSearchTool
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

class McpPluginService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(McpPluginService::class.java)
    }

    @Volatile
    private var server: HttpMcpServer? = null

    init {
        LOG.info("McpPluginService initializing for project: ${project.name}")
        ApplicationManager.getApplication().executeOnPooledThread {
            startServer()
        }
    }

    private fun startServer() {
        try {
            val registry = McpToolRegistry(listOf(
                ProjectStructureTool(),
                FileContentTool(),
                ExternalDepsTool(),
                InternalDepsTool(),
                SymbolSearchTool(),
                DiagnosticsTool()
            ))
            for (port in 6789..6800) {
                val httpServer = HttpMcpServer(project, registry, port)
                try {
                    httpServer.start()
                    server = httpServer
                    LOG.info("MCP server started on port $port")
                    notify("MCP server started on port $port", NotificationType.INFORMATION)
                    return
                } catch (e: java.net.BindException) {
                    LOG.info("Port $port in use, trying next")
                }
            }
            LOG.warn("MCP server could not start: all ports 6789-6800 busy")
            notify("MCP server could not start: all ports 6789–6800 busy", NotificationType.ERROR)
        } catch (e: Throwable) {
            LOG.error("MCP server failed to start unexpectedly", e)
            notify("MCP server failed to start: ${e.message}", NotificationType.ERROR)
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
