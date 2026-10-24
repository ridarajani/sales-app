package com.salesapp.ui.cash

import com.salesapp.data.CollectCompany
import com.salesapp.data.CollectCustomer
import com.salesapp.data.CollectInfo
import com.salesapp.data.HandoverDetail
import com.salesapp.data.OpenInvoice
import com.salesapp.data.User
import com.salesapp.data.canCollect
import com.salesapp.data.canCountCash
import com.salesapp.data.canRecordExpenses
import com.salesapp.data.canSeePayments
import com.salesapp.data.canSeeWallets
import com.salesapp.data.canSetUpWallets
import com.salesapp.data.holdsCash
import com.salesapp.data.isOfficeCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Who may do what with money, and the few sums the screens work out themselves.
class Phase7RulesTest {
    private fun user(role: String) = User(id = 1, name = "Test", phone = "0991234567", role = role)

    @Test
    fun whoCanRecordAPayment() {
        for (role in listOf("admin", "sales_manager", "salesman", "accounts", "cashier")) {
            assertTrue(user(role).canCollect(), role)
        }
        for (role in listOf("management", "warehouse", "driver")) {
            assertFalse(user(role).canCollect(), role)
        }
    }

    @Test
    fun warehouseAndDriversSeeNoMoney() {
        for (role in listOf("warehouse", "driver")) {
            assertFalse(user(role).canSeePayments(), role)
            assertFalse(user(role).holdsCash(), role)
            assertFalse(user(role).canSeeWallets(), role)
            assertFalse(user(role).canRecordExpenses(), role)
        }
    }

    @Test
    fun onlyThreeRolesRecordExpenses() {
        for (role in listOf("admin", "sales_manager", "salesman")) assertTrue(user(role).canRecordExpenses(), role)
        for (role in listOf("management", "accounts", "cashier")) assertFalse(user(role).canRecordExpenses(), role)
    }

    @Test
    fun theCashierAndAdminCount() {
        assertTrue(user("cashier").canCountCash())
        assertTrue(user("admin").canCountCash())
        assertFalse(user("sales_manager").canCountCash())
    }

    @Test
    fun onlyAdminSetsUpWallets() {
        assertTrue(user("admin").canSetUpWallets())
        for (role in listOf("management", "sales_manager", "cashier", "accounts")) {
            assertTrue(user(role).canSeeWallets(), role)
            assertFalse(user(role).canSetUpWallets(), role)
        }
    }

    // At the office the customer is not there to sign a transfer; in the field they always sign.
    @Test
    fun theOfficeRecordsTransfersWithoutASignature() {
        for (role in listOf("admin", "accounts", "cashier")) assertTrue(user(role).isOfficeCollector(), role)
        for (role in listOf("sales_manager", "salesman")) assertFalse(user(role).isOfficeCollector(), role)
    }

    private val info = CollectInfo(
        customer = CollectCustomer(id = 1, name = "Shop A"),
        companies = listOf(
            CollectCompany(
                code = "LV",
                name = "Lucky Ventures",
                owes = 200_000.0,
                invoices = listOf(OpenInvoice(id = 7, number = "LV-INV-000007", total = 200_000.0, balance = 200_000.0)),
            ),
        ),
        signatureNeeded = false,
    )

    // Cash always needs a signature, even at the office.
    @Test
    fun cashAlwaysNeedsASignature() {
        val office = CollectState(user = user("accounts"), info = info, companyCode = "LV", mode = "cash")
        assertTrue(office.needsSignature)
        assertFalse(office.copy(mode = "bank").needsSignature)
        assertFalse(office.copy(mode = "airtel").needsSignature)
    }

    @Test
    fun inTheFieldEveryWayNeedsASignature() {
        val field = CollectState(
            user = user("salesman"),
            info = info.copy(signatureNeeded = true),
            companyCode = "LV",
            mode = "bank",
        )
        assertTrue(field.needsSignature)
        assertTrue(field.copy(mode = "airtel").needsSignature)
        assertTrue(field.copy(mode = "cash").needsSignature)
    }

    // A bank or Airtel payment needs proof, and Airtel needs its reference; nothing is asked for 0.
    @Test
    fun proofAndReferenceFollowTheWayItWasPaid() {
        val base = CollectState(user = user("salesman"), info = info, companyCode = "LV", amount = 100_000)
        assertFalse(base.copy(mode = "cash").needsProof)
        assertTrue(base.copy(mode = "bank").needsProof)
        assertFalse(base.copy(mode = "bank").needsReference)
        assertTrue(base.copy(mode = "airtel").needsReference)
        // A discount with no money moving asks for neither.
        assertFalse(base.copy(mode = "airtel", amount = null).needsProof)
        assertFalse(base.copy(mode = "airtel", amount = null).needsReference)
    }

    // A discount or withholding tax can only come off an invoice.
    @Test
    fun lessNeedsAnInvoice() {
        val state = CollectState(user = user("salesman"), info = info, companyCode = "LV")
        assertFalse(state.canUseLess)
        assertTrue(state.copy(invoiceId = 7).canUseLess)
    }

    @Test
    fun theTotalCountsTheDiscountAndTax() {
        val state = CollectState(amount = 90_000, discount = 5_000, wht = 5_000)
        assertEquals(100_000L, state.total)
    }

    // The count is compared with what the app expects, before anything is sent.
    @Test
    fun theDifferenceIsTheCountLessWhatIsExpected() {
        val handover = HandoverDetail(id = 1, number = "HO-000001", net = 100_000.0)
        assertEquals(0L, HandoverDetailState(handover = handover, counted = 100_000).difference)
        assertEquals(-5_000L, HandoverDetailState(handover = handover, counted = 95_000).difference)
        assertEquals(2_000L, HandoverDetailState(handover = handover, counted = 102_000).difference)
        assertNull(HandoverDetailState(handover = handover).difference)
    }
}
