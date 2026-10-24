package com.salesapp.ui.returns

import com.salesapp.data.InvoiceLine
import com.salesapp.data.NOTE_KINDS
import com.salesapp.data.ReturnDetail
import com.salesapp.data.ReturnLine
import com.salesapp.data.User
import com.salesapp.data.canAdjustInvoices
import com.salesapp.data.canCancelPayments
import com.salesapp.data.canReceiveReturns
import com.salesapp.data.canSeeLedger
import com.salesapp.data.canSeeReturns
import com.salesapp.data.noteMakesThemOweMore
import com.salesapp.data.noteNeedsTheirNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Who may change what a customer owes, and the sums the return and note forms work out themselves.
class Phase8RulesTest {
    private fun user(role: String) = User(id = 1, name = "Test", phone = "0991234567", role = role)

    @Test
    fun onlyTheSalesTeamAndAccountsAdjustAnInvoice() {
        for (role in listOf("admin", "management", "sales_manager", "accounts")) {
            assertTrue(user(role).canAdjustInvoices(), role)
        }
        for (role in listOf("salesman", "cashier", "warehouse", "driver")) {
            assertFalse(user(role).canAdjustInvoices(), role)
        }
    }

    // The warehouse sees returns to take the goods back, but never their money.
    @Test
    fun theWarehouseSeesReturnsButNotTheirMoney() {
        assertTrue(user("warehouse").canSeeReturns())
        assertTrue(user("warehouse").canReceiveReturns())
        assertFalse(user("warehouse").canAdjustInvoices())
        assertFalse(user("warehouse").canSeeLedger())
    }

    @Test
    fun onlyTheWarehouseAndAdminTakeGoodsBack() {
        assertTrue(user("admin").canReceiveReturns())
        for (role in listOf("management", "sales_manager", "accounts", "cashier")) {
            assertFalse(user(role).canReceiveReturns(), role)
        }
    }

    @Test
    fun driversSeeNeitherReturnsNorLedgers() {
        assertFalse(user("driver").canSeeReturns())
        assertFalse(user("driver").canSeeLedger())
    }

    @Test
    fun onlyAccountsAndAdminCancelAPayment() {
        assertTrue(user("accounts").canCancelPayments())
        assertTrue(user("admin").canCancelPayments())
        for (role in listOf("management", "sales_manager", "salesman", "cashier")) {
            assertFalse(user(role).canCancelPayments(), role)
        }
    }

    // A return without money is what the warehouse gets; the screen hides the money rows then.
    @Test
    fun aReturnWithoutMoneyShowsNone() {
        val base = ReturnDetail(id = 1, number = "LV-SR-000001", subtotal = 90_000.0, vat = 15_750.0, total = 105_750.0)
        assertTrue(base.showsMoney)
        assertFalse(base.copy(subtotal = null, vat = null, total = null).showsMoney)
    }

    private fun line(canReturn: Int?, perCarton: Int = 12) =
        InvoiceLine(lineId = 11, productId = 1, piecesPerCarton = perCarton, pieces = 48, canReturn = canReturn)

    @Test
    fun whatComesBackIsCartonsPlusLoosePieces() {
        val entry = ReturnLineEntry(line(canReturn = 48), cartons = 2, pieces = 3)
        assertEquals(27, entry.wanted)
        assertEquals(48, entry.most)
        assertFalse(entry.tooMuch)
    }

    @Test
    fun moreThanCameBackIsRefused() {
        val entry = ReturnLineEntry(line(canReturn = 12), cartons = 2, pieces = 0)
        assertEquals(24, entry.wanted)
        assertTrue(entry.tooMuch)
    }

    // Until a drop is confirmed the server cannot say what may come back.
    @Test
    fun anInvoiceThatIsNotDeliveredCannotHaveAReturn() {
        val notYet = NewReturnState(
            loading = false,
            invoice = com.salesapp.data.InvoiceDetail(id = 3, lines = listOf(line(canReturn = null), line(canReturn = null))),
        )
        assertTrue(notYet.notDeliveredYet)
        val delivered = notYet.copy(invoice = notYet.invoice?.copy(lines = listOf(line(canReturn = 48))))
        assertFalse(delivered.notDeliveredYet)
    }

    @Test
    fun nothingTypedIsNotWorthSending() {
        val state = NewReturnState(loading = false, lines = listOf(ReturnLineEntry(line(canReturn = 48))))
        assertFalse(state.anything)
        assertTrue(state.copy(lines = listOf(ReturnLineEntry(line(canReturn = 48), cartons = 1))).anything)
    }

    // Only a note the customer sent us carries their own number.
    @Test
    fun receivedNotesNeedTheirNumber() {
        assertTrue(noteNeedsTheirNumber("debit_received"))
        assertTrue(noteNeedsTheirNumber("credit_received"))
        assertFalse(noteNeedsTheirNumber("credit_issued"))
        assertFalse(noteNeedsTheirNumber("debit_issued"))
    }

    // Two of the four make the customer owe more; the form says so under each choice.
    @Test
    fun twoKindsMakeThemOweMore() {
        assertEquals(listOf("debit_issued", "credit_received"), NOTE_KINDS.filter { noteMakesThemOweMore(it) })
        assertEquals(listOf("credit_issued", "debit_received"), NOTE_KINDS.filterNot { noteMakesThemOweMore(it) })
    }

    @Test
    fun theNoteFormAsksForTheirNumberOnlyWhenItIsTheirs() {
        val state = NewNoteState(loading = false, kind = "credit_issued")
        assertFalse(state.needsTheirNumber)
        assertTrue(state.copy(kind = "debit_received").needsTheirNumber)
    }

    // Everything comes back good unless the warehouse ticks it as damaged.
    @Test
    fun receivingStartsWithEverythingGood() {
        val saved = ReturnDetail(
            id = 1,
            number = "LV-SR-000001",
            canReceive = true,
            lines = listOf(ReturnLine(id = 1, pieces = 12), ReturnLine(id = 2, pieces = 6)),
        )
        val state = ReturnDetailState(loading = false, salesReturn = saved, receiving = true)
        assertTrue(state.damaged.isEmpty())
        assertEquals(setOf(2), state.copy(damaged = setOf(2)).damaged)
    }
}
