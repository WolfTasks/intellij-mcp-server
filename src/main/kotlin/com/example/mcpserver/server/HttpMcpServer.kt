package com.example.mcpserver.server

import com.example.mcpserver.tools.McpToolRegistry
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HttpMcpServer(
    private val project: Project,
    private val registry: McpToolRegistry,
    private val port: Int = 6789,
    private val serverName: String = "intellij-mcp-server",
    /** Shared secret every request must carry as `Authorization: Bearer <token>`. */
    token: String,
    /** Name of the served project; a client may pin it via the [PROJECT_HEADER] request header. */
    private val projectName: String = "",
) {
    companion object {
        const val PATH = "/mcp"
        const val MAX_BODY_BYTES = 1024 * 1024
        /** Optional request header: if sent, it must equal the served project's name (else 421). */
        const val PROJECT_HEADER = "X-IntelliJ-Project"
        private const val WORKER_THREADS = 4
        private val LOG = Logger.getInstance(HttpMcpServer::class.java)
    }

    init {
        require(token.isNotBlank()) { "MCP auth token must not be blank" }
    }

    private val tokenBytes = token.toByteArray(Charsets.UTF_8)

    @Volatile
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** Host headers a legitimate local client sends. Anything else (e.g. a rebound domain) is refused. */
    private val allowedHosts = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")

    fun start() {
        check(server == null) { "MCP server is already running" }
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        httpServer.createContext(PATH) { exchange ->
            try {
                handle(exchange)
            } catch (e: Exception) {
                LOG.warn("MCP request handling failed", e)
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally {
                exchange.close()
            }
        }
        val pool = Executors.newFixedThreadPool(WORKER_THREADS)
        httpServer.executor = pool
        httpServer.start()
        executor = pool
        server = httpServer
    }

    fun stop() {
        server?.stop(2)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handle(exchange: HttpExchange) {
        // createContext matches by prefix — serve the exact endpoint only.
        if (exchange.requestURI.path != PATH) return reject(exchange, 404)
        if (exchange.requestMethod != "POST") return reject(exchange, 405)
        val host = exchange.requestHeaders.getFirst("Host")?.lowercase()
        if (host == null || host !in allowedHosts) return reject(exchange, 403)
        // MCP clients are not browsers and send no Origin; browsers always do on cross-site POSTs.
        if (exchange.requestHeaders.containsKey("Origin")) return reject(exchange, 403)
        if (!isAuthorized(exchange.requestHeaders.getFirst("Authorization"))) {
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"intellij-mcp-server\"")
            return reject(exchange, 401)
        }
        // Wrong IDE instance / project behind this port (e.g. ports shifted because another project
        // started first): fail loudly instead of answering about a different project.
        exchange.requestHeaders.getFirst(PROJECT_HEADER)?.let { expected ->
            if (decodeHeader(expected) != projectName) return reject(exchange, 421)
        }
        val contentType = exchange.requestHeaders.getFirst("Content-Type")
            ?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "application/json") return reject(exchange, 415)

        val bodyBytes = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (bodyBytes.size > MAX_BODY_BYTES) return reject(exchange, 413)

        val response = try {
            dispatch(McpProtocol.parseRequest(bodyBytes.toString(Charsets.UTF_8)))
        } catch (e: Exception) {
            LOG.debug("MCP parse error", e)
            McpProtocol.errorResponse(JsonNull, -32700, "Parse error")
        }
        val responseBytes = McpProtocol.serializeResponse(response).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }

    /** `Authorization: Bearer <token>`, scheme case-insensitive, token compared in constant time. */
    private fun isAuthorized(header: String?): Boolean {
        if (header == null) return false
        val parts = header.trim().split(' ', limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return false
        return MessageDigest.isEqual(parts[1].trim().toByteArray(Charsets.UTF_8), tokenBytes)
    }

    /** Header values are Latin-1 on the wire; non-ASCII project names can be sent percent-encoded (UTF-8). */
    private fun decodeHeader(value: String): String =
        runCatching { URLDecoder.decode(value.trim().replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(value.trim())

    private fun reject(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
    }

    private fun dispatch(request: McpProtocol.Request): McpProtocol.Response {
        return when (request.method) {
            "initialize" -> McpProtocol.successResponse(
                id = request.id,
                result = buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                    put("serverInfo", buildJsonObject {
                        put("name", serverName)
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
