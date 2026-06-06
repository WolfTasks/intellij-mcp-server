package com.example.mcpserver.server

import com.example.mcpserver.tools.McpToolRegistry
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class HttpMcpServer(
    private val project: Project,
    private val registry: McpToolRegistry,
    private val port: Int = 6789
) {
    @Volatile
    private var server: HttpServer? = null

    fun start() {
        check(server == null) { "MCP server is already running" }
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        httpServer.createContext("/mcp") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val responseBytes = try {
                val request = McpProtocol.parseRequest(body)
                val response = dispatch(request)
                McpProtocol.serializeResponse(response).toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                val errResp = McpProtocol.errorResponse(JsonNull, -32700, "Parse error: ${e.message}")
                McpProtocol.serializeResponse(errResp).toByteArray(Charsets.UTF_8)
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        httpServer.executor = Executors.newCachedThreadPool()
        httpServer.start()
        server = httpServer
    }

    fun stop() {
        server?.stop(2)
        server = null
    }

    private fun dispatch(request: McpProtocol.Request): McpProtocol.Response {
        return when (request.method) {
            "initialize" -> McpProtocol.successResponse(
                id = request.id,
                result = buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                    put("serverInfo", buildJsonObject {
                        put("name", "intellij-mcp-server")
                        put("version", "1.0.0")
                    })
                }
            )
            "tools/list" -> McpProtocol.successResponse(
                id = request.id,
                result = buildJsonObject { put("tools", registry.listTools()) }
            )
            "tools/call" -> {
                val name = request.toolName()
                    ?: return McpProtocol.errorResponse(request.id, -32602, "Missing tool name")
                val args = request.toolArguments()
                val toolResult = registry.callTool(name, args, project)
                McpProtocol.successResponse(
                    id = request.id,
                    result = buildJsonObject {
                        put("content", buildJsonArray {
                            addJsonObject {
                                put("type", "text")
                                put("text", toolResult.toString())
                            }
                        })
                    }
                )
            }
            else -> McpProtocol.errorResponse(request.id, -32601, "Method not found: ${request.method}")
        }
    }
}
