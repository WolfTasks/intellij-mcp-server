package com.example.mcpserver.server

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {

    @Test
    fun `deserializes tools-call request`() {
        val json = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"read_file","arguments":{"path":"/foo/Bar.java"}}}
        """.trimIndent()
        val req = McpProtocol.parseRequest(json)
        assertEquals("tools/call", req.method)
        assertEquals("read_file", req.toolName())
        assertEquals("/foo/Bar.java", req.toolArguments()["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `serializes success response`() {
        val resp = McpProtocol.successResponse(
            id = JsonPrimitive(1),
            result = buildJsonObject { put("content", "hello") }
        )
        val text = McpProtocol.serializeResponse(resp)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
        assertNull(parsed["error"])
        assertNotNull(parsed["result"])
    }

    @Test
    fun `serializes error response`() {
        val resp = McpProtocol.errorResponse(JsonPrimitive(2), -32601, "Method not found")
        val text = McpProtocol.serializeResponse(resp)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertNotNull(parsed["error"])
        assertEquals(-32601, parsed["error"]!!.jsonObject["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `toolName returns null for non-tools-call method`() {
        val req = McpProtocol.parseRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        assertNull(req.toolName())
    }

    @Test
    fun `parseRequest throws on malformed JSON`() {
        try {
            McpProtocol.parseRequest("{bad json}")
            fail("Expected exception for malformed JSON")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun `toolName and toolArguments return null and empty map when params is not object`() {
        val req = McpProtocol.parseRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":null}""")
        assertNull(req.toolName())
        assertEquals(JsonObject(emptyMap()), req.toolArguments())
    }
}
