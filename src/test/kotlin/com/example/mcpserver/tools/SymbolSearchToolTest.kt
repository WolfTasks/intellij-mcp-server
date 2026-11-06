package com.example.mcpserver.tools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class SymbolSearchToolTest : BasePlatformTestCase() {

    private val tool = SymbolSearchTool()

    private fun results(args: JsonObject): List<JsonObject> =
        tool.execute(args, project).jsonObject["results"]!!.jsonArray.map { it.jsonObject }

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "com/acme/GroupEngine.java",
            """
            package com.acme;

            public class GroupEngine {
                public void rebalance() {}
            }
            """.trimIndent()
        )
    }

    fun `test finds class by fully qualified name with file and line`() {
        val hit = results(buildJsonObject { put("name", "com.acme.GroupEngine") }).single()
        assertEquals("com.acme.GroupEngine", hit["fqn"]?.jsonPrimitive?.content)
        assertTrue(hit["file"]!!.jsonPrimitive.content.endsWith("GroupEngine.java"))
        assertEquals(3, hit["line"]?.jsonPrimitive?.int)
    }

    fun `test finds class by simple name`() {
        val hit = results(buildJsonObject { put("name", "GroupEngine") }).single()
        assertEquals("com.acme.GroupEngine", hit["fqn"]?.jsonPrimitive?.content)
    }

    fun `test finds method by name`() {
        val hit = results(buildJsonObject { put("name", "rebalance"); put("kind", "method") }).single()
        assertEquals("com.acme.GroupEngine.rebalance", hit["fqn"]?.jsonPrimitive?.content)
        assertEquals(4, hit["line"]?.jsonPrimitive?.int)
    }

    fun `test unknown symbol yields empty results`() {
        assertTrue(results(buildJsonObject { put("name", "DoesNotExist") }).isEmpty())
    }

    fun `test invalid kind is reported`() {
        val result = tool.execute(buildJsonObject { put("name", "GroupEngine"); put("kind", "field") }, project)
        assertTrue(result.jsonObject["error"]!!.jsonPrimitive.content.contains("Invalid kind"))
    }

    fun `test missing name is reported`() {
        val result = tool.execute(JsonObject(emptyMap()), project)
        assertEquals("Missing argument: name", result.jsonObject["error"]?.jsonPrimitive?.content)
    }
}
