package com.salesapp.core

import java.util.Calendar
import java.util.TimeZone

actual fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

actual fun todayIsoDate(): String {
    val now = Calendar.getInstance()
    val month = (now.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val day = now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "${now.get(Calendar.YEAR)}-$month-$day"
}

actual fun currentUtcOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
