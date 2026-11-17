package com.agoria.app.util

import org.json.JSONArray
import org.json.JSONObject

object Json {

    fun buildObject(vararg pairs: Pair<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in pairs) {
            if (value == null) continue
            obj.put(key, value)
        }
        return obj
    }

    fun array(vararg values: String): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    /** Finds the first JSON object in a model response. Models often add prose around JSON. */
    fun tryObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    /** Finds the first JSON array in a model response. */
    fun tryArray(text: String?): JSONArray? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            JSONArray(text.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    fun string(obj: JSONObject?, key: String, default: String = ""): String {
        if (obj == null) return default
        val value = obj.optString(key, default)
        return if (value == "null") default else value
    }

    fun int(obj: JSONObject?, key: String, default: Int = 0): Int {
        if (obj == null) return default
        return obj.optInt(key, default)
    }

    fun bool(obj: JSONObject?, key: String, default: Boolean = false): Boolean {
        if (obj == null) return default
        return obj.optBoolean(key, default)
    }

    fun obj(obj: JSONObject?, key: String): JSONObject? {
        if (obj == null) return null
        return obj.optJSONObject(key)
    }

    fun array(obj: JSONObject?, key: String): JSONArray? {
        if (obj == null) return null
        return obj.optJSONArray(key)
    }

    fun pretty(obj: JSONObject?): String {
        if (obj == null) return ""
        return try {
            obj.toString(2)
        } catch (_: Exception) {
            obj.toString()
        }
    }

    fun pretty(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val parsed = tryObject(text)
        return if (parsed != null) pretty(parsed) else text
    }

    fun stringList(obj: JSONObject?, key: String): List<String> {
        val arr = array(obj, key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i, "")
            if (value.isNotBlank()) out += value
        }
        return out
    }
}