package com.salesapp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The eight kinds of people who use the app.
enum class Role(val code: String, val label: String) {
    Admin("admin", "Admin"),
    Management("management", "Management"),
    SalesManager("sales_manager", "Sales manager"),
    Salesman("salesman", "Salesman"),
    Warehouse("warehouse", "Warehouse"),
    Driver("driver", "Driver"),
    Cashier("cashier", "Cashier"),
    Accounts("accounts", "Accounts"),
    ;

    // Admin and management always see all four companies.
    val seesAllCompanies: Boolean get() = this == Admin || this == Management

    companion object {
        fun fromCode(code: String): Role? = entries.firstOrNull { it.code == code }
    }
}

// The four companies, in the order they are shown.
val companyCodes = listOf("LV", "ZT", "ZF", "GL")

fun companyName(code: String): String = when (code) {
    "LV" -> "Lucky Ventures"
    "ZT" -> "Zoona Trading"
    "ZF" -> "Zangafee"
    "GL" -> "Greenland"
    else -> code
}

fun roleLabel(code: String): String = Role.fromCode(code)?.label ?: code

@Serializable
data class User(
    val id: Int,
    val name: String,
    val phone: String,
    val role: String,
    val companies: List<String> = emptyList(),
    val managerId: Int? = null,
)

@Serializable
data class UserRow(
    val id: Int,
    val name: String,
    val phone: String,
    val role: String,
    val companies: List<String> = emptyList(),
    val managerId: Int? = null,
    val managerName: String? = null,
    val active: Boolean = true,
)

@Serializable
data class LoginResponse(val token: String, val user: User)

@Serializable
data class ApiError(val error: String = "", val message: String = "")

@Serializable
data class Company(val code: String, val name: String, val facilityCode: String = "")

@Serializable
data class AppVersion(
    val platform: String = "",
    val versionCode: Int,
    val versionName: String = "",
    val notes: String? = null,
    val downloadUrl: String = "",
    val downloadPath: String = "",
)

@Serializable
data class PhoneCheck(val name: String, val role: String = "")

@Serializable
data class PhoneRequest(val phone: String)

@Serializable
data class LoginRequest(val phone: String, val pin: String)

@Serializable
data class NewUserRequest(
    val name: String,
    val phone: String,
    val role: String,
    val companies: List<String>,
    val managerId: Int? = null,
    val pin: String,
)

@Serializable
data class ResetPinRequest(val pin: String)

@Serializable
data class CustomerCompany(val code: String, val paymentTermsDays: Int = 0)

@Serializable
data class Customer(
    val id: Int,
    val name: String,
    val channel: String = "",
    val area: String = "",
    val phone: String? = null,
    val needsLpo: Boolean = false,
    val cashOnly: Boolean = false,
    val active: Boolean = true,
    // Whether the server says this user may change the customer; null from an older server.
    val canEdit: Boolean? = null,
    val companies: List<CustomerCompany> = emptyList(),
)

// Fields left null are not sent, so a change only touches what was set.
@Serializable
data class CustomerRequest(
    val name: String? = null,
    val channel: String? = null,
    val area: String? = null,
    val phone: String? = null,
    val needsLpo: Boolean? = null,
    val cashOnly: Boolean? = null,
    val companies: List<CustomerCompany>? = null,
    val active: Boolean? = null,
)

@Serializable
data class Product(
    val id: Int,
    val code: String,
    val companyCode: String,
    val brand: String = "",
    val name: String,
    val packSize: String = "",
    val piecesPerCarton: Int = 1,
    val active: Boolean = true,
)

@Serializable
data class ProductRequest(
    val code: String? = null,
    val companyCode: String? = null,
    val brand: String? = null,
    val name: String? = null,
    val packSize: String? = null,
    val piecesPerCarton: Int? = null,
    val active: Boolean? = null,
)

@Serializable
data class Vehicle(
    val id: Int,
    val plate: String,
    val description: String = "",
    val facilityCode: String? = null,
    val defaultDriverId: Int? = null,
    val defaultDriverName: String? = null,
    val active: Boolean = true,
)

// For adding a vehicle. Changing one uses a hand-built JSON object (see vehicleChange) so that
// facilityCode and defaultDriverId can be sent as null to clear them.
@Serializable
data class VehicleRequest(
    val plate: String? = null,
    val description: String? = null,
    val facilityCode: String? = null,
    val defaultDriverId: Int? = null,
    val active: Boolean? = null,
)

// Builds the body for changing a vehicle. Only the fields passed are sent; a passed null is sent
// as null (clears the value). Leave a field out to keep it as it is.
fun vehicleChange(
    plate: String? = null,
    description: String? = null,
    facilityCode: Optional<String?> = Optional.Absent,
    defaultDriverId: Optional<Int?> = Optional.Absent,
    active: Boolean? = null,
): JsonObject = buildJsonObject {
    if (plate != null) put("plate", plate)
    if (description != null) put("description", description)
    if (facilityCode is Optional.Present) put("facilityCode", facilityCode.value)
    if (defaultDriverId is Optional.Present) put("defaultDriverId", defaultDriverId.value)
    if (active != null) put("active", active)
}

