package com.salesapp.data

// Where the report screens get their data. The catalogue is asked for once and kept, because it is
// the same for a person all day; the filters each report was last run with are kept too, so coming
// back to a report does not mean picking everything again.
class ReportsRepository(private val api: Api, app: AppState) {
    private var catalogue: ReportCatalogue? = null
    private val lastFilters = mutableMapOf<String, ReportFilters>()

    init {
        // Another person signing in on this phone starts again with nothing.
        app.onSessionChanged {
            catalogue = null
            lastFilters.clear()
        }
    }

    suspend fun catalogue(): ReportCatalogue = catalogue ?: api.reports().also { catalogue = it }

    fun cachedCatalogue(): ReportCatalogue? = catalogue

    suspend fun run(key: String, filters: ReportFilters): ReportResult = api.report(key, filters.query())

    suspend fun file(key: String, filters: ReportFilters, format: String): ServerFile =
        api.reportFile(key, filters.query(), format)

    fun remember(key: String, filters: ReportFilters) {
        lastFilters[key] = filters
    }

    fun remembered(key: String): ReportFilters? = lastFilters[key]

    suspend fun targets(month: String): List<TargetRow> = api.targets(month)

    suspend fun setTarget(salesmanId: Int, month: String, amount: Int?): TargetRow =
        api.setTarget(targetBody(salesmanId, month, amount))
}
