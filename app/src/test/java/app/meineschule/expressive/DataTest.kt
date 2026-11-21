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

    @Test fun schedule_filtersGarbageExtras_acceptsArraySlots_andDetectsChanges() {
        val j = Json.parseToJsonElement("""{"schedule":{"days":[{"d":"2025-03-03","l":[[{"st":"Mathe","r":"null","x":-1,"room":"202","t":null}],{"e":[{"st":"Deutsch","vertretung":"Müller"}]},[{"st":"Kunst","info":"Entfällt"}]]}]}}""")
        val d = parseSchedule(j).days.single()
        val m = d.slots[0].entries.single(); assertEquals("Mathe", m.subject); assertEquals(listOf("room" to "202"), m.extra.filter { it.first == "room" })
        assertFalse(m.extra.any { it.second == "null" || it.second == "-1" })
        assertTrue(d.slots[1].entries.single().changed); assertTrue(d.slots[2].entries.single().cancelled)
    }

    @Test fun deepLinks_parseProfile_andRejectInvalid() {
        val u = "123e4567-e89b-12d3-a456-426614174000"
        val p = app.meineschule.expressive.core.DeepLinks.parseProfile("u=$u&n=Max%20Muster&sc=abc")!!
        assertEquals(u, p.userId); assertEquals("Max Muster", p.name); assertEquals("abc", p.school)
        assertNull(app.meineschule.expressive.core.DeepLinks.parseProfile("u=nope"))
        assertNull(app.meineschule.expressive.core.DeepLinks.parseProfile("u=$u&b=http://evil.example")!!.server)
    }

    @Test fun hip_sanitizesScripts_andParsesGrades() {
        val html = """<script>alert(1)</script><h3>Noten</h3><h4>Mathe</h4><table><tr><th>Datum</th><th>Note</th><th>Bemerkung</th><th>Info</th></tr><tr><td>01.02.2025</td><td>2</td><td>Test</td><td></td></tr></table><img src=x onerror=alert(1)>"""
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
