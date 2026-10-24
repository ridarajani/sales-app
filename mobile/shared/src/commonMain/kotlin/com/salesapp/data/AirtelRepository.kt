package com.salesapp.data

// Where the Airtel Money screens get their data.
class AirtelRepository(private val api: Api, private val app: AppState) {
    suspend fun wallets(): List<WalletListItem> = api.wallets()

    suspend fun wallet(id: Int): WalletDetail = api.wallet(id)

    suspend fun tick(entryId: Int): WalletDetail = api.tickEntry(entryId)

    suspend fun withdraw(walletId: Int, request: WithdrawRequest): WalletDetail = api.withdraw(walletId, request)

    suspend fun check(walletId: Int, request: WalletCheckRequest): WalletDetail = api.checkWallet(walletId, request)

    suspend fun create(request: WalletRequest): WalletDetail = api.createWallet(request)

    suspend fun update(id: Int, request: WalletRequest): WalletDetail = api.updateWallet(id, request)

    // Admin picks the holder from the sales managers.
    suspend fun possibleHolders(): List<UserRow> = api.users().filter { it.active && (it.role == "sales_manager" || it.role == "admin") }

    // How many payments across every wallet this user can see are waiting to be ticked.
    suspend fun waitingTotal(): Int = wallets().sumOf { it.waiting }
}
