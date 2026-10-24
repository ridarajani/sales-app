package com.salesapp.data

// Where the returns and notes screens get their data.
class ReturnsRepository(private val api: Api, private val app: AppState) {
    // status: to_book, waiting, done or all.
    suspend fun list(status: String, query: String?): List<ReturnListItem> =
        api.returns(status, query?.trim()?.takeIf { it.isNotBlank() })

    suspend fun detail(id: Int): ReturnDetail = api.salesReturn(id)

    suspend fun book(id: Int): ReturnDetail = api.bookReturn(id)

    suspend fun receive(id: Int, damaged: List<Int>): ReturnDetail = api.receiveReturn(id, ReceiveRequest(damaged))

    suspend fun addReturn(invoiceId: Int, request: ReturnRequest): ReturnDetail = api.addReturn(invoiceId, request)

    suspend fun addNote(invoiceId: Int, request: NoteRequest): InvoiceNoteDetail = api.addNote(invoiceId, request)

    // How many returns wait for the warehouse to take the goods back.
    suspend fun waitingTotal(): Int = list("waiting", null).size
}
