package com.example.mcpserver.tools

import com.example.mcpserver.testutil.writeLocalFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*

class DiagnosticsToolTest : BasePlatformTestCase() {

    private val tool = DiagnosticsTool()

    fun `test missing file is reported`() {
        val result = tool.execute(buildJsonObject { put("path", "/does/not/exist.java") }, project).jsonObject
        assertTrue(result["error"]!!.jsonPrimitive.content.startsWith("File not found"))
    }

    fun `test existing file returns a diagnostics array`() {
        val file = writeLocalFile(testRootDisposable, "Ok.java", "class Ok {}")
        val result = tool.execute(buildJsonObject { put("path", file.path) }, project).jsonObject
        assertTrue(result["diagnostics"] is JsonArray)
    }

    fun `test no path lists problematic files`() {
        val result = tool.execute(JsonObject(emptyMap()), project).jsonObject
        assertTrue(result["problematic_files"] is JsonArray)
    }

    fun `test explicit null path behaves like no path`() {
        val result = tool.execute(buildJsonObject { put("path", JsonNull) }, project).jsonObject
        assertTrue(result["problematic_files"] is JsonArray)
    }
}
