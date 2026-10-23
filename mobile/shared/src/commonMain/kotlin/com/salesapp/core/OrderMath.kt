package com.salesapp.core

import kotlin.math.roundToLong

// The same order sums as the server, so the phone shows exactly what the invoice will say.

// Used when a company has no VAT rate set.
const val DEFAULT_VAT_RATE = 17.5

// Price is per carton, so loose pieces cost their share of a carton. Rounds half up, like the server.
fun lineAmount(totalPieces: Int, pricePerCarton: Long, piecesPerCarton: Int): Long {
    if (totalPieces <= 0 || pricePerCarton <= 0) return 0
    val perCarton = piecesPerCarton.coerceAtLeast(1).toLong()
    val a = pricePerCarton * totalPieces
    return (2 * a + perCarton) / (2 * perCarton)
}

// VAT on a subtotal at a rate in percent (17.5 means 17.5%).
fun vatOf(subtotal: Long, vatRatePercent: Double): Long = (subtotal * vatRatePercent / 100.0).roundToLong()

// Tomorrow's date on the phone as YYYY-MM-DD: the usual delivery day.
fun tomorrowIsoDate(): String {
    val today = todayIsoDate()
    val day = epochDayFromIso(today) ?: return today
    return isoFromEpochDay(day + 1)
}
