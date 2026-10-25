package com.salesapp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Phase 10: the reports the office asked for on 7 October, and each salesman's monthly target.
// One screen shows every report, so the server's catalogue says what each one takes and shows.

// A column's kind decides how its value is written: money and int as whole numbers with thousands
// separators, percent with a %, date as dd/MM/yyyy, qty as cartons and pieces (already written by
// the server), and text as it comes.
@Serializable
data class ReportColumn(
    val key: String,
    val title: String = "",
    val kind: String = "text",
    val total: Boolean = false,
) {
    val isNumber: Boolean get() = kind == "money" || kind == "int" || kind == "percent"

    val isText: Boolean get() = kind == "text"
}

// One report in the catalogue: which filters it takes, which must be picked first, what its person
// filter is called, and where its dates start.
@Serializable
data class ReportInfo(
    val key: String,
    val title: String = "",
    val description: String = "",
    val filters: List<String> = emptyList(),
    val required: List<String> = emptyList(),
    val personLabel: String = "Person",
    val defaultFrom: String = "month",
) {
    fun uses(filter: String): Boolean = filter in filters

    fun needs(filter: String): Boolean = filter in required
}

@Serializable
data class ReportGroup(val key: String, val title: String = "", val reports: List<ReportInfo> = emptyList())

// Staff this person may pick in a person filter.
@Serializable
data class ReportPerson(val id: Int, val name: String = "", val role: String = "")

@Serializable
data class ReportCatalogue(val groups: List<ReportGroup> = emptyList(), val people: List<ReportPerson> = emptyList())

// One report, run. The rows are plain JSON: each column's kind says how to read and write its value.
@Serializable
data class ReportResult(
    val key: String = "",
    val group: String = "",
    val description: String = "",
    val from: String = "",
    val to: String = "",
    val title: String = "",
    // What was picked, in the server's own words.
    val picked: String = "",
    val columns: List<ReportColumn> = emptyList(),
    val rows: List<JsonObject> = emptyList(),
    val totals: Map<String, Double> = emptyMap(),
    // True when there were more rows than the server will send.
    val truncated: Boolean = false,
)

// What the user picked on a report screen. Kept while the app is open, one per report.
data class ReportFilters(
    val from: String = "",
    val to: String = "",
    // Null means every company this person works for.
    val company: String? = null,
    val person: Int? = null,
    val customer: Int? = null,
    val channel: String? = null,
    val product: Int? = null,
    // The names of what was picked, so the screen can show them without asking again.
    val customerName: String = "",
    val productName: String = "",
) {
    fun query(): Map<String, String?> = mapOf(
        "from" to from.ifBlank { null },
        "to" to to.ifBlank { null },
        "company" to company,
        "person" to person?.toString(),
        "customer" to customer?.toString(),
        "channel" to channel,
        "product" to product?.toString(),
    )

    // Whether a filter the report must have has been picked.
    fun has(filter: String): Boolean = when (filter) {
        "dates" -> from.isNotBlank() && to.isNotBlank()
        "company" -> company != null
        "person" -> person != null
        "customer" -> customer != null
        "channel" -> channel != null
        "product" -> product != null
        else -> true
    }
}

// --- Targets ---

// One salesman's target for a month. Null means none is set.
@Serializable
data class TargetRow(
    val salesmanId: Int,
    val name: String = "",
    val companies: List<String> = emptyList(),
    val amount: Double? = null,
)

// Builds the body for setting a target. The amount is always sent, as null when the field is left
// empty, because null is how a target is taken away.
fun targetBody(salesmanId: Int, month: String, amount: Int?): JsonObject = buildJsonObject {
    put("salesmanId", salesmanId)
    put("month", month)
    put("amount", amount as Number?)
}

// Reads one cell out of a row. Numbers and text both come back as text here; the screen decides how
// to write it from the column's kind.
fun JsonObject.cell(key: String): String {
    val value = this[key] ?: return ""
    val primitive = value as? JsonPrimitive ?: return ""
    return if (primitive.isString) primitive.content else primitive.content.takeIf { it != "null" }.orEmpty()
}

fun JsonObject.number(key: String): Double? = cell(key).toDoubleOrNull()
