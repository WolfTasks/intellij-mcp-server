package com.example.mcpserver.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.serialization.json.*

class InternalDepsTool : McpTool {
    override val name = "internal_dependencies"
    override val description = "Finds usages of a Java class (direction='usages') or its imports (direction='deps'). symbol must be a fully-qualified class name."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("symbol") {
                put("type", "string")
                put("description", "Fully qualified class name, e.g. com.example.Foo")
            }
            putJsonObject("direction") {
                put("type", "string")
                put("enum", buildJsonArray { add("usages"); add("deps") })
                put("description", "'usages' = who references this class; 'deps' = what this class imports")
            }
        }
        put("required", buildJsonArray { add("symbol"); add("direction") })
    }

    override fun execute(arguments: JsonObject, project: Project): JsonElement {
        val symbol = arguments["symbol"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "Missing argument: symbol") }
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "Missing argument: direction") }

        if (DumbService.isDumb(project)) {
            return buildJsonObject { put("error", "IntelliJ is still indexing. Try again shortly.") }
        }

        return ReadAction.compute<JsonElement, Exception> {
            val scope = GlobalSearchScope.projectScope(project)
            val psiClass = JavaPsiFacade.getInstance(project).findClass(symbol, scope)
                ?: return@compute buildJsonObject { put("error", "Class not found in project scope: $symbol") }

            when (direction) {
                "usages" -> {
                    val refs = ReferencesSearch.search(psiClass, scope).findAll()
                    buildJsonObject {
                        put("symbol", symbol)
                        put("direction", "usages")
                        put("results", buildJsonArray {
                            refs.forEach { ref ->
                                val el = ref.element
                                val file = el.containingFile?.virtualFile?.path ?: "unknown"
                                val line = el.containingFile?.viewProvider?.document
                                    ?.getLineNumber(el.textOffset)?.plus(1) ?: 0
                                addJsonObject {
                                    put("file", file)
                                    put("line", line)
                                }
                            }
                        })
                    }
                }
                "deps" -> {
                    val imports = (psiClass.containingFile as? PsiJavaFile)
                        ?.importList
                        ?.allImportStatements
                        ?.mapNotNull { it.importReference?.qualifiedName }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()
                    buildJsonObject {
                        put("symbol", symbol)
                        put("direction", "deps")
                        put("results", buildJsonArray { imports.forEach { add(it) } })
                    }
                }
                else -> buildJsonObject {
                    put("error", "Invalid direction '$direction'. Use 'usages' or 'deps'")
                }
            }
        }
    }
}
