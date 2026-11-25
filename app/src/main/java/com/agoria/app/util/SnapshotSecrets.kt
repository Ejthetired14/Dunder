package com.agoria.app.util

import com.agoria.app.core.ConnectorCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strips credentials out of an export or auto-backup snapshot (SEC-2).
 *
 * A snapshot is meant to be restorable on another phone, where the Android Keystore key that
 * encrypted the originals does not exist, so no secret may be written into it. A restore keeps
 * only the non-secret shape of the data; the user re-enters credentials afterwards.
 */
object SnapshotSecrets {

    /**
     * Key names that mean "credential" wherever they appear. Connector configs are handled from
     * [ConnectorCatalog]'s `secret` flag first because that list is authoritative; this pattern
     * covers agent tokens, skill configs and fields added later without the flag. It deliberately
     * matches singular `...token` only, so budgets such as `maxTokens` survive.
     */
    private val SECRET_KEY = Regex(
        "(?i)^(.*token|.*secret|api[-_]?key|service[-_]?key|private[-_]?key|linearKey|" +
            "password|passwd|authorization|credentials?|webhook[-_]?url)$",
    )

    /** Keys whose value is itself JSON text that has to be scrubbed too (agent/skill config). */
    private val JSON_TEXT_KEYS = setOf("config", "configJson")

    fun isSecretKey(key: String): Boolean = SECRET_KEY.matches(key)

    /** Every key the catalog declares secret for [connectorId] (empty for unknown connectors). */
    fun catalogSecretKeys(connectorId: String): Set<String> =
        ConnectorCatalog.get(connectorId)?.fields?.filter { it.secret }?.map { it.key }?.toSet().orEmpty()

    /**
     * Removes the credentials of one connector config in place and returns how many keys it
     * removed. Non-secret settings (base URL, default repository, ...) stay, so a restore can
     * still apply them.
     */
    fun redactConnectorConfig(connectorId: String, config: JSONObject): Int {
        val catalogKeys = catalogSecretKeys(connectorId)
        var removed = 0
        for (key in config.keys().asSequence().toList()) {
            if (key in catalogKeys || isSecretKey(key)) {
                config.remove(key)
                removed++
            }
        }
        return removed
    }

    /**
     * Walks the whole snapshot and removes every credential: agent tokens, connector configs
     * (catalog-driven plus pattern-driven) and credentials inside skill config JSON text.
     * Returns the number of removed keys. Safe to call twice; the second call removes nothing.
     */
    fun redactSnapshot(root: JSONObject): Int {
        var removed = 0
        val connectors = root.optJSONArray("connectors")
        if (connectors != null) {
            for (i in 0 until connectors.length()) {
                val connector = connectors.optJSONObject(i) ?: continue
                val config = connector.optJSONObject("config") ?: continue
                removed += redactConnectorConfig(connector.optString("id"), config)
            }
        }
        return removed + redactObject(root)
    }

    private fun redactObject(node: JSONObject): Int {
        var removed = 0
        for (key in node.keys().asSequence().toList()) {
            if (isSecretKey(key)) {
                node.remove(key)
                removed++
                continue
            }
            removed += when (val value = node.opt(key)) {
                is JSONObject -> redactObject(value)
                is JSONArray -> redactArray(value)
                is String -> redactJsonText(node, key, value)
                else -> 0
            }
        }
        return removed
    }

    private fun redactArray(array: JSONArray): Int {
        var removed = 0
        for (i in 0 until array.length()) {
            removed += when (val value = array.opt(i)) {
                is JSONObject -> redactObject(value)
                is JSONArray -> redactArray(value)
                else -> 0
            }
        }
        return removed
    }

    private fun redactJsonText(node: JSONObject, key: String, value: String): Int {
        if (key !in JSON_TEXT_KEYS) return 0
        val parsed = runCatching { JSONObject(value) }.getOrNull() ?: return 0
        val removed = redactObject(parsed)
        if (removed > 0) node.put(key, parsed.toString())
        return removed
    }
}
