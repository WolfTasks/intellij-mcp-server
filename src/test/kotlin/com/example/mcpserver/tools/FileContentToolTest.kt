package com.example.mcpserver.tools

import com.example.mcpserver.testutil.writeLocalFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class FileContentToolTest : BasePlatformTestCase() {

    fun `test reads java file content`() {
        // Must be a real on-disk file: the tool resolves absolute paths via LocalFileSystem,
        // which cannot see the light fixture's in-memory temp:// files.
        val file = writeLocalFile(testRootDisposable, "Hello.java", "class Hello { /* hellö */ }")
        val tool = FileContentTool()
        val result = tool.execute(
            buildJsonObject { put("path", file.path) },
            project
        )
        val content = result.jsonObject["content"]?.jsonPrimitive?.content
        assertNotNull("content must be present", content)
        assertEquals("class Hello { /* hellö */ }", content)
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
