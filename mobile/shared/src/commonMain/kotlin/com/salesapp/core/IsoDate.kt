package com.salesapp.core

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

// "2026-10-20" -> "20 Oct 2026". Anything that is not a date is shown as it is.
fun formatIsoDate(iso: String): String {
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return iso
    val year = parts[0].toIntOrNull() ?: return iso
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return iso
    val day = parts[2].toIntOrNull() ?: return iso
    return "$day ${monthNames[month - 1]} $year"
}

// Days since 1 Jan 1970 for a date; the other way round is isoFromEpochDay.
fun epochDayFromIso(iso: String): Long? {
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

fun isoFromEpochDay(epochDay: Long): String {
    val z = epochDay + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

private const val MILLIS_PER_DAY = 86_400_000L

fun isoFromUtcMillis(millis: Long): String = isoFromEpochDay(millis.floorDiv(MILLIS_PER_DAY))

fun utcMillisFromIso(iso: String): Long? = epochDayFromIso(iso)?.let { it * MILLIS_PER_DAY }

// "2026-10-20T22:30:00.000Z" (UTC) -> the date on the phone's clock, "2026-10-21" in Malawi.
fun localIsoDateFromUtcTimestamp(timestamp: String): String {
    val day = epochDayFromIso(timestamp) ?: return timestamp.take(10)
    val time = timestamp.drop(11).take(5).split(":")
    val minutes = (time.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (time.getOrNull(1)?.toIntOrNull() ?: 0)
    val local = day * 1440 + minutes + currentUtcOffsetMinutes()
    return isoFromEpochDay(local.floorDiv(1440L))
}
