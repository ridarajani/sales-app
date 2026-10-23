package com.salesapp.ui.orders

import com.salesapp.data.OrderDetail
import com.salesapp.data.OrderHistoryEntry
import com.salesapp.data.User
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderDetailHistoryTest {
    private val history = listOf(
        OrderHistoryEntry(title = "Booked", detail = "SO-000001"),
        OrderHistoryEntry(title = "Stock reserved", detail = "Not all of it is in stock. The rest waits for new stock."),
        OrderHistoryEntry(title = "Put on hold", detail = "Cannot be loaded until released. Stock stays reserved. Shop is closed"),
        OrderHistoryEntry(title = "Hold released", detail = "Can be loaded again. Not all of it is in stock."),
        OrderHistoryEntry(title = "Cancelled", detail = "Reserved stock released."),
        OrderHistoryEntry(title = "Closed", detail = "The rest of the order will not be delivered. Its reserved stock was released."),
    )

    private fun stateFor(role: String) = OrderDetailState(
        loading = false,
        order = OrderDetail(id = 1, history = history),
        user = User(id = 7, name = "X", phone = "", role = role),
    )

    @Test
    fun salesmanSeesNoStockWordsInHistory() {
        val shown = stateFor("salesman").history
        assertEquals(listOf("Booked", "Put on hold", "Hold released", "Cancelled", "Closed"), shown.map { it.title })
        assertEquals(
            listOf("SO-000001", "Cannot be loaded until released. Shop is closed", "Can be loaded again.", "", "The rest of the order will not be delivered."),
            shown.map { it.detail },
        )
    }

    @Test
    fun managerSeesHistoryAsSent() {
        assertEquals(history, stateFor("sales_manager").history)
    }
}