// Builds the body for changing a company. Only the fields passed are sent; a passed null is sent
// as null (clears the value). Leave a field Absent to keep it as it is.
fun companyChange(
    name: Optional<String> = Optional.Absent,
    tpin: Optional<String> = Optional.Absent,
    vatRate: Optional<Double?> = Optional.Absent,
    phone: Optional<String> = Optional.Absent,
    email: Optional<String> = Optional.Absent,
    address: Optional<String?> = Optional.Absent,
): JsonObject = buildJsonObject {
    if (name is Optional.Present) put("name", name.value)
    if (tpin is Optional.Present) put("tpin", tpin.value)
    if (vatRate is Optional.Present) put("vatRate", vatRate.value)
    if (phone is Optional.Present) put("phone", phone.value)
    if (email is Optional.Present) put("email", email.value)
    if (address is Optional.Present) put("address", address.value)
}

// A value that is either left out or given (and the given value may be null).
sealed interface Optional<out T> {
    data object Absent : Optional<Nothing>
    data class Present<T>(val value: T) : Optional<T>
}

@Serializable
data class Driver(val id: Int, val name: String, val phone: String = "")

@Serializable
data class CompanyDetails(
    val code: String,
    val name: String,
    val facilityCode: String = "",
    val tpin: String? = null,
    val vatRate: Double? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
)

@Serializable
data class CompanyDetailsRequest(
    val name: String? = null,
    val tpin: String? = null,
    val vatRate: Double? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
)

@Serializable
data class OpeningBalance(
    val customerId: Int,
    val customerName: String = "",
    val companyCode: String,
    val amount: Long,
    val asOf: String,
    val enteredBy: String = "",
    val enteredAt: String = "",
)

@Serializable
data class OpeningBalanceRequest(val companyCode: String, val amount: Long, val asOf: String)

// Stock: what is on the shelves, by product and by batch. Quantities are in pieces; cartons and loosePieces are the same amount split up.
@Serializable
data class StockBatch(
    val id: Int,
    val batchNo: String,
    val expiryDate: String,
    val onHand: Int = 0,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
)

@Serializable
data class StockProduct(
    val productId: Int,
    val code: String = "",
    val companyCode: String,
    val brand: String = "",
    val name: String,
    val packSize: String = "",
    val piecesPerCarton: Int = 1,
    val onHand: Int = 0,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
    val nearestExpiry: String? = null,
    val batches: List<StockBatch> = emptyList(),
)

// daysLeft below zero means the batch has expired.
@Serializable
data class ShortDatedBatch(
    val id: Int,
    val productId: Int,
    val companyCode: String,
    val batchNo: String,
    val expiryDate: String,
    val onHand: Int = 0,
    val piecesPerCarton: Int = 1,
    val code: String = "",
    val name: String,
    val packSize: String = "",
    val daysLeft: Int = 0,
    val expired: Boolean = false,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
)

// kind is "received", "adjusted" or "counted"; reason is set when adjusted.
@Serializable
data class StockMovement(
    val id: Int,
    val change: Int,
    val kind: String,
    val reason: String? = null,
    val note: String = "",
    val userName: String = "",
    val createdAt: String = "",
    val documentNumber: String? = null,
)

@Serializable
data class ReceiptLineRequest(
    val productId: Int,
    val batchNo: String,
    val expiryDate: String,
    val cartons: Int = 0,
    val pieces: Int = 0,
)

@Serializable
data class ReceiptRequest(
    val companyCode: String,
    val supplier: String = "",
    val reference: String = "",
    val receivedOn: String,
    val note: String = "",
    val lines: List<ReceiptLineRequest>,
)

@Serializable
data class ReceiptLine(
    val productId: Int,
    val productCode: String = "",
    val productName: String = "",
    val packSize: String = "",
    val batchNo: String = "",
    val expiryDate: String = "",
    val pieces: Int = 0,
    val piecesPerCarton: Int = 1,
)

@Serializable
data class ReceiptDetail(
    val id: Int,
    val number: String,
    val companyCode: String,
    val supplier: String = "",
    val reference: String = "",
    val receivedOn: String = "",
    val note: String = "",
    val receivedBy: String = "",
    val createdAt: String = "",
    val lines: List<ReceiptLine> = emptyList(),
)

// reason: damaged, expired, lost, found or other. direction ("add" or "remove") is only sent for other.
@Serializable
data class AdjustRequest(
    val batchId: Int,
    val reason: String,
    val direction: String? = null,
    val cartons: Int = 0,
    val pieces: Int = 0,
    val note: String = "",
)

// The new amount on hand after the change.
@Serializable
data class AdjustResult(
    val batchId: Int,
    val change: Int = 0,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
)

@Serializable
data class CountLineRequest(val batchId: Int, val cartons: Int = 0, val pieces: Int = 0)

@Serializable
data class CountRequest(
    val companyCode: String,
    val note: String = "",
    val lines: List<CountLineRequest>,
)

@Serializable
data class CountDifference(
    val batchId: Int,
    val batchNo: String = "",
    val before: Int = 0,
    val counted: Int = 0,
    val change: Int = 0,
)

@Serializable
data class CountResult(
    val id: Int,
    val number: String,
    val differences: List<CountDifference> = emptyList(),
    val linesCounted: Int = 0,
)
