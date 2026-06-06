package com.example.mcpserver.tools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class ProjectStructureToolTest : BasePlatformTestCase() {

    fun `test returns project name in result`() {
        myFixture.addFileToProject("src/Hello.java", "class Hello {}")
        val tool = ProjectStructureTool()
        val result = tool.execute(JsonObject(emptyMap()), project)
        val obj = result.jsonObject
        assertTrue(obj.containsKey("name"))
        assertNotNull(obj["files"])
    }

    fun `test depth 1 limits file listing`() {
        myFixture.addFileToProject("src/a/Deep.java", "class Deep {}")
        myFixture.addFileToProject("src/Top.java", "class Top {}")
        val tool = ProjectStructureTool()
        val result = tool.execute(
            buildJsonObject { put("depth", 1) },
            project
        )
        assertNotNull(result.jsonObject["files"])
    }
}
