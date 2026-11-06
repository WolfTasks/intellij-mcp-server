package com.example.mcpserver.tools

import com.example.mcpserver.testutil.fakeProject
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpToolRegistryTest {

    private class StubTool(
        override val name: String,
        private val behavior: (JsonObject) -> JsonElement = { args -> buildJsonObject { put("echo", args) } }
    ) : McpTool {
        override val description = "stub $name"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override fun execute(arguments: JsonObject, project: Project): JsonElement = behavior(arguments)
    }

    private val project = fakeProject()

    @Test
    fun `listTools exposes name, description and inputSchema of every tool`() {
        val tools = McpToolRegistry(listOf(StubTool("a"), StubTool("b"))).listTools()
        assertEquals(2, tools.size)
        val first = tools[0].jsonObject
        assertEquals("a", first["name"]?.jsonPrimitive?.content)
        assertEquals("stub a", first["description"]?.jsonPrimitive?.content)
        assertEquals("object", first["inputSchema"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `duplicate tool names are rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            McpToolRegistry(listOf(StubTool("dup"), StubTool("dup"), StubTool("ok")))
        }
        assertTrue(e.message!!.contains("dup"))
    }

    @Test
    fun `callTool forwards arguments to the matching tool`() {
        val registry = McpToolRegistry(listOf(StubTool("echo")))
        val args = buildJsonObject { put("x", 1) }
        val result = registry.callTool("echo", args, project)
        assertEquals(args, result.jsonObject["echo"])
    }

    @Test
    fun `callTool returns error object for unknown tool`() {
        val result = McpToolRegistry(emptyList()).callTool("missing", JsonObject(emptyMap()), project)
        assertEquals("Unknown tool", result.jsonObject["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `callTool converts tool exceptions into error objects`() {
        val registry = McpToolRegistry(listOf(StubTool("boom") { throw IllegalStateException("kaputt") }))
        val result = registry.callTool("boom", JsonObject(emptyMap()), project)
        assertEquals("kaputt", result.jsonObject["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `callTool uses fallback message when exception has none`() {
        val registry = McpToolRegistry(listOf(StubTool("boom") { throw RuntimeException() }))
        val result = registry.callTool("boom", JsonObject(emptyMap()), project)
        assertEquals("Unknown error in tool 'boom'", result.jsonObject["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `production registry has unique names and object schemas`() {
        val tools = listOf(
            ProjectStructureTool(), FileContentTool(), ExternalDepsTool(),
            InternalDepsTool(), SymbolSearchTool(), DiagnosticsTool()
        )
        val listed = McpToolRegistry(tools).listTools()
        assertEquals(6, listed.size)
        listed.forEach { tool ->
            val schema = tool.jsonObject["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val properties = schema["properties"]?.jsonObject?.keys ?: emptySet()
            assertTrue("required ⊆ properties for ${tool.jsonObject["name"]}", properties.containsAll(required))
        }
    }
}
