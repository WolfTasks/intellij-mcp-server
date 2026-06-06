package com.example.mcpserver.server

import kotlinx.serialization.json.*

object McpProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    data class Request(
        val jsonrpc: String,
        val id: JsonElement,
        val method: String,
        val params: JsonElement
    ) {
        fun toolName(): String? =
            if (method == "tools/call") params.jsonObject["name"]?.jsonPrimitive?.content else null

        fun toolArguments(): JsonObject =
            params.jsonObject["arguments"]?.jsonObject ?: JsonObject(emptyMap())
    }

    data class Response(
        val id: JsonElement,
        val result: JsonElement?,
        val error: JsonObject?
    )

    fun parseRequest(body: String): Request {
        val obj = json.parseToJsonElement(body).jsonObject
        return Request(
            jsonrpc = obj["jsonrpc"]?.jsonPrimitive?.content ?: "2.0",
            id = obj["id"] ?: JsonNull,
            method = obj["method"]?.jsonPrimitive?.content ?: "",
            params = obj["params"] ?: JsonObject(emptyMap())
        )
    }

    fun successResponse(id: JsonElement, result: JsonElement): Response =
        Response(id = id, result = result, error = null)

    fun errorResponse(id: JsonElement, code: Int, message: String): Response =
        Response(
            id = id,
            result = null,
            error = buildJsonObject {
                put("code", code)
                put("message", message)
            }
        )

    fun serializeResponse(response: Response): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", response.id)
            if (response.error != null) {
                put("error", response.error)
            } else {
                put("result", response.result ?: JsonNull)
            }
        }.toString()
}
