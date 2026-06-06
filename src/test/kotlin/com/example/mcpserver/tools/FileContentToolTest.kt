package com.example.mcpserver.tools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class FileContentToolTest : BasePlatformTestCase() {

    fun `test reads java file content`() {
        val file = myFixture.addFileToProject("src/Hello.java", "class Hello { /* hello */ }")
        val tool = FileContentTool()
        val result = tool.execute(
            buildJsonObject { put("path", file.virtualFile.path) },
            project
        )
        val content = result.jsonObject["content"]?.jsonPrimitive?.content
        assertNotNull("content must be present", content)
        assertTrue("content must contain class name", content!!.contains("Hello"))
    }

    fun `test returns error for missing file`() {
        val tool = FileContentTool()
        val result = tool.execute(
            buildJsonObject { put("path", "/does/not/exist.java") },
            project
        )
        assertNotNull("error key must be present", result.jsonObject["error"])
    }

    fun `test returns error when path argument missing`() {
        val tool = FileContentTool()
        val result = tool.execute(JsonObject(emptyMap()), project)
        assertNotNull("error key must be present", result.jsonObject["error"])
    }
}
