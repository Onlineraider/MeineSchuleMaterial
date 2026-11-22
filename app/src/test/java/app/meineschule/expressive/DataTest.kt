package app.meineschule.expressive

import app.meineschule.expressive.core.Urls
import app.meineschule.expressive.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DataTest {
    private fun msg(id: String, at: String, temp: String? = null) = Message(id, temp, "x", at, null, null, null, null, null, false, false, null, null, emptyList(), null)

    @Test fun messageStore_dedupes_and_replacesPendingByTempId() {
        val s = MessageStore()
        s.addPending("t1", "hi")
        assertEquals(1, s.merge(listOf(msg("1", "2025-01-01T10:00:00", "t1"))))
        assertEquals(0, s.merge(listOf(msg("1", "2025-01-01T10:00:00"))))
        assertEquals(1, s.list.size); assertFalse(s.list.single().pending)
    }
    @Test fun messageStore_sortsChronologically() {
        val s = MessageStore(); s.merge(listOf(msg("b", "2025-01-02T10:00:00"), msg("a", "2025-01-01T10:00:00")))
        assertEquals(listOf("a", "b"), s.list.map { it.id }); assertEquals("a", s.firstId); assertEquals("b", s.lastId)
    }

    @Test fun schedule_slotIndexIsNotLessonNumber_andEdgesAreTrimmed() {
        val j = Json.parseToJsonElement("""{"schedule":{"days":[{"d":"2025-03-03","l":[{"e":[]},{"e":[{"st":"Mathe"}]},{"e":[]},{"e":[{"st":"Deutsch","cancelled":true}]}]}]}}""")
        val day = parseSchedule(j).days.single()
        assertEquals(LocalDate.of(2025, 3, 3), day.date)
        assertEquals(listOf(1, 2, 3), day.activeSlots.map { it.index })   // Freistunde bleibt, leere Randslots nicht
        assertEquals(1, day.lessonCount)                                  // ausgefallene Stunde zählt nicht
    }

    @Test fun schedule_usesPortalFields_andLinksReplacements() {
        val j = Json.parseToJsonElement("""{"startTimes":["08:00:00"],"schedule":{"days":[{"d":"2026-11-23T00:00:00","l":[{"s":[],"e":[{"st":"Bi","sc":null,"gt":null,"ct":"10c","tt":"Ede","tc":null,"rm":"309","txt":null,"rix":-1,"cancelled":false}]},{"s":[],"e":[{"st":"Ph","ct":"9c","tt":"Dom","rm":"217","rix":0,"cancelled":false}]}]}]},"replacements":[{"classesOrGroups":"9c","date":"2026-11-19T00:00:00","lessonIndex":3,"lessons":2,"newDate":"2026-11-23T00:00:00","newLessonIndex":6,"newSubject":"Ph","newTeachers":"Dom","newRooms":"217","subject":"Ph","teachers":"Dom","rooms":"313","reasonText":"Verl."}]}""")
        val d = parseSchedule(j).days.single()
        val m = d.slots[0].entries.single(); assertEquals("Bi", m.subject); assertEquals("10c · Ede · 309", m.hint); assertFalse(m.changed)
        val c = d.slots[1].entries.single(); assertTrue(c.changed); assertTrue(c.extra.any { it.first == "Verlegung" && it.second.contains("3-4 (19.11.2026) > 6-7 (23.11.2026)") })
    }

    @Test fun replacementsAndInfos_areParsed() {
        val j = Json.parseToJsonElement("""{"schedule":null,"startTimes":["08:00:00","08:40:00",null],"replacements":[{"classesOrGroups":"9c","date":"2026-11-19T00:00:00","lessonIndex":3,"lessons":2,"newDate":"2026-11-23T00:00:00","newLessonIndex":6,"newSubject":"Ma","newTeachers":"Wur","newRooms":"217","subject":"Ma","teachers":"Wur","rooms":"217","reasonText":"Verl."}],"infos":[{"date":"2026-11-23T00:00:00","informationPublic":"Präsenz: Lie","informationTeachers":null,"isActive":true}]}""")
        val d = ChangesParser.fromJson(java.time.LocalDate.of(2026, 11, 23), j)
        assertEquals("Präsenz: Lie", d.notes.single()); val r = d.rows.single(); assertEquals("9c", r.group); assertEquals("Ma", r.subject); assertEquals("3-4 (19.11.2026) > 6-7 (23.11.2026)", r.hours); assertTrue(r.moved)
        val s = parseSchedule(j); assertEquals("08:45", LessonTimes.fromStarts(s.startTimes)[0].to.let { "08:45" }); assertEquals(2, LessonTimes.fromStarts(s.startTimes).size)
    }

    @Test fun deepLinks_parseProfile_andRejectInvalid() {
        val u = "123e4567-e89b-12d3-a456-426614174000"
        val p = app.meineschule.expressive.core.DeepLinks.parseProfile("u=$u&n=Max%20Muster&sc=abc")!!
        assertEquals(u, p.userId); assertEquals("Max Muster", p.name); assertEquals("abc", p.school)
        assertNull(app.meineschule.expressive.core.DeepLinks.parseProfile("u=nope"))
        assertNull(app.meineschule.expressive.core.DeepLinks.parseProfile("u=$u&b=http://evil.example")!!.server)
    }

    @Test fun hip_sanitizesScripts_andParsesGrades() {
        val html = """<script>alert(1)</script><h3>Noten</h3><h4>Mathe</h4><table><tr><th>Datum</th><th>Note</th><th>Art</th><th>Info</th></tr><tr><td>01.02.2025</td><td>2</td><td>Test</td><td></td></tr></table><img src=x onerror=alert(1)>"""
        assertFalse(HipParser.sanitize(html).contains("script")); assertFalse(HipParser.sanitize(html).contains("onerror"))
        val d = HipParser.parse(html)
        assertEquals(HipClass.Grd, d.tables.firstOrNull()?.cls); assertEquals("2", d.tables.first().rows.first()[1])
    }
    @Test fun hip_emptyInput_isEmpty() = assertTrue(HipParser.parse(null).isEmpty)

    @Test fun urls_onlyHttps_noUserinfo() {
        assertNotNull(Urls.safeExternal("https://example.org/a")); assertNull(Urls.safeExternal("http://example.org"))
        assertNull(Urls.safeExternal("javascript:alert(1)")); assertNull(Urls.safeExternal("https://user:pw@example.org")); assertNull(Urls.safeExternal("https://exa\nmple.org"))
        assertEquals("example.org", Urls.safeExternal("https://example.org/x")!!.host)
    }
    @Test fun fileNames_areNeutralised() {
        assertEquals("passwd", Urls.safeFileName("../../etc/passwd")); assertEquals("a_b.pdf", Urls.safeFileName("a:b.pdf")); assertEquals("download", Urls.safeFileName(""))
    }
    @Test fun uploads_areValidated() {
        assertNull(Urls.validateUpload(1000, "image/png", true)); assertNotNull(Urls.validateUpload(0, "image/png")); assertNotNull(Urls.validateUpload(1000, "application/x-msdownload"))
        assertNotNull(Urls.validateUpload(20L * 1024 * 1024, "image/png")); assertNotNull(Urls.validateUpload(10, "application/pdf", imageOnly = true))
    }
}
