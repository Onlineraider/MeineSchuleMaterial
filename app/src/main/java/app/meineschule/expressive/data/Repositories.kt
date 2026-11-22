package app.meineschule.expressive.data

import app.meineschule.expressive.core.*
import app.meineschule.expressive.net.ApiClient
import app.meineschule.expressive.net.PortalClient
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Repositories: dünne Wrapper um die in der Doku belegten Endpunkte (siehe docs/API-MAPPING.md). Nichts erfunden. */
class Repos(private val api: ApiClient, private val portal: PortalClient) {
    /** Eigene User-ID (für „meine Reaktion“); wird von Session gesetzt. */
    @Volatile var meId: String? = null
    private fun q(vararg p: Pair<String, String?>): String = p.filter { !it.second.isNullOrEmpty() }.joinToString("&") { "${it.first}=${Urls.seg(it.second!!)}" }.let { if (it.isEmpty()) "" else "?$it" }
    private fun seg(s: String) = Urls.seg(s)

    val school = SchoolRepo(); val messenger = MessengerRepo(); val calendar = CalendarRepo(); val media = MediaRepo(); val hip = HipRepo()
    val protocols = ProtocolRepo(); val lost = LostRepo(); val absence = AbsenceRepo(); val care = CareRepo()

    inner class SchoolRepo {
        suspend fun profile(): Profile = J(api.get(api.global("Profile"))).let { p -> Profile(p.str("userId").orEmpty(), p.str("displayName").orEmpty(), p.arr("schools").map { ProfileSchool(it.str("name").orEmpty(), it.str("tag").orEmpty()) }) }
        suspend fun index(): SchoolInfo = J(api.get(api.scoped("School/Index"))).let { s ->
            SchoolInfo(s.str("name").orEmpty(), s.strings("roles"), s.arr("features").map { it.raw?.toString()?.trim('"').orEmpty() }, s.arr("students").map { it.toStudent() },
                s.arr("links").map { SchoolLink(it.str("title").orEmpty(), it.str("url").orEmpty(), it.str("appMode") ?: "Disabled") }, s.arr("translationLanguages").map { Language(it.str("key", "id", "code").orEmpty(), it.str("name", "displayName") ?: it.str("key").orEmpty()) },
                s.bool("userHasHIPAccess") == true, s.bool("canUseMessenger") == true, s.bool("canCreateAbsences") == true, s.bool("canCreateLostAndFound") == true,
                s.bool("canCreateProtocol") == true, s.bool("canCreateMediaItems") == true, s.bool("canCreateCalendarItems") == true, s.bool("hasStudentProperties") == true)
        }
        suspend fun state(): SchoolState = J(api.get(api.scoped("School/State"))).let { s ->
            fun ids(k: String) = s.arr(k).map { it.str("chatId", "id") ?: it.raw?.toString()?.trim('"').orEmpty() }
            SchoolState(ids("unreadChatItems"), ids("notConfirmedCalenderItems"), ids("notConfirmedMediaItems"), s.int("unreadChatMessageCount") ?: 0)
        }
        suspend fun users(ids: List<String>): Map<String, String> = J.list(api.post(api.global("Users"), JsonArray(ids.map { JsonPrimitive(it) }))).associate { it.str("id").orEmpty() to it.str("displayname", "displayName").orEmpty() }
        suspend fun userInfo(id: String): List<String> = J(api.get(api.scoped("School/UserInfo/${seg(id)}"))).strings("relations")
        suspend fun student(id: String): StudentInfo = J(api.get(api.scoped("School/Student/${seg(id)}"))).toStudentInfo()
        suspend fun classes(): List<IdName> = J.list(api.get(api.scoped("School/StudentClasses"))).map { it.toIdName() }
        suspend fun groups(): List<IdName> = J.list(api.get(api.scoped("School/StudentGroups"))).map { it.toIdName() }
        suspend fun studentsOf(scope: String, id: String): List<Student> = J.list(api.get(api.scoped("School/Students/${if (scope == "SG") "SG" else "SC"}/${seg(id)}"))).map { it.toStudent() }
        /** Empfängerindex (Struktur nicht vollständig dokumentiert): alle Objekte mit `key` werden als KeyText übernommen. */
        suspend fun recipients(): List<Recipient> {
            val out = mutableListOf<Recipient>()
            fun walk(e: JsonElement?) { when (e) { is JsonArray -> e.forEach { walk(it) }; is JsonObject -> { if (e.keys.any { it.equals("key", true) }) J(e).let { out += it.toRecipient() } else e.values.forEach { walk(it) } }; else -> {} } }
            walk(api.get(api.scoped("School/Recipients"))); return out
        }
        suspend fun searchRecipients(term: String): List<Recipient> = J.list(api.get(api.scoped("School/SearchRecipients/${seg(term)}"))).map { it.toRecipient() }
        private fun J.toRecipient() = Recipient(str("key").orEmpty(), str("text").orEmpty(), str("information", "info"), str("source"))
        /** One-Time-Token unmittelbar vor dem Öffnen anfordern; wird weder gespeichert noch geloggt. Gibt die Browser-URL zurück. */
        suspend fun webLoginUrl(path: String, origin: String, schoolTag: String): String {
            val token = J(api.post(api.global("OneTimeToken"))).str("token") ?: throw ApiException(ErrorKind.Server, "Kein One-Time-Token")
            val back = "/$schoolTag/${path.trimStart('/')}"
            return "$origin/Account/Login?token=${Urls.seg(token)}&inapp=1&returnurl=${Urls.seg(back)}"
        }
        fun avatarUrl(userId: String, w: Int = 96) = "${api.origin}/Account/Image/${seg(userId)}?w=$w"
    }

