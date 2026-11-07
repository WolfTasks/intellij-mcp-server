package com.example.mcpserver.server

import com.example.mcpserver.testutil.fakeProject
import com.example.mcpserver.tools.McpTool
import com.example.mcpserver.tools.McpToolRegistry
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** Request validation in front of the JSON-RPC dispatcher, exercised with raw HTTP. */
class HttpMcpServerSecurityTest {

    private companion object {
        const val TOKEN = "test-token-0123456789abcdefghijklmnopqrstuvwxyz"
    }

    private object MarkerTool : McpTool {
        override val name = "marker"
        override val description = "returns a marker"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override fun execute(arguments: JsonObject, project: Project): JsonElement =
            buildJsonObject { put("content", "MARKER") }
    }

    private val port = ServerSocket(0).use { it.localPort }
    private val server = HttpMcpServer(fakeProject(), McpToolRegistry(listOf(MarkerTool)), port, token = TOKEN, projectName = "Demo Project")
    private val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"marker","arguments":{}}}"""

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.stop()

    private fun send(
        path: String = "/mcp",
        host: String? = "127.0.0.1:$port",
        contentType: String? = "application/json",
        authorization: String? = "Bearer $TOKEN",
        extraHeaders: List<String> = emptyList(),
        payload: String = body,
    ): String {
        val headers = buildList {
            host?.let { add("Host: $it") }
            contentType?.let { add("Content-Type: $it") }
            authorization?.let { add("Authorization: $it") }
            addAll(extraHeaders)
            add("Content-Length: ${payload.toByteArray().size}")
            add("Connection: close")
        }
        val request = "POST $path HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n" + payload
        return Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().apply { write(request.toByteArray()); flush() }
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun status(response: String): Int = response.lineSequence().first().split(" ")[1].toInt()

    @Test
    fun `valid local request is served`() {
        val resp = send()
        assertEquals(200, status(resp))
        assertTrue(resp.contains("MARKER"))
    }

    @Test
    fun `localhost and ipv6 loopback host headers are accepted`() {
        assertEquals(200, status(send(host = "localhost:$port")))
        assertEquals(200, status(send(host = "[::1]:$port")))
    }

    @Test
    fun `content type with charset is accepted`() {
        assertEquals(200, status(send(contentType = "application/json; charset=utf-8")))
    }

    @Test
    fun `foreign host header is rejected`() {
        val resp = send(host = "rebind.example.test:$port")
        assertEquals(403, status(resp))
        assertFalse(resp.contains("MARKER"))
    }

    @Test
    fun `loopback host with wrong port is rejected`() {
        assertEquals(403, status(send(host = "127.0.0.1:${port + 1}")))
    }

    @Test
    fun `missing host header is rejected`() {
        assertEquals(403, status(send(host = null)))
    }

    @Test
    fun `requests carrying an Origin header are rejected`() {
        val resp = send(extraHeaders = listOf("Origin: http://127.0.0.1:$port"))
        assertEquals(403, status(resp))
        assertFalse(resp.contains("MARKER"))
    }

    @Test
    fun `non-json content type is rejected`() {
        val resp = send(contentType = "text/plain")
        assertEquals(415, status(resp))
        assertFalse(resp.contains("MARKER"))
    }

    @Test
    fun `missing content type is rejected`() {
        assertEquals(415, status(send(contentType = null)))
    }

    @Test
    fun `sub paths of mcp are not served`() {
        assertEquals(404, status(send(path = "/mcp/anything")))
        assertEquals(404, status(send(path = "/mcpx")))
    }

    @Test
    fun `oversized body is rejected`() {
        val big = "{\"pad\":\"" + "x".repeat(HttpMcpServer.MAX_BODY_BYTES) + "\"}"
        assertEquals(413, status(send(payload = big)))
    }

    @Test
    fun `parse errors do not echo internal exception details`() {
        val resp = send(payload = "{not json")
        assertEquals(200, status(resp))
        val json = Json.parseToJsonElement(resp.substringAfter("\r\n\r\n")).jsonObject
        assertEquals(-32700, json["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
        assertEquals("Parse error", json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `missing authorization is rejected with 401 and a bearer challenge`() {
        val resp = send(authorization = null)
        assertEquals(401, status(resp))
        assertTrue(resp.contains("WWW-Authenticate: Bearer", ignoreCase = true))
        assertFalse(resp.contains("MARKER"))
    }

    @Test
    fun `wrong token is rejected`() {
        assertEquals(401, status(send(authorization = "Bearer wrong")))
        assertEquals(401, status(send(authorization = "Bearer ${TOKEN}x")))
        assertEquals(401, status(send(authorization = "Bearer ${TOKEN.dropLast(1)}")))
        assertEquals(401, status(send(authorization = "Bearer ")))
    }

    @Test
    fun `non-bearer schemes are rejected`() {
        assertEquals(401, status(send(authorization = "Basic $TOKEN")))
        assertEquals(401, status(send(authorization = TOKEN)))
    }

    @Test
    fun `bearer scheme is case-insensitive`() {
        assertEquals(200, status(send(authorization = "bearer $TOKEN")))
    }

    @Test
    fun `authorization is checked before content type and body`() {
        assertEquals(401, status(send(authorization = null, contentType = "text/plain")))
    }

    @Test
    fun `matching project header is accepted`() {
        val resp = send(extraHeaders = listOf("${HttpMcpServer.PROJECT_HEADER}: Demo Project"))
        assertEquals(200, status(resp))
        assertTrue(resp.contains("MARKER"))
    }

    @Test
    fun `percent-encoded project header is accepted`() {
        assertEquals(200, status(send(extraHeaders = listOf("${HttpMcpServer.PROJECT_HEADER}: Demo%20Project"))))
    }

    @Test
    fun `project header naming another project is rejected with 421`() {
        val resp = send(extraHeaders = listOf("${HttpMcpServer.PROJECT_HEADER}: Other"))
        assertEquals(421, status(resp))
        assertFalse(resp.contains("MARKER"))
    }

    @Test
    fun `project header is only evaluated after authentication`() {
        assertEquals(401, status(send(authorization = null, extraHeaders = listOf("${HttpMcpServer.PROJECT_HEADER}: Other"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank token is refused at construction`() {
        HttpMcpServer(fakeProject(), McpToolRegistry(emptyList()), port, token = " ")
    }
}
