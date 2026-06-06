package com.example.mcpserver.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

class McpToolRegistry(tools: List<McpTool>) {

    private val toolMap: Map<String, McpTool> = tools.associateBy { it.name }

    init {
        require(tools.size == toolMap.size) {
            val dupes = tools.groupBy { it.name }.filter { it.value.size > 1 }.keys
            "Duplicate tool names registered: $dupes"
        }
    }

    fun listTools(): JsonArray = buildJsonArray {
        toolMap.values.forEach { tool ->
            addJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", tool.inputSchema)
            }
        }
    }

    fun callTool(name: String, arguments: JsonObject, project: Project): JsonElement {
        val tool = toolMap[name]
            ?: return buildJsonObject { put("error", "Unknown tool") }
        return try {
            tool.execute(arguments, project)
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: "Unknown error in tool '$name'") }
        }
    }
}
