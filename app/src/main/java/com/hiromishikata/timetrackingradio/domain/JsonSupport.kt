package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal fun parseJsonElement(body: String): JsonElement =
    try {
        Json.parseToJsonElement(body)
    } catch (failure: IllegalArgumentException) {
        throw MalformedResponseException("response is not JSON", failure)
    }

internal fun parseJsonObject(body: String): JsonObject =
    parseJsonElement(body) as? JsonObject ?: throw MalformedResponseException("response is not a JSON object")

internal fun parseJsonArray(body: String): JsonArray =
    parseJsonElement(body) as? JsonArray ?: throw MalformedResponseException("response is not a JSON array")

internal fun JsonObject.stringField(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: throw MalformedResponseException("$key is missing or not a string")
    if (!primitive.isString) throw MalformedResponseException("$key is not a string")
    return primitive.content
}

internal fun JsonObject.stringFieldOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.booleanFieldOrNull(key: String): Boolean? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) throw MalformedResponseException("$key is not a boolean")
    return primitive.booleanOrNull ?: throw MalformedResponseException("$key is not a boolean")
}

internal fun JsonObject.longFieldOrNull(key: String): Long? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) throw MalformedResponseException("$key is not a number")
    return primitive.longOrNull ?: throw MalformedResponseException("$key is not a whole number")
}
