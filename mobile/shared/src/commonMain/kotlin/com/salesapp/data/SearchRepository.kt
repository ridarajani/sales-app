package com.salesapp.data

// Where the search box gets its answers. An interface so a test can stand in for the server, since
// the waiting and the dropping of late answers is the part that has to be right.
interface SearchSource {
    suspend fun search(q: String): SearchResult
}

class SearchRepository(private val api: Api) : SearchSource {
    override suspend fun search(q: String): SearchResult = api.search(q)
}

// Where the alerts screen and the Home square get their list.
class AlertsRepository(private val api: Api) {
    suspend fun alerts(): AlertsResult = api.alerts()

    suspend fun problem(id: Int): OutboxProblem = api.outboxProblem(id)

    suspend fun clearProblem(id: Int, note: String): OutboxProblem = api.clearOutboxProblem(id, note)
}
