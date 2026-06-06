package com.example.mcpserver.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.*

class ProjectStructureTool : McpTool {
    override val name = "project_structure"
    override val description = "Lists modules, source roots and files of the open project"
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("depth") {
                put("type", "integer")
                put("description", "Max directory depth (default: unlimited)")
            }
        }
    }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        val maxDepth = arguments["depth"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        return ReadAction.compute<JsonElement, Exception> {
            val rootManager = ProjectRootManager.getInstance(project)
            buildJsonObject {
                put("name", project.name)
                put("files", buildJsonArray {
                    rootManager.contentRoots.forEach { root ->
                        addAll(collectFiles(root, 0, maxDepth))
                    }
                })
            }
        }
    }

    private fun collectFiles(dir: VirtualFile, depth: Int, maxDepth: Int): List<JsonElement> {
        if (depth > maxDepth) return emptyList()
        return dir.children.flatMap { child ->
            if (child.isDirectory) {
                collectFiles(child, depth + 1, maxDepth)
            } else {
                listOf(JsonPrimitive(child.path))
            }
        }
    }
}
