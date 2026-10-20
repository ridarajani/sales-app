package com.salesapp.core

import androidx.compose.runtime.Composable
import com.salesapp.resources.Res
import com.salesapp.resources.channel_minibus
import com.salesapp.resources.channel_supermarket
import com.salesapp.resources.channel_upcountry
import com.salesapp.resources.channel_wholesaler
import org.jetbrains.compose.resources.stringResource

// 1234567 -> "1,234,567"
fun formatThousands(amount: Long): String {
    val negative = amount < 0
    val digits = if (negative) amount.toString().drop(1) else amount.toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}

// 1234567 -> "MWK 1,234,567"
fun formatMwk(amount: Long): String = "MWK ${formatThousands(amount)}"

// "0991234567" -> "0991 234 567". Anything that is not 10 digits is shown as it is.
fun formatPhone(digits: String?): String {
    if (digits == null) return ""
    val clean = digits.filter { it.isDigit() }
    if (clean.length != 10) return digits
    return "${clean.substring(0, 4)} ${clean.substring(4, 7)} ${clean.substring(7)}"
}

// Keeps only the digits and turns +265 / 265 / 00265 at the front into a leading 0.
fun normalisePhoneInput(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    val local = when {
        digits.startsWith("00265") -> "0" + digits.removePrefix("00265")
        digits.startsWith("265") -> "0" + digits.removePrefix("265")
        else -> digits
    }
    // 991234567 (nine digits, no leading 0) is accepted too.
    return if (local.length == 9 && !local.startsWith("0")) "0$local" else local
}

@Composable
fun channelLabel(code: String): String = when (code) {
    "supermarket" -> stringResource(Res.string.channel_supermarket)
    "wholesaler" -> stringResource(Res.string.channel_wholesaler)
    "minibus" -> stringResource(Res.string.channel_minibus)
    "upcountry" -> stringResource(Res.string.channel_upcountry)
    else -> code
}