    inner class MessengerRepo {
        suspend fun index(search: String? = null, category: String? = null): List<Chat> = J.list(api.get(api.scoped("Messenger/Index${q("searchquery" to search, "category" to category)}"))).map { it.toChat() }
        suspend fun categories(): List<IdName> = J.list(api.get(api.scoped("Messenger/Categories"))).map { it.toIdName() }
        /** [recipients] im Serverformat `key=Anzeigename` (wie die Original-App), z. B. `U:USER:{id}=Max Muster`. */
        suspend fun create(name: String?, recipients: List<String>, reuse: Boolean = true, authorsLimited: Boolean = false): String? {
            val body = buildJsonObject { put("recipients", JsonArray(recipients.map { JsonPrimitive(it) })); put("name", name?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull); put("useExisting", reuse); put("authorsLimited", authorsLimited) }
            return J(api.post(api.scoped("Messenger/Chat/Create"), body)).str("id", "chatId")
        }
        suspend fun editData(id: String): J = J(api.get(api.scoped("Messenger/Chat/${seg(id)}/Edit")))
        suspend fun editSave(id: String, recipients: List<String>, name: String?, authorsLimited: Boolean, authors: List<JsonElement>) {
            api.patch(api.scoped("Messenger/Chat/${seg(id)}/Edit"), buildJsonObject { put("id", id); put("recipients", JsonArray(recipients.map { JsonPrimitive(it) })); put("name", name?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull); put("authorsLimited", authorsLimited); put("authors", JsonArray(if (authorsLimited) authors else emptyList())) })
        }
        suspend fun remove(id: String) { api.delete(api.scoped("Messenger/Chat/${seg(id)}")) }
        suspend fun mute(id: String, v: Boolean) { api.post(api.scoped("Messenger/Chat/${seg(id)}/Mute"), buildJsonObject { put("isSilent", v) }) }
        suspend fun pin(id: String, v: Boolean) { api.post(api.scoped("Messenger/Chat/${seg(id)}/Pin"), buildJsonObject { put("isPinned", v) }) }
        suspend fun markUnread(id: String) { api.post(api.scoped("Messenger/Chat/${seg(id)}/MarkAsUnread"), buildJsonObject {}) }
        suspend fun chat(id: String): Chat = J(api.get(api.scoped("Messenger/Chat/${seg(id)}"))).toChat()
        suspend fun deleteChat(id: String) { api.delete(api.scoped("Messenger/Chat/${seg(id)}")) }
        suspend fun info(id: String): J = J(api.get(api.scoped("Messenger/Chat/${seg(id)}/Info")))
        suspend fun items(id: String, page: Int = 1, size: Int = 30): List<Message> = J.list(api.get(api.scoped("Messenger/Chat/${seg(id)}/Items?page=$page&pagesize=$size"))).map { it.toMessage(meId) }
        suspend fun before(id: String, msgId: String): List<Message> = J.list(api.get(api.scoped("Messenger/Chat/${seg(id)}/ItemsBefore/${seg(msgId)}"))).map { it.toMessage(meId) }
        suspend fun after(id: String, msgId: String): List<Message> = J.list(api.get(api.scoped("Messenger/Chat/${seg(id)}/ItemsAfter/${seg(msgId)}?maxitems=1000"))).map { it.toMessage(meId) }
        /** `tempId` nur mitsenden, wenn vorher eine Datei per [upload] hochgeladen wurde (wie die Original-App). */
        suspend fun send(chatId: String, content: String, tempId: String? = null, replyTo: String? = null): Message =
            J(api.post(api.scoped("Messenger/Chat/${seg(chatId)}/Create"), buildJsonObject { put("content", content); tempId?.let { put("tempId", it) }; replyTo?.let { put("replyToMessageId", it) } })).toMessage(meId)
        suspend fun upload(chatId: String, tempId: String, bytes: ByteArray, mime: String, name: String) { api.upload(api.scoped("Messenger/Chat/${seg(chatId)}/Upload/${seg(tempId)}"), bytes, mime, name) }
        suspend fun forward(chatId: String, messageId: String): Message = J(api.post(api.scoped("Messenger/Chat/${seg(chatId)}/Forward"), JsonPrimitive(messageId))).toMessage()
        suspend fun delete(chatId: String, ids: List<String>) { api.post(api.scoped("Messenger/Chat/${seg(chatId)}/Delete"), buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }) }
        suspend fun report(chatId: String, messageId: String, reason: String) { api.post(api.scoped("Messenger/Chat/${seg(chatId)}/Report/${seg(messageId)}"), buildJsonObject { put("reason", reason) }) }
        suspend fun markRead(chatId: String, ids: List<String>) { api.post(api.scoped("Messenger/Chat/${seg(chatId)}/MarkAsRead"), buildJsonObject { if (ids.isNotEmpty()) put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }) }
        /** Emoji-Reaktion setzen; `null` entfernt die eigene Reaktion. */
        suspend fun react(chatId: String, messageId: String, emoji: String?) { api.post(api.scoped("Messenger/Chat/${seg(chatId)}/Vote/${seg(messageId)}"), buildJsonObject { put("vote", emoji?.let { JsonPrimitive(it) } ?: JsonNull) }) }
        fun downloadUrl(chatId: String, attId: String) = api.web("Messenger/Chat/${seg(chatId)}/Download/${seg(attId)}")
    }

    inner class CalendarRepo {
        suspend fun index(): List<CalendarEvent> = J.list(api.get(api.scoped("Calendar/Index"))).map { it.toCalendar() }
        suspend fun detail(id: String, lang: String?): CalendarEvent = J(api.get(api.scoped("Calendar/Detail/${seg(id)}${q("lang" to lang)}"))).toCalendar()
        /** `answer`: 1 = Zusagen, 2 = Absagen, 0 = Keine Antwort (aus dem Web-Client). */
        suspend fun confirm(id: String, answer: Int) { api.post(api.scoped("Calendar/Confirm/${seg(id)}"), buildJsonObject { put("answer", answer) }) }
    }

    inner class MediaRepo {
        suspend fun categories(): List<IdName> = J.list(api.get(api.scoped("Media/Categories"))).map { it.toIdName() }
        suspend fun index(filters: Map<String, String> = emptyMap()): List<MediaItem> = J.list(api.get(api.scoped("Media/Index${q(*filters.toList().toTypedArray())}"))).map { it.toMedia() }
        suspend fun detail(id: String, lang: String?): MediaItem = J(api.get(api.scoped("Media/Detail/${seg(id)}${q("lang" to lang)}"))).toMedia()
        suspend fun confirm(id: String) { api.post(api.scoped("Media/Confirm/${seg(id)}"), buildJsonObject {}) }
        suspend fun toggleLike(id: String) { api.post(api.scoped("Media/ToggleLike/${seg(id)}"), buildJsonObject {}) }
        fun imageUrl(id: String, w: Int = 512) = api.web("Media/Image/${seg(id)}?w=$w")
        fun downloadUrl(attId: String) = api.web("Media/Download/${seg(attId)}")
    }

    inner class HipRepo {
        suspend fun students(): List<Student> = J(api.get(api.scoped("HIP/Index"))).let { r -> r.arr("students").ifEmpty { r.arr("Students") } }.map { it.toStudent() }
        /** HTML-Fragmente (content, contentCompact) – Bereinigung in HipParser. */
        suspend fun student(id: String): HipData = J(api.get(api.scoped("HIP/Student/${seg(id)}"))).let { HipParser.parse(listOf("content", "contentCompact").firstNotNullOfOrNull { k -> it.str(k)?.takeIf { v -> v.isNotBlank() } }) }
    }

    inner class ProtocolRepo {
        suspend fun index(): List<ProtocolItem> = J.list(api.get(api.scoped("Protocol/Index"))).map { it.toProtocol() }
        suspend fun detail(id: String): ProtocolDetail = J(api.get(api.scoped("Protocol/Detail/${seg(id)}"))).toProtocolDetail()
        fun downloadUrl(protocolId: String, attId: String) = api.web("Protocol/${seg(protocolId)}/download/${seg(attId)}")
    }

    inner class LostRepo {
        suspend fun index(): List<LostItem> = J.list(api.get(api.scoped("LostAndFound/Index"))).map { it.toLost() }
        suspend fun create(name: String, location: String): LostItem = J(api.post(api.scoped("LostAndFound/"), buildJsonObject { put("name", name); put("location", location) })).toLost()
        suspend fun update(item: LostItem) { api.patch(api.scoped("LostAndFound/"), buildJsonObject { put("id", item.id); put("name", item.name); put("location", item.location); put("text", item.text); put("found", item.found); put("isActive", item.isActive) }) }
        suspend fun remove(id: String) { api.delete(api.scoped("LostAndFound/${seg(id)}")) }
        suspend fun attach(id: String, bytes: ByteArray, mime: String) { api.upload(api.scoped("LostAndFound/${seg(id)}/Attachment"), bytes, mime, "photo.jpg") }
        fun imageUrl(id: String, w: Int = 512) = api.web("LostAndFound/Image/${seg(id)}?w=$w")
    }

    inner class AbsenceRepo {
        suspend fun index(): AbsenceIndex = J(api.get(api.scoped("Absence/Report/Index"))).let { AbsenceIndex(it.arr("students").map { s -> s.toStudent() }, it.arr("categories").map { c -> c.toIdName() }, it.arr("lessons").map { l -> IdName(l.str("id", "index", "number") ?: "", l.str("displayName", "name") ?: l.str("id", "number").orEmpty()) }) }
        suspend fun byDate(d: LocalDate): J = J(api.get(api.scoped("Absence/Index/$d")))
        suspend fun reports(studentId: String): List<AbsenceReport> = J.list(api.get(api.scoped("Absence/Report/Student/${seg(studentId)}"))).map { it.toAbsenceReport() }
        private fun body(r: AbsenceReport) = buildJsonObject {
            r.id?.let { put("id", it) }; put("studentId", r.studentId); r.categoryId?.let { put("absenceCategoryId", it) }; r.from?.let { put("from", it) }; r.to?.let { put("to", it) }
            r.fromLesson?.let { put("fromLesson", it) }; r.toLesson?.let { put("toLesson", it) }; put("isFullDay", r.fullDay); r.text?.let { put("text", it) }
        }
        suspend fun create(r: AbsenceReport): AbsenceReport = J(api.post(api.scoped("Absence/Report/Student/${seg(r.studentId)}"), body(r))).toAbsenceReport()
        suspend fun update(r: AbsenceReport) { api.patch(api.scoped("Absence/Report/Student/${seg(r.studentId)}"), body(r)) }
        suspend fun remove(studentId: String, reportId: String) { api.delete(api.scoped("Absence/Report/Student/${seg(studentId)}/${seg(reportId)}")) }
        suspend fun attach(studentId: String, reportId: String, bytes: ByteArray, mime: String) { api.upload(api.scoped("Absence/Report/Student/${seg(studentId)}/${seg(reportId)}/Attachment"), bytes, mime, "report.jpg") }
        fun imageUrl(reportId: String, w: Int = 1024) = api.web("StudentAbsenceReport/Image/${seg(reportId)}?w=$w")
    }

    inner class CareRepo {
        suspend fun index(): CareIndex = J(api.get(api.scoped("AfterSchoolCare/Parent/Index"))).let { CareIndex(it.arr("students").map { s -> s.toStudent() }, it.arr("pickupOptions").map { o -> PickupOption(o.str("id").orEmpty(), o.str("displayName", "name", "text").orEmpty()) }, it.int("pickupWeekDays")) }
        suspend fun byDate(d: LocalDate): List<CareEntry> = J.list(api.get(api.scoped("AfterSchoolCare/Parent/$d"))).map { it.toCareEntry() }
        /** Eine nicht-null Antwort bedeutet laut Client Erfolg. */
        suspend fun save(studentId: String, date: LocalDate, optionId: String?, memo: String?, weekDefaults: Set<java.time.DayOfWeek> = emptySet(), futureDefault: Boolean = false): Boolean {
            val body = buildJsonObject {
                put("date", date.toString()); optionId?.let { put("optionId", it) }; memo?.let { put("memo", it) }; put("isFutureDefault", futureDefault)
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"); java.time.DayOfWeek.entries.forEachIndexed { i, d -> if (weekDefaults.isNotEmpty()) put("is${names[i]}Default", d in weekDefaults) }
            }
            return api.post(api.scoped("AfterSchoolCare/Parent/Student/${seg(studentId)}"), body) != null
        }
        suspend fun remove(studentId: String, date: LocalDate) { api.delete(api.scoped("AfterSchoolCare/Parent/Student/${seg(studentId)}/$date")) }
    }

    /** Vertretungen/Änderungen eines Tages: Portal-Ansicht „Vertretungen“ (`view=replacements`) liefert `replacements` + `infos`. */
    suspend fun changes(date: LocalDate): DayChanges = ChangesParser.fromJson(date, portal.scheduleJson(api.school ?: throw ApiException(ErrorKind.Client, "Keine Schule"), date, null, null, null))
    suspend fun schedule(date: LocalDate, studentId: String?, cog: String? = null): Schedule = parseSchedule(portal.scheduleJson(api.school ?: throw ApiException(ErrorKind.Client, "Keine Schule"), date, studentId, cog))
}
