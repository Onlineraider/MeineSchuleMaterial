package app.meineschule.expressive.data

import app.meineschule.expressive.core.J
import kotlinx.serialization.json.*

/**
 * Normalisierte Modelle. Dokumentierte Felder sind exakt benannt; für nicht vollständig dokumentierte
 * Modelle (Chat, ChatMessage, Calendar, IndexMedia, IndexProtocol …) werden Alternativnamen toleriert (siehe README → Annahmen).
 */
data class ProfileSchool(val name: String, val tag: String)
data class Profile(val userId: String, val displayName: String, val schools: List<ProfileSchool>)
data class Student(val id: String, val name: String, val cls: String?)
data class SchoolLink(val title: String, val url: String, val appMode: String)
data class Language(val key: String, val name: String)
data class SchoolInfo(
    val name: String, val roles: List<String>, val features: List<String>, val students: List<Student>, val links: List<SchoolLink>, val languages: List<Language>,
    val userHasHIPAccess: Boolean, val canUseMessenger: Boolean, val canCreateAbsences: Boolean, val canCreateLostAndFound: Boolean,
    val canCreateProtocol: Boolean, val canCreateMediaItems: Boolean, val canCreateCalendarItems: Boolean, val hasStudentProperties: Boolean,
)
data class SchoolState(val unreadChatIds: List<String>, val notConfirmedCalendar: List<String>, val notConfirmedMedia: List<String>, val unreadChatMessageCount: Int)
data class IdName(val id: String, val name: String)
data class Recipient(val key: String, val text: String, val info: String?, val source: String?)

data class Chat(
    val id: String, val name: String, val teaser: String?, val unread: Int, val pinned: Boolean, val silent: Boolean, val infoChannel: Boolean,
    val categoryId: String?, val categoryName: String?, val canPost: Boolean, val isOwner: Boolean, val authorsLimited: Boolean, val creatorId: String?, val creatorName: String?, val created: String?,
)
data class Attachment(val id: String, val name: String, val isImage: Boolean, val size: String? = null)
data class Reaction(val emoji: String, val count: Int, val mine: Boolean, val who: List<String>)
data class Message(
    val id: String, val tempId: String?, val content: String, val at: String?, val authorId: String?, val authorName: String?, val own: Boolean?,
    val replyId: String?, val replyContent: String?, val canDelete: Boolean, val isRead: Boolean,
    val attachmentId: String?, val attachmentName: String?, val reactions: List<Reaction>, val myVote: String?,
    val pending: Boolean = false, val failed: Boolean = false,
) {
    val hasAttachment: Boolean get() = !attachmentId.isNullOrBlank()
    val attachmentIsImage: Boolean get() = attachmentName?.matches(Regex(".*\\.(jpe?g|png|gif|webp|heic|bmp)$", RegexOption.IGNORE_CASE)) == true
}
data class CalendarEvent(val id: String, val title: String, val start: String?, val end: String?, val created: String?, val text: String?, val location: String?, val category: String?, val confirmReceive: Boolean, val answer: Int?)
data class MediaItem(val id: String, val title: String, val creator: String?, val from: String?, val to: String?, val created: String?, val text: String?, val liked: Boolean, val likeCount: Int, val confirmReceive: Boolean, val confirmed: Boolean, val attachments: List<Attachment>)
data class ProtocolItem(val id: String, val title: String, val date: String?)
data class ProtocolDetail(val item: ProtocolItem, val agenda: String?, val text: String?, val attachments: List<Attachment>, val attendees: List<String>)
data class LostItem(val id: String, val name: String, val location: String, val text: String, val found: Boolean, val hasAttachment: Boolean, val canEdit: Boolean, val isActive: Boolean, val creator: String? = null)
data class Person(val id: String?, val name: String, val type: String?, val userId: String?)
data class InfoGroup(val title: String?, val persons: List<Person>)
data class StudentProperty(val name: String, val type: String?, val value: String?, val canEdit: Boolean)
data class StudentInfo(
    val student: Student, val parents: InfoGroup, val partners: InfoGroup, val classInfo: List<InfoGroup>, val groupInfo: List<InfoGroup>,
    /** Hierarchisch aus subjectInfo.details[]: jedes Fach + seine Personen. */
    val subjects: List<InfoGroup>, val properties: List<StudentProperty>, val hasHip: Boolean, val userId: String? = null,
)
data class AbsenceReport(
    val id: String?, val studentId: String, val categoryId: String?, val from: String?, val to: String?, val fromLesson: Int?, val toLesson: Int?,
    val fullDay: Boolean, val text: String?, val hasAttachment: Boolean, val created: String?, val creator: String?,
)
data class AbsenceIndex(val students: List<Student>, val categories: List<IdName>, val lessons: List<IdName>)
data class PickupOption(val id: String, val name: String)
data class CareIndex(val students: List<Student>, val options: List<PickupOption>, val weekDays: Int?)
data class CareEntry(val studentId: String?, val optionId: String?, val choice: String?, val memo: String?, val canEdit: Boolean, val raw: J)

