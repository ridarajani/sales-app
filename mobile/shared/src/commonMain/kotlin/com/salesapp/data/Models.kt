package com.salesapp.data

import kotlinx.serialization.Serializable

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
