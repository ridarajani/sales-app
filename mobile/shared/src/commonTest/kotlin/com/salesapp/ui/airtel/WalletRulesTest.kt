package com.salesapp.ui.airtel

import com.salesapp.data.WalletDetail
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Admin can tick a payment and check the balance for a holder, but the cash itself only ever goes
// into the holder's own hand, so only they may take it out.
class WalletRulesTest {
    private fun wallet(canAct: Boolean, canTakeOut: Boolean) =
        WalletDetail(id = 1, holderId = 3, holderName = "Mr. Abbas", canAct = canAct, canTakeOut = canTakeOut)

    @Test
    fun theHolderMayDoEverything() {
        val state = WalletDetailState(loading = false, wallet = wallet(canAct = true, canTakeOut = true))
        assertTrue(state.canAct)
        assertTrue(state.canTakeOut)
    }

    @Test
    fun adminTicksAndChecksButDoesNotTakeCashOut() {
        val state = WalletDetailState(loading = false, wallet = wallet(canAct = true, canTakeOut = false))
        assertTrue(state.canAct)
        assertFalse(state.canTakeOut)
    }

    @Test
    fun aWatcherMayDoNeither() {
        val state = WalletDetailState(loading = false, wallet = wallet(canAct = false, canTakeOut = false))
        assertFalse(state.canAct)
        assertFalse(state.canTakeOut)
    }

    @Test
    fun withNoWalletYetNothingIsAllowed() {
        val state = WalletDetailState()
        assertFalse(state.canAct)
        assertFalse(state.canTakeOut)
    }
}