// ---- Mapper ----
fun J.toStudent() = Student(str("id").orEmpty(), str("displayName", "name").orEmpty(), str("studentClass", "class", "className"))
fun J.toIdName() = IdName(str("id").orEmpty(), str("displayName", "name", "title").orEmpty())
fun J.toAttachment() = Attachment(str("id", "attachmentId").orEmpty(), (str("mediaFileName", "name", "fileName", "filename") ?: "Anhang") + (str("mediaExtension") ?: ""),
    ((str("mediaFileName", "name", "fileName", "filename").orEmpty() + (str("mediaExtension") ?: "")).matches(Regex(".*\\.(jpe?g|png|gif|webp|heic|bmp)$", RegexOption.IGNORE_CASE))) || str("contentType", "mimeType")?.startsWith("image/") == true,
    str("mediaSize", "size"))

fun J.toChat() = Chat(
    id = str("id", "chatId").orEmpty(), name = str("name", "creatorUserDisplayName", "title") ?: "Unbenannt", teaser = str("contentTeaser"),
    unread = int("unReadCount", "unreadCount") ?: 0, pinned = bool("isPinned") == true, silent = bool("isSilent") == true,
    infoChannel = bool("isInfoChannel") == true || str("contentTeaser", "name")?.contains("infokanal", true) == true,
    categoryId = str("categoryId"), categoryName = obj("category")?.str("name"), canPost = bool("canPost") != false, isOwner = bool("isOwner") == true,
    authorsLimited = bool("authorsLimited") == true, creatorId = str("creatorUserId"), creatorName = str("creatorUserDisplayName"), created = str("created"),
)

