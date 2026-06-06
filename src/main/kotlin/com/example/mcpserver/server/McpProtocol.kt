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
            if (method == "tools/call")
                (params as? JsonObject)?.get("name")?.jsonPrimitive?.content
            else null

        fun toolArguments(): JsonObject =
            (params as? JsonObject)?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
    }

    sealed class Response {
        abstract val id: JsonElement

        data class Success(override val id: JsonElement, val result: JsonElement) : Response()
        data class Error(override val id: JsonElement, val code: Int, val message: String) : Response()
    }

    // Throws SerializationException for invalid JSON, IllegalArgumentException if not a JSON object
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
        Response.Success(id = id, result = result)

    fun errorResponse(id: JsonElement, code: Int, message: String): Response =
        Response.Error(id = id, code = code, message = message)

    fun serializeResponse(response: Response): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", response.id)
            when (response) {
                is Response.Success -> put("result", response.result)
                is Response.Error -> put("error", buildJsonObject {
                    put("code", response.code)
                    put("message", response.message)
                })
            }
        }.toString()
}
