package com.example.mcpserver.tools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class InternalDepsToolTest : BasePlatformTestCase() {

    private val tool = InternalDepsTool()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "com/acme/Engine.java",
            """
            package com.acme;

            import java.util.List;
            import java.util.Map;

            public class Engine {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/acme/Car.java",
            """
            package com.acme;

            public class Car {
                private Engine engine;
            }
            """.trimIndent()
        )
    }

    private fun call(symbol: String, direction: String): JsonObject =
        tool.execute(buildJsonObject { put("symbol", symbol); put("direction", direction) }, project).jsonObject

    fun `test usages lists referencing file and line`() {
        val results = call("com.acme.Engine", "usages")["results"]!!.jsonArray.map { it.jsonObject }
        val hit = results.single()
        assertTrue(hit["file"]!!.jsonPrimitive.content.endsWith("Car.java"))
        assertEquals(4, hit["line"]?.jsonPrimitive?.int)
    }

    fun `test deps lists imports of the class file`() {
        val imports = call("com.acme.Engine", "deps")["results"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("java.util.List", "java.util.Map"), imports)
    }

    fun `test unknown class is reported`() {
        assertTrue(call("com.acme.Nope", "usages")["error"]!!.jsonPrimitive.content.contains("Class not found"))
    }

    fun `test invalid direction is reported`() {
        assertTrue(call("com.acme.Engine", "sideways")["error"]!!.jsonPrimitive.content.contains("Invalid direction"))
    }

    fun `test missing arguments are reported`() {
        val noSymbol = tool.execute(buildJsonObject { put("direction", "usages") }, project).jsonObject
        assertEquals("Missing argument: symbol", noSymbol["error"]?.jsonPrimitive?.content)
        val noDirection = tool.execute(buildJsonObject { put("symbol", "com.acme.Engine") }, project).jsonObject
        assertEquals("Missing argument: direction", noDirection["error"]?.jsonPrimitive?.content)
    }
}
