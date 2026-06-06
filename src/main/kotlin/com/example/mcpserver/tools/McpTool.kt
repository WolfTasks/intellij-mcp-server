package com.example.mcpserver.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface McpTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    fun execute(arguments: JsonObject, project: Project): JsonElement
}
