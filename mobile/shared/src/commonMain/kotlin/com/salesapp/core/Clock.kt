package com.salesapp.core

// The hour of the day on the phone, 0 to 23. Used for "Good morning".
expect fun currentHour(): Int

// Today's date on the phone as YYYY-MM-DD.
expect fun todayIsoDate(): String

// How far the phone's clock is ahead of UTC right now, in minutes (Malawi: 120).
expect fun currentUtcOffsetMinutes(): Int
