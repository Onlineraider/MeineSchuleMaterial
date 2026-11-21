package app.meineschule.expressive.data

import app.meineschule.expressive.core.Dates
import java.time.LocalDate

/** Nachrichtenzustand eines Chats: Merge ohne Duplikate (id + tempId), stabile Sortierung, Pagination. */
class MessageStore {
    private val byId = LinkedHashMap<String, Message>()
    var oldestReached = false

    val list: List<Message> get() = byId.values.sortedWith(compareBy<Message> { it.pending }.thenBy { Dates.parse(it.at) ?: java.time.LocalDateTime.MIN }.thenBy { it.id })
    val firstId: String? get() = list.firstOrNull { !it.pending }?.id
    val lastId: String? get() = list.lastOrNull { !it.pending }?.id

    /** @return Anzahl neu hinzugekommener Nachrichten */
    fun merge(msgs: List<Message>): Int {
        var added = 0
        for (m in msgs) {
            m.tempId?.let { byId.remove("tmp:$it") }
            if (m.id.isBlank()) continue
            if (!byId.containsKey(m.id)) added++
            byId[m.id] = m.copy(pending = false, failed = false)
        }
        return added
    }
    fun addPending(tempId: String, content: String) { byId["tmp:$tempId"] = Message("tmp:$tempId", tempId, content, java.time.LocalDateTime.now().toString(), null, null, true, null, false, false, emptyList(), false, pending = true) }
    fun fail(tempId: String) { byId["tmp:$tempId"]?.let { byId["tmp:$tempId"] = it.copy(failed = true, pending = true) } }
    fun remove(ids: Collection<String>) { ids.forEach { byId.remove(it) } }
    fun clear() { byId.clear(); oldestReached = false }

    companion object {
        data class DayGroup(val date: LocalDate?, val label: String, val items: List<Message>)
        fun groupByDay(list: List<Message>, now: LocalDate = LocalDate.now()): List<DayGroup> =
            list.groupBy { Dates.date(it.at) }.map { (d, items) -> DayGroup(d, d?.let { Dates.dayHeading(it, now) } ?: "Unbekanntes Datum", items) }
    }
}
