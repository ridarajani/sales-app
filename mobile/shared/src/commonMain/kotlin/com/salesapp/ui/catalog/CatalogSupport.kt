package com.salesapp.ui.catalog

import androidx.compose.runtime.Composable
import com.salesapp.data.Role
import com.salesapp.data.User
import com.salesapp.data.companyCodes
import com.salesapp.resources.Res
import com.salesapp.resources.facility_1
import com.salesapp.resources.facility_2
import com.salesapp.resources.facility_none
import org.jetbrains.compose.resources.stringResource

// The companies this user may see, in the usual order.
fun companiesOf(user: User?): List<String> {
    if (user == null) return emptyList()
    val all = Role.fromCode(user.role)?.seesAllCompanies == true
    return if (all) companyCodes else companyCodes.filter { it in user.companies }
}

@Composable
fun facilityLabel(code: String?): String = when (code) {
    "F1" -> stringResource(Res.string.facility_1)
    "F2" -> stringResource(Res.string.facility_2)
    else -> stringResource(Res.string.facility_none)
}
