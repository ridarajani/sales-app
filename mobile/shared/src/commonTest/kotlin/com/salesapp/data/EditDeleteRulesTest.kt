package com.salesapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Client answers of 24 October: who may change or delete what, and which vehicles a picker shows.
class EditDeleteRulesTest {
    private fun user(role: String, id: Int = 1, companies: List<String> = listOf("LV", "ZT")) =
        User(id = id, name = "Test", phone = "0881000000", role = role, companies = companies)

    private fun salesman(id: Int, managerId: Int?, managers: List<UserManager> = emptyList()) =
        UserRow(id = id, name = "Test", phone = "0881000001", role = "salesman", companies = listOf("LV"),
            managerId = managerId, managers = managers)

    @Test
    fun onlyAdminAndAccountsDeleteProducts() {
        assertTrue(user("admin").canDeleteProducts())
        assertTrue(user("accounts").canDeleteProducts())
        assertFalse(user("sales_manager").canDeleteProducts())
        assertFalse(user("management").canDeleteProducts())
    }

    @Test
    fun salesManagersKeepTheVehicles() {
        assertTrue(user("admin").canDeleteVehicles())
        assertTrue(user("sales_manager").canDeleteVehicles())
        assertFalse(user("warehouse").canDeleteVehicles())
        assertFalse(user("management").canDeleteVehicles())
    }

    @Test
    fun aSalesManagerOnlyChangesTheirOwnSalesmen() {
        val me = user("sales_manager", id = 7)
        assertTrue(me.canManageUser(salesman(20, managerId = 7)))
        assertTrue(me.canManageUser(salesman(21, managerId = null, managers = listOf(UserManager("LV", 7)))))
        // Someone else's salesman, and anyone who is not a salesman.
        assertFalse(me.canManageUser(salesman(22, managerId = 8)))
        assertFalse(me.canManageUser(UserRow(23, "Test", "0881000002", "driver")))
    }

    @Test
    fun anAdminChangesAnyoneAndOthersChangeNobody() {
        assertTrue(user("admin").canManageUser(salesman(20, managerId = 8)))
        assertFalse(user("management").canManageUser(salesman(20, managerId = 8)))
        assertFalse(user("salesman", id = 20).canManageUser(salesman(20, managerId = 8)))
    }

    @Test
    fun onlyAnAdminChangesWhatJobSomeoneDoes() {
        assertTrue(user("admin").canChangeUserRole())
        assertFalse(user("sales_manager").canChangeUserRole())
    }

    @Test
    fun aVehicleIsInUseWhenItIsOnAndNotDormant() {
        val vehicle = Vehicle(1, "XX 0001", "", null, null, null)
        assertTrue(vehicle.inUse)
        assertFalse(vehicle.copy(dormant = true).inUse)
        assertFalse(vehicle.copy(active = false).inUse)
    }

    // A picker shows the ones in use, plus the one already chosen even if it was put away.
    @Test
    fun aPickerShowsVehiclesInUseAndTheChosenOne() {
        val vehicles = listOf(
            Vehicle(1, "XX 0001", "", null, null, null),
            Vehicle(2, "XX 0002", "", null, null, null, dormant = true),
            Vehicle(3, "XX 0003", "", null, null, null, active = false),
        )
        assertEquals(listOf(1), vehicles.filter { it.inUse }.map { it.id })
        assertEquals(listOf(1, 2), vehicles.filter { it.inUse || it.id == 2 }.map { it.id })
    }
}
