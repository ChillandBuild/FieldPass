package com.fieldpass.student.core.time

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.DateTimeParseException
import java.util.Locale

/** Parsing of contract UTC timestamps and display in the device's local zone. */
object TimeFormat {

    /** Parses `2026-10-10T09:30:00Z` (with or without fractional seconds). Null when malformed. */
    fun parseUtc(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun formatDateTime(value: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val instant = parseUtc(value) ?: return ""
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale).withZone(zone).format(instant)
    }

    fun formatTime(value: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val instant = parseUtc(value) ?: return ""
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)
    }

    /** Date plus start-end, e.g. "10 Oct 2026, 3:00 PM - 7:00 PM" in the given zone. */
    fun formatRange(startsAt: String?, endsAt: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val start = formatDateTime(startsAt, zone, locale)
        val end = formatTime(endsAt, zone, locale)
        return if (end.isEmpty()) start else "$start - $end"
    }

    /** Milliseconds between two server-provided timestamps; null if either is malformed. */
    fun millisBetween(from: String?, to: String?): Long? {
        val a = parseUtc(from) ?: return null
        val b = parseUtc(to) ?: return null
        return Duration.between(a, b).toMillis()
    }

    /** "1:30" style mm:ss; never negative. */
    fun formatCountdown(remainingMillis: Long): String {
        val totalSeconds = ((remainingMillis.coerceAtLeast(0)) + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}
