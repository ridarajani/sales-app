package com.salesapp.data

import kotlinx.serialization.Serializable

// Phase 10 part 3: one search box over everything, the alerts that need someone now, and the trip
// sales a phone could not read back.

// --- Search ---

// One thing found. The title and the detail are already written in plain words by the server.
@Serializable
data class SearchItem(val id: Int, val title: String = "", val detail: String = "")

// One kind of thing found. count is everything there is; items are the first few.
@Serializable
data class SearchGroup(
    val key: String,
    val title: String = "",
    val count: Int = 0,
    val items: List<SearchItem> = emptyList(),
) {
    // True when the server is holding back more than it sent.
    val more: Boolean get() = count > items.size
}

@Serializable
data class SearchResult(val q: String = "", val groups: List<SearchGroup> = emptyList())

// --- Alerts ---

// severity: bad (money or stock at risk), warn (work waiting) or info (worth knowing). screen and
// id say where the app opens it; report is for the office website.
@Serializable
data class AlertRow(
    val key: String = "",
    val severity: String = "info",
    val title: String = "",
    val detail: String = "",
    val screen: String = "",
    val id: Int? = null,
    val report: String? = null,
) {
    val bad: Boolean get() = severity == "bad"

    val warn: Boolean get() = severity == "warn"
}

@Serializable
data class AlertCounts(val bad: Int = 0, val warn: Int = 0, val info: Int = 0) {
    // What the Home square counts: the things that need doing, not the ones worth knowing.
    val needing: Int get() = bad + warn
}

@Serializable
data class AlertsResult(val alerts: List<AlertRow> = emptyList(), val counts: AlertCounts = AlertCounts())

// --- Trip sales a phone could not read ---

// One such file, as the office sees it.
@Serializable
data class OutboxProblem(
    val id: Int,
    val tripId: Int = 0,
    val tripNumber: String = "",
    val companyCode: String = "",
    val tripStatus: String = "",
    val route: String = "",
    val leaderName: String = "",
    val fileName: String = "",
    val fileSize: Int = 0,
    val note: String = "",
    val reportedBy: String = "",
    val reportedAt: String = "",
    val cleared: Boolean = false,
    val clearedBy: String? = null,
    val clearedAt: String? = null,
    val clearNote: String = "",
    val canClear: Boolean = false,
)

// What the phone sends: the file as base64, under the kept file's own name.
@Serializable
data class OutboxProblemRequest(
    val requestId: String,
    val file: String,
    val fileName: String = "",
    val note: String = "",
)

// The answer, the same for a first send (201) and a repeat (200).
@Serializable
data class OutboxProblemSaved(val id: Int = 0, val cleared: Boolean = false)

@Serializable
data class ClearProblemRequest(val note: String)
