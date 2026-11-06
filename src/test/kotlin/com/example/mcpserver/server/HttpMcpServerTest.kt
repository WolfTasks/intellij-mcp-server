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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HttpMcpServerTest {

    private object EchoTool : McpTool {
        override val name = "echo"
        override val description = "echoes arguments"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override fun execute(arguments: JsonObject, project: Project): JsonElement =
            buildJsonObject { put("got", arguments) }
    }

    private val port = ServerSocket(0).use { it.localPort }
    private val server = HttpMcpServer(fakeProject(), McpToolRegistry(listOf(EchoTool)), port)
    private val client = HttpClient.newHttpClient()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.stop()

    private fun post(body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun rpc(body: String): JsonObject {
        val response = post(body)
        assertEquals(200, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null))
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    @Test
    fun `initialize returns protocol version, tool capability and server info`() {
        val resp = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertEquals(1, resp["id"]?.jsonPrimitive?.int)
        val result = resp["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
        assertNotNull(result["capabilities"]?.jsonObject?.get("tools"))
        assertEquals("intellij-mcp-server", result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `tools-list returns registered tools`() {
        val resp = rpc("""{"jsonrpc":"2.0","id":"abc","method":"tools/list"}""")
        assertEquals("abc", resp["id"]?.jsonPrimitive?.content)
        val tools = resp["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("echo"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `tools-call wraps tool result as text content`() {
        val resp = rpc(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"q":"hi"}}}"""
        )
        val content = resp["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", content["type"]?.jsonPrimitive?.content)
        val inner = Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("hi", inner["got"]?.jsonObject?.get("q")?.jsonPrimitive?.content)
    }

    @Test
    fun `tools-call without name is an invalid-params error`() {
        val resp = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{}}""")
        assertEquals(-32602, resp["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
        assertEquals(3, resp["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `unknown method is method-not-found`() {
        val resp = rpc("""{"jsonrpc":"2.0","id":4,"method":"nope"}""")
        val error = resp["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("nope"))
    }

    @Test
    fun `malformed body is a parse error with null id`() {
        val resp = rpc("this is not json")
        assertEquals(-32700, resp["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
        assertEquals(JsonNull, resp["id"])
    }

    @Test
    fun `non-POST requests are rejected with 405`() {
        val response = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).GET().build(),
            HttpResponse.BodyHandlers.discarding()
        )
        assertEquals(405, response.statusCode())
    }

    @Test
    fun `starting twice fails, restart after stop works`() {
        assertThrows(IllegalStateException::class.java) { server.start() }
        server.stop()
        server.start()
        assertEquals(1, rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")["id"]?.jsonPrimitive?.int)
    }
}
