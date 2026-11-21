package app.meineschule.expressive.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Dates {
    val DE: Locale = Locale.GERMANY
    val zone: ZoneId get() = ZoneId.systemDefault()

    /** ISO-Zeitstempel (mit/ohne Offset), "yyyy-MM-dd" oder "dd.MM.yyyy" → LocalDateTime in lokaler Zeit. */
    fun parse(v: String?): LocalDateTime? {
        val s = v?.trim().orEmpty(); if (s.isEmpty()) return null
        return runCatching {
            when {
                Regex("""\d{4}-\d{2}-\d{2}""").matches(s) -> LocalDate.parse(s).atStartOfDay()
                Regex("""\d{2}\.\d{2}\.\d{4}""").matches(s) -> LocalDate.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy")).atStartOfDay()
                s.endsWith("Z") || Regex(""".*[+-]\d{2}:?\d{2}$""").matches(s) -> OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime()
                else -> LocalDateTime.parse(s)
            }
        }.getOrNull()
    }
    fun date(v: String?): LocalDate? = parse(v)?.toLocalDate()

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun isoWeek(d: LocalDate): Int = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    private val dayLong = DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE)
    private val dayShort = DateTimeFormatter.ofPattern("dd.MM.yyyy", DE)
    private val dm = DateTimeFormatter.ofPattern("d. MMMM", DE)
    private val time = DateTimeFormatter.ofPattern("HH:mm", DE)
    fun dayLong(d: LocalDate): String = d.format(dayLong)
    fun dayShort(d: LocalDate): String = d.format(dayShort)
    fun time(t: LocalDateTime): String = t.format(time)
    fun weekday(d: LocalDate, style: TextStyle = TextStyle.SHORT): String = d.dayOfWeek.getDisplayName(style, DE)
    fun range(a: LocalDate, b: LocalDate): String =
        if (a.month == b.month) "${a.dayOfMonth}.–${b.format(dm)} ${b.year}" else "${a.format(dm)} – ${b.format(dm)} ${b.year}"

    fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) { in 5..10 -> "Guten Morgen"; in 11..16 -> "Guten Tag"; in 17..21 -> "Guten Abend"; else -> "Gute Nacht" }

    /** Chatliste: Uhrzeit heute, „Gestern“, Wochentag, sonst Datum. */
    fun chatTime(v: String?, now: LocalDate = LocalDate.now()): String {
        val t = parse(v) ?: return ""
        val days = Duration.between(t.toLocalDate().atStartOfDay(), now.atStartOfDay()).toDays()
        return when { days == 0L -> time(t); days == 1L -> "Gestern"; days in 2..6 -> weekday(t.toLocalDate()); else -> t.format(DateTimeFormatter.ofPattern("dd.MM.", DE)) }
    }
    fun dayHeading(d: LocalDate, now: LocalDate = LocalDate.now()): String = when (Duration.between(d.atStartOfDay(), now.atStartOfDay()).toDays()) { 0L -> "Heute"; 1L -> "Gestern"; else -> dayLong(d) }
}
