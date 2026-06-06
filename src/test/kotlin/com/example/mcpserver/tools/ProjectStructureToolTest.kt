package com.example.mcpserver.tools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class ProjectStructureToolTest : BasePlatformTestCase() {

    fun `test returns project name and files list`() {
        val file = myFixture.addFileToProject("src/Hello.java", "class Hello {}")
        val tool = ProjectStructureTool()
        val result = tool.execute(JsonObject(emptyMap()), project)
        val obj = result.jsonObject
        assertTrue("result must have 'name' key", obj.containsKey("name"))
        val files = obj["files"]
        assertNotNull("result must have 'files' key", files)
        assertTrue("files must be a JsonArray", files is JsonArray)
        // the added file must appear somewhere in the flat file list
        val paths = (files as JsonArray).map { it.jsonPrimitive.content }
        assertTrue("Hello.java must be listed", paths.any { it.endsWith("Hello.java") })
    }

    fun `test depth 1 excludes deeply nested files`() {
        myFixture.addFileToProject("src/a/Deep.java", "class Deep {}")
        myFixture.addFileToProject("src/Top.java", "class Top {}")
        val tool = ProjectStructureTool()
        val result = tool.execute(
            buildJsonObject { put("depth", 1) },
            project
        )
        val files = (result.jsonObject["files"] as JsonArray)
            .map { it.jsonPrimitive.content }
        assertTrue("Top.java at depth 1 must be included", files.any { it.endsWith("Top.java") })
        assertFalse("Deep.java at depth 2 must be excluded with maxDepth=1", files.any { it.endsWith("Deep.java") })
    }
}
