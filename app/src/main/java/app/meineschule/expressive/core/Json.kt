package app.meineschule.expressive.core

import kotlinx.serialization.json.*

/**
 * Tolerante JSON-Sicht. Für viele Server-Modelle (Chat, ChatMessage, Calendar, …) liegen in der Doku keine
 * vollständigen Feldlisten vor. Schlüssel werden daher case-insensitiv und mit Alternativen gesucht;
 * nichts wird erfunden, unbekannte Felder bleiben in [raw] erhalten.
 */
class J(val raw: JsonElement?) {
    private val obj: JsonObject? get() = raw as? JsonObject

    private fun find(keys: Array<out String>): JsonElement? {
        val o = obj ?: return null
        for (k in keys) {
            o[k]?.takeIf { it !is JsonNull }?.let { return it }
            o.entries.firstOrNull { it.key.equals(k, ignoreCase = true) && it.value !is JsonNull }?.let { return it.value }
        }
        return null
    }

    fun str(vararg k: String): String? = (find(k) as? JsonPrimitive)?.let { if (it.isString) it.content else it.contentOrNull }
    fun bool(vararg k: String): Boolean? = (find(k) as? JsonPrimitive)?.booleanOrNull
    fun int(vararg k: String): Int? = (find(k) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    fun long(vararg k: String): Long? = (find(k) as? JsonPrimitive)?.longOrNull
    fun obj(vararg k: String): J? = find(k)?.let { J(it) }
    fun has(vararg k: String): Boolean = find(k) != null
    fun arr(vararg k: String): List<J> = (find(k) as? JsonArray)?.map { J(it) } ?: emptyList()
    fun strings(vararg k: String): List<String> = (find(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val isNull: Boolean get() = raw == null || raw is JsonNull
    override fun toString(): String = raw?.toString() ?: "null"

    companion object {
        val EMPTY = J(null)
        /** Array an der Wurzel, oder in `items`/`data`. */
        fun list(e: JsonElement?): List<J> = when (e) {
            is JsonArray -> e.map { J(it) }
            is JsonObject -> J(e).let { it.arr("items").ifEmpty { it.arr("data") } }
            else -> emptyList()
        }
    }
}

val AppJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = true }

fun jsonObject(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