private val VOTE_KEY = Regex("vote|reaction|emoji", RegexOption.IGNORE_CASE)
/** Reaktionen tolerant lesen (das Antwortformat ist nicht fest dokumentiert): Felder, deren Name vote/reaction/emoji enthält. */
internal fun J.reactions(meId: String?): Pair<List<Reaction>, String?> {
    val o = raw as? kotlinx.serialization.json.JsonObject ?: return emptyList<Reaction>() to null
    val map = linkedMapOf<String, Triple<Int, Boolean, MutableList<String>>>()
    fun add(e: String?, n: Int, mine: Boolean, who: String?) { val k = e?.trim().orEmpty(); if (k.isEmpty()) return; val c = map[k]; map[k] = Triple((c?.first ?: 0) + n, (c?.second ?: false) || mine, (c?.third ?: mutableListOf()).also { l -> who?.takeIf { it.isNotBlank() }?.let(l::add) }) }
    fun item(x: kotlinx.serialization.json.JsonElement) {
        when (x) {
            is kotlinx.serialization.json.JsonPrimitive -> add(x.contentOrNull, 1, false, null)
            is kotlinx.serialization.json.JsonObject -> { val j = J(x); val uid = j.str("userId", "creatorUserId")
                add(j.str("vote", "emoji", "reaction", "value", "text", "key"), j.int("count", "voteCount", "total") ?: 1,
                    j.bool("isFromMe", "isMine", "mine", "own", "isOwn", "self") == true || (uid != null && meId != null && uid.equals(meId, true)), j.str("userDisplayName", "creatorUserDisplayName", "displayName", "name")) }
            else -> {}
        }
    }
    var mineVote: String? = null
    for ((k, v) in o) {
        if (!VOTE_KEY.containsMatchIn(k) || k.equals("vote", true) || v is kotlinx.serialization.json.JsonNull) continue
        when (v) {
            is kotlinx.serialization.json.JsonArray -> v.forEach(::item)
            is kotlinx.serialization.json.JsonObject -> { val es = v.entries; if (es.isNotEmpty() && es.all { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull != null || it.value is kotlinx.serialization.json.JsonArray })
                es.forEach { (e, y) -> val arr = (y as? kotlinx.serialization.json.JsonArray); add(e, arr?.size ?: (y as kotlinx.serialization.json.JsonPrimitive).intOrNull ?: 0, arr?.any { u -> J(u).let { j -> (j.str("userId", "id") ?: (u as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull)?.equals(meId, true) == true } } == true, null) } else item(v) }
            is kotlinx.serialization.json.JsonPrimitive -> if (v.isString && v.content.isNotBlank()) item(v)
            else -> {}
        }
    }
    J(raw).str("vote")?.takeIf { it.isNotBlank() }?.let { mineVote = it.trim(); val c = map[mineVote!!]; if (c == null) add(mineVote, 1, true, null) else map[mineVote!!] = c.copy(second = true) }
    val list = map.map { (e, t) -> Reaction(e, t.first, t.second, t.third) }
    return list to (mineVote ?: list.firstOrNull { it.mine }?.emoji)
}
fun J.toMessage(meId: String? = null): Message {
    val (re, my) = reactions(meId)
    return Message(
        id = str("id", "messageId").orEmpty(), tempId = str("tempId"), content = str("content").orEmpty(), at = str("created"), authorId = str("creatorUserId"), authorName = str("creatorUserDisplayName"),
        own = bool("isFromMe"), replyId = str("replyToMessageId") ?: obj("replyToMessage")?.str("id", "messageId", "chatMessageId"), replyContent = obj("replyToMessage")?.let { it.str("content") ?: "Anhang" },
        canDelete = bool("canDelete") == true, isRead = bool("isRead") != false, attachmentId = str("attachmentId"), attachmentName = str("attachmentName"), reactions = re, myVote = my,
    )
}
fun J.toCalendar() = CalendarEvent(str("id").orEmpty(), str("name", "title") ?: "Termin", str("from"), str("to"), str("created"), str("text"), str("location"), obj("category")?.str("name"),
    bool("confirmReceive") == true, int("confirmAnswer"))
fun J.toMedia() = MediaItem(str("id").orEmpty(), str("name", "title") ?: "Beitrag", str("creatorUserDisplayName"), str("from"), str("to"), str("created"), str("content", "text"),
    bool("liked") == true, int("likeCount") ?: 0, bool("confirmReceive") == true, bool("isConfirmed") == true, arr("attachments").map { it.toAttachment() })
fun J.toProtocol() = ProtocolItem(str("id").orEmpty(), str("title", "name", "subject") ?: "Protokoll", str("date", "created", "meetingDate", "modified"))
fun J.toProtocolDetail() = ProtocolDetail(toProtocol(), str("agenda"), str("text"), arr("attachments").map { it.toAttachment() }, arr("attendees").map { it.str("displayName", "name") ?: it.raw?.toString()?.trim('"').orEmpty() })
fun J.toLost() = LostItem(str("id").orEmpty(), str("name", "creatorUserDisplayName", "title").orEmpty(), str("location").orEmpty(), str("text").orEmpty(), bool("found") != false, bool("hasAttachment") == true, bool("canEdit") == true, bool("isActive") != false, str("creatorUserDisplayName"))
fun J.toPerson() = Person(str("id"), str("displayName", "userDisplayName").orEmpty(), str("type"), str("userId"))
fun J.toGroup() = InfoGroup(str("displayName"), arr("persons").map { it.toPerson() })
fun J.toStudentInfo(): StudentInfo {
    fun groups(key: String): List<InfoGroup> { val o = obj(key) ?: return emptyList(); val d = o.arr("details"); return if (d.isNotEmpty()) d.map { it.toGroup() } else listOf(o.toGroup()).filter { it.persons.isNotEmpty() } }
    return StudentInfo(toStudent(), obj("parents")?.toGroup() ?: InfoGroup("Eltern", emptyList()), obj("partners")?.toGroup() ?: InfoGroup("Partner", emptyList()),
        groups("studentClassInfo"), groups("studentGroupInfo"),
        // Fachlehrkräfte: hierarchisch aus subjectInfo.details[]; flache subjectInfo.persons ist NICHT maßgeblich
        obj("subjectInfo")?.arr("details")?.map { it.toGroup() } ?: emptyList(),
        arr("studentProperties").map { StudentProperty(it.str("name").orEmpty(), it.str("type"), it.str("value", "mediaFileName"), it.bool("canEdit") == true) }, bool("hasHIP") == true, str("userId"))
}
fun J.toAbsenceReport() = AbsenceReport(str("id"), str("studentId").orEmpty(), str("absenceCategoryId"), str("from"), str("to"), int("fromLesson"), int("toLesson"),
    bool("isFullDay") == true, str("text"), bool("hasAttachment") == true, str("created"), str("creatorUserDisplayName"))
fun J.toCareEntry() = CareEntry(str("studentId"), str("optionId"), str("choice"), str("memo"), bool("canEdit") == true, this)
