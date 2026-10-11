package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Android JSONObject.optString turns JSON null into the literal text "null". */
object JsonProtocol {
    fun text(value: JSONObject, key: String): String? = value.opt(key) as? String
    fun text(value: JSONArray, index: Int): String? = value.opt(index) as? String
    fun string(value: JSONObject, key: String, fallback: String = "") = text(value, key) ?: fallback
    fun string(value: JSONArray, index: Int, fallback: String = "") = text(value, index) ?: fallback
    fun nonBlank(value: JSONObject, key: String): String? = text(value, key)?.takeIf { it.isNotBlank() }
    fun copy(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { key -> put(key, copy(value.opt(key))) }
        }
        is JSONArray -> JSONArray().apply {
            for (i in 0 until value.length()) put(copy(value.opt(i)))
        }
        else -> value // Strings/numbers are immutable; do not serialize multi-MB strings to clone them.
    }
    fun objectCopy(value: JSONObject) = copy(value) as JSONObject
    fun arrayCopy(value: JSONArray) = copy(value) as JSONArray
}
