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

// "0991234567" -> "0991 234 567". Foreign numbers (+971501234567) are shown as stored.
fun formatPhone(phone: String?): String {
    if (phone.isNullOrBlank()) return ""
    val clean = normalisePhoneInput(phone)
    if (clean.length != 10 || !clean.all { it in '0'..'9' } || clean[0] != '0') return clean
    return "${clean.substring(0, 4)} ${clean.substring(4, 7)} ${clean.substring(7)}"
}

private val MALAWI_PREFIX = Regex("^(?:\\+265|00265|265)(.*)$")
private val MALAWI_SHORT = Regex("^[389][0-9]{8}$")
private val LOCAL_PHONE = Regex("^0[0-9]{9}$")
private val INTERNATIONAL_PHONE = Regex("^\\+[1-9][0-9]{7,14}$")

// Mirrors the server: strips spaces, '-', '(', ')' and '.'; Malawi numbers (+265 / 00265 / 265 / 9 digits)
// become 0 + 9 digits, other +country numbers keep the '+', 00country becomes +country.
fun normalisePhoneInput(raw: String): String {
    val p = raw.filter { !it.isWhitespace() && it != '-' && it != '(' && it != ')' && it != '.' }
    val malawi = MALAWI_PREFIX.matchEntire(p)
    return when {
        malawi != null -> {
            val rest = malawi.groupValues[1]
            if (rest.startsWith("0")) rest else "0$rest"
        }
        p.startsWith("+") -> p
        p.startsWith("00") -> "+" + p.substring(2)
        MALAWI_SHORT.matches(p) -> "0$p"
        else -> p
    }
}

// For an already normalised number: 0 + 9 digits, or + country code with 8 to 15 digits.
fun isValidPhone(phone: String): Boolean = LOCAL_PHONE.matches(phone) || INTERNATIONAL_PHONE.matches(phone)

@Composable
fun channelLabel(code: String): String = when (code) {
    "supermarket" -> stringResource(Res.string.channel_supermarket)
    "wholesaler" -> stringResource(Res.string.channel_wholesaler)
    "minibus" -> stringResource(Res.string.channel_minibus)
    "upcountry" -> stringResource(Res.string.channel_upcountry)
    else -> code
}

// 25 pieces in cartons of 12 -> (2, 1): cartons and loose pieces.
fun splitPieces(total: Int, piecesPerCarton: Int): Pair<Int, Int> {
    if (piecesPerCarton <= 0) return 0 to total
    return total / piecesPerCarton to total % piecesPerCarton
}
