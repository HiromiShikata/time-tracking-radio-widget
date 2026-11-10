package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun parseJsonObject(body: String): JsonObject {
    val element = try {
        Json.parseToJsonElement(body)
    } catch (failure: IllegalArgumentException) {
        throw MalformedResponseException("response is not JSON", failure)
    }
    return element as? JsonObject ?: throw MalformedResponseException("response is not a JSON object")
}

internal fun JsonObject.stringField(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: throw MalformedResponseException("$key is missing or not a string")
    if (!primitive.isString) throw MalformedResponseException("$key is not a string")
    return primitive.content
}

internal fun JsonObject.stringFieldOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.longFieldOrNull(key: String): Long? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) throw MalformedResponseException("$key is not a number")
    return primitive.longOrNull ?: throw MalformedResponseException("$key is not a whole number")
}
