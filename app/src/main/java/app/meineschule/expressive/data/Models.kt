package app.meineschule.expressive.data

import app.meineschule.expressive.core.J

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

data class Chat(val id: String, val name: String, val last: String?, val lastAt: String?, val unread: Int, val pinned: Boolean, val muted: Boolean, val group: Boolean?)
data class Attachment(val id: String, val name: String, val isImage: Boolean)
data class Message(
    val id: String, val tempId: String?, val content: String, val at: String?, val authorId: String?, val authorName: String?, val own: Boolean?,
    val replyTo: String?, val forwarded: Boolean, val deleted: Boolean, val attachments: List<Attachment>, val hasPoll: Boolean,
    val pending: Boolean = false, val failed: Boolean = false,
)
data class CalendarEvent(val id: String, val title: String, val start: String?, val end: String?, val allDay: Boolean, val text: String?, val location: String?, val category: String?, val confirmed: Boolean?)
data class MediaItem(val id: String, val title: String, val text: String?, val date: String?, val category: String?, val liked: Boolean, val confirmed: Boolean?, val attachments: List<Attachment>)
data class ProtocolItem(val id: String, val title: String, val date: String?)
data class ProtocolDetail(val item: ProtocolItem, val agenda: String?, val text: String?, val attachments: List<Attachment>, val attendees: List<String>)
data class LostItem(val id: String, val name: String, val location: String, val text: String, val found: Boolean, val hasAttachment: Boolean, val canEdit: Boolean, val isActive: Boolean)
data class Person(val id: String?, val name: String, val type: String?, val userId: String?)
data class InfoGroup(val title: String?, val persons: List<Person>)
data class StudentProperty(val name: String, val type: String?, val value: String?, val canEdit: Boolean)
data class StudentInfo(
    val student: Student, val parents: InfoGroup, val partners: InfoGroup, val classInfo: List<InfoGroup>, val groupInfo: List<InfoGroup>,
    /** Hierarchisch aus subjectInfo.details[]: jedes Fach + seine Personen. */
    val subjects: List<InfoGroup>, val properties: List<StudentProperty>, val hasHip: Boolean,
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
fun J.toAttachment() = Attachment(str("id", "attachmentId").orEmpty(), str("name", "fileName", "filename") ?: "Anhang",
    str("name", "fileName", "filename")?.matches(Regex(".*\\.(jpe?g|png|gif|webp|heic)$", RegexOption.IGNORE_CASE)) == true || str("contentType", "mimeType")?.startsWith("image/") == true || bool("isImage") == true)

fun J.toChat() = Chat(
    id = str("id", "chatId").orEmpty(), name = str("name", "title", "displayName") ?: "Chat", last = str("lastMessage", "lastMessageText", "lastItem", "preview", "text", "content"),
    lastAt = str("lastMessageDate", "lastMessageTime", "lastActivity", "lastModified", "date", "modified", "created"),
    unread = int("unreadCount", "unreadMessages", "unread", "unreadMessageCount") ?: 0, pinned = bool("isPinned", "pinned") == true, muted = bool("isSilent", "isMuted", "silent", "muted") == true,
    group = bool("isGroup", "group") ?: arr("recipients").size.takeIf { it > 0 }?.let { it > 1 },
)
fun J.toMessage() = Message(
    id = str("id", "messageId").orEmpty(), tempId = str("tempId"), content = str("content", "text", "message", "body").orEmpty(), at = str("created", "date", "timestamp", "sent", "createdAt", "time"),
    authorId = str("authorUserId", "authorId", "userId", "creatorUserId", "senderId"), authorName = str("authorName", "authorDisplayName", "authorUserDisplayName", "userDisplayName", "senderName", "displayName"),
    own = bool("isOwn", "isMine", "own", "mine"), replyTo = str("replyToMessageId", "replyTo"), forwarded = has("forwardedMessageId") || bool("isForwarded", "forwarded") == true,
    deleted = bool("isDeleted", "deleted") == true, attachments = arr("attachments", "files").map { it.toAttachment() }, hasPoll = has("poll", "votes", "pollOptions"),
)
fun J.toCalendar() = CalendarEvent(str("id").orEmpty(), str("title", "name", "subject") ?: "Termin", str("start", "from", "startDate", "date", "begin"), str("end", "to", "endDate"),
    bool("isAllDay", "allDay", "fullDay") == true, str("text", "description", "content", "info"), str("location", "place"), str("category", "categoryName", "type"),
    bool("confirmed", "isConfirmed") ?: str("confirmation", "answer")?.let { it != "0" && !it.equals("null", true) && it.isNotBlank() })
fun J.toMedia() = MediaItem(str("id").orEmpty(), str("title", "name", "subject") ?: "Beitrag", str("text", "description", "summary", "content", "teaser"), str("date", "published", "publishDate", "created", "modified"),
    str("category", "categoryName"), bool("liked", "isLiked", "hasLiked", "like") == true, bool("confirmed", "isConfirmed"), arr("attachments", "files").map { it.toAttachment() })
fun J.toProtocol() = ProtocolItem(str("id").orEmpty(), str("title", "name", "subject") ?: "Protokoll", str("date", "created", "meetingDate", "modified"))
fun J.toProtocolDetail() = ProtocolDetail(toProtocol(), str("agenda"), str("text"), arr("attachments").map { it.toAttachment() }, arr("attendees").map { it.str("displayName", "name") ?: it.raw?.toString()?.trim('"').orEmpty() })
fun J.toLost() = LostItem(str("id").orEmpty(), str("name").orEmpty(), str("location").orEmpty(), str("text").orEmpty(), bool("found") != false, bool("hasAttachment") == true, bool("canEdit") == true, bool("isActive") != false)
fun J.toPerson() = Person(str("id"), str("displayName", "userDisplayName").orEmpty(), str("type"), str("userId"))
fun J.toGroup() = InfoGroup(str("displayName"), arr("persons").map { it.toPerson() })
fun J.toStudentInfo(): StudentInfo {
    fun groups(key: String): List<InfoGroup> { val o = obj(key) ?: return emptyList(); val d = o.arr("details"); return if (d.isNotEmpty()) d.map { it.toGroup() } else listOf(o.toGroup()).filter { it.persons.isNotEmpty() } }
    return StudentInfo(toStudent(), obj("parents")?.toGroup() ?: InfoGroup("Eltern", emptyList()), obj("partners")?.toGroup() ?: InfoGroup("Partner", emptyList()),
        groups("studentClassInfo"), groups("studentGroupInfo"),
        // Fachlehrkräfte: hierarchisch aus subjectInfo.details[]; flache subjectInfo.persons ist NICHT maßgeblich
        obj("subjectInfo")?.arr("details")?.map { it.toGroup() } ?: emptyList(),
        arr("studentProperties").map { StudentProperty(it.str("name").orEmpty(), it.str("type"), it.str("value"), it.bool("canEdit") == true) }, bool("hasHIP") == true)
}
fun J.toAbsenceReport() = AbsenceReport(str("id"), str("studentId").orEmpty(), str("absenceCategoryId"), str("from"), str("to"), int("fromLesson"), int("toLesson"),
    bool("isFullDay") == true, str("text"), bool("hasAttachment") == true, str("created"), str("creatorUserDisplayName"))
fun J.toCareEntry() = CareEntry(str("studentId"), str("optionId"), str("choice"), str("memo"), bool("canEdit") == true, this)
