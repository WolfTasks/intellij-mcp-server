package com.example.mcpserver

import com.example.mcpserver.auth.McpAuthStore
import com.example.mcpserver.proxy.ProxyInstaller
import com.example.mcpserver.server.HttpMcpServer
import com.example.mcpserver.tools.DiagnosticsTool
import com.example.mcpserver.tools.ExternalDepsTool
import com.example.mcpserver.tools.FileContentTool
import com.example.mcpserver.tools.InternalDepsTool
import com.example.mcpserver.tools.McpToolRegistry
import com.example.mcpserver.tools.ProjectStructureTool
import com.example.mcpserver.tools.SymbolSearchTool
import com.intellij.ide.impl.isTrusted
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpPluginService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(McpPluginService::class.java)
        private val PORTS = 6789..6800

        /**
         * Must be unique across all plugins: the bundled JetBrains plugin com.intellij.mcpServer
         * already registers "MCP Server". Keep in sync with notificationGroup in plugin.xml.
         */
        const val NOTIFICATION_GROUP_ID = "IntelliJ MCP Server (WolfTasks)"

        /** One store per IDE process; it serializes token creation across projects opening in parallel. */
        private val authStore by lazy { McpAuthStore() }
    }

    @Volatile
    private var server: HttpMcpServer? = null
    @Volatile
    private var serverPort: Int? = null

    /** Port of the running server, or null if none is running. */
    internal val port: Int? get() = serverPort

    init {
        LOG.info("McpPluginService initializing for project: ${project.name}")
        if (ApplicationManager.getApplication().isUnitTestMode) {
            // Light test projects must not bind real ports or write discovery records.
            LOG.info("MCP server not started: unit test mode")
        } else if (project.isTrusted()) {
            // Untrusted projects (e.g. freshly cloned, "Preview in Safe Mode") must not expose their
            // contents over a local server automatically.
            ApplicationManager.getApplication().executeOnPooledThread {
                startServer()
            }
        } else {
            LOG.info("MCP server not started: project '${project.name}' is not trusted")
            notify("MCP server not started: this project is not trusted.", NotificationType.INFORMATION)
        }
    }

    private fun startServer() {
        try {
            val token = try {
                authStore.loadOrCreateToken()
            } catch (e: Exception) {
                LOG.warn("MCP auth token could not be loaded or created in ${authStore.dir}", e)
                notify("MCP server not started: auth token could not be created in ${authStore.dir} (${e.message})", NotificationType.ERROR)
                return
            }
            runCatching { ProxyInstaller(authStore).install() }
                .onFailure { LOG.warn("Could not install the MCP stdio proxy", it) }
            val registry = McpToolRegistry(listOf(
                ProjectStructureTool(),
                FileContentTool(),
                ExternalDepsTool(),
                InternalDepsTool(),
                SymbolSearchTool(),
                DiagnosticsTool()
            ))
            val foreignPorts = mutableListOf<Int>()
            for (port in PORTS) {
                val httpServer = HttpMcpServer(
                    project, registry, port,
                    serverName = "intellij-mcp-server (${project.name})",
                    token = token,
                    projectName = project.name,
                )
                try {
                    httpServer.start()
                } catch (e: java.net.BindException) {
                    LOG.info("Port $port in use, trying next")
                    if (!authStore.isLiveInstance(port)) foreignPorts += port
                    continue
                }
                server = httpServer
                serverPort = port
                publishInstance(port)
                LOG.info("MCP server started on port $port")
                notify("MCP server for '${project.name}' started on port $port", NotificationType.INFORMATION)
                warnAboutForeignPorts(foreignPorts)
                return
            }
            LOG.warn("MCP server could not start: all ports ${PORTS.first}-${PORTS.last} busy")
            notify("MCP server could not start: all ports ${PORTS.first}–${PORTS.last} busy", NotificationType.ERROR)
            warnAboutForeignPorts(foreignPorts)
        } catch (e: Throwable) {
            LOG.error("MCP server failed to start unexpectedly", e)
            notify("MCP server failed to start: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun publishInstance(port: Int) {
        val record = buildJsonObject {
            put("port", port)
            put("url", "http://127.0.0.1:$port${HttpMcpServer.PATH}")
            put("projectName", project.name)
            put("projectPath", project.basePath)
            put("pid", ProcessHandle.current().pid())
        }
        try {
            authStore.writeInstance(port, record.toString())
        } catch (e: Exception) {
            LOG.warn("Could not write MCP discovery record for port $port", e)
        }
    }

    /** A busy port without a live discovery record is held by something other than this plugin. */
    private fun warnAboutForeignPorts(ports: List<Int>) {
        if (ports.isEmpty()) return
        val list = ports.joinToString(", ")
        LOG.warn("MCP ports in use by a program that is not an IntelliJ MCP server: $list")
        notify(
            "Port(s) $list are used by another program, not by an IntelliJ MCP server. " +
                "A client configured for these ports would send its token there — check what holds them.",
            NotificationType.WARNING,
        )
    }

    override fun dispose() {
        server?.stop()
        server = null
        serverPort?.let { port -> runCatching { authStore.removeInstance(port) } }
        serverPort = null
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            ?.createNotification("IntelliJ MCP Server", message, type)
            ?.notify(project)
    }
}
