package com.salesapp.core

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeZoneWithAbbreviation

actual fun currentHour(): Int = NSCalendar.currentCalendar.component(NSCalendarUnitHour, fromDate = NSDate()).toInt()

actual fun todayIsoDate(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    return formatter.stringFromDate(NSDate())
}

actual fun currentUtcOffsetMinutes(): Int = NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate()).toInt() / 60

actual fun nowUtcTimestamp(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    formatter.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    formatter.timeZone = NSTimeZone.timeZoneWithAbbreviation("UTC") ?: NSTimeZone.localTimeZone
    return formatter.stringFromDate(NSDate())
}
