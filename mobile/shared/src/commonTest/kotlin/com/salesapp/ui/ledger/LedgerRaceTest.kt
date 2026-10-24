package com.salesapp.ui.ledger

import com.salesapp.core.Documents
import com.salesapp.core.todayIsoDate
import com.salesapp.core.KeyValueStore
import com.salesapp.data.AppState
import com.salesapp.data.Company
import com.salesapp.data.Customer
import com.salesapp.data.CustomerCompany
import com.salesapp.data.Ledger
import com.salesapp.data.LedgerCompany
import com.salesapp.data.LedgerCustomer
import com.salesapp.data.LedgerRow
import com.salesapp.data.LedgerSource
import com.salesapp.data.ServerFile
import com.salesapp.data.SessionStore
import com.salesapp.data.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// Tapping one company and then another used to leave the chips on the second while the rows and
// balance were the first's, because the slower earlier answer landed last.
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerRaceTest {
    private val customer = Customer(
        id = 1,
        name = "Area 47 Superstore",
        area = "Area 47",
        companies = listOf(CustomerCompany(code = "LV"), CustomerCompany(code = "ZT")),
    )

    // Every later answer comes back quicker than the one before it, so an earlier answer always
    // lands last unless its job was cancelled. It echoes back what it was asked for.
    private class SlowFirstLedger(private val customer: Customer) : LedgerSource {
        var calls = 0
            private set

        override suspend fun ledger(customerId: Int, company: String, from: String?): Ledger {
            calls++
            delay(1000L / calls)
            return Ledger(
                customer = LedgerCustomer(id = customerId, name = customer.name),
                company = LedgerCompany(code = company, name = company),
                from = from,
                rows = listOf(LedgerRow(date = "2026-10-24", document = "$company-INV-000001", what = "Invoice", charged = 1.0, balance = 1.0)),
                owes = if (company == "LV") 100_000.0 else 200_000.0,
            )
        }

        override suspend fun statement(customerId: Int, company: String, from: String?): ServerFile =
            ServerFile(ByteArray(0), "application/pdf")

        override suspend fun customers(query: String?): List<Customer> = listOf(customer)

        override suspend fun customer(id: Int): Customer = customer

        override suspend fun companiesOf(customer: Customer): List<Company> =
            listOf(Company("LV", "Lucky Ventures", "F1"), Company("ZT", "Zoona Trading", "F1"))
    }

    private fun appState(): AppState {
        val store = KeyValueStore()
        return AppState(store, SessionStore(store)).apply {
            user = User(id = 1, name = "Thoko Mwale", phone = "0991234567", role = "accounts", companies = listOf("LV", "ZT"))
        }
    }

    @Test
    fun theCompanyTappedLastIsTheOneShown() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = SlowFirstLedger(customer)
            val model = LedgerViewModel(customerId = 1, companyCode = "LV", repository, Documents(), appState())

            // The first ledger (LV) is still on its way.
            advanceTimeBy(50)
            assertEquals(null, model.state.value.ledger)

            // The shop's other company is tapped while LV is still loading.
            model.onEvent(LedgerEvent.CompanyChosen("ZT"))
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(2, repository.calls)
            assertEquals("ZT", state.companyCode)
            // Without cancelling the first job, LV's slower answer landed here last.
            assertEquals("ZT", state.ledger?.company?.code)
            assertEquals(200_000.0, state.ledger?.owes)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun thePeriodTappedLastIsTheOneShown() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = SlowFirstLedger(customer)
            val model = LedgerViewModel(customerId = 1, companyCode = "ZT", repository, Documents(), appState())
            advanceUntilIdle()

            // Last 30 days, then This month before the first answer is back.
            model.onEvent(LedgerEvent.PeriodChosen(LedgerPeriod.Last30))
            advanceTimeBy(5)
            model.onEvent(LedgerEvent.PeriodChosen(LedgerPeriod.ThisMonth))
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(LedgerPeriod.ThisMonth, state.period)
            // This month asks from the 1st; Last 30 days asks from 29 days back. The chip and the
            // rows have to agree, so the answer kept must be the one This month asked for.
            assertEquals(todayIsoDate().take(7) + "-01", state.ledger?.from)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Going back to the search must not let a ledger still on its way reopen the card.
    @Test
    fun goingBackToTheSearchDropsAnAnswerStillOnItsWay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = SlowFirstLedger(customer)
            val model = LedgerViewModel(customerId = 1, companyCode = "LV", repository, Documents(), appState())
            advanceTimeBy(50)

            model.onEvent(LedgerEvent.ChangeCustomer)
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(null, state.customer)
            assertEquals(null, state.ledger)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
